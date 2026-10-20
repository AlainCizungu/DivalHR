import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type Locator,
  type Page,
} from '@playwright/test';
import { mkdirSync, readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import {
  CORE_API,
  acceptInvitation,
  USERS,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  sql,
  subjectLock,
  typeSecret,
} from './support';

// MVP-092 (Issue #149): versioning, locking and exporting worked-time inputs with the real identity
// provider, database and server clock. Setup over the API as the MVP-090A journey (the
// administrator's existing SSO session; the three employees' accounts are set up once through the
// API-driven invitation, never asserted as a journey): two legal entities with one site each; both
// weeks approved and locked; the payroll inputs of both generated as version 1. Exactly two journeys:
//  - English: the first week was unlocked and locked again, so its upstream evidence changed;
//    "Payroll preparation" offers the replacement only then; the administrator supersedes version 1
//    with a reason, locks version 2 after the preview and its confirmation, creates the export and
//    downloads the CSV and the manifest, whose SHA-256 and content are verified byte for byte;
//  - French : un verrouillage dont l’aperçu devient périmé (semaine source déverrouillée), un
//    blocage « semaine source non verrouillée », puis « preuve en amont modifiée » ; la version 2
//    corrige ; l’ancienne version est bloquée (pas la dernière) ; la dernière est verrouillée et
//    exportée, avec l’avis sur le salaire mensuel dans chaque zone.
// Fail-closed paths, staleness, idempotency, concurrency, tenant isolation, audits, events, the
// V38 constraints and the CSV and manifest rules are proven at domain, API and database level, not
// here. No Playwright retry; no rate limit is raised.

// Tenant B's administrator (its own rate-limit buckets).
const TENANT_B = '00000000-0000-4000-8000-00000000000b';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY_ONE = `XD-${stamp}`;
const ENTITY_TWO = `XE-${stamp}`;
const SITE_ONE = `XF-${stamp}`;
const SITE_TWO = `XG-${stamp}`;
const DEPARTMENT_ONE = `XH-${stamp}`;
const DEPARTMENT_TWO = `XI-${stamp}`;
const ANA = `TQA-${stamp}-1`;
const CLEO = `TQC-${stamp}-2`;
const MIA = `TQM-${stamp}-3`;
const ANA_FAMILY = `Lukusa${letters}`;
const CLEO_FAMILY = `Mbala${letters}`;
const MIA_FAMILY = `Kitenge${letters}`;
const ANA_EMAIL = `e2e.lc.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.lc.cleo.${lower}@example.test`;
const MIA_EMAIL = `e2e.lc.mia.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Lcy!`;
const ENTITY_ONE_NAME = `Entité Version Nord ${stamp}`;
const ENTITY_TWO_NAME = `Entité Version Sud ${stamp}`;
const ANA_EN = `Bay shift ${letters}`;
const ANA_FR = `Quart de la baie ${letters}`;
const CLEO_EN = `Yard shift ${letters}`;
const CLEO_FR = `Quart de la cour ${letters}`;
const EXPLANATION = 'The badge reader at the dock was offline during this shift.';

let ZONE = 'Africa/Lubumbashi';
let monday = '';
let day = '';
let entityOne = '';
let entityTwo = '';
let siteOne = '';
let siteTwo = '';
let generationOne = '';
let generationTwo = '';
let lockActionOne = '';
let lockActionTwo = '';

/** PR evidence (EVIDENCE=1 only): a screenshot written outside the working tree. */
async function evidence(page: Page, name: string, region?: Locator) {
  const out = process.env.EVIDENCE_DIR;
  if (!process.env.EVIDENCE || !out) return;
  mkdirSync(out, { recursive: true });
  await page.waitForLoadState('networkidle');
  const path = join(out, `${name}.png`);
  if (region) await region.screenshot({ path, animations: 'disabled' });
  else await page.screenshot({ path, fullPage: true, animations: 'disabled' });
}

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function employeeId(number: string): string {
  return sql(
    `SELECT id FROM people.employee WHERE tenant_id = '${TENANT_B}' AND employee_number = '${number}'`,
  );
}

/** Lubumbashi, unless it is in the first hour of its day; then Kinshasa (an hour behind). */
function chooseZone(): string {
  return sql(
    `SELECT CASE WHEN (now() AT TIME ZONE 'Africa/Lubumbashi')::time >= time '01:00'
     THEN 'Africa/Lubumbashi' ELSE 'Africa/Kinshasa' END`,
  );
}

/** Site-local date-times at each offset (minutes) from one reading of the server's clock. */
function localTimes(offsets: number[]): string[] {
  const columns = offsets
    .map(
      (minutes) =>
        `to_char((n + interval '${minutes} minutes') AT TIME ZONE '${ZONE}', 'YYYY-MM-DD"T"HH24:MI')`,
    )
    .join(" || '|' || ");
  return sql(`SELECT ${columns} FROM (SELECT date_trunc('minute', now()) AS n) t`).split('|');
}

function slotInstant(labelEn: string, column: 'start_at' | 'end_at'): string {
  return sql(
    `SELECT to_char(${column} AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"+00:00"')
     FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_B}' AND label_en = '${labelEn}'`,
  );
}

function slotId(labelEn: string): string {
  return sql(
    `SELECT id FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_B}'
     AND label_en = '${labelEn}'`,
  );
}

function timesheetOf(number: string): string {
  return sql(
    `SELECT id FROM people.timesheet WHERE tenant_id = '${TENANT_B}'
     AND employee_id = '${employeeId(number)}'`,
  );
}

/** The payroll-input runs of a legal entity: version, status and counts, newest first. */
function runsOf(entity: string): string {
  return sql(
    `SELECT string_agg(version || ':' || status || ':' || employee_count || ':' || row_count || ':'
       || worked_minutes, ',' ORDER BY version DESC)
     FROM payroll.input_run WHERE tenant_id = '${TENANT_B}' AND legal_entity_id = '${entity}'`,
  );
}

async function storage(page: Page): Promise<string> {
  return page.evaluate(() => {
    const values: string[] = [];
    for (const store of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < store.length; index++) {
        const key = store.key(index) ?? '';
        values.push(key, store.getItem(key) ?? '');
      }
    }
    return values.join('\n');
  });
}

async function noOverflow(page: Page) {
  for (const viewport of [
    { width: 390, height: 800 },
    { width: 320, height: 720 },
    // 200 % zoom of a 1280 px window.
    { width: 640, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      `no horizontal overflow at ${viewport.width} px`,
    ).toBe(true);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** axe at the top of the page (the sticky top bar would otherwise cover the last control). */
async function accessible(page: Page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
  });
  await expectAccessible(page);
}

/**
 * A command over the API as tenant B's administrator, who also runs the MVP-070D, MVP-070E and
 * MVP-071 journeys: a setup call refused by the per-subject limit (429) waits for the window and
 * is sent again (with the same key when it has one).
 */
async function post(
  page: Page,
  bearer: () => string,
  path: string,
  key: string | null,
  data?: object,
) {
  let response: APIResponse | undefined;
  for (let attempt = 0; attempt < 4; attempt++) {
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key === null ? {} : { 'Idempotency-Key': `e2e-lc-${key}-${stamp}-0001` }),
      },
      ...(data === undefined ? {} : { data }),
    });
    if (response.status() !== 429) return response;
    await page.waitForTimeout(
      1000 * Math.min(60, Number(response.headers()['retry-after'] ?? '20') || 20),
    );
  }
  return response as APIResponse;
}

/** One legal entity with one site and one department, over the API. */
async function entityWithSite(
  page: Page,
  bearer: () => string,
  codes: { entity: string; site: string; department: string },
  name: string,
  key: string,
) {
  const entity = await post(page, bearer, '/legal-entities', `le-${key}`, {
    code: codes.entity,
    name,
    countryCode: 'CD',
    effectiveFrom: '2026-01-01',
  });
  expect(entity.status()).toBe(201);
  const entityId = ((await entity.json()) as { id: string }).id;
  const site = await post(page, bearer, '/sites', `st-${key}`, {
    legalEntityId: entityId,
    code: codes.site,
    name: `Site ${name}`,
    timezone: ZONE,
    effectiveFrom: '2026-01-01',
  });
  expect(site.status()).toBe(201);
  const siteId = ((await site.json()) as { id: string }).id;
  expect(
    (
      await post(page, bearer, '/departments', `dp-${key}`, {
        siteId,
        code: codes.department,
        name: `Exploitation ${codes.department}`,
        effectiveFrom: '2026-01-01',
      })
    ).status(),
  ).toBe(201);
  return { entityId, siteId };
}

/** One published schedule of one ended shift today at a site, over the API. */
async function publishShift(
  page: Page,
  bearer: () => string,
  siteId: string,
  slot: { start: string; end: string; en: string; fr: string; employee: string },
  key: string,
) {
  const proposal = {
    siteId,
    firstDate: day,
    lastDate: day,
    slots: [
      {
        clientRef: key,
        start: slot.start,
        end: slot.end,
        labels: { en: slot.en, fr: slot.fr },
        employeeId: slot.employee,
      },
    ],
  };
  const preview = await post(page, bearer, '/work-schedules/preview', null, proposal);
  expect(preview.status()).toBe(200);
  const digest = ((await preview.json()) as { previewDigest: string }).previewDigest;
  expect(
    (
      await post(page, bearer, '/work-schedules', `pub-${key}`, {
        ...proposal,
        previewDigest: digest,
        confirm: true,
      })
    ).status(),
  ).toBe(201);
}

/** An administrator's correction creating a shift's missing record (MVP-062), over the API. */
async function createMissing(
  page: Page,
  bearer: () => string,
  labelEn: string,
  number: string,
  key: string,
) {
  const proposal = {
    slotId: slotId(labelEn),
    employeeId: employeeId(number),
    checkedInAt: slotInstant(labelEn, 'start_at'),
    checkedOutAt: slotInstant(labelEn, 'end_at'),
    reasonCode: 'DEVICE_OR_CONNECTIVITY',
    explanation: EXPLANATION,
  };
  const preview = await post(
    page,
    bearer,
    '/attendance/corrections/missing-record-preview',
    null,
    proposal,
  );
  expect(preview.status()).toBe(200);
  const reviewed = (await preview.json()) as { targetVersion: number; previewDigest: string };
  const commit = await post(page, bearer, '/attendance/corrections/missing-record', key, {
    ...proposal,
    targetVersion: reviewed.targetVersion,
    previewDigest: reviewed.previewDigest,
    confirm: true,
  });
  expect(commit.status()).toBe(201);
}

/** Makes `manager` the report's manager from `from` on (MVP-051), over the API. */
async function manage(
  page: Page,
  bearer: () => string,
  report: string,
  manager: string,
  from: string,
  key: string,
) {
  const change = { type: 'CHANGE', effectiveFrom: from, manager: { employeeId: manager } };
  const preview = await post(
    page,
    bearer,
    `/employees/${report}/employment-changes/preview`,
    null,
    change,
  );
  expect(preview.status()).toBe(200);
  const previewed = (await preview.json()) as {
    expectedVersion: number;
    previewDigest: string;
    requiresAcknowledgement: boolean;
  };
  expect(
    (
      await post(page, bearer, `/employees/${report}/employment-changes`, key, {
        ...change,
        expectedVersion: previewed.expectedVersion,
        previewDigest: previewed.previewDigest,
        acknowledgeRetroactive: previewed.requiresAcknowledgement,
      })
    ).status(),
  ).toBe(201);
}

/** Generates a site's current week (MVP-070A), over the API; the generation's id. */
async function generateWeek(page: Page, bearer: () => string, siteId: string, key: string) {
  const generated = await post(page, bearer, '/timesheets/generations', `gen-${key}`, {
    siteId,
    weekStart: monday,
    confirm: true,
  });
  expect(generated.status()).toBe(200);
  return ((await generated.json()) as { generation: { generationId: string } }).generation
    .generationId;
}

/**
 * Invites an employee, accepts, sets the password and links the access to the employee record: the
 * MVP-071 account setup, over the API apart from Keycloak's one-time password page, which is opened
 * in a closed context and never asserted (the invitation journeys are invitations.spec.ts).
 */
async function inviteAndLink(
  page: Page,
  browser: Browser,
  bearer: () => string,
  email: string,
  employee: string,
  key: string,
) {
  expect(
    (
      await post(page, bearer, '/invitations', `inv-${key}`, {
        email,
        role: 'employee',
        locale: 'fr',
      })
    ).status(),
  ).toBe(201);
  const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
    await mailTo(email, /Invitation à rejoindre/u),
  );
  expect(link, 'invitation link').not.toBeNull();
  expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
  const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
    await mailTo(email, /mot de passe|password|actions|compte|account/iu),
  );
  expect(setup, 'password setup link').not.toBeNull();
  const context = await browser.newContext({ locale: 'en-US' });
  const setupPage = await context.newPage();
  await openSecretLink(setupPage, setup?.[0] ?? '');
  const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
  if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
    await proceed.click();
  }
  await typeSecret(setupPage, '#password-new', PASSWORD);
  await typeSecret(setupPage, '#password-confirm', PASSWORD);
  await setupPage.locator('[type="submit"]').first().click();
  await context.close();

  const lookup = await page.request.post(`${CORE_API}/employees/${employee}/access-link/lookup`, {
    headers: { Authorization: bearer() },
    data: { email },
  });
  expect(lookup.status()).toBe(200);
  const membershipId = ((await lookup.json()) as { membershipId: string }).membershipId;
  expect(
    (
      await post(page, bearer, `/employees/${employee}/access-link`, `link-${key}`, {
        membershipId,
      })
    ).status(),
  ).toBe(201);
}

/** A signed-in employee with the in-memory bearer of their session. */
async function signedIn(browser: Browser, email: string) {
  const context = await browser.newContext({ locale: 'en-US' });
  const page = await context.newPage();
  const bearer = await signIn(page, [email, PASSWORD], 'en', undefined);
  return { page, bearer, close: () => context.close() };
}

/** The employee submits their reviewed timesheet and Mia approves it, over the API. */
async function approvedOverApi(browser: Browser, email: string, sheet: string) {
  const own = await signedIn(browser, email);
  const read = await own.page.request.get(`${CORE_API}/me/timesheets/${sheet}`, {
    headers: { Authorization: own.bearer() },
  });
  expect(read.status()).toBe(200);
  const mine = (await read.json()) as { revision: number; generationVersion: number };
  expect(
    (
      await post(own.page, own.bearer, `/me/timesheets/${sheet}/submissions`, `sub-${sheet}`, {
        revision: mine.revision,
        generationVersion: mine.generationVersion,
        confirm: true,
      })
    ).status(),
  ).toBe(200);
  await own.close();
  const mia = await signedIn(browser, MIA_EMAIL);
  const reviewed = await mia.page.request.get(`${CORE_API}/me/time-approvals/${sheet}`, {
    headers: { Authorization: mia.bearer() },
  });
  expect(reviewed.status()).toBe(200);
  const submission = (await reviewed.json()) as {
    revision: number;
    generationVersion: number;
    cycle: number;
    submissionActionId: string;
  };
  expect(
    (
      await post(mia.page, mia.bearer, `/me/time-approvals/${sheet}/approval`, `apv-${sheet}`, {
        revision: submission.revision,
        generationVersion: submission.generationVersion,
        cycle: submission.cycle,
        submissionActionId: submission.submissionActionId,
        confirm: true,
      })
    ).status(),
  ).toBe(200);
  await mia.close();
}

/** Locks a fully approved week (MVP-071) over the API; the LOCKED action's id. */
async function lockWeek(
  page: Page,
  bearer: () => string,
  entityId: string,
  siteId: string,
  generationId: string,
  key: string,
): Promise<string> {
  const scope = { legalEntityId: entityId, siteId, weekStart: monday };
  const preview = await post(page, bearer, '/timesheet-periods/lock-preview', null, scope);
  expect(preview.status()).toBe(200);
  const reviewed = (await preview.json()) as {
    ready: boolean;
    generationVersion: number;
    lock: { version: number };
    previewDigest: string;
  };
  expect(reviewed.ready, 'the week is ready to lock').toBe(true);
  const locked = await post(page, bearer, '/timesheet-periods/locks', `lock-${key}`, {
    ...scope,
    generationVersion: reviewed.generationVersion,
    lockVersion: reviewed.lock.version,
    previewDigest: reviewed.previewDigest,
    confirm: true,
  });
  expect(locked.status()).toBe(200);
  return sql(
    `SELECT id FROM people.timesheet_period_action WHERE tenant_id = '${TENANT_B}'
     AND generation_id = '${generationId}' AND action = 'LOCKED' ORDER BY sequence DESC LIMIT 1`,
  );
}

/** "Payroll preparation" with a legal entity and the week's period chosen, previewed. */

const NOTICE_EN =
  'Worked-time inputs only. Monthly salary is not calculated from these minutes. This export does not calculate or authorize pay, tax, deductions or payment.';
const NOTICE_FR =
  'Données de temps travaillé uniquement. Le salaire mensuel n’est pas calculé à partir de ces minutes. Cet export ne calcule ni n’autorise le salaire, les impôts, les retenues ou le paiement.';
const UNLOCK_EXPLANATION = `Upstream check of the week for the version journey ${letters}.`;

let runOne = '';
let runTwo = '';

/** The payroll-input rows of the lifecycle for a legal entity: versions, supersessions, locks, exports. */
function lifecycleOf(entity: string): string {
  return sql(
    `SELECT (SELECT string_agg(version::text, ',' ORDER BY version) FROM payroll.input_run
               WHERE tenant_id = '${TENANT_B}' AND legal_entity_id = '${entity}') || '|' ||
            (SELECT count(*) FROM payroll.input_run_supersession s JOIN payroll.input_run r
               ON r.tenant_id = s.tenant_id AND r.id = s.run_id
               WHERE s.tenant_id = '${TENANT_B}' AND r.legal_entity_id = '${entity}') || '|' ||
            (SELECT count(*) FROM payroll.input_run_lock
               WHERE tenant_id = '${TENANT_B}' AND legal_entity_id = '${entity}') || '|' ||
            (SELECT count(*) FROM payroll.input_export
               WHERE tenant_id = '${TENANT_B}' AND legal_entity_id = '${entity}')`,
  );
}

function audits(action: string): number {
  return Number(
    sql(
      `SELECT count(*) FROM platform.audit_event WHERE tenant_id = '${TENANT_B}'
       AND action = '${action}'`,
    ),
  );
}

/** The latest version's run of a legal entity. */
function latestRun(entity: string): string {
  return sql(
    `SELECT id FROM payroll.input_run WHERE tenant_id = '${TENANT_B}'
     AND legal_entity_id = '${entity}' ORDER BY version DESC LIMIT 1`,
  );
}

/** No person, run, export, digest or file reaches browser storage or the URL. */
async function clean(page: Page, secrets: string[]) {
  const stored = await storage(page);
  for (const secret of [...secrets, stamp, ANA_FAMILY, 'example.test']) {
    expect(stored).not.toContain(secret);
    expect(page.url()).not.toContain(secret);
  }
  expect(page.url()).not.toMatch(/[0-9a-f]{64}/u);
  expect(page.url()).not.toMatch(
    /[?&#](code|state|session_state|runId|exportId|previewDigest|cursor)=/u,
  );
}

/** No name, employee number, address or explanation in a region of the page. */
async function noPerson(region: Locator) {
  for (const marker of [ANA_FAMILY, CLEO_FAMILY, MIA_FAMILY, ANA, CLEO, MIA, 'example.test']) {
    await expect(region).not.toContainText(marker);
  }
}

/**
 * An unlock and a new lock of an approved week over the API: the lock evidence of the week changes
 * (a new LOCKED action), so the evidence a payroll-input version recorded no longer matches it.
 */
async function unlockWeek(
  page: Page,
  bearer: () => string,
  entityId: string,
  siteId: string,
  key: string,
) {
  const scope = { legalEntityId: entityId, siteId, weekStart: monday };
  const reason = { reasonCode: 'ORGANIZATION_REQUEST', explanation: UNLOCK_EXPLANATION };
  const preview = await post(page, bearer, '/timesheet-periods/unlock-preview', null, {
    ...scope,
    ...reason,
  });
  expect(preview.status()).toBe(200);
  const reviewed = (await preview.json()) as {
    lock: { version: number };
    currentLock: { actionId: string };
    previewDigest: string;
  };
  const unlocked = await post(page, bearer, '/timesheet-periods/unlocks', `unlock-${key}`, {
    ...scope,
    ...reason,
    lockVersion: reviewed.lock.version,
    lockActionId: reviewed.currentLock.actionId,
    previewDigest: reviewed.previewDigest,
    confirm: true,
  });
  expect(unlocked.status()).toBe(200);
}

/** The period's payroll-input run over the API (preview, then generate with the reviewed digests). */
async function generateRun(
  page: Page,
  bearer: () => string,
  entityId: string,
  key: string,
): Promise<string> {
  const period = { legalEntityId: entityId, periodStart: monday, periodEnd: day };
  const preview = await post(page, bearer, '/payroll-inputs/preview', null, period);
  expect(preview.status()).toBe(200);
  const reviewed = (await preview.json()) as {
    ready: boolean;
    previewDigest: string;
    sourceVersionsDigest: string;
  };
  expect(reviewed.ready, 'the week is locked and unchanged').toBe(true);
  const generated = await post(page, bearer, '/payroll-inputs', `gen-${key}`, {
    ...period,
    previewDigest: reviewed.previewDigest,
    sourceVersionsDigest: reviewed.sourceVersionsDigest,
    confirm: true,
  });
  expect(generated.status()).toBe(200);
  return ((await generated.json()) as { run: { runId: string } }).run.runId;
}

/** Opens "Payroll preparation" from the navigation. */
async function openPage(page: Page, language: 'en' | 'fr') {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Payroll preparation' : 'Préparation de la paie', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Payroll preparation' : 'Préparation de la paie',
  );
  await expect(page.getByTestId('payroll-prep-history')).toBeVisible();
}

/** The history row of a legal entity's newest run (the list is newest first). */
function historyRow(page: Page, entityName: string): Locator {
  return page.getByTestId('payroll-prep-item').filter({ hasText: entityName }).first();
}

/** The run's detail with its lifecycle panel. */
function lifecycle(page: Page): Locator {
  return page.getByTestId('payroll-prep-lifecycle');
}

/**
 * Downloads the CSV and the manifest of the exports table's row, and proves their integrity: the
 * CSV is UTF-8 with CRLF lines and the 18 approved columns, its SHA-256 is the database's and the
 * manifest's, and the manifest carries both fixed notices and the run version.
 */
async function downloadsOf(
  page: Page,
  row: Locator,
  expected: { entity: string; version: number; exportId: string; employee: string },
) {
  const csvDownload = page.waitForEvent('download');
  await row.getByTestId('payroll-prep-export-csv').click();
  const csvFile = await csvDownload;
  expect(csvFile.suggestedFilename()).toBe(
    `divalhr-worked-time-inputs-${expected.entity}-${monday}-${day}-v${expected.version}.csv`,
  );
  const csvBytes = readFileSync(await csvFile.path());
  const manifestDownload = page.waitForEvent('download');
  await row.getByTestId('payroll-prep-export-manifest').click();
  const manifestFile = await manifestDownload;
  expect(manifestFile.suggestedFilename()).toBe(
    `divalhr-worked-time-inputs-${expected.entity}-${monday}-${day}-v${expected.version}.manifest.json`,
  );
  const manifestText = readFileSync(await manifestFile.path(), 'utf8');

  const text = csvBytes.toString('utf8');
  expect(text.endsWith('\r\n')).toBe(true);
  expect(text.includes('\n') && !text.replaceAll('\r\n', '').includes('\n')).toBe(true);
  const lines = text.split('\r\n').slice(0, -1);
  expect(lines).toHaveLength(2);
  const header = (lines[0] ?? '').split(',').map((cell) => cell.replaceAll('"', ''));
  expect(header).toHaveLength(18);
  expect(header.slice(0, 2)).toEqual(['employee_id', 'employment_id']);
  expect(header[6]).toBe('approved_worked_minutes');
  expect(header[17]).toBe('payroll_input_run_version');
  const cells = (lines[1] ?? '').split(',').map((cell) => cell.replaceAll('"', ''));
  expect(cells).toHaveLength(18);
  expect(cells[0]).toBe(expected.employee);
  expect(cells[6]).toBe('15');
  expect(cells[17]).toBe(String(expected.version));
  expect(text).not.toMatch(
    new RegExp(`${ANA_FAMILY}|${CLEO_FAMILY}|${MIA_FAMILY}|example\\.test`, 'u'),
  );

  const digest = createHash('sha256').update(csvBytes).digest('hex');
  const manifest = JSON.parse(manifestText) as Record<string, unknown>;
  expect(manifest.csvSha256).toBe(digest);
  expect(manifest.exportId).toBe(expected.exportId);
  expect(manifest.payrollInputRunVersion).toBe(expected.version);
  expect(manifestText).toContain(NOTICE_EN);
  expect(manifestText).toContain(NOTICE_FR);
  expect(
    sql(
      `SELECT csv_sha256 FROM payroll.input_export WHERE tenant_id = '${TENANT_B}'
       AND id = '${expected.exportId}'`,
    ),
  ).toBe(digest);
  return digest;
}

let releaseSubject: () => void = () => undefined;

test.describe.serial('MVP-092: version, lock and export worked-time inputs', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(900_000);
    // The other specs writing as tenant B's administrator never run at the same time.
    releaseSubject = await subjectLock(USERS.adminB[0]);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminB, 'fr');
    const one = await entityWithSite(
      page,
      bearer,
      { entity: ENTITY_ONE, site: SITE_ONE, department: DEPARTMENT_ONE },
      ENTITY_ONE_NAME,
      'one',
    );
    const two = await entityWithSite(
      page,
      bearer,
      { entity: ENTITY_TWO, site: SITE_TWO, department: DEPARTMENT_TWO },
      ENTITY_TWO_NAME,
      'two',
    );
    entityOne = one.entityId;
    siteOne = one.siteId;
    entityTwo = two.entityId;
    siteTwo = two.siteId;
    const [anaStart, anaEnd, cleoStart, cleoEnd] = localTimes([-60, -45, -40, -25]) as [
      string,
      string,
      string,
      string,
    ];
    day = anaStart.slice(0, 10);
    expect(cleoEnd.slice(0, 10), 'the shifts stay on one local date').toBe(day);
    monday = sql(`SELECT (date_trunc('week', DATE '${day}'))::date::text`);
    const hired = plusDays(day, -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${ANA};Ana;${ANA_FAMILY};${hired};${ENTITY_ONE};${SITE_ONE};${DEPARTMENT_ONE}`,
      `${CLEO};Cléo;${CLEO_FAMILY};${hired};${ENTITY_TWO};${SITE_TWO};${DEPARTMENT_TWO}`,
      `${MIA};Mia;${MIA_FAMILY};${hired};${ENTITY_ONE};${SITE_ONE};${DEPARTMENT_ONE}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-lc-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-lc-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    await manage(page, bearer, employeeId(ANA), employeeId(MIA), day, 'mgr-ana');
    await manage(page, bearer, employeeId(CLEO), employeeId(MIA), day, 'mgr-cleo');
    await publishShift(
      page,
      bearer,
      siteOne,
      { start: anaStart, end: anaEnd, en: ANA_EN, fr: ANA_FR, employee: employeeId(ANA) },
      'ana',
    );
    await publishShift(
      page,
      bearer,
      siteTwo,
      { start: cleoStart, end: cleoEnd, en: CLEO_EN, fr: CLEO_FR, employee: employeeId(CLEO) },
      'cleo',
    );
    await createMissing(page, bearer, ANA_EN, ANA, 'ana');
    await createMissing(page, bearer, CLEO_EN, CLEO, 'cleo');
    generationOne = await generateWeek(page, bearer, siteOne, 'one');
    generationTwo = await generateWeek(page, bearer, siteTwo, 'two');
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await inviteAndLink(page, browser, bearer, CLEO_EMAIL, employeeId(CLEO), 'cleo');
    await inviteAndLink(page, browser, bearer, MIA_EMAIL, employeeId(MIA), 'mia');
    await approvedOverApi(browser, ANA_EMAIL, timesheetOf(ANA));
    await approvedOverApi(browser, CLEO_EMAIL, timesheetOf(CLEO));
    // Both weeks are locked (MVP-071) and their payroll inputs generated (MVP-090A) as version 1.
    lockActionOne = await lockWeek(page, bearer, entityOne, siteOne, generationOne, 'one');
    lockActionTwo = await lockWeek(page, bearer, entityTwo, siteTwo, generationTwo, 'two');
    expect(lockActionOne).toMatch(/^[0-9a-f-]{36}$/u);
    expect(lockActionTwo).toMatch(/^[0-9a-f-]{36}$/u);
    runOne = await generateRun(page, bearer, entityOne, 'one');
    runTwo = await generateRun(page, bearer, entityTwo, 'two');
    // The first week's upstream evidence changes after version 1 (unlocked, then locked again):
    // the second week's changes during the French journey.
    await unlockWeek(page, bearer, entityOne, siteOne, 'one');
    lockActionOne = await lockWeek(page, bearer, entityOne, siteOne, generationOne, 'one-again');
    await context.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('changed upstream evidence is superseded by version 2, which is locked and exported with a verified CSV and manifest, in English', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'en-US', acceptDownloads: true });
    const admin = await context.newPage();
    await signIn(admin, USERS.adminB, 'en');
    await openPage(admin, 'en');
    expect(lifecycleOf(entityOne), 'version 1 only, nothing superseded, locked or exported').toBe(
      '1|0|0|0',
    );
    const auditsBefore = {
      supersede: audits('payroll-input.supersede'),
      lock: audits('payroll-input.lock'),
      create: audits('payroll-input-export.create'),
      download: audits('payroll-input-export.download'),
    };

    // Version 1 is open; the history says so in text.
    const row = historyRow(admin, ENTITY_ONE_NAME);
    await expect(row).toContainText('Open');
    await expect(admin.getByTestId('payroll-prep-history-notice')).toHaveText(NOTICE_EN);
    await expect(admin.getByTestId('payroll-prep-exports-notice')).toHaveText(NOTICE_EN);
    await row.getByTestId('payroll-prep-item-open').click();
    const detail = admin.getByTestId('payroll-prep-detail');
    await expect(detail.getByTestId('payroll-prep-detail-id')).toHaveText(runOne);
    await expect(detail.getByTestId('payroll-prep-detail-version')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-notice')).toHaveText(NOTICE_EN);
    const panel = lifecycle(admin);
    await expect(panel).toHaveAttribute('data-state', 'OPEN');
    await expect(panel.getByTestId('payroll-prep-lifecycle-state')).toHaveText('Open');
    await expect(panel.getByTestId('payroll-prep-version')).toHaveCount(1);
    await noPerson(detail);

    // The upstream week was unlocked and locked again: the server offers the replacement.
    await panel.getByTestId('payroll-prep-supersede-check').click();
    const offered = panel.getByTestId('payroll-prep-supersede-preview');
    await expect(offered).toHaveAttribute('data-ready', 'true');
    await expect(offered.getByTestId('payroll-prep-supersede-notice')).toHaveText(NOTICE_EN);
    await expect(offered.getByTestId('payroll-prep-supersede')).toBeDisabled();
    await offered.getByTestId('payroll-prep-supersede-reason').selectOption('UPSTREAM_REAPPROVAL');
    await offered.getByTestId('payroll-prep-supersede-confirm').check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp092-1-supersede-en', panel);
    await offered.getByTestId('payroll-prep-supersede').click();

    // Version 2 replaces version 1; version 1 stays readable and unchanged.
    await expect(admin.getByTestId('payroll-prep-detail-version')).toHaveText('2');
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Version 2 created. Earlier versions remain unchanged.',
    );
    const second = latestRun(entityOne);
    expect(second).not.toBe(runOne);
    await expect(admin.getByTestId('payroll-prep-detail-id')).toHaveText(second);
    const versions = panel.getByTestId('payroll-prep-version');
    await expect(versions).toHaveCount(2);
    await expect(versions.nth(0)).toHaveAttribute('data-latest', 'false');
    await expect(versions.nth(1)).toHaveAttribute('data-latest', 'true');
    await expect(versions.nth(1)).toContainText('Upstream time approved again');
    expect(lifecycleOf(entityOne)).toBe('1,2|1|0|0');
    expect(audits('payroll-input.supersede')).toBe(auditsBefore.supersede + 1);

    // The lock: the preview, the notice, the confirmation, then the final state.
    await panel.getByTestId('payroll-prep-lock-check').click();
    const lockReview = panel.getByTestId('payroll-prep-lock-preview');
    await expect(lockReview).toHaveAttribute('data-ready', 'true');
    await expect(lockReview.getByTestId('payroll-prep-lock-notice')).toHaveText(NOTICE_EN);
    await expect(lockReview.getByTestId('payroll-prep-lock-employees')).toHaveText('1');
    await expect(lockReview.getByTestId('payroll-prep-lock-rows')).toHaveText('1');
    await expect(lockReview.getByTestId('payroll-prep-lock-worked')).toHaveText('0 h 15 min');
    await expect(lockReview).toContainText('This version supersedes version 1.');
    await expect(lockReview.getByTestId('payroll-prep-lock')).toBeDisabled();
    await lockReview.getByTestId('payroll-prep-lock-confirm').check();
    await accessible(admin);
    await evidence(admin, 'mvp092-2-lock-en', panel);
    await lockReview.getByTestId('payroll-prep-lock').click();
    await expect(panel.getByTestId('payroll-prep-lifecycle-state')).toHaveText('Locked');
    await expect(panel).toHaveAttribute('data-state', 'LOCKED');
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Version 2 is locked. It is final and cannot be changed or unlocked.',
    );
    await expect(panel.getByTestId('payroll-prep-lock-check')).toHaveCount(0);
    await expect(panel.getByTestId('payroll-prep-supersede-check')).toHaveCount(0);
    expect(lifecycleOf(entityOne)).toBe('1,2|1|1|0');

    // The export of the locked version, its history row and the verified downloads.
    await expect(panel.getByTestId('payroll-prep-export-notice')).toHaveText(NOTICE_EN);
    const create = panel.getByTestId('payroll-prep-export-create');
    await expect(create).toBeDisabled();
    await panel.getByTestId('payroll-prep-export-confirm').check();
    await create.click();
    const exportRow = admin.getByTestId('payroll-prep-export').first();
    await expect(exportRow).toBeVisible();
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Worked-time export created.',
    );
    await expect(panel.getByTestId('payroll-prep-export-exists')).toBeVisible();
    await expect(exportRow.getByTestId('payroll-prep-export-rows')).toHaveText('1');
    await expect(admin.getByTestId('payroll-prep-exports-notice')).toHaveText(NOTICE_EN);
    expect(lifecycleOf(entityOne)).toBe('1,2|1|1|1');
    const exportId = sql(
      `SELECT id FROM payroll.input_export WHERE tenant_id = '${TENANT_B}'
       AND legal_entity_id = '${entityOne}'`,
    );
    await expect(exportRow.getByTestId('payroll-prep-export-sha')).toHaveText(
      sql(
        `SELECT csv_sha256 FROM payroll.input_export WHERE tenant_id = '${TENANT_B}'
         AND id = '${exportId}'`,
      ),
    );
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp092-3-export-en', admin.getByTestId('payroll-prep-exports'));
    await downloadsOf(admin, exportRow, {
      entity: entityOne,
      version: 2,
      exportId,
      employee: employeeId(ANA),
    });
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'The integrity manifest was downloaded.',
    );
    await clean(admin, [runOne, latestRun(entityOne), exportId]);

    // Audited, and the immutable evidence of version 1 is untouched.
    expect(audits('payroll-input.lock')).toBe(auditsBefore.lock + 1);
    expect(audits('payroll-input-export.create')).toBe(auditsBefore.create + 1);
    expect(audits('payroll-input-export.download')).toBe(auditsBefore.download + 2);
    expect(runsOf(entityOne)).toBe('2:GENERATED:1:1:15,1:GENERATED:1:1:15');
    await context.close();
  });

  test('a stale lock, an unlocked source and an older version are blocked, then the corrected version is locked and exported, in French', async ({
    browser,
  }) => {
    test.setTimeout(420_000);
    const context = await browser.newContext({ locale: 'fr-FR', acceptDownloads: true });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'fr');
    await openPage(admin, 'fr');
    // The English journey's writes leave this subject's minute window before the French ones.
    await admin.waitForTimeout(61_000);
    expect(lifecycleOf(entityTwo)).toBe('1|0|0|0');

    await historyRow(admin, ENTITY_TWO_NAME).getByTestId('payroll-prep-item-open').click();
    const detail = admin.getByTestId('payroll-prep-detail');
    await expect(detail.getByTestId('payroll-prep-detail-id')).toHaveText(runTwo);
    await expect(detail.getByTestId('payroll-prep-detail-notice')).toHaveText(NOTICE_FR);
    const panel = lifecycle(admin);
    await expect(panel.getByTestId('payroll-prep-lifecycle-state')).toHaveText('Ouverte');

    // Nothing changed upstream: nothing to replace, and the server says so.
    await panel.getByTestId('payroll-prep-supersede-check').click();
    await expect(panel.getByTestId('payroll-prep-supersede-blocked')).toContainText(
      'La preuve en amont est identique à cette version. Il n’y a rien à remplacer.',
    );
    await expect(panel.getByTestId('payroll-prep-supersede')).toHaveCount(0);

    // A reviewed lock goes stale when the upstream week is unlocked after the preview.
    await panel.getByTestId('payroll-prep-lock-check').click();
    const reviewed = panel.getByTestId('payroll-prep-lock-preview');
    await expect(reviewed).toHaveAttribute('data-ready', 'true');
    await expect(reviewed.getByTestId('payroll-prep-lock-notice')).toHaveText(NOTICE_FR);
    await reviewed.getByTestId('payroll-prep-lock-confirm').check();
    await unlockWeek(admin, bearer, entityTwo, siteTwo, 'two');
    await reviewed.getByTestId('payroll-prep-lock').click();
    await expect(panel.getByTestId('payroll-prep-lifecycle-error')).toContainText(
      'La preuve verrouillée a changé depuis cet aperçu, ou l’aperçu a expiré. Prévisualisez de nouveau.',
    );
    await expect(panel.getByTestId('payroll-prep-lock-preview')).toHaveCount(0);
    expect(lifecycleOf(entityTwo)).toBe('1|0|0|0');

    // The source week is no longer locked: a stable blocker, no confirmation offered.
    await panel.getByTestId('payroll-prep-lock-check').click();
    const unlocked = panel.getByTestId('payroll-prep-lock-blocked');
    await expect(
      unlocked.getByTestId('payroll-prep-lock-blocker-SOURCE_PERIOD_NOT_LOCKED'),
    ).toHaveText('Une semaine source n’est plus verrouillée.');
    await expect(panel.getByTestId('payroll-prep-lock')).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp092-4-blocked-fr', panel);

    // The week is locked again: its evidence differs from version 1, which must be superseded.
    lockActionTwo = await lockWeek(admin, bearer, entityTwo, siteTwo, generationTwo, 'two-again');
    await panel.getByTestId('payroll-prep-lock-check').click();
    await expect(
      panel.getByTestId('payroll-prep-lock-blocker-UPSTREAM_EVIDENCE_CHANGED'),
    ).toHaveText(
      'La preuve en amont a changé depuis la génération de cette version. Créez d’abord une nouvelle version.',
    );
    await panel.getByTestId('payroll-prep-supersede-check').click();
    const offered = panel.getByTestId('payroll-prep-supersede-preview');
    await expect(offered).toHaveAttribute('data-ready', 'true');
    await expect(offered.getByTestId('payroll-prep-supersede-notice')).toHaveText(NOTICE_FR);
    await offered.getByTestId('payroll-prep-supersede-confirm').check();
    await offered.getByTestId('payroll-prep-supersede').click();
    await expect(admin.getByTestId('payroll-prep-detail-version')).toHaveText('2');
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Version 2 créée. Les versions précédentes restent inchangées.',
    );
    expect(lifecycleOf(entityTwo)).toBe('1,2|1|0|0');
    const versions = panel.getByTestId('payroll-prep-version');
    await expect(versions).toHaveCount(2);
    await expect(versions.nth(1)).toContainText('Preuve en amont corrigée');

    // The older version is shown as not the latest: its lock is blocked by the server.
    await versions.nth(0).getByTestId('payroll-prep-version-open').click();
    await expect(admin.getByTestId('payroll-prep-detail-version')).toHaveText('1');
    await expect(panel).toHaveAttribute('data-latest', 'false');
    await expect(panel).toContainText(
      'Ceci n’est pas la dernière version. Seule la dernière version peut être remplacée ou verrouillée.',
    );
    await panel.getByTestId('payroll-prep-lock-check').click();
    await expect(panel.getByTestId('payroll-prep-lock-blocker-NOT_LATEST_VERSION')).toHaveText(
      'Une version plus récente existe. Seule la dernière version peut être remplacée ou verrouillée.',
    );
    await accessible(admin);
    await panel.getByTestId('payroll-prep-open-latest').click();
    await expect(admin.getByTestId('payroll-prep-detail-version')).toHaveText('2');
    await expect(panel).toHaveAttribute('data-latest', 'true');

    // The corrected latest version is locked and exported, with the fixed notice in every area.
    await panel.getByTestId('payroll-prep-lock-check').click();
    const ready = panel.getByTestId('payroll-prep-lock-preview');
    await expect(ready).toHaveAttribute('data-ready', 'true');
    await expect(ready.getByTestId('payroll-prep-lock-notice')).toHaveText(NOTICE_FR);
    await ready.getByTestId('payroll-prep-lock-confirm').check();
    await ready.getByTestId('payroll-prep-lock').click();
    await expect(panel.getByTestId('payroll-prep-lifecycle-state')).toHaveText('Verrouillée');
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'La version 2 est verrouillée. Elle est définitive et ne peut être ni modifiée ni déverrouillée.',
    );
    await expect(panel.getByTestId('payroll-prep-export-notice')).toHaveText(NOTICE_FR);
    await panel.getByTestId('payroll-prep-export-confirm').check();
    await panel.getByTestId('payroll-prep-export-create').click();
    const exportRow = admin.getByTestId('payroll-prep-export').first();
    await expect(exportRow).toBeVisible();
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Export de temps travaillé créé.',
    );
    for (const area of [
      'payroll-prep-detail-notice',
      'payroll-prep-history-notice',
      'payroll-prep-exports-notice',
    ]) {
      await expect(admin.getByTestId(area)).toHaveText(NOTICE_FR);
    }
    await expect(historyRow(admin, ENTITY_TWO_NAME)).toContainText('Verrouillée');
    expect(lifecycleOf(entityTwo)).toBe('1,2|1|1|1');
    const exportId = sql(
      `SELECT id FROM payroll.input_export WHERE tenant_id = '${TENANT_B}'
       AND legal_entity_id = '${entityTwo}'`,
    );
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp092-5-export-fr', admin.getByTestId('payroll-prep-exports'));
    await downloadsOf(admin, exportRow, {
      entity: entityTwo,
      version: 2,
      exportId,
      employee: employeeId(CLEO),
    });
    await clean(admin, [runTwo, latestRun(entityTwo), exportId]);
    expect(runsOf(entityTwo)).toBe('2:GENERATED:1:1:15,1:GENERATED:1:1:15');
    await context.close();
  });
});

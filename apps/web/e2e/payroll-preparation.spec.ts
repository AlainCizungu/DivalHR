import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type Locator,
  type Page,
} from '@playwright/test';
import { mkdirSync } from 'node:fs';
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

// MVP-090A (Issue #141): generating time-based payroll inputs from locked approved weeks with the
// real identity provider, database and server clock. Setup over the API as the MVP-071 journey
// (the administrator's existing SSO session; the three employees' accounts are set up once through
// the API-driven invitation, never asserted as a journey): two legal entities with one site each;
// Ana at the first site, Cléo at the second, both managed by Mia from today; one short, ended shift
// each today, completed by an administrator's correction; both weeks generated; Ana and Cléo submit
// and Mia approves; the first site's week is locked, the second's is left approved but unlocked.
// Exactly two journeys:
//  - English: "Payroll preparation" previews the first legal entity's period (one locked week, one
//    employee, 15 approved minutes, nothing left out, no blocker), the administrator confirms and
//    generates; the result and the history show the run, which the database holds traceable to the
//    lock action; a second preview names the identical existing run;
//  - French : la seconde entité est bloquée (semaine générée non verrouillée : rien n’est généré,
//    aucune confirmation) ; le verrouillage en amont, puis une nouvelle prévisualisation, rendent la
//    période prête ; la génération réussit et l’historique montre les deux séries, la plus récente
//    d’abord.
// Fail-closed paths, staleness, idempotency, concurrency, tenant isolation, audits, events and V37
// constraints are proven at API and database level, not here.

// Tenant B's administrator (its own rate-limit buckets).
const TENANT_B = '00000000-0000-4000-8000-00000000000b';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY_ONE = `XP-${stamp}`;
const ENTITY_TWO = `XQ-${stamp}`;
const SITE_ONE = `XO-${stamp}`;
const SITE_TWO = `XN-${stamp}`;
const DEPARTMENT_ONE = `XM-${stamp}`;
const DEPARTMENT_TWO = `XW-${stamp}`;
const ANA = `TPA-${stamp}-1`;
const CLEO = `TPC-${stamp}-2`;
const MIA = `TPM-${stamp}-3`;
const ANA_FAMILY = `Mutombo${letters}`;
const CLEO_FAMILY = `Kabila${letters}`;
const MIA_FAMILY = `Tshisekedi${letters}`;
const ANA_EMAIL = `e2e.pay.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.pay.cleo.${lower}@example.test`;
const MIA_EMAIL = `e2e.pay.mia.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Pay!`;
const ENTITY_ONE_NAME = `Entité Paie Nord ${stamp}`;
const ENTITY_TWO_NAME = `Entité Paie Sud ${stamp}`;
const ANA_EN = `Dock shift ${letters}`;
const ANA_FR = `Quart du quai ${letters}`;
const CLEO_EN = `Store shift ${letters}`;
const CLEO_FR = `Quart du magasin ${letters}`;
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

/** No person, shift, digest or run reaches browser storage or the URL. */
async function clean(page: Page) {
  const markers = new RegExp(
    `${stamp}|${ANA_FAMILY}|${CLEO_FAMILY}|${MIA_FAMILY}|dock|quai|store shift|magasin|badge reader|example\\.test`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[0-9a-f]{64}/u);
  expect(page.url()).not.toMatch(
    /[?&#](code|state|session_state|runId|legalEntityId|previewDigest|cursor)=/u,
  );
}

/** No name, employee number, address or explanation in a region of the page. */
async function noPerson(region: Locator) {
  for (const marker of [ANA_FAMILY, CLEO_FAMILY, MIA_FAMILY, ANA, CLEO, MIA, 'example.test']) {
    await expect(region).not.toContainText(marker);
  }
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
        ...(key === null ? {} : { 'Idempotency-Key': `e2e-pay-${key}-${stamp}-0001` }),
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
async function previewPeriod(page: Page, language: 'en' | 'fr', entityId: string) {
  const en = language === 'en';
  const form = page.getByTestId('payroll-prep-form');
  await form
    .getByRole('combobox', { name: en ? 'Legal entity' : 'Entité juridique' })
    .selectOption(entityId);
  await form.getByLabel(en ? 'First day' : 'Premier jour').fill(monday);
  await form.getByLabel(en ? 'Last day' : 'Dernier jour').fill(day);
  await form.getByRole('button', { name: en ? 'Preview' : 'Prévisualiser', exact: true }).click();
  const preview = page.getByTestId('payroll-prep-preview');
  await expect(preview).toBeVisible();
  return preview;
}

/** Opens "Payroll preparation" from the navigation; the page's title and statement are exact. */
async function openPage(page: Page, language: 'en' | 'fr') {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Payroll preparation' : 'Préparation de la paie', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Payroll preparation' : 'Préparation de la paie',
  );
  await expect(page.getByTestId('payroll-prep-notice')).toHaveText(
    en
      ? 'Payroll inputs only. DivalHR has not calculated gross pay, deductions, tax, net pay or payment.'
      : 'Données de préparation uniquement. DivalHR n’a calculé ni salaire brut, ni retenues, ni impôts, ni salaire net, ni paiement.',
  );
  await expect(page.getByTestId('payroll-prep-history')).toBeVisible();
}

let releaseSubject: () => void = () => undefined;

test.describe.serial('MVP-090A: generate time-based payroll inputs', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
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
        'Idempotency-Key': `e2e-pay-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pay-commit-${stamp}-0001` },
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
    // The first legal entity's week is locked (MVP-071); the second's stays approved, unlocked.
    lockActionOne = await lockWeek(page, bearer, entityOne, siteOne, generationOne, 'one');
    expect(lockActionOne).toMatch(/^[0-9a-f-]{36}$/u);
    await context.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('a locked week is previewed, confirmed and generated into traceable payroll inputs shown in the result and the history, in English', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'en-US' });
    const admin = await context.newPage();
    await signIn(admin, USERS.adminB, 'en');
    await openPage(admin, 'en');
    expect(runsOf(entityOne), 'no payroll inputs before the journey').toBe('');

    // The preview: one locked week, one employee, 15 approved minutes, nothing left out.
    const preview = await previewPeriod(admin, 'en', entityOne);
    await expect(preview).toHaveAttribute('data-ready', 'true');
    await expect(preview.getByRole('heading', { level: 2 })).toHaveText('Payroll inputs preview');
    await expect(preview.getByTestId('payroll-prep-scope')).toContainText(
      `${ENTITY_ONE_NAME} (${ENTITY_ONE})`,
    );
    const weeks = preview.getByTestId('payroll-prep-week-row');
    await expect(weeks).toHaveCount(1);
    await expect(weeks.first()).toHaveAttribute('data-status', 'LOCKED');
    await expect(weeks.first()).toContainText('Generation 1 · lock 1');
    await expect(preview.getByTestId('payroll-prep-eligible-employees')).toHaveText('1');
    await expect(preview.getByTestId('payroll-prep-eligible-timesheets')).toHaveText('1');
    await expect(preview.getByTestId('payroll-prep-eligible-rows')).toHaveText('1');
    await expect(preview.getByTestId('payroll-prep-eligible-worked')).toHaveText('0 h 15 min');
    await expect(preview.getByTestId('payroll-prep-excluded-none')).toHaveText('Nothing left out.');
    await expect(preview.getByTestId('payroll-prep-ready')).toHaveText(
      'Every week with worked time in this period is locked and unchanged. The inputs can be generated.',
    );
    await expect(preview.getByTestId('payroll-prep-existing')).toHaveCount(0);
    await expect(preview.getByTestId('payroll-prep-blocked')).toHaveCount(0);
    await expect(preview.getByTestId('payroll-prep-expiry')).toContainText(
      'This preview is valid until',
    );
    await noPerson(preview);
    const generate = preview.getByRole('button', { name: 'Generate payroll inputs', exact: true });
    await expect(generate).toBeDisabled();
    await preview
      .getByLabel('I reviewed this preview and want to generate the payroll inputs.')
      .check();
    await expect(generate).toBeEnabled();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp090a-1-preview-en', preview);
    await generate.click();

    // Generated: the announcement, the immutable result, the history; no preview is left.
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Payroll inputs generated.',
    );
    await expect(admin.getByTestId('payroll-prep-preview')).toHaveCount(0);
    const detail = admin.getByTestId('payroll-prep-detail');
    await expect(detail.getByRole('heading', { level: 2 })).toHaveText('Payroll inputs');
    await expect(detail).toContainText('Generated');
    await expect(detail.getByTestId('payroll-prep-detail-version')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-weeks')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-digest')).toHaveText(/^[0-9a-f]{64}$/u);
    await expect(detail.getByTestId('payroll-prep-detail-counts-employees')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-counts-rows')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-counts-worked')).toHaveText('0 h 15 min');
    await expect(detail.getByTestId('payroll-prep-detail-excluded-none')).toBeVisible();
    const sourceWeeks = detail.getByTestId('payroll-prep-detail-week-row');
    await expect(sourceWeeks).toHaveCount(1);
    await expect(sourceWeeks.first()).toHaveAttribute('data-status', 'LOCKED');
    const employees = detail.getByTestId('payroll-prep-employee');
    await expect(employees).toHaveCount(1);
    await expect(employees.first()).toContainText(employeeId(ANA));
    await expect(employees.first().getByTestId('payroll-prep-employee-worked')).toHaveText(
      '0 h 15 min',
    );
    await expect(detail).toContainText('Generated inputs are never edited or deleted.');
    await noPerson(detail);
    const runId = (await detail.getByTestId('payroll-prep-detail-id').textContent()) ?? '';
    expect(runId).toMatch(/^[0-9a-f-]{36}$/u);
    const history = admin.getByTestId('payroll-prep-history');
    const items = history.getByTestId('payroll-prep-item');
    await expect(items.first()).toContainText(`${ENTITY_ONE_NAME} (${ENTITY_ONE})`);
    await expect(items.first().getByTestId('payroll-prep-item-employees')).toHaveText('1');
    await noPerson(history);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp090a-2-generated-en');
    await clean(admin);

    // The database holds the run traceable to the lock action, the approval and the timesheet.
    expect(runsOf(entityOne)).toBe('1:GENERATED:1:1:15');
    expect(
      sql(
        `SELECT generation_id || ':' || lock_action_id || ':' || lock_version
         FROM payroll.input_source_week WHERE tenant_id = '${TENANT_B}' AND run_id = '${runId}'`,
      ),
    ).toBe(`${generationOne}:${lockActionOne}:1`);
    expect(
      sql(
        `SELECT employee_id || ':' || timesheet_id || ':' || work_date || ':' || worked_minutes
           || ':' || (approval_action_id IS NOT NULL) || ':' || approval_cycle
         FROM payroll.input_source_row WHERE tenant_id = '${TENANT_B}' AND run_id = '${runId}'`,
      ),
    ).toBe(`${employeeId(ANA)}:${timesheetOf(ANA)}:${day}:15:true:1`);
    expect(
      sql(
        `SELECT count(*) FROM platform.outbox_event WHERE tenant_id = '${TENANT_B}'
         AND event_type = 'payroll.payroll-input.generated.v1'
         AND envelope -> 'data' ->> 'runId' = '${runId}'`,
      ),
    ).toBe('1');
    // Nothing personal in the audit record or the event.
    expect(
      sql(
        `SELECT count(*) FROM platform.audit_event WHERE tenant_id = '${TENANT_B}'
         AND action = 'payroll-input.generate' AND resource_id = '${runId}'
         AND metadata::text !~* '${ANA_FAMILY}|${ANA}|example\\.test'`,
      ),
    ).toBe('1');

    // Back on a fresh page, the history opens the run again; a new preview names the identical
    // existing inputs.
    await primaryNav(admin).getByRole('link', { name: 'Timesheets', exact: true }).click();
    await expect(admin.getByRole('heading', { level: 1 })).toHaveText('Timesheets');
    await openPage(admin, 'en');
    await expect(admin.getByTestId('payroll-prep-detail')).toHaveCount(0);
    await admin
      .getByTestId('payroll-prep-history')
      .getByTestId('payroll-prep-item-open')
      .first()
      .click();
    await expect(admin.getByTestId('payroll-prep-detail-id')).toHaveText(runId);
    const again = await previewPeriod(admin, 'en', entityOne);
    await expect(again).toHaveAttribute('data-ready', 'true');
    await expect(again.getByTestId('payroll-prep-existing')).toHaveAttribute(
      'data-unchanged',
      'true',
    );
    await expect(again.getByTestId('payroll-prep-existing')).toHaveText(
      'Payroll inputs already exist for this period with exactly this evidence. Generating again shows them.',
    );
    await accessible(admin);
    await evidence(admin, 'mvp090a-3-existing-en', again);
    await clean(admin);
    await context.close();
    expect(runsOf(entityOne), 'previewing again generated nothing').toBe('1:GENERATED:1:1:15');
  });

  test('une semaine non verrouillée bloque la période sans rien générer ; après le verrouillage en amont, la nouvelle prévisualisation permet la génération et l’historique montre les deux séries, en français', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'fr');
    await openPage(admin, 'fr');

    // Blocked: the generated week is not locked; no count, no confirmation, nothing generated.
    const blocked = await previewPeriod(admin, 'fr', entityTwo);
    await expect(blocked).toHaveAttribute('data-ready', 'false');
    await expect(blocked.getByRole('heading', { level: 2 })).toHaveText(
      'Aperçu des données de préparation',
    );
    const weeks = blocked.getByTestId('payroll-prep-week-row');
    await expect(weeks).toHaveCount(1);
    await expect(weeks.first()).toHaveAttribute('data-status', 'NOT_LOCKED');
    await expect(weeks.first()).toContainText('Non verrouillée');
    await expect(blocked.getByTestId('payroll-prep-eligible-employees')).toHaveText('0');
    await expect(blocked.getByTestId('payroll-prep-eligible-worked')).toHaveText('0 h 0 min');
    const blockers = blocked.getByTestId('payroll-prep-blocked');
    await expect(blockers.getByRole('heading', { level: 3 })).toHaveText(
      'Cette période ne peut pas être générée',
    );
    await expect(blockers.getByTestId('payroll-prep-blocker-WEEK_NOT_LOCKED')).toHaveText(
      '1 semaine générée n’est pas verrouillée.',
    );
    await expect(blockers).toContainText('Des données partielles ne sont jamais produites.');
    await expect(blocked.getByTestId('payroll-prep-ready')).toHaveCount(0);
    await expect(blocked.getByTestId('payroll-prep-generate')).toHaveCount(0);
    await expect(blocked.getByTestId('payroll-prep-confirm')).toHaveCount(0);
    await noPerson(blocked);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp090a-4-blocked-fr', blocked);
    await clean(admin);
    expect(runsOf(entityTwo), 'a blocked period generates nothing').toBe('');

    // Upstream, the administrator locks the approved week (MVP-071), then previews again.
    lockActionTwo = await lockWeek(admin, bearer, entityTwo, siteTwo, generationTwo, 'two');
    const ready = await previewPeriod(admin, 'fr', entityTwo);
    await expect(ready).toHaveAttribute('data-ready', 'true');
    await expect(ready.getByTestId('payroll-prep-week-row')).toHaveCount(1);
    await expect(ready.getByTestId('payroll-prep-week-row').first()).toHaveAttribute(
      'data-status',
      'LOCKED',
    );
    await expect(ready.getByTestId('payroll-prep-week-row').first()).toContainText(
      'Génération 1 · verrou 1',
    );
    await expect(ready.getByTestId('payroll-prep-eligible-employees')).toHaveText('1');
    await expect(ready.getByTestId('payroll-prep-eligible-worked')).toHaveText('0 h 15 min');
    await expect(ready.getByTestId('payroll-prep-excluded-none')).toHaveText('Rien n’est écarté.');
    await expect(ready.getByTestId('payroll-prep-ready')).toHaveText(
      'Chaque semaine comportant du temps travaillé dans cette période est verrouillée et inchangée. Les données peuvent être générées.',
    );
    await expect(ready.getByTestId('payroll-prep-blocked')).toHaveCount(0);
    await expect(ready.getByTestId('payroll-prep-expiry')).toContainText(
      'Cet aperçu est valable jusqu’au',
    );
    const generate = ready.getByRole('button', {
      name: 'Générer les données de préparation',
      exact: true,
    });
    await expect(generate).toBeDisabled();
    await ready
      .getByLabel(
        'J’ai vérifié cet aperçu et je veux générer les données de préparation de la paie.',
      )
      .check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp090a-5-ready-fr', ready);
    await generate.click();

    // Generated: the result in French, the history with both runs, newest first.
    await expect(admin.getByTestId('payroll-prep-announcer')).toHaveText(
      'Données de préparation de la paie générées.',
    );
    const detail = admin.getByTestId('payroll-prep-detail');
    await expect(detail.getByRole('heading', { level: 2 })).toHaveText(
      'Données de préparation de la paie',
    );
    await expect(detail).toContainText('Générées');
    await expect(detail.getByTestId('payroll-prep-scope')).toContainText(
      `${ENTITY_TWO_NAME} (${ENTITY_TWO})`,
    );
    await expect(detail.getByTestId('payroll-prep-detail-version')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-counts-employees')).toHaveText('1');
    await expect(detail.getByTestId('payroll-prep-detail-counts-worked')).toHaveText('0 h 15 min');
    const employees = detail.getByTestId('payroll-prep-employee');
    await expect(employees).toHaveCount(1);
    await expect(employees.first()).toContainText(employeeId(CLEO));
    await expect(detail).toContainText(
      'Les données générées ne sont jamais modifiées ni supprimées.',
    );
    await noPerson(detail);
    const runId = (await detail.getByTestId('payroll-prep-detail-id').textContent()) ?? '';
    const items = admin.getByTestId('payroll-prep-history').getByTestId('payroll-prep-item');
    await expect(items).toHaveCount(2);
    await expect(items.nth(0)).toContainText(`${ENTITY_TWO_NAME} (${ENTITY_TWO})`);
    await expect(items.nth(1)).toContainText(`${ENTITY_ONE_NAME} (${ENTITY_ONE})`);
    await expect(
      items.nth(0).getByRole('button', { name: /^Consulter les données de préparation du / }),
    ).toBeVisible();
    await noPerson(admin.getByTestId('payroll-prep-history'));
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp090a-6-generated-fr');
    await clean(admin);
    await context.close();

    // Each legal entity has exactly one run; the second is bound to its own lock action.
    expect(runsOf(entityOne)).toBe('1:GENERATED:1:1:15');
    expect(runsOf(entityTwo)).toBe('1:GENERATED:1:1:15');
    expect(
      sql(
        `SELECT generation_id || ':' || lock_action_id || ':' || lock_version
         FROM payroll.input_source_week WHERE tenant_id = '${TENANT_B}' AND run_id = '${runId}'`,
      ),
    ).toBe(`${generationTwo}:${lockActionTwo}:1`);
    expect(
      sql(
        `SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = '${TENANT_B}'
         AND run_id = '${runId}' AND employee_id = '${employeeId(CLEO)}' AND worked_minutes = 15`,
      ),
    ).toBe('1');
  });
});

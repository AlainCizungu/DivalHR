import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type Locator,
  type Page,
} from '@playwright/test';
import { createHash } from 'node:crypto';
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

// MVP-071 (Issue #139): locking an approved week with the real identity provider, database and
// server clock. Setup (API, the administrator's existing SSO session), as the MVP-070E journey:
// Ana, Cléo and Mia are imported at a new site; Mia manages Ana and Cléo from today; one schedule of
// today holds one short, ended shift each for Ana and Cléo, completed by an administrator's
// correction; the administrator generates the week; the three are invited and linked; Ana and Cléo
// submit and Mia approves both; the approved week is exported once (MVP-070E). Exactly two journeys:
//  - English: "Timesheets" opens the fully approved week; the administrator previews the lock
//    (2 approved, exported) and locks it; Ana sees "Period locked" on her own week; a correction of
//    Cléo's shift is refused (409 TIMESHEET_PERIOD_LOCKED, the preview stays readable); the
//    administrator unlocks with a reason and an explanation after the preview and its warning;
//  - French : après le déverrouillage, la correction rouvre la feuille de Cléo ; le verrouillage
//    est refusé (NOT_ALL_APPROVED) ; l’actualisation, la nouvelle soumission et la nouvelle
//    approbation rétablissent l’éligibilité ; l’administratrice reverrouille ; l’historique montre
//    les trois transitions et l’export antérieur est inchangé (mêmes octets, même SHA-256).
// Eligibility, staleness, idempotency, the guard's other paths, authority boundaries, audits,
// events, V36 constraints and plans are proven at API and database level, not here.

// Tenant B's administrator (its own rate-limit buckets).
const TENANT_B = '00000000-0000-4000-8000-00000000000b';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `XL-${stamp}`;
const SITE = `XK-${stamp}`;
const DEPARTMENT = `XJ-${stamp}`;
const ANA = `TLA-${stamp}-1`;
const CLEO = `TLC-${stamp}-2`;
const MIA = `TLM-${stamp}-3`;
const ANA_FAMILY = `Ilunga${letters}`;
const CLEO_FAMILY = `Nzuzi${letters}`;
const MIA_FAMILY = `Kasongo${letters}`;
const ANA_EMAIL = `e2e.lock.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.lock.cleo.${lower}@example.test`;
const MIA_EMAIL = `e2e.lock.mia.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Lck!`;
const SITE_NAME = `Site Verrou ${stamp}`;
const ANA_EN = `Gate watch ${letters}`;
const ANA_FR = `Garde au portail ${letters}`;
const CLEO_EN = `Cold room ${letters}`;
const CLEO_FR = `Chambre froide ${letters}`;
const EXPLANATION = 'The badge reader at the cold room was offline during this shift.';
const UNLOCK_EXPLANATION = `Monday check-in of the cold room shift to correct ${letters}.`;

let ZONE = 'Africa/Lubumbashi';
let monday = '';
let entityId = '';
let siteId = '';
let generationId = '';
let anaSheet = '';
let cleoSheet = '';
let exportId = '';
let exportSha = '';
let exportBytes: Buffer = Buffer.alloc(0);

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

async function clean(page: Page) {
  const markers = new RegExp(
    `${stamp}|${ANA_FAMILY}|${CLEO_FAMILY}|${MIA_FAMILY}|watch|portail|cold room|chambre|badge reader`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(
    /[?&#](code|state|session_state|timesheetId|generationId|reason|explanation)=/u,
  );
}

/**
 * A command over the API. Tenant B's administrator also runs the MVP-070D and MVP-070E journeys:
 * a setup call refused by the per-subject limit (429) waits for the window and is sent again with
 * the same key.
 */
async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  let response: APIResponse | undefined;
  for (let attempt = 0; attempt < 4; attempt++) {
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tl-${key}-${stamp}-0001` },
      ...(data === undefined ? {} : { data }),
    });
    if (response.status() !== 429) return response;
    await page.waitForTimeout(
      1000 * Math.min(60, Number(response.headers()['retry-after'] ?? '20') || 20),
    );
  }
  return response as APIResponse;
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
  const preview = await page.request.post(
    `${CORE_API}/attendance/corrections/missing-record-preview`,
    { headers: { Authorization: bearer() }, data: proposal },
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
  const preview = await page.request.post(
    `${CORE_API}/employees/${report}/employment-changes/preview`,
    { headers: { Authorization: bearer() }, data: change },
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

/** Invites an employee, accepts, sets the password and links the access to the employee record. */
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
async function signedIn(browser: Browser, email: string, language: 'en' | 'fr') {
  const context = await browser.newContext({ locale: language === 'en' ? 'en-US' : 'fr-FR' });
  const page = await context.newPage();
  const bearer = await signIn(page, [email, PASSWORD], language, undefined);
  return { page, bearer, close: () => context.close() };
}

/** The employee submits their reviewed timesheet and Mia approves it, over the API. */
async function approvedOverApi(browser: Browser, email: string, sheet: string, cycle: string) {
  const own = await signedIn(browser, email, 'en');
  const read = await own.page.request.get(`${CORE_API}/me/timesheets/${sheet}`, {
    headers: { Authorization: own.bearer() },
  });
  expect(read.status()).toBe(200);
  const mine = (await read.json()) as { revision: number; generationVersion: number };
  expect(
    (
      await post(
        own.page,
        own.bearer,
        `/me/timesheets/${sheet}/submissions`,
        `sub${cycle}-${sheet}`,
        {
          revision: mine.revision,
          generationVersion: mine.generationVersion,
          confirm: true,
        },
      )
    ).status(),
  ).toBe(200);
  await own.close();
  const mia = await signedIn(browser, MIA_EMAIL, 'en');
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
      await post(
        mia.page,
        mia.bearer,
        `/me/time-approvals/${sheet}/approval`,
        `apv${cycle}-${sheet}`,
        {
          revision: submission.revision,
          generationVersion: submission.generationVersion,
          cycle: submission.cycle,
          submissionActionId: submission.submissionActionId,
          confirm: true,
        },
      )
    ).status(),
  ).toBe(200);
  await mia.close();
}

/**
 * An administrator's correction of Cléo's record over the API (the check-in five minutes after the
 * shift's start): the preview, then the commit's response (refused while the week is locked).
 */
async function correctCleo(page: Page, bearer: () => string, key: string) {
  const record = sql(
    `SELECT r.id FROM people.attendance_record r JOIN people.work_schedule_slot l
     ON l.tenant_id = r.tenant_id AND l.id = r.slot_id
     WHERE r.tenant_id = '${TENANT_B}' AND l.label_en = '${CLEO_EN}'`,
  );
  const later = sql(
    `SELECT to_char((start_at + interval '5 minutes') AT TIME ZONE 'UTC',
       'YYYY-MM-DD"T"HH24:MI:SS"+00:00"')
     FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_B}' AND label_en = '${CLEO_EN}'`,
  );
  const proposal = {
    checkedInAt: later,
    checkedOutAt: slotInstant(CLEO_EN, 'end_at'),
    reasonCode: 'DATA_ENTRY_ERROR',
    explanation: EXPLANATION,
  };
  const preview = await page.request.post(
    `${CORE_API}/attendance/records/${record}/correction-preview`,
    { headers: { Authorization: bearer() }, data: proposal },
  );
  expect(preview.status()).toBe(200);
  const reviewed = (await preview.json()) as {
    targetVersion: number;
    previewDigest: string;
    warnings: string[];
  };
  expect(reviewed.warnings).toContain('APPROVED_TIME_MAY_REOPEN');
  return post(page, bearer, `/attendance/records/${record}/corrections`, key, {
    ...proposal,
    targetVersion: reviewed.targetVersion,
    previewDigest: reviewed.previewDigest,
    confirm: true,
  });
}

/** "Timesheets" with the run's legal entity, site and week chosen and its drafts opened. */
async function openWeek(page: Page, language: 'en' | 'fr'): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Timesheets' : 'Feuilles de temps', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Timesheets' : 'Feuilles de temps',
  );
  const form = page.getByTestId('timesheets-form');
  await form
    .getByRole('combobox', { name: en ? 'Legal entity' : 'Entité juridique' })
    .selectOption(entityId);
  await form.getByRole('combobox', { name: 'Site', exact: true }).selectOption(siteId);
  await form
    .getByLabel(en ? 'Week starting (Monday)' : 'Semaine commençant le (lundi)')
    .fill(monday);
  await form.getByRole('button', { name: en ? 'Preview' : 'Prévisualiser', exact: true }).click();
  await page
    .getByRole('button', { name: en ? 'Open the drafts' : 'Ouvrir les brouillons', exact: true })
    .click();
  const generation = page.getByTestId('timesheets-generation');
  await expect(generation).toBeVisible();
  return generation;
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

/** The first export's CSV over the API, as bytes. */
async function exportCsv(page: Page, bearer: () => string): Promise<Buffer> {
  const response = await page.request.get(`${CORE_API}/timesheet-exports/${exportId}/csv`, {
    headers: { Authorization: bearer() },
  });
  expect(response.status()).toBe(200);
  return response.body();
}

let releaseSubject: () => void = () => undefined;

test.describe.serial('MVP-071: lock and unlock an approved week', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
    // The other specs writing as tenant B's administrator never run at the same time.
    releaseSubject = await subjectLock(USERS.adminB[0]);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminB, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Entité Verrou ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    entityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: entityId,
      code: SITE,
      name: SITE_NAME,
      timezone: ZONE,
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    siteId = ((await site.json()) as { id: string }).id;
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId,
          code: DEPARTMENT,
          name: `Chambre froide ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const [anaStart, anaEnd, cleoStart, cleoEnd] = localTimes([-60, -45, -40, -25]) as [
      string,
      string,
      string,
      string,
    ];
    const day = anaStart.slice(0, 10);
    expect(cleoEnd.slice(0, 10), 'the shifts stay on one local date').toBe(day);
    const hired = plusDays(day, -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${ANA};Ana;${ANA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${CLEO};Cléo;${CLEO_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${MIA};Mia;${MIA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-tl-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tl-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    await manage(page, bearer, employeeId(ANA), employeeId(MIA), day, 'mgr-ana');
    await manage(page, bearer, employeeId(CLEO), employeeId(MIA), day, 'mgr-cleo');
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'ana',
          start: anaStart,
          end: anaEnd,
          labels: { en: ANA_EN, fr: ANA_FR },
          employeeId: employeeId(ANA),
        },
        {
          clientRef: 'cleo',
          start: cleoStart,
          end: cleoEnd,
          labels: { en: CLEO_EN, fr: CLEO_FR },
          employeeId: employeeId(CLEO),
        },
      ],
    };
    const preview = await page.request.post(`${CORE_API}/work-schedules/preview`, {
      headers: { Authorization: bearer() },
      data: proposal,
    });
    expect(preview.status()).toBe(200);
    const digest = ((await preview.json()) as { previewDigest: string }).previewDigest;
    expect(
      (
        await post(page, bearer, '/work-schedules', 'pub', {
          ...proposal,
          previewDigest: digest,
          confirm: true,
        })
      ).status(),
    ).toBe(201);
    await createMissing(page, bearer, ANA_EN, ANA, 'ana');
    await createMissing(page, bearer, CLEO_EN, CLEO, 'cleo');
    monday = sql(`SELECT (date_trunc('week', DATE '${day}'))::date::text`);
    const generated = await post(page, bearer, '/timesheets/generations', 'gen', {
      siteId,
      weekStart: monday,
      confirm: true,
    });
    expect(generated.status()).toBe(200);
    generationId = ((await generated.json()) as { generation: { generationId: string } }).generation
      .generationId;
    anaSheet = sql(
      `SELECT id FROM people.timesheet WHERE tenant_id = '${TENANT_B}'
       AND employee_id = '${employeeId(ANA)}'`,
    );
    cleoSheet = sql(
      `SELECT id FROM people.timesheet WHERE tenant_id = '${TENANT_B}'
       AND employee_id = '${employeeId(CLEO)}'`,
    );
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await inviteAndLink(page, browser, bearer, CLEO_EMAIL, employeeId(CLEO), 'cleo');
    await inviteAndLink(page, browser, bearer, MIA_EMAIL, employeeId(MIA), 'mia');
    await approvedOverApi(browser, ANA_EMAIL, anaSheet, '1');
    await approvedOverApi(browser, CLEO_EMAIL, cleoSheet, '1');
    // The approved week exported once (MVP-070E): the evidence a lock must leave unchanged.
    const exportPreview = await page.request.post(`${CORE_API}/timesheet-exports/preview`, {
      headers: { Authorization: bearer() },
      data: { legalEntityId: entityId, siteId, weekStart: monday },
    });
    expect(exportPreview.status()).toBe(200);
    const exportDigest = ((await exportPreview.json()) as { previewDigest: string }).previewDigest;
    const created = await post(page, bearer, '/timesheet-exports', 'exp', {
      legalEntityId: entityId,
      siteId,
      weekStart: monday,
      previewDigest: exportDigest,
      confirm: true,
    });
    expect(created.status()).toBe(200);
    const exported = (await created.json()) as {
      outcome: string;
      export: { exportId: string; csvSha256: string };
    };
    expect(exported.outcome).toBe('CREATED');
    exportId = exported.export.exportId;
    exportSha = exported.export.csvSha256;
    exportBytes = await exportCsv(page, bearer);
    expect(sha256(exportBytes)).toBe(exportSha);
    await context.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('the approved week is locked, the employee sees it locked, a correction is refused and the administrator unlocks with a reason, in English', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'en-US' });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'en');

    const generation = await openWeek(admin, 'en');
    await expect(generation.getByTestId('timesheets-draft-status')).toHaveText([
      'Approved',
      'Approved',
    ]);
    const panel = admin.getByTestId('period-lock');
    await expect(panel.getByTestId('period-lock-state')).toHaveText('Not locked');
    await expect(panel.getByTestId('period-lock-history-none')).toBeVisible();
    await expect(generation.getByTestId('timesheets-refresh-preview')).toBeEnabled();

    // The lock preview: two approved timesheets, nothing blocking, the evidence already exported.
    await panel.getByRole('button', { name: 'Lock period', exact: true }).click();
    const preview = panel.getByTestId('period-lock-preview-card');
    await expect(preview.getByRole('heading', { level: 4 })).toHaveText('Lock preview');
    await expect(preview.getByTestId('period-lock-states-approved')).toHaveText('2');
    await expect(preview.getByTestId('period-lock-states-reopened')).toHaveText('0');
    await expect(preview.getByTestId('period-lock-counts-timesheets')).toHaveText('2');
    await expect(preview.getByTestId('period-lock-counts-worked')).toHaveText('0 h 30 min');
    await expect(preview.getByTestId('period-lock-exported')).toHaveText(
      'This approved evidence was already exported.',
    );
    await expect(preview.getByTestId('period-lock-ready')).toBeVisible();
    // No name or employee number in the readiness.
    await expect(preview).not.toContainText(ANA_FAMILY);
    await expect(preview).not.toContainText(ANA);
    const confirm = preview.getByRole('button', { name: 'Confirm lock', exact: true });
    await expect(confirm).toBeDisabled();
    await preview.getByLabel('I reviewed this preview and want to lock the period.').check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp071-1-lock-preview-en', panel);
    await confirm.click();

    // Locked: the banner and receipt, the history, no refresh.
    await expect(admin.getByTestId('period-lock-announcer')).toHaveText('Period locked.');
    const locked = admin.getByTestId('period-lock');
    await expect(locked).toHaveAttribute('data-state', 'LOCKED');
    await expect(locked.getByTestId('period-lock-state')).toHaveText('Period locked');
    await expect(locked.getByTestId('period-lock-banner')).toContainText(
      'Locked for payroll preparation',
    );
    await expect(locked.getByTestId('period-lock-receipt')).toContainText('Lock 1');
    await expect(locked.getByTestId('period-lock-receipt-counts')).toContainText('0 h 30 min');
    await expect(locked.getByTestId('period-lock-action')).toHaveCount(1);
    await expect(locked.getByTestId('period-lock-action').first()).toHaveAttribute(
      'data-action',
      'LOCKED',
    );
    await expect(admin.getByTestId('timesheets-refresh-preview')).toBeDisabled();
    await expect(admin.getByTestId('timesheets-refresh-locked-period')).toHaveText(
      'The week is locked for payroll preparation; unlock it before refreshing.',
    );
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp071-2-locked-en', locked);
    await clean(admin);
    expect(
      sql(
        `SELECT state || ':' || version FROM people.timesheet_period_lock
         WHERE tenant_id = '${TENANT_B}' AND generation_id = '${generationId}'`,
      ),
    ).toBe('LOCKED:1');

    // Ana sees her own week locked: the badge and its explanation, nothing administrative.
    const ana = await signedIn(browser, ANA_EMAIL, 'en');
    await primaryNav(ana.page).getByRole('link', { name: 'My timesheets', exact: true }).click();
    const cards = ana.page.getByTestId('my-timesheet');
    await expect(cards).toHaveCount(1);
    await cards.getByTestId('my-timesheet-view').click();
    const own = ana.page.getByTestId('my-timesheet-detail');
    await expect(own.getByTestId('my-timesheet-detail-status')).toHaveText('Approved');
    await expect(own.getByTestId('my-timesheet-period-locked')).toHaveText('Period locked');
    await expect(own.getByTestId('my-timesheet-period-locked-hint')).toHaveText(
      'Locked for payroll preparation. This week cannot change until an administrator unlocks it.',
    );
    await expect(own.getByRole('button', { name: /unlock|lock period/iu })).toHaveCount(0);
    await expect(own).not.toContainText(UNLOCK_EXPLANATION);
    await accessible(ana.page);
    await evidence(ana.page, 'mvp071-3-employee-locked-en', own);
    // Ana cannot lock or unlock: the API refuses her (403), without a hint of the week.
    const refused = await ana.page.request.post(`${CORE_API}/timesheet-periods/lock-preview`, {
      headers: { Authorization: ana.bearer() },
      data: { legalEntityId: entityId, siteId, weekStart: monday },
    });
    expect(refused.status()).toBe(403);
    await clean(ana.page);
    await ana.close();

    // A correction of Cléo's shift is refused while the week is locked; its preview stays readable.
    const commit = await correctCleo(admin, bearer, 'fix-cleo-locked');
    expect(commit.status()).toBe(409);
    expect(((await commit.json()) as { code: string }).code).toBe('TIMESHEET_PERIOD_LOCKED');
    expect(
      sql(
        `SELECT count(*) FROM people.attendance_correction c JOIN people.work_schedule_slot l
         ON l.tenant_id = c.tenant_id AND l.id = c.slot_id
         WHERE c.tenant_id = '${TENANT_B}' AND l.label_en = '${CLEO_EN}'`,
      ),
      'only the record-creating correction exists',
    ).toBe('1');
    expect(
      sql(
        `SELECT status FROM people.timesheet WHERE tenant_id = '${TENANT_B}' AND id = '${cleoSheet}'`,
      ),
    ).toBe('APPROVED');

    // The unlock: a closed reason and an explanation, the preview with its warning, the receipt.
    await locked.getByRole('button', { name: 'Unlock period', exact: true }).click();
    const unlock = locked.getByTestId('period-unlock');
    await unlock.getByRole('button', { name: 'Preview unlock', exact: true }).click();
    await expect(unlock.getByText('Choose a reason.')).toBeVisible();
    await expect(unlock.getByText('Give an explanation.')).toBeVisible();
    await unlock
      .getByRole('combobox', { name: 'Reason', exact: true })
      .selectOption('ATTENDANCE_CORRECTION_REQUIRED');
    await unlock.getByLabel('Explanation', { exact: true }).fill(UNLOCK_EXPLANATION);
    await accessible(admin);
    await unlock.getByRole('button', { name: 'Preview unlock', exact: true }).click();
    const unlockPreview = unlock.getByTestId('period-unlock-preview-card');
    await expect(unlockPreview.getByTestId('period-unlock-current')).toContainText('Lock 1');
    await expect(unlockPreview.getByTestId('period-unlock-reason-text')).toContainText(
      'Attendance correction required',
    );
    await expect(unlockPreview.getByTestId('period-unlock-warnings')).toContainText(
      'Unlocking does not change earlier exports',
    );
    const confirmUnlock = unlockPreview.getByRole('button', {
      name: 'Confirm unlock',
      exact: true,
    });
    await expect(confirmUnlock).toBeDisabled();
    await unlockPreview
      .getByLabel('I reviewed this preview and want to unlock the period.')
      .check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp071-4-unlock-preview-en', locked);
    await confirmUnlock.click();
    await expect(admin.getByTestId('period-lock-announcer')).toHaveText('Period unlocked.');
    const reopened = admin.getByTestId('period-lock');
    await expect(reopened).toHaveAttribute('data-state', 'OPEN');
    await expect(reopened.getByTestId('period-lock-state')).toHaveText('Not locked');
    await expect(reopened.getByTestId('period-lock-banner')).toHaveCount(0);
    const rows = reopened.getByTestId('period-lock-action');
    await expect(rows).toHaveCount(2);
    await expect(rows.nth(0)).toHaveAttribute('data-action', 'UNLOCKED');
    await expect(rows.nth(0).getByTestId('period-lock-action-reason')).toHaveText(
      'Attendance correction required',
    );
    await expect(rows.nth(1)).toHaveAttribute('data-action', 'LOCKED');
    // The explanation is never shown back.
    await expect(reopened).not.toContainText(UNLOCK_EXPLANATION);
    await expect(admin.getByTestId('timesheets-refresh-preview')).toBeEnabled();
    await accessible(admin);
    await evidence(admin, 'mvp071-5-unlocked-en', reopened);
    await clean(admin);
    await context.close();
    // Unlocking alone changed no timesheet and no export.
    expect(
      sql(
        `SELECT string_agg(status, ',' ORDER BY status) FROM people.timesheet
         WHERE tenant_id = '${TENANT_B}' AND generation_id = '${generationId}'`,
      ),
    ).toBe('APPROVED,APPROVED');
    expect(
      sql(
        `SELECT csv_sha256 FROM people.timesheet_export WHERE tenant_id = '${TENANT_B}'
         AND id = '${exportId}'`,
      ),
    ).toBe(exportSha);
  });

  test('après le déverrouillage, la correction rouvre la feuille, le cycle la réapprouve et l’administratrice reverrouille ; l’export antérieur est inchangé, en français', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'fr');
    // The administrator's writes (previews included) share the work-schedule-write budget, 20 per
    // subject per minute, never raised for tests: on a fast host the setup's and the English
    // journey's writes land in one minute, and this journey's correction, lock preview and refresh
    // preview would be the ones refused. A full window passes before its first write.
    await admin.waitForTimeout(61_000);
    // The same correction now commits and reopens only Cléo's approved week (MVP-070D).
    const commit = await correctCleo(admin, bearer, 'fix-cleo-open');
    expect(commit.status()).toBe(201);
    expect(((await commit.json()) as { reopenedTimesheets: number }).reopenedTimesheets).toBe(1);

    const generation = await openWeek(admin, 'fr');
    await expect(generation.getByTestId('timesheets-draft-status')).toHaveText([
      'Approuvée',
      'Rouverte',
    ]);
    const panel = admin.getByTestId('period-lock');
    await expect(panel.getByTestId('period-lock-state')).toHaveText('Non verrouillée');
    await expect(panel.getByTestId('period-lock-action')).toHaveCount(2);

    // Not ready: the reopened week names its blocker and offers no confirmation.
    await panel.getByRole('button', { name: 'Verrouiller la période', exact: true }).click();
    const preview = panel.getByTestId('period-lock-preview-card');
    await expect(preview.getByTestId('period-lock-not-ready')).toContainText(
      'Cette semaine ne peut pas encore être verrouillée.',
    );
    await expect(preview.getByTestId('period-lock-blocker')).toHaveText(
      'Au moins une feuille est encore un brouillon, soumise ou rouverte.',
    );
    await expect(preview.getByTestId('period-lock-states-reopened')).toHaveText('1');
    await expect(preview.getByTestId('period-lock-states-approved')).toHaveText('1');
    await expect(
      preview.getByRole('button', { name: 'Confirmer le verrouillage', exact: true }),
    ).toHaveCount(0);
    await expect(preview).not.toContainText(CLEO_FAMILY);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp071-6-not-ready-fr', panel);
    await preview.getByRole('button', { name: 'Annuler', exact: true }).click();

    // The refresh gives Cléo's reopened timesheet its new revision (a draft again, cycle 2).
    await generation
      .getByRole('button', { name: 'Prévisualiser l’actualisation', exact: true })
      .click();
    const refresh = admin.getByTestId('timesheets-refresh');
    await expect(refresh.getByTestId('timesheets-refresh-reopened')).toHaveText('1');
    await expect(refresh.getByTestId('timesheets-refresh-locked')).toHaveText('1');
    await refresh
      .getByLabel('J’ai vérifié cet aperçu et je veux appliquer l’actualisation.')
      .check();
    await refresh.getByRole('button', { name: 'Appliquer l’actualisation', exact: true }).click();
    await expect(admin.getByTestId('timesheets-announcer')).toHaveText(
      'Actualisation appliquée : version 2.',
    );
    await expect(generation.getByTestId('timesheets-draft-status')).toHaveText([
      'Approuvée',
      'Brouillon',
    ]);
    await accessible(admin);
    await clean(admin);

    // Cléo resubmits and Mia approves (cycle 2); the week is eligible again.
    await approvedOverApi(browser, CLEO_EMAIL, cleoSheet, '2');
    expect(
      sql(
        `SELECT status || ':c' || submission_cycle || ':r' || current_revision FROM people.timesheet
         WHERE tenant_id = '${TENANT_B}' AND id = '${cleoSheet}'`,
      ),
    ).toBe('APPROVED:c2:r2');

    // The relock: the preview is ready again (the new approval), the second lock follows.
    const again = await openWeek(admin, 'fr');
    await expect(again.getByTestId('timesheets-draft-status')).toHaveText([
      'Approuvée',
      'Approuvée',
    ]);
    const relock = admin.getByTestId('period-lock');
    await relock.getByRole('button', { name: 'Verrouiller la période', exact: true }).click();
    const ready = relock.getByTestId('period-lock-preview-card');
    await expect(ready.getByTestId('period-lock-ready')).toHaveText(
      'Chaque feuille de temps de cette semaine est approuvée. Le verrou fige exactement cette preuve.',
    );
    await expect(ready.getByTestId('period-lock-states-approved')).toHaveText('2');
    await expect(ready.getByTestId('period-lock-exported')).toHaveText(
      'Cette preuve approuvée n’a pas encore été exportée. Le verrouillage ne crée aucun export.',
    );
    await ready.getByLabel('J’ai vérifié cet aperçu et je veux verrouiller la période.').check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp071-7-relock-preview-fr', relock);
    await ready.getByRole('button', { name: 'Confirmer le verrouillage', exact: true }).click();
    await expect(admin.getByTestId('period-lock-announcer')).toHaveText('Période verrouillée.');
    const lockedAgain = admin.getByTestId('period-lock');
    await expect(lockedAgain).toHaveAttribute('data-state', 'LOCKED');
    await expect(lockedAgain.getByTestId('period-lock-state')).toHaveText('Période verrouillée');
    await expect(lockedAgain.getByTestId('period-lock-banner')).toContainText(
      'Verrouillée pour la préparation de la paie',
    );
    await expect(lockedAgain.getByTestId('period-lock-receipt')).toContainText('Verrou 3');
    const rows = lockedAgain.getByTestId('period-lock-action');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toHaveAttribute('data-action', 'LOCKED');
    await expect(rows.nth(1)).toHaveAttribute('data-action', 'UNLOCKED');
    await expect(rows.nth(1).getByTestId('period-lock-action-reason')).toHaveText(
      'Correction de présence nécessaire',
    );
    await expect(rows.nth(2)).toHaveAttribute('data-action', 'LOCKED');
    await expect(lockedAgain).not.toContainText(UNLOCK_EXPLANATION);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp071-8-relocked-fr', lockedAgain);
    await clean(admin);

    // The earlier export is unchanged: the same bytes and SHA-256, downloadable while locked.
    const csv = await exportCsv(admin, bearer);
    expect(csv.equals(exportBytes)).toBe(true);
    expect(sha256(csv)).toBe(exportSha);
    await context.close();
    expect(
      sql(
        `SELECT string_agg(action || ':' || sequence, ',' ORDER BY sequence)
         FROM people.timesheet_period_action
         WHERE tenant_id = '${TENANT_B}' AND generation_id = '${generationId}'`,
      ),
    ).toBe('LOCKED:1,UNLOCKED:2,LOCKED:3');
    expect(
      sql(
        `SELECT count(*) FROM people.timesheet_export_row WHERE tenant_id = '${TENANT_B}'
         AND export_id = '${exportId}'`,
      ),
    ).toBe('2');
  });
});

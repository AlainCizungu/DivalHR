import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type Locator,
  type Page,
} from '@playwright/test';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync } from 'node:fs';
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

// MVP-070E (Issue #134): exporting approved time with the real identity provider, database and
// server clock. Setup (API, the administrator's existing SSO session): Ana, Cléo and Mia are
// imported at a new site; Mia manages Ana and Cléo from today; one schedule of today holds one
// short, already ended shift each for Ana and Cléo, completed by an administrator's correction; the
// administrator generates the week; Ana, Cléo and Mia are invited and linked; Ana and Cléo submit
// and Mia approves both. Exactly two journeys:
//  - English: "Time exports" previews the approved week (2 timesheets, nothing excluded), creates
//    the export after confirming, finds it in the history, and downloads the CSV and the manifest;
//    the CSV's header, rows and SHA-256 match the manifest and the page;
//  - French : une correction rouvre la feuille approuvée de Cléo ; « Exports de temps » montre
//    l’avis « Temps approuvé uniquement… », l’exclusion de la feuille rouverte et n’exporte que
//    celle d’Ana ; un employé et la responsable n’ont aucun accès ; axe et adaptation à 320 px,
//    390 px et 200 %.
// Eligibility, staleness, immutability, audits, events and plans are proven at API and database
// level, not here.

// Tenant B's administrator (its own rate-limit buckets: the MVP-070A and MVP-070B/C journeys use
// tenant A's administrator).
const TENANT_B = '00000000-0000-4000-8000-00000000000b';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `XE-${stamp}`;
const SITE = `XS-${stamp}`;
const DEPARTMENT = `XD-${stamp}`;
const ANA = `TXA-${stamp}-1`;
const CLEO = `TXC-${stamp}-2`;
const MIA = `TXM-${stamp}-3`;
const ANA_FAMILY = `Kabongo${letters}`;
const CLEO_FAMILY = `Mutombo${letters}`;
const MIA_FAMILY = `Lukusa${letters}`;
const ANA_EMAIL = `e2e.export.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.export.cleo.${lower}@example.test`;
const MIA_EMAIL = `e2e.export.mia.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Exp!`;
const SITE_NAME = `Site Exports ${stamp}`;
const ANA_EN = `Gate patrol ${letters}`;
const ANA_FR = `Ronde au portail ${letters}`;
const CLEO_EN = `Loading dock ${letters}`;
const CLEO_FR = `Quai de chargement ${letters}`;
const EXPLANATION = 'The badge reader at the dock was offline during this shift.';

let ZONE = 'Africa/Lubumbashi';
let firstExport = '';
let anaSheet = '';
let cleoSheet = '';

/**
 * PR evidence (EVIDENCE=1 only): a screenshot written outside the working tree. The pages hold
 * only this run's generated test data.
 */
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

/**
 * Site-local date-times `YYYY-MM-DDTHH:MM` at each offset (minutes) from one reading of the
 * server's clock, so a minute that ticks over between two readings can never stretch a shift.
 */
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
    `${stamp}|${ANA_FAMILY}|${CLEO_FAMILY}|${MIA_FAMILY}|patrol|portail|dock|chargement|pause`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state|timesheetId|reason)=/u);
}

/**
 * A command over the API. Tenant B's administrator also runs the MVP-070D journeys: a setup call
 * refused by the per-subject limit (429) waits for the window and is sent again with the same key.
 */
async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  let response: APIResponse | undefined;
  for (let attempt = 0; attempt < 4; attempt++) {
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tx-${key}-${stamp}-0001` },
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
async function approvedOverApi(browser: Browser, email: string, sheet: string) {
  const own = await signedIn(browser, email, 'en');
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

/**
 * An administrator's correction of a shift's record, over the API: the check-in five minutes after
 * the shift's start (a material change of the approved time). Returns the receipt.
 */
async function corrected(page: Page, bearer: () => string, labelEn: string, key: string) {
  const record = sql(
    `SELECT r.id FROM people.attendance_record r JOIN people.work_schedule_slot l
     ON l.tenant_id = r.tenant_id AND l.id = r.slot_id
     WHERE r.tenant_id = '${TENANT_B}' AND l.label_en = '${labelEn}'`,
  );
  const later = sql(
    `SELECT to_char((start_at + interval '5 minutes') AT TIME ZONE 'UTC',
       'YYYY-MM-DD"T"HH24:MI:SS"+00:00"')
     FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_B}' AND label_en = '${labelEn}'`,
  );
  const proposal = {
    checkedInAt: later,
    checkedOutAt: slotInstant(labelEn, 'end_at'),
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
  const commit = await post(page, bearer, `/attendance/records/${record}/corrections`, key, {
    ...proposal,
    targetVersion: reviewed.targetVersion,
    previewDigest: reviewed.previewDigest,
    confirm: true,
  });
  expect(commit.status()).toBe(201);
  return (await commit.json()) as { reopenedTimesheets: number };
}

let monday = '';
let entityId = '';
let siteId = '';

/** "Time exports" with the run's legal entity, site and week chosen and previewed. */
async function previewExport(page: Page, language: 'en' | 'fr'): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Time exports' : 'Exports de temps', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Time exports' : 'Exports de temps',
  );
  const form = page.getByTestId('time-exports-form');
  await form
    .getByRole('combobox', { name: en ? 'Legal entity' : 'Entité juridique' })
    .selectOption(entityId);
  await form.getByRole('combobox', { name: 'Site', exact: true }).selectOption(siteId);
  await form
    .getByLabel(en ? 'Week starting (Monday)' : 'Semaine commençant le (lundi)')
    .fill(monday);
  await form.getByRole('button', { name: en ? 'Preview' : 'Prévisualiser', exact: true }).click();
  const preview = page.getByTestId('time-exports-preview');
  await expect(preview).toBeVisible();
  return preview;
}

/** A download started by a click, as bytes. */
async function downloaded(page: Page, click: () => Promise<void>): Promise<Buffer> {
  const [download] = await Promise.all([page.waitForEvent('download'), click()]);
  const path = await download.path();
  return readFileSync(path);
}

function sha256(bytes: Buffer): string {
  return createHash('sha256').update(bytes).digest('hex');
}

const HEADER =
  'schema_version,export_id,legal_entity_id,site_id,site_timezone,week_start,week_end,' +
  'employee_id,employee_number,employment_id,cost_center_id,work_date,shift_id,timesheet_id,' +
  'timesheet_revision,approval_action_id,approval_cycle,worked_minutes';

let releaseSubject: () => void = () => undefined;

test.describe.serial('MVP-070E: export approved time', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
    // The other spec writing as tenant B's administrator never runs at the same time.
    releaseSubject = await subjectLock(USERS.adminB[0]);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminB, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Entité Exports ${stamp}`,
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
          name: `Entrepôt ${stamp}`,
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
        'Idempotency-Key': `e2e-tx-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tx-commit-${stamp}-0001` },
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
    await context.close();
    await approvedOverApi(browser, ANA_EMAIL, anaSheet);
    await approvedOverApi(browser, CLEO_EMAIL, cleoSheet);
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('the approved week is previewed, exported, found in the history and downloaded, in English', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'en-US', acceptDownloads: true });
    const admin = await context.newPage();
    await signIn(admin, USERS.adminB, 'en');
    await expect(admin.getByTestId('time-exports-notice')).toHaveCount(0);

    const preview = await previewExport(admin, 'en');
    await expect(admin.getByTestId('time-exports-notice')).toHaveText(
      'Approved time only. This export does not calculate pay.',
    );
    await expect(preview.getByTestId('time-exports-eligible-timesheets')).toHaveText('2');
    await expect(preview.getByTestId('time-exports-eligible-employees')).toHaveText('2');
    await expect(preview.getByTestId('time-exports-eligible-rows')).toHaveText('2');
    await expect(preview.getByTestId('time-exports-eligible-worked')).toHaveText('0 h 30 min');
    await expect(preview.getByTestId('time-exports-excluded-draft')).toHaveText('0');
    await expect(preview.getByTestId('time-exports-excluded-reopened')).toHaveText('0');
    // No name or employee number in the preview.
    await expect(preview).not.toContainText(ANA_FAMILY);
    await expect(preview).not.toContainText(ANA);
    const create = preview.getByRole('button', { name: 'Create export' });
    await expect(create).toBeDisabled();
    await preview.getByLabel('I reviewed this preview and want to create the export.').check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp070e-1-preview-en', preview);
    await create.click();

    const detail = admin.getByTestId('time-exports-detail');
    await expect(admin.getByTestId('time-exports-announcer')).toHaveText('Export created.');
    const exportId =
      (await detail.getByTestId('time-exports-detail-id').textContent())?.trim() ?? '';
    const digest =
      (await detail.getByTestId('time-exports-detail-digest').textContent())?.trim() ?? '';
    expect(exportId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(digest).toMatch(/^[0-9a-f]{64}$/u);
    firstExport = exportId;
    const item = admin.getByTestId('time-exports-item').filter({ hasText: SITE_NAME });
    await expect(item).toHaveCount(1);
    await expect(item.getByTestId('time-exports-item-timesheets')).toHaveText('2');
    await accessible(admin);
    await noOverflow(admin);

    // The CSV: the fixed English header, one row per approved row, the manifest's SHA-256.
    const csv = await downloaded(admin, () =>
      detail.getByRole('button', { name: 'Download CSV' }).click(),
    );
    expect(sha256(csv)).toBe(digest);
    const lines = csv.toString('utf8').split('\r\n');
    expect(lines[0]?.replaceAll('"', '')).toBe(HEADER);
    expect(lines).toHaveLength(4); // header, two rows, the final line end
    expect(lines[3]).toBe('');
    for (const line of lines.slice(1, 3)) {
      const cells = line.split(',').map((cell) => cell.replaceAll('"', ''));
      expect(cells).toHaveLength(18);
      expect(cells[1]).toBe(exportId);
      expect(cells[2]).toBe(entityId);
      expect(cells[3]).toBe(siteId);
      expect(cells[5]).toBe(monday);
      expect(cells[17]).toBe('15');
    }
    expect(lines.slice(1, 3).map((line) => line.split(',')[8]?.replaceAll('"', ''))).toEqual(
      [ANA, CLEO].sort(),
    );
    expect(csv.toString('utf8')).not.toMatch(
      new RegExp(`${ANA_FAMILY}|${CLEO_FAMILY}|@|patrol|chargement`, 'iu'),
    );
    const manifest = await downloaded(admin, () =>
      detail.getByRole('button', { name: 'Download manifest' }).click(),
    );
    const facts = JSON.parse(manifest.toString('utf8')) as {
      exportId: string;
      status: string;
      csvSha256: string;
      counts: { timesheets: number; rows: number };
      totalWorkedMinutes: number;
    };
    expect(facts).toMatchObject({
      exportId,
      status: 'READY',
      csvSha256: digest,
      counts: { timesheets: 2, rows: 2 },
      totalWorkedMinutes: 30,
    });
    expect(manifest.toString('utf8')).not.toMatch(/sub-|@|Kabongo|Mutombo/u);
    // Opened again from the history, the same export downloads the same bytes.
    await item.getByTestId('time-exports-item-open').click();
    await expect(detail.getByTestId('time-exports-detail-id')).toHaveText(exportId);
    const again = await downloaded(admin, () =>
      detail.getByRole('button', { name: 'Download CSV' }).click(),
    );
    expect(again.equals(csv)).toBe(true);
    await evidence(admin, 'mvp070e-2-export-en', detail);
    await clean(admin);
    await context.close();
    expect(
      sql(`SELECT count(*) FROM people.timesheet_export_row WHERE export_id = '${exportId}'`),
    ).toBe('2');
  });

  test('une feuille rouverte est exclue, l’avis « sans calcul de paie » et les limites d’autorisation, en français', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'fr');
    // A correction reopens Cléo's approved week: her time is no longer exportable.
    const receipt = await corrected(admin, bearer, CLEO_EN, 'fix-cleo');
    expect(receipt.reopenedTimesheets).toBe(1);

    const preview = await previewExport(admin, 'fr');
    await expect(admin.getByTestId('time-exports-notice')).toHaveText(
      'Temps approuvé uniquement. Cet export ne calcule pas la paie.',
    );
    await expect(preview.getByTestId('time-exports-eligible-timesheets')).toHaveText('1');
    await expect(preview.getByTestId('time-exports-excluded-reopened')).toHaveText('1');
    await expect(preview.getByTestId('time-exports-excluded-draft')).toHaveText('0');
    await expect(preview).toContainText(
      'Seules les feuilles de temps dont l’état actuel est Approuvée',
    );
    await expect(preview.getByTestId('time-exports-existing')).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp070e-3-exclusions-fr');
    await preview.getByLabel('J’ai vérifié cet aperçu et je veux créer l’export.').check();
    await preview.getByRole('button', { name: 'Créer l’export' }).click();
    await expect(admin.getByTestId('time-exports-announcer')).toHaveText('Export créé.');
    const detail = admin.getByTestId('time-exports-detail');
    await expect(detail.getByTestId('time-exports-detail-counts-timesheets')).toHaveText('1');
    await expect(detail.getByTestId('time-exports-detail-id')).not.toHaveText(firstExport);
    // Both exports are in the history, newest first; the first one was never changed.
    const items = admin.getByTestId('time-exports-item').filter({ hasText: SITE_NAME });
    await expect(items).toHaveCount(2);
    await expect(items.nth(0).getByTestId('time-exports-item-timesheets')).toHaveText('1');
    await expect(items.nth(1).getByTestId('time-exports-item-timesheets')).toHaveText('2');
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp070e-4-history-fr');
    await clean(admin);
    await context.close();

    // An employee and the routed manager have no access: neither the page nor the API.
    for (const email of [ANA_EMAIL, MIA_EMAIL]) {
      const own = await signedIn(browser, email, 'fr');
      await expect(
        primaryNav(own.page).getByRole('link', { name: 'Exports de temps', exact: true }),
      ).toHaveCount(0);
      // In-app navigation keeps the in-memory session (a reload would sign the user out).
      await own.page.evaluate(() => {
        window.history.pushState({}, '', '/admin/time-exports');
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      await expect(own.page.getByTestId('not-authorized')).toHaveText(
        'Seul un administrateur de l’organisation avec authentification multifacteur peut exporter le temps approuvé.',
      );
      await expect(own.page.getByTestId('time-exports')).toHaveCount(0);
      await accessible(own.page);
      for (const path of ['/timesheet-exports', `/timesheet-exports/${firstExport}/csv`]) {
        const denied = await own.page.request.get(`${CORE_API}${path}`, {
          headers: { Authorization: own.bearer() },
        });
        expect(denied.status(), path).toBe(403);
      }
      const refused = await own.page.request.post(`${CORE_API}/timesheet-exports/preview`, {
        headers: { Authorization: own.bearer() },
        data: { legalEntityId: entityId, siteId, weekStart: monday },
      });
      expect(refused.status()).toBe(403);
      await clean(own.page);
      await own.close();
    }
  });
});

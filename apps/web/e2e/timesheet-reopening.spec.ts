import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
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

// MVP-070D (Issue #132): reopening approved time with the real identity provider, database and
// server clock. Setup (API, the administrator's existing SSO session): Ana, Cléo and Mia are
// imported at a new site; Mia manages Ana and Cléo from today; one schedule of today holds one
// short, already ended shift each for Ana and Cléo, completed by an administrator's correction; the
// administrator generates the week; Ana, Cléo and Mia are invited and linked; Ana and Cléo submit
// and Mia approves both. Exactly two journeys:
//  - English: an administrator's correction of Ana's approved attendance reopens her timesheet;
//    the administrator previews and applies the refresh (Ana's reopened timesheet becomes a draft,
//    Cléo's approval stays locked); Ana sees why and resubmits cycle 2; Mia approves it again; the
//    first approval stays in the history;
//  - French : une correction de la présence approuvée de Cléo rouvre sa feuille ; Cléo voit
//    « Rouverte », l'avis et tout l'historique, sans pouvoir soumettre ; Mia n'a rien à décider ;
//    l'administratrice voit l'historique sans commande de décision.
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. Materiality,
// races, tenant scope, audits, events, rollback and query plans are proven at API and database
// level, not here.

// Tenant B's administrator (its own rate-limit buckets: the MVP-070A and MVP-070B/C journeys use
// tenant A's administrator).
const TENANT_B = '00000000-0000-4000-8000-00000000000b';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `RE-${stamp}`;
const SITE = `RS-${stamp}`;
const DEPARTMENT = `RD-${stamp}`;
const ANA = `TRA-${stamp}-1`;
const CLEO = `TRC-${stamp}-2`;
const MIA = `TRM-${stamp}-3`;
const ANA_FAMILY = `Kabongo${letters}`;
const CLEO_FAMILY = `Mutombo${letters}`;
const MIA_FAMILY = `Lukusa${letters}`;
const ANA_EMAIL = `e2e.reopen.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.reopen.cleo.${lower}@example.test`;
const MIA_EMAIL = `e2e.reopen.mia.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Apv!`;
const SITE_NAME = `Site Réouverture ${stamp}`;
const ANA_EN = `Gate patrol ${letters}`;
const ANA_FR = `Ronde au portail ${letters}`;
const CLEO_EN = `Loading dock ${letters}`;
const CLEO_FR = `Quai de chargement ${letters}`;
const EXPLANATION = 'The badge reader at the dock was offline during this shift.';

let ZONE = 'Africa/Lubumbashi';
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

async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tr-${key}-${stamp}-0001` },
    ...(data === undefined ? {} : { data }),
  });
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

/** A signed-in employee in their own browser context. */
async function employee(
  browser: Browser,
  email: string,
  language: 'en' | 'fr',
): Promise<{ page: Page; close: () => Promise<void> }> {
  const context = await browser.newContext({ locale: language === 'en' ? 'en-US' : 'fr-FR' });
  const page = await context.newPage();
  await signIn(page, [email, PASSWORD], language, undefined);
  return { page, close: () => context.close() };
}

async function openMine(page: Page, language: 'en' | 'fr'): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'My timesheets' : 'Mes feuilles de temps', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'My timesheets' : 'Mes feuilles de temps',
  );
  const cards = page.getByTestId('my-timesheet');
  await expect(cards).toHaveCount(1);
  await cards.getByTestId('my-timesheet-view').click();
  const detail = page.getByTestId('my-timesheet-detail');
  await expect(detail.getByTestId('timesheet-row')).toHaveCount(1);
  return detail;
}

async function submitOwn(page: Page, detail: Locator, language: 'en' | 'fr') {
  const en = language === 'en';
  const submission = detail.getByTestId('my-timesheet-submission');
  await expect(submission).toContainText(
    en
      ? 'This week is complete. You can submit it to your manager for approval.'
      : 'Cette semaine est complète. Vous pouvez la soumettre à votre responsable pour approbation.',
  );
  await submission
    .getByRole('button', { name: en ? 'Submit timesheet' : 'Soumettre la feuille de temps' })
    .click();
  const confirm = submission.getByRole('button', {
    name: en ? 'Submit' : 'Soumettre',
    exact: true,
  });
  await expect(confirm).toBeDisabled();
  await submission
    .getByLabel(
      en
        ? 'I reviewed this timesheet and want to submit it to my manager.'
        : 'J’ai vérifié cette feuille de temps et je veux la soumettre à mon responsable.',
    )
    .check();
  await accessible(page);
  return confirm;
}

async function openApprovals(
  page: Page,
  language: 'en' | 'fr',
  family: string,
  worked: string,
): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Time approvals' : 'Approbations du temps', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Time approvals' : 'Approbations du temps',
  );
  const card = page.getByTestId('time-approval').filter({ hasText: family });
  await expect(card).toHaveCount(1);
  await expect(card).toContainText(SITE_NAME);
  await expect(card.getByTestId('time-approval-worked')).toHaveText(worked);
  return card;
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

/** The Timesheets page with the run's site and week chosen through its own controls. */
async function openWeek(page: Page, language: 'en' | 'fr'): Promise<Locator> {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Timesheets' : 'Feuilles de temps', exact: true })
    .click();
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
  await expect(generation.getByTestId('timesheets-draft')).toHaveCount(2);
  return generation;
}

function history(scope: Locator) {
  return scope.getByTestId('timesheet-action');
}

let releaseSubject: () => void = () => undefined;

test.describe.serial('MVP-070D: reopen and reapprove approved time', () => {
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
      name: `Réouverture Entité ${stamp}`,
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
        'Idempotency-Key': `e2e-tr-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tr-commit-${stamp}-0001` },
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

  test('a correction reopens Ana’s approved week, which is refreshed, resubmitted and approved again, in English', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'en-US' });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'en');
    const receipt = await corrected(admin, bearer, ANA_EN, 'fix-ana');
    expect(receipt.reopenedTimesheets).toBe(1);

    // Ana sees why another review is needed; nothing can be submitted before the refresh.
    const ana = await employee(browser, ANA_EMAIL, 'en');
    let detail = await openMine(ana.page, 'en');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Reopened');
    const reopened = detail.getByTestId('my-timesheet-reopened');
    await expect(reopened).toContainText('Attendance changed after approval');
    await expect(reopened.getByTestId('my-timesheet-reopened-next')).toContainText(
      'Refresh before resubmission.',
    );
    await expect(history(detail)).toHaveCount(3);
    await expect(history(detail).nth(0)).toHaveAttribute('data-action', 'REOPENED');
    await expect(history(detail).nth(1)).toHaveAttribute('data-action', 'APPROVED');
    await expect(detail.getByTestId('my-timesheet-submission')).toHaveCount(0);
    await accessible(ana.page);
    await noOverflow(ana.page);
    await evidence(ana.page, 'mvp070d-1-employee-reopened-en');
    await clean(ana.page);

    // The administrator previews the refresh: Ana's reopened timesheet changes; Cléo's approval
    // stays locked; then applies it after confirming.
    const generation = await openWeek(admin, 'en');
    await expect(
      generation
        .getByTestId('timesheets-draft')
        .filter({ hasText: ANA_FAMILY })
        .getByTestId('timesheets-draft-status'),
    ).toHaveText('Reopened');
    await generation.getByRole('button', { name: 'Preview refresh' }).click();
    const refresh = generation.getByTestId('timesheets-refresh');
    await expect(refresh.getByTestId('timesheets-refresh-reopened')).toHaveText('1');
    await expect(refresh.getByTestId('timesheets-refresh-locked')).toHaveText('1');
    await expect(refresh.getByTestId('timesheets-refresh-reopened-note')).toContainText(
      'attendance changed after approval',
    );
    const apply = refresh.getByRole('button', { name: 'Apply refresh' });
    await expect(apply).toBeDisabled();
    await refresh.getByLabel('I reviewed this preview and want to apply the refresh.').check();
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp070d-2-admin-refresh-en', refresh);
    await apply.click();
    await expect(admin.getByTestId('timesheets-announcer')).toHaveText(
      'Refresh applied: version 2.',
    );
    await clean(admin);

    // Ana reviews the refreshed revision and submits it as cycle 2.
    await primaryNav(ana.page).getByRole('link', { name: 'Home', exact: true }).click();
    detail = await openMine(ana.page, 'en');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Draft');
    await expect(detail.getByTestId('my-timesheet-reopened-next')).toContainText(
      'submit it again as cycle 2',
    );
    const confirm = await submitOwn(ana.page, detail, 'en');
    await evidence(ana.page, 'mvp070d-3-employee-resubmit-en', detail);
    await confirm.click();
    await expect(detail.getByTestId('my-timesheet-awaiting')).toContainText('(cycle 2)');

    // Mia approves cycle 2; the first approval stays in the history.
    const mia = await employee(browser, MIA_EMAIL, 'en');
    // The corrected check-in is five minutes later: 0 h 10 min instead of 0 h 15 min.
    const card = await openApprovals(mia.page, 'en', ANA_FAMILY, '0 h 10 min');
    await card
      .getByRole('button', { name: `Review the timesheet of Ana ${ANA_FAMILY} (${ANA})` })
      .click();
    const review = mia.page.getByTestId('time-approval-detail');
    await expect(review.getByTestId('time-approval-status')).toHaveText('Submitted');
    await expect(history(review)).toHaveCount(4);
    await expect(history(review).nth(1)).toHaveAttribute('data-action', 'REOPENED');
    await review.getByRole('button', { name: 'Approve', exact: true }).click();
    await review.getByLabel('I reviewed this timesheet and approve it.').check();
    await accessible(mia.page);
    await evidence(mia.page, 'mvp070d-4-manager-reapprove-en', review);
    await review.getByRole('button', { name: 'Confirm approval' }).click();
    await expect(mia.page.getByTestId('time-approvals-receipt')).toHaveText(
      `Timesheet of Ana ${ANA_FAMILY} (${ANA}) approved.`,
    );
    await clean(mia.page);
    await mia.close();

    await primaryNav(ana.page).getByRole('link', { name: 'Home', exact: true }).click();
    detail = await openMine(ana.page, 'en');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Approved');
    await expect(detail.getByTestId('my-timesheet-approved')).toContainText('(cycle 2)');
    await expect(history(detail)).toHaveCount(5);
    await accessible(ana.page);
    await clean(ana.page);
    await ana.close();
    await context.close();
    expect(
      sql(
        `SELECT string_agg(action || ':' || cycle || ':' || revision, ',' ORDER BY sequence)
         FROM people.timesheet_action WHERE timesheet_id = '${anaSheet}'`,
      ),
    ).toBe('SUBMITTED:1:1,APPROVED:1:1,REOPENED:1:1,SUBMITTED:2:2,APPROVED:2:2');
    expect(
      sql(
        `SELECT status || ':' || submission_cycle FROM people.timesheet WHERE id = '${cleoSheet}'`,
      ),
    ).toBe('APPROVED:1');
  });

  test('une correction rouvre la feuille approuvée de Cléo : avis, historique et limites d’autorisation, en français', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminB, 'fr');
    const receipt = await corrected(admin, bearer, CLEO_EN, 'fix-cleo');
    expect(receipt.reopenedTimesheets).toBe(1);

    const cleo = await signedIn(browser, CLEO_EMAIL, 'fr');
    const detail = await openMine(cleo.page, 'fr');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Rouverte');
    const reopened = detail.getByTestId('my-timesheet-reopened');
    await expect(reopened).toContainText('La présence a changé après l’approbation');
    await expect(reopened.getByTestId('my-timesheet-reopened-next')).toContainText(
      'Actualisez avant une nouvelle soumission.',
    );
    await expect(history(detail)).toHaveCount(3);
    await expect(history(detail).nth(0)).toContainText('Rouverte');
    await expect(history(detail).nth(1)).toContainText('Approuvée');
    await expect(detail.getByTestId('my-timesheet-submission')).toHaveCount(0);
    await expect(detail).not.toContainText(MIA_FAMILY);
    await expect(detail).not.toContainText(EXPLANATION);
    await accessible(cleo.page);
    await noOverflow(cleo.page);
    await evidence(cleo.page, 'mvp070d-5-employee-reopened-fr');
    await clean(cleo.page);
    // Cléo cannot submit a reopened timesheet before the refresh.
    const own = (await (
      await cleo.page.request.get(`${CORE_API}/me/timesheets/${cleoSheet}`, {
        headers: { Authorization: cleo.bearer() },
      })
    ).json()) as { revision: number; generationVersion: number };
    const refused = await post(
      cleo.page,
      cleo.bearer,
      `/me/timesheets/${cleoSheet}/submissions`,
      'sub-reopened',
      { revision: own.revision, generationVersion: own.generationVersion, confirm: true },
    );
    expect(refused.status()).toBe(409);
    expect(((await refused.json()) as { code: string }).code).toBe(
      'TIMESHEET_SUBMISSION_NOT_ALLOWED',
    );
    await cleo.close();

    // Mia has nothing to decide: the reopening places nothing in her queue.
    const mia = await signedIn(browser, MIA_EMAIL, 'fr');
    await primaryNav(mia.page)
      .getByRole('link', { name: 'Approbations du temps', exact: true })
      .click();
    await expect(mia.page.getByTestId('time-approvals-empty')).toBeVisible();
    const read = await mia.page.request.get(`${CORE_API}/me/time-approvals/${cleoSheet}`, {
      headers: { Authorization: mia.bearer() },
    });
    expect(read.status()).toBe(404);
    await clean(mia.page);
    await mia.close();

    // The administrator reads the state and the whole history; no decision is offered.
    const generation = await openWeek(admin, 'fr');
    await generation
      .getByRole('button', {
        name: `Consulter la feuille de temps de Cléo ${CLEO_FAMILY} (${CLEO})`,
      })
      .click();
    const sheet = admin.getByTestId('timesheets-detail');
    await expect(sheet.getByTestId('timesheets-detail-status')).toHaveText('Rouverte');
    await expect(sheet.getByTestId('timesheets-detail-reopened')).toContainText(
      'Actualisez avant une nouvelle soumission',
    );
    await expect(history(sheet)).toHaveCount(3);
    await expect(sheet.getByRole('button', { name: 'Approuver', exact: true })).toHaveCount(0);
    await expect(sheet.getByRole('button', { name: 'Renvoyer', exact: true })).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp070d-6-admin-reopened-fr');
    await clean(admin);
    await context.close();
    expect(
      sql(
        `SELECT status || ':' || submission_cycle FROM people.timesheet WHERE id = '${cleoSheet}'`,
      ),
    ).toBe('REOPENED:1');
  });
});

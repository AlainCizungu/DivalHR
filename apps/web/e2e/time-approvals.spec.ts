import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import {
  CORE_API,
  acceptInvitation,
  TENANT_A,
  USERS,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  sql,
  typeSecret,
} from './support';

// MVP-070B/C (Issue #130): weekly time submission and approval with the real identity provider,
// database and server clock. Setup (API, the administrator's existing SSO session): Ana, Cléo and
// Mia are imported at a new site; Mia manages Ana and Cléo from today; one schedule of today holds
// one short, already ended shift each for Ana and Cléo, completed by an administrator's correction;
// the administrator generates the week; Ana, Cléo and Mia are invited and linked. Exactly two
// journeys:
//  - English: Ana submits her clean timesheet after confirming; Mia, the routed manager, approves
//    it from "Time approvals"; Ana sees it Approved with its immutable history;
//  - French : Cléo soumet sa feuille de temps ; Mia la renvoie avec un motif depuis « Approbations
//    du temps » ; Cléo voit le motif, la feuille redevient un brouillon et sa nouvelle soumission
//    crée le cycle 2.
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. Blockers,
// stale evidence, routing changes, concurrency, tenant scope, audits, events and query plans are
// proven at API and database level, not here.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `AE-${stamp}`;
const SITE = `AS-${stamp}`;
const DEPARTMENT = `AD-${stamp}`;
const ANA = `TAA-${stamp}-1`;
const CLEO = `TAC-${stamp}-2`;
const MIA = `TAM-${stamp}-3`;
const ANA_FAMILY = `Kabongo${letters}`;
const CLEO_FAMILY = `Mutombo${letters}`;
const MIA_FAMILY = `Lukusa${letters}`;
const ANA_EMAIL = `e2e.approval.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.approval.cleo.${lower}@example.test`;
const MIA_EMAIL = `e2e.approval.mia.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Apv!`;
const SITE_NAME = `Site Approbations ${stamp}`;
const ANA_EN = `Gate patrol ${letters}`;
const ANA_FR = `Ronde au portail ${letters}`;
const CLEO_EN = `Loading dock ${letters}`;
const CLEO_FR = `Quai de chargement ${letters}`;
const EXPLANATION = 'The badge reader at the dock was offline during this shift.';
const REASON = `La pause de midi manque sur ce poste ${stamp}.`;

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
    `SELECT id FROM people.employee WHERE tenant_id = '${TENANT_A}' AND employee_number = '${number}'`,
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
     FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_A}' AND label_en = '${labelEn}'`,
  );
}

function slotId(labelEn: string): string {
  return sql(
    `SELECT id FROM people.work_schedule_slot WHERE tenant_id = '${TENANT_A}'
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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ta-${key}-${stamp}-0001` },
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

async function openApprovals(page: Page, language: 'en' | 'fr', family: string): Promise<Locator> {
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
  await expect(card.getByTestId('time-approval-worked')).toHaveText('0 h 15 min');
  return card;
}

test.describe.serial('MVP-070B/C: submit and approve weekly time', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(480_000);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Approbations Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const entityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: entityId,
      code: SITE,
      name: SITE_NAME,
      timezone: ZONE,
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    const siteId = ((await site.json()) as { id: string }).id;
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
    // Today at the site, all ended, from one clock reading: Ana 15 minutes from an hour ago,
    // Cléo 15 minutes from 40 minutes ago.
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
        'Idempotency-Key': `e2e-ta-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ta-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    // Mia manages Ana and Cléo from today (so on the week's Sunday too).
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
    const monday = sql(`SELECT (date_trunc('week', DATE '${day}'))::date::text`);
    const generated = await post(page, bearer, '/timesheets/generations', 'gen', {
      siteId,
      weekStart: monday,
      confirm: true,
    });
    expect(generated.status()).toBe(200);
    anaSheet = sql(
      `SELECT id FROM people.timesheet WHERE tenant_id = '${TENANT_A}'
       AND employee_id = '${employeeId(ANA)}'`,
    );
    cleoSheet = sql(
      `SELECT id FROM people.timesheet WHERE tenant_id = '${TENANT_A}'
       AND employee_id = '${employeeId(CLEO)}'`,
    );
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await inviteAndLink(page, browser, bearer, CLEO_EMAIL, employeeId(CLEO), 'cleo');
    await inviteAndLink(page, browser, bearer, MIA_EMAIL, employeeId(MIA), 'mia');
    await context.close();
  });

  test('Ana submits her clean week and her manager approves it, in English', async ({
    browser,
  }) => {
    test.setTimeout(240_000);
    const ana = await employee(browser, ANA_EMAIL, 'en');
    let detail = await openMine(ana.page, 'en');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Draft');
    await evidence(ana.page, 'mvp070bc-1-employee-ready-en');
    const confirm = await submitOwn(ana.page, detail, 'en');
    await evidence(ana.page, 'mvp070bc-2-employee-confirm-en', detail);
    await confirm.click();
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Submitted');
    await expect(detail.getByTestId('my-timesheet-awaiting')).toContainText(
      'Awaiting manager review.',
    );
    await expect(detail.getByTestId('my-timesheet-awaiting')).toContainText('(cycle 1)');
    await expect(detail.getByTestId('my-timesheet-submission')).toHaveCount(0);
    // The manager is never named to the employee.
    await expect(ana.page.getByTestId('my-timesheets')).not.toContainText(MIA_FAMILY);
    await accessible(ana.page);
    await noOverflow(ana.page);
    await evidence(ana.page, 'mvp070bc-3-employee-submitted-en');
    await clean(ana.page);

    const mia = await employee(browser, MIA_EMAIL, 'en');
    const card = await openApprovals(mia.page, 'en', ANA_FAMILY);
    await expect(
      mia.page.getByTestId('time-approval').filter({ hasText: CLEO_FAMILY }),
    ).toHaveCount(0);
    await accessible(mia.page);
    await evidence(mia.page, 'mvp070bc-4-manager-queue-en');
    await card
      .getByRole('button', { name: `Review the timesheet of Ana ${ANA_FAMILY} (${ANA})` })
      .click();
    const review = mia.page.getByTestId('time-approval-detail');
    await expect(review.getByTestId('time-approval-status')).toHaveText('Submitted');
    await expect(review.getByTestId('timesheet-row')).toHaveCount(1);
    await expect(review.getByTestId('timesheet-row')).toContainText(ANA_EN);
    await expect(review.getByTestId('timesheet-action')).toHaveCount(1);
    await expect(review).not.toContainText(EXPLANATION);
    await review.getByRole('button', { name: 'Approve', exact: true }).click();
    const approve = review.getByRole('button', { name: 'Confirm approval' });
    await expect(approve).toBeDisabled();
    await review.getByLabel('I reviewed this timesheet and approve it.').check();
    await accessible(mia.page);
    await noOverflow(mia.page);
    await evidence(mia.page, 'mvp070bc-5-manager-approve-en', review);
    await approve.click();
    await expect(mia.page.getByTestId('time-approvals-receipt')).toHaveText(
      `Timesheet of Ana ${ANA_FAMILY} (${ANA}) approved.`,
    );
    await expect(mia.page.getByTestId('time-approval').filter({ hasText: ANA_FAMILY })).toHaveCount(
      0,
    );
    await accessible(mia.page);
    await evidence(mia.page, 'mvp070bc-6-manager-approved-en');
    await clean(mia.page);
    await mia.close();

    // Back through the app (a reload would end the in-memory session).
    await primaryNav(ana.page).getByRole('link', { name: 'Home', exact: true }).click();
    detail = await openMine(ana.page, 'en');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Approved');
    await expect(detail.getByTestId('my-timesheet-approved')).toContainText('(cycle 1)');
    const history = detail.getByTestId('timesheet-action');
    await expect(history).toHaveCount(2);
    await expect(history.nth(0)).toHaveAttribute('data-action', 'APPROVED');
    await expect(history.nth(1)).toHaveAttribute('data-action', 'SUBMITTED');
    await accessible(ana.page);
    await noOverflow(ana.page);
    await evidence(ana.page, 'mvp070bc-7-employee-approved-en');
    await clean(ana.page);
    await ana.close();
    expect(
      sql(
        `SELECT string_agg(action || ':' || cycle, ',' ORDER BY sequence)
         FROM people.timesheet_action WHERE timesheet_id = '${anaSheet}'`,
      ),
    ).toBe('SUBMITTED:1,APPROVED:1');
  });

  test('Cléo soumet, sa responsable la renvoie avec un motif, puis elle soumet le cycle 2, en français', async ({
    browser,
  }) => {
    test.setTimeout(240_000);
    const cleo = await employee(browser, CLEO_EMAIL, 'fr');
    let detail = await openMine(cleo.page, 'fr');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Brouillon');
    await (await submitOwn(cleo.page, detail, 'fr')).click();
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Soumise');
    await expect(detail.getByTestId('my-timesheet-awaiting')).toContainText(
      'En attente de l’examen du responsable.',
    );

    const mia = await employee(browser, MIA_EMAIL, 'fr');
    const card = await openApprovals(mia.page, 'fr', CLEO_FAMILY);
    await card
      .getByRole('button', {
        name: `Examiner la feuille de temps de Cléo ${CLEO_FAMILY} (${CLEO})`,
      })
      .click();
    const review = mia.page.getByTestId('time-approval-detail');
    await expect(review.getByTestId('timesheet-row')).toContainText(CLEO_FR);
    await review.getByRole('button', { name: 'Renvoyer', exact: true }).click();
    const reason = review.getByLabel('Motif du renvoi');
    await expect(reason).toBeFocused();
    await reason.fill('Trop bref');
    await review.getByLabel('Je veux renvoyer cette feuille de temps à l’employé.').check();
    await review.getByRole('button', { name: 'Confirmer le renvoi' }).click();
    await expect(review.getByTestId('time-approval-reason-error')).toHaveText(
      'Saisissez de 10 à 500 caractères.',
    );
    await reason.fill(REASON);
    await accessible(mia.page);
    await noOverflow(mia.page);
    await evidence(mia.page, 'mvp070bc-8-manager-return-fr', review);
    await review.getByRole('button', { name: 'Confirmer le renvoi' }).click();
    await expect(mia.page.getByTestId('time-approvals-receipt')).toHaveText(
      `Feuille de temps de Cléo ${CLEO_FAMILY} (${CLEO}) renvoyée à l’employé.`,
    );
    await expect(mia.page.getByTestId('time-approvals-empty')).toBeVisible();
    await evidence(mia.page, 'mvp070bc-9-manager-returned-fr');
    await clean(mia.page);
    await mia.close();

    await primaryNav(cleo.page).getByRole('link', { name: 'Accueil', exact: true }).click();
    detail = await openMine(cleo.page, 'fr');
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Brouillon');
    const returned = detail.getByTestId('my-timesheet-returned');
    await expect(returned).toContainText('Renvoyée par votre responsable');
    await expect(returned.getByTestId('my-timesheet-returned-reason')).toHaveText(REASON);
    await expect(returned).toContainText(
      'Régularisez les présences avec votre organisation. Une fois la feuille de temps mise à jour, soumettez-la de nouveau.',
    );
    await expect(detail.getByTestId('timesheet-action')).toHaveCount(2);
    await accessible(cleo.page);
    await noOverflow(cleo.page);
    await evidence(cleo.page, 'mvp070bc-10-employee-returned-fr');
    // Nothing changed in her sources, so she is still eligible: the resubmission is cycle 2.
    await (await submitOwn(cleo.page, detail, 'fr')).click();
    await expect(detail.getByTestId('my-timesheet-detail-status')).toHaveText('Soumise');
    await expect(detail.getByTestId('my-timesheet-awaiting')).toContainText('(cycle 2)');
    const history = detail.getByTestId('timesheet-action');
    await expect(history).toHaveCount(3);
    await expect(history.nth(1).getByTestId('timesheet-action-reason')).toHaveText(REASON);
    await accessible(cleo.page);
    await evidence(cleo.page, 'mvp070bc-11-employee-resubmitted-fr');
    await clean(cleo.page);
    await cleo.close();
    expect(
      sql(
        `SELECT string_agg(action || ':' || cycle, ',' ORDER BY sequence)
         FROM people.timesheet_action WHERE timesheet_id = '${cleoSheet}'`,
      ),
    ).toBe('SUBMITTED:1,RETURNED:1,SUBMITTED:2');
    expect(
      sql(
        `SELECT status || ':' || submission_cycle FROM people.timesheet WHERE id = '${cleoSheet}'`,
      ),
    ).toBe('SUBMITTED:2');
  });
});

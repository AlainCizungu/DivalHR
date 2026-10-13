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

// MVP-060C (Issue #119): factual attendance exceptions with the real identity provider, database
// and server clock. Setup (API, the administrator's existing SSO session): Mia and Ana are imported
// at a new site, Ana reports to Mia, and one schedule of today holds two of Ana's shifts: one that
// ended a quarter of an hour ago without any check-in, and one that started ten minutes ago. Ana
// checks in to the second now and checks out at once, so her three exceptions exist at the
// server's instant: « Aucune arrivée enregistrée », « Arrivée après l’heure prévue » and « Départ
// avant l’heure prévue ». Two journeys:
//  - English: Mia reviews her direct report's exceptions in "Team attendance" and filters by type;
//  - French : l'administratrice suit la présence du site sous « Opérations », puis Ana voit ses
//    propres anomalies dans « Ma présence », sans identité.
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. Boundaries,
// scopes, cursors, audits and query plans are proven at API and database level, not here.
// The site's zone is whichever of the two supported zones is at least an hour into its local day,
// so the past shifts can be published for today (a publication never starts before today).

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `XE-${stamp}`;
const SITE = `XS-${stamp}`;
const DEPARTMENT = `XD-${stamp}`;
const MIA = `XAM-${stamp}-1`;
const ANA = `XAA-${stamp}-2`;
const MIA_FAMILY = `Mbuyi${letters}`;
const ANA_FAMILY = `Kalala${letters}`;
const MIA_EMAIL = `e2e.anomalie.mia.${lower}@example.test`;
const ANA_EMAIL = `e2e.anomalie.ana.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Exc!`;
const SITE_NAME = `Site Anomalies ${stamp}`;
const MISSED_EN = `Stock count ${letters}`;
const MISSED_FR = `Inventaire ${letters}`;
const LATE_EN = `Cashier ${letters}`;
const LATE_FR = `Caissière ${letters}`;

let ZONE = 'Africa/Lubumbashi';
let entityId = '';
let siteId = '';

/**
 * PR evidence (EVIDENCE=1 only): a full-page screenshot written outside the working tree. The
 * pages hold only this run's generated test data.
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

function today(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization
     WHERE id = '${TENANT_A}'`,
  );
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

/** The site-local date-time `minutes` from now, `YYYY-MM-DDTHH:MM` (the server's own clock). */
function localFromNow(minutes: number): string {
  return sql(
    `SELECT to_char((now() + interval '${minutes} minutes') AT TIME ZONE '${ZONE}',
     'YYYY-MM-DD"T"HH24:MI')`,
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

async function clean(page: Page) {
  // The run's own markers only: the stamp, the synthetic family names and the slot labels. Never
  // the stamp's bare letter fragment, which can be one or two letters that occur in any route.
  const markers = new RegExp(
    `${stamp}|${MIA_FAMILY}|${ANA_FAMILY}|Caissi|Cashier|Inventaire|Stock count`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state|siteId|type|from|to)=/u);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ax-${key}-${stamp}-0001` },
    ...(data === undefined ? {} : { data }),
  });
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
  const context = await browser.newContext({ locale: 'fr-FR' });
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

/**
 * The report's manager from yesterday (the organization's date) on: the line covers today's
 * shifts whichever of the two zones the site uses. A past date is a retroactive change.
 */
async function manage(page: Page, bearer: () => string, report: string, manager: string) {
  const change = {
    type: 'CHANGE',
    effectiveFrom: plusDays(today(), -1),
    manager: { employeeId: manager },
    reasonCode: 'LATE_NOTIFICATION',
  };
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
      await post(page, bearer, `/employees/${report}/employment-changes`, 'mgr', {
        ...change,
        expectedVersion: previewed.expectedVersion,
        previewDigest: previewed.previewDigest,
        acknowledgeRetroactive: previewed.requiresAcknowledgement,
      })
    ).status(),
  ).toBe(201);
}

/** The exception cards of Ana's shifts in a list. */
function anaCards(scope: Locator): Locator {
  return scope.getByTestId('attendance-exception').filter({ hasText: SITE_NAME });
}

test.describe.serial('MVP-060C: factual attendance exceptions', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(420_000);
    ZONE = chooseZone();
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Anomalies Entité ${stamp}`,
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
          name: `Caisse ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const hired = plusDays(today(), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${MIA};Mia;${MIA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${ANA};Ana;${ANA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-ax-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ax-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    await manage(page, bearer, employeeId(ANA), employeeId(MIA));

    // Today at the site: a shift that ended 15 minutes ago (never checked in) and one that started
    // 10 minutes ago and ends in two hours.
    const missedStart = localFromNow(-50);
    const day = missedStart.slice(0, 10);
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'missed',
          start: missedStart,
          end: localFromNow(-15),
          labels: { en: MISSED_EN, fr: MISSED_FR },
          employeeId: employeeId(ANA),
        },
        {
          clientRef: 'late',
          start: localFromNow(-10),
          end: localFromNow(120),
          labels: { en: LATE_EN, fr: LATE_FR },
          employeeId: employeeId(ANA),
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
    await inviteAndLink(page, browser, bearer, MIA_EMAIL, employeeId(MIA), 'mia');
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await context.close();

    // Ana checks in late and checks out at once (the server's instants).
    const anaContext = await browser.newContext({ locale: 'fr-FR' });
    const anaPage = await anaContext.newPage();
    const anaBearer = await signIn(anaPage, [ANA_EMAIL, PASSWORD], 'fr', undefined);
    const checkIn = await post(anaPage, anaBearer, '/me/attendance/check-in', 'in', {
      slotId: slotId(LATE_EN),
    });
    expect(checkIn.status()).toBe(201);
    const record = ((await checkIn.json()) as { attendanceRecordId: string }).attendanceRecordId;
    expect(
      (await post(anaPage, anaBearer, `/me/attendance/${record}/check-out`, 'out')).status(),
    ).toBe(200);
    await anaContext.close();
  });

  test('Mia reviews and filters her direct report’s exceptions, in English', async ({ page }) => {
    test.setTimeout(180_000);
    await signIn(page, [MIA_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(page).getByRole('link', { name: 'Team attendance', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Team attendance');
    const section = page.getByTestId('attendance-exceptions-team');
    await expect(section.getByRole('heading', { level: 2, name: 'Exceptions' })).toBeVisible();
    const cards = anaCards(section);
    await expect(cards).toHaveCount(3);
    // Newest shift first; within a shift, the stable type order.
    await expect(cards.getByTestId('attendance-exception-type')).toHaveText([
      'Checked in after scheduled start',
      'Checked out before scheduled end',
      'No check-in recorded',
    ]);
    await expect(cards.first().getByTestId('attendance-exception-heading')).toHaveText(
      `Ana ${ANA_FAMILY} (${ANA})`,
    );
    await expect(cards.nth(2)).toContainText(MISSED_EN);
    await expect(cards.nth(0).getByTestId('attendance-exception-variance')).toHaveText(
      /^\d+ min after the scheduled start$/u,
    );
    await expect(cards.nth(1).getByTestId('attendance-exception-variance')).toHaveText(
      /^\d+ min before the scheduled end$/u,
    );
    await expect(cards.nth(2).getByTestId('attendance-exception-variance')).toHaveCount(0);
    await expect(cards.first().getByTestId('attendance-exception-timezone')).toContainText(ZONE);
    await expect(section.getByTestId('attendance-exceptions-disclaimer')).toContainText(
      'not a pay, absence or disciplinary decision',
    );
    await expect(section.getByTestId('attendance-exceptions-corrections')).toContainText(
      'Only the organization’s administrators can correct attendance',
    );
    await expect(
      section.getByRole('button', { name: /correct|excuse|approve|justify/iu }),
    ).toHaveCount(0);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp060c-1-team-exceptions-en', section);

    await section
      .getByRole('combobox', { name: 'Type', exact: true })
      .selectOption({ label: 'Checked in after scheduled start' });
    await section.getByRole('button', { name: 'Apply filters' }).click();
    await expect(cards).toHaveCount(1);
    await expect(cards.getByTestId('attendance-exception-type')).toHaveText(
      'Checked in after scheduled start',
    );
    await expect(page.getByTestId('attendance-exceptions-announcer')).toContainText('shown');
    await expectAccessible(page);
    await evidence(page, 'mvp060c-2-team-exceptions-filtered-en', section);
    await section.getByRole('button', { name: 'Clear filters' }).click();
    await expect(cards).toHaveCount(3);
    await clean(page);
  });

  test('l’administratrice suit la présence du site, puis Ana voit ses anomalies, en français', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, USERS.adminA, 'fr');
    await primaryNav(page).getByRole('link', { name: 'Suivi de la présence', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Suivi de la présence');
    await expect(page.getByRole('navigation', { name: 'Fil d’Ariane' })).toContainText(
      'Opérations',
    );
    const review = page.getByTestId('attendance-exceptions-admin');
    await review.getByRole('combobox', { name: 'Entité juridique' }).selectOption(entityId);
    const siteSelect = review.getByRole('combobox', { name: 'Site', exact: true });
    await expect(siteSelect.locator('option')).toHaveCount(2);
    await siteSelect.selectOption(siteId);
    await review.getByRole('button', { name: 'Appliquer les filtres' }).click();
    const cards = review.getByTestId('attendance-exception');
    await expect(cards).toHaveCount(3);
    await expect(cards.getByTestId('attendance-exception-type')).toHaveText([
      'Arrivée après l’heure prévue',
      'Départ avant l’heure prévue',
      'Aucune arrivée enregistrée',
    ]);
    await expect(cards.first().getByTestId('attendance-exception-heading')).toHaveText(
      `Ana ${ANA_FAMILY} (${ANA})`,
    );
    await expect(cards.nth(2)).toContainText(MISSED_FR);
    await expect(cards.nth(0).getByTestId('attendance-exception-variance')).toHaveText(
      /^\d+ min après le début prévu$/u,
    );
    await expect(review.getByTestId('attendance-exceptions-corrections')).toContainText(
      'Corrigez un pointage, ou créez un pointage manquant',
    );
    // MVP-062: one correction action per card; never excuse or approve.
    await expect(review.getByRole('button', { name: /justif|approuv|excus/iu })).toHaveCount(0);
    await expect(review.getByTestId('attendance-exception-correct')).toHaveCount(3);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp060c-3-admin-review-fr');
    await review
      .getByRole('combobox', { name: 'Type', exact: true })
      .selectOption({ label: 'Aucune arrivée enregistrée' });
    await review.getByRole('button', { name: 'Appliquer les filtres' }).click();
    await expect(cards).toHaveCount(1);
    await expect(cards.getByTestId('attendance-exception-checked-in')).toHaveText('Non enregistré');
    await expectAccessible(page);
    await clean(page);

    const context = await browser.newContext({ locale: 'fr-FR' });
    const ana = await context.newPage();
    await signIn(ana, [ANA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(ana).getByRole('link', { name: 'Ma présence', exact: true }).click();
    await expect(ana.getByRole('heading', { level: 1 })).toHaveText('Ma présence');
    const mine = ana.getByTestId('attendance-exceptions-self');
    await expect(
      mine.getByRole('heading', { level: 2, name: 'Anomalies de présence' }),
    ).toBeVisible();
    const own = anaCards(mine);
    await expect(own).toHaveCount(3);
    await expect(own.getByTestId('attendance-exception-type')).toHaveText([
      'Arrivée après l’heure prévue',
      'Départ avant l’heure prévue',
      'Aucune arrivée enregistrée',
    ]);
    await expect(own.nth(1).getByTestId('attendance-exception-variance')).toHaveText(
      /^\d+ min avant la fin prévue$/u,
    );
    await expect(mine).not.toContainText(ANA);
    await expect(mine).not.toContainText(MIA_FAMILY);
    await expect(mine.getByTestId('attendance-exceptions-disclaimer')).toContainText(
      'Ce n’est pas une décision de paie, d’absence ni de discipline',
    );
    await expectAccessible(ana);
    await noOverflow(ana);
    await evidence(ana, 'mvp060c-4-employee-exceptions-fr', mine);
    await clean(ana);
    await context.close();
  });
});

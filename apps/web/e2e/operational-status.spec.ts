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

// MVP-100 (Issue #154): today's operational status with the real identity provider, database and
// server clock. Setup (API, the administrator's existing SSO session): Mia and Ana are imported at
// a new site, Ana reports to Mia, and one schedule of today holds two of Ana's shifts (one that
// started 50 minutes ago and was never checked in, one that started 10 minutes ago) and one shift
// nobody covers. Ana checks in to the second now and stays on it: it is Present and Late. The
// organization's figures are read through this run's legal-entity and site filters, so parallel
// specs on the same tenant never change them. Two journeys:
//  - English: the administrator opens "Today's operations" under Operations, filters by legal
//    entity and site, reads the counts, thresholds, instant and details, and refreshes;
//  - French : Mia (the manager) opens « Opérations de l’équipe » and sees only Ana's two shifts, no
//    uncovered notion, the neutral wording « Aucun pointage d’arrivée » and her own destinations.
// axe, reflow and target size at 320 px, 390 px, 640 px (200 % zoom of a 1280 px window), keyboard
// focus, browser storage and URLs are checked. Boundaries, scopes, audits, consistency with the
// approval queues and query plans are proven at API and database level, not here.
// The site's zone is whichever of the two supported zones is at least an hour into its local day,
// so the past shifts can be published for today (a publication never starts before today).

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `XOE-${stamp}`;
const SITE = `XOS-${stamp}`;
const DEPARTMENT = `XOD-${stamp}`;
const MIA = `XOM-${stamp}-1`;
const ANA = `XOA-${stamp}-2`;
const MIA_FAMILY = `Mbuyi${letters}`;
const ANA_FAMILY = `Kalala${letters}`;
const MIA_EMAIL = `e2e.ops.mia.${lower}@example.test`;
const ANA_EMAIL = `e2e.ops.ana.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Ops!`;
const SITE_NAME = `Site Opérations ${stamp}`;
const MISSED_EN = `Stock check ${letters}`;
const MISSED_FR = `Inventaire opérations ${letters}`;
const LATE_EN = `Cashier ${letters}`;
const LATE_FR = `Caissière ${letters}`;
const OPEN_EN = `Open till ${letters}`;
const OPEN_FR = `Caisse libre ${letters}`;

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

async function reflowAndAccessible(page: Page) {
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
    await expectAccessible(page);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

async function clean(page: Page) {
  // The run's own markers only: the stamp, the synthetic family names and the slot labels. Never
  // the stamp's bare letter fragment, which can be one or two letters that occur in any route.
  const markers = new RegExp(
    `${stamp}|${MIA_FAMILY}|${ANA_FAMILY}|Caissi|Cashier|Inventaire|Stock check|Open till`,
    'iu',
  );
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state|siteId|legalEntityId)=/u);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data?: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-os-${key}-${stamp}-0001` },
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

/** The organization, its people and the day's shifts; Ana checks in to the second shift. */
async function setup(browser: Browser) {
  ZONE = chooseZone();
  const context = await browser.newContext();
  const page = await context.newPage();
  const bearer = await signIn(page, USERS.adminA, 'fr');
  const entity = await post(page, bearer, '/legal-entities', 'le', {
    code: ENTITY,
    name: `Opérations Entité ${stamp}`,
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
      'Idempotency-Key': `e2e-os-import-${stamp}-0001`,
    },
    data: Buffer.from(file, 'utf8'),
  });
  expect(upload.status()).toBe(201);
  const imported = (await upload.json()) as { id: string; previewDigest: string };
  const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-os-commit-${stamp}-0001` },
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
      {
        clientRef: 'open',
        start: localFromNow(-5),
        end: localFromNow(60),
        labels: { en: OPEN_EN, fr: OPEN_FR },
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
  await anaContext.close();
}

test.describe.serial('MVP-100: today’s operational status', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(420_000);
    await setup(browser);
  });

  test('the administrator reads today’s operations of one site, in English', async ({ page }) => {
    test.setTimeout(180_000);
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Today’s operations', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Today’s operations');
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Operations');
    const view = page.getByTestId('operations-status-admin');
    await expect(view.getByTestId('status-shifts')).toBeVisible();

    await view.getByRole('combobox', { name: 'Legal entity' }).selectOption(entityId);
    const siteSelect = view.getByRole('combobox', { name: 'Site', exact: true });
    await expect(siteSelect.locator('option')).toHaveCount(2);
    await siteSelect.selectOption(siteId);
    await view.getByRole('button', { name: 'Apply filters' }).click();

    // Ana's two shifts, apart from the one nobody covers; one not checked in, one present and late.
    const tile = (id: string) => view.getByTestId(id).getByRole('definition');
    await expect(tile('tile-scheduled')).toHaveText('2');
    await expect(tile('tile-not-checked-in')).toHaveText('1');
    await expect(tile('tile-present')).toHaveText('1');
    await expect(tile('tile-completed')).toHaveText('0');
    await expect(tile('tile-uncovered')).toHaveText('1');
    await expect(view.getByTestId('tile-late')).toContainText('Late (a subset): 1 shift');
    await expect(view.getByTestId('tile-leave-overdue')).toHaveText('0 overdue');
    await expect(view.getByTestId('status-thresholds')).toContainText(
      'more than 48 hours (leave requests) or more than 72 hours (timesheets)',
    );
    await expect(view.getByTestId('operations-status-asof')).toContainText('As of');
    await expect(view.getByTestId('operations-status-disclaimer')).toContainText(
      'draws no conclusion',
    );
    const unchecked = view.getByTestId('details-not-checked-in');
    await expect(unchecked.getByTestId('detail-not-checked-in')).toHaveCount(1);
    await expect(unchecked).toContainText(`Ana ${ANA_FAMILY} (${ANA})`);
    await expect(unchecked).toContainText(MISSED_EN);
    const uncovered = view.getByTestId('details-uncovered');
    await expect(uncovered).toContainText(OPEN_EN);
    await expect(uncovered).not.toContainText(ANA_FAMILY);
    await expect(view.getByRole('button', { name: /approve|decline|correct/iu })).toHaveCount(0);
    await expect(page.locator('main')).not.toContainText(/\babsen(t|ce)/iu);
    await expectAccessible(page);
    await reflowAndAccessible(page);

    // Keyboard: the refresh control is reachable, visibly focused and re-reads the status.
    const refresh = view.getByTestId('operations-status-refresh');
    await refresh.focus();
    await expect(refresh).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(view.getByTestId('tile-scheduled')).toBeVisible();
    await expect(tile('tile-scheduled')).toHaveText('2');
    await evidence(page, 'mvp100-1-admin-operations-en');

    // The drill-down leads to an existing screen.
    await view.getByRole('link', { name: 'Open timesheets', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Timesheets/u);
    await clean(page);
  });

  test('Mia voit les opérations de son équipe, en français', async ({ page }) => {
    test.setTimeout(180_000);
    await signIn(page, [MIA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page)
      .getByRole('link', { name: 'Opérations de l’équipe', exact: true })
      .click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Opérations de l’équipe');
    const view = page.getByTestId('operations-status-team');
    await expect(view.getByTestId('status-shifts')).toBeVisible();
    await expect(
      view.getByRole('heading', { level: 2, name: 'Quarts planifiés', exact: true }),
    ).toBeVisible();
    const tile = (id: string) => view.getByTestId(id).getByRole('definition');
    // Only Ana's two shifts: never Mia's own, never the shift nobody covers.
    await expect(tile('tile-scheduled')).toHaveText('2');
    await expect(view.getByTestId('tile-not-checked-in').getByRole('term')).toHaveText(
      'Aucun pointage d’arrivée',
    );
    await expect(tile('tile-not-checked-in')).toHaveText('1');
    await expect(tile('tile-present')).toHaveText('1');
    await expect(view.getByTestId('tile-uncovered')).toHaveCount(0);
    await expect(view.getByTestId('details-uncovered')).toHaveCount(0);
    await expect(view.getByTestId('operations-status-filters')).toHaveCount(0);
    await expect(view.getByTestId('tile-late')).toContainText(
      'En retard (sous-ensemble) : 1 quart',
    );
    await expect(view.getByTestId('status-thresholds')).toContainText(
      'plus de 48 heures (demandes de congé) ou plus de 72 heures (feuilles de temps)',
    );
    await expect(view.getByTestId('tile-leave-within')).toContainText('dans le seuil');
    await expect(view.getByTestId('details-not-checked-in')).toContainText(
      `Ana ${ANA_FAMILY} (${ANA})`,
    );
    await expect(view.getByTestId('details-not-checked-in')).toContainText(MISSED_FR);
    await expect(view.getByTestId('details-overdue-leave')).toContainText('Aucun.');
    await expect(page.locator('main')).not.toContainText(/\babsen(t|ce)|Pointage absent/iu);
    await expectAccessible(page);
    await reflowAndAccessible(page);
    await evidence(page, 'mvp100-2-team-operations-fr');

    await view.getByRole('link', { name: 'Ouvrir la présence de l’équipe', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Présence de l’équipe');
    await clean(page);
  });
});

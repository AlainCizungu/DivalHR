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

// MVP-060B (Issue #115): supervisor-assisted attendance with the real identity provider, database
// and server clock. Setup (API, the administrator's existing SSO session): Mia and Ana are imported
// at a new site, Ana reports to Mia from today, a shift of Ana's starts half an hour from now in
// the site's time zone (so the check-in window is open), and both are invited and linked. Two
// journeys, each by the manager and then the employee:
//  - English: Mia sees the home task card, opens "Team attendance", records Ana's check-in with an
//    English reason; Ana finds it "Recorded by a supervisor" with the reason in "My attendance"
//    and "My schedule", and never Mia;
//  - French: Mia « Enregistrer le départ » d'Ana depuis « Présence de l’équipe » avec un motif en
//    français ; Ana voit « Enregistré par un responsable » et les deux motifs dans « Ma présence ».
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. Authority,
// windows, refusals and races are proven at API and database level, not here.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ZONE = 'Africa/Lubumbashi';
const ENTITY = `TE-${stamp}`;
const SITE = `TS-${stamp}`;
const DEPARTMENT = `TD-${stamp}`;
const MIA = `TAM-${stamp}-1`;
const ANA = `TAA-${stamp}-2`;
const MIA_FAMILY = `Mbuyi${letters}`;
const ANA_FAMILY = `Kalala${letters}`;
const MIA_EMAIL = `e2e.equipe.mia.${lower}@example.test`;
const ANA_EMAIL = `e2e.equipe.ana.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Team!`;
const SITE_NAME = `Site Équipe ${stamp}`;
const WORK_EN = `Cashier ${letters}`;
const WORK_FR = `Caissière ${letters}`;
const REASON_EN = 'Badge reader at the entrance was out of order';
const REASON_FR = 'Téléphone de la salariée en panne à Lubumbashi';

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

/** The site-local date-time `minutes` from now, `YYYY-MM-DDTHH:MM` (the server's own clock). */
function localFromNow(minutes: number): string {
  return sql(
    `SELECT to_char((now() + interval '${minutes} minutes') AT TIME ZONE '${ZONE}',
     'YYYY-MM-DD"T"HH24:MI')`,
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
  const markers = new RegExp(`${stamp}|${MIA_FAMILY}|${ANA_FAMILY}|Caissi|Cashier`, 'iu');
  expect(await storage(page)).not.toMatch(markers);
  expect(page.url()).not.toMatch(markers);
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state)=/u);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ta-${key}-${stamp}-0001` },
    data,
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

/** The report's manager from today on (employment-history API). */
async function manage(page: Page, bearer: () => string, report: string, manager: string) {
  const change = { type: 'CHANGE', effectiveFrom: today(), manager: { employeeId: manager } };
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

/** Fills the reason, confirms the open team-attendance dialog and waits for it to close. */
async function record(
  page: Page,
  title: string,
  language: string,
  reason: string,
  button: string,
  evidenceName: string,
) {
  const dialog = page.getByTestId('team-attendance-dialog');
  await expect(page.getByRole('dialog', { name: title })).toBeVisible();
  await expect(dialog.getByTestId('team-attendance-dialog-employee')).toContainText(
    `Ana ${ANA_FAMILY} (${ANA})`,
  );
  await expect(dialog.getByTestId('team-attendance-dialog-shift')).toContainText(SITE_NAME);
  await dialog.getByRole('combobox').selectOption({ label: language });
  await dialog.getByRole('textbox').fill(reason);
  await expectAccessible(page);
  await evidence(page, evidenceName);
  await dialog.getByRole('button', { name: button, exact: true }).click();
  await expect(dialog).toBeHidden();
}

/** Ana's own page in a separate browser context. */
async function asAna(browser: Browser, locale: 'en' | 'fr'): Promise<[Page, () => Promise<void>]> {
  const context = await browser.newContext({ locale: locale === 'fr' ? 'fr-FR' : 'en-US' });
  const page = await context.newPage();
  await signIn(page, [ANA_EMAIL, PASSWORD], locale, undefined);
  return [page, () => context.close()];
}

test.describe.serial('MVP-060B: a manager records a direct report’s attendance', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(360_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Équipe Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
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
        'Idempotency-Key': `e2e-ta-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ta-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    await manage(page, bearer, employeeId(ANA), employeeId(MIA));

    // Ana's shift starts 30 minutes from now at the site: the check-in window (2 h before) is open.
    const start = localFromNow(30);
    const end = localFromNow(270);
    const day = start.slice(0, 10);
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'ana',
          start,
          end,
          labels: { en: WORK_EN, fr: WORK_FR },
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
  });

  test('Mia records Ana’s check-in and Ana sees why, never who, in English', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, [MIA_EMAIL, PASSWORD], 'en', undefined);
    const card = page.getByTestId('home-team-attendance');
    await expect(card).toBeVisible();
    await expect(card).toContainText('Team attendance to record');
    await evidence(page, 'mvp060b-1-home-card-en', card);
    await primaryNav(page).getByRole('link', { name: 'Team attendance', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Team attendance');
    const row = page.getByTestId('team-attendance-action').filter({ hasText: ANA_FAMILY });
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('team-attendance-required')).toHaveText('Check-in needed');
    await expect(row.getByTestId('team-attendance-timezone')).toContainText(ZONE);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp060b-2-queue-check-in-en');

    await row.getByTestId('team-check-in').click();
    await record(
      page,
      'Record a check-in',
      'English',
      REASON_EN,
      'Confirm check-in',
      'mvp060b-3-check-in-confirm-en',
    );
    await expect(page.getByTestId('team-attendance-announcer')).toContainText(
      `Check-in recorded for Ana ${ANA_FAMILY}`,
    );
    await expect(row.getByTestId('team-attendance-required')).toHaveText('Check-out needed');
    await expect(row.getByTestId('team-attendance-checked-in')).toBeVisible();
    await expectAccessible(page);
    await clean(page);
    expect(await storage(page)).not.toContain(REASON_EN);

    const [ana, close] = await asAna(browser, 'en');
    await primaryNav(ana).getByRole('link', { name: 'My attendance', exact: true }).click();
    await expect(ana.getByRole('heading', { level: 1 })).toHaveText('My attendance');
    const mine = ana.getByTestId('my-attendance-record').filter({ hasText: WORK_EN });
    await expect(mine.getByTestId('my-attendance-status')).toHaveText('Open');
    const assisted = mine.getByTestId('my-attendance-check-in-assisted');
    await expect(assisted).toContainText('Recorded by a supervisor');
    await expect(assisted.getByTestId('my-attendance-check-in-assisted-reason')).toHaveText(
      REASON_EN,
    );
    await expect(mine).not.toContainText(MIA_FAMILY);
    await expect(mine).not.toContainText(MIA);
    await expectAccessible(ana);
    await noOverflow(ana);
    await evidence(ana, 'mvp060b-4-employee-history-en');
    await primaryNav(ana).getByRole('link', { name: 'My schedule', exact: true }).click();
    const shift = ana.getByTestId('my-shift').filter({ hasText: WORK_EN });
    await expect(shift.getByTestId('my-shift-check-in-assisted')).toContainText(
      'Recorded by a supervisor',
    );
    await expect(shift).not.toContainText(MIA_FAMILY);
    await expectAccessible(ana);
    await clean(ana);
    await close();
  });

  test('Mia enregistre le départ d’Ana et Ana voit le motif, jamais la responsable, en français', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, [MIA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Présence de l’équipe', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Présence de l’équipe');
    const row = page.getByTestId('team-attendance-action').filter({ hasText: ANA_FAMILY });
    await expect(row.getByTestId('team-attendance-required')).toHaveText('Départ à enregistrer');
    await expectAccessible(page);
    await noOverflow(page);
    await row
      .getByRole('button', { name: `Enregistrer le départ de Ana ${ANA_FAMILY}`, exact: false })
      .click();
    await expect(page.getByTestId('team-attendance-dialog-checked-in')).toContainText(
      'Arrivée pointée à',
    );
    await record(
      page,
      'Enregistrer un départ',
      'Français',
      REASON_FR,
      'Confirmer le départ',
      'mvp060b-5-check-out-confirm-fr',
    );
    await expect(page.getByTestId('team-attendance-announcer')).toContainText(
      `Départ enregistré pour Ana ${ANA_FAMILY}`,
    );
    await expect(row).toHaveCount(0);
    await expectAccessible(page);
    await clean(page);
    expect(await storage(page)).not.toContain(REASON_FR);

    const [ana, close] = await asAna(browser, 'fr');
    await primaryNav(ana).getByRole('link', { name: 'Ma présence', exact: true }).click();
    await expect(ana.getByRole('heading', { level: 1 })).toHaveText('Ma présence');
    const mine = ana.getByTestId('my-attendance-record').filter({ hasText: WORK_FR });
    await expect(mine.getByTestId('my-attendance-status')).toHaveText('Terminée');
    const out = mine.getByTestId('my-attendance-check-out-assisted');
    await expect(out).toContainText('Enregistré par un responsable');
    await expect(out.getByTestId('my-attendance-check-out-assisted-reason')).toHaveText(REASON_FR);
    await expect(out.getByTestId('my-attendance-check-out-assisted-reason')).toHaveAttribute(
      'lang',
      'fr',
    );
    await expect(mine.getByTestId('my-attendance-check-in-assisted-reason')).toHaveText(REASON_EN);
    await expect(mine).not.toContainText(MIA_FAMILY);
    await expectAccessible(ana);
    await noOverflow(ana);
    await evidence(ana, 'mvp060b-6-employee-history-fr');
    await clean(ana);
    await close();
  });
});

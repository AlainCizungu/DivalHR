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

// MVP-060A (Issue #113): online attendance with the real identity provider, database and server
// clock. Setup (API, the administrator's existing SSO session): two employees are imported at a
// new site and invited and linked; a schedule is published whose shifts start half an hour from
// now in the site's time zone, so the check-in window is open. Two journeys:
//  - English: Ana checks in from "My schedule" after a confirmation, sees the recorded time, checks
//    out there and finds the completed record in "My attendance";
//  - French: Cléo « Pointer l’arrivée » from « Mon horaire », then « Pointer le départ » from
//    « Ma présence », and sees « Terminée ».
// axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked. The windows,
// refusals and races are proven at API and database level, not here.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ZONE = 'Africa/Lubumbashi';
const ENTITY = `AE-${stamp}`;
const SITE = `AS-${stamp}`;
const DEPARTMENT = `AD-${stamp}`;
const ANA = `ATA-${stamp}-1`;
const CLEO = `ATC-${stamp}-2`;
const ANA_FAMILY = `Kalala${letters}`;
const CLEO_FAMILY = `Banza${letters}`;
const ANA_EMAIL = `e2e.pointage.ana.${lower}@example.test`;
const CLEO_EMAIL = `e2e.pointage.cleo.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Point!`;
const SITE_NAME = `Site Pointage ${stamp}`;
const WORK_EN = `Cashier ${letters}`;
const WORK_FR = `Caissière ${letters}`;

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
  expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${letters}|Caissi|Cashier`, 'iu'));
  expect(page.url()).not.toMatch(new RegExp(`${stamp}|${letters}`, 'iu'));
  expect(page.url()).not.toMatch(/[?&#](code|state|session_state)=/u);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-at-${key}-${stamp}-0001` },
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

/** Confirms the open attendance dialog and waits for it to close. */
async function confirm(page: Page, title: string, button: string) {
  const dialog = page.getByTestId('attendance-dialog');
  await expect(page.getByRole('dialog', { name: title })).toBeVisible();
  const action = dialog.getByRole('button', { name: button, exact: true });
  await expect(action).toBeFocused();
  await expectAccessible(page);
  await action.click();
  await expect(dialog).toBeHidden();
}

test.describe.serial('MVP-060A: check in and out of a scheduled shift', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(360_000);
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Pointage Entité ${stamp}`,
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
      `${ANA};Ana;${ANA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${CLEO};Cléo;${CLEO_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-at-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-at-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    // Shifts starting 30 minutes from now at the site: the check-in window (2 h before) is open.
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
        {
          clientRef: 'cleo',
          start,
          end,
          labels: { en: WORK_EN, fr: WORK_FR },
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
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await inviteAndLink(page, browser, bearer, CLEO_EMAIL, employeeId(CLEO), 'cleo');
    await context.close();
  });

  test('Ana checks in and out from My schedule and sees the record, in English', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, [ANA_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(page).getByRole('link', { name: 'My schedule', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My schedule');
    const shift = page.getByTestId('my-shift').filter({ hasText: WORK_EN });
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Not checked in');
    await expect(shift.getByTestId('check-out')).toHaveCount(0);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp060a-1-check-in-available-en', shift);

    await shift.getByRole('button', { name: `Check in for ${WORK_EN} on`, exact: false }).click();
    await evidence(page, 'mvp060a-2-check-in-confirm-en');
    await confirm(page, 'Check in', 'Confirm check-in');
    await expect(page.getByTestId('my-schedule-announcer')).toContainText(
      'Your check-in was recorded at',
    );
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Checked in');
    await expect(shift.getByTestId('my-shift-checked-in')).toContainText('Checked in at');
    await expect(shift.getByTestId('check-in')).toHaveCount(0);
    await expectAccessible(page);

    await shift.getByTestId('check-out').click();
    await confirm(page, 'Check out', 'Confirm check-out');
    await expect(page.getByTestId('my-schedule-announcer')).toContainText(
      'Your check-out was recorded at',
    );
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Checked in and out');
    await expect(shift.getByTestId('my-shift-checked-out')).toContainText('Checked out at');
    await clean(page);

    await primaryNav(page).getByRole('link', { name: 'My attendance', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My attendance');
    const record = page.getByTestId('my-attendance-record').filter({ hasText: WORK_EN });
    await expect(record).toHaveCount(1);
    await expect(record.getByTestId('my-attendance-status')).toHaveText('Completed');
    await expect(record.getByTestId('my-attendance-timezone')).toContainText(ZONE);
    await expect(record.getByTestId('check-out')).toHaveCount(0);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp060a-3-history-en');
    await clean(page);
  });

  test('Cléo pointe son arrivée depuis « Mon horaire » et son départ depuis « Ma présence »', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, [CLEO_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mon horaire');
    const shift = page.getByTestId('my-shift').filter({ hasText: WORK_FR });
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Arrivée non pointée');
    await shift
      .getByRole('button', { name: `Pointer l’arrivée pour ${WORK_FR} le`, exact: false })
      .click();
    await evidence(page, 'mvp060a-4-check-in-confirm-fr');
    await confirm(page, 'Pointer l’arrivée', 'Confirmer l’arrivée');
    await expect(page.getByTestId('my-schedule-announcer')).toContainText(
      'Votre arrivée a été enregistrée à',
    );
    await expect(shift.getByTestId('my-shift-attendance-status')).toHaveText('Arrivée pointée');
    await expect(shift.getByTestId('my-shift-checked-in')).toContainText('Arrivée pointée à');
    await expectAccessible(page);
    await noOverflow(page);
    await clean(page);

    await primaryNav(page).getByRole('link', { name: 'Ma présence', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Ma présence');
    const record = page.getByTestId('my-attendance-record').filter({ hasText: WORK_FR });
    await expect(record.getByTestId('my-attendance-status')).toHaveText('En cours');
    await expect(record.getByTestId('my-attendance-checked-out')).toHaveText('Départ non pointé');
    await expectAccessible(page);
    await evidence(page, 'mvp060a-5-history-open-fr');
    await record
      .getByRole('button', { name: `Pointer le départ pour ${WORK_FR} le`, exact: false })
      .click();
    await expect(page.getByTestId('attendance-dialog-checked-in')).toContainText(
      'Arrivée pointée à',
    );
    await confirm(page, 'Pointer le départ', 'Confirmer le départ');
    await expect(page.getByTestId('my-attendance-announcer')).toContainText(
      'Votre départ a été enregistré à',
    );
    await expect(record.getByTestId('my-attendance-status')).toHaveText('Terminée');
    await expect(record.getByTestId('check-out')).toHaveCount(0);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp060a-6-history-completed-fr');
    await clean(page);
  });
});

import { expect, test, type Browser, type Page } from '@playwright/test';
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

// MVP-050A (Issue #99). Setup (API, the administrator's existing SSO session): two employees are
// imported at a new site and get invited, linked employee access. In « Horaires » under Opérations
// the administrator builds a schedule in French: an assigned day shift and an uncovered overnight
// shift; a second overlapping shift for the same employee is shown as a conflict and blocks
// publication accessibly, then removed; the preview's totals are reviewed and publication is
// explicitly confirmed. The administrator sees the totals and filters the uncovered slots in French
// and English. The assigned employee sees only their own shift in French and English; the
// unrelated employee sees none and cannot reach the administrators' endpoints. axe, keyboard use,
// reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `HE-${stamp}`;
const SITE = `HS-${stamp}`;
const DEPARTMENT = `HD-${stamp}`;
const ANA_NUMBER = `HSA-${stamp}-1`;
const BEN_NUMBER = `HSB-${stamp}-2`;
const ANA_FAMILY = `Kabeya${letters}`;
const BEN_FAMILY = `Lukusa${letters}`;
const ANA_EMAIL = `e2e.horaire.ana.${lower}@example.test`;
const BEN_EMAIL = `e2e.horaire.ben.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Horaire!`;
const SITE_NAME = `Site horaires ${stamp}`;
const DAY_FR = 'Caissière principale';
const DAY_EN = 'Lead cashier';
const NIGHT_FR = 'Veille de nuit';
const NIGHT_EN = 'Night watch';

// The site runs on its own clock (R100-1): Goma's zone, one hour from the organization's Kinshasa.
const SITE_ZONE = 'Africa/Lubumbashi';

/** Today at the site, the date the server's "not in the past" rule uses. */
function today(): string {
  return sql(`SELECT (now() AT TIME ZONE '${SITE_ZONE}')::date::text`);
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
  expect(await storage(page)).not.toMatch(
    new RegExp(`${stamp}|${ANA_FAMILY}|${BEN_FAMILY}|Caissi|Veille|cashier|watch`, 'iu'),
  );
  expect(page.url()).not.toContain(stamp);
  expect(page.url()).not.toMatch(/Kabeya|Lukusa/iu);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ws-${key}-${stamp}-0001` },
    data,
  });
}

/** Invites, accepts, sets the password and links the access to the employee record. */
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
  expect(
    (
      await post(page, bearer, `/employees/${employee}/access-link`, `link-${key}`, {
        membershipId: ((await lookup.json()) as { membershipId: string }).membershipId,
      })
    ).status(),
  ).toBe(201);
}

/** Fills one slot of the form. */
async function fillSlot(
  page: Page,
  index: number,
  slot: { date: string; start: string; end: string; en: string; fr: string; employee?: string },
) {
  const card = page.getByTestId('slot').nth(index);
  await card.getByLabel('Date', { exact: true }).fill(slot.date);
  await card.getByLabel('Début', { exact: true }).fill(slot.start);
  await card.getByLabel('Fin', { exact: true }).fill(slot.end);
  await card.getByLabel('Travail, en anglais', { exact: true }).fill(slot.en);
  await card.getByLabel('Travail, en français', { exact: true }).fill(slot.fr);
  if (slot.employee) {
    const picker = card.getByTestId('slot-employee');
    await picker.getByLabel('Rechercher par matricule ou par nom').fill(slot.employee);
    await picker.getByRole('button', { name: 'Rechercher', exact: true }).click();
    await picker.getByRole('button', { name: new RegExp(`\\(${slot.employee}\\)$`, 'u') }).click();
    await expect(picker.getByText(/^Sélection :/u)).toContainText(slot.employee);
  }
}

test.describe.serial('MVP-050A: site work schedules', () => {
  test('setup: a new site, two placed employees with linked access', async ({ page, browser }) => {
    test.setTimeout(300_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Horaires Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: SITE_NAME,
      timezone: SITE_ZONE,
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId: ((await site.json()) as { id: string }).id,
          code: DEPARTMENT,
          name: `Caisses ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const hired = plusDays(today(), -10);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${ANA_NUMBER};Ana;${ANA_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${BEN_NUMBER};Ben;${BEN_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-ws-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ws-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA_NUMBER), 'ana');
    await inviteAndLink(page, browser, bearer, BEN_EMAIL, employeeId(BEN_NUMBER), 'ben');
  });

  test('the administrator publishes a mixed schedule in French; a conflict blocks it', async ({
    page,
  }) => {
    test.setTimeout(240_000);
    const day = plusDays(today(), 3);
    await signIn(page, USERS.adminA, 'fr');
    await primaryNav(page).getByRole('link', { name: 'Horaires', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Horaires' })).toBeVisible();
    await page.getByLabel('Entité juridique', { exact: true }).selectOption({
      label: `Horaires Entité ${stamp} (${ENTITY})`,
    });
    await page
      .getByLabel('Site', { exact: true })
      .selectOption({ label: `${SITE_NAME} (${SITE})` });
    await page.getByLabel('Première date', { exact: true }).fill(day);
    await page.getByLabel('Dernière date', { exact: true }).fill(plusDays(day, 1));
    await fillSlot(page, 0, {
      date: day,
      start: '08:00',
      end: '16:00',
      en: DAY_EN,
      fr: DAY_FR,
      employee: ANA_NUMBER,
    });
    // Keyboard: duplicate the first slot, then make the copy an uncovered overnight shift.
    await page.getByRole('button', { name: 'Dupliquer le créneau 1' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('slot')).toHaveCount(2);
    await expect(page.getByTestId('slot').nth(1).getByLabel('Date', { exact: true })).toBeFocused();
    // A second overlapping shift for Ana (the copy, 08:00 to 16:00).
    await page.getByRole('button', { name: 'Ajouter un créneau' }).click();
    await fillSlot(page, 2, {
      date: day,
      start: '22:00',
      end: '06:00',
      en: NIGHT_EN,
      fr: NIGHT_FR,
    });
    await expect(page.getByTestId('slot').nth(2).getByTestId('slot-overnight')).toHaveText(
      'Se termine le lendemain.',
    );
    await expectAccessible(page);
    await noOverflow(page);
    await page.getByRole('button', { name: 'Afficher l’aperçu de l’horaire' }).click();
    const review = page.getByTestId('schedule-review');
    await expect(review.getByTestId('review-blocked')).toBeVisible();
    await expect(
      review.getByTestId('review-slot').filter({ hasText: 'dans cet horaire' }),
    ).toHaveCount(1);
    await expect(review.getByRole('button', { name: 'Confirmer et publier' })).toBeDisabled();
    await expectAccessible(page);

    // Back to editing: remove the conflicting copy and preview again.
    await page.getByRole('button', { name: 'Revenir à la saisie' }).click();
    await page.getByRole('button', { name: 'Retirer le créneau 2' }).click();
    await expect(page.getByTestId('slot')).toHaveCount(2);
    await page.getByRole('button', { name: 'Afficher l’aperçu de l’horaire' }).click();
    await expect(review.getByTestId('review-totals')).toHaveText(
      '2 créneaux : 1 couverts, 1 non couverts',
    );
    await expect(review.getByTestId('review-warning')).toContainText('non couvert');
    await expect(review.getByTestId('review-slot').nth(0)).toContainText(ANA_FAMILY);
    await expect(review.getByTestId('review-slot').nth(1)).toContainText('le lendemain');
    await expect(review.getByTestId('review-timezone')).toHaveText(
      `Heures locales du site, fuseau horaire ${SITE_ZONE}.`,
    );
    await expectAccessible(page);
    await noOverflow(page);
    await review.getByRole('button', { name: 'Confirmer et publier' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Horaire publié');
    const detail = page.getByTestId('schedule-detail');
    await expect(detail.getByTestId('detail-slot')).toHaveCount(2);
    await expect(detail.getByTestId('detail-totals')).toHaveText(
      '2 créneaux : 1 couverts, 1 non couverts',
    );
    await detail.getByLabel('Créneaux non couverts seulement').check();
    await expect(detail.getByTestId('detail-slot')).toHaveCount(1);
    await expect(detail.getByTestId('detail-slot')).toContainText(NIGHT_FR);
    await expectAccessible(page);
    await clean(page);

    // In English: the list's totals and the uncovered filter.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Schedules' })).toBeVisible();
    const row = page.getByTestId('schedule-row').filter({ hasText: SITE_NAME });
    await expect(row.getByTestId('schedule-uncovered')).toHaveText('1 uncovered');
    await row.getByRole('button', { name: /^View the schedule of/u }).click();
    await expect(detail.getByTestId('detail-totals')).toHaveText(
      '2 slots: 1 assigned, 1 uncovered',
    );
    await detail.getByLabel('Uncovered slots only').check();
    await expect(detail.getByTestId('detail-slot')).toHaveCount(1);
    await expect(detail.getByTestId('detail-slot')).toContainText(NIGHT_EN);
    await expectAccessible(page);
    await clean(page);
  });

  test('the assigned employee sees only their shift; the other sees none and no admin data', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, [ANA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Mon horaire' })).toBeVisible();
    const shifts = page.getByTestId('my-shift');
    await expect(shifts).toHaveCount(1);
    await expect(shifts.getByTestId('my-shift-label')).toHaveText(DAY_FR);
    await expect(shifts.getByTestId('my-shift-site')).toContainText(SITE_NAME);
    await expect(shifts.getByTestId('my-shift-timezone')).toHaveText(
      `Heure locale du site (${SITE_ZONE})`,
    );
    await expect(shifts.getByTestId('my-shift-time')).toContainText('8:00');
    await expect(page.getByRole('main')).not.toContainText(NIGHT_FR);
    await expect(page.getByRole('main')).not.toContainText(BEN_FAMILY);
    await expectAccessible(page);
    await noOverflow(page);
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'My schedule' })).toBeVisible();
    await expect(shifts.getByTestId('my-shift-label')).toHaveText(DAY_EN);
    await expectAccessible(page);
    await clean(page);

    const context = await browser.newContext({ locale: 'fr-FR' });
    const other = await context.newPage();
    const ben = await signIn(other, [BEN_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(other).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    await expect(other.getByTestId('my-schedule-empty')).toBeVisible();
    await expect(
      primaryNav(other).getByRole('link', { name: 'Horaires', exact: true }),
    ).toHaveCount(0);
    for (const path of ['/work-schedules', '/work-schedules/preview']) {
      const response =
        path === '/work-schedules'
          ? await other.request.get(`${CORE_API}${path}`, { headers: { Authorization: ben() } })
          : await other.request.post(`${CORE_API}${path}`, {
              headers: { Authorization: ben() },
              data: {},
            });
      expect(response.status(), path).toBe(403);
    }
    const own = await (
      await other.request.get(`${CORE_API}/me/work-schedule`, { headers: { Authorization: ben() } })
    ).text();
    expect(own).not.toContain(ANA_FAMILY);
    expect(own).not.toContain(DAY_FR);
    await clean(other);
    await context.close();
  });
});

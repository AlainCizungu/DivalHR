import { expect, test, type Page } from '@playwright/test';
import {
  CORE_API,
  TENANT_A,
  acceptInvitation,
  USERS,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  sql,
  typeSecret,
} from './support';

// MVP-093A (Issue #175): exactly two journeys, with synthetic data only.
// (1) English: the administrator establishes a USD monthly compensation for a synthetic employee
//     (preview, then an explicit confirmation); the linked employee reads only their own value and
//     history at /me/compensation; a different synthetic employee sees nothing of it.
// (2) French: the administrator establishes a CDF compensation, schedules a future change and
//     cancels it (preview and confirmation each), then commits a bounded retroactive correction
//     (reason code, explanation, acknowledgement); the employee sees the corrected history in
//     French, never the administrator's explanation.
// Setup (API, the administrator's existing SSO session, one interactive sign-in per employee): two
// invited and linked employees. Authorization, tenant isolation, limits, idempotency, races and
// rollbacks are covered by the Core tests, not here. axe, a keyboard-only step, reflow at 320 px,
// 390 px and 640 px (200 % zoom of a 1280 px window), browser storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const ENTITY = `XR-${stamp}`;
const SITE = `XT-${stamp}`;
const DEPARTMENT = `XP-${stamp}`;

interface Person {
  number: string;
  given: string;
  family: string;
  email: string;
  password: string;
  locale: 'en' | 'fr';
  employee: string;
}
const person = (suffix: string, given: string, locale: 'en' | 'fr'): Person => ({
  number: `CMP-${stamp}-${suffix}`,
  given,
  family: `Ilunga${letters}${suffix.toLowerCase()}`,
  email: `e2e.remu.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Remu${suffix}!`,
  locale,
  employee: '',
});
const ANA = person('A', 'Anna', 'en');
const BEN = person('B', 'Bénédicte', 'fr');

const EXPLANATION = `Montant saisi avec une erreur de frappe (${stamp})`;

function utcDate(days: number): string {
  const value = new Date();
  value.setUTCHours(0, 0, 0, 0);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/** Today in the organization's business time zone, as the Core computes it. */
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

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-cp-${key}-${stamp}-0001` },
    data,
  });
}

async function keyboardTabReaches(page: Page, testId: string) {
  // Keyboard-only: tab forward until the control has focus (bounded).
  for (let step = 0; step < 60; step++) {
    if (await page.getByTestId(testId).evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await page.getByTestId(testId).evaluate((el) => el === document.activeElement)).toBe(true);
}

async function openProfile(page: Page, p: Person, label: string) {
  await primaryNav(page).getByRole('link', { name: label, exact: true }).click();
  await page
    .getByLabel(label === 'Employés' ? 'Matricule ou nom' : 'Employee number or name')
    .fill(p.number);
  await page.keyboard.press('Enter');
  const name = `${p.given} ${p.family}`;
  await page.getByTestId('directory-table').getByRole('link', { name }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);
}

test.describe.serial('MVP-093A: monthly compensation, administrator and employee', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(420_000);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Rémunération Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Rémunération ${stamp}`,
      timezone: 'Africa/Kinshasa',
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId: ((await site.json()) as { id: string }).id,
          code: DEPARTMENT,
          name: `Opérations ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const people = [ANA, BEN];
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      ...people.map(
        (p) => `${p.number};${p.given};${p.family};${utcDate(-30)};${ENTITY};${SITE};${DEPARTMENT}`,
      ),
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-cp-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-cp-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    for (const p of people) {
      const found = await page.request.post(`${CORE_API}/employees/search`, {
        headers: { Authorization: bearer() },
        data: { query: p.number, limit: 5 },
      });
      expect(found.status()).toBe(200);
      p.employee =
        ((await found.json()) as { items: { id: string; employeeNumber: string }[] }).items.find(
          (item) => item.employeeNumber === p.number,
        )?.id ?? '';
      expect(p.employee).not.toBe('');
      expect(
        (
          await post(page, bearer, '/invitations', `inv-${p.number}`, {
            email: p.email,
            role: 'employee',
            locale: p.locale,
          })
        ).status(),
      ).toBe(201);
      const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
        await mailTo(p.email, /Invitation (à rejoindre|to join)/u),
      );
      expect(link, 'invitation link').not.toBeNull();
      expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
      const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
        await mailTo(p.email, /mot de passe|password|actions|compte|account/iu),
      );
      expect(setup, 'password setup link').not.toBeNull();
      const context = await browser.newContext({ locale: p.locale === 'fr' ? 'fr-FR' : 'en-US' });
      const setupPage = await context.newPage();
      await openSecretLink(setupPage, setup?.[0] ?? '');
      const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
      if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
        await proceed.click();
      }
      await typeSecret(setupPage, '#password-new', p.password);
      await typeSecret(setupPage, '#password-confirm', p.password);
      await setupPage.locator('[type="submit"]').first().click();
      await context.close();
      const lookup = await page.request.post(
        `${CORE_API}/employees/${p.employee}/access-link/lookup`,
        { headers: { Authorization: bearer() }, data: { email: p.email } },
      );
      expect(lookup.status()).toBe(200);
      expect(
        (
          await post(page, bearer, `/employees/${p.employee}/access-link`, `link-${p.number}`, {
            membershipId: ((await lookup.json()) as { membershipId: string }).membershipId,
          })
        ).status(),
      ).toBe(201);
    }
    await page.close();
  });

  test('English administrator establishes a USD compensation; the employee reads only their own', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    await signIn(admin, USERS.adminA, 'en');
    await openProfile(admin, ANA, 'Employees');
    const section = admin.getByTestId('compensation-section');
    await expect(section.getByRole('heading', { level: 2, name: 'Compensation' })).toBeVisible();
    await expect(section.getByTestId('compensation-notice')).toHaveText(
      'Recording compensation does not calculate payroll or initiate payment.',
    );
    await expect(section.getByTestId('compensation-privacy')).toContainText('restricted');
    await expect(section.getByTestId('compensation-none')).toBeVisible();
    await expectAccessible(admin);

    // First compensation: an invalid amount stops locally; a valid one is previewed first.
    await section.getByRole('button', { name: 'Record the first compensation' }).click();
    const form = section.getByTestId('compensation-form');
    await expect(
      form.getByRole('heading', { name: 'Record the first compensation' }),
    ).toBeFocused();
    await form.getByLabel('Currency', { exact: true }).selectOption('USD');
    await form.getByLabel('Monthly base amount', { exact: true }).fill('2500.505');
    await form.getByRole('button', { name: 'Preview', exact: true }).click();
    await expect(admin.getByTestId('compensation-problems')).toContainText(
      'Enter a valid amount for this currency.',
    );
    await expectAccessible(admin);
    await form.getByLabel('Monthly base amount', { exact: true }).fill('2500.50');
    await noOverflow(admin);
    await form.getByRole('button', { name: 'Preview', exact: true }).click();
    const preview = section.getByTestId('compensation-preview');
    await expect(preview.getByRole('heading', { level: 4, name: 'Preview' })).toBeFocused();
    await expect(preview.getByTestId('preview-summary')).toContainText(/USD\s2,500\.50/u);
    await expect(preview.getByTestId('preview-after')).toContainText('USD');
    await expect(section.getByTestId('current-interval')).toHaveCount(0);
    await expectAccessible(admin);
    await noOverflow(admin);

    // Keyboard only: reach the explicit confirmation and press Enter.
    await keyboardTabReaches(admin, 'compensation-confirm');
    await admin.keyboard.press('Enter');
    await expect(admin.getByTestId('announcer')).toContainText('First compensation recorded.');
    const current = section.getByTestId('current-interval');
    await expect(current).toContainText(/USD\s2,500\.50/u);
    await expect(section.getByTestId('compensation-change')).toHaveCount(1);
    await expect(section.getByRole('heading', { level: 2, name: 'Compensation' })).toBeFocused();
    await expectAccessible(admin);
    await noOverflow(admin);
    expect(await storage(admin)).not.toMatch(new RegExp(`${stamp}|${ANA.family}|2500`, 'iu'));
    expect(admin.url()).not.toMatch(/2500|compensation/iu);
    await adminContext.close();

    // The linked employee reads only their own value and history.
    const employeeContext = await browser.newContext();
    const employee = await employeeContext.newPage();
    const bearer = await signIn(employee, [ANA.email, ANA.password], 'en', undefined);
    await primaryNav(employee).getByRole('link', { name: 'My compensation', exact: true }).click();
    await expect(employee.getByRole('heading', { level: 1 })).toHaveText('My compensation');
    await expect(employee.getByTestId('my-compensation-notice')).toHaveText(
      'Worked minutes do not automatically change a monthly salary, and no payment is initiated.',
    );
    await expect(employee.getByTestId('current-interval')).toContainText(/USD\s2,500\.50/u);
    await expect(employee.getByTestId('compensation-change')).toHaveCount(1);
    await expect(employee.getByTestId('compensation-change')).toContainText('First compensation');
    // Read-only: no control, no administrator explanation.
    await expect(employee.getByRole('main').getByRole('button')).toHaveCount(0);
    await expect(employee.getByRole('main').locator('input, select, textarea')).toHaveCount(0);
    await expect(employee.getByTestId('change-explanation')).toHaveCount(0);
    await expectAccessible(employee);
    await noOverflow(employee);
    expect(await storage(employee)).not.toMatch(new RegExp(`${stamp}|${ANA.family}|2500`, 'iu'));
    expect(employee.url()).not.toMatch(/2500/u);
    // The employee has no operation on another employee's record.
    const foreign = await employee.request.get(
      `${CORE_API}/employees/${BEN.employee}/compensation`,
      {
        headers: { Authorization: bearer() },
      },
    );
    expect([403, 404]).toContain(foreign.status());
    await employeeContext.close();

    // A different synthetic employee sees nothing of it.
    const otherContext = await browser.newContext();
    const other = await otherContext.newPage();
    await signIn(other, [BEN.email, BEN.password], 'en', undefined);
    await primaryNav(other).getByRole('link', { name: 'My compensation', exact: true }).click();
    await expect(other.getByRole('heading', { level: 1 })).toHaveText('My compensation');
    await expect(other.getByTestId('compensation-empty')).toHaveText(
      'No compensation has been recorded for you yet.',
    );
    await expect(other.getByTestId('compensation-change')).toHaveCount(0);
    await expect(other.getByRole('main')).not.toContainText(/2,500/u);
    await expectAccessible(other);
    await noOverflow(other);
    await otherContext.close();
  });

  test('French administrator: establish CDF, schedule then cancel a change, correct; the employee reads it', async ({
    browser,
  }) => {
    test.setTimeout(420_000);
    const base = today();
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    await signIn(admin, USERS.adminA, 'fr');
    await openProfile(admin, BEN, 'Employés');
    const section = admin.getByTestId('compensation-section');
    await expect(section.getByRole('heading', { level: 2, name: 'Rémunération' })).toBeVisible();
    await expect(section.getByTestId('compensation-notice')).toHaveText(
      'L’enregistrement de la rémunération ne calcule pas la paie et n’initie aucun paiement.',
    );
    await expectAccessible(admin);
    await noOverflow(admin);

    // 1. Establish a CDF compensation (preview, then confirm).
    await section.getByRole('button', { name: 'Enregistrer la première rémunération' }).click();
    const form = section.getByTestId('compensation-form');
    await form.getByLabel('Devise', { exact: true }).selectOption('CDF');
    await form.getByLabel('Montant mensuel de base', { exact: true }).fill('135000');
    await form.getByRole('button', { name: 'Aperçu', exact: true }).click();
    const preview = section.getByTestId('compensation-preview');
    await expect(preview.getByTestId('preview-summary')).toContainText(/135\s000\sCDF/u);
    await expectAccessible(admin);
    await noOverflow(admin);
    await preview.getByRole('button', { name: 'Confirmer et enregistrer' }).click();
    await expect(admin.getByTestId('announcer')).toContainText(
      'Première rémunération enregistrée.',
    );
    await expect(section.getByTestId('current-interval')).toContainText(/135\s000\sCDF/u);

    // 2. Schedule a future change (CDF), previewed then confirmed.
    await section.getByRole('button', { name: 'Programmer ou enregistrer un changement' }).click();
    const change = section.getByTestId('compensation-form');
    await change.getByLabel('Date d’effet', { exact: true }).fill(plusDays(base, 30));
    await change.getByLabel('Devise', { exact: true }).selectOption('CDF');
    await change.getByLabel('Montant mensuel de base', { exact: true }).fill('150000');
    await change.getByRole('button', { name: 'Aperçu', exact: true }).click();
    await expect(section.getByTestId('compensation-preview')).toBeVisible();
    await expectAccessible(admin);
    await section.getByRole('button', { name: 'Confirmer et enregistrer' }).click();
    await expect(admin.getByTestId('announcer')).toContainText(
      'Changement de rémunération enregistré.',
    );
    const scheduled = section.getByTestId('scheduled-interval');
    await expect(scheduled).toHaveCount(1);
    await expect(scheduled).toContainText(/150\s000\sCDF/u);
    await noOverflow(admin);

    // 3. Cancel it (preview, then confirm); only the scheduled change offers the cancellation.
    await expect(
      section.getByTestId('current-interval').getByTestId('compensation-cancel'),
    ).toHaveCount(0);
    await scheduled.getByRole('button', { name: 'Annuler ce changement programmé' }).click();
    const cancel = section.getByTestId('compensation-cancel-panel');
    await expect(cancel.getByTestId('preview-before')).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);
    await cancel.getByRole('button', { name: 'Confirmer l’annulation' }).click();
    await expect(admin.getByTestId('announcer')).toContainText('Changement programmé annulé.');
    await expect(section.getByTestId('scheduled-interval')).toHaveCount(0);
    await expect(section.getByTestId('current-interval')).toContainText(/135\s000\sCDF/u);

    // 4. A bounded retroactive correction: reason, explanation and acknowledgement.
    await section.getByTestId('current-interval').getByRole('button', { name: 'Corriger' }).click();
    const correction = section.getByTestId('compensation-form');
    await correction.getByRole('button', { name: 'Aperçu', exact: true }).click();
    await expect(admin.getByTestId('compensation-problems')).toContainText(
      'Choisissez un motif de correction.',
    );
    await expectAccessible(admin);
    await correction.getByLabel('Date d’effet', { exact: true }).fill(plusDays(base, -5));
    await correction.getByLabel('Montant mensuel de base', { exact: true }).fill('140000');
    await correction
      .getByLabel('Motif de la correction', { exact: true })
      .selectOption('DATA_ENTRY_ERROR');
    await correction.getByLabel('Explication pour les administrateurs').fill(EXPLANATION);
    await noOverflow(admin);
    await correction.getByRole('button', { name: 'Aperçu', exact: true }).click();
    const corrected = section.getByTestId('compensation-preview');
    await expect(corrected.getByTestId('preview-warnings')).toBeVisible();
    // No commit without the explicit acknowledgement.
    await corrected.getByRole('button', { name: 'Confirmer et enregistrer' }).click();
    await expect(admin.getByTestId('compensation-problems')).toContainText(
      'Confirmez que vous avez pris connaissance de ce changement rétroactif.',
    );
    await corrected
      .getByRole('checkbox', {
        name: 'Je reconnais que ce changement s’applique à une date passée.',
      })
      .check();
    await expectAccessible(admin);
    await noOverflow(admin);
    await corrected.getByRole('button', { name: 'Confirmer et enregistrer' }).click();
    await expect(admin.getByTestId('announcer')).toContainText(
      'Correction de rémunération enregistrée.',
    );
    await expect(section.getByTestId('current-interval')).toContainText(/140\s000\sCDF/u);
    // The immutable history keeps all four recorded changes, with the explanation for the administrator.
    await expect(section.getByTestId('compensation-change')).toHaveCount(4);
    await expect(section.getByTestId('change-explanation')).toHaveText(EXPLANATION);
    await expectAccessible(admin);
    await noOverflow(admin);
    expect(await storage(admin)).not.toMatch(new RegExp(`${stamp}|${BEN.family}|140000`, 'iu'));
    expect(admin.url()).not.toMatch(/140000|compensation/iu);
    await adminContext.close();

    // 5. The employee sees the corrected history in French, never the explanation.
    const employeeContext = await browser.newContext();
    const employee = await employeeContext.newPage();
    await signIn(employee, [BEN.email, BEN.password], 'fr', undefined);
    await primaryNav(employee).getByRole('link', { name: 'Ma rémunération', exact: true }).click();
    await expect(employee.getByRole('heading', { level: 1 })).toHaveText('Ma rémunération');
    await expect(employee.getByTestId('my-compensation-notice')).toHaveText(
      'Les minutes travaillées ne modifient pas automatiquement un salaire mensuel, et aucun paiement n’est initié.',
    );
    await expect(employee.getByTestId('current-interval')).toContainText(/140\s000\sCDF/u);
    const rows = employee.getByTestId('compensation-change');
    await expect(rows).toHaveCount(4);
    await expect(rows.first()).toContainText('Correction');
    await expect(rows.first()).toContainText('Erreur de saisie');
    await expect(rows.first()).toContainText('Rétroactif');
    await expect(employee.getByTestId('scheduled-interval')).toHaveCount(0);
    await expect(employee.getByRole('main').getByRole('button')).toHaveCount(0);
    await expect(employee.getByTestId('change-explanation')).toHaveCount(0);
    await expect(employee.getByRole('main')).not.toContainText(EXPLANATION);
    await expect(employee.getByRole('main')).not.toContainText('Explication de l’administrateur');
    await expectAccessible(employee);
    await noOverflow(employee);
    expect(await storage(employee)).not.toMatch(new RegExp(`${stamp}|${BEN.family}|140000`, 'iu'));
    expect(employee.url()).not.toContain(stamp);
    await employeeContext.close();
  });
});

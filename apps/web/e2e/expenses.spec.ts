import { expect, test, type Page } from '@playwright/test';
import {
  CORE_API,
  acceptInvitation,
  USERS,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  typeSecret,
} from './support';

// MVP-081 (Issue #169): exactly two journeys, with synthetic data only.
// (1) An English employee creates a USD expense, attaches a synthetic receipt, submits it,
//     withdraws it after an explicit confirmation, corrects it through a NEW expense and verifies
//     the status and history of both.
// (2) A French employee records a CDF expense and submits it; the French manager returns it with
//     a reason; the employee corrects and resubmits it; the manager approves it; the French
//     administrator then observes it read-only.
// Setup (API, the administrator's existing SSO session, one interactive sign-in per employee):
// three invited and linked employees, the employee A -> manager M and B -> M reporting lines. Authorization,
// tenant isolation, races, receipt hardening and rollbacks are covered by the Core tests, not
// here. axe, reflow at 320 px, 390 px and 640 px, browser storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const ENTITY = `XE-${stamp}`;
const SITE = `XS-${stamp}`;
const DEPARTMENT = `XD-${stamp}`;

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
  number: `EXP-${stamp}-${suffix}`,
  given,
  family: `Kabasele${letters}${suffix.toLowerCase()}`,
  email: `e2e.frais.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Frais${suffix}!`,
  locale,
  employee: '',
});
const ANA = person('A', 'Anna', 'en');
const BEN = person('B', 'Bénédicte', 'fr');
const MAN = person('M', 'Mireille', 'fr');

const MERCHANT_1 = `Taxi Gombe ${stamp}`;
const MERCHANT_2 = `Taxi corrigé ${stamp}`;
const MERCHANT_3 = `Papeterie Centrale ${stamp}`;
const DESCRIPTION = `Course aller-retour vers le client (${stamp})`;
const REASON = `Merci de préciser le motif du déplacement (${stamp})`;
const RECEIPT_NAME = `recu-synthetique-${lower}.png`;

function utcDate(days: number): string {
  const value = new Date();
  value.setUTCHours(0, 0, 0, 0);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/** A synthetic 1x1 PNG receipt, built here with correct chunk checksums. */
function syntheticPng(): Buffer {
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = (bytes: Buffer) => {
    let c = 0xffffffff;
    for (const byte of bytes) c = (crcTable[(c ^ byte) & 0xff] ?? 0) ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, data: Buffer) => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const sum = Buffer.alloc(4);
    sum.writeUInt32BE(crc(body));
    return Buffer.concat([length, body, sum]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(1, 0);
  ihdr.writeUInt32BE(1, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  // One filter byte and one RGB pixel, deflated (stored block; zlib header and adler32).
  const raw = Buffer.from([0, 255, 255, 255]);
  let low = 1;
  let high = 0;
  for (const byte of raw) {
    low = (low + byte) % 65521;
    high = (high + low) % 65521;
  }
  const adler = Buffer.alloc(4);
  adler.writeUInt32BE(((high << 16) | low) >>> 0);
  const stored = Buffer.concat([
    Buffer.from([0x78, 0x01, 0x01, raw.length, 0, ~raw.length & 0xff, 0xff]),
    raw,
    adler,
  ]);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', stored),
    chunk('IEND', Buffer.alloc(0)),
  ]);
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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ex-${key}-${stamp}-0001` },
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

test.describe.serial('MVP-081: employee expenses, approval and read-only observation', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(420_000);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Frais Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Frais ${stamp}`,
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
    const people = [ANA, BEN, MAN];
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
        'Idempotency-Key': `e2e-ex-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ex-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
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

    // Reporting lines: Anna and Ben both report to Mireille from ten days ago (a late
    // notification), so each has an effective manager at submission.
    for (const [index, employee] of [ANA, BEN].entries()) {
      const change = {
        type: 'CHANGE',
        effectiveFrom: utcDate(-10),
        manager: { employeeId: MAN.employee },
        reasonCode: 'LATE_NOTIFICATION',
      };
      const preview = await page.request.post(
        `${CORE_API}/employees/${employee.employee}/employment-changes/preview`,
        { headers: { Authorization: bearer() }, data: change },
      );
      expect(preview.status()).toBe(200);
      const previewed = (await preview.json()) as {
        expectedVersion: number;
        previewDigest: string;
        requiresAcknowledgement: boolean;
      };
      const applied = await page.request.post(
        `${CORE_API}/employees/${employee.employee}/employment-changes`,
        {
          headers: {
            Authorization: bearer(),
            'Idempotency-Key': `e2e-ex-manager-${stamp}-000${String(index + 1)}`,
          },
          data: {
            ...change,
            expectedVersion: previewed.expectedVersion,
            previewDigest: previewed.previewDigest,
            acknowledgeRetroactive: previewed.requiresAcknowledgement,
          },
        },
      );
      expect(applied.status()).toBe(201);
    }
    await page.close();
  });

  test('English employee: USD expense with receipt, submit, withdraw, correct in a new expense', async ({
    page,
  }) => {
    test.setTimeout(240_000);
    await signIn(page, [ANA.email, ANA.password], 'en', undefined);
    await primaryNav(page).getByRole('link', { name: 'My expenses', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My expenses');
    await expect(page.getByTestId('approval-notice')).toContainText('not reimbursing or paying');
    await expect(page.getByTestId('expenses-empty')).toBeVisible();
    await expectAccessible(page);

    // Create a USD draft (the amount grammar is checked locally first).
    await page.getByTestId('new-expense').click();
    const form = page.getByTestId('expense-form');
    await form.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByTestId('form-problems')).toBeVisible();
    await expectAccessible(page);
    await form.getByLabel('Currency', { exact: true }).selectOption('USD');
    await form.getByLabel(/^Amount/u).fill('12.505');
    await form.getByLabel('Expense date').fill(utcDate(-1));
    await form.getByLabel('Category').selectOption('TRANSPORT');
    await form.getByLabel('Merchant or payee').fill(MERCHANT_1);
    await form.getByLabel('Description').fill(DESCRIPTION);
    await form.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(form.getByText('Enter a valid amount for this currency.')).toBeVisible();
    await form.getByLabel(/^Amount/u).fill('12.50');
    await noOverflow(page);
    await form.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByTestId('announcer')).toContainText('Expense draft created.');
    const detail = page.getByTestId('expense-detail');
    await expect(detail.getByTestId('expense-state').first()).toHaveText('Draft');
    await expect(detail.getByTestId('expense-amount')).toHaveText('12.50 USD');

    // Attach a synthetic receipt, then submit.
    await page.getByTestId('receipt-input').setInputFiles({
      name: RECEIPT_NAME,
      mimeType: 'image/png',
      buffer: syntheticPng(),
    });
    await expect(page.getByTestId('announcer')).toContainText('Receipt attached.');
    await expect(detail.getByTestId('expense-facts')).toContainText(RECEIPT_NAME);
    await expectAccessible(page);
    await keyboardTabReaches(page, 'submit-expense');
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('announcer')).toContainText('Expense submitted for approval.');
    await expect(detail.getByTestId('expense-state').first()).toHaveText('Waiting for approval');

    // Withdraw only after an explicit confirmation.
    await page.getByTestId('withdraw-expense').click();
    await expectAccessible(page);
    await page.getByTestId('confirm-withdraw').click();
    await expect(page.getByTestId('announcer')).toContainText('Expense withdrawn.');
    await expect(detail.getByTestId('expense-state').first()).toHaveText('Withdrawn');
    await expect(detail.getByTestId('expense-history').getByRole('listitem')).toHaveCount(4);
    await expect(detail.getByTestId('expense-revision')).toHaveCount(1);
    await noOverflow(page);

    // Correct through a NEW expense (the withdrawn one stays as it is).
    await page.getByTestId('correct-expense').click();
    const second = page.getByTestId('expense-form');
    await expect(second.getByLabel('Merchant or payee')).toHaveValue(MERCHANT_1);
    await second.getByLabel('Merchant or payee').fill(MERCHANT_2);
    await second.getByLabel(/^Amount/u).fill('14.00');
    await second.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(page.getByTestId('announcer')).toContainText('Expense draft created.');
    await page.getByTestId('receipt-input').setInputFiles({
      name: RECEIPT_NAME,
      mimeType: 'image/png',
      buffer: syntheticPng(),
    });
    await expect(page.getByTestId('announcer')).toContainText('Receipt attached.');
    await page.getByTestId('submit-expense').click();
    await expect(page.getByTestId('announcer')).toContainText('Expense submitted for approval.');

    // Status and history of both, from the list.
    await page.getByRole('button', { name: 'Back to the list' }).click();
    const rows = page.getByTestId('my-expense');
    await expect(rows).toHaveCount(2);
    await expect(rows.filter({ hasText: MERCHANT_1 }).getByTestId('expense-state')).toHaveText(
      'Withdrawn',
    );
    await expect(rows.filter({ hasText: MERCHANT_2 }).getByTestId('expense-state')).toHaveText(
      'Waiting for approval',
    );
    await expect(rows.filter({ hasText: MERCHANT_2 }).getByTestId('expense-amount')).toHaveText(
      '14.00 USD',
    );
    await rows.filter({ hasText: MERCHANT_2 }).getByRole('button').click();
    await expect(
      page.getByTestId('expense-detail').getByTestId('expense-history').getByRole('listitem'),
    ).toHaveCount(3);
    await expect(page.getByTestId('receipt-download')).toBeVisible();
    await expectAccessible(page);
    await noOverflow(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${ANA.family}`, 'iu'));
    expect(page.url()).not.toContain(stamp);
  });

  test('French employee, manager and administrator: return, correction, approval, observation', async ({
    browser,
  }) => {
    test.setTimeout(420_000);
    // 1. Bénédicte records and submits a CDF expense.
    const employeeContext = await browser.newContext();
    const employee = await employeeContext.newPage();
    await signIn(employee, [BEN.email, BEN.password], 'fr', undefined);
    await primaryNav(employee)
      .getByRole('link', { name: 'Mes notes de frais', exact: true })
      .click();
    await expect(employee.getByRole('heading', { level: 1 })).toHaveText('Mes notes de frais');
    await employee.getByTestId('new-expense').click();
    const form = employee.getByTestId('expense-form');
    await form.getByLabel('Devise', { exact: true }).selectOption('CDF');
    await form.getByLabel(/^Montant/u).fill('125000');
    await form.getByLabel('Date de la dépense').fill(utcDate(-2));
    await form.getByLabel('Catégorie').selectOption('SUPPLIES');
    await form.getByLabel('Commerçant ou bénéficiaire').fill(MERCHANT_3);
    await form.getByLabel('Description').fill(DESCRIPTION);
    await expectAccessible(employee);
    await noOverflow(employee);
    await form.getByRole('button', { name: 'Enregistrer', exact: true }).click();
    await expect(employee.getByTestId('announcer')).toContainText(
      'Brouillon de note de frais créé.',
    );
    await expect(employee.getByTestId('expense-amount')).toContainText('CDF');
    await employee.getByTestId('receipt-input').setInputFiles({
      name: RECEIPT_NAME,
      mimeType: 'image/png',
      buffer: syntheticPng(),
    });
    await expect(employee.getByTestId('announcer')).toContainText('Justificatif joint.');
    await employee.getByTestId('submit-expense').click();
    await expect(employee.getByTestId('announcer')).toContainText(
      'Note de frais soumise pour approbation.',
    );

    // 2. Mireille returns it with a reason.
    const managerContext = await browser.newContext();
    const manager = await managerContext.newPage();
    await signIn(manager, [MAN.email, MAN.password], 'fr', undefined);
    await primaryNav(manager)
      .getByRole('link', { name: 'Notes de frais à approuver', exact: true })
      .click();
    await expect(manager.getByRole('heading', { level: 1 })).toHaveText(
      'Notes de frais à approuver',
    );
    const queued = manager.getByTestId('approval-item').filter({ hasText: MERCHANT_3 });
    await expect(queued).toHaveCount(1);
    await expect(queued).toContainText(BEN.number);
    await expectAccessible(manager);
    await queued.getByRole('button').click();
    await expect(manager.getByTestId('approval-detail').getByRole('heading').first()).toBeFocused();
    await manager.getByTestId('return-expense').click();
    await manager.getByTestId('confirm-return').click();
    await expect(
      manager.getByText('Indiquez un motif de 10 à 500 caractères, sans lien ni balisage.'),
    ).toBeVisible();
    await manager.getByLabel('Motif du renvoi').fill(REASON);
    await expectAccessible(manager);
    await noOverflow(manager);
    await manager.getByTestId('confirm-return').click();
    await expect(manager.getByTestId('announcer')).toContainText(
      'Note de frais renvoyée pour correction.',
    );
    await expect(manager.getByTestId('approval-item').filter({ hasText: MERCHANT_3 })).toHaveCount(
      0,
    );

    // 3. Bénédicte sees the reason, corrects and resubmits.
    await employee.getByRole('button', { name: 'Retour à la liste' }).click();
    await employee
      .getByTestId('my-expense')
      .filter({ hasText: MERCHANT_3 })
      .getByRole('button')
      .click();
    await expect(employee.getByTestId('returned-note')).toContainText(REASON);
    await expect(employee.getByTestId('expense-state').first()).toHaveText(
      'Renvoyée pour correction',
    );
    await employee.getByRole('button', { name: 'Modifier', exact: true }).click();
    const again = employee.getByTestId('expense-form');
    await again.getByLabel(/^Montant/u).fill('135000');
    await again.getByRole('button', { name: 'Enregistrer', exact: true }).click();
    await expect(employee.getByTestId('announcer')).toContainText('Note de frais enregistrée.');
    await employee.getByTestId('submit-expense').click();
    await expect(employee.getByTestId('announcer')).toContainText(
      'Note de frais soumise pour approbation.',
    );
    await expect(employee.getByTestId('expense-revision')).toHaveCount(2);

    // 4. Mireille approves after an explicit confirmation.
    await manager.reload();
    await manager.getByRole('banner').getByRole('button', { name: 'Se connecter' }).click();
    const requeued = manager.getByTestId('approval-item').filter({ hasText: MERCHANT_3 });
    await expect(requeued).toContainText('135');
    await requeued.getByRole('button').click();
    await manager.getByTestId('approve-expense').click();
    await expect(manager.getByTestId('approve-confirm')).toContainText(
      'ne signifie ni la rembourser ni la payer',
    );
    await manager.getByTestId('confirm-approve').click();
    await expect(manager.getByTestId('announcer')).toContainText('Note de frais approuvée.');
    await expect(manager.getByTestId('approval-item').filter({ hasText: MERCHANT_3 })).toHaveCount(
      0,
    );

    // 5. Bénédicte sees the final status; the administrator observes read-only.
    await employee.getByRole('button', { name: 'Retour à la liste' }).click();
    await employee
      .getByTestId('my-expense')
      .filter({ hasText: MERCHANT_3 })
      .getByRole('button')
      .click();
    await expect(employee.getByTestId('expense-state').first()).toHaveText('Approuvée');
    await expect(employee.getByTestId('expense-action').last()).toContainText('Approuvée');
    expect(await employee.getByTestId('expense-action').count()).toBeGreaterThanOrEqual(7);
    await expect(employee.getByTestId('submit-expense')).toHaveCount(0);
    await expectAccessible(employee);
    expect(await storage(employee)).not.toMatch(new RegExp(`${stamp}|${BEN.family}`, 'iu'));
    expect(employee.url()).not.toContain(stamp);
    await employeeContext.close();
    await managerContext.close();

    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    await signIn(admin, USERS.adminA, 'fr');
    await primaryNav(admin).getByRole('link', { name: 'Notes de frais', exact: true }).click();
    await expect(admin.getByRole('heading', { level: 1 })).toHaveText('Notes de frais');
    const observed = admin.getByTestId('admin-expense').filter({ hasText: MERCHANT_3 });
    await expect(observed).toHaveCount(1);
    await expect(observed.getByTestId('expense-state')).toHaveText('Approuvée');
    await expect(observed.getByTestId('expense-amount')).toContainText('CDF');
    await expectAccessible(admin);
    await noOverflow(admin);
    await observed.getByRole('button').click();
    const view = admin.getByTestId('admin-expense-detail');
    await expect(view.getByTestId('receipt-download').first()).toBeVisible();
    // Observation only: apart from the receipt downloads, the one button leaves the view.
    const others = view.getByRole('button').filter({ hasNotText: /^Télécharger/u });
    await expect(others).toHaveCount(1);
    await expect(others).toHaveText('Retour à la liste');
    await adminContext.close();
  });
});

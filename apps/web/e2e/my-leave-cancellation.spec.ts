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

// MVP-041C (Issue #91): an employee cancels their own pending request. Setup (API, the
// administrator's existing SSO session): one invited and linked employee, one policy routed to the
// manager and one to tenant administrators. The employee (one interactive sign-in) submits one
// request under each (API), cancels the administrator-routed one in « Mes congés » after an
// explicit confirmation, sees « Annulée » with the reason as written, switches to English without a
// reload (the reason is unchanged), and submits the same dates again (they were released). The
// tenant administrator's inbox no longer lists it. Races between approval and cancellation are
// covered by the Core tests, not here. axe, reflow at 320 px, 390 px and 200 % zoom, browser storage
// and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `CE-${stamp}`;
const SITE = `CS-${stamp}`;
const DEPARTMENT = `CD-${stamp}`;
const NUMBER = `CAN-${stamp}-1`;
const FAMILY = `Ilunga${lower.replace(/[^a-z]/gu, '')}`;
const EMAIL = `e2e.annulation.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Annul!`;
const MANAGED = `CM-${stamp}`;
const MANAGED_FR = `Congé du responsable ${stamp}`;
const MANAGED_EN = `Manager leave ${stamp}`;
const CENTRAL = `CA-${stamp}`;
const CENTRAL_FR = `Congé administratif ${stamp}`;
const CENTRAL_EN = `Administrative leave ${stamp}`;
const REASON = `Mes dates ont changé, désolée ! (${stamp})`;

const dates = { start: '', end: '' };

function utcDate(days: number): string {
  const value = new Date();
  value.setUTCHours(0, 0, 0, 0);
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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-cx-${key}-${stamp}-0001` },
    data,
  });
}

function policy(code: string, en: string, fr: string, approvalRoute: string) {
  return {
    code,
    names: { en, fr },
    unit: 'DAYS',
    balanceMode: 'UNTRACKED',
    minimumServiceDays: 0,
    approvalRoute,
    payrollEffect: 'PAID',
    effectiveFrom: '2026-01-01',
  };
}

test.describe.serial('MVP-041C: cancel my pending leave request', () => {
  test('setup: two routes and one linked employee', async ({ page, browser }) => {
    test.setTimeout(240_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Annulations Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Annulations ${stamp}`,
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
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${NUMBER};Bénédicte;${FAMILY};${utcDate(-30)};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-cx-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-cx-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 1, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    for (const [key, body] of [
      ['mgr', policy(MANAGED, MANAGED_EN, MANAGED_FR, 'MANAGER')],
      ['adm', policy(CENTRAL, CENTRAL_EN, CENTRAL_FR, 'TENANT_ADMIN')],
    ] as const) {
      expect((await post(page, bearer, '/leave-policies', `pol-${key}`, body)).status()).toBe(201);
    }
    const found = await page.request.post(`${CORE_API}/employees/search`, {
      headers: { Authorization: bearer() },
      data: { query: NUMBER, limit: 5 },
    });
    expect(found.status()).toBe(200);
    const employee =
      ((await found.json()) as { items: { id: string; employeeNumber: string }[] }).items.find(
        (item) => item.employeeNumber === NUMBER,
      )?.id ?? '';
    expect(employee).not.toBe('');

    // Invite, accept, set the password, link the access to the employee record.
    expect(
      (
        await post(page, bearer, '/invitations', 'inv', {
          email: EMAIL,
          role: 'employee',
          locale: 'fr',
        })
      ).status(),
    ).toBe(201);
    const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
      await mailTo(EMAIL, /Invitation à rejoindre/u),
    );
    expect(link, 'invitation link').not.toBeNull();
    expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
    const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
      await mailTo(EMAIL, /mot de passe|password|actions|compte|account/iu),
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
      data: { email: EMAIL },
    });
    expect(lookup.status()).toBe(200);
    expect(
      (
        await post(page, bearer, `/employees/${employee}/access-link`, 'link', {
          membershipId: ((await lookup.json()) as { membershipId: string }).membershipId,
        })
      ).status(),
    ).toBe(201);
  });

  test('the employee cancels a pending request in French and sees it cancelled in either language', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    const bearer = await signIn(page, [EMAIL, PASSWORD], 'fr', undefined);
    const policies = await page.request.get(`${CORE_API}/me/leave-policies?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const items = ((await policies.json()) as { items: { id: string; code: string }[] }).items;
    const id = (code: string) => items.find((item) => item.code === code)?.id ?? '';
    dates.start = utcDate(20);
    dates.end = utcDate(21);
    for (const [key, code, from, to] of [
      ['managed', MANAGED, utcDate(10), utcDate(10)],
      ['central', CENTRAL, dates.start, dates.end],
    ] as const) {
      const created = await post(page, bearer, '/me/leave-requests', `req-${key}`, {
        policyId: id(code),
        startDate: from,
        endDate: to,
        amount: 2,
      });
      expect(created.status()).toBe(201);
    }

    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const central = page.getByTestId('my-request').filter({ hasText: CENTRAL_FR });
    const managed = page.getByTestId('my-request').filter({ hasText: MANAGED_FR });
    await expect(central.getByTestId('request-state')).toHaveText('En attente');
    await expect(page.getByTestId('cancel-request')).toHaveCount(2);
    await expectAccessible(page);

    await central.getByRole('button', { name: /^Annuler la demande Congé administratif/u }).click();
    const dialog = page.getByTestId('cancel-dialog');
    await expect(
      page.getByRole('dialog', { name: 'Annuler cette demande de congé' }),
    ).toBeVisible();
    await expect(dialog.getByLabel('Motif de l’annulation', { exact: true })).toBeFocused();
    await expect(dialog.getByLabel('Langue du motif')).toHaveValue('fr');
    await dialog.getByLabel('Motif de l’annulation', { exact: true }).fill(`  ${REASON}  `);
    await dialog.getByRole('button', { name: 'Vérifier l’annulation' }).click();
    await expect(page.getByRole('dialog', { name: 'Confirmer l’annulation' })).toBeVisible();
    await expect(dialog.getByTestId('cancel-reason')).toHaveText(REASON);
    const confirm = dialog.getByRole('button', { name: 'Confirmer l’annulation' });
    await expect(confirm).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('announcer')).toContainText('a été annulée');
    await expect(central.getByTestId('request-state')).toHaveText('Annulée');
    const reason = central.getByTestId('request-decision').locator('[lang="fr"]');
    await expect(reason).toHaveText(REASON);
    await expect(central.getByTestId('cancel-request')).toHaveCount(0);
    await expect(managed.getByTestId('request-state')).toHaveText('En attente');
    await expect(page.getByRole('heading', { name: 'Mes demandes' })).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);

    // English without a reload: the interface changes, the reason does not.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My leave');
    await expect(central.getByTestId('request-state')).toHaveText('Cancelled');
    await expect(reason).toHaveText(REASON);
    await expectAccessible(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${FAMILY}|dates ont`, 'iu'));
    expect(page.url()).not.toContain(stamp);

    // The cancelled dates are free again (submitted under the other policy).
    const again = await post(page, bearer, '/me/leave-requests', 'req-again', {
      policyId: id(MANAGED),
      startDate: dates.start,
      endDate: dates.end,
      amount: 2,
    });
    expect(again.status()).toBe(201);
  });

  test('the tenant administrator inbox no longer lists the cancelled request', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Leave approvals', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Leave approvals');
    // Other runs may share this tenant: only this run's employee is checked.
    await expect(
      page.getByTestId('inbox-empty').or(page.getByTestId('approval-item').first()),
    ).toBeVisible();
    await expect(page.getByTestId('approval-item').filter({ hasText: FAMILY })).toHaveCount(0);
  });
});

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

// MVP-041B (Issue #89): one-step leave approval with the real identity provider and database.
// Setup (API): one employee reports to a manager; both, and an unrelated employee, have invited and
// linked employee access; one policy routes approval to the manager, another to tenant
// administrators. The employee submits one request under each (API). The manager sees only the
// manager-routed request in « Approbations de congé » and approves it with a French reason after
// an explicit confirmation; an unrelated employee can neither list nor decide it; the tenant
// administrator (existing MFA session) sees only the administrator-routed request and rejects it in
// English. The employee sees both outcomes with the reasons as written, switches language without
// a reload, and the reasons stay unchanged. axe, reflow at 320 px, 390 px and 200 % zoom, and
// browser storage are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `AE-${stamp}`;
const SITE = `AS-${stamp}`;
const DEPARTMENT = `AD-${stamp}`;
const REQUESTER = `APR-${stamp}-1`;
const MANAGER = `APR-${stamp}-2`;
const STRANGER = `APR-${stamp}-3`;
const FAMILY = `Kalala${lower.replace(/[^a-z]/gu, '')}`;
const REQUESTER_EMAIL = `e2e.approb.demande.${lower}@example.test`;
const MANAGER_EMAIL = `e2e.approb.resp.${lower}@example.test`;
const STRANGER_EMAIL = `e2e.approb.autre.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Appro!`;
const MANAGED = `RM-${stamp}`;
const MANAGED_FR = `Congé du responsable ${stamp}`;
const MANAGED_EN = `Manager leave ${stamp}`;
const CENTRAL = `RA-${stamp}`;
const CENTRAL_FR = `Congé administratif ${stamp}`;
const CENTRAL_EN = `Administrative leave ${stamp}`;
const FRENCH_REASON = `Équipe au complet, bon congé ! (${stamp})`;
const ENGLISH_REASON = `Year-end closing week: please choose other dates (${stamp}).`;

const requests: { managed: string; central: string } = { managed: '', central: '' };

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ap-${key}-${stamp}-0001` },
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

test.describe.serial('MVP-041B: approve or reject leave requests', () => {
  test('setup: two routes, a reporting line and three linked employees', async ({
    page,
    browser,
  }) => {
    test.setTimeout(300_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Approbations Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const legalEntityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: `Site Approbations ${stamp}`,
      timezone: 'Africa/Kinshasa',
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    const siteId = ((await site.json()) as { id: string }).id;
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId,
          code: DEPARTMENT,
          name: `Opérations ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const start = plusDays(today(), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${REQUESTER};Bénédicte;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${MANAGER};Josué;Kabeya;${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${STRANGER};Aline;Tshibanda;${start};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-ap-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ap-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    for (const [key, body] of [
      ['mgr', policy(MANAGED, MANAGED_EN, MANAGED_FR, 'MANAGER')],
      ['adm', policy(CENTRAL, CENTRAL_EN, CENTRAL_FR, 'TENANT_ADMIN')],
    ] as const) {
      expect((await post(page, bearer, '/leave-policies', `pol-${key}`, body)).status()).toBe(201);
    }

    // The requester reports to the manager from today on (employment-history API).
    const requester = employeeId(REQUESTER);
    const change = {
      type: 'CHANGE',
      effectiveFrom: today(),
      manager: { employeeId: employeeId(MANAGER) },
    };
    const preview = await page.request.post(
      `${CORE_API}/employees/${requester}/employment-changes/preview`,
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
        await post(page, bearer, `/employees/${requester}/employment-changes`, 'mgr-line', {
          ...change,
          expectedVersion: previewed.expectedVersion,
          previewDigest: previewed.previewDigest,
          acknowledgeRetroactive: previewed.requiresAcknowledgement,
        })
      ).status(),
    ).toBe(201);

    await inviteAndLink(page, browser, bearer, REQUESTER_EMAIL, requester, 'req');
    await inviteAndLink(page, browser, bearer, MANAGER_EMAIL, employeeId(MANAGER), 'mgr');
    await inviteAndLink(page, browser, bearer, STRANGER_EMAIL, employeeId(STRANGER), 'oth');
  });

  test('the employee submits one request under each route', async ({ page }) => {
    test.setTimeout(120_000);
    const bearer = await signIn(page, [REQUESTER_EMAIL, PASSWORD], 'fr', undefined);
    const policies = await page.request.get(`${CORE_API}/me/leave-policies?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const items = ((await policies.json()) as { items: { id: string; code: string }[] }).items;
    const id = (code: string) => items.find((item) => item.code === code)?.id ?? '';
    for (const [key, code, from, to] of [
      ['managed', MANAGED, 10, 12],
      ['central', CENTRAL, 20, 20],
    ] as const) {
      const created = await post(page, bearer, '/me/leave-requests', `req-${key}`, {
        policyId: id(code),
        startDate: plusDays(today(), from),
        endDate: plusDays(today(), to),
        amount: key === 'managed' ? 2.5 : 1,
      });
      expect(created.status()).toBe(201);
      requests[key] = ((await created.json()) as { id: string }).id;
    }
  });

  test('the manager sees only the manager-routed request and approves it in French', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, [MANAGER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page)
      .getByRole('link', { name: 'Approbations de congé', exact: true })
      .click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Approbations de congé');
    const items = page.getByTestId('approval-item');
    await expect(items).toHaveCount(1);
    const item = items.first();
    await expect(item.getByRole('heading', { level: 3 })).toHaveText(`Bénédicte ${FAMILY}`);
    await expect(item).toContainText(MANAGED_FR);
    await expect(item).toContainText(MANAGED_EN);
    await expect(item.getByTestId('approval-amount')).toHaveText('2,5 jours');
    await expect(page.getByText(CENTRAL_FR)).toHaveCount(0);
    await expectAccessible(page);
    await noOverflow(page);

    const approve = item.getByRole('button', {
      name: `Approuver la demande de Bénédicte ${FAMILY}…`,
    });
    await approve.click();
    await expect(
      page.getByRole('dialog', { name: 'Approuver cette demande de congé' }),
    ).toBeVisible();
    const dialog = page.getByTestId('decision-dialog');
    await expect(dialog.getByLabel('Motif', { exact: true })).toBeFocused();
    await expect(dialog.getByLabel('Langue du motif')).toHaveValue('fr');
    // The explicit confirmation step.
    await dialog.getByRole('button', { name: 'Vérifier la décision' }).click();
    await expect(dialog.getByTestId('decision-problems')).toBeFocused();
    await dialog.getByLabel('Motif', { exact: true }).fill(FRENCH_REASON);
    await dialog.getByRole('button', { name: 'Vérifier la décision' }).click();
    await expect(page.getByRole('dialog', { name: 'Confirmer l’approbation' })).toBeVisible();
    await expect(dialog.getByTestId('decision-reason')).toHaveText(FRENCH_REASON);
    const confirm = dialog.getByRole('button', { name: 'Confirmer l’approbation' });
    await expect(confirm).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('inbox-empty')).toHaveText(
      'Aucune demande de congé n’attend votre décision.',
    );
    await expect(page.getByTestId('announcer')).toContainText('a été approuvée');
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${FAMILY}|Équipe`, 'iu'));
    expect(page.url()).not.toContain(stamp);
  });

  test('an unrelated employee can neither list nor decide it', async ({ page }) => {
    test.setTimeout(120_000);
    const bearer = await signIn(page, [STRANGER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page)
      .getByRole('link', { name: 'Approbations de congé', exact: true })
      .click();
    await expect(page.getByTestId('inbox-empty')).toBeVisible();
    const listed = await page.request.get(`${CORE_API}/me/leave-approvals`, {
      headers: { Authorization: bearer() },
    });
    expect(listed.status()).toBe(200);
    expect(listed.headers()['cache-control']).toContain('no-store');
    expect(((await listed.json()) as { items: unknown[] }).items).toHaveLength(0);
    for (const id of [requests.managed, requests.central]) {
      const decided = await post(page, bearer, `/me/leave-approvals/${id}/decision`, `x-${id}`, {
        decision: 'REJECTED',
        reasonLocale: 'fr',
        reason: 'Tentative non autorisée',
      });
      expect(decided.status()).toBe(404);
      expect(((await decided.json()) as { code: string }).code).toBe('LEAVE_REQUEST_NOT_FOUND');
    }
    const admin = await page.request.get(`${CORE_API}/leave-approvals`, {
      headers: { Authorization: bearer() },
    });
    expect(admin.status()).toBe(403);
  });

  test('the tenant administrator sees only the administrator-routed request and rejects it', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Leave approvals', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Leave approvals');
    // Other runs may have left requests in this shared tenant: only this run's are checked.
    const ours = page.getByTestId('approval-item').filter({ hasText: FAMILY });
    await expect(ours).toHaveCount(1);
    await expect(ours).toContainText(CENTRAL_EN);
    await expect(page.getByText(MANAGED_EN)).toHaveCount(0);
    await ours.getByRole('button', { name: `Reject the request of Bénédicte ${FAMILY}…` }).click();
    await expect(page.getByRole('dialog', { name: 'Reject this leave request' })).toBeVisible();
    const dialog = page.getByTestId('decision-dialog');
    await expect(dialog.getByLabel('Language of the reason')).toHaveValue('en');
    await dialog.getByLabel('Reason', { exact: true }).fill(ENGLISH_REASON);
    await dialog.getByRole('button', { name: 'Review the decision' }).click();
    await dialog.getByRole('button', { name: 'Confirm the rejection' }).click();
    await expect(dialog).toBeHidden();
    await expect(ours).toHaveCount(0);
    await expect(page.getByTestId('announcer')).toContainText('was rejected');
    await expectAccessible(page);
  });

  test('the employee sees both outcomes and the reasons as written in either language', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, [REQUESTER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const managed = page.getByTestId('my-request').filter({ hasText: MANAGED_FR });
    const central = page.getByTestId('my-request').filter({ hasText: CENTRAL_FR });
    await expect(managed.getByTestId('request-state')).toHaveText('Approuvée');
    await expect(central.getByTestId('request-state')).toHaveText('Rejetée');
    const french = managed.getByTestId('request-decision').locator('[lang="fr"]');
    const english = central.getByTestId('request-decision').locator('[lang="en"]');
    await expect(french).toHaveText(FRENCH_REASON);
    await expect(english).toHaveText(ENGLISH_REASON);
    await expect(page.getByTestId('my-requests')).not.toContainText('Kabeya');
    await expectAccessible(page);
    await noOverflow(page);

    // English without a reload: the interface changes, the reasons do not.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My leave');
    await expect(managed.getByTestId('request-state')).toHaveText('Approved');
    await expect(central.getByTestId('request-state')).toHaveText('Rejected');
    await expect(french).toHaveText(FRENCH_REASON);
    await expect(english).toHaveText(ENGLISH_REASON);
    await expectAccessible(page);
    await noOverflow(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|Équipe|Year-end`, 'iu'));
  });
});

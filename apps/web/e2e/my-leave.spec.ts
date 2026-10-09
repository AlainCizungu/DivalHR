import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  CORE_API,
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

// MVP-041A (Issue #87): an employee submits a leave request with the real identity provider and
// database. Setup: the administrator creates two policies and two employees, each with an invited
// and linked employee access (the MVP-030 self-service setup). The first employee opens « Mes
// congés », sees the policies, submits a request with a French decimal, sees it pending, switches to
// English without a reload and sees the English name and status; the page passes axe and has no
// horizontal overflow at 320 px, 390 px and 200 % zoom. The second employee sees none of the first
// one's requests and cannot use the administrator's policy endpoint. No request data reaches
// browser storage.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `LE-${stamp}`;
const SITE = `LS-${stamp}`;
const DEPARTMENT = `LD-${stamp}`;
const OWNER = `LVE-${stamp}-1`;
const OTHER = `LVE-${stamp}-2`;
const FAMILY = `Ilunga${lower.replace(/[^a-z]/gu, '')}`;
const OWNER_EMAIL = `e2e.conge.${lower}@example.test`;
const OTHER_EMAIL = `e2e.conge.autre.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Conge!`;
const POLICY = `CA-${stamp}`;
const POLICY_FR = `Congé annuel ${stamp} — été`;
const POLICY_EN = `Annual leave ${stamp}`;

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-lv-${key}-${stamp}-0001` },
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
  const accepted = await page.request.post(`${CORE_API}/public/invitations/accept`, {
    data: { token: link?.[1] ?? '' },
  });
  expect(accepted.status()).toBe(200);
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

test.describe.serial('MVP-041A: submit and view my leave requests', () => {
  test('setup: two policies, two employees with linked employee access', async ({
    page,
    browser,
  }) => {
    test.setTimeout(240_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Congés Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const legalEntityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: `Site Congés ${stamp}`,
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
          name: `Ressources ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const start = plusDays(today(), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${OWNER};Bénédicte;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${OTHER};Jean-Pierre;Mukendi;${start};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-lv-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-lv-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    const policy = await post(page, bearer, '/leave-policies', 'pol', {
      code: POLICY,
      names: { en: POLICY_EN, fr: POLICY_FR },
      unit: 'DAYS',
      balanceMode: 'UNTRACKED',
      minimumServiceDays: 0,
      approvalRoute: 'MANAGER',
      payrollEffect: 'PAID',
      effectiveFrom: '2026-01-01',
    });
    expect(policy.status()).toBe(201);
    await inviteAndLink(page, browser, bearer, OWNER_EMAIL, employeeId(OWNER), 'owner');
    await inviteAndLink(page, browser, bearer, OTHER_EMAIL, employeeId(OTHER), 'other');
  });

  test('the employee submits a request in French and reviews it in English', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, [OWNER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mes congés');
    await expect(page.getByTestId('my-leave-scope')).toContainText(
      'Les jours ouvrables, les jours fériés et les soldes de congé ne sont pas encore calculés.',
    );
    await expect(page.getByTestId('history-empty')).toBeVisible();
    const policy = page.getByTestId('my-policy').filter({ hasText: POLICY_FR });
    await expect(policy.getByTestId('policy-status')).toHaveText('En vigueur');

    const first = plusDays(today(), 7);
    const last = plusDays(today(), 9);
    const form = page.getByTestId('request-leave');
    await form.getByLabel('Politique de congé').selectOption({ label: `${POLICY_FR} (${POLICY})` });
    await form.getByLabel('Premier jour').fill(first);
    await form.getByLabel('Dernier jour').fill(last);
    await form.getByLabel('Jours demandés').fill('2,5');
    await form.getByRole('button', { name: 'Soumettre la demande' }).click();

    const row = page.getByTestId('my-request');
    await expect(row).toHaveCount(1);
    await expect(row.getByRole('rowheader')).toContainText(POLICY_FR);
    await expect(row).toContainText('2,5 jours');
    await expect(row.getByTestId('request-state')).toHaveText('En attente');
    await expect(page.getByTestId('announcer')).toContainText(POLICY_FR);
    await expect(form.getByLabel('Politique de congé')).toHaveValue('');
    await expectAccessible(page);
    await noOverflow(page);

    // The same dates again overlap the pending request.
    await form.getByLabel('Politique de congé').selectOption({ label: `${POLICY_FR} (${POLICY})` });
    await form.getByLabel('Premier jour').fill(first);
    await form.getByLabel('Dernier jour').fill(first);
    await form.getByLabel('Jours demandés').fill('1');
    await form.getByRole('button', { name: 'Soumettre la demande' }).click();
    await expect(page.getByTestId('request-error')).toContainText(
      'Vous avez déjà une demande en attente ou approuvée pour une partie de ces dates.',
    );
    await expect(page.getByTestId('request-error')).toBeFocused();

    // English without a reload.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My leave');
    await expect(row.getByRole('rowheader')).toContainText(POLICY_EN);
    await expect(row.getByTestId('request-state')).toHaveText('Pending');
    await expect(row).toContainText('2.5 days');
    await expectAccessible(page);
    await noOverflow(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${first}|Congé`, 'iu'));
  });

  test('another employee sees none of it and cannot use the administrator endpoint', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    const bearer = await signIn(page, [OTHER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    await expect(page.getByTestId('history-empty')).toBeVisible();
    await expect(primaryNav(page).getByRole('link', { name: 'Politiques de congé' })).toHaveCount(
      0,
    );
    const own = await page.request.get(`${CORE_API}/me/leave-requests`, {
      headers: { Authorization: bearer() },
    });
    expect(own.status()).toBe(200);
    expect(((await own.json()) as { items: unknown[] }).items).toHaveLength(0);
    for (const response of [
      await page.request.get(`${CORE_API}/leave-policies`, {
        headers: { Authorization: bearer() },
      }),
      await page.request.post(`${CORE_API}/leave-policies`, {
        headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-lv-deny-${stamp}-0001` },
        data: { code: `DENY-${stamp}` },
      }),
    ]) {
      expect(response.status()).toBe(403);
      expect(response.headers()['cache-control']).toContain('no-store');
    }
    // No request names an employee: an extra field is refused, never honoured.
    const foreign = await page.request.post(`${CORE_API}/me/leave-requests`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-lv-foreign-${stamp}-0001` },
      data: {
        policyId: '00000000-0000-4000-8000-000000000001',
        startDate: plusDays(today(), 1),
        endDate: plusDays(today(), 1),
        amount: 1,
        employeeId: employeeId(OWNER),
      },
    });
    expect(foreign.status()).toBe(400);
    expect(JSON.stringify(await foreign.json())).not.toContain(employeeId(OWNER));
  });
});

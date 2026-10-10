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

// MVP-041D/E (Issue #92). Setup (API, the administrator's existing SSO session): two invited and
// linked employees (a requester without a manager, and a future manager), one policy routed to the
// manager and one to tenant administrators. The requester (one interactive sign-in) amends a
// pending request in « Mes congés » after a confirmation of the old and new values: the original
// shows « Modifiée » with the reason as written, the replacement « En attente », unchanged in
// English; the original's dates are released and the replacement's are blocked. Two MANAGER-routed
// requests without a qualifying manager appear only in the tenant administrator's routing
// exceptions, where one is approved as an override (exact MFA, the administrator's session); a
// manager line added for the other's first day removes it from the exceptions and shows it in that
// manager's inbox (one interactive sign-in). The requester sees the override outcome and reason but
// no override identity. Races are covered by the Core tests, not here. axe, reflow at 320 px, 390 px
// and 200 % zoom, browser storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `AE-${stamp}`;
const SITE = `AS-${stamp}`;
const DEPARTMENT = `AD-${stamp}`;
const NUMBER = `AMD-${stamp}-1`;
const MANAGER_NUMBER = `AMD-${stamp}-2`;
const FAMILY = `Kalala${lower.replace(/[^a-z]/gu, '')}`;
const MANAGER_FAMILY = `Kabeya${lower.replace(/[^a-z]/gu, '')}`;
const EMAIL = `e2e.modification.${lower}@example.test`;
const MANAGER_EMAIL = `e2e.responsable.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Modif!`;
const MANAGED = `AM-${stamp}`;
const MANAGED_FR = `Congé du responsable ${stamp}`;
const MANAGED_EN = `Manager leave ${stamp}`;
const CENTRAL = `AA-${stamp}`;
const CENTRAL_FR = `Congé administratif ${stamp}`;
const CENTRAL_EN = `Administrative leave ${stamp}`;
const REASON = `Mes dates de voyage ont changé (${stamp})`;
const OVERRIDE_REASON = `Aucun responsable admissible, accordé (${stamp})`;

const ids = { employee: '', manager: '', exception: '', returned: '' };

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

async function clean(page: Page) {
  expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${FAMILY}|voyage|admissible`, 'iu'));
  expect(page.url()).not.toContain(stamp);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-am-${key}-${stamp}-0001` },
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

/** Invites, accepts, sets the password and links the access to the employee record. */
async function inviteAndLink(
  page: Page,
  browser: import('@playwright/test').Browser,
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

async function employeeId(page: Page, bearer: () => string, number: string) {
  const found = await page.request.post(`${CORE_API}/employees/search`, {
    headers: { Authorization: bearer() },
    data: { query: number, limit: 5 },
  });
  expect(found.status()).toBe(200);
  const id =
    ((await found.json()) as { items: { id: string; employeeNumber: string }[] }).items.find(
      (item) => item.employeeNumber === number,
    )?.id ?? '';
  expect(id).not.toBe('');
  return id;
}

test.describe.serial('MVP-041D/E: amend a pending request and resolve routing exceptions', () => {
  test('setup: two routes and two linked employees, no reporting line', async ({
    page,
    browser,
  }) => {
    test.setTimeout(300_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Modifications Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Modifications ${stamp}`,
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
      `${MANAGER_NUMBER};Josué;${MANAGER_FAMILY};${utcDate(-30)};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-am-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-am-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    for (const [key, body] of [
      ['mgr', policy(MANAGED, MANAGED_EN, MANAGED_FR, 'MANAGER')],
      ['adm', policy(CENTRAL, CENTRAL_EN, CENTRAL_FR, 'TENANT_ADMIN')],
    ] as const) {
      expect((await post(page, bearer, '/leave-policies', `pol-${key}`, body)).status()).toBe(201);
    }
    ids.employee = await employeeId(page, bearer, NUMBER);
    ids.manager = await employeeId(page, bearer, MANAGER_NUMBER);
    await inviteAndLink(page, browser, bearer, EMAIL, ids.employee, 'emp');
    await inviteAndLink(page, browser, bearer, MANAGER_EMAIL, ids.manager, 'mgr');
  });

  test('the employee amends a pending request in French; the chain shows in either language', async ({
    page,
  }) => {
    test.setTimeout(150_000);
    const bearer = await signIn(page, [EMAIL, PASSWORD], 'fr', undefined);
    const policies = await page.request.get(`${CORE_API}/me/leave-policies?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const items = ((await policies.json()) as { items: { id: string; code: string }[] }).items;
    const id = (code: string) => items.find((item) => item.code === code)?.id ?? '';
    // One administrator-routed request to amend, two MANAGER-routed ones without any manager.
    for (const [key, code, from, to] of [
      ['amend', CENTRAL, utcDate(10), utcDate(11)],
      ['exception', MANAGED, utcDate(20), utcDate(20)],
      ['returned', MANAGED, utcDate(30), utcDate(30)],
    ] as const) {
      const created = await post(page, bearer, '/me/leave-requests', `req-${key}`, {
        policyId: id(code),
        startDate: from,
        endDate: to,
        amount: 2,
      });
      expect(created.status()).toBe(201);
      const createdId = ((await created.json()) as { id: string }).id;
      if (key === 'exception') ids.exception = createdId;
      if (key === 'returned') ids.returned = createdId;
    }

    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const original = page.getByTestId('my-request').filter({ hasText: CENTRAL_FR });
    await expect(original.getByTestId('request-state')).toHaveText('En attente');
    await expectAccessible(page);
    await original
      .getByRole('button', { name: new RegExp(`^Modifier la demande ${CENTRAL_FR}`, 'u') })
      .click();
    const dialog = page.getByTestId('amend-dialog');
    await expect(
      page.getByRole('dialog', { name: 'Modifier cette demande de congé' }),
    ).toBeVisible();
    await dialog.getByLabel('Premier jour').fill(utcDate(12));
    await dialog.getByLabel('Dernier jour').fill(utcDate(13));
    await dialog.getByLabel('Motif de la modification', { exact: true }).fill(`  ${REASON}  `);
    await dialog.getByRole('button', { name: 'Vérifier la modification' }).click();
    await expect(page.getByRole('dialog', { name: 'Confirmer la modification' })).toBeVisible();
    await expect(dialog.getByTestId('amend-comparison')).toContainText(CENTRAL_FR);
    await expect(dialog.getByTestId('amend-reason')).toHaveText(REASON);
    const confirm = dialog.getByRole('button', { name: 'Confirmer la modification' });
    await expect(confirm).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('announcer')).toContainText('a été modifiée');
    await expect(page.getByRole('heading', { name: 'Mes demandes' })).toBeFocused();

    // Both entries: the original amended with the reason, the replacement pending, linked.
    const rows = page.getByTestId('my-request').filter({ hasText: CENTRAL_FR });
    await expect(rows).toHaveCount(2);
    const amended = rows.filter({ has: page.getByTestId('request-replaced-by') });
    const pending = rows.filter({ has: page.getByTestId('request-replaces') });
    await expect(amended.getByTestId('request-state')).toHaveText('Modifiée');
    await expect(pending.getByTestId('request-state')).toHaveText('En attente');
    const reason = amended.getByTestId('request-decision').locator('[lang="fr"]');
    await expect(reason).toHaveText(REASON);
    await expectAccessible(page);
    await noOverflow(page);

    // English without a reload: the interface changes, the reason does not.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My leave');
    await expect(amended.getByTestId('request-state')).toHaveText('Amended');
    await expect(pending.getByTestId('request-state')).toHaveText('Pending');
    await expect(reason).toHaveText(REASON);
    await expectAccessible(page);
    await clean(page);

    // The original's own days are released; the replacement's are blocked.
    const released = await post(page, bearer, '/me/leave-requests', 'req-released', {
      policyId: id(MANAGED),
      startDate: utcDate(10),
      endDate: utcDate(11),
      amount: 2,
    });
    expect(released.status()).toBe(201);
    const blocked = await post(page, bearer, '/me/leave-requests', 'req-blocked', {
      policyId: id(MANAGED),
      startDate: utcDate(13),
      endDate: utcDate(13),
      amount: 1,
    });
    expect(blocked.status()).toBe(409);
    expect(((await blocked.json()) as { code: string }).code).toBe('LEAVE_REQUEST_OVERLAP');
  });

  test('the tenant administrator approves a routing exception as an override', async ({ page }) => {
    test.setTimeout(150_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    // The exceptions are not in the administrator inbox (their route stays MANAGER).
    const inbox = await page.request.get(`${CORE_API}/leave-approvals?limit=50`, {
      headers: { Authorization: bearer() },
    });
    expect(JSON.stringify(await inbox.json())).not.toContain(ids.exception);

    await primaryNav(page)
      .getByRole('link', { name: 'Exceptions d’acheminement des congés', exact: true })
      .click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      'Exceptions d’acheminement des congés',
    );
    await expect(page.getByTestId('override-notice')).toBeVisible();
    const ours = page.getByTestId('approval-item').filter({ hasText: FAMILY });
    await expect(ours).toHaveCount(3);
    await expectAccessible(page);
    await noOverflow(page);
    // Every listed item carries the fixed reason code (API view of the same queue).
    const exceptions = await page.request.get(`${CORE_API}/leave-routing-exceptions?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const listed = (
      (await exceptions.json()) as { items: { id: string; exceptionReason: string }[] }
    ).items.filter((item) => [ids.exception, ids.returned].includes(item.id));
    expect(listed.map((item) => item.exceptionReason)).toEqual([
      'NO_QUALIFYING_MANAGER',
      'NO_QUALIFYING_MANAGER',
    ]);
    // Newest first: the released-dates request, the day-30 request, then the day-20 request.
    const item = ours.nth(2);
    await item.getByRole('button', { name: /^Approuver la demande de Bénédicte/u }).click();
    const dialog = page.getByTestId('decision-dialog');
    await expect(dialog.getByTestId('dialog-override-notice')).toBeVisible();
    await dialog.getByLabel('Motif', { exact: true }).fill(OVERRIDE_REASON);
    await dialog.getByRole('button', { name: 'Vérifier la décision' }).click();
    await expect(dialog.getByTestId('decision-confirm')).toContainText(
      'décision administrative exceptionnelle',
    );
    await expectAccessible(page);
    await dialog.getByRole('button', { name: 'Confirmer l’approbation exceptionnelle' }).click();
    await expect(dialog).toBeHidden();
    await expect(ours).toHaveCount(2);
    await clean(page);
    const decidedNow = JSON.stringify(
      await (
        await page.request.get(`${CORE_API}/leave-routing-exceptions?limit=50`, {
          headers: { Authorization: bearer() },
        })
      ).json(),
    );
    expect(decidedNow).not.toContain(ids.exception);
    expect(decidedNow).toContain(ids.returned);

    // A manager line from day 25 covers the request starting on day 30: it leaves the exceptions.
    const change = {
      type: 'CHANGE',
      effectiveFrom: utcDate(25),
      manager: { employeeId: ids.manager },
    };
    const preview = await page.request.post(
      `${CORE_API}/employees/${ids.employee}/employment-changes/preview`,
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
        await post(page, bearer, `/employees/${ids.employee}/employment-changes`, 'mgr-line', {
          ...change,
          expectedVersion: previewed.expectedVersion,
          previewDigest: previewed.previewDigest,
          acknowledgeRetroactive: previewed.requiresAcknowledgement,
        })
      ).status(),
    ).toBe(201);
    // Back to the page without a reload (the tokens live in memory only): the queue is re-read.
    await primaryNav(page)
      .getByRole('link', { name: 'Approbations de congé', exact: true })
      .click();
    await primaryNav(page)
      .getByRole('link', { name: 'Exceptions d’acheminement des congés', exact: true })
      .click();
    await expect(page.getByTestId('approval-item').filter({ hasText: FAMILY })).toHaveCount(1);
    const after = await page.request.get(`${CORE_API}/leave-routing-exceptions?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const remaining = JSON.stringify(await after.json());
    expect(remaining).not.toContain(ids.returned);
    expect(remaining).not.toContain(ids.exception);
  });

  test('the new manager sees the returned request; the employee sees the override outcome', async ({
    page,
    browser,
  }) => {
    test.setTimeout(150_000);
    await signIn(page, [MANAGER_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(page).getByRole('link', { name: 'Leave approvals', exact: true }).click();
    const theirs = page.getByTestId('approval-item').filter({ hasText: FAMILY });
    await expect(theirs).toHaveCount(1);
    await expect(theirs).toContainText(MANAGED_EN);

    const context = await browser.newContext({ locale: 'fr-FR' });
    const employeePage = await context.newPage();
    const bearer = await signIn(employeePage, [EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(employeePage).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const decided = employeePage.getByTestId('my-request').filter({
      has: employeePage.getByTestId('request-decision').filter({ hasText: OVERRIDE_REASON }),
    });
    await expect(decided.getByTestId('request-state')).toHaveText('Approuvée');
    await expectAccessible(employeePage);
    const own = await employeePage.request.get(`${CORE_API}/me/leave-requests?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const body = await own.text();
    expect(body).toContain(OVERRIDE_REASON);
    for (const leak of ['OVERRIDE', 'decisionAuthority', 'decidedBy', '"subject', 'dev-admin']) {
      expect(body.includes(leak), 'no override identity in the employee response').toBe(false);
    }
    await clean(employeePage);
    await context.close();
  });
});

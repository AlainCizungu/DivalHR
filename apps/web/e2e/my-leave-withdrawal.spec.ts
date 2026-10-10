import { expect, test, type Browser, type Page } from '@playwright/test';
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

// MVP-041F (Issue #95). Setup (API, the administrator's existing SSO session): two invited and
// linked employees and one policy routed to tenant administrators. The employee (one interactive
// sign-in) submits two requests through the API, one starting on the organization's business date
// and one later; the administrator approves both through the API. In « Mes congés » the employee
// withdraws the future one in French after a confirmation that shows the approved period, amount,
// policy and approval: the history shows « Retirée » with the approval and the withdrawal reasons,
// unchanged in English. Its dates are blocked before and reusable after; the one starting today
// offers no withdrawal and the API refuses it. The administrator's inbox and routing exceptions
// never regain it. Another employee (one interactive sign-in) can neither find nor withdraw it.
// Races are covered by the Core tests, not here. axe, reflow at 320 px, 390 px and 200 % zoom,
// browser storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `WE-${stamp}`;
const SITE = `WS-${stamp}`;
const DEPARTMENT = `WD-${stamp}`;
const NUMBER = `WDR-${stamp}-1`;
const OTHER_NUMBER = `WDR-${stamp}-2`;
const FAMILY = `Lumbala${lower.replace(/[^a-z]/gu, '')}`;
const OTHER_FAMILY = `Tshala${lower.replace(/[^a-z]/gu, '')}`;
const EMAIL = `e2e.retrait.${lower}@example.test`;
const OTHER_EMAIL = `e2e.collegue.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Retrait!`;
const CENTRAL = `WA-${stamp}`;
const CENTRAL_FR = `Congé administratif ${stamp}`;
const CENTRAL_EN = `Administrative leave ${stamp}`;
const APPROVAL_REASON = `Accordé, bon repos (${stamp})`;
const REASON = `Mes projets ont changé (${stamp})`;

const ids = { employee: '', other: '', policy: '', future: '', today: '', asOf: '' };

function addDays(day: string, days: number): string {
  const value = new Date(`${day}T00:00:00Z`);
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
  expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${FAMILY}|projets|repos`, 'iu'));
  expect(page.url()).not.toContain(stamp);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-wd-${key}-${stamp}-0001` },
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

test.describe.serial('MVP-041F: withdraw approved leave', () => {
  test('setup: one administrator-routed policy and two linked employees', async ({
    page,
    browser,
  }) => {
    test.setTimeout(300_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Retraits Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Retraits ${stamp}`,
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
    const hired = addDays(new Date().toISOString().slice(0, 10), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${NUMBER};Bénédicte;${FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${OTHER_NUMBER};Aline;${OTHER_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-wd-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-wd-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    const created = await post(page, bearer, '/leave-policies', 'pol', {
      code: CENTRAL,
      names: { en: CENTRAL_EN, fr: CENTRAL_FR },
      unit: 'DAYS',
      balanceMode: 'UNTRACKED',
      minimumServiceDays: 0,
      approvalRoute: 'TENANT_ADMIN',
      payrollEffect: 'PAID',
      effectiveFrom: '2026-01-01',
    });
    expect(created.status()).toBe(201);
    ids.employee = await employeeId(page, bearer, NUMBER);
    ids.other = await employeeId(page, bearer, OTHER_NUMBER);
    await inviteAndLink(page, browser, bearer, EMAIL, ids.employee, 'emp');
    await inviteAndLink(page, browser, bearer, OTHER_EMAIL, ids.other, 'oth');
  });

  test('the employee withdraws approved future leave in French; history in either language', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    const bearer = await signIn(page, [EMAIL, PASSWORD], 'fr', undefined);
    const policies = await page.request.get(`${CORE_API}/me/leave-policies?limit=50`, {
      headers: { Authorization: bearer() },
    });
    const listed = (await policies.json()) as {
      items: { id: string; code: string }[];
      asOf: string;
    };
    ids.policy = listed.items.find((item) => item.code === CENTRAL)?.id ?? '';
    ids.asOf = listed.asOf;
    expect(ids.policy).not.toBe('');
    const future = { start: addDays(ids.asOf, 10), end: addDays(ids.asOf, 11) };
    for (const [key, from, to] of [
      ['future', future.start, future.end],
      ['today', ids.asOf, ids.asOf],
    ] as const) {
      const made = await post(page, bearer, '/me/leave-requests', `req-${key}`, {
        policyId: ids.policy,
        startDate: from,
        endDate: to,
        amount: key === 'future' ? 2 : 1,
      });
      expect(made.status()).toBe(201);
      ids[key] = ((await made.json()) as { id: string }).id;
    }
    // The administrator approves both (API, the administrator's existing session).
    const adminContext = await browser.newContext({ locale: 'fr-FR' });
    const adminPage = await adminContext.newPage();
    const admin = await signIn(adminPage, USERS.adminA, 'fr');
    for (const id of [ids.future, ids.today]) {
      const decided = await post(adminPage, admin, `/leave-approvals/${id}/decision`, `ok-${id}`, {
        decision: 'APPROVED',
        reasonLocale: 'fr',
        reason: APPROVAL_REASON,
      });
      expect(decided.status()).toBe(200);
    }
    // Approved dates are blocked.
    const blocked = await post(page, bearer, '/me/leave-requests', 'req-before', {
      policyId: ids.policy,
      startDate: future.start,
      endDate: future.start,
      amount: 1,
    });
    expect(blocked.status()).toBe(409);
    expect(((await blocked.json()) as { code: string }).code).toBe('LEAVE_REQUEST_OVERLAP');

    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const rows = page.getByTestId('my-request').filter({ hasText: CENTRAL_FR });
    await expect(rows).toHaveCount(2);
    const offered = rows.filter({ has: page.getByTestId('withdraw-request') });
    await expect(page.getByTestId('withdraw-request')).toHaveCount(1);
    await expectAccessible(page);
    await offered
      .getByRole('button', {
        name: new RegExp(`^Retirer le congé approuvé ${CENTRAL_FR}`, 'u'),
      })
      .click();
    const dialog = page.getByTestId('withdraw-dialog');
    await expect(page.getByRole('dialog', { name: 'Retirer ce congé approuvé' })).toBeVisible();
    await expect(dialog.getByTestId('withdraw-summary')).toContainText(CENTRAL_FR);
    await expect(dialog.getByTestId('withdraw-approval')).toContainText('Approuvée');
    await dialog.getByLabel('Motif du retrait', { exact: true }).fill(`  ${REASON}  `);
    await dialog.getByRole('button', { name: 'Vérifier le retrait' }).click();
    await expect(page.getByRole('dialog', { name: 'Confirmer le retrait' })).toBeVisible();
    await expect(dialog.getByTestId('withdraw-reason')).toHaveText(REASON);
    const confirm = dialog.getByRole('button', { name: 'Confirmer le retrait' });
    await expect(confirm).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('announcer')).toContainText('a été retiré');
    await expect(page.getByRole('heading', { name: 'Mes demandes' })).toBeFocused();

    const withdrawn = rows.filter({ has: page.getByTestId('request-withdrawal') });
    await expect(withdrawn.getByTestId('request-state')).toHaveText('Retirée');
    const approval = withdrawn.getByTestId('request-decision').locator('p[lang="fr"]').first();
    await expect(approval).toHaveText(APPROVAL_REASON);
    const reason = withdrawn.getByTestId('request-withdrawal').locator('[lang="fr"]');
    await expect(reason).toHaveText(REASON);
    await expect(page.getByTestId('withdraw-request')).toHaveCount(0);
    await expectAccessible(page);
    await noOverflow(page);
    await clean(page);

    // English without a reload: the interface changes, both reasons do not.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My leave');
    await expect(withdrawn.getByTestId('request-state')).toHaveText('Withdrawn');
    await expect(approval).toHaveText(APPROVAL_REASON);
    await expect(reason).toHaveText(REASON);
    await expectAccessible(page);
    await clean(page);

    // The dates are reusable; leave starting today cannot be withdrawn.
    const reused = await post(page, bearer, '/me/leave-requests', 'req-after', {
      policyId: ids.policy,
      startDate: future.start,
      endDate: future.start,
      amount: 1,
    });
    expect(reused.status()).toBe(201);
    const closed = await post(page, bearer, `/me/leave-requests/${ids.today}/withdrawal`, 'today', {
      reasonLocale: 'fr',
      reason: 'Trop tard ?',
    });
    expect(closed.status()).toBe(409);
    expect(((await closed.json()) as { code: string }).code).toBe(
      'LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED',
    );
    // The history shows no actor; the administrator's inbox and exceptions never regain it.
    const mine = await (
      await page.request.get(`${CORE_API}/me/leave-requests?limit=50`, {
        headers: { Authorization: bearer() },
      })
    ).text();
    for (const leak of ['withdrawnBy', 'decidedBy', 'decisionAuthority', '"subject']) {
      expect(mine.includes(leak), 'no actor identity in the employee response').toBe(false);
    }
    for (const path of ['/leave-approvals?limit=50', '/leave-routing-exceptions?limit=50']) {
      const queue = await adminPage.request.get(`${CORE_API}${path}`, {
        headers: { Authorization: admin() },
      });
      expect(queue.status()).toBe(200);
      expect(await queue.text()).not.toContain(ids.future);
    }
    await adminContext.close();
  });

  test('another employee can neither discover nor withdraw it', async ({ page }) => {
    test.setTimeout(120_000);
    const bearer = await signIn(page, [OTHER_EMAIL, PASSWORD], 'fr', undefined);
    const refused = await post(page, bearer, `/me/leave-requests/${ids.today}/withdrawal`, 'oth', {
      reasonLocale: 'fr',
      reason: 'Pas à moi.',
    });
    expect(refused.status()).toBe(404);
    const body = (await refused.json()) as { code: string; params: object };
    expect(body.code).toBe('LEAVE_REQUEST_NOT_FOUND');
    expect(body.params).toEqual({});
    const theirs = await (
      await page.request.get(`${CORE_API}/me/leave-requests?limit=50`, {
        headers: { Authorization: bearer() },
      })
    ).text();
    expect(theirs).not.toContain(ids.future);
    expect(theirs).not.toContain(ids.today);
    await primaryNav(page).getByRole('link', { name: 'Mes congés', exact: true }).click();
    await expect(page.getByTestId('history-empty')).toBeVisible();
    await expectAccessible(page);
    await clean(page);
  });
});

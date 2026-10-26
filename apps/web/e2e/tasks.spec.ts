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

// MVP-080 (Issue #167): exactly two journeys. (1) An English tenant administrator assigns a task
// to an employee found by number, sees it listed, and later (a fresh page load) opens it and
// cancels it with a closed reason after an explicit confirmation. (2) The French employee sees
// another assigned task under « À faire », starts it, then completes it with a note after an
// explicit confirmation, and sees it under « Terminées ou annulées ». Setup (API, the
// administrator's existing SSO session, one interactive sign-in for the employee): one invited and
// linked employee and the task of journey 2. Authorization, tenant isolation, races and rollbacks
// are covered by the Core tests, not here. axe, reflow at 320 px, 390 px and 640 px, browser
// storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `TE-${stamp}`;
const SITE = `TS-${stamp}`;
const DEPARTMENT = `TD-${stamp}`;
const NUMBER = `TSK-${stamp}-1`;
const FAMILY = `Mukendi${lower.replace(/[^a-z]/gu, '')}`;
const EMAIL = `e2e.taches.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Taches!`;
const ADMIN_TITLE = `Inventaire du dépôt ${stamp}`;
const ADMIN_TEXT = `Compter les palettes puis ranger (${stamp})`;
const EMPLOYEE_TITLE = `Vérifier les extincteurs ${stamp}`;
const NOTE = `Fait, trois extincteurs remplacés (${stamp})`;

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tk-${key}-${stamp}-0001` },
    data,
  });
}

test.describe.serial('MVP-080: assign and complete operational tasks', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(240_000);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Tâches Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Tâches ${stamp}`,
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
        'Idempotency-Key': `e2e-tk-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tk-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 1, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
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
    // The task of journey 2, assigned through the API.
    const due = new Date(Date.now() + 5 * 24 * 3600 * 1000).toISOString();
    const assigned = await post(page, bearer, '/tasks', 'task-2', {
      assigneeEmployeeId: employee,
      title: EMPLOYEE_TITLE,
      instructions: 'Contrôle mensuel',
      priority: 'URGENT',
      dueAt: due,
    });
    expect(assigned.status()).toBe(201);
    await page.close();
  });

  test('English administrator assigns a task and later cancels it', async ({ page }) => {
    test.setTimeout(150_000);
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Tasks', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Tasks');
    await expectAccessible(page);

    const form = page.getByTestId('task-form');
    await form.getByLabel('Find by employee number or name').fill(NUMBER);
    await form.getByRole('button', { name: 'Search', exact: true }).click();
    await form.getByRole('button', { name: new RegExp(NUMBER, 'u') }).click();
    await expect(page.getByTestId('task-assignee-selected')).toContainText(NUMBER);
    await form.getByLabel('Title', { exact: true }).fill(ADMIN_TITLE);
    await form.getByLabel('Instructions (optional)').fill(ADMIN_TEXT);
    await form.getByLabel('Priority').selectOption('HIGH');
    await form.getByRole('button', { name: 'Assign task' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Task assigned.');
    const row = page.getByTestId('admin-task').filter({ hasText: ADMIN_TITLE });
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('task-state')).toHaveText('Open');
    await expect(row.getByTestId('task-priority')).toHaveText('High priority');
    await expectAccessible(page);
    await noOverflow(page);

    // Later: a fresh page load, then cancel after an explicit confirmation.
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Tasks');
    const again = page.getByTestId('admin-task').filter({ hasText: ADMIN_TITLE });
    await again.getByRole('button', { name: /^View/u }).click();
    const detail = page.getByTestId('task-detail');
    await expect(detail.getByRole('heading', { name: ADMIN_TITLE })).toBeFocused();
    await expect(detail.getByTestId('task-history').getByRole('listitem')).toHaveCount(1);
    await detail.getByRole('button', { name: 'Cancel this task' }).click();
    const confirm = page.getByTestId('cancel-confirm');
    await confirm.getByRole('button', { name: 'Cancel the task' }).click();
    await expect(confirm.getByText('Choose a reason.')).toBeVisible();
    await confirm.getByLabel('Reason', { exact: true }).selectOption('NO_LONGER_REQUIRED');
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.getByRole('button', { name: 'Cancel the task' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Task cancelled.');
    await expect(detail.getByTestId('task-state')).toHaveText('Cancelled');
    await expect(detail.getByTestId('task-history').getByRole('listitem')).toHaveCount(2);
    await expect(detail.getByText('No longer required')).toBeVisible();
    await expect(detail.getByRole('button', { name: 'Cancel this task' })).toHaveCount(0);
    await expectAccessible(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${FAMILY}`, 'iu'));
    expect(page.url()).not.toContain(stamp);
  });

  test('French employee starts and completes an assigned task', async ({ page }) => {
    test.setTimeout(150_000);
    await signIn(page, [EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mes tâches', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mes tâches');
    const open = page.getByTestId('group-open');
    const item = open.getByTestId('my-task').filter({ hasText: EMPLOYEE_TITLE });
    await expect(item).toHaveCount(1);
    await expect(item.getByTestId('task-priority')).toHaveText('Urgente');
    // Journey 1's task (cancelled) is theirs too: two tasks, no one else's.
    await expect(page.getByTestId('my-task')).toHaveCount(2);
    await expectAccessible(page);
    await noOverflow(page);

    await item.getByRole('button', { name: 'Démarrer' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Tâche démarrée.');
    const running = page.getByTestId('group-inProgress').getByTestId('my-task');
    await expect(running.filter({ hasText: EMPLOYEE_TITLE })).toHaveCount(1);
    await expect(page.getByRole('heading', { name: /^En cours/u })).toBeFocused();

    await running.getByRole('button', { name: 'Terminer' }).click();
    const confirm = page.getByTestId('complete-confirm');
    await expect(confirm.getByLabel('Note de fin (facultatif)')).toBeFocused();
    await confirm.getByLabel('Note de fin (facultatif)').fill(NOTE);
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.getByRole('button', { name: 'Terminer la tâche' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Tâche terminée.');
    const closed = page.getByTestId('group-closed').getByTestId('my-task');
    await expect(closed.filter({ hasText: EMPLOYEE_TITLE })).toHaveCount(1);
    await expect(closed.getByTestId('task-state').first()).toHaveText(/Terminée|Annulée/u);
    await expect(closed.filter({ hasText: NOTE })).toHaveCount(1);
    await expect(page.getByRole('heading', { name: /^Terminées ou annulées/u })).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|${FAMILY}|extincteurs`, 'iu'));
    expect(page.url()).not.toContain(stamp);
  });
});

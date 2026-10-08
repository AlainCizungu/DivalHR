import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { enterCode, typeSecret } from './support';

// DEVELOPMENT-ONLY seed credentials from infrastructure/docker/keycloak (never valid elsewhere).
const USERNAME = process.env.E2E_USERNAME ?? 'dev-admin-a';
const PASSWORD = process.env.E2E_PASSWORD ?? 'dev-only-Admin-A-2026';
const CORE_API = process.env.E2E_CORE_API_URL ?? 'http://localhost:8080/api/v1';
const AI_SERVICE = process.env.E2E_AI_SERVICE_URL ?? 'http://localhost:8090/api/v1';

async function chooseLocale(page: Page, label: 'English' | 'Français') {
  await page.getByRole('button', { name: label, exact: true }).click();
}

async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

async function storedValues(page: Page): Promise<string> {
  return page.evaluate(() =>
    [window.localStorage, window.sessionStorage]
      .flatMap((storage) =>
        Array.from({ length: storage.length }, (_, i) => {
          const key = storage.key(i) ?? '';
          return `${key}=${storage.getItem(key) ?? ''}`;
        }),
      )
      .join('\n'),
  );
}

test('status page calls both public status endpoints through configured URLs', async ({ page }) => {
  const calls: string[] = [];
  page.on('request', (request) => {
    if (request.url().includes('/system/status')) calls.push(request.url());
  });
  await page.goto('/status');
  await chooseLocale(page, 'English');
  await expect(page.getByTestId('status-core-api')).toHaveAttribute('data-state', 'up');
  await expect(page.getByTestId('status-ai-service')).toHaveAttribute('data-state', 'up');
  expect(calls).toContain(`${CORE_API}/system/status`);
  expect(calls).toContain(`${AI_SERVICE}/system/status`);
  await expectAccessible(page);

  await chooseLocale(page, 'Français');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('État du système');
  await expect(page.getByTestId('status-core-api')).toContainText('Opérationnel');
  await expectAccessible(page);
});

test(
  'user signs in with PKCE, sees the verified tenant, and tokens stay out of storage',
  { tag: '@identity' },
  async ({ page }) => {
    await page.goto('/');
    await chooseLocale(page, 'English');
    await page.getByRole('main').getByRole('button', { name: 'Sign in' }).click();

    await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
    expect(new URL(page.url()).searchParams.get('code_challenge_method')).toBe('S256');
    await page.locator('#username').fill(USERNAME);
    await typeSecret(page, '#password', PASSWORD);
    await page.locator('#kc-login').click();
    await enterCode(page, USERNAME);

    await page.waitForURL(
      (url) => url.origin === new URL(page.url()).origin && url.pathname === '/',
    );
    await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
    await expect(page.getByTestId('session-roles')).toHaveText('Organization administrator');

    const stored = await storedValues(page);
    expect(stored).not.toMatch(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\./); // no JWTs
    expect(stored).not.toContain('access_token');
    expect(stored).not.toContain('refresh_token');

    // UI-001: the signed-in home and the account menu (sign-out moved into the menu).
    await chooseLocale(page, 'Français');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      'Administration de l’organisation',
    );
    await expect(page.getByTestId('session-roles')).toHaveText('Administrateur de l’organisation');
    await page.getByRole('button', { name: /Menu du compte/ }).click();
    await expect(page.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
    await expectAccessible(page);
    await page.keyboard.press('Escape');

    await chooseLocale(page, 'English');
    await page.getByRole('button', { name: /Account menu/ }).click();
    await expect(page.getByRole('button', { name: 'Sign out' })).toBeVisible();
    await expectAccessible(page);
  },
);

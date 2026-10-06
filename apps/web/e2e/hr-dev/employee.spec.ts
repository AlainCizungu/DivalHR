import { expect, test } from '@playwright/test';
import { authorize, getJsonWithToken, ORIGIN, type TempUser } from './support';

/**
 * OPS-001 section 11: smoke test with a real synthetic employee of the test environment (created
 * through the normal invitation flow, never seeded). Live only: it is skipped unless the operator
 * supplies HR_DEV_EMPLOYEE_USER and HR_DEV_EMPLOYEE_PASSWORD for this run.
 */

const username = process.env.HR_DEV_EMPLOYEE_USER ?? '';
const password = process.env.HR_DEV_EMPLOYEE_PASSWORD ?? '';
test.skip(!username || !password, 'HR_DEV_EMPLOYEE_USER and HR_DEV_EMPLOYEE_PASSWORD not supplied');

const employee: TempUser = { id: '', username, password };

for (const locale of [
  {
    language: 'English',
    signIn: 'Sign in',
    home: 'My space',
    menu: /Account menu/,
    signOut: 'Sign out',
  },
  {
    language: 'Français',
    signIn: 'Se connecter',
    home: 'Mon espace',
    menu: /Menu du compte/,
    signOut: 'Se déconnecter',
  },
] as const) {
  test(`employee signs in and out through the landing page (${locale.language})`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/');
    await page.getByRole('button', { name: locale.language, exact: true }).click();
    await page
      .getByRole('banner')
      .getByRole('button', { name: locale.signIn, exact: true })
      .click();
    await page.waitForURL(/\/identity\/realms\/divalhr-test\/protocol\/openid-connect\/auth/);
    await page.locator('#username').fill(username);
    await page.locator('#password').evaluate((input, value) => {
      (input as HTMLInputElement).value = value;
    }, password);
    await page.locator('#kc-login').click();
    await page.waitForURL((url) => url.origin === ORIGIN && url.pathname === '/');
    await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(locale.home);
    await page.getByRole('button', { name: locale.menu }).click();
    await page.getByRole('button', { name: locale.signOut }).click();
    await page.waitForURL((url) => url.pathname === '/' && !url.search.includes('code='));
    await expect(page.getByTestId('landing')).toBeVisible();
  });
}

test('the employee token is accepted by Core and refused for administration', async ({ page }) => {
  await page.goto('/');
  const { code, verifier } = await authorize(page, employee);
  const token = await page.evaluate(
    async ([url, body]) => {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(body).toString(),
      });
      return ((await response.json()) as { access_token?: string }).access_token ?? '';
    },
    [
      `${ORIGIN}/identity/realms/divalhr-test/protocol/openid-connect/token`,
      {
        grant_type: 'authorization_code',
        client_id: 'divalhr-web',
        code,
        code_verifier: verifier,
        redirect_uri: `${ORIGIN}/auth/callback`,
      },
    ] as const,
  );
  expect(token).not.toBe('');
  const session = await getJsonWithToken(page, '/api/v1/session', token);
  expect(session.status).toBe(200);
  expect(session.json.roles).toEqual(['employee']);
  const invitations = await getJsonWithToken(page, '/api/v1/invitations', token);
  expect(invitations.status).toBe(403);
  expect(invitations.json.code).toBe('ACCESS_DENIED');
  const anonymous = await getJsonWithToken(page, '/api/v1/session', '');
  expect(anonymous.status).toBe(401);
});

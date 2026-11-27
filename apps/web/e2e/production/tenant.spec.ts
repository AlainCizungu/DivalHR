import { expect, test } from '@playwright/test';
import { APP_URL, requireOrigins, SIGN_IN_PATH } from './support';

/**
 * LAUNCH-002 journey 2 (bilingual synthetic tenant): the ONE synthetic acceptance tenant's employee
 * signs in through the real production realm from the application origin, sees the home page in
 * English and in French, and signs out. Skipped unless the operator supplies the synthetic account
 * for this run (PROD_ACCEPTANCE_USER / PROD_ACCEPTANCE_PASSWORD): no seed account exists, and none is
 * ever created by this suite. The password never appears in a step argument.
 */
const username = process.env.PROD_ACCEPTANCE_USER ?? '';
const password = process.env.PROD_ACCEPTANCE_PASSWORD ?? '';
test.skip(!username || !password, 'the synthetic acceptance account was not supplied');
test.beforeAll(() => {
  requireOrigins();
});

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
  test(`the synthetic employee signs in and out on the application origin (${locale.language})`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(`${APP_URL}/`);
    await page.getByRole('button', { name: locale.language, exact: true }).click();
    await page
      .getByRole('banner')
      .getByRole('button', { name: locale.signIn, exact: true })
      .click();
    await page.waitForURL((url) => url.pathname.startsWith(SIGN_IN_PATH));
    await page.locator('#username').fill(username);
    await page.locator('#password').evaluate((input, value) => {
      (input as HTMLInputElement).value = value;
    }, password);
    await page.locator('#kc-login').click();
    await page.waitForURL((url) => url.origin === new URL(APP_URL).origin && url.pathname === '/');
    await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(locale.home);
    await page.getByRole('button', { name: locale.menu }).click();
    await page.getByRole('button', { name: locale.signOut }).click();
    await page.waitForURL((url) => url.pathname === '/' && !url.search.includes('code='));
    await expect(page.getByTestId('landing')).toBeVisible();
  });
}

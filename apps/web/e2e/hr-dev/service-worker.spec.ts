import { expect, test } from '@playwright/test';

/**
 * OPS-001: every real browser installs the web app's service worker, which answers navigations
 * with the cached application shell. In the same-origin test environment the identity provider
 * lives under /identity/, so with the service worker in control the sign-in redirect must still
 * reach Keycloak, not the shell's "Page not found". The rest of this suite blocks service workers.
 */
test.use({ serviceWorkers: 'allow' });

test('with the service worker installed, Sign in still reaches the identity provider', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  const installed = await page.evaluate(() =>
    Promise.race([
      navigator.serviceWorker.ready.then(() => true),
      new Promise<boolean>((resolve) => {
        setTimeout(() => {
          resolve(false);
        }, 30_000);
      }),
    ]),
  );
  expect(installed, 'the service worker was installed').toBe(true);
  await page.reload();
  const controlled = await page.evaluate(() => navigator.serviceWorker.controller !== null);
  expect(controlled, 'the service worker controls the page').toBe(true);

  await page.getByRole('button', { name: 'English', exact: true }).click();
  await page.getByRole('banner').getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(/\/identity\/realms\/divalhr-test\/protocol\/openid-connect\/auth/);
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.locator('#kc-login')).toBeVisible();
});

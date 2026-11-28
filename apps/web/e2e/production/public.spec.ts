import { expect, test, type Page } from '@playwright/test';
import { expectAccessible } from '../support';
import { APP_URL, PUBLIC_URL, REALM, requireOrigins, SIGN_IN_PATH, WWW_URL } from './support';

/**
 * LAUNCH-002 acceptance journey 1 of exactly two (public and sign-in), with service workers ALLOWED:
 * the commercial site in English and French, canonical on its own origin, indexable, www redirecting
 * to it; a direct reload of a deep route; the service worker takes control on the first load and
 * reload (the controller exists); with it in control the Sign in action still reaches the production
 * realm on the application origin, and the identity and AI server routes and the API are not
 * answered with the cached application shell. Synthetic and read-only: nothing is submitted.
 * Origin and redirect rules beyond this live in unit and policy tests.
 */
const COPY = {
  en: { language: 'English', signIn: 'Sign in' },
  fr: { language: 'Français', signIn: 'Se connecter' },
} as const;

/** Installs the worker (first load), reloads, and asserts that it now controls the page. */
async function expectControlled(page: Page, label: string) {
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
  expect(installed, `${label}: the service worker was installed`).toBe(true);
  await page.reload();
  const controlled = await page.evaluate(() => navigator.serviceWorker.controller !== null);
  expect(controlled, `${label}: the service worker controls the page`).toBe(true);
}

test('public site in English and French, service worker in control, sign-in and server routes excluded', async ({
  page,
  request,
}) => {
  requireOrigins();
  await page.setViewportSize({ width: 1440, height: 900 });

  await test.step('commercial site on its own origin, in both languages', async () => {
    const response = await page.goto(`${PUBLIC_URL}/`);
    expect(response?.status()).toBe(200);
    // The commercial site is meant to be found: no blanket noindex, unlike every test environment.
    expect(response?.headers()['x-robots-tag'] ?? '').not.toMatch(/noindex/i);
    for (const locale of ['en', 'fr'] as const) {
      await page.getByRole('button', { name: COPY[locale].language, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(
        page.getByRole('banner').getByRole('button', { name: COPY[locale].signIn }),
      ).toBeVisible();
      await expect(page.getByTestId('environment-badge')).toHaveCount(0);
      const canonical = await page.locator('link[rel="canonical"]').getAttribute('href');
      expect(new URL(canonical ?? '').origin).toBe(new URL(PUBLIC_URL).origin);
      await page.waitForLoadState('networkidle');
      await page.evaluate(() => {
        window.scrollTo(0, 0);
      });
      await expectAccessible(page);
    }
  });

  await test.step('www redirects to the commercial origin', async () => {
    const redirect = await request.get(`${WWW_URL}/legal/privacy`, { maxRedirects: 0 });
    expect([301, 308]).toContain(redirect.status());
    expect(redirect.headers()['location']).toBe(`${PUBLIC_URL}/legal/privacy`);
  });

  await test.step('service worker: first load, reload, controller present; direct reload of a deep route', async () => {
    await expectControlled(page, 'public origin');
    const deep = await page.goto(`${PUBLIC_URL}/legal/privacy`);
    expect(deep?.status()).toBe(200);
    await page.reload();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    expect(await page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true);
  });

  await test.step('Sign in with the worker in control reaches the production realm (both languages)', async () => {
    for (const locale of ['en', 'fr'] as const) {
      await page.goto(`${PUBLIC_URL}/`);
      await page.getByRole('button', { name: COPY[locale].language, exact: true }).click();
      await page
        .getByRole('banner')
        .getByRole('button', { name: COPY[locale].signIn, exact: true })
        .click();
      await page.waitForURL((url) => url.pathname.startsWith(SIGN_IN_PATH));
      expect(new URL(page.url()).origin).toBe(new URL(APP_URL).origin);
      await expect(page.locator('#kc-form-login')).toBeVisible();
    }
  });

  await test.step('application origin: worker in control, server routes never answered by the shell', async () => {
    await page.goto(`${APP_URL}/`);
    await expectControlled(page, 'application origin');
    for (const path of [
      `/identity/realms/${REALM}/.well-known/openid-configuration`,
      '/api/v1/me',
      '/ai/',
    ]) {
      const response = await page.goto(`${APP_URL}${path}`);
      expect(response, path).not.toBeNull();
      const body = await page.content();
      expect(body, `${path} is not the application shell`).not.toContain('id="root"');
    }
    const issuer = await page.goto(
      `${APP_URL}/identity/realms/${REALM}/.well-known/openid-configuration`,
    );
    expect(issuer?.status()).toBe(200);
    expect(await issuer?.text()).toContain('"issuer"');
  });

  await test.step('a non-commercial path on the public origin lands on the application origin', async () => {
    await page.goto(`${PUBLIC_URL}/partners/apply`);
    await page.waitForURL((url) => url.origin === new URL(APP_URL).origin);
  });
});

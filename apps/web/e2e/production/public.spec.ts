import { expect, test } from '@playwright/test';
import { expectAccessible } from '../support';
import { APP_URL, PUBLIC_URL, requireOrigins, SIGN_IN_PATH, WWW_URL } from './support';

/**
 * LAUNCH-002 journey 1 (public and sign-in): the commercial site on divalhr.com in English and
 * French, indexable and without a test badge, with the canonical link on its own origin; the sign-in
 * action leaves for the application origin and the production realm's sign-in page; a path that is
 * not commercial on the public origin lands on the application origin; www redirects to the
 * commercial origin. Synthetic and read-only: nothing is submitted.
 */
test.beforeAll(() => {
  requireOrigins();
});

const COPY = {
  en: { language: 'English', signIn: 'Sign in' },
  fr: { language: 'Français', signIn: 'Se connecter' },
} as const;

for (const locale of ['en', 'fr'] as const) {
  test(`commercial site in ${locale}: production page, canonical origin, sign-in leaves for the application`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const response = await page.goto(`${PUBLIC_URL}/`);
    expect(response?.status()).toBe(200);
    // The commercial site is meant to be found: no blanket noindex, unlike every test environment.
    expect(response?.headers()['x-robots-tag'] ?? '').not.toMatch(/noindex/i);
    await page.getByRole('button', { name: COPY[locale].language, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
    await expect(page.getByTestId('environment-badge')).toHaveCount(0);
    const canonical = await page.locator('link[rel="canonical"]').getAttribute('href');
    expect(new URL(canonical ?? '').origin).toBe(new URL(PUBLIC_URL).origin);
    await page.waitForLoadState('networkidle');
    await page.evaluate(() => {
      window.scrollTo(0, 0);
    });
    await expectAccessible(page);

    await page
      .getByRole('banner')
      .getByRole('button', { name: COPY[locale].signIn, exact: true })
      .click();
    await page.waitForURL((url) => url.pathname.startsWith(SIGN_IN_PATH));
    expect(new URL(page.url()).origin).toBe(new URL(APP_URL).origin);
    await expect(page.locator('#kc-form-login')).toBeVisible();
  });
}

test('a non-commercial path on the public origin lands on the application origin', async ({
  page,
}) => {
  await page.goto(`${PUBLIC_URL}/partners/apply`);
  await page.waitForURL((url) => url.origin === new URL(APP_URL).origin);
});

test('www redirects to the commercial origin', async ({ request }) => {
  test.skip(!WWW_URL, 'PROD_WWW_URL not supplied');
  const response = await request.get(`${WWW_URL}/legal/privacy`, { maxRedirects: 0 });
  expect([301, 308]).toContain(response.status());
  expect(response.headers()['location']).toBe(`${PUBLIC_URL}/legal/privacy`);
});

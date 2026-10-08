import { expect, test, type Page } from '@playwright/test';
import { enterCode, expectAccessible, typeSecret, USERS } from './support';

/**
 * UI-002 (Issue #63): the public landing page in a real browser. Both languages and themes,
 * reflow at 320 px and 200 % zoom, the disclosure menu by keyboard, same-origin assets only, no
 * storage beyond preferences, no authentication instructions or invented figures, and the
 * unchanged sign-in, callback, sign-out and deep-link behaviour.
 */

const COPY = {
  en: {
    language: 'English',
    h1: 'Run your people. Power your operations.',
    menu: 'Menu',
    sections: 'Page sections',
    signIn: 'Sign in',
    heroSignIn: 'Sign in to DivalHR',
    integrations: 'Integrations',
  },
  fr: {
    language: 'Français',
    h1: 'Gérez vos talents. Pilotez vos opérations.',
    menu: 'Menu',
    sections: 'Sections de la page',
    signIn: 'Se connecter',
    heroSignIn: 'Se connecter à DivalHR',
    integrations: 'Intégrations',
  },
} as const;

const FORBIDDEN =
  /authenticator|authentificat|\bOTP\b|TOTP|one-time|code d’|seed|dev-only|password|mot de passe/iu;
const ALLOWED_NUMBERS = new Set(['2026', '20022', '365', '30']);

/** Records every request host; the landing page must stay on the application's origin. */
function trackHosts(page: Page) {
  const hosts = new Set<string>();
  page.on('request', (request) => hosts.add(new URL(request.url()).host));
  return hosts;
}

async function openLanding(page: Page, locale: 'en' | 'fr') {
  await page.goto('/');
  await page.getByRole('button', { name: COPY[locale].language, exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(COPY[locale].h1);
  await expect(page.getByTestId('landing-integrations')).toBeVisible();
}

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

for (const colorScheme of ['light', 'dark'] as const) {
  test.describe(`anonymous visitors, ${colorScheme}`, () => {
    test.use({ colorScheme, viewport: { width: 1440, height: 900 } });

    for (const locale of ['en', 'fr'] as const) {
      test(`see the landing page in ${locale}, served from the application only`, async ({
        page,
        baseURL,
      }) => {
        const hosts = trackHosts(page);
        await openLanding(page, locale);
        await expect(page.locator('html')).toHaveAttribute('lang', locale);
        await expect(page.locator('html')).toHaveAttribute('data-theme', colorScheme);
        await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
        await expect(page.getByTestId('environment-badge')).toBeVisible();

        // Every logo loads from the application's own origin.
        const logos = page.getByTestId('landing-integrations').locator('img');
        await expect(logos).toHaveCount(8);
        for (const logo of await logos.all()) {
          await logo.scrollIntoViewIfNeeded();
          await expect
            .poll(() => logo.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
            .toBeGreaterThan(0);
          expect(new URL(await logo.evaluate((img: HTMLImageElement) => img.src)).origin).toBe(
            new URL(baseURL ?? '').origin,
          );
        }
        await page.waitForLoadState('networkidle');
        expect([...hosts]).toEqual([new URL(baseURL ?? '').host]);

        // UI2-3, UI2-4: no authentication instructions, secrets or invented figures.
        const text = await page.locator('body').innerText();
        expect(text).not.toMatch(FORBIDDEN);
        const numbers = text.match(/\d+/gu) ?? [];
        expect(numbers.filter((number) => !ALLOWED_NUMBERS.has(number))).toEqual([]);

        // Only preferences are stored; nothing personal.
        const stored = await page.evaluate(() => [
          ...Object.keys(window.localStorage),
          ...Object.keys(window.sessionStorage),
        ]);
        expect(stored.every((key) => /^divalhr\.(locale|theme|sidebar)$/u.test(key))).toBe(true);

        await expectAccessible(page);
      });
    }
  });
}

for (const [label, viewport] of [
  ['320 px', { width: 320, height: 720 }],
  ['200 % zoom of a 1280 px window', { width: 640, height: 400 }],
] as const) {
  for (const locale of ['en', 'fr'] as const) {
    test(`no horizontal scrolling at ${label}, ${locale}, and the menu works by keyboard`, async ({
      page,
    }) => {
      await page.setViewportSize(viewport);
      await openLanding(page, locale);
      await expectNoHorizontalScroll(page);
      await expectAccessible(page);

      const button = page.getByRole('button', { name: COPY[locale].menu, exact: true });
      await button.focus();
      await page.keyboard.press('Enter');
      await expect(button).toHaveAttribute('aria-expanded', 'true');
      const menu = page.getByTestId('landing-menu');
      await expect(menu.getByRole('button', { name: COPY[locale].signIn })).toBeVisible();
      await page.keyboard.press('Tab');
      await expect(menu.getByRole('link').first()).toBeFocused();
      await expectNoHorizontalScroll(page);
      await expectAccessible(page);
      await page.keyboard.press('Escape');
      await expect(button).toHaveAttribute('aria-expanded', 'false');
      await expect(button).toBeFocused();

      await button.click();
      await menu.getByRole('link', { name: COPY[locale].integrations }).click();
      await expect(button).toHaveAttribute('aria-expanded', 'false');
      await expect(page).toHaveURL(/#integrations$/u);
    });
  }
}

test.describe('sign-in from the landing page (unchanged flow)', { tag: '@identity' }, () => {
  test.use({ viewport: { width: 1440, height: 900 } });

  test('the header Sign in returns an employee to the role home; sign-out returns to the landing page', async ({
    page,
  }) => {
    await openLanding(page, 'en');
    await page.getByRole('banner').getByRole('button', { name: 'Sign in', exact: true }).click();
    await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
    expect(new URL(page.url()).searchParams.get('code_challenge_method')).toBe('S256');
    await page.locator('#username').fill(USERS.employeeA[0]);
    await typeSecret(page, '#password', USERS.employeeA[1]);
    await page.locator('#kc-login').click();
    await page.waitForURL((url) => url.pathname === '/');
    await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
    await expect(page.getByTestId('landing')).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: 'Main navigation' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My space');

    await page.getByRole('button', { name: /Account menu/ }).click();
    await page.getByRole('button', { name: 'Sign out' }).click();
    await page.waitForURL((url) => url.pathname === '/' && !url.search.includes('code='));
    await expect(page.getByTestId('landing')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(COPY.en.h1);
  });

  test('the hero Sign in takes an organization administrator through the same flow, in French', async ({
    page,
  }) => {
    await openLanding(page, 'fr');
    await page.getByRole('button', { name: COPY.fr.heroSignIn }).click();
    await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
    expect(new URL(page.url()).searchParams.get('ui_locales')).toBe('fr');
    await page.locator('#username').fill(USERS.adminA[0]);
    await typeSecret(page, '#password', USERS.adminA[1]);
    await page.locator('#kc-login').click();
    await enterCode(page, USERS.adminA[0]);
    await page.waitForURL((url) => url.pathname === '/');
    await expect(page.getByTestId('session-roles')).toHaveText('Administrateur de l’organisation');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(
      'Administration de l’organisation',
    );
    await expect(page.getByTestId('landing')).toHaveCount(0);
  });

  test('a protected deep link still asks for sign-in in the public frame, not the landing page', async ({
    page,
  }) => {
    await page.goto('/admin/people');
    await expect(page.getByTestId('landing')).toHaveCount(0);
    await expect(
      page.getByRole('banner').getByRole('button', { name: /Sign in|Se connecter/ }),
    ).toBeVisible();
  });
});

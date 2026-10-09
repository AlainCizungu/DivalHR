import { expect, test, type Page } from '@playwright/test';
import {
  expectAccessible,
  freshCode,
  signIn,
  TOTP_SEEDS,
  typeCode,
  typeSecret,
  USERS,
} from './support';

/**
 * UI-001 (Issue #53): the product shell in a real browser. Role-aware navigation, deep links and
 * history, keyboard use, the native dialog drawer, reflow at 320 px and 200 % zoom, reduced
 * motion, storage, and Axe in English and French across layouts and themes.
 */

const nav = (page: Page, name = 'Main navigation') => page.getByRole('navigation', { name });

async function navLabels(page: Page, name?: string) {
  return nav(page, name).locator('.nav-link__label').allTextContents();
}

async function chooseLocale(page: Page, label: 'English' | 'Français') {
  await page.getByRole('button', { name: label, exact: true }).click();
}

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  expect(overflow).toBeLessThanOrEqual(0);
}

async function chooseTheme(page: Page, menu: RegExp, option: string) {
  await page.getByRole('button', { name: menu }).click();
  await page.getByRole('button', { name: option, exact: true }).click();
  await page.keyboard.press('Escape');
}

test.describe('desktop', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('an organization administrator sees exactly their destinations, and history works', async ({
    page,
  }) => {
    await signIn(page, USERS.adminA, 'en');
    expect(await navLabels(page)).toEqual([
      'Home',
      'Employees',
      'Import employees',
      'Leave policies',
      'Leave approvals',
      'Organizational structure',
      'Contract templates',
      'Contract expirations',
      'Users and invitations',
      'Access review',
    ]);
    await expect(nav(page).getByRole('link', { name: 'System status' })).toHaveCount(0);
    await expect(page.getByTestId('environment-badge')).toContainText('Development environment');
    await expectAccessible(page);

    await nav(page).getByRole('link', { name: 'Employees', exact: true }).click();
    await expect(page).toHaveURL(/\/admin\/people$/);
    await expect(nav(page).getByRole('link', { name: 'Employees', exact: true })).toHaveAttribute(
      'aria-current',
      'page',
    );
    await expectAccessible(page);
    await nav(page).getByRole('link', { name: 'Contract templates' }).click();
    await expect(page).toHaveURL(/\/admin\/contract-templates$/);
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText(
      'Contract templates',
    );
    await page.goBack();
    await expect(page).toHaveURL(/\/admin\/people$/);
    await page.goForward();
    await expect(page).toHaveURL(/\/admin\/contract-templates$/);

    // Keyboard: collapse the sidebar, then the account menu closes on Escape and gives focus back.
    await page.getByRole('button', { name: 'Collapse navigation' }).focus();
    await page.keyboard.press('Enter');
    const expand = page.getByRole('button', { name: 'Expand navigation' });
    await expect(expand).toBeFocused();
    await expect(expand).toHaveAttribute('aria-expanded', 'false');
    await expect(nav(page).getByRole('link', { name: 'Access review' })).toBeVisible();
    await expectAccessible(page);
    const menu = page.getByRole('button', { name: /Account menu/ });
    await menu.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('session-roles')).toBeVisible();
    await expectAccessible(page);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('session-roles')).toBeHidden();
    await expect(menu).toBeFocused();

    // Each module page inside the shell, in French and dark mode.
    await chooseLocale(page, 'Français');
    await chooseTheme(page, /Menu du compte/, 'Sombre');
    for (const name of [
      'Accueil',
      'Employés',
      'Importer des employés',
      'Politiques de congé',
      'Structure organisationnelle',
      'Modèles de contrat',
      'Utilisateurs et invitations',
      'Revue des accès',
    ]) {
      await nav(page, 'Navigation principale').getByRole('link', { name, exact: true }).click();
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expectAccessible(page);
    }
    await chooseTheme(page, /Menu du compte/, 'Système');

    // Only the collapse preference joins the existing language and theme preferences.
    const keys = await page.evaluate(() => Object.keys(window.localStorage).sort());
    expect(keys).toEqual(['divalhr.locale', 'divalhr.sidebar', 'divalhr.theme']);
  });

  test('an employee and a platform administrator see only their destinations', async ({
    page,
    browser,
  }) => {
    await signIn(page, USERS.employeeA, 'fr');
    expect(await navLabels(page, 'Navigation principale')).toEqual([
      'Accueil',
      'Mes contrats',
      'Mes congés',
      'Approbations de congé',
    ]);
    await expect(page.getByTestId('roadmap')).toContainText('À venir');
    await expect(page.getByTestId('roadmap').locator('a, button')).toHaveCount(0);
    await expectAccessible(page);
    await chooseLocale(page, 'English');
    await expectAccessible(page);

    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    const platform = await context.newPage();
    await signIn(platform, USERS.platformAdmin, 'en');
    expect(await navLabels(platform)).toEqual([
      'Home',
      'Create organization',
      'First administrator',
    ]);
    await expect(platform.getByTestId('home-card-status')).toBeVisible();
    await expectAccessible(platform);
    await context.close();
  });

  test(
    'a deep link opened before sign-in returns to the same page, never showing other roles',
    { tag: '@identity' },
    async ({ page }) => {
      await page.goto('/admin/people');
      await chooseLocale(page, 'English');
      await expect(nav(page)).toHaveCount(0);
      await expectAccessible(page);

      // Hold the session response so the signed-in shell is seen while the session loads.
      let release: () => void = () => undefined;
      const held = new Promise<void>((resolve) => {
        release = resolve;
      });
      await page.route('**/api/v1/session', async (route) => {
        await held;
        await route.continue();
      });
      await page.getByRole('banner').getByRole('button', { name: 'Sign in' }).click();
      await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
      await page.locator('#username').fill(USERS.adminA[0]);
      await typeSecret(page, '#password', USERS.adminA[1]);
      await page.locator('#kc-login').click();
      // The deep link, not Home, is where the sign-in returns (so the shared helper's wait for "/"
      // does not apply here).
      await page.locator('#otp').waitFor();
      await typeCode(
        page,
        '#otp',
        await freshCode(page, USERS.adminA[0], TOTP_SEEDS[USERS.adminA[0]] ?? ''),
      );
      await page.locator('#kc-login').click();
      await page.waitForURL((url) => url.pathname === '/admin/people');
      await expect(nav(page).locator('.nav-link__label')).toHaveText(['Home']);
      release();
      await expect(nav(page).locator('.nav-link__label')).toHaveCount(10);
      await expect(page.getByTestId('directory-table')).toBeVisible();
    },
  );

  test('anonymous visitors get the landing page (UI-002), accessible in both languages', async ({
    page,
  }) => {
    await page.goto('/');
    await page.keyboard.press('Tab');
    await expect(page.locator('.skip-link')).toBeFocused();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('main')).toBeFocused();
    for (const label of ['English', 'Français'] as const) {
      await chooseLocale(page, label);
      // The only button in the landing content is the hero's Sign in (UI-002).
      await expect(page.getByRole('main').getByRole('button')).toHaveCount(1);
      await expectAccessible(page);
    }
  });
});

test.describe('mobile', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the drawer is a modal dialog that keeps and returns focus', async ({ page }) => {
    await signIn(page, USERS.employeeA, 'en');
    await expect(nav(page)).toBeHidden();
    const menuButton = page.getByRole('button', { name: 'Open navigation' });
    await menuButton.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Main navigation' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('link', { name: 'Home', exact: true })).toBeFocused();
    await expectAccessible(page);
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press('Tab');
      expect(await dialog.evaluate((node) => node.contains(document.activeElement))).toBe(true);
    }
    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(menuButton).toBeFocused();

    await menuButton.click();
    await dialog.getByRole('link', { name: 'My contracts' }).click();
    await expect(page).toHaveURL(/\/me\/contracts$/);
    await expect(dialog).toBeHidden();
    await expect(menuButton).toBeFocused();

    await chooseLocale(page, 'Français');
    await page.getByRole('button', { name: 'Ouvrir le menu' }).click();
    await expectAccessible(page);
    await page.keyboard.press('Escape');
    await expectNoHorizontalScroll(page);
  });

  test('reduced motion turns the drawer animation off', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await signIn(page, USERS.employeeA, 'en');
    await page.getByRole('button', { name: 'Open navigation' }).click();
    const animation = await page
      .getByRole('dialog')
      .evaluate((node) => getComputedStyle(node).animationName);
    expect(animation).toBe('none');
  });
});

test.describe('reflow', () => {
  for (const [label, viewport] of [
    ['320 px', { width: 320, height: 720 }],
    ['200 % zoom of a 1280 px window', { width: 640, height: 400 }],
    ['tablet', { width: 834, height: 1112 }],
  ] as const) {
    test(`no horizontal scrolling at ${label}, in French`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await page.goto('/');
      await chooseLocale(page, 'Français');
      await expectNoHorizontalScroll(page);
      await signIn(page, USERS.employeeA, 'fr');
      await expectNoHorizontalScroll(page);
      await expect(page.getByTestId('environment-badge')).toBeVisible();
      await expectAccessible(page);
    });
  }
});

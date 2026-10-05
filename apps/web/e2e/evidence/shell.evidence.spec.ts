import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { signIn, USERS } from '../support';

/**
 * UI-001 PR evidence: screenshots of the shell in both languages, both themes and every layout.
 * Runs only with EVIDENCE=1 and writes outside the working tree (default .git/divalhr-verify/
 * evidence), so nothing is committed. Global Playwright screenshots stay off (MVP-011); these
 * pages contain no one-time codes and only development seed data.
 */
test.skip(!process.env.EVIDENCE, 'evidence screenshots run only with EVIDENCE=1');

const OUT =
  process.env.EVIDENCE_DIR ??
  fileURLToPath(new URL('../../../../.git/divalhr-verify/evidence/', import.meta.url));

const VIEWPORTS = {
  desktop: { width: 1440, height: 1000 },
  tablet: { width: 834, height: 1112 },
  mobile: { width: 390, height: 844 },
  narrow: { width: 320, height: 720 },
} as const;

async function shot(page: Page, name: string, fullPage = false) {
  mkdirSync(OUT, { recursive: true });
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: join(OUT, `${name}.png`), fullPage, animations: 'disabled' });
}

async function locale(page: Page, which: 'en' | 'fr') {
  await page.getByRole('button', { name: which === 'fr' ? 'Français' : 'English' }).click();
}

async function theme(page: Page, which: 'light' | 'dark', lang: 'en' | 'fr') {
  const menu = page.getByRole('button', {
    name: lang === 'fr' ? /Menu du compte/ : /Account menu/,
  });
  await menu.click();
  const label = { en: { light: 'Light', dark: 'Dark' }, fr: { light: 'Clair', dark: 'Sombre' } };
  await page.getByRole('button', { name: label[lang][which], exact: true }).click();
  await page.keyboard.press('Escape');
}

test('organization administrator', async ({ page }) => {
  test.setTimeout(240_000);
  await page.setViewportSize(VIEWPORTS.desktop);
  await signIn(page, USERS.adminA, 'en');
  await theme(page, 'light', 'en');
  await shot(page, 'after-01-tenant-admin-home-desktop-light-en', true);
  await locale(page, 'fr');
  await shot(page, 'after-02-tenant-admin-home-desktop-light-fr', true);
  await page.getByRole('button', { name: /Menu du compte/ }).click();
  await shot(page, 'after-07-user-menu-light-fr');
  await page.keyboard.press('Escape');
  await locale(page, 'en');
  await theme(page, 'dark', 'en');
  await page.getByRole('button', { name: 'Collapse navigation' }).click();
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Employees', exact: true })
    .hover();
  await shot(page, 'after-03-tenant-admin-home-desktop-collapsed-dark-en', true);
  await page.getByRole('button', { name: 'Expand navigation' }).click();
  await theme(page, 'light', 'en');
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Contract templates' })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await shot(page, 'after-08-contract-templates-in-shell-light-en', true);
  // Tokens live in memory only: navigate inside the app, never reload.
  await page
    .getByRole('navigation', { name: 'Main navigation' })
    .getByRole('link', { name: 'Home', exact: true })
    .click();
  await locale(page, 'fr');
  await page.setViewportSize(VIEWPORTS.tablet);
  await shot(page, 'after-09-tablet-rail-tenant-admin-light-fr', true);
  await page.setViewportSize(VIEWPORTS.mobile);
  await shot(page, 'after-10-mobile-home-tenant-admin-light-fr', true);
  await page.getByRole('button', { name: 'Ouvrir le menu' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await shot(page, 'after-11-mobile-drawer-tenant-admin-light-fr');
});

test('employee', async ({ page }) => {
  test.setTimeout(180_000);
  await page.setViewportSize(VIEWPORTS.desktop);
  await signIn(page, USERS.employeeA, 'fr');
  await theme(page, 'light', 'fr');
  await shot(page, 'after-04-employee-home-desktop-light-fr', true);
  await locale(page, 'en');
  await theme(page, 'dark', 'en');
  await shot(page, 'after-05-employee-home-desktop-dark-en', true);
  await page.setViewportSize(VIEWPORTS.mobile);
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await expect(page.getByRole('dialog')).toBeVisible();
  await shot(page, 'after-12-mobile-drawer-employee-dark-en');
  await page.keyboard.press('Escape');
  await theme(page, 'light', 'en');
  await locale(page, 'fr');
  await page.setViewportSize(VIEWPORTS.narrow);
  await shot(page, 'after-13-320px-employee-home-light-fr', true);
});

test('platform administrator', async ({ page }) => {
  test.setTimeout(180_000);
  await page.setViewportSize(VIEWPORTS.desktop);
  await signIn(page, USERS.platformAdmin, 'en');
  await theme(page, 'light', 'en');
  await shot(page, 'after-06-platform-admin-home-desktop-light-en', true);
});

test('anonymous visitor', async ({ page }) => {
  await page.setViewportSize(VIEWPORTS.desktop);
  await page.goto('/');
  await locale(page, 'fr');
  await shot(page, 'after-00-public-welcome-light-fr', true);
  await page.setViewportSize(VIEWPORTS.narrow);
  await shot(page, 'after-00-public-welcome-320px-fr', true);
});

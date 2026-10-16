import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { signIn, USERS } from '../support';

/**
 * UI-003 PR evidence: the four required screenshots (English landing desktop, French landing
 * mobile, administrator home, employee home). Runs only with EVIDENCE=1 and writes outside the
 * working tree (default .git/divalhr-verify/evidence), so nothing is committed. The pages hold no
 * one-time codes and only development seed data.
 */
test.skip(!process.env.EVIDENCE, 'evidence screenshots run only with EVIDENCE=1');

const OUT =
  process.env.EVIDENCE_DIR ??
  fileURLToPath(new URL('../../../../.git/divalhr-verify/evidence/', import.meta.url));

async function shot(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true });
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: join(OUT, `${name}.png`), fullPage: true, animations: 'disabled' });
}

test('landing page, English desktop and French mobile', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/');
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await expect(page.getByTestId('landing-modules')).toBeVisible();
  await shot(page, 'ui003-1-landing-desktop-en');
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole('button', { name: 'FR', exact: true })
    .or(page.getByRole('button', { name: 'Français', exact: true }))
    .first()
    .click();
  await expect(page.getByTestId('landing-today')).toContainText('Horaires de travail');
  await shot(page, 'ui003-2-landing-mobile-fr');
});

test('organization administrator home', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, USERS.adminA, 'en');
  await expect(page.getByTestId('attention-count')).toBeVisible();
  await shot(page, 'ui003-3-admin-home-en');
});

test('employee home', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, USERS.employeeA, 'en');
  await expect(page.getByTestId('home-my-work')).toBeVisible();
  await shot(page, 'ui003-4-employee-home-en');
});

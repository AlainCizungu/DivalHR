import { expect, test, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * UI-002 PR evidence: the public landing page in both languages, both themes and every layout,
 * plus the module and integration sections on their own. Runs only with EVIDENCE=1 and writes
 * outside the working tree (default .git/divalhr-verify/evidence). Anonymous pages only: no
 * credentials, codes or personal data appear.
 */
test.skip(!process.env.EVIDENCE, 'evidence screenshots run only with EVIDENCE=1');
test.use({ reducedMotion: 'reduce' });

const OUT =
  process.env.EVIDENCE_DIR ??
  fileURLToPath(new URL('../../../../.git/divalhr-verify/evidence/', import.meta.url));

const VIEWPORTS = {
  desktop: { width: 1440, height: 900 },
  tablet: { width: 834, height: 1112 },
  mobile: { width: 390, height: 844 },
  narrow: { width: 320, height: 720 },
} as const;

async function open(page: Page, locale: 'en' | 'fr') {
  await page.goto('/');
  await page
    .getByRole('button', { name: locale === 'fr' ? 'Français' : 'English', exact: true })
    .click();
  await expect(page.getByTestId('landing-integrations')).toBeVisible();
  // Load every lazily loaded logo before capturing.
  for (const logo of await page.getByTestId('landing-integrations').locator('img').all()) {
    await logo.scrollIntoViewIfNeeded();
    await expect
      .poll(() => logo.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
      .toBeGreaterThan(0);
  }
  await page.evaluate(() => {
    window.scrollTo({ top: 0, behavior: 'instant' });
  });
  await page.waitForLoadState('networkidle');
}

async function shot(page: Page, name: string, fullPage = true) {
  mkdirSync(OUT, { recursive: true });
  await page.screenshot({ path: join(OUT, `${name}.png`), fullPage, animations: 'disabled' });
}

async function sections(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true });
  // The sticky header would otherwise be captured over each section.
  await page.locator('.landing-header').evaluate((header: HTMLElement) => {
    header.style.position = 'static';
  });
  for (const id of ['landing-modules', 'landing-integrations'] as const) {
    await page
      .getByTestId(id)
      .screenshot({ path: join(OUT, `${name}-${id.replace('landing-', '')}.png`) });
  }
}

for (const colorScheme of ['light', 'dark'] as const) {
  test.describe(colorScheme, () => {
    test.use({ colorScheme });
    for (const locale of ['en', 'fr'] as const) {
      test(`landing ${locale} ${colorScheme}`, async ({ page }) => {
        test.setTimeout(120_000);
        await page.setViewportSize(VIEWPORTS.desktop);
        await open(page, locale);
        await shot(page, `ui002-desktop-${locale}-${colorScheme}`);
        await sections(page, `ui002-desktop-${locale}-${colorScheme}`);
        if (colorScheme === 'dark') return;
        await page.setViewportSize(VIEWPORTS.mobile);
        await open(page, locale);
        await shot(page, `ui002-mobile-${locale}`);
        await sections(page, `ui002-mobile-${locale}`);
        await page.evaluate(() => {
          window.scrollTo({ top: 0, behavior: 'instant' });
        });
        await page.getByRole('button', { name: 'Menu', exact: true }).click();
        await shot(page, `ui002-mobile-${locale}-menu`, false);
      });
    }
  });
}

test('landing tablet and 320 px, French', async ({ page }) => {
  await page.setViewportSize(VIEWPORTS.tablet);
  await open(page, 'fr');
  await shot(page, 'ui002-tablet-fr', false);
  await page.setViewportSize(VIEWPORTS.narrow);
  await open(page, 'fr');
  await shot(page, 'ui002-narrow-fr', false);
});

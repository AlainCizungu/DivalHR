import { expect, test } from '@playwright/test';
import { expectAccessible } from '../support';
import { ORIGIN, trackHosts } from './support';

/** OPS-001 section 11: the public commercial page of the test environment (LAUNCH-004), English and French. */

const COPY = {
  en: {
    language: 'English',
    h1: 'Run your people operations with clarity—from employee records to payroll preparation.',
    badge: 'Test environment',
  },
  fr: {
    language: 'Français',
    h1: 'Gérez vos opérations RH avec clarté — des dossiers employés à la préparation de la paie.',
    badge: 'Environnement de test',
  },
} as const;

for (const locale of ['en', 'fr'] as const) {
  test(`landing page in ${locale}: test badge, same-origin assets, accessible`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const hosts = trackHosts(page);
    const response = await page.goto('/');
    expect(response?.headers()['x-robots-tag']).toBe('noindex, nofollow, noarchive');
    await page.getByRole('button', { name: COPY[locale].language, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(COPY[locale].h1);
    await expect(page.getByTestId('environment-badge')).toContainText(COPY[locale].badge);
    const logos = page.getByTestId('commercial-integrations').locator('img');
    await expect(logos).toHaveCount(7);
    for (const logo of await logos.all()) {
      await logo.scrollIntoViewIfNeeded();
      await expect
        .poll(() => logo.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
        .toBeGreaterThan(0);
    }
    await page.waitForLoadState('networkidle');
    expect([...hosts]).toEqual([new URL(ORIGIN).host]);
    // The checks run at the top of the page: the sticky header would otherwise partially cover
    // whatever the logo loop scrolled under it, which axe reports as an obscured target.
    await page.evaluate(() => {
      window.scrollTo(0, 0);
    });
    await expectAccessible(page);
  });
}

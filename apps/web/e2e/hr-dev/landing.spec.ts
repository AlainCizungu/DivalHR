import { expect, test } from '@playwright/test';
import { expectAccessible } from '../support';
import { ORIGIN, trackHosts } from './support';

/** OPS-001 section 11: the public landing page of the test environment, English and French. */

const COPY = {
  en: {
    language: 'English',
    h1: 'Run your people. Power your operations.',
    badge: 'Test environment',
  },
  fr: {
    language: 'Français',
    h1: 'Gérez vos talents. Pilotez vos opérations.',
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
    const logos = page.getByTestId('landing-integrations').locator('img');
    await expect(logos).toHaveCount(8);
    for (const logo of await logos.all()) {
      await logo.scrollIntoViewIfNeeded();
      await expect
        .poll(() => logo.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
        .toBeGreaterThan(0);
    }
    await page.waitForLoadState('networkidle');
    expect([...hosts]).toEqual([new URL(ORIGIN).host]);
    await expectAccessible(page);
  });
}

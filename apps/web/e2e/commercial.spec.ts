import { expect, test, type Page } from '@playwright/test';
import { expectAccessible } from './support';

/**
 * LAUNCH-004 (Issue #228): exactly two browser journeys for the public commercial page.
 *  1. An English prospective employer: pilot positioning, the sections, a real product screenshot,
 *     the payroll limitation, the demo call to action (a plain business e-mail link: DivalHR stores
 *     nothing), keyboard use, accessibility and reflow.
 *  2. A French prospective partner: salary advances and the partner network with the lending
 *     disclaimer, the partner application and back without losing the language, the motion reel
 *     under reduced motion, accessibility and reflow.
 */

const SECTION_IDS = [
  'product',
  'solutions',
  'payroll',
  'services',
  'partners',
  'security',
  'pilot',
];

async function expectNoHorizontalScroll(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
  );
  const culprits =
    overflow > 0
      ? await page.evaluate(() =>
          [...document.querySelectorAll('body *')]
            .filter((el) => el.getBoundingClientRect().right > document.documentElement.clientWidth)
            .slice(0, 5)
            .map((el) => `${el.tagName.toLowerCase()}.${String(el.className).slice(0, 60)}`),
        )
      : [];
  expect(
    overflow,
    `horizontal overflow; first offenders: ${culprits.join(' | ')}`,
  ).toBeLessThanOrEqual(0);
}

/** WCAG 1.4.12: the text-spacing overrides must not cut off or overlap content. */
async function expectTextSpacingSafe(page: Page) {
  await page.evaluate(() => {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(
      '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }',
    );
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  });
  await expectNoHorizontalScroll(page);
}

async function expectReflow(page: Page) {
  for (const width of [320, 390, 640]) {
    await page.setViewportSize({ width, height: 800 });
    await expectNoHorizontalScroll(page);
  }
}

/** Every image in the viewport region loads: lazy images are scrolled into view first. */
async function expectImagesLoaded(page: Page, scope: string) {
  const images = page.locator(`${scope} img`);
  const count = await images.count();
  expect(count).toBeGreaterThan(0);
  for (const image of await images.all()) {
    await image.scrollIntoViewIfNeeded();
    await expect
      .poll(() => image.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth))
      .toBeGreaterThan(0);
  }
}

test('English prospective employer: pilot positioning, real product, payroll limits, demo request', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const requests: string[] = [];
  page.on('request', (request) =>
    requests.push(`${request.method()} ${new URL(request.url()).pathname}`),
  );

  await page.goto('/');
  await page.getByRole('button', { name: 'English', exact: true }).first().click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Run your people operations with clarity—from employee records to payroll preparation.',
  );
  await expect(page).toHaveTitle(/DivalHR/u);
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');

  // Pilot positioning: the badge sits next to the calls to action, and nothing claims general availability.
  const badge = page.getByTestId('commercial-pilot-badge');
  await expect(badge).toHaveText('Controlled pilot');
  await expect(page.getByRole('link', { name: 'Request a demo' }).first()).toBeVisible();
  await expect(page.getByRole('link', { name: 'Join the pilot' }).first()).toBeVisible();
  await expect(page.locator('main')).not.toContainText(
    /generally available|production-certified/iu,
  );

  // Keyboard: the skip link comes first, focus is visible, and every section is reachable from the navigation.
  // A fresh load puts the keyboard starting point at the top of the document; the language is kept.
  await page.reload();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to main content' })).toBeFocused();
  const outline = await page.evaluate(() => {
    const style = getComputedStyle(document.activeElement as Element);
    return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) };
  });
  expect(outline.style).not.toBe('none');
  expect(outline.width).toBeGreaterThanOrEqual(2);

  const nav = page.getByRole('navigation', { name: 'Page sections' }).first();
  await expect(nav.getByRole('link')).toHaveText([
    'Product',
    'Solutions',
    'Payroll',
    'Employee services',
    'Partner network',
    'Security',
    'Pilot program',
  ]);
  for (const id of SECTION_IDS) {
    await nav.locator(`a[href="/#${id}"]`).click();
    await expect(page.locator(`#${id}`)).toBeInViewport();
  }

  // A real product screenshot, loaded from the application's own origin, with meaningful alternative text.
  const gallery = page.getByTestId('commercial-gallery');
  await expect(gallery).toBeVisible();
  await expectImagesLoaded(page, '[data-testid="commercial-gallery"]');
  for (const image of await gallery.locator('img').all()) {
    expect((await image.getAttribute('alt'))?.length ?? 0).toBeGreaterThan(20);
  }
  const firstShot = gallery.locator('img').first();
  await expect(firstShot).toHaveAttribute('src', /^\/commercial\//u);

  // Payroll: strong but exact; the limitation is stated and statuses use customer labels.
  await page.locator('#payroll').scrollIntoViewIfNeeded();
  const payroll = page.getByTestId('commercial-payroll');
  await expect(payroll.getByTestId('commercial-payroll-disclosure')).toHaveText(
    'Payment execution, statutory configuration and external accounting connections require customer-specific review and approved providers. DivalHR does not mark employees paid merely because a file was generated.',
  );
  await expect(payroll).toContainText('Available after configuration');
  await expect(payroll).not.toContainText(/pays every|fully compliant|instant/iu);

  // Request a demo: a plain business e-mail link. No form, no field, no request carrying prospect data.
  const demo = page.getByRole('link', { name: 'Request a demo' }).first();
  await expect(demo).toHaveAttribute(
    'href',
    'mailto:contact@dival.ai?subject=DivalHR%20demonstration%20request',
  );
  await expect(page.locator('main form, main input, main textarea')).toHaveCount(0);
  expect(requests.filter((request) => !request.startsWith('GET '))).toEqual([]);
  expect(requests.some((request) => request.includes('/api/'))).toBe(false);
  const stored = await page.evaluate(() => ({
    local: Object.keys(localStorage),
    session: Object.keys(sessionStorage),
    cookies: document.cookie,
  }));
  expect(stored.cookies).toBe('');
  for (const key of [...stored.local, ...stored.session]) {
    expect(key).toMatch(/^divalhr[.:]|^oidc\.|locale|theme|pref/iu);
  }

  // Accessibility and reflow.
  await expectAccessible(page);
  await expectTextSpacingSafe(page);
  await expectReflow(page);
  await page.setViewportSize({ width: 390, height: 800 });
  await page.getByRole('button', { name: 'Menu' }).click();
  await expect(page.getByTestId('commercial-menu')).toBeVisible();
  await expectAccessible(page);
});

test('French prospective partner: partner-required wording, the application, the language kept, reduced motion', async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await page.goto('/');
  await page.getByRole('button', { name: 'Français', exact: true }).first().click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Gérez vos opérations RH avec clarté — des dossiers employés à la préparation de la paie.',
  );
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  await expect(page.getByTestId('commercial-pilot-badge')).toHaveText('Pilote contrôlé');

  // Salary advances: partner required, never lending by DivalHR.
  await page.locator('#services').scrollIntoViewIfNeeded();
  const wellness = page.getByTestId('commercial-wellness');
  await expect(wellness).toContainText('Partenaire approuvé requis');
  await expect(page.getByTestId('commercial-lending-disclosure')).toHaveText(
    'Disponible uniquement par l’intermédiaire de partenaires approuvés et après activation pour le client. DivalHR ne prend pas lui-même de décision de crédit, n’accorde pas de prêt, n’émet pas d’assurance et ne garantit aucune approbation.',
  );
  await expect(wellness).toContainText('DivalHR n’est pas le prêteur.');
  await expect(wellness).not.toContainText(/instantan|approbation garantie|prêt garanti/iu);

  // Partner network: the path, the disclaimer, and the integration groups with partner-dependent brands.
  await page.locator('#partners').scrollIntoViewIfNeeded();
  const partners = page.getByTestId('commercial-partners');
  await expect(partners.locator('ol.commercial-path li')).toHaveCount(7);
  await expect(page.getByTestId('commercial-partner-disclaimer')).toHaveText(
    'Une candidature ou une approbation ne crée ni connexion technique, ni caution commerciale, ni droit d’accès aux données des employés.',
  );
  const partnerGroup = partners.locator('[data-integration-group="partner"]');
  await expect(partnerGroup).toContainText('Dépend d’un partenaire');
  await expect(partnerGroup).toContainText('Partenaire approuvé requis');
  await expect(partners).not.toContainText(/connecté à|connected to/iu);

  // Reduced motion: the reel does not start by itself; the visitor can start and pause it.
  const reel = page.getByTestId('commercial-reel');
  await reel.scrollIntoViewIfNeeded();
  await expect(reel).toHaveAttribute('data-playing', 'false');
  await reel.getByRole('button', { name: 'Lire la séquence de présentation' }).click();
  await expect(reel).toHaveAttribute('data-playing', 'true');
  await reel.getByRole('button', { name: 'Mettre en pause la séquence de présentation' }).click();
  await expect(reel).toHaveAttribute('data-playing', 'false');
  await reel.getByText('Afficher la transcription').click();
  await expect(reel.locator('.commercial-transcript li')).toHaveCount(6);

  // The partner application, then back to the page without losing the language.
  await page.getByTestId('commercial-partner-cta').click();
  await expect(page).toHaveURL(/\/partners\/apply$/u);
  await expect(page.getByTestId('partner-apply-page')).toBeVisible();
  await page.goBack();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    'Gérez vos opérations RH avec clarté — des dossiers employés à la préparation de la paie.',
  );
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  await page.goto('/legal/privacy');
  await expect(page.getByTestId('commercial-legal-notice')).toContainText('Document de pilote');
  await page.getByRole('link', { name: 'Retour à la page d’accueil' }).click();
  await expect(page.getByTestId('commercial-pilot-badge')).toHaveText('Pilote contrôlé');

  // Accessibility and reflow.
  await expectAccessible(page);
  await expectTextSpacingSafe(page);
  await expectReflow(page);
});

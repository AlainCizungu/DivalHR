import { expect, test } from '@playwright/test';

/**
 * R229-2 (PR #229): the sticky commercial header must never hide the keyboard-focused partner link.
 * The general axe scan in the hr-dev landing test runs at the top of the page; this regression test
 * covers the position that scan skips. At four widths and in both languages it jumps to the partner
 * section, scrolls so the link sits under the header, reaches the link with the Tab key, and checks
 * that the link, its focus outline and at least 24 px of its target are clear of the header.
 */

const WIDTHS = [320, 390, 640, 1440] as const;
const LANGUAGES = [
  { code: 'en', button: 'English', link: 'Apply as a partner' },
  { code: 'fr', button: 'Français', link: 'Devenir partenaire' },
] as const;

for (const language of LANGUAGES) {
  for (const width of WIDTHS) {
    test(`R229-2 ${language.code} at ${width} px: the focused partner link is clear of the sticky header`, async ({
      page,
    }) => {
      // Scrolling is instant under reduced motion, so the positions below are deterministic.
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await page.setViewportSize({ width, height: 800 });
      await page.goto('/');
      await page.getByRole('button', { name: language.button, exact: true }).first().click();
      await expect(page.locator('html')).toHaveAttribute('lang', language.code);

      // Navigate to the partner section as the navigation links do.
      await page.evaluate(() => {
        window.location.hash = '#partners';
      });
      const section = page.locator('#partners');
      await expect(section).toBeVisible();
      await section.focus();

      // The section heading reached by the anchor is not under the header either.
      const heading = await page.evaluate(() => ({
        top:
          document.querySelector('#commercial-partners-title')?.getBoundingClientRect().top ?? -1,
        headerBottom:
          document.querySelector('.commercial-header')?.getBoundingClientRect().bottom ?? 0,
      }));
      expect(
        heading.top,
        'partner heading below the sticky header after the anchor jump',
      ).toBeGreaterThanOrEqual(heading.headerBottom - 0.5);

      const link = page.getByTestId('commercial-partner-cta');
      await expect(link).toHaveText(language.link);

      // Worst case: the link is 10 px from the top of the viewport, that is under the header.
      await link.evaluate((element) => {
        window.scrollTo(0, element.getBoundingClientRect().top + window.scrollY - 10);
      });
      for (let press = 0; press < 40; press += 1) {
        await page.keyboard.press('Tab');
        if (await link.evaluate((element) => element === document.activeElement)) break;
      }
      await expect(link).toBeFocused();

      const m = await link.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const header = document.querySelector('.commercial-header')?.getBoundingClientRect();
        const style = getComputedStyle(element);
        const hit = document.elementFromPoint(
          rect.left + rect.width / 2,
          rect.top + rect.height / 2,
        );
        return {
          top: rect.top,
          bottom: rect.bottom,
          left: rect.left,
          right: rect.right,
          headerBottom: header?.bottom ?? 0,
          viewportHeight: window.innerHeight,
          viewportWidth: window.innerWidth,
          outlineStyle: style.outlineStyle,
          ring: parseFloat(style.outlineWidth) + parseFloat(style.outlineOffset),
          hitIsLink: hit === element || element.contains(hit),
          focusVisible: element.matches(':focus-visible'),
        };
      });

      // 3. Focused by the keyboard, so the focus indicator is shown.
      expect(m.focusVisible).toBe(true);
      // 4. Visible: inside the viewport.
      await expect(link).toBeInViewport();
      // 5. At least 24 px of the target is unobscured, vertically and horizontally.
      expect(
        Math.min(m.bottom, m.viewportHeight) - Math.max(m.top, m.headerBottom),
        'unobscured target height',
      ).toBeGreaterThanOrEqual(24);
      expect(
        Math.min(m.right, m.viewportWidth) - Math.max(m.left, 0),
        'target width',
      ).toBeGreaterThanOrEqual(24);
      expect(m.hitIsLink, 'the link is the topmost element at its centre').toBe(true);
      // 6. It does not overlap the sticky header.
      expect(m.top, 'link top below the header bottom').toBeGreaterThanOrEqual(
        m.headerBottom - 0.5,
      );
      // 7. The focus outline is drawn and its top edge is not under the header.
      expect(m.outlineStyle).not.toBe('none');
      expect(m.top - m.ring, 'outline top below the header bottom').toBeGreaterThanOrEqual(
        m.headerBottom - 0.5,
      );
    });
  }
}

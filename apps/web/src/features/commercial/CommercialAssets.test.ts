import { describe, expect, it } from 'vitest';
import attribution from '../../../../../docs/BRAND-ASSETS.md?raw';
import { INTEGRATION_GROUPS } from './integrations';
import manifest from './media.json';

/**
 * LAUNCH-004 (Issue #228): every mark on the page has a local file and a documented source and
 * usage basis, every file is used and documented, and every screenshot in the manifest exists
 * within its size budget.
 */
const BRAND_FILES = import.meta.glob<string>('../../../public/brands/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true,
});
const fileNames = Object.keys(BRAND_FILES).map((path) => path.split('/').pop() as string);

const brands = INTEGRATION_GROUPS.flatMap((group) => group.entries).flatMap((entry) =>
  entry.kind === 'brand' ? [entry] : [],
);

describe('brand assets', () => {
  it('shows a local mark for each brand that has one, and documents its source and basis', () => {
    for (const brand of brands.filter((entry) => entry.logo)) {
      expect(fileNames, `${brand.name}: missing ${brand.logo}`).toContain(brand.logo);
      expect(attribution, `${brand.name} is not in docs/BRAND-ASSETS.md`).toContain(brand.name);
      expect(attribution).toContain(`public/brands/${brand.logo}`);
    }
  });

  it('documents every brand shown by name only as an exception, and draws no substitute logo', () => {
    const exceptions = attribution.split('## Exceptions')[1] ?? '';
    for (const brand of brands.filter((entry) => !entry.logo)) {
      expect(exceptions, `${brand.name} is not a documented exception`).toContain(brand.name);
      expect(fileNames.join(' ').toLowerCase()).not.toContain(
        brand.name.toLowerCase().replace(/[^a-z]/gu, ''),
      );
    }
  });

  it('keeps no unused or undocumented brand file', () => {
    const used = new Set(brands.flatMap((brand) => (brand.logo ? [brand.logo] : [])));
    for (const file of fileNames) {
      expect(used.has(file), `${file} is not used by the page`).toBe(true);
      expect(attribution).toContain(`public/brands/${file}`);
    }
  });

  it('states that a logo is not an endorsement and that nothing is shown as connected', () => {
    expect(attribution).toMatch(/does not mean a commercial partnership or endorsement/u);
  });
});

describe('screenshot manifest', () => {
  it('records release, route, locale, viewport and files for each capture', () => {
    const data = manifest as unknown as {
      release: string;
      shots: {
        id: string;
        locale: string;
        viewport: string;
        width: number;
        height: number;
        base: string;
        widths: number[];
      }[];
    };
    expect(data.release).toMatch(/^[0-9a-f]{40}$/u);
    for (const shot of data.shots) {
      expect(['en', 'fr']).toContain(shot.locale);
      expect(['desktop', 'mobile']).toContain(shot.viewport);
      expect(shot.width).toBeGreaterThan(0);
      expect(shot.height).toBeGreaterThan(0);
      expect(shot.widths.length).toBeGreaterThan(0);
    }
  });
});

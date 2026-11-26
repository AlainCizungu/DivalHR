import { resources } from '@divalhr/localization';
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { initI18n } from '../../i18n';
import { renderApp } from '../../test/renderApp';
import { ProductReel, REEL_SECONDS, SCENE_SECONDS } from './CommercialMedia';
import forbidden from './forbidden-claims.json';
import source from './launch-truth.json';
import { applyPageMeta, structuredData } from './metadata';

/**
 * LAUNCH-004 (Issue #228): component and route tests of the commercial page, in both languages.
 * The truth rules themselves (statuses, evidence, gaps, Markdown) are enforced by
 * scripts/ops/commercial-truth.test.mjs; here the rendered page must agree with that source.
 */
const STATUSES = [
  'PILOT_AVAILABLE',
  'CONFIGURATION_REQUIRED',
  'PARTNER_REQUIRED',
  'PLANNED',
] as const;
const CAPABILITY_IDS = source.capabilities.map((capability) => capability.id);
const STATUS_OF = new Map(
  source.capabilities.map((capability) => [capability.id, capability.status]),
);
const CLAIMS = forbidden.claims.map((pattern) => new RegExp(pattern, forbidden.flags));
const SYNTHETIC = new RegExp(forbidden.syntheticInternals, forbidden.flags);

const NAV = {
  en: [
    'Product',
    'Solutions',
    'Payroll',
    'Employee services',
    'Partner network',
    'Security',
    'Pilot program',
  ],
  fr: [
    'Produit',
    'Solutions',
    'Paie',
    'Services aux employés',
    'Réseau de partenaires',
    'Sécurité',
    'Programme pilote',
  ],
} as const;

async function renderPage(locale: 'en' | 'fr') {
  const view = await renderApp('/', locale);
  await screen.findByTestId('commercial-pilot-badge');
  return view;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('commercial page (%s)', (locale) => {
  const c = resources[locale].common.commercial;

  it('shows the fixed headline, the controlled-pilot badge and the positioning statement', async () => {
    await renderPage(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(c.hero.title);
    expect(screen.getByTestId('commercial-pilot-badge')).toHaveTextContent(c.badge.pilot);
    expect(c.badge.pilot).toBe(locale === 'en' ? 'Controlled pilot' : 'Pilote contrôlé');
    expect(screen.getByText(c.hero.positioning)).toBeInTheDocument();
    expect(document.documentElement.lang).toBe(locale);
  });

  it('has the public navigation, the persistent demo call to action and a working mobile menu', async () => {
    await renderPage(locale);
    const header = document.querySelector('header') as HTMLElement;
    const nav = within(header).getAllByRole('navigation', { name: c.nav.label })[0] as HTMLElement;
    expect(
      within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual([...NAV[locale]]);
    for (const link of within(nav).getAllByRole('link')) {
      expect(link.getAttribute('href')).toMatch(
        /^\/#(product|solutions|payroll|services|partners|security|pilot)$/u,
      );
    }
    const demo = within(header).getAllByRole('link', { name: c.cta.demo })[0] as HTMLAnchorElement;
    expect(demo.getAttribute('href')).toBe(
      `mailto:contact@dival.ai?subject=${encodeURIComponent(c.cta.demoSubject)}`,
    );
    expect(within(header).getAllByRole('button', { name: c.nav.signIn }).length).toBeGreaterThan(0);

    const user = userEvent.setup();
    const toggle = within(header).getByRole('button', { name: c.nav.menu });
    const menu = screen.getByTestId('commercial-menu');
    expect(menu).toHaveAttribute('hidden');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    expect(menu).not.toHaveAttribute('hidden');
    await user.keyboard('{Escape}');
    expect(menu).toHaveAttribute('hidden');
    expect(toggle).toHaveFocus();
  });

  it('labels every capability with its customer status, never the engineering code', async () => {
    const { container } = await renderPage(locale);
    const badges = container.querySelectorAll<HTMLElement>('[data-capability-badge]');
    expect(badges.length).toBeGreaterThan(40);
    for (const badge of badges) {
      const id = badge.dataset.capabilityBadge as string;
      const status = badge.dataset.status as (typeof STATUSES)[number];
      expect(CAPABILITY_IDS).toContain(id);
      expect(status).toBe(STATUS_OF.get(id));
      expect(badge).toHaveTextContent(c.status[status]);
    }
    expect(container.textContent).not.toMatch(
      /PILOT_AVAILABLE|CONFIGURATION_REQUIRED|PARTNER_REQUIRED|(?<![A-Za-zÀ-ÿ])PLANNED/u,
    );
    // The four labels are the architect's, and differ from each other.
    expect(new Set(STATUSES.map((status) => c.status[status])).size).toBe(4);
  });

  it('places every truth-file capability on the page and no capability outside it', async () => {
    const { container } = await renderPage(locale);
    const placed = new Set(
      [
        ...container.querySelectorAll<HTMLElement>('[data-capability], [data-capability-badge]'),
      ].map((node) => (node.dataset.capability ?? node.dataset.capabilityBadge) as string),
    );
    for (const id of placed) expect(CAPABILITY_IDS, `unknown capability ${id}`).toContain(id);
    for (const id of CAPABILITY_IDS)
      expect(placed.has(id), `${id} is missing from the page`).toBe(true);
  });

  it('shows the seven pillars, each with an outcome and a link to its section', async () => {
    const { container } = await renderPage(locale);
    const pillars = container.querySelectorAll<HTMLElement>('[data-pillar]');
    expect(pillars).toHaveLength(7);
    for (const pillar of pillars) {
      expect(within(pillar).getByRole('heading', { level: 3 })).toBeInTheDocument();
      expect(within(pillar).getAllByRole('listitem').length).toBeGreaterThanOrEqual(2);
      const target = within(pillar).getByRole('link').getAttribute('href') as string;
      expect(container.querySelector(target)).not.toBeNull();
    }
  });

  it('states the payroll limitation, the lending disclaimer and the partner disclaimer exactly', async () => {
    await renderPage(locale);
    expect(screen.getByTestId('commercial-payroll-disclosure')).toHaveTextContent(
      c.payroll.disclosure,
    );
    expect(c.payroll.disclosure).toContain(
      locale === 'en'
        ? 'does not mark employees paid merely because a file was generated'
        : 'ne marque pas un employé comme payé du simple fait qu’un fichier a été généré',
    );
    expect(screen.getByTestId('commercial-lending-disclosure')).toHaveTextContent(
      source.partnerDisclosure[locale],
    );
    expect(screen.getByTestId('commercial-partner-disclaimer')).toHaveTextContent(
      c.partners.disclaimer,
    );
    const wellness = screen.getByTestId('commercial-wellness');
    expect(
      within(wellness).getAllByText(source.statuses.PARTNER_REQUIRED[locale]).length,
    ).toBeGreaterThan(0);
    for (const key of [
      'notLender',
      'noGuarantee',
      'noHidden',
      'privateChoices',
      'availability',
    ] as const) {
      expect(wellness).toHaveTextContent(c.wellness.statements[key]);
    }
  });

  it('routes the partner call to action to the existing application and lists the seven-step path', async () => {
    await renderPage(locale);
    const cta = screen.getByTestId('commercial-partner-cta');
    expect(cta).toHaveAttribute('href', '/partners/apply');
    expect(cta).toHaveTextContent(c.cta.partner);
    const partners = screen.getByTestId('commercial-partners');
    expect(partners.querySelectorAll('ol.commercial-path li')).toHaveLength(7);
    expect(partners.querySelectorAll('ul.commercial-chips li')).toHaveLength(7);
  });

  it('never describes a bank, operator, insurer or logo as connected, and shows no figures or endorsements', async () => {
    const { container } = await renderPage(locale);
    const text = container.textContent;
    for (const claim of CLAIMS) expect(text, `forbidden claim ${claim}`).not.toMatch(claim);
    expect(text).not.toMatch(SYNTHETIC);
    expect(text).not.toMatch(/\d[\d\s,.]*\s?(%|\+)/u);
    const groups = container.querySelectorAll<HTMLElement>('[data-integration-group]');
    expect([...groups].map((group) => group.dataset.integrationGroup)).toEqual([
      'available',
      'designed',
      'partner',
      'planned',
    ]);
    const partnerGroup = container.querySelector(
      '[data-integration-group="partner"]',
    ) as HTMLElement;
    for (const brand of within(partnerGroup).getAllByTestId('commercial-integration-brand')) {
      expect(brand.getAttribute('data-capability')).toBe('real-payment-delivery');
    }
    expect(
      within(partnerGroup).getByText(source.statuses.PARTNER_REQUIRED[locale]),
    ).toBeInTheDocument();
    for (const logo of container.querySelectorAll('.commercial-integration__logo')) {
      expect(logo).toHaveAttribute('alt', '');
    }
    expect(text).toContain(c.integrations.logoNote);
  });

  it('describes the mobile web experience and the planned native apps accurately', async () => {
    await renderPage(locale);
    const mobile = screen.getByTestId('commercial-mobile');
    expect(mobile).toHaveTextContent(c.mobile.body);
    expect(c.mobile.body).toContain(
      locale === 'en'
        ? 'Native mobile applications are planned.'
        : 'Les applications mobiles natives sont prévues.',
    );
  });

  it('keeps the security copy free of certification badges and compliance claims', async () => {
    await renderPage(locale);
    const security = screen.getByTestId('commercial-security');
    expect(security).toHaveTextContent(c.security.intro);
    expect(security.querySelectorAll('li[data-capability]')).toHaveLength(9);
    expect(security.querySelector('img')).toBeNull();
  });

  it('explains the pilot, with demo and pilot calls to action that open the business contact only', async () => {
    await renderPage(locale);
    const pilot = screen.getByTestId('commercial-pilot');
    expect(pilot.querySelectorAll('ol li')).toHaveLength(6);
    expect(pilot).toHaveTextContent(c.pilot.contactNote);
    const links = within(pilot).getAllByRole('link');
    expect(links).toHaveLength(2);
    for (const link of links)
      expect(link.getAttribute('href')).toMatch(/^mailto:contact@dival\.ai\?subject=/u);
    expect(document.querySelector('main form, main input, main textarea')).toBeNull();
  });

  it('has a footer without a dead link, with the language switcher and the pilot-document note', async () => {
    await renderPage(locale);
    const footer = document.querySelector('footer') as HTMLElement;
    const hrefs = within(footer)
      .getAllByRole('link')
      .map((link) => link.getAttribute('href'));
    expect(hrefs).toEqual([
      '/#product',
      '/#pilot',
      '/partners/apply',
      '/#security',
      '/legal/privacy',
      '/legal/terms',
      'mailto:contact@dival.ai',
    ]);
    expect(within(footer).getByRole('button', { name: c.nav.signIn })).toBeInTheDocument();
    expect(within(footer).getAllByRole('button', { pressed: true })).toHaveLength(1);
    expect(footer).toHaveTextContent(c.footer.legalNote);
  });

  it('sets localized metadata, canonical URL and truthful structured data', async () => {
    await renderPage(locale);
    expect(document.title).toBe(c.meta.title);
    const meta = (selector: string) =>
      document.head.querySelector(selector)?.getAttribute('content');
    expect(meta('meta[name="description"]')).toBe(c.meta.description);
    expect(meta('meta[property="og:title"]')).toBe(c.meta.ogTitle);
    expect(meta('meta[property="og:description"]')).toBe(c.meta.ogDescription);
    expect(meta('meta[property="og:image:alt"]')).toBe(c.meta.ogImageAlt);
    expect(meta('meta[property="og:locale"]')).toBe(locale === 'en' ? 'en_US' : 'fr_FR');
    expect(document.head.querySelector('link[rel="canonical"]')).toHaveAttribute(
      'href',
      `${window.location.origin}/`,
    );
    const data = JSON.parse(
      document.head.querySelector('script#commercial-jsonld')?.textContent ?? '{}',
    ) as {
      '@graph': { '@type': string }[];
    };
    expect(data['@graph'].map((node) => node['@type'])).toEqual([
      'Organization',
      'SoftwareApplication',
    ]);
    expect(JSON.stringify(data)).not.toMatch(
      /aggregateRating|offers|review|price|award|telephone|address/u,
    );
  });

  it('has no accessibility violations that a DOM-only check can find', async () => {
    const { container } = await renderPage(locale);
    const results = await axe.run(container, {
      rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
    });
    expect(results.violations.map((violation) => violation.id)).toEqual([]);
    const headings = [...container.querySelectorAll('h1, h2, h3, h4')].map((h) =>
      Number(h.tagName.slice(1)),
    );
    expect(headings.filter((level) => level === 1)).toHaveLength(1);
    headings.forEach((level, index) => {
      if (index > 0) expect(level - (headings[index - 1] as number)).toBeLessThanOrEqual(1);
    });
  });
});

describe('language switch', () => {
  it('switches the whole page, the document language and the metadata without losing the route', async () => {
    await renderPage('en');
    const user = userEvent.setup();
    await user.click(screen.getAllByRole('button', { name: 'Français' })[0] as HTMLElement);
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      resources.fr.common.commercial.hero.title,
    );
    expect(document.documentElement.lang).toBe('fr');
    expect(document.title).toBe(resources.fr.common.commercial.meta.title);
    expect(window.location.pathname).toBe('/');
  });
});

describe.each(['en', 'fr'] as const)('pilot legal pages (%s)', (locale) => {
  it.each(['privacy', 'terms'] as const)(
    '%s is a labelled pilot document with a way back',
    async (kind) => {
      await renderApp(`/legal/${kind}`, locale);
      const c = resources[locale].common.commercial;
      expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
        c.legal[kind].title,
      );
      expect(screen.getByTestId('commercial-legal-notice')).toHaveTextContent(c.legal.pilotNotice);
      expect(screen.getByRole('link', { name: c.legal.back })).toHaveAttribute('href', '/');
    },
  );
});

describe('product reel', () => {
  it('lasts between 25 and 40 seconds, has captions and a transcript of every scene, in both languages', async () => {
    expect(REEL_SECONDS).toBeGreaterThanOrEqual(25);
    expect(REEL_SECONDS).toBeLessThanOrEqual(40);
    for (const locale of ['en', 'fr'] as const) {
      await initI18n(locale);
      const { unmount } = render(<ProductReel />);
      const c = resources[locale].common.commercial.media.reel;
      expect(screen.getByRole('button', { name: c.play })).toHaveAttribute('aria-pressed', 'false');
      expect(screen.getByText(c.transcriptToggle)).toBeInTheDocument();
      expect(document.querySelectorAll('.commercial-transcript li')).toHaveLength(6);
      expect(screen.getByTestId('commercial-reel-stage')).toHaveTextContent(c.scenes.brand);
      unmount();
    }
  });

  it('does not start by itself when the visitor prefers reduced motion, and the control still works', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('reduce'),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    }));
    const observe = vi.fn();
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        observe = observe;
        disconnect() {}
      },
    );
    await initI18n('en');
    render(<ProductReel />);
    expect(observe).not.toHaveBeenCalled();
    expect(screen.getByTestId('commercial-reel')).toHaveAttribute('data-playing', 'false');
    const user = userEvent.setup();
    await user.click(
      screen.getByRole('button', { name: resources.en.common.commercial.media.reel.play }),
    );
    expect(screen.getByTestId('commercial-reel')).toHaveAttribute('data-playing', 'true');
    await user.click(
      screen.getByRole('button', { name: resources.en.common.commercial.media.reel.pause }),
    );
    expect(screen.getByTestId('commercial-reel')).toHaveAttribute('data-playing', 'false');
  });

  it('advances one scene every few seconds while playing and pauses on demand', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      await initI18n('en');
      render(<ProductReel />);
      const c = resources.en.common.commercial.media.reel;
      act(() => {
        screen.getByRole('button', { name: c.play }).click();
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(SCENE_SECONDS * 1000);
      });
      expect(screen.getByTestId('commercial-reel-stage')).toHaveTextContent(c.scenes.home);
      act(() => {
        screen.getByRole('button', { name: c.pause }).click();
      });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(SCENE_SECONDS * 3000);
      });
      expect(screen.getByTestId('commercial-reel-stage')).toHaveTextContent(c.scenes.home);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('metadata helpers', () => {
  it('emits only an organization and a software application, never ratings, prices or reviews', () => {
    const data = structuredData('https://example.test', 'x');
    expect(JSON.stringify(data)).not.toMatch(/aggregateRating|offers|review|price|award/u);
    const restore = applyPageMeta({
      title: 't',
      description: 'd',
      ogTitle: 'o',
      ogDescription: 'od',
      ogImageAlt: 'a',
      locale: 'en',
    });
    expect(document.title).toBe('t');
    restore();
  });
});

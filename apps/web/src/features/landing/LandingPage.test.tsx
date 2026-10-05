import { resources } from '@divalhr/localization';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import type { UserManager } from 'oidc-client-ts';
import { describe, expect, it, vi } from 'vitest';
import { App } from '../../app/App';
import { initI18n } from '../../i18n';
import { fakeUserManager, testConfig } from '../../test/renderApp';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { HomePage } from '../home/HomePage';
import attribution from '../../../../../docs/BRAND-ASSETS.md?raw';
import {
  AVAILABLE_MODULES,
  CONTACT_EMAIL,
  INTEGRATION_GROUPS,
  MODULES,
  VISION_MODULES,
} from './catalogue';

/** The brand files as served from public/brands, and this feature's own source files. */
const BRAND_FILES = import.meta.glob<string>('../../../public/brands/*.svg', {
  query: '?raw',
  import: 'default',
  eager: true,
});
const SOURCE_FILES = import.meta.glob<string>(
  ['./catalogue.ts', './LandingContent.tsx', './LandingFrame.tsx', './landing.css'],
  { query: '?raw', import: 'default', eager: true },
);

async function renderLanding(locale: 'en' | 'fr' = 'en') {
  const signinRedirect = vi.fn(() => Promise.resolve());
  const manager = Object.assign(fakeUserManager(), { signinRedirect }) as UserManager;
  window.history.pushState({}, '', '/');
  await initI18n(locale);
  const view = render(<App config={testConfig} userManager={manager} />);
  await screen.findByTestId('landing-modules');
  return { ...view, signinRedirect };
}

/** UI2-1: the twelve vision modules approved by the owner, in their approved order. */
const APPROVED_VISION = [
  'employees',
  'leave',
  'payroll',
  'loans',
  'benefits',
  'performance',
  'documents',
  'analytics',
  'workflow',
  'ai',
  'integrations',
  'mobile',
];

/** UI2-3, UI2-4: what the public page must never contain. */
const FORBIDDEN =
  /authenticator|authentificat|\bOTP\b|TOTP|one-time|code d’|seed|dev-only|password|mot de passe|example\.test/iu;
/** The only figures the page may show: the copyright year, the ISO standard, a product name, a sample question. */
const ALLOWED_NUMBERS = new Set(['2026', '20022', '365', '30']);

describe('catalogue (UI2-1, UI2-2)', () => {
  it('keeps the twelve vision modules in order, with statuses reviewed against the product', () => {
    expect(VISION_MODULES.map((module) => module.id)).toEqual(APPROVED_VISION);
    expect(AVAILABLE_MODULES.map((module) => module.id)).toEqual([
      'employees',
      'organization',
      'contracts',
      'access',
    ]);
    expect(new Set(MODULES.map((module) => module.id)).size).toBe(MODULES.length);
  });

  it('lists the twenty approved integrations, each logo served from the application', () => {
    const names = INTEGRATION_GROUPS.flatMap((group) =>
      group.entries.map((entry) => (entry.kind === 'brand' ? entry.name : entry.labelKey)),
    );
    expect(names).toEqual([
      'M-PESA',
      'Airtel Money',
      'Orange Money',
      'bankTransfer',
      'isoFiles',
      'Sage',
      'QuickBooks',
      'Odoo',
      'SAP',
      'Oracle',
      'Microsoft Teams',
      'WhatsApp Business',
      'Slack',
      'Microsoft 365',
      'Google Workspace',
      'Microsoft Entra ID',
      'Keycloak',
      'restApi',
      'webhooks',
      'sso',
    ]);
    for (const group of INTEGRATION_GROUPS) {
      for (const entry of group.entries) {
        if (entry.kind !== 'brand') continue;
        // Every brand is recorded: a local asset with its source, or a documented exception.
        expect(attribution).toContain(`| ${entry.name} |`);
        if (!entry.logo) continue;
        const svg = BRAND_FILES[`../../../public/brands/${entry.logo}`];
        expect(svg).toBeDefined();
        expect(svg).not.toMatch(/https?:\/\/(?!www\.w3\.org\/2000\/svg)/u);
        expect(svg).not.toMatch(/<script|<image|href=/iu);
        expect(attribution).toContain(`public/brands/${entry.logo}`);
      }
    }
  });
});

describe.each(['en', 'fr'] as const)('landing page (%s)', (locale) => {
  const strings = resources[locale].common;

  it('is the anonymous home, in one language, with no raw keys', async () => {
    const { container } = await renderLanding(locale);
    expect(document.documentElement.lang).toBe(locale);
    expect(document.title).toBe(strings.landing.documentTitle);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      `${strings.landing.hero.line1} ${strings.landing.hero.line2}`,
    );
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
    expect(screen.getByTestId('environment-badge')).toBeInTheDocument();
    // The signed-in application navigation never appears on the landing page.
    expect(screen.queryByRole('navigation', { name: strings.nav.primary })).toBeNull();
    const sections = screen.getByRole('navigation', { name: strings.landing.sectionsLabel });
    expect(
      within(sections)
        .getAllByRole('link')
        .map((link) => link.getAttribute('href')),
    ).toEqual(['#platform', '#payroll', '#integrations', '#why']);
    for (const id of ['platform', 'payroll', 'integrations', 'why']) {
      expect(container.querySelector(`#${id}`)).not.toBeNull();
    }
    expect(container.textContent).not.toMatch(/\blanding\.[a-z]/u);
  });

  it('labels every vision module Available now or Coming later from the catalogue', async () => {
    await renderLanding(locale);
    const modules = screen.getByTestId('landing-modules');
    const cards = modules.querySelectorAll('li[data-status]');
    expect([...cards].map((card) => card.getAttribute('data-module'))).toEqual(APPROVED_VISION);
    for (const card of cards) {
      const id = card.getAttribute('data-module') ?? '';
      const status = MODULES.find((module) => module.id === id)?.status;
      const html = card as HTMLElement;
      expect(html).toHaveTextContent(strings.landing.modules.items[id as 'employees'].title);
      const available = within(html).queryAllByText(strings.landing.modules.available);
      const later = within(html).queryAllByText(strings.landing.modules.later);
      expect(available.length + later.length).toBe(1);
      expect(available.length).toBe(status === 'available' ? 1 : 0);
    }
    const today = screen.getByTestId('landing-today');
    expect(
      [...today.querySelectorAll('li')].map((item) => item.getAttribute('data-module')),
    ).toEqual(AVAILABLE_MODULES.map((module) => module.id));
  });

  it('shows integrations as planned, with local logos announced once and the disclaimer', async () => {
    const { container } = await renderLanding(locale);
    const section = screen.getByTestId('landing-integrations');
    expect(within(section).getAllByText(strings.landing.integrations.planned)).toHaveLength(4);
    expect(within(section).queryByText(strings.landing.modules.available)).toBeNull();
    const logos = [...section.querySelectorAll('img')];
    expect(logos.length).toBe(
      INTEGRATION_GROUPS.flatMap((group) => group.entries).filter(
        (entry) => entry.kind === 'brand' && entry.logo,
      ).length,
    );
    for (const logo of logos) {
      expect(logo).toHaveAttribute('alt', '');
      expect(logo.getAttribute('src')).toMatch(/^\/brands\/[a-z]+\.svg$/u);
    }
    for (const name of ['M-PESA', 'Keycloak', 'Microsoft Teams', 'Google Workspace']) {
      expect(within(section).getByText(name)).toBeInTheDocument();
    }
    expect(screen.getByTestId('landing-integrations-note')).toHaveTextContent(
      strings.landing.integrations.note,
    );
    expect(screen.getByTestId('landing-payout-note')).toHaveTextContent(
      strings.landing.payroll.note,
    );
    expect(container.querySelector('[style]')).toBeNull();
  });

  it('starts the existing sign-in flow and offers the approved demo address', async () => {
    const { signinRedirect } = await renderLanding(locale);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: strings.landing.hero.signIn }));
    expect(signinRedirect).toHaveBeenLastCalledWith(
      expect.objectContaining({ state: { returnTo: '/' } }),
    );
    const demo = screen
      .getAllByRole('link', { name: new RegExp(strings.landing.requestDemo, 'u') })
      .map((link) => link.getAttribute('href'));
    expect(new Set(demo)).toEqual(
      new Set([
        `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(strings.landing.demoSubject)}`,
      ]),
    );
    expect(screen.getByRole('link', { name: CONTACT_EMAIL })).toBeInTheDocument();
  });

  it('never explains authentication and shows no fabricated figures (UI2-3, UI2-4)', async () => {
    const { container } = await renderLanding(locale);
    const text = container.textContent;
    expect(text).not.toMatch(FORBIDDEN);
    const numbers = text.match(/\d+/gu) ?? [];
    expect(numbers.filter((number) => !ALLOWED_NUMBERS.has(number))).toEqual([]);
    expect(text).not.toMatch(/%/u);
    // The illustration is announced once, by its caption.
    const preview = screen.getByTestId('landing-preview');
    expect(preview.querySelector('[aria-hidden="true"]')).not.toBeNull();
    expect(screen.getByRole('figure')).toHaveAccessibleName(strings.landing.preview.caption);
  });

  it('has no detectable accessibility violations', async () => {
    const { container } = await renderLanding(locale);
    const result = await axe.run(container, {
      rules: { 'color-contrast': { enabled: false } }, // jsdom cannot compute colours
    });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

describe('landing menu', () => {
  it('is a disclosure: Escape closes it and returns focus, a link closes it', async () => {
    await renderLanding('en');
    const user = userEvent.setup();
    const button = screen.getByRole('button', { name: resources.en.common.landing.menu });
    const menu = screen.getByTestId('landing-menu');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(menu).not.toBeVisible();
    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(menu).toBeVisible();
    await user.keyboard('{Escape}');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
    await user.click(button);
    await user.click(
      within(menu).getByRole('link', { name: resources.en.common.landing.nav.integrations }),
    );
    expect(button).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('signed-in visitors keep the application (D1)', () => {
  it('shows the role home at / and no landing content', async () => {
    await renderWithSession(<HomePage />, sessionWithRoles(['employee']));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
      resources.en.common.home.employee.title,
    );
    expect(screen.queryByTestId('landing-modules')).toBeNull();
  });
});

describe('landing source (UI2-3, UI2-4)', () => {
  it('holds no authentication instructions, secrets or invented metrics', () => {
    const sources = Object.values(SOURCE_FILES);
    expect(sources).toHaveLength(4);
    const copy = ['en', 'fr'].map((locale) =>
      JSON.stringify(resources[locale as 'en' | 'fr'].common.landing),
    );
    for (const text of [...sources, ...copy]) {
      expect(text).not.toMatch(FORBIDDEN);
      // UI2-6: the page never hard-codes a deployment host.
      expect(text).not.toMatch(/hr-dev|https?:\/\//u);
    }
    for (const text of copy) expect(text).not.toMatch(/\d+(?:[.,]\d+)?\s?%/u);
  });
});

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
  AVAILABLE_NOW,
  COMING_NEXT,
  CONTACT_EMAIL,
  INTEGRATION_GROUPS,
  MODULES,
  VISION_MODULES,
  type Availability,
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

/**
 * MVP-093A (D93-14), tests 13 and 14: the exact module truth table. Three states; a module is
 * `available` only when its whole scope is usable, `capabilities` when only part is, and `later`
 * when nothing is built.
 */
const MODULE_TRUTH: Record<string, Availability> = {
  employees: 'available',
  leave: 'available',
  payroll: 'capabilities',
  loans: 'later',
  benefits: 'later',
  performance: 'later',
  documents: 'capabilities',
  analytics: 'capabilities',
  workflow: 'capabilities',
  ai: 'available',
  integrations: 'capabilities',
  mobile: 'later',
};

/** The only integrations that exist today; every other displayed name is planned. */
const INTEGRATION_TRUTH: Record<string, 'capabilities' | 'later'> = {
  'WhatsApp Business': 'capabilities',
  restApi: 'capabilities',
  webhooks: 'capabilities',
};

describe('catalogue (UI2-1, UI2-2, D93-14)', () => {
  it('keeps the twelve vision modules in order, with the exact three-state truth table', () => {
    expect(VISION_MODULES.map((module) => module.id)).toEqual(APPROVED_VISION);
    expect(Object.fromEntries(VISION_MODULES.map((module) => [module.id, module.status]))).toEqual(
      MODULE_TRUTH,
    );
    // Shipped capabilities listed in addition to the vision modules, never a thirteenth one.
    expect(AVAILABLE_MODULES.map((module) => module.id)).toEqual([
      'employees',
      'leave',
      'ai',
      'organization',
      'scheduling',
      'attendance',
      'time',
      'tasks',
      'expenses',
      'contracts',
      'access',
    ]);
    expect(MODULES.filter((module) => !module.vision).every((m) => m.status === 'available')).toBe(
      true,
    );
    // D93-14: "Available now" in grouped concise copy, then "Coming next" after this story.
    expect(AVAILABLE_NOW).toEqual([
      'people',
      'contracts',
      'leave',
      'scheduling',
      'timesheets',
      'payrollPreparation',
      'compensation',
      'operations',
      'integrations',
      'tasks',
      'expenses',
      'languages',
    ]);
    expect(COMING_NEXT).toEqual([
      'payrollCalculation',
      'statutory',
      'payslips',
      'disbursement',
      'advances',
      'platform',
    ]);
    expect(MODULES.find((module) => module.id === 'scheduling')?.vision).toBe(false);
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
    // D93-14: each entry has its own truthful state; only the three that exist are active.
    for (const entry of INTEGRATION_GROUPS.flatMap((group) => group.entries)) {
      const key = entry.kind === 'brand' ? entry.name : entry.labelKey;
      expect(entry.state, key).toBe(INTEGRATION_TRUTH[key] ?? 'later');
    }
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

  it('labels every vision module with exactly one of the three states from the catalogue', async () => {
    await renderLanding(locale);
    const modules = screen.getByTestId('landing-modules');
    const cards = modules.querySelectorAll('li[data-status]');
    expect([...cards].map((card) => card.getAttribute('data-module'))).toEqual(APPROVED_VISION);
    const label = {
      available: strings.landing.modules.available,
      capabilities: strings.landing.modules.capabilities,
      later: strings.landing.modules.later,
    } as const;
    for (const card of cards) {
      const id = card.getAttribute('data-module') ?? '';
      const status = MODULE_TRUTH[id] as Availability;
      const html = card as HTMLElement;
      expect(card.getAttribute('data-status')).toBe(status);
      const item = strings.landing.modules.items[id as 'employees'];
      expect(html).toHaveTextContent(item.title);
      // Exactly one state label, the right one.
      for (const [state, text] of Object.entries(label)) {
        expect(within(html).queryAllByText(text)).toHaveLength(state === status ? 1 : 0);
      }
      // A "Capabilities available" module also says what is not yet available (never the whole
      // vision claimed); other modules show no such line.
      const remaining = within(html).queryByTestId('landing-remaining');
      if (status === 'capabilities') {
        expect(remaining).toHaveTextContent(strings.landing.modules.remainingLabel);
        expect(remaining).toHaveTextContent((item as unknown as { remaining: string }).remaining);
      } else {
        expect(remaining).toBeNull();
      }
    }
    // D93-14: the two states as plain lists, each item from the catalogue, no control and no date.
    const today = screen.getByTestId('landing-today');
    expect(today).toHaveTextContent(strings.landing.modules.todayTitle);
    expect(
      [...today.querySelectorAll('li')].map((item) => item.getAttribute('data-module')),
    ).toEqual([...AVAILABLE_NOW]);
    for (const id of AVAILABLE_NOW) {
      expect(today).toHaveTextContent(strings.landing.modules.now[id]);
    }
    const next = screen.getByTestId('landing-next');
    expect(next).toHaveTextContent(strings.landing.modules.nextTitle);
    expect(
      [...next.querySelectorAll('li')].map((item) => item.getAttribute('data-module')),
    ).toEqual([...COMING_NEXT]);
    for (const id of COMING_NEXT) {
      expect(next).toHaveTextContent(strings.landing.modules.next[id]);
    }
    expect(today.querySelectorAll('a, button')).toHaveLength(0);
    expect(next.querySelectorAll('a, button')).toHaveLength(0);
    expect(`${today.textContent}${next.textContent}`).not.toMatch(/\d/u);
  });

  it('keeps every shipped capability out of "Coming next" and every planned claim planned (tests 13, 14)', async () => {
    await renderLanding(locale);
    const next = screen.getByTestId('landing-next').textContent;
    const now = screen.getByTestId('landing-today').textContent;
    // Test 13: nothing shipped through MVP-093A is represented only as "Coming next".
    expect(next).not.toMatch(
      /lock|verrouill|webhook|whatsapp|task|tâche|expense|frais|briefing|synthèse|compensation|rémunération|variance|écart|contrat|contract/iu,
    );
    for (const id of AVAILABLE_NOW) {
      expect(screen.getByTestId('landing-next')).not.toHaveTextContent(
        strings.landing.modules.now[id],
      );
    }
    // Test 14: payment, statutory, payslip, loan, benefit, performance, general-document and
    // mobile claims are never in "Available now".
    expect(now).not.toMatch(
      /calcul|gross|brute|net pay|payslip|bulletin|statut|légal|disburs|versement|reconcil|rapproch|advance|avance|loan|prêt|benefit|avantage|performance|e-?signature|signature|mobile|native|natives/iu,
    );
    // ...and appear in "Coming next" (and, for payroll, only as planned on the payroll card).
    for (const id of COMING_NEXT) {
      expect(screen.getByTestId('landing-next')).toHaveTextContent(
        strings.landing.modules.next[id],
      );
    }
    for (const id of ['loans', 'benefits', 'performance', 'mobile']) {
      const card = screen
        .getByTestId('landing-modules')
        .querySelector(`li[data-module="${id}"]`) as HTMLElement;
      expect(within(card).getByText(strings.landing.modules.later)).toBeInTheDocument();
    }
    // No obsolete approved-time-only wording survives (D93-14).
    expect(JSON.stringify(strings.landing)).not.toMatch(/pretending|sans prétendre/iu);
    // Claims never made anywhere on the page: calculation, payslips or payment performed today.
    const active = [
      strings.landing.modules.items.payroll.description,
      strings.landing.payroll.activeMessage,
      strings.landing.payroll.steps.preparation.description,
      strings.landing.payroll.steps.compensation.description,
    ].join(' ');
    expect(active).not.toMatch(/payslip|bulletin|net pay|paie nette|disburs|versement/iu);
  });

  it('shows payroll preparation and compensation as active and calculation and payment as planned (D93-14)', async () => {
    const { container } = await renderLanding(locale);
    const message = screen.getByTestId('landing-payroll-active');
    expect(message).toHaveTextContent(strings.landing.payroll.activeMessage);
    expect(message).toHaveTextContent(strings.landing.modules.capabilities);
    const section = container.querySelector('#payroll') as HTMLElement;
    const step = (key: string) => section.querySelector(`li[data-step="${key}"]`) as HTMLElement;
    for (const key of ['preparation', 'compensation']) {
      expect(step(key)).toHaveTextContent(strings.landing.modules.available);
      expect(step(key)).not.toHaveTextContent(strings.landing.modules.later);
    }
    for (const key of ['calculation', 'payment']) {
      expect(step(key)).toHaveTextContent(strings.landing.modules.later);
      expect(step(key)).not.toHaveTextContent(strings.landing.modules.available);
    }
    // The responsibility disclaimer stays, and so does the planned-rails list.
    expect(screen.getByTestId('landing-payout-note')).toHaveTextContent(
      strings.landing.payroll.note,
    );
    expect(strings.landing.payroll.note).toMatch(
      locale === 'en' ? /remains responsible/u : /reste responsable/u,
    );
    expect(section.querySelector('ul[aria-label]')).toHaveAccessibleName(
      strings.landing.payroll.railsLabel,
    );
    // The fixed compensation notice is on the page (D93-12 wording).
    expect(strings.landing.payroll.steps.compensation.description).toContain(
      locale === 'en'
        ? 'Recording compensation does not calculate payroll or initiate payment.'
        : 'Enregistrer une rémunération ne calcule pas la paie et ne déclenche aucun paiement.',
    );
    // The finance flow stays planned; the AI section is the governed briefing, marked active.
    const finance = document.getElementById('landing-finance-title') as HTMLElement;
    expect(
      within(finance.parentElement as HTMLElement).getByText(strings.landing.modules.later),
    ).toBeInTheDocument();
    // U4-1 kept: no text claims that DivalHR calculates payroll, moves money or lends today.
    const now = AVAILABLE_NOW.map((id) => strings.landing.modules.now[id]).join(' ');
    expect(now).not.toMatch(/calcul|payout|versement|disburse|loan|prêt|credit|crédit/iu);
  });

  it('describes Dival AI as the governed, grounded daily briefing only (D93-14)', async () => {
    const { container } = await renderLanding(locale);
    const heading = document.getElementById('landing-ai-title') as HTMLElement;
    expect(
      within(heading.parentElement as HTMLElement).getByText(strings.landing.modules.available),
    ).toBeInTheDocument();
    const section = container.querySelector('.landing-ai') as HTMLElement;
    for (const key of ['metrics', 'templates', 'sources', 'fallback'] as const) {
      expect(section).toHaveTextContent(strings.landing.ai.guarantees[key]);
    }
    expect(section.querySelectorAll('.landing-guarantees li')).toHaveLength(4);
    expect(section.querySelector('ul[aria-label]')).toHaveAccessibleName(
      strings.landing.ai.topicsLabel,
    );
    // No free-form chat and no employee-level conclusion is promised.
    expect(JSON.stringify(strings.landing.ai)).not.toMatch(
      /ask workforce|plain language|posez|langage naturel|which sites need|Which employees/iu,
    );
    expect(strings.landing.ai.body).toMatch(
      locale === 'en'
        ? /does not answer free-form questions/u
        : /ne répond pas à des questions libres/u,
    );
    expect(strings.landing.modules.items.ai.description).toMatch(
      locale === 'en' ? /not a general assistant/u : /pas un assistant généraliste/u,
    );
  });

  it('marks Leave available, lists Work scheduling today and keeps the rest planned (UI-003)', async () => {
    await renderLanding(locale);
    const modules = screen.getByTestId('landing-modules');
    const card = (id: string) => modules.querySelector(`li[data-module="${id}"]`) as HTMLElement;
    expect(within(card('leave')).getByText(strings.landing.modules.available)).toBeInTheDocument();
    expect(card('leave')).toHaveTextContent(strings.landing.modules.items.leave.description);
    for (const id of ['loans', 'benefits', 'performance', 'mobile']) {
      expect(within(card(id)).getByText(strings.landing.modules.later)).toBeInTheDocument();
    }
    // Scheduling, attendance, time, tasks and expenses are shipped capabilities, never among the
    // twelve vision cards.
    for (const id of ['scheduling', 'attendance', 'time', 'tasks', 'expenses']) {
      expect(modules.querySelector(`li[data-status][data-module="${id}"]`)).toBeNull();
    }
    const today = screen.getByTestId('landing-today');
    expect(today.querySelector('li[data-module="scheduling"]')).toHaveTextContent(
      strings.landing.modules.now.scheduling,
    );
    // No unbuilt leave capability is claimed (UI3-1, UI3-2, U4-1): no balance or calendar.
    const leave = `${strings.landing.modules.items.leave.description} ${strings.landing.modules.now.leave} ${strings.landing.outcomes.items.employees.description}`;
    expect(leave).not.toMatch(/balance|solde|calendar|calendrier/iu);
  });

  it('shows the current navigation and workflows in the illustrative preview (UI3-3, U4-2)', async () => {
    await renderLanding(locale);
    const preview = screen.getByTestId('landing-preview');
    const p = strings.landing.preview;
    // The preview's navigation uses the application's own labels (nav.*), so it cannot drift.
    expect(p.nav.schedules).toBe(strings.nav.workSchedules);
    expect(p.nav.attendance).toBe(strings.nav.attendanceReview);
    expect(p.nav.timesheets).toBe(strings.nav.timesheets);
    expect(p.nav.exports).toBe(strings.nav.timeExports);
    // D93-14: Tasks and Expenses join the illustrative navigation under the application's names.
    expect(p.nav.tasks).toBe(strings.nav.tasks);
    expect(p.nav.expenses).toBe(strings.nav.expenses);
    for (const text of [
      p.nav.employees,
      p.nav.leave,
      p.nav.schedules,
      p.nav.attendance,
      p.nav.timesheets,
      p.nav.exports,
      p.actions.import,
      p.actions.reviewLeave,
      p.actions.openTimesheets,
      p.actions.exportTime,
      p.cards.attendance.title,
      p.cards.timesheets.title,
      p.cards.exports.title,
      p.cards.exports.description,
      p.nav.tasks,
      p.nav.expenses,
      p.cards.tasks.title,
      p.cards.tasks.description,
      p.cards.expenses.title,
      p.cards.expenses.description,
    ]) {
      expect(preview).toHaveTextContent(text);
    }
    expect(preview.querySelectorAll('.landing-preview__card')).toHaveLength(6);
    expect(preview.querySelectorAll('.landing-preview__link')).toHaveLength(9);
    expect(preview.querySelectorAll('a, button')).toHaveLength(0);
    expect(screen.getByRole('figure')).toHaveAccessibleName(p.caption);
  });

  it('gives every integration its own truthful state, with local logos announced once and the disclaimer', async () => {
    const { container } = await renderLanding(locale);
    const section = screen.getByTestId('landing-integrations');
    const entries = [...section.querySelectorAll('li[data-entry]')];
    expect(entries).toHaveLength(20);
    for (const entry of entries) {
      const key = entry.getAttribute('data-entry') ?? '';
      const state = INTEGRATION_TRUTH[key] ?? 'later';
      expect(entry.getAttribute('data-state')).toBe(state);
      // The state is text, never colour alone; "Available now" is not used for an integration.
      expect(entry).toHaveTextContent(
        state === 'capabilities'
          ? strings.landing.modules.capabilities
          : strings.landing.modules.later,
      );
      expect(entry).not.toHaveTextContent(strings.landing.modules.available);
    }
    expect(entries.filter((e) => e.getAttribute('data-state') === 'capabilities')).toHaveLength(3);
    // Teams, Slack, banks, mobile money, accounting providers and SSO stay planned.
    for (const key of [
      'Microsoft Teams',
      'Slack',
      'M-PESA',
      'Sage',
      'SAP',
      'bankTransfer',
      'sso',
    ]) {
      expect(section.querySelector(`li[data-entry="${key}"]`)?.getAttribute('data-state')).toBe(
        'later',
      );
    }
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

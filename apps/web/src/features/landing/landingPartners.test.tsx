import { resources } from '@divalhr/localization';
import { render, screen, within } from '@testing-library/react';
import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { App } from '../../app/App';
import { initI18n } from '../../i18n';
import { fakeUserManager, testConfig } from '../../test/renderApp';
import { AVAILABLE_NOW, MODULES, PARTNER_POINTS } from './catalogue';

/** MVP-120B (D120B-10 to D120B-12): the truthful landing refresh and the partner program. */
const BANNED =
  /approved\s+loan|guarantee|funded|interest\s+rate|(?<!\p{L})APR(?!\p{L})|credit\s+score|prêt\s+approuvé|garanti|financé|taux\s+d.intérêt|TAEG|score\s+de\s+crédit/iu;

async function renderLanding(locale: 'en' | 'fr') {
  const manager = fakeUserManager();
  window.history.pushState({}, '', '/');
  await initI18n(locale);
  const view = render(<App config={testConfig} userManager={manager} />);
  await screen.findByTestId('landing-modules');
  return view;
}

describe.each(['en', 'fr'] as const)('landing partner program (%s)', (locale) => {
  const strings = resources[locale].common;

  it('keeps Loans & Salary Advances "Coming next" and adds the labelled foundation only', async () => {
    await renderLanding(locale);
    const loans = MODULES.find((module) => module.id === 'loans');
    expect(loans?.status).toBe('later');
    expect(loans?.foundation).toBe(true);
    const card = screen
      .getByTestId('landing-modules')
      .querySelector('li[data-module="loans"]') as HTMLElement;
    expect(card).toHaveAttribute('data-status', 'later');
    expect(within(card).getByText(strings.landing.modules.later)).toBeInTheDocument();
    const foundation = within(card).getByTestId('landing-foundation');
    expect(foundation).toHaveTextContent(strings.landing.modules.foundationLabel);
    expect(foundation).toHaveTextContent(strings.landing.modules.items.loans.foundation);
    // Only Loans & Salary Advances carries a foundation; the plain "Available now" list never names it.
    expect(screen.getAllByTestId('landing-foundation')).toHaveLength(1);
    expect(AVAILABLE_NOW.join(' ')).not.toMatch(/advance|loan/iu);
    expect(screen.getByTestId('landing-today')).not.toHaveTextContent(
      strings.landing.modules.items.loans.foundation,
    );
    expect(screen.getByTestId('landing-next')).toHaveTextContent(
      strings.landing.modules.next.advances,
    );
  });

  it('shows the Partner with DivalHR section linking to the public application', async () => {
    await renderLanding(locale);
    const section = screen.getByTestId('landing-partners');
    expect(within(section).getByRole('heading', { level: 2 })).toHaveTextContent(
      locale === 'en' ? 'Partner with DivalHR' : 'Devenir partenaire de DivalHR',
    );
    for (const point of PARTNER_POINTS) {
      const item = section.querySelector(`li[data-partner-point="${point.key}"]`);
      expect(item).not.toBeNull();
      expect(item).toHaveTextContent(strings.landing.partners.points[point.key as 'consent'].title);
    }
    const cta = within(section).getByTestId('landing-partner-cta');
    expect(cta).toHaveTextContent(strings.landing.partners.cta);
    // An internal link to the application: no mailto, no form, upload or portal on the landing page.
    expect(cta.getAttribute('href')).toBe('/partners/apply');
    expect(section.querySelectorAll('form, input, textarea, select, [type="file"]')).toHaveLength(
      0,
    );
    expect(section.querySelectorAll('a')).toHaveLength(1);
    expect(section).toHaveTextContent(strings.landing.partners.ctaNote);
    // D120D-16: no claim that a real partner is connected, listed or integrated.
    expect(strings.landing.partners.body).toMatch(
      locale === 'en' ? /no real partner is connected/u : /aucun partenaire réel n’est connecté/u,
    );
  });

  it('says plainly what the delivered partner catalogue is and what is still not available (R218-2)', async () => {
    await renderLanding(locale);
    const benefits = screen
      .getByTestId('landing-modules')
      .querySelector('li[data-module="benefits"]') as HTMLElement;
    expect(MODULES.find((module) => module.id === 'benefits')?.status).toBe('capabilities');
    expect(benefits).toHaveAttribute('data-status', 'capabilities');
    // Not "Coming next": the card says what is available and what is not.
    expect(within(benefits).getByText(strings.landing.modules.capabilities)).toBeInTheDocument();
    expect(within(benefits).queryByText(strings.landing.modules.later)).not.toBeInTheDocument();
    const item = strings.landing.modules.items.benefits;
    expect(benefits).toHaveTextContent(item.description);
    expect(benefits).toHaveTextContent(strings.landing.modules.remainingLabel);
    expect(benefits).toHaveTextContent(item.remaining);
    expect(within(benefits).queryByTestId('landing-foundation')).not.toBeInTheDocument();
    expect(item.description).toMatch(
      locale === 'en'
        ? /Approved partner catalogue available\. Employees can review informational offers\./u
        : /Catalogue de partenaires approuvés disponible\. Les employés peuvent consulter des offres informatives\./u,
    );
    expect(item.remaining).toMatch(
      locale === 'en'
        ? /enrollment.*applications.*connections.*financing decisions.*payroll deductions.*money movement/iu
        : /adhésion.*demandes.*connexions.*décisions de financement.*retenues sur salaire.*mouvements d’argent/iu,
    );
    // The plain "Available now" list names the catalogue as information only and "Coming next"
    // keeps enrollment and applications.
    expect(screen.getByTestId('landing-today')).toHaveTextContent(
      strings.landing.modules.now.partnerCatalogue,
    );
    expect(screen.getByTestId('landing-next')).toHaveTextContent(
      strings.landing.modules.next.platform,
    );
    // The benefits card names no partner, product or internal reference.
    expect(benefits).not.toHaveTextContent(/\bP[TP]-[0-9A-Z]{12,16}\b/u);
  });

  it('states that DivalHR is not a lender and that every handoff is synthetic today', () => {
    expect(strings.landing.partners.body).toMatch(
      locale === 'en' ? /not a lender/u : /n’est pas un prêteur/u,
    );
    expect(strings.landing.partners.points.synthetic.description).toMatch(
      locale === 'en' ? /test partner/u : /partenaire de test/u,
    );
    expect(strings.landing.modules.items.loans.foundation).toMatch(
      locale === 'en' ? /not the lender/u : /n’est pas le prêteur/u,
    );
  });

  it('carries no banned lending wording in any new copy', () => {
    const copy = JSON.stringify([
      strings.landing.partners,
      strings.landing.modules.foundationLabel,
      strings.landing.modules.items.loans,
      strings.landing.modules.next.advances,
      strings.salaryAdvance.handoff,
      strings.home.tenant.progress.items.salaryAdvanceHandoff,
      strings.home.employee.cards.salaryAdvance,
    ]);
    expect(copy).not.toMatch(BANNED);
  });

  it('has no detectable accessibility violations in the partner section', async () => {
    await renderLanding(locale);
    const result = await axe.run(screen.getByTestId('landing-partners'), {
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

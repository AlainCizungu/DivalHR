import { resources } from '@divalhr/localization';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { PartnerApplyPage } from './PartnerApplyPage';
import { CATEGORIES } from './partners';

const RECEIPT = {
  reference: 'PA-ABCDEFGHJKMN',
  submittedAt: '2026-10-20T09:30:00Z',
  status: 'SUBMITTED',
  nextStep: 'DIVALHR_WILL_CONTACT_BUSINESS_EMAIL',
};
const problem = (code: string, status: number, params: object = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-12345678',
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

async function open(locale: 'en' | 'fr' = 'en') {
  await renderWithSession(<PartnerApplyPage />, { kind: 'anonymous' }, locale, '/partners/apply');
  return { user: userEvent.setup(), c: resources[locale].common.partners };
}

type Strings = (typeof resources)['en']['common']['partners'];

async function fillValid(user: ReturnType<typeof userEvent.setup>, c: Strings, category = 'BANK') {
  const f = c.apply.fields;
  await user.type(screen.getByLabelText(f.legalName.label), 'Synthetic Test Bank SA');
  await user.selectOptions(screen.getByLabelText(f.countryOfRegistration.label), 'CD');
  const operation = screen.getByRole('group', { name: f.countriesOfOperation.label });
  await user.click(operation.querySelector('input[value="CD"]') as HTMLElement);
  await user.type(screen.getByLabelText(f.registrationReference.label), 'SYN-0001');
  await user.selectOptions(screen.getByLabelText(f.primaryCategory.label), category);
  if (screen.queryByLabelText(f.regulatorName.label)) {
    await user.type(screen.getByLabelText(f.regulatorName.label), 'Synthetic Regulator');
    await user.type(screen.getByLabelText(f.licenceReference.label), 'LIC-0001');
  }
  await user.click(screen.getByRole('checkbox', { name: c.capability.SALARY_ADVANCE }));
  await user.type(
    screen.getByLabelText(f.serviceSummary.label),
    'A synthetic summary that is long enough.',
  );
  const website = screen.getByLabelText(f.website.label);
  await user.clear(website);
  await user.type(website, 'https://example.test');
  await user.click(screen.getByRole('radio', { name: c.api.PLANNED }));
  await user.type(screen.getByLabelText(f.contactName.label), 'Test Contact');
  await user.type(screen.getByLabelText(f.contactRole.label), 'Head of partnerships');
  await user.type(screen.getByLabelText(f.businessEmail.label), 'contact@example.test');
  await user.type(screen.getByLabelText(f.privacyContactEmail.label), 'privacy@example.test');
  await user.click(screen.getByRole('checkbox', { name: f.acknowledgment.label }));
}

const posts = (requests: Recorded[]) => requests.filter((r) => r.method === 'POST');

describe('public partner application', () => {
  it('offers the eight categories and shows the regulatory fields only for regulated ones', async () => {
    const { user, c } = await open();
    const select = screen.getByLabelText(c.apply.fields.primaryCategory.label);
    const values = within(select)
      .getAllByRole('option')
      .map((o) => (o as HTMLOptionElement).value)
      .filter(Boolean);
    expect(values).toEqual([...CATEGORIES]);
    expect(screen.queryByTestId('partner-regulatory-fields')).toBeNull();
    for (const category of CATEGORIES) {
      await user.selectOptions(select, category);
      const regulated = [
        'BANK',
        'MICROFINANCE',
        'INSURANCE',
        'TELECOM',
        'MOBILE_MONEY_PAYMENT',
        'PENSION_SAVINGS',
      ].includes(category);
      expect(screen.queryByTestId('partner-regulatory-fields') !== null, category).toBe(regulated);
    }
  });

  it('hides the honeypot from sight, keyboard and assistive technology', async () => {
    const { user } = await open();
    const trap = screen.getByTestId('partner-trap');
    expect(trap).toHaveAttribute('aria-hidden', 'true');
    expect(trap).toHaveAttribute('inert');
    const input = trap.querySelector('input') as HTMLInputElement;
    expect(input).toHaveAttribute('tabindex', '-1');
    expect(input).toHaveAttribute('autocomplete', 'off');
    expect(input.value).toBe('');
    // Not exposed as a control and never reached by Tab.
    expect(screen.queryAllByRole('textbox').includes(input)).toBe(false);
    const focusable: Element[] = [];
    for (let i = 0; i < 80; i += 1) {
      await user.tab();
      focusable.push(document.activeElement as Element);
    }
    expect(focusable).not.toContain(input);
  });

  it('keeps what was typed after a validation error and sends nothing', async () => {
    const requests = stub(() => ({ status: 201, body: RECEIPT }));
    const { user, c } = await open();
    await user.type(
      screen.getByLabelText(c.apply.fields.legalName.label),
      'Synthetic Test Bank SA',
    );
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    const summary = await screen.findByTestId('partner-error-summary');
    expect(summary).toHaveTextContent(c.apply.errorSummary);
    expect(summary).toHaveTextContent(c.apply.validation.REQUIRED);
    expect(screen.getByLabelText(c.apply.fields.legalName.label)).toHaveValue(
      'Synthetic Test Bank SA',
    );
    expect(requests).toHaveLength(0);
  });

  it('submits once with an idempotency key and ends on the reference, the time and the next step only', async () => {
    const requests = stub(() => ({ status: 201, body: RECEIPT }));
    const { user, c } = await open();
    await fillValid(user, c);
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    const confirmation = await screen.findByTestId('partner-confirmation');
    const sent = posts(requests);
    expect(sent).toHaveLength(1);
    expect(sent[0]?.path).toBe('/public/partner-applications');
    expect(sent[0]?.key).toMatch(/^web-/u);
    const body = JSON.parse(sent[0]?.body ?? '{}') as Record<string, unknown>;
    expect(body.faxNumber).toBe('');
    expect(body.acknowledgment).toBe(true);
    expect(body.locale).toBe('en');
    expect(within(confirmation).getByTestId('partner-reference')).toHaveTextContent(
      RECEIPT.reference,
    );
    expect(within(confirmation).getByTestId('partner-submitted-at')).toBeInTheDocument();
    expect(within(confirmation).getByTestId('partner-next-step')).toHaveTextContent(
      c.confirmation.nextStep.DIVALHR_WILL_CONTACT_BUSINESS_EMAIL,
    );
    // No public status link or any other control, and nothing typed is shown again.
    expect(within(confirmation).queryAllByRole('link')).toHaveLength(0);
    expect(within(confirmation).queryAllByRole('button')).toHaveLength(0);
    expect(confirmation).not.toHaveTextContent('Synthetic Test Bank');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('reads the honeypot at submit time only and never keeps it in browser storage', async () => {
    const requests = stub(() => ({ status: 201, body: RECEIPT }));
    const { user, c } = await open();
    await fillValid(user, c);
    const input = screen.getByTestId('partner-trap').querySelector('input') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'bot-value' } });
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    await screen.findByTestId('partner-confirmation');
    const body = JSON.parse(posts(requests)[0]?.body ?? '{}') as Record<string, unknown>;
    expect(body.faxNumber).toBe('bot-value');
    expect(document.body.textContent).not.toContain('bot-value');
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('shows server field errors by name and keeps the values', async () => {
    stub(() => ({
      status: 400,
      body: problem('PARTNER_APPLICATION_INVALID', 400, {
        fields: [{ field: 'website', constraint: 'FORMAT' }],
      }),
    }));
    const { user, c } = await open();
    await fillValid(user, c);
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    const summary = await screen.findByTestId('partner-error-summary');
    expect(summary).toHaveTextContent(c.apply.fields.website.label);
    expect(summary).toHaveTextContent(c.apply.validation.FORMAT);
    expect(screen.getByTestId('partner-failure')).toHaveTextContent(
      resources.en.common.errors.PARTNER_APPLICATION_INVALID,
    );
    expect(screen.getByLabelText(c.apply.fields.legalName.label)).toHaveValue(
      'Synthetic Test Bank SA',
    );
    expect(screen.getByLabelText(c.apply.fields.website.label)).toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  it.each([
    ['PARTNER_APPLICATION_RATE_LIMITED', 429],
    ['PARTNER_APPLICATION_CONSENT_REQUIRED', 400],
    ['PARTNER_APPLICATION_IDEMPOTENCY_REUSED', 409],
  ] as const)('renders %s as a finished sentence in English and French', async (code, status) => {
    for (const locale of ['en', 'fr'] as const) {
      stub(() => ({ status, body: problem(code, status) }));
      const { user, c } = await open(locale);
      await fillValid(user, c);
      await user.click(screen.getByRole('button', { name: c.apply.submit }));
      const alert = await screen.findByTestId('partner-failure');
      expect(alert).toHaveTextContent(resources[locale].common.errors[code]);
      expect(alert).not.toHaveTextContent(code);
      vi.unstubAllGlobals();
      document.body.innerHTML = '';
    }
  });

  it('retries the same payload with the same key and a reused key with a new one', async () => {
    let calls = 0;
    const replies: Reply[] = [
      { status: 503, body: problem('SERVICE_UNAVAILABLE', 503) },
      { status: 409, body: problem('PARTNER_APPLICATION_IDEMPOTENCY_REUSED', 409) },
      { status: 201, body: RECEIPT },
    ];
    const requests = stub(() => replies[calls++] ?? { status: 201, body: RECEIPT });
    const { user, c } = await open();
    await fillValid(user, c);
    const submit = screen.getByRole('button', { name: c.apply.submit });
    await user.click(submit);
    await screen.findByTestId('partner-failure');
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    await waitFor(() => {
      expect(screen.getByTestId('partner-failure')).toHaveAttribute(
        'data-code',
        'PARTNER_APPLICATION_IDEMPOTENCY_REUSED',
      );
    });
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    await screen.findByTestId('partner-confirmation');
    const keys = posts(requests).map((r) => r.key);
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it('has no detectable accessibility violations', async () => {
    await open();
    const result = await axe.run(screen.getByTestId('partner-apply-page'), {
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

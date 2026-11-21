import { resources } from '@divalhr/localization';
import { cleanup, fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { PartnerApplyPage } from './PartnerApplyPage';
import { RECEIPT_STORAGE_KEY } from './partnerReceipt';
import { CATEGORIES, MAX_OPERATION } from './partners';

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

/** R216-4: the one browser-storage entry is the four-field receipt: no form, contact or consent data. */
function expectOnlyTheSafeReceiptStored() {
  expect(window.localStorage.length).toBe(0);
  expect(window.sessionStorage.length).toBe(1);
  const raw = window.sessionStorage.getItem(RECEIPT_STORAGE_KEY) ?? '';
  expect(JSON.parse(raw)).toEqual(RECEIPT);
  expect(Object.keys(JSON.parse(raw) as object).sort()).toEqual([
    'nextStep',
    'reference',
    'status',
    'submittedAt',
  ]);
  for (const forbidden of [
    'Synthetic',
    'example.test',
    'LIC-0001',
    'SYN-0001',
    'PARTNER-',
    'bot-value',
    'faxNumber',
    'acknowledgment',
  ]) {
    expect(raw).not.toContain(forbidden);
  }
}

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
    expect(
      within(confirmation)
        .getAllByRole('button')
        .map((b) => b.textContent),
    ).toEqual([c.confirmation.copy, c.confirmation.startAnother]);
    expect(confirmation).not.toHaveTextContent('Synthetic Test Bank');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');
    expectOnlyTheSafeReceiptStored();
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
    expectOnlyTheSafeReceiptStored();
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

  it('shows the regulatory fields when a regulated category is only an additional one', async () => {
    const { user, c } = await open();
    await user.selectOptions(
      screen.getByLabelText(c.apply.fields.primaryCategory.label),
      'HEALTH_BENEFITS',
    );
    expect(screen.queryByTestId('partner-regulatory-fields')).toBeNull();
    const additional = screen.getByRole('group', {
      name: c.apply.fields.additionalCategories.label,
    });
    await user.click(
      within(additional).getByRole('checkbox', { name: c.category.OTHER_REGULATED_PROVIDER }),
    );
    expect(screen.queryByTestId('partner-regulatory-fields')).toBeNull();
    await user.click(within(additional).getByRole('checkbox', { name: c.category.TELECOM }));
    expect(screen.getByTestId('partner-regulatory-fields')).toBeInTheDocument();
    await user.click(within(additional).getByRole('checkbox', { name: c.category.TELECOM }));
    expect(screen.queryByTestId('partner-regulatory-fields')).toBeNull();
  });

  it('refuses to send an application with a regulated additional category and no regulator', async () => {
    const requests = stub(() => ({ status: 201, body: RECEIPT }));
    const { user, c } = await open();
    await fillValid(user, c, 'HEALTH_BENEFITS');
    const additional = screen.getByRole('group', {
      name: c.apply.fields.additionalCategories.label,
    });
    await user.click(within(additional).getByRole('checkbox', { name: c.category.INSURANCE }));
    await user.click(screen.getByRole('button', { name: c.apply.submit }));
    const summary = await screen.findByTestId('partner-error-summary');
    expect(summary).toHaveTextContent(c.apply.fields.regulatorName.label);
    expect(summary).toHaveTextContent(c.apply.fields.licenceReference.label);
    expect(requests).toHaveLength(0);
  });

  it.each(['en', 'fr'] as const)(
    'offers every ISO country by its %s name and caps the countries of operation',
    async (locale) => {
      const { c } = await open(locale);
      const operation = screen.getByRole('group', {
        name: c.apply.fields.countriesOfOperation.label,
      });
      const boxes = within(operation).getAllByRole('checkbox');
      expect(boxes).toHaveLength(249);
      const names = new Intl.DisplayNames([locale], { type: 'region' });
      expect(within(operation).getByRole('checkbox', { name: names.of('SN') })).toBeInTheDocument();
      expect(within(operation).getByRole('checkbox', { name: names.of('CD') })).toBeInTheDocument();
      expect(within(operation).queryByRole('checkbox', { name: /^(other|autre)$/iu })).toBeNull();
      const registration = within(
        screen.getByLabelText(c.apply.fields.countryOfRegistration.label),
      ).getAllByRole('option');
      expect(registration).toHaveLength(250);
      for (const box of boxes.slice(0, MAX_OPERATION)) fireEvent.click(box);
      const rest = within(operation).getAllByRole('checkbox', { checked: false });
      expect(rest).toHaveLength(249 - MAX_OPERATION);
      for (const box of rest) expect(box).toBeDisabled();
    },
  );

  describe('the safe receipt', () => {
    it.each(['en', 'fr'] as const)(
      'survives a reload in %s without storing anything else or contacting the server',
      async (locale) => {
        const requests = stub(() => ({ status: 201, body: RECEIPT }));
        const { user, c } = await open(locale);
        await fillValid(user, c);
        await user.click(screen.getByRole('button', { name: c.apply.submit }));
        await screen.findByTestId('partner-confirmation');
        expectOnlyTheSafeReceiptStored();
        expect(posts(requests)).toHaveLength(1);

        cleanup();
        const reloaded = await open(locale);
        const confirmation = screen.getByTestId('partner-confirmation');
        expect(within(confirmation).getByTestId('partner-reference')).toHaveTextContent(
          RECEIPT.reference,
        );
        expect(confirmation).toHaveTextContent(reloaded.c.confirmation.title);
        expect(within(confirmation).getByTestId('partner-next-step')).toHaveTextContent(
          reloaded.c.confirmation.nextStep.DIVALHR_WILL_CONTACT_BUSINESS_EMAIL,
        );
        expect(screen.queryByTestId('partner-apply-page')).toBeNull();
        expect(posts(requests)).toHaveLength(1);
        expectOnlyTheSafeReceiptStored();
      },
    );

    it('copies the reference and says so, or says it could not', async () => {
      stub(() => ({ status: 201, body: RECEIPT }));
      const { user, c } = await open();
      await fillValid(user, c);
      await user.click(screen.getByRole('button', { name: c.apply.submit }));
      await screen.findByTestId('partner-confirmation');
      await user.click(screen.getByRole('button', { name: c.confirmation.copy }));
      expect(await screen.findByTestId('partner-copy-status')).toHaveTextContent(
        c.confirmation.copied,
      );
      expect(await navigator.clipboard.readText()).toBe(RECEIPT.reference);

      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) },
      });
      await user.click(screen.getByRole('button', { name: c.confirmation.copy }));
      await waitFor(() => {
        expect(screen.getByTestId('partner-copy-status')).toHaveTextContent(
          c.confirmation.copyFailed,
        );
      });
    });

    it('starts another application with an empty form and no stored receipt', async () => {
      stub(() => ({ status: 201, body: RECEIPT }));
      const { user, c } = await open();
      await fillValid(user, c);
      await user.click(screen.getByRole('button', { name: c.apply.submit }));
      await screen.findByTestId('partner-confirmation');
      await user.click(screen.getByRole('button', { name: c.confirmation.startAnother }));
      expect(screen.getByTestId('partner-apply-page')).toBeInTheDocument();
      expect(screen.getByLabelText(c.apply.fields.legalName.label)).toHaveValue('');
      expect(window.sessionStorage.length).toBe(0);
      expect(window.localStorage.length).toBe(0);
      cleanup();
      await open();
      expect(screen.queryByTestId('partner-confirmation')).toBeNull();
      expect(screen.getByTestId('partner-apply-page')).toBeInTheDocument();
    });

    it.each([
      ['not JSON', 'not json'],
      ['extra fields', JSON.stringify({ ...RECEIPT, legalName: 'Synthetic Test Bank SA' })],
      ['a bad reference', JSON.stringify({ ...RECEIPT, reference: 'PA-lowercase0000' })],
      ['a bad next step', JSON.stringify({ ...RECEIPT, nextStep: 'SOMETHING_ELSE' })],
      ['a bad time', JSON.stringify({ ...RECEIPT, submittedAt: 'yesterday' })],
    ])('discards a stored value that is %s and shows the empty form', async (_name, raw) => {
      window.sessionStorage.setItem(RECEIPT_STORAGE_KEY, raw);
      await open();
      expect(screen.queryByTestId('partner-confirmation')).toBeNull();
      expect(screen.getByTestId('partner-apply-page')).toBeInTheDocument();
      expect(window.sessionStorage.getItem(RECEIPT_STORAGE_KEY)).toBeNull();
    });

    it('stores nothing while the application is only being typed or refused', async () => {
      stub(() => ({ status: 429, body: problem('PARTNER_APPLICATION_RATE_LIMITED', 429) }));
      const { user, c } = await open();
      await fillValid(user, c);
      expect(window.sessionStorage.length + window.localStorage.length).toBe(0);
      await user.click(screen.getByRole('button', { name: c.apply.submit }));
      await screen.findByTestId('partner-failure');
      expect(window.sessionStorage.length + window.localStorage.length).toBe(0);
    });
  });
});

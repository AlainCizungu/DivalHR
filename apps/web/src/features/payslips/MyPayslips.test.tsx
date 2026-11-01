import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import {
  PAYSLIP_ID,
  PAYSLIP_ID_2,
  payslipPage,
  payslipSummary,
  payslipView,
  problem,
} from './fixtures';
import { MyPayslipPage, MyPayslipsPage } from './MyPayslipsPage';
import { stub, type Recorded, type Reply } from './stub';

async function showList(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route path="/me/payslips" element={<MyPayslipsPage />} />
      <Route path="/me/payslips/:payslipId" element={<p data-testid="view-reached">view</p>} />
    </Routes>,
    sessionWithRoles(['employee']),
    locale,
    '/me/payslips',
  );
}

async function showView(locale: 'en' | 'fr', id = PAYSLIP_ID) {
  return renderWithSession(
    <Routes>
      <Route path="/me/payslips/:payslipId" element={<MyPayslipPage />} />
    </Routes>,
    sessionWithRoles(['employee']),
    locale,
    `/me/payslips/${id}`,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const digitsOf = (text: string | null) => (text ?? '').replace(/\D/gu, '');

describe.each(['en', 'fr'] as const)('my payslips (%s)', (locale) => {
  const c = resources[locale].common;

  it('lists own payslips newest first with keyset load more and a visible VOIDED state', async () => {
    const requests = stub((r) => {
      if (r.path.startsWith('/me/payslips?') && r.path.includes('cursor=next-2')) {
        return {
          status: 200,
          body: payslipPage([
            payslipSummary({ payslipId: PAYSLIP_ID_2, period: '2026-07', status: 'APPROVED' }),
          ]),
        };
      }
      if (r.path.startsWith('/me/payslips?')) {
        return {
          status: 200,
          body: payslipPage(
            [
              payslipSummary({
                period: '2026-08',
                status: 'VOIDED',
                voidReasonCode: 'PROFILE_CORRECTION',
                voidedAt: '2026-10-03T09:00:00Z',
              }),
            ],
            'next-2',
          ),
        };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    const { container } = await showList(locale);
    const table = await screen.findByTestId('my-payslips-table');
    expect(screen.getByTestId('my-payslips-not-proof')).toHaveTextContent(
      c.payrollPackages.notProofOfPayment,
    );
    const first = within(table).getAllByTestId('my-payslip-row')[0] as HTMLElement;
    expect(first).toHaveAttribute('data-status', 'VOIDED');
    expect(within(first).getByTestId('my-payslip-voided')).toHaveTextContent(c.payslips.my.voided);
    expect(within(first).getByTestId('my-payslip-voided')).toHaveTextContent(
      c.payrollPackages.voidReason.PROFILE_CORRECTION,
    );
    await user.click(screen.getByTestId('my-payslips-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('my-payslip-row')).toHaveLength(2);
    });
    expect(screen.queryByTestId('my-payslips-more')).toBeNull();
    expect(requests[0]?.path).toBe('/me/payslips?limit=25');
    expect(requests[1]?.path).toBe('/me/payslips?cursor=next-2&limit=25');
    // Only the employee's own amounts: no employer-wide total and no other-employee column.
    expect(container.querySelector('[data-testid^="package-total"]')).toBeNull();
    expect(container).not.toHaveTextContent(c.payrollPackages.totals.employerContributions);
    expect(screen.getAllByTestId('my-payslip-open')[0]).toHaveAttribute(
      'href',
      `/me/payslips/${PAYSLIP_ID}`,
    );
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('shows an empty state and the unlinked-employee message', async () => {
    stub(() => ({ status: 200, body: payslipPage([]) }));
    await showList(locale);
    expect(await screen.findByTestId('my-payslips-empty')).toHaveTextContent(c.payslips.my.empty);
    vi.unstubAllGlobals();
    stub(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    document.body.replaceChildren();
    await showList(locale);
    const error = await screen.findByTestId('my-payslips-error');
    expect(error).toHaveAttribute('data-kind', 'forbidden');
    expect(error).toHaveTextContent(c.errors.EMPLOYEE_LINK_REQUIRED);
  });

  it('downloads the PDF for the interface language under a safe local name', async () => {
    const requests = stub((r) => {
      if (r.path.includes('.pdf')) return { status: 200, pdf: true };
      return { status: 200, body: payslipPage([payslipSummary()]) };
    });
    Object.assign(URL, { createObjectURL: () => 'blob:synthetic', revokeObjectURL: vi.fn() });
    const names: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(`${this.download}|${this.href}`);
    });
    const user = userEvent.setup();
    await showList(locale);
    await user.click(await screen.findByTestId('my-payslip-download'));
    await waitFor(() => {
      expect(names).toEqual(['payslip-2026-09-aaaaaaaa.pdf|blob:synthetic']);
    });
    expect(requests.at(-1)?.path).toBe(`/me/payslips/${PAYSLIP_ID}.pdf?locale=${locale}`);
    expect(screen.getByTestId('my-payslips-announcer')).toHaveTextContent(c.payslips.downloaded);
  });

  it('renders the HTML payslip and switches the document language without changing amounts', async () => {
    const requests = stub((r: Recorded): Reply => {
      if (r.path.startsWith(`/me/payslips/${PAYSLIP_ID}?locale=en`)) {
        return { status: 200, body: payslipView('en') };
      }
      if (r.path.startsWith(`/me/payslips/${PAYSLIP_ID}?locale=fr`)) {
        return { status: 200, body: payslipView('fr') };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    const { container } = await showView(locale);
    const doc = await screen.findByTestId('payslip-document');
    expect(doc).toHaveAttribute('data-locale', locale);
    expect(screen.getByTestId('synthetic-badge')).toBeInTheDocument();
    // Semantic headings and table.
    expect(within(doc).getByRole('heading', { level: 2 })).toBeInTheDocument();
    const table = within(doc).getByTestId('payslip-doc-amounts');
    expect(within(table).getAllByRole('rowheader')).toHaveLength(5);
    const amounts = () =>
      ['gross', 'cnss', 'irpp', 'deductions', 'net'].map((key) =>
        digitsOf(screen.getByTestId(`payslip-doc-${key}`).querySelector('td')?.textContent ?? ''),
      );
    const employer = () => digitsOf(screen.getByTestId('payslip-doc-employer-amount').textContent);
    const before = amounts();
    expect(before).toEqual(['1000000', '50000', '50000', '100000', '900000']);
    expect(employer()).toBe('60000');
    expect(screen.getByTestId('payslip-doc-employer-note')).toBeInTheDocument();
    expect(screen.getByTestId('payslip-doc-not-proof')).toBeInTheDocument();
    // The employer contribution is not part of the net table.
    expect(within(table).queryByText(c.payslips.doc.employerContributions)).toBeNull();
    // Switch the document language.
    const other = locale === 'en' ? 'fr' : 'en';
    await user.click(screen.getByTestId(`payslip-language-${other}`));
    await waitFor(() => {
      expect(screen.getByTestId('payslip-document')).toHaveAttribute('data-locale', other);
    });
    expect(screen.getByTestId('payslip-document')).toHaveAttribute('lang', other);
    expect(requests.some((r) => r.path.endsWith(`locale=${other}`))).toBe(true);
    expect(amounts()).toEqual(before);
    expect(employer()).toBe('60000');
    expect(screen.getByTestId('payslip-doc-title')).toHaveTextContent(
      other === 'en' ? 'Payslip' : 'Bulletin de paie',
    );
    expect(screen.getByTestId('payslip-doc-digest')).toHaveTextContent('9'.repeat(64));
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('shows the VOIDED banner with the reason code label only', async () => {
    stub(() => ({ status: 200, body: payslipView(locale, {}, true) }));
    await showView(locale);
    const banner = await screen.findByTestId('payslip-voided-banner');
    expect(banner).toHaveTextContent(locale === 'en' ? 'VOIDED' : 'ANNULÉ');
    expect(screen.getByTestId('payslip-void-reason')).toHaveAttribute(
      'data-code',
      'SOURCE_CORRECTION',
    );
    expect(screen.getByTestId('payslip-void-reason')).toHaveTextContent(
      c.payrollPackages.voidReason.SOURCE_CORRECTION,
    );
    expect(screen.getByTestId('package-status')).toHaveTextContent(c.payrollPackages.status.VOIDED);
    // The document is still downloadable and still states it is not proof of payment.
    expect(screen.getByTestId('payslip-download')).toBeEnabled();
    expect(screen.getByTestId('payslip-doc-not-proof')).toBeInTheDocument();
  });

  it('shows the same safe state for a foreign or unknown payslip', async () => {
    stub(() => ({ status: 404, body: problem('PAYSLIP_NOT_FOUND', 404) }));
    await showView(locale);
    const error = await screen.findByTestId('payslip-error');
    expect(error).toHaveAttribute('data-kind', 'notFound');
    expect(error).toHaveTextContent(c.errors.PAYSLIP_NOT_FOUND);
    expect(screen.queryByTestId('payslip-document')).toBeNull();
  });
});

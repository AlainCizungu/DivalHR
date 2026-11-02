import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { EmployeePaymentSection } from './EmployeePaymentSection';
import { MyPaymentDestinationPage } from './MyPaymentDestinationPage';
import { PaymentBatchPanel } from './PaymentBatchPanel';

const EMPLOYEE = '33333333-3333-4333-8333-333333333333';
const ENTITY = '22222222-2222-4222-8222-222222222222';
const PACKAGE = '44444444-4444-4444-8444-444444444444';
const ACCOUNT = 'SYNTHACCT0099887766';

const provider = { code: 'RAWBANK', name: 'Rawbank' };
const view = {
  id: '55555555-5555-4555-8555-555555555555',
  version: 1,
  method: 'BANK_ACCOUNT',
  countryCode: 'CD',
  provider,
  maskedValue: '****7766',
  effectiveFrom: '2026-09-01',
  status: 'RECORDED',
  reasonCode: 'INITIAL_SETUP',
  recordedAt: '2026-09-02T08:00:00Z',
};
const problem = (code: string, status: number) => ({
  type: `https://docs.divalhr.com/errors/${code.toLowerCase()}`,
  title: code,
  status,
  code,
  correlationId: 'corr-synthetic',
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('payment pages (%s)', (locale) => {
  const c = resources[locale].common;

  it('shows an employee only their masked destination, read-only', async () => {
    stub(() => ({
      status: 200,
      body: {
        status: 'RECORDED',
        method: 'BANK_ACCOUNT',
        countryCode: 'CD',
        provider,
        maskedValue: '****7766',
        effectiveFrom: '2026-09-01',
      },
    }));
    const { container } = await renderWithSession(
      <MyPaymentDestinationPage />,
      sessionWithRoles(['employee']),
      locale,
    );
    expect(await screen.findByTestId('my-payment-masked')).toHaveTextContent('****7766');
    expect(screen.getByTestId('my-payment-request')).toHaveTextContent(c.payments.my.requestUpdate);
    expect(container.querySelector('input, select, textarea, button')).toBeNull();
    expect(container).not.toHaveTextContent(ACCOUNT);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('says so plainly when no destination is recorded', async () => {
    stub(() => ({
      status: 200,
      body: {
        status: 'NOT_RECORDED',
        method: null,
        countryCode: null,
        provider: null,
        maskedValue: null,
        effectiveFrom: null,
      },
    }));
    await renderWithSession(<MyPaymentDestinationPage />, sessionWithRoles(['employee']), locale);
    expect(await screen.findByTestId('my-payment-none')).toHaveTextContent(c.payments.my.none);
  });

  it('records a bank destination through preview and a confirmed commit, then forgets it', async () => {
    const requests = stub((r: Recorded): Reply => {
      if (r.method === 'GET' && r.path.endsWith('/history?limit=25')) {
        return { status: 200, body: { items: [view], nextCursor: null } };
      }
      if (r.method === 'GET') {
        return {
          status: 200,
          body: { employeeId: EMPLOYEE, latestVersion: 1, current: view, scheduled: null },
        };
      }
      if (r.path.endsWith('/preview')) {
        return {
          status: 200,
          body: {
            employeeId: EMPLOYEE,
            expectedVersion: 1,
            status: 'READY',
            blockers: [],
            warnings: ['DUPLICATE_DESTINATION'],
            method: 'BANK_ACCOUNT',
            countryCode: 'CD',
            provider,
            maskedValue: '****7766',
            effectiveFrom: '2026-10-01',
            reasonCode: 'ACCOUNT_CORRECTION',
            previewDigest: 'a'.repeat(64),
            expiresAt: '2026-10-19T10:30:00Z',
          },
        };
      }
      return { status: 201, body: { ...view, version: 2 } };
    });
    const user = userEvent.setup();
    const onChanged = vi.fn();
    const { container } = await renderWithSession(
      <EmployeePaymentSection employeeId={EMPLOYEE} onChanged={onChanged} />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    expect(await screen.findByTestId('payment-current')).toHaveTextContent('****7766');
    expect(container).not.toHaveTextContent(ACCOUNT);
    await user.click(screen.getByTestId('payment-open-form'));
    await user.selectOptions(screen.getByTestId('payment-method'), 'BANK_ACCOUNT');
    await user.selectOptions(screen.getByTestId('payment-provider'), 'RAWBANK');
    await user.type(screen.getByTestId('payment-holder'), 'Synthetic Holder');
    await user.type(screen.getByTestId('payment-account'), ACCOUNT);
    await user.type(screen.getByTestId('payment-effective'), '2026-10-01');
    await user.selectOptions(screen.getByTestId('payment-reason'), 'ACCOUNT_CORRECTION');
    await user.click(screen.getByTestId('payment-preview-button'));

    const preview = await screen.findByTestId('payment-preview');
    expect(within(preview).getByTestId('payment-preview-line')).toHaveTextContent('****7766');
    expect(preview).not.toHaveTextContent(ACCOUNT);
    expect(within(preview).getByTestId('payment-warnings')).toHaveTextContent(
      c.payments.destinationWarnings.DUPLICATE_DESTINATION,
    );
    // The commit stays disabled until the details are reviewed.
    expect(screen.getByTestId('payment-commit')).toBeDisabled();
    await user.click(screen.getByTestId('payment-reviewed'));
    expect(screen.getByTestId('payment-commit')).toBeEnabled();
    await user.click(screen.getByTestId('payment-commit'));

    await waitFor(() => {
      expect(onChanged).toHaveBeenCalledWith(c.payments.destination.recorded);
    });
    const commit = requests.find((r) => r.method === 'POST' && r.path.endsWith('/commit'));
    expect(commit?.key).toBeTruthy();
    const body = JSON.parse(commit?.body ?? '{}') as Record<string, unknown>;
    expect(body).toMatchObject({
      method: 'BANK_ACCOUNT',
      accountNumber: ACCOUNT,
      expectedVersion: 1,
      reviewedDetails: true,
      previewDigest: 'a'.repeat(64),
    });
    // After the command the sensitive values are gone from the page and from storage.
    expect(container).not.toHaveTextContent(ACCOUNT);
    expect(screen.queryByDisplayValue(ACCOUNT)).toBeNull();
    for (const store of [localStorage, sessionStorage]) {
      for (let i = 0; i < store.length; i++) {
        expect(store.getItem(store.key(i) ?? '') ?? '').not.toContain(ACCOUNT);
      }
    }
  });

  it('keeps the batch commit disabled until both confirmations are ticked', async () => {
    const lines = [
      {
        payslipId: 'a'.repeat(8) + '-0000-4000-8000-000000000001',
        employeeId: EMPLOYEE,
        employeeNumber: 'E-001',
        method: 'BANK_ACCOUNT',
        provider,
        maskedValue: '****7766',
        destinationVersion: 1,
        netMinor: 149040,
        issue: null,
      },
    ];
    const requests = stub((r: Recorded): Reply => {
      if (r.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      if (r.path.endsWith('/preview')) {
        return {
          status: 200,
          body: {
            legalEntityId: ENTITY,
            packageId: PACKAGE,
            packageVersion: 1,
            period: '2026-10',
            currency: 'CDF',
            packageStatus: 'APPROVED',
            status: 'READY',
            blockers: [],
            warnings: [],
            lineCount: 1,
            zeroNetExcludedCount: 0,
            methodCounts: { bankAccount: 1, mobileMoney: 0, manual: 0 },
            totalNetMinor: 149040,
            expectedVersion: 0,
            destinationsDigest: 'b'.repeat(64),
            previewDigest: 'c'.repeat(64),
            expiresAt: '2026-10-19T10:30:00Z',
            lines,
          },
        };
      }
      return { status: 201, body: {} };
    });
    const user = userEvent.setup();
    const onChanged = vi.fn();
    const { container } = await renderWithSession(
      <PaymentBatchPanel
        legalEntityId={ENTITY}
        packageId={PACKAGE}
        packageStatus="APPROVED"
        revision={0}
        onChanged={onChanged}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    await user.click(await screen.findByTestId('payment-batch-preview-button'));
    expect(await screen.findByTestId('payment-batch-line-count')).toHaveTextContent('1');
    expect(screen.getByTestId('payment-batch-total')).toHaveTextContent('149');
    expect(screen.getByTestId('payment-batch-preview-lines')).toHaveTextContent('****7766');
    expect(screen.getByTestId('payment-no-money')).toHaveTextContent(c.payments.noMoney);
    expect(screen.getByTestId('payment-batch-commit')).toBeDisabled();
    await user.click(screen.getByTestId('payment-batch-reviewed'));
    expect(screen.getByTestId('payment-batch-commit')).toBeDisabled();
    await user.click(screen.getByTestId('payment-batch-nomoney'));
    expect(screen.getByTestId('payment-batch-commit')).toBeEnabled();
    await user.click(screen.getByTestId('payment-batch-commit'));
    await waitFor(() => {
      expect(onChanged).toHaveBeenCalledWith(c.payments.batch.announcePrepared);
    });
    const commit = requests.find((r) => r.method === 'POST' && !r.path.endsWith('/preview'));
    expect(JSON.parse(commit?.body ?? '{}')).toMatchObject({
      expectedVersion: 0,
      packageVersion: 1,
      reviewedTotalsAndDestinations: true,
      noMoneySentAcknowledged: true,
    });
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('shows the blockers of a blocked batch and cannot be committed', async () => {
    stub((r: Recorded): Reply => {
      if (r.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      return {
        status: 200,
        body: {
          legalEntityId: ENTITY,
          packageId: PACKAGE,
          packageVersion: 1,
          period: '2026-10',
          currency: 'CDF',
          packageStatus: 'APPROVED',
          status: 'BLOCKED',
          blockers: ['MISSING_DESTINATION'],
          warnings: [],
          lineCount: 1,
          zeroNetExcludedCount: 0,
          methodCounts: { bankAccount: 0, mobileMoney: 0, manual: 0 },
          totalNetMinor: 149040,
          expectedVersion: 0,
          destinationsDigest: null,
          previewDigest: null,
          expiresAt: null,
          lines: [],
        },
      };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <PaymentBatchPanel
        legalEntityId={ENTITY}
        packageId={PACKAGE}
        packageStatus="APPROVED"
        revision={0}
        onChanged={vi.fn()}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    await user.click(await screen.findByTestId('payment-batch-preview-button'));
    expect(await screen.findByTestId('payment-batch-blockers')).toHaveTextContent(
      c.payments.batchBlockers.MISSING_DESTINATION,
    );
    expect(screen.getByTestId('payment-batch-commit')).toBeDisabled();
    expect(screen.getByTestId('payment-batch-why')).toHaveTextContent(c.payments.batch.whyBlocked);
  });

  it('maps a stale preview to a finished message and asks for a new review', async () => {
    stub((r: Recorded): Reply => {
      if (r.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      if (r.path.endsWith('/preview')) {
        return {
          status: 200,
          body: {
            legalEntityId: ENTITY,
            packageId: PACKAGE,
            packageVersion: 1,
            period: '2026-10',
            currency: 'CDF',
            packageStatus: 'APPROVED',
            status: 'READY',
            blockers: [],
            warnings: [],
            lineCount: 1,
            zeroNetExcludedCount: 0,
            methodCounts: { bankAccount: 1, mobileMoney: 0, manual: 0 },
            totalNetMinor: 1000,
            expectedVersion: 0,
            destinationsDigest: 'b'.repeat(64),
            previewDigest: 'c'.repeat(64),
            expiresAt: '2026-10-19T10:30:00Z',
            lines: [],
          },
        };
      }
      return { status: 409, body: problem('PAYMENT_BATCH_PREVIEW_STALE', 409) };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <PaymentBatchPanel
        legalEntityId={ENTITY}
        packageId={PACKAGE}
        packageStatus="APPROVED"
        revision={0}
        onChanged={vi.fn()}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    await user.click(await screen.findByTestId('payment-batch-preview-button'));
    await user.click(await screen.findByTestId('payment-batch-reviewed'));
    await user.click(screen.getByTestId('payment-batch-nomoney'));
    await user.click(screen.getByTestId('payment-batch-commit'));
    const failure = await screen.findByTestId('payment-batch-failure');
    expect(failure).toHaveAttribute('data-kind', 'stale');
    expect(failure).toHaveTextContent(c.errors.PAYMENT_BATCH_PREVIEW_STALE);
    expect(screen.getByTestId('payment-batch-stale')).toHaveTextContent(
      c.payments.batch.staleRefresh,
    );
    // The preview was discarded, so nothing can be committed on stale evidence.
    expect(screen.queryByTestId('payment-batch-commit')).toBeNull();
  });
});

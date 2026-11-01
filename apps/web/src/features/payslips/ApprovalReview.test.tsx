import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import {
  approvalLine,
  approvalPreview,
  approvalReceipt,
  CALC_ID,
  DIGEST,
  ENTITY_ID,
  EXPIRES,
  PACKAGE_ID,
  PREVIEW_DIGEST,
  problem,
  RUN_ID,
  cdf,
} from './fixtures';
import { ApprovalReviewPage } from './ApprovalReviewPage';
import { stub } from './stub';

const PREVIEW_PATH = `/legal-entities/${ENTITY_ID}/payroll-runs/${RUN_ID}/statutory-calculations/${CALC_ID}/approval/preview`;
const COMMIT_PATH = `/legal-entities/${ENTITY_ID}/payroll-runs/${RUN_ID}/statutory-calculations/${CALC_ID}/approval`;

async function show(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route
        path="/admin/payroll-runs/:legalEntityId/:runId/statutory/:calculationId/approval"
        element={<ApprovalReviewPage />}
      />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}/statutory/${CALC_ID}/approval`,
  );
}

const review = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await screen.findByTestId('approval-preview-button'));
  return screen.findByTestId('approval-preview');
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('payroll approval review (%s)', (locale) => {
  const c = resources[locale].common;

  it('shows the honest label, the pack statement, the synthetic badge and the totals', async () => {
    stub((r) =>
      r.path === PREVIEW_PATH ? { status: 200, body: approvalPreview() } : { status: 500 },
    );
    const user = userEvent.setup();
    await show(locale);
    expect(screen.getByTestId('package-label')).toHaveTextContent(c.payrollPackages.label);
    const preview = await review(user);
    expect(screen.getByTestId('synthetic-badge')).toHaveTextContent(
      c.payrollPackages.synthetic.badge,
    );
    expect(screen.getByTestId('synthetic-notice')).toBeInTheDocument();
    expect(within(preview).getByTestId('approval-pack')).toHaveTextContent(
      c.payrollPackages.rulePackStatement
        .replace('{{code}}', 'SYNTHETIC-TEST-1')
        .replace('{{version}}', '1'),
    );
    expect(within(preview).getByTestId('approval-run')).toHaveTextContent(RUN_ID);
    expect(within(preview).getByTestId('approval-calculation')).toHaveTextContent(CALC_ID);
    expect(within(preview).getByTestId('approval-count')).toHaveTextContent('2');
    for (const key of [
      'contractualGross',
      'employeeDeductions',
      'employerContributions',
      'approvedNet',
      'employerCost',
    ]) {
      expect(within(preview).getByTestId(`package-total-${key}`)).toHaveTextContent(/\d/u);
    }
    expect(within(preview).getByTestId('approval-warning')).toHaveTextContent(
      c.payrollPackages.warnings.SYNTHETIC_RULE_PACK,
    );
    expect(screen.queryByTestId('approval-same-preparer')).toBeNull();
    // Employer contributions are a separate column, never inside the net.
    const table = within(preview).getByTestId('approval-lines');
    expect(within(table).getAllByTestId('approval-line')).toHaveLength(2);
    expect(
      within(table).getByRole('columnheader', { name: c.payrollPackages.lines.employerSeparate }),
    ).toBeInTheDocument();
    expect(preview).toHaveTextContent(c.payrollPackages.lines.employerNote);
  });

  it('shows a prominent same-preparer warning when the preparer is the approver', async () => {
    stub(() => ({
      status: 200,
      body: approvalPreview({
        samePreparer: true,
        warnings: ['SAME_PREPARER_AND_APPROVER', 'SYNTHETIC_RULE_PACK'],
      }),
    }));
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    expect(screen.getByTestId('approval-same-preparer')).toHaveTextContent(
      c.payrollPackages.samePreparer.warning,
    );
    expect(screen.getByTestId('approval-same-preparer')).toHaveAttribute('role', 'alert');
  });

  it('does not badge a real pack as synthetic', async () => {
    stub(() => ({
      status: 200,
      body: approvalPreview({
        pack: { packId: 'p', code: 'CD-SALARY-PREVIEW', version: 2, synthetic: false },
        warnings: [],
      }),
    }));
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    expect(screen.queryByTestId('synthetic-badge')).toBeNull();
    expect(screen.getByTestId('approval-pack')).toHaveTextContent('CD-SALARY-PREVIEW');
  });

  it('blocks approval with stable-code messages and a visible reason', async () => {
    stub(() => ({
      status: 200,
      body: approvalPreview({
        status: 'BLOCKED',
        blockers: ['RULE_PACK_NOT_VERIFIED', 'POLICY_CHANGED'],
        previewDigest: null,
        expiresAt: null,
      }),
    }));
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    const blockers = screen.getAllByTestId('approval-blocker');
    expect(blockers.map((item) => item.textContent)).toEqual([
      c.payrollPackages.blockers.RULE_PACK_NOT_VERIFIED,
      c.payrollPackages.blockers.POLICY_CHANGED,
    ]);
    const commit = screen.getByTestId('approval-commit');
    expect(commit).toBeDisabled();
    expect(screen.getByTestId('approval-commit-why')).toHaveTextContent(
      c.payrollPackages.review.whyBlocked,
    );
    expect(commit).toHaveAccessibleDescription(c.payrollPackages.review.whyBlocked);
    expect(screen.getByTestId('approval-confirm-totals')).toBeDisabled();
  });

  it('offers the existing package when nothing changed', async () => {
    stub(() => ({
      status: 200,
      body: approvalPreview({
        status: 'NO_CHANGE',
        previewDigest: null,
        expiresAt: null,
        existingPackage: {
          packageId: PACKAGE_ID,
          version: 4,
        },
      }),
    }));
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    expect(screen.getByTestId('approval-existing-link')).toHaveAttribute(
      'href',
      `/admin/payroll-packages/${ENTITY_ID}/${PACKAGE_ID}`,
    );
    expect(screen.getByTestId('approval-commit-why')).toHaveTextContent(
      c.payrollPackages.review.whyNoChange,
    );
  });

  it('requires all three confirmations, then sends exactly the previewed evidence', async () => {
    const requests = stub((r) => {
      if (r.path === PREVIEW_PATH) return { status: 200, body: approvalPreview() };
      if (r.path === COMMIT_PATH) return { status: 201, body: approvalReceipt() };
      return { status: 500 };
    });
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    const commit = screen.getByTestId('approval-commit');
    expect(commit).toBeDisabled();
    expect(screen.getByTestId('approval-commit-why')).toHaveTextContent(
      c.payrollPackages.review.whyConfirm,
    );
    await user.click(screen.getByTestId('approval-confirm-totals'));
    await user.click(screen.getByTestId('approval-confirm-exceptions'));
    expect(commit).toBeDisabled();
    await user.click(screen.getByTestId('approval-confirm-noPayment'));
    expect(commit).toBeEnabled();
    expect(screen.queryByTestId('approval-commit-why')).toBeNull();
    await user.click(commit);
    const receipt = await screen.findByTestId('approval-receipt');
    const post = requests.find((r) => r.path === COMMIT_PATH);
    expect(post?.key).toMatch(/^web-/u);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      expectedVersion: 0,
      sourceRunDigest: DIGEST,
      calculationDigest: 'b'.repeat(64),
      profileDigest: 'c'.repeat(64),
      rulePackDigest: 'd'.repeat(64),
      previewDigest: PREVIEW_DIGEST,
      expiresAt: EXPIRES,
      reviewedTotals: true,
      reviewedExceptions: true,
      understandsNoPayment: true,
    });
    // The receipt carries no amount and the honest wording.
    expect(receipt).toHaveTextContent(c.payrollPackages.receipt.title);
    expect(receipt).toHaveTextContent(c.payrollPackages.notProofOfPayment);
    expect(receipt.textContent).not.toMatch(/1[\s.,\u202f\u00a0]?500[\s.,\u202f\u00a0]?000/u);
    expect(within(receipt).getByTestId('approval-receipt-count')).toHaveTextContent('2');
    expect(within(receipt).getByTestId('approval-receipt-pack')).toHaveTextContent(
      'SYNTHETIC-TEST-1',
    );
    expect(within(receipt).getByTestId('approval-receipt-open')).toHaveAttribute(
      'href',
      `/admin/payroll-packages/${ENTITY_ID}/${PACKAGE_ID}`,
    );
    expect(screen.queryByTestId('approval-preview')).toBeNull();
  });

  it('on a stale answer discards the review and the confirmations and asks for a new review', async () => {
    let calls = 0;
    stub((r) => {
      if (r.path === PREVIEW_PATH) return { status: 200, body: approvalPreview() };
      if (r.path === COMMIT_PATH) {
        calls += 1;
        return { status: 409, body: problem('PAYROLL_APPROVAL_PREVIEW_STALE', 409) };
      }
      return { status: 500 };
    });
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    for (const key of ['totals', 'exceptions', 'noPayment']) {
      await user.click(screen.getByTestId(`approval-confirm-${key}`));
    }
    await user.click(screen.getByTestId('approval-commit'));
    const failure = await screen.findByTestId('approval-failure');
    expect(failure).toHaveAttribute('data-kind', 'stale');
    expect(failure).toHaveTextContent(c.errors.PAYROLL_APPROVAL_PREVIEW_STALE);
    expect(calls).toBe(1);
    expect(screen.queryByTestId('approval-preview')).toBeNull();
    expect(screen.getByTestId('approval-stale-refresh')).toHaveTextContent(
      c.payrollPackages.review.staleRefresh,
    );
    expect(screen.getByTestId('approval-preview-button')).toHaveTextContent(
      c.payrollPackages.review.previewAgain,
    );
    // A new review starts with all confirmations cleared.
    await user.click(screen.getByTestId('approval-preview-button'));
    await screen.findByTestId('approval-preview');
    expect(screen.getByTestId('approval-confirm-totals')).not.toBeChecked();
    expect(screen.getByTestId('approval-commit')).toBeDisabled();
  });

  it.each([
    ['PAYROLL_PACKAGE_BLOCKED', 409, 'domain'],
    ['PAYROLL_APPROVAL_NO_CHANGE', 409, 'stale'],
    ['PAYROLL_PACKAGE_VERSION_CONFLICT', 409, 'stale'],
    ['PAYROLL_PACKAGE_ALREADY_APPROVED', 409, 'stale'],
    ['PAYROLL_PACKAGE_LIMIT_REACHED', 409, 'domain'],
    ['STATUTORY_CALCULATION_NOT_FOUND', 404, 'notFound'],
    ['MFA_REQUIRED', 403, 'forbidden'],
    ['RATE_LIMITED', 429, 'rateLimited'],
  ] as const)('maps %s to a finished message', async (code, status, kind) => {
    stub(() => ({ status, body: problem(code, status) }));
    const user = userEvent.setup();
    await show(locale);
    await user.click(await screen.findByTestId('approval-preview-button'));
    const failure = await screen.findByTestId('approval-failure');
    expect(failure).toHaveAttribute('data-kind', kind);
    expect(failure).toHaveTextContent(c.errors[code]);
    expect(failure).not.toHaveTextContent(code);
    expect(failure).toHaveTextContent('corr-pay-4242');
  });

  it('shows more lines on request without calculating anything', async () => {
    const lines = Array.from({ length: 130 }, (_, index) =>
      approvalLine({
        employeeId: `22222222-0000-4000-8000-${String(index).padStart(12, '0')}`,
        employeeNumber: `N-${String(index)}`,
        approvedNet: cdf('1'),
      }),
    );
    stub(() => ({ status: 200, body: approvalPreview({ lines, payslipCount: 130 }) }));
    const user = userEvent.setup();
    await show(locale);
    await review(user);
    expect(screen.getAllByTestId('approval-line')).toHaveLength(100);
    await user.click(screen.getByTestId('approval-lines-more'));
    expect(screen.getAllByTestId('approval-line')).toHaveLength(130);
    expect(screen.queryByTestId('approval-lines-more')).toBeNull();
  });

  it('is accessible and uses no payment claim', async () => {
    stub(() => ({ status: 200, body: approvalPreview() }));
    const user = userEvent.setup();
    const { container } = await show(locale);
    await review(user);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
    expect(container.textContent).not.toMatch(
      locale === 'en'
        ? /\bpaid\b|payment completed|\bfiled\b/iu
        : /\bpayée?s?\b|paiement effectué/iu,
    );
  });
});

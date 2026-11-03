import { resources } from '@divalhr/localization';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { PaymentSubmissionPanel } from './PaymentSubmissionPanel';

const ENTITY = '22222222-2222-4222-8222-222222222222';
const BATCH = '66666666-6666-4666-8666-666666666666';
const SENTINEL_ACCOUNT = 'SYNTHACCT0099887766';

const problem = (code: string, status: number) => ({
  type: `https://docs.divalhr.com/errors/${code.toLowerCase()}`,
  title: code,
  status,
  code,
  correlationId: 'corr-synthetic',
});
const provider = { code: 'SYNTHETIC-TEST', name: 'SYNTHETIC-TEST', synthetic: true };
const preview = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY,
  batchId: BATCH,
  batchVersion: 1,
  batchDigest: 'a'.repeat(64),
  provider,
  status: 'READY',
  blockers: [],
  currency: 'CDF',
  lineCount: 3,
  methodCounts: { bankAccount: 2, mobileMoney: 1, manual: 0 },
  totalNetMinor: 450000,
  previewDigest: 'b'.repeat(64),
  expiresAt: '2026-10-19T10:30:00Z',
  ...overrides,
});
const submission = (state: string, overrides: Record<string, unknown> = {}) => ({
  id: '77777777-7777-4777-8777-777777777777',
  batchId: BATCH,
  legalEntityId: ENTITY,
  provider,
  state,
  version: 2,
  batchVersion: 1,
  currency: 'CDF',
  lineCount: 3,
  methodCounts: { bankAccount: 2, mobileMoney: 1, manual: 0 },
  totalNetMinor: 450000,
  attemptCount: 1,
  outcomeCode: null,
  providerReference: null,
  nextAttemptAt: null,
  createdAt: '2026-10-19T10:00:00Z',
  updatedAt: '2026-10-19T10:01:00Z',
  ...overrides,
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('payment submission panel (%s)', (locale) => {
  const c = resources[locale].common;

  it('reviews, needs both confirmations, then sends exactly what the preview returned', async () => {
    let created = false;
    const requests = stub((r: Recorded): Reply => {
      if (r.method === 'GET' && r.path.endsWith('/submission')) {
        return created
          ? { status: 200, body: submission('QUEUED', { attemptCount: 0 }) }
          : { status: 404, body: problem('PAYMENT_SUBMISSION_NOT_FOUND', 404) };
      }
      if (r.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      if (r.path.endsWith('/submissions/preview')) return { status: 200, body: preview() };
      created = true;
      return {
        status: 201,
        body: {
          submissionId: '77777777-7777-4777-8777-777777777777',
          batchId: BATCH,
          legalEntityId: ENTITY,
          providerCode: 'SYNTHETIC-TEST',
          state: 'QUEUED',
          version: 1,
          createdAt: '2026-10-19T10:00:00Z',
        },
      };
    });
    const user = userEvent.setup();
    const known = vi.fn();
    const { container } = await renderWithSession(
      <PaymentSubmissionPanel legalEntityId={ENTITY} batchId={BATCH} prepared onKnown={known} />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    expect(await screen.findByTestId('submission-none')).toHaveTextContent(
      c.paymentSubmission.none,
    );
    await waitFor(() => {
      expect(known).toHaveBeenCalledWith(false);
    });
    await user.click(screen.getByTestId('submission-review-button'));
    expect(await screen.findByTestId('submission-preview-lines')).toHaveTextContent('3');
    expect(screen.getByTestId('submission-synthetic-badge')).toHaveTextContent('SYNTHETIC-TEST');
    expect(screen.getByTestId('submission-synthetic-help')).toHaveTextContent(
      c.paymentSubmission.syntheticHelp,
    );
    expect(screen.getByTestId('submission-preview-notice')).toHaveTextContent(
      c.paymentSubmission.notice,
    );
    expect(screen.getByTestId('submission-commit')).toBeDisabled();
    await user.click(screen.getByTestId('submission-ack-reviewed'));
    expect(screen.getByTestId('submission-commit')).toBeDisabled();
    await user.click(screen.getByTestId('submission-ack-understand'));
    expect(screen.getByTestId('submission-commit')).toBeEnabled();
    await user.click(screen.getByTestId('submission-commit'));
    expect(await screen.findByTestId('submission-state-badge')).toHaveTextContent(
      c.paymentSubmission.state.QUEUED,
    );
    const commit = requests.find((r) => r.method === 'POST' && !r.path.endsWith('/preview'));
    expect(commit?.key).toMatch(/^web-/u);
    expect(JSON.parse(commit?.body ?? '{}')).toEqual({
      expectedBatchVersion: 1,
      batchDigest: 'a'.repeat(64),
      previewDigest: 'b'.repeat(64),
      expiresAt: '2026-10-19T10:30:00Z',
      reviewedProviderAndTotals: true,
      understandAcceptedIsNotPaid: true,
    });
    expect(screen.getByTestId('submission-notice')).toHaveTextContent(c.paymentSubmission.notice);
    expect(container).not.toHaveTextContent(SENTINEL_ACCOUNT);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('lists the blockers of a blocked review and cannot be committed', async () => {
    stub((r: Recorded): Reply => {
      if (r.method === 'GET' && r.path.endsWith('/submission')) {
        return { status: 404, body: problem('PAYMENT_SUBMISSION_NOT_FOUND', 404) };
      }
      return {
        status: 200,
        body: preview({
          status: 'BLOCKED',
          provider: null,
          blockers: ['PROVIDER_NOT_AVAILABLE', 'DESTINATION_UNREADABLE'],
          previewDigest: null,
          expiresAt: null,
        }),
      };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <PaymentSubmissionPanel
        legalEntityId={ENTITY}
        batchId={BATCH}
        prepared
        onKnown={() => undefined}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    await user.click(await screen.findByTestId('submission-review-button'));
    const blockers = await screen.findByTestId('submission-blockers');
    expect(blockers).toHaveTextContent(c.paymentSubmission.blockers.PROVIDER_NOT_AVAILABLE);
    expect(blockers).toHaveTextContent(c.paymentSubmission.blockers.DESTINATION_UNREADABLE);
    expect(screen.getByTestId('submission-commit')).toBeDisabled();
    expect(screen.getByTestId('submission-ack-reviewed')).toBeDisabled();
  });

  it.each([
    ['ACCEPTED_FOR_PROCESSING', 'ACCEPTED', 'SYNTH-REF-000123'],
    ['REJECTED', 'REJECTED_SIGNATURE', null],
    ['DELIVERY_UNKNOWN', 'UNKNOWN_TIMEOUT', null],
    ['MANUAL_INTERVENTION', 'RETRIES_EXHAUSTED', null],
  ] as const)(
    'shows the %s state with its evidence and the fixed notice',
    async (state, outcome, reference) => {
      stub((r: Recorded): Reply => {
        if (r.method === 'GET' && r.path.endsWith('/submission')) {
          return {
            status: 200,
            body: submission(state, { outcomeCode: outcome, providerReference: reference }),
          };
        }
        if (r.path.includes('/submission/reconciliation')) {
          return { status: 404, body: problem('PAYMENT_RECONCILIATION_NOT_FOUND', 404) };
        }
        return {
          status: 200,
          body: {
            items: [
              {
                sequence: 2,
                attemptNumber: 1,
                state,
                outcomeCode: outcome,
                providerReference: reference,
                occurredAt: '2026-10-19T10:01:00Z',
              },
              {
                sequence: 1,
                attemptNumber: 0,
                state: 'QUEUED',
                outcomeCode: null,
                providerReference: null,
                occurredAt: '2026-10-19T10:00:00Z',
              },
            ],
            nextCursor: null,
          },
        };
      });
      const { container } = await renderWithSession(
        <PaymentSubmissionPanel
          legalEntityId={ENTITY}
          batchId={BATCH}
          prepared
          onKnown={() => undefined}
        />,
        sessionWithRoles(['tenant-admin']),
        locale,
      );
      expect(await screen.findByTestId('submission-state-badge')).toHaveTextContent(
        c.paymentSubmission.state[state],
      );
      expect(screen.getByTestId('submission-state-help')).toHaveTextContent(
        c.paymentSubmission.stateHelp[state],
      );
      expect(screen.getByTestId('submission-outcome')).toHaveTextContent(
        c.paymentSubmission.outcomeLabel[outcome],
      );
      expect(screen.getByTestId('submission-notice')).toHaveTextContent(c.paymentSubmission.notice);
      if (reference) {
        expect(screen.getByTestId('submission-reference')).toHaveTextContent(reference);
      } else {
        expect(screen.queryByTestId('submission-reference')).toBeNull();
      }
      expect(await screen.findAllByTestId('submission-attempt')).toHaveLength(2);
      expect(screen.queryByTestId('submission-review-button')).toBeNull();
      await waitFor(async () => {
        const result = await axe.run(container);
        expect(result.violations.map((v) => v.id)).toEqual([]);
      });
    },
  );

  it('discards a stale review and reloads the state', async () => {
    let step = 0;
    stub((r: Recorded): Reply => {
      if (r.method === 'GET' && r.path.endsWith('/submission')) {
        return step < 2
          ? { status: 404, body: problem('PAYMENT_SUBMISSION_NOT_FOUND', 404) }
          : { status: 200, body: submission('QUEUED') };
      }
      if (r.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      if (r.path.endsWith('/submissions/preview')) {
        step = 1;
        return { status: 200, body: preview() };
      }
      step = 2;
      return { status: 409, body: problem('PAYMENT_SUBMISSION_EXISTS', 409) };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <PaymentSubmissionPanel
        legalEntityId={ENTITY}
        batchId={BATCH}
        prepared
        onKnown={() => undefined}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    await user.click(await screen.findByTestId('submission-review-button'));
    await user.click(await screen.findByTestId('submission-ack-reviewed'));
    await user.click(screen.getByTestId('submission-ack-understand'));
    await user.click(screen.getByTestId('submission-commit'));
    expect(await screen.findByTestId('submission-state-badge')).toBeInTheDocument();
    expect(screen.queryByTestId('submission-preview')).toBeNull();
  });
});

import { resources } from '@divalhr/localization';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { PaymentReconciliationPanel } from './PaymentReconciliationPanel';
import { PaymentSubmissionPanel } from './PaymentSubmissionPanel';
import { RECONCILIATION_OUTCOMES, RECONCILIATION_STATES } from './payments';

const ENTITY = '22222222-2222-4222-8222-222222222222';
const BATCH = '66666666-6666-4666-8666-666666666666';
const SUBMISSION = '77777777-7777-4777-8777-777777777777';
const RECONCILIATION = '88888888-8888-4888-8888-888888888888';
const SENTINEL_ACCOUNT = 'SYNTHACCT0099887766';
const SENTINEL_REQUEST = '123e4567-e89b-42d3-a456-426614174000';

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
  submissionId: SUBMISSION,
  submissionState: 'DELIVERY_UNKNOWN',
  submissionVersion: 3,
  submissionOutcomeCode: 'UNKNOWN_TIMEOUT',
  provider,
  requestFingerprint: 'abcdef012345',
  previous: null,
  status: 'READY',
  blockers: [],
  previewDigest: 'c'.repeat(64),
  expiresAt: '2026-10-19T10:30:00Z',
  ...overrides,
});
const reconciliation = (state: string, overrides: Record<string, unknown> = {}) => ({
  id: RECONCILIATION,
  submissionId: SUBMISSION,
  batchId: BATCH,
  legalEntityId: ENTITY,
  provider,
  state,
  version: 2,
  submissionState: 'DELIVERY_UNKNOWN',
  requestFingerprint: 'abcdef012345',
  attemptCount: 1,
  outcomeCode: null,
  nextAttemptAt: null,
  createdAt: '2026-10-19T10:00:00Z',
  updatedAt: '2026-10-19T10:01:00Z',
  ...overrides,
});
const inquiry = (sequence: number, state: string, outcomeCode: string | null, auth = false) => ({
  sequence,
  attemptNumber: sequence - 1,
  state,
  outcomeCode,
  authenticated: auth,
  occurredAt: `2026-10-19T10:${String(sequence).padStart(2, '0')}:00Z`,
});
const isReconciliationGet = (r: Recorded) =>
  r.method === 'GET' && r.path.endsWith('/submission/reconciliation');
const isInquiriesGet = (r: Recorded) =>
  r.method === 'GET' && r.path.includes('/submission/reconciliation/inquiries');
const panel = <PaymentReconciliationPanel legalEntityId={ENTITY} batchId={BATCH} />;

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('payment reconciliation panel (%s)', (locale) => {
  const c = resources[locale].common;
  const rc = c.paymentReconciliation;

  it('previews, needs both confirmations, sends a fresh idempotency key and exactly the preview', async () => {
    let created = false;
    const requests = stub((r: Recorded): Reply => {
      if (isReconciliationGet(r)) {
        return created
          ? { status: 200, body: reconciliation('OPEN', { version: 1, attemptCount: 0 }) }
          : { status: 404, body: problem('PAYMENT_RECONCILIATION_NOT_FOUND', 404) };
      }
      if (isInquiriesGet(r)) {
        return { status: 200, body: { items: [inquiry(1, 'OPEN', null)], nextCursor: null } };
      }
      if (r.path.endsWith('/reconciliation/preview')) return { status: 200, body: preview() };
      created = true;
      return {
        status: 201,
        body: {
          reconciliationId: RECONCILIATION,
          submissionId: SUBMISSION,
          batchId: BATCH,
          legalEntityId: ENTITY,
          providerCode: 'SYNTHETIC-TEST',
          state: 'OPEN',
          version: 1,
          createdAt: '2026-10-19T10:00:00Z',
        },
      };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      panel,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    expect(await screen.findByTestId('reconciliation-none')).toHaveTextContent(rc.none);
    await user.click(screen.getByTestId('reconciliation-open'));
    const dialog = await screen.findByTestId('reconciliation-dialog');
    expect(screen.getByTestId('reconciliation-open')).toHaveTextContent(rc.open);
    expect(dialog).toHaveTextContent('SYNTHETIC-TEST');
    expect(screen.getByTestId('reconciliation-fingerprint')).toHaveTextContent('abcdef012345');
    expect(dialog).toHaveTextContent(c.paymentSubmission.state.DELIVERY_UNKNOWN);
    expect(dialog).toHaveTextContent(c.paymentSubmission.outcomeLabel.UNKNOWN_TIMEOUT);
    expect(screen.getByTestId('reconciliation-warning')).toHaveTextContent(rc.notice);
    expect(screen.getByTestId('reconciliation-confirm')).toBeDisabled();
    await user.click(screen.getByTestId('reconciliation-ack-no-resend'));
    expect(screen.getByTestId('reconciliation-confirm')).toBeDisabled();
    await user.click(screen.getByTestId('reconciliation-ack-not-paid'));
    expect(screen.getByTestId('reconciliation-confirm')).toBeEnabled();
    await user.click(screen.getByTestId('reconciliation-confirm'));
    expect(await screen.findByTestId('reconciliation-state')).toHaveTextContent(rc.stateLabel.OPEN);
    const commit = requests.find((r) => r.method === 'POST' && !r.path.endsWith('/preview'));
    expect(commit?.key).toMatch(/^web-/u);
    expect(JSON.parse(commit?.body ?? '{}')).toEqual({
      expectedSubmissionVersion: 3,
      expectedSubmissionState: 'DELIVERY_UNKNOWN',
      previewDigest: 'c'.repeat(64),
      expiresAt: '2026-10-19T10:30:00Z',
      understandInquiryDoesNotResend: true,
      understandAcceptedIsNotPaid: true,
    });
    expect(screen.queryByTestId('reconciliation-dialog')).toBeNull();
    expect(screen.getByTestId('reconciliation-warning')).toHaveTextContent(rc.notice);
    expect(screen.getByTestId('reconciliation-history-warning')).toHaveTextContent(rc.notice);
    expect(container).not.toHaveTextContent(SENTINEL_ACCOUNT);
    expect(container).not.toHaveTextContent(SENTINEL_REQUEST);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('lists the blockers of a blocked review and cannot be confirmed', async () => {
    stub((r: Recorded): Reply => {
      if (isReconciliationGet(r)) {
        return { status: 404, body: problem('PAYMENT_RECONCILIATION_NOT_FOUND', 404) };
      }
      return {
        status: 200,
        body: preview({
          status: 'BLOCKED',
          provider: null,
          blockers: ['PROVIDER_NOT_AVAILABLE', 'SUBMISSION_NOT_ELIGIBLE'],
          previewDigest: null,
          expiresAt: null,
          previous: {
            state: 'STILL_UNRESOLVED',
            outcomeCode: 'PENDING',
            updatedAt: '2026-10-19T10:00:00Z',
          },
        }),
      };
    });
    const user = userEvent.setup();
    await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    await user.click(await screen.findByTestId('reconciliation-open'));
    const blockers = await screen.findByTestId('reconciliation-blockers');
    expect(blockers).toHaveTextContent(rc.blockers.PROVIDER_NOT_AVAILABLE);
    expect(blockers).toHaveTextContent(rc.blockers.SUBMISSION_NOT_ELIGIBLE);
    expect(screen.getByTestId('reconciliation-preview-previous')).toHaveTextContent(
      rc.stateLabel.STILL_UNRESOLVED,
    );
    expect(screen.getByTestId('reconciliation-warning')).toHaveTextContent(rc.notice);
    expect(screen.getByTestId('reconciliation-confirm')).toBeDisabled();
    expect(screen.getByTestId('reconciliation-ack-no-resend')).toBeDisabled();
    expect(screen.getByTestId('reconciliation-ack-not-paid')).toBeDisabled();
  });

  it.each(RECONCILIATION_STATES)('shows the %s state with the fixed warning', async (state) => {
    const outcome =
      state === 'CONFIRMED_ACCEPTED_FOR_PROCESSING'
        ? 'ACCEPTED_FOR_PROCESSING'
        : state === 'CONFIRMED_REJECTED'
          ? 'REJECTED_UNSUPPORTED'
          : state === 'STILL_UNRESOLVED'
            ? 'PENDING'
            : state === 'MANUAL_REVIEW_REQUIRED'
              ? 'RETRIES_EXHAUSTED'
              : null;
    stub((r: Recorded): Reply => {
      if (isReconciliationGet(r)) {
        return {
          status: 200,
          body: reconciliation(state, {
            outcomeCode: outcome,
            nextAttemptAt: state === 'OPEN' ? '2026-10-19T10:05:00Z' : null,
          }),
        };
      }
      return {
        status: 200,
        body: {
          items: [inquiry(2, state, outcome, true), inquiry(1, 'OPEN', null)],
          nextCursor: null,
        },
      };
    });
    const { container } = await renderWithSession(
      panel,
      sessionWithRoles(['tenant-admin']),
      locale,
    );
    expect(await screen.findByTestId('reconciliation-state')).toHaveTextContent(
      rc.stateLabel[state],
    );
    expect(screen.getByTestId('reconciliation-state-help')).toHaveTextContent(rc.stateHelp[state]);
    expect(screen.getByTestId('reconciliation-warning')).toHaveTextContent(rc.notice);
    expect(screen.getByTestId('reconciliation-history-warning')).toHaveTextContent(rc.notice);
    expect(screen.getByTestId('reconciliation-submission-state')).toHaveTextContent(
      c.paymentSubmission.state.DELIVERY_UNKNOWN,
    );
    if (outcome) {
      expect(screen.getByTestId('reconciliation-outcome')).toHaveTextContent(
        rc.outcomeLabel[outcome],
      );
    } else {
      expect(screen.queryByTestId('reconciliation-outcome')).toBeNull();
    }
    expect(screen.queryByTestId('reconciliation-next-attempt') !== null).toBe(state === 'OPEN');
    const guided = state === 'STILL_UNRESOLVED' || state === 'MANUAL_REVIEW_REQUIRED';
    if (guided) {
      expect(screen.getByTestId('reconciliation-guidance')).toHaveTextContent(rc.guidance[state]);
    } else {
      expect(screen.queryByTestId('reconciliation-guidance')).toBeNull();
    }
    expect(await screen.findAllByTestId('reconciliation-inquiry')).toHaveLength(2);
    expect(screen.queryByTestId('reconciliation-open')).toBeNull();
    // The state and outcome elements never say paid or settled.
    const status = screen.getByTestId('reconciliation-state').textContent;
    expect(status).not.toMatch(/\bpaid\b|\bsettled\b|payé|réglé/iu);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('translates all 17 outcome codes in the card and the history', async () => {
    for (const outcome of RECONCILIATION_OUTCOMES) {
      expect(rc.outcomeLabel[outcome]).toBeTruthy();
    }
    stub((r: Recorded): Reply =>
      isReconciliationGet(r)
        ? { status: 200, body: reconciliation('STILL_UNRESOLVED', { outcomeCode: 'UNKNOWN' }) }
        : {
            status: 200,
            body: {
              items: RECONCILIATION_OUTCOMES.map((o, i) => inquiry(i + 1, 'OPEN', o)),
              nextCursor: null,
            },
          },
    );
    await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    const rows = await screen.findAllByTestId('reconciliation-inquiry');
    expect(rows).toHaveLength(17);
    RECONCILIATION_OUTCOMES.forEach((outcome, index) => {
      expect(rows[index]).toHaveTextContent(rc.outcomeLabel[outcome]);
    });
  });

  it('pages the history with a keyset cursor and keeps the order', async () => {
    const requests = stub((r: Recorded): Reply => {
      if (isReconciliationGet(r)) return { status: 200, body: reconciliation('STILL_UNRESOLVED') };
      if (r.path.includes('cursor=next-1')) {
        return { status: 200, body: { items: [inquiry(1, 'OPEN', null)], nextCursor: null } };
      }
      return {
        status: 200,
        body: {
          items: [
            inquiry(3, 'STILL_UNRESOLVED', 'PENDING', true),
            inquiry(2, 'INQUIRY_IN_PROGRESS', null),
          ],
          nextCursor: 'next-1',
        },
      };
    });
    const user = userEvent.setup();
    await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findAllByTestId('reconciliation-inquiry')).toHaveLength(2);
    await user.click(screen.getByTestId('reconciliation-history-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('reconciliation-inquiry')).toHaveLength(3);
    });
    expect(screen.queryByTestId('reconciliation-history-more')).toBeNull();
    expect(requests.some((r) => r.path.includes('cursor=next-1'))).toBe(true);
    expect(screen.getByTestId('reconciliation-history')).toHaveTextContent(rc.history.yes);
  });

  it('shows empty and error history states', async () => {
    stub((r: Recorded): Reply =>
      isReconciliationGet(r)
        ? { status: 200, body: reconciliation('OPEN') }
        : { status: 200, body: { items: [], nextCursor: null } },
    );
    const first = await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findByTestId('reconciliation-history-empty')).toHaveTextContent(
      rc.history.empty,
    );
    first.unmount();
    stub((r: Recorded): Reply =>
      isReconciliationGet(r)
        ? { status: 200, body: reconciliation('OPEN') }
        : { status: 500, body: problem('INTERNAL_ERROR', 500) },
    );
    await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findByTestId('reconciliation-history-error')).toHaveTextContent(
      rc.history.error,
    );
  });

  it('shows a finished message when the investigation cannot be loaded', async () => {
    stub((): Reply => ({ status: 503, body: problem('PAYMENT_SUBMISSION_KEY_UNAVAILABLE', 503) }));
    await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findByTestId('reconciliation-load-failure')).toHaveTextContent(
      c.errors.PAYMENT_SUBMISSION_KEY_UNAVAILABLE,
    );
  });

  it('discards a stale review, shows the finished message and reloads', async () => {
    let step = 0;
    stub((r: Recorded): Reply => {
      if (isReconciliationGet(r)) {
        return step < 2
          ? { status: 404, body: problem('PAYMENT_RECONCILIATION_NOT_FOUND', 404) }
          : { status: 200, body: reconciliation('OPEN') };
      }
      if (isInquiriesGet(r)) return { status: 200, body: { items: [], nextCursor: null } };
      if (r.path.endsWith('/reconciliation/preview')) {
        step = 1;
        return { status: 200, body: preview() };
      }
      step = 2;
      return { status: 409, body: problem('PAYMENT_RECONCILIATION_EXISTS', 409) };
    });
    const user = userEvent.setup();
    await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    await user.click(await screen.findByTestId('reconciliation-open'));
    await user.click(await screen.findByTestId('reconciliation-ack-no-resend'));
    await user.click(screen.getByTestId('reconciliation-ack-not-paid'));
    await user.click(screen.getByTestId('reconciliation-confirm'));
    expect(await screen.findByTestId('reconciliation-state')).toBeInTheDocument();
    expect(screen.queryByTestId('reconciliation-dialog')).toBeNull();
  });

  it('refreshes while running, stops at a conclusion and cleans up on unmount', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    let reads = 0;
    stub((r: Recorded): Reply => {
      if (isReconciliationGet(r)) {
        reads += 1;
        return {
          status: 200,
          body:
            reads < 2
              ? reconciliation('INQUIRY_IN_PROGRESS')
              : reconciliation('CONFIRMED_ACCEPTED_FOR_PROCESSING', {
                  version: 3,
                  outcomeCode: 'ACCEPTED_FOR_PROCESSING',
                }),
        };
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const view = await renderWithSession(panel, sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findByTestId('reconciliation-state')).toHaveTextContent(
      rc.stateLabel.INQUIRY_IN_PROGRESS,
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4100);
    });
    await waitFor(() => {
      expect(screen.getByTestId('reconciliation-state')).toHaveTextContent(
        rc.stateLabel.CONFIRMED_ACCEPTED_FOR_PROCESSING,
      );
    });
    const settled = reads;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(20000);
    });
    expect(reads).toBe(settled);
    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('reconciliation inside the submission panel', () => {
  const submission = (state: string) => ({
    id: SUBMISSION,
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
  });

  it.each(['DELIVERY_UNKNOWN', 'MANUAL_INTERVENTION'])('is shown for %s', async (state) => {
    stub((r: Recorded): Reply => {
      if (r.method === 'GET' && r.path.endsWith('/submission')) {
        return { status: 200, body: submission(state) };
      }
      if (isReconciliationGet(r)) {
        return { status: 404, body: problem('PAYMENT_RECONCILIATION_NOT_FOUND', 404) };
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    await renderWithSession(
      <PaymentSubmissionPanel
        legalEntityId={ENTITY}
        batchId={BATCH}
        prepared
        onKnown={() => undefined}
      />,
      sessionWithRoles(['tenant-admin']),
      'en',
    );
    expect(await screen.findByTestId('reconciliation-open')).toHaveTextContent(
      'Investigate delivery',
    );
  });

  it.each(['QUEUED', 'SENDING', 'ACCEPTED_FOR_PROCESSING', 'REJECTED'])(
    'is not shown and not requested for %s',
    async (state) => {
      const requests = stub((r: Recorded): Reply => {
        if (r.method === 'GET' && r.path.endsWith('/submission')) {
          return { status: 200, body: submission(state) };
        }
        return { status: 200, body: { items: [], nextCursor: null } };
      });
      await renderWithSession(
        <PaymentSubmissionPanel
          legalEntityId={ENTITY}
          batchId={BATCH}
          prepared
          onKnown={() => undefined}
        />,
        sessionWithRoles(['tenant-admin']),
        'en',
      );
      expect(await screen.findByTestId('submission-state-badge')).toBeInTheDocument();
      expect(screen.queryByTestId('payment-reconciliation-panel')).toBeNull();
      expect(requests.some((r) => r.path.includes('/reconciliation'))).toBe(false);
    },
  );
});

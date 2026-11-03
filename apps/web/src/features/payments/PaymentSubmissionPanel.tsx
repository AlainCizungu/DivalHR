import type {
  CommitPaymentSubmission,
  PaymentSubmissionAttempt,
  PaymentSubmissionPreview,
  PaymentSubmissionView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { MethodCounts, MinorAmount, ProviderBadge } from './PaymentBits';
import { PaymentReconciliationPanel } from './PaymentReconciliationPanel';
import {
  ATTEMPTS_PAGE,
  paymentFailureOf,
  RECONCILIATION_ELIGIBLE,
  SUBMISSION_IN_FLIGHT,
  SUBMISSION_REFRESH_MS,
  submissionTone,
} from './payments';

type Current =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; submission: PaymentSubmissionView };

/** The fixed statement shown on every submission view (D95B-9): accepted is never paid. */
export function SubmissionNotice({ testId = 'submission-notice' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="muted" data-testid={testId}>
      <strong>{t('paymentSubmission.notice')}</strong>
    </p>
  );
}

/**
 * MVP-095B (Issue #187, ADR 0037): "Submit to provider" / « Soumettre au prestataire ». Shows the
 * review (provider, totals, blockers), asks for both confirmations, and then shows the submission
 * state with its evidence and attempt history. The browser sends what the preview returned and
 * never builds a provider request, a signature or a payload. Accepted for processing is not paid.
 */
export function PaymentSubmissionPanel({
  legalEntityId,
  batchId,
  prepared,
  onKnown,
}: {
  legalEntityId: string;
  batchId: string;
  prepared: boolean;
  onKnown: (submitted: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [current, setCurrent] = useState<Current>({ kind: 'loading' });
  const [attempts, setAttempts] = useState<PaymentSubmissionAttempt[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [preview, setPreview] = useState<PaymentSubmissionPreview | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [understand, setUnderstand] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission', {
        params: { path: { legalEntityId, batchId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setCurrent({ kind: 'ready', submission: data });
            onKnown(true);
            return;
          }
          const next = paymentFailureOf(response, error);
          if (next.code === 'PAYMENT_SUBMISSION_NOT_FOUND') {
            setCurrent({ kind: 'none' });
            onKnown(false);
          } else {
            setCurrent({ kind: 'failed', failure: next });
          }
        },
        () => {
          if (active) setCurrent({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, batchId, revision, onKnown]);

  const loadAttempts = useCallback(
    async (after: string | null) => {
      try {
        const { data } = await core.GET(
          '/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/attempts',
          {
            params: {
              path: { legalEntityId, batchId },
              query: after ? { cursor: after, limit: ATTEMPTS_PAGE } : { limit: ATTEMPTS_PAGE },
            },
            cache: 'no-store',
          },
        );
        return data ?? null;
      } catch {
        return null;
      }
    },
    [core, legalEntityId, batchId],
  );

  const submissionVersion = current.kind === 'ready' ? current.submission.version : 0;
  const inFlight = current.kind === 'ready' && SUBMISSION_IN_FLIGHT.has(current.submission.state);

  useEffect(() => {
    if (current.kind !== 'ready') return undefined;
    let active = true;
    void loadAttempts(null).then((page) => {
      if (!active || !page) return;
      setAttempts(page.items);
      setCursor(page.nextCursor);
    });
    return () => {
      active = false;
    };
  }, [current.kind, submissionVersion, loadAttempts]);

  useEffect(() => {
    if (!inFlight) return undefined;
    const timer = window.setInterval(() => {
      setRevision((value) => value + 1);
    }, SUBMISSION_REFRESH_MS);
    return () => {
      window.clearInterval(timer);
    };
  }, [inFlight]);

  const discard = () => {
    setPreview(null);
    setReviewed(false);
    setUnderstand(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payment-batches/{batchId}/submissions/preview',
        { params: { path: { legalEntityId, batchId } }, cache: 'no-store' },
      );
      setReviewed(false);
      setUnderstand(false);
      key.reset();
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(paymentFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const committable =
    preview !== null &&
    preview.status === 'READY' &&
    preview.previewDigest !== null &&
    preview.expiresAt !== null;

  const onCommit = async () => {
    if (!preview || busy || !committable || !reviewed || !understand) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitPaymentSubmission = {
      expectedBatchVersion: preview.batchVersion,
      batchDigest: preview.batchDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedProviderAndTotals: true,
      understandAcceptedIsNotPaid: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payment-batches/{batchId}/submissions',
        {
          params: {
            path: { legalEntityId, batchId },
            header: { 'Idempotency-Key': key.keyFor({ legalEntityId, batchId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        key.reset();
        setPreview(null);
        setReviewed(false);
        setUnderstand(false);
        setAnnouncement(t('paymentSubmission.announce'));
        setCurrent({ kind: 'loading' });
        setRevision((value) => value + 1);
        return;
      }
      const next = paymentFailureOf(response, error);
      if (next.kind === 'stale') {
        discard();
        setRevision((value) => value + 1);
      }
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  if (!prepared && current.kind !== 'ready') return null;

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="payment-submission-panel"
    >
      <h2 id={`${ids}-title`}>{t('paymentSubmission.title')}</h2>
      <p className="muted">{t('paymentSubmission.description')}</p>
      <p role="status" className="visually-hidden">
        {announcement}
      </p>
      {current.kind === 'loading' && <p role="status">{t('paymentSubmission.loading')}</p>}
      {current.kind === 'failed' && (
        <PayrollAlert failure={current.failure} testId="submission-load-failure" />
      )}
      {current.kind === 'none' && (
        <>
          <p data-testid="submission-none">{t('paymentSubmission.none')}</p>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={busy !== null}
              data-testid="submission-review-button"
              onClick={() => void onPreview()}
            >
              {busy === 'preview'
                ? t('paymentSubmission.previewing')
                : t('paymentSubmission.submit')}
            </button>
          </div>
          {failure && (
            <PayrollAlert
              failure={failure}
              testId="submission-failure"
              alertRef={focus.register('failure')}
            />
          )}
          {preview && (
            <section aria-labelledby={`${ids}-review`} data-testid="submission-preview">
              <h3 id={`${ids}-review`} tabIndex={-1} ref={focus.register('preview')}>
                {t('paymentSubmission.reviewHeading')}
              </h3>
              <p>
                <StatusBadge
                  tone={preview.status === 'READY' ? 'success' : 'danger'}
                  testId="submission-preview-status"
                >
                  {t(`payments.previewStatus.${preview.status}`)}
                </StatusBadge>
              </p>
              <dl className="decision-summary decision-summary--wrapping">
                <dt>{t('paymentSubmission.provider')}</dt>
                <dd>
                  {preview.provider ? (
                    <ProviderBadge provider={preview.provider} />
                  ) : (
                    t('paymentSubmission.blockers.PROVIDER_NOT_AVAILABLE')
                  )}
                </dd>
                <dt>{t('paymentSubmission.batchVersion')}</dt>
                <dd>{preview.batchVersion}</dd>
                <dt>{t('paymentSubmission.lines')}</dt>
                <dd data-testid="submission-preview-lines">{preview.lineCount}</dd>
                <dt>{t('paymentSubmission.total')}</dt>
                <dd data-testid="submission-preview-total">
                  <MinorAmount minor={preview.totalNetMinor} currency={preview.currency} />
                </dd>
              </dl>
              <MethodCounts counts={preview.methodCounts} />
              {preview.blockers.length > 0 && (
                <div className="error-summary" role="alert" data-testid="submission-blockers">
                  <ul>
                    {preview.blockers.map((blocker) => (
                      <li key={blocker}>{t(`paymentSubmission.blockers.${blocker}`)}</li>
                    ))}
                  </ul>
                </div>
              )}
              {committable && preview.expiresAt && (
                <p className="muted">
                  {t('paymentSubmission.expires', {
                    at: formatInstant(i18n.language, preview.expiresAt),
                  })}
                </p>
              )}
              <SubmissionNotice testId="submission-preview-notice" />
              <div className="choice">
                <input
                  id={`${ids}-reviewed`}
                  type="checkbox"
                  checked={reviewed}
                  disabled={!committable}
                  data-testid="submission-ack-reviewed"
                  onChange={(event) => {
                    setReviewed(event.target.checked);
                  }}
                />
                <label htmlFor={`${ids}-reviewed`}>{t('paymentSubmission.reviewed')}</label>
              </div>
              <div className="choice">
                <input
                  id={`${ids}-understand`}
                  type="checkbox"
                  checked={understand}
                  disabled={!committable}
                  data-testid="submission-ack-understand"
                  onChange={(event) => {
                    setUnderstand(event.target.checked);
                  }}
                />
                <label htmlFor={`${ids}-understand`}>{t('paymentSubmission.understand')}</label>
              </div>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  data-testid="submission-commit"
                  disabled={busy !== null || !committable || !reviewed || !understand}
                  onClick={() => void onCommit()}
                >
                  {busy === 'commit'
                    ? t('paymentSubmission.confirming')
                    : t('paymentSubmission.confirm')}
                </button>
              </div>
            </section>
          )}
        </>
      )}
      {current.kind === 'ready' && (
        <>
          <section aria-labelledby={`${ids}-state`} data-testid="submission-state">
            <h3 id={`${ids}-state`}>{t('paymentSubmission.current')}</h3>
            <p>
              <StatusBadge
                tone={submissionTone(current.submission.state)}
                testId="submission-state-badge"
              >
                {t(`paymentSubmission.state.${current.submission.state}`)}
              </StatusBadge>
            </p>
            <p data-testid="submission-state-help">
              {t(`paymentSubmission.stateHelp.${current.submission.state}`)}
            </p>
            <SubmissionNotice />
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('paymentSubmission.provider')}</dt>
              <dd>
                <ProviderBadge provider={current.submission.provider} />
              </dd>
              <dt>{t('paymentSubmission.lines')}</dt>
              <dd data-testid="submission-lines">{current.submission.lineCount}</dd>
              <dt>{t('paymentSubmission.total')}</dt>
              <dd data-testid="submission-total">
                <MinorAmount
                  minor={current.submission.totalNetMinor}
                  currency={current.submission.currency}
                />
              </dd>
              <dt>{t('paymentSubmission.attemptsMade')}</dt>
              <dd data-testid="submission-attempt-count">{current.submission.attemptCount}</dd>
              {current.submission.outcomeCode && (
                <>
                  <dt>{t('paymentSubmission.outcome')}</dt>
                  <dd data-testid="submission-outcome">
                    {t(`paymentSubmission.outcomeLabel.${current.submission.outcomeCode}`)}
                  </dd>
                </>
              )}
              {current.submission.providerReference && (
                <>
                  <dt>{t('paymentSubmission.reference')}</dt>
                  <dd data-testid="submission-reference">{current.submission.providerReference}</dd>
                </>
              )}
              {current.submission.nextAttemptAt && (
                <>
                  <dt>{t('paymentSubmission.nextAttempt')}</dt>
                  <dd>{formatInstant(i18n.language, current.submission.nextAttemptAt)}</dd>
                </>
              )}
              <dt>{t('paymentSubmission.created')}</dt>
              <dd>{formatInstant(i18n.language, current.submission.createdAt)}</dd>
              <dt>{t('paymentSubmission.updated')}</dt>
              <dd>{formatInstant(i18n.language, current.submission.updatedAt)}</dd>
            </dl>
            <MethodCounts counts={current.submission.methodCounts} />
            <p className="muted">{t('paymentSubmission.final')}</p>
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--secondary"
                data-testid="submission-refresh"
                onClick={() => {
                  setRevision((value) => value + 1);
                }}
              >
                {t('paymentSubmission.refresh')}
              </button>
            </div>
          </section>
          <section aria-labelledby={`${ids}-history`} data-testid="submission-history">
            <h3 id={`${ids}-history`}>{t('paymentSubmission.history.title')}</h3>
            {attempts.length === 0 ? (
              <p className="muted">{t('paymentSubmission.history.empty')}</p>
            ) : (
              <ScrollRegion label={t('paymentSubmission.history.caption')}>
                <table data-testid="submission-attempts">
                  <caption className="visually-hidden">
                    {t('paymentSubmission.history.caption')}
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('paymentSubmission.history.sequence')}</th>
                      <th scope="col">{t('paymentSubmission.history.attempt')}</th>
                      <th scope="col">{t('paymentSubmission.history.state')}</th>
                      <th scope="col">{t('paymentSubmission.history.outcome')}</th>
                      <th scope="col">{t('paymentSubmission.history.reference')}</th>
                      <th scope="col">{t('paymentSubmission.history.time')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {attempts.map((row) => (
                      <tr key={row.sequence} data-testid="submission-attempt">
                        <th scope="row">{row.sequence}</th>
                        <td>{row.attemptNumber}</td>
                        <td>{t(`paymentSubmission.state.${row.state}`)}</td>
                        <td>
                          {row.outcomeCode
                            ? t(`paymentSubmission.outcomeLabel.${row.outcomeCode}`)
                            : '—'}
                        </td>
                        <td>{row.providerReference ?? '—'}</td>
                        <td>{formatInstant(i18n.language, row.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
            )}
            {cursor && (
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--secondary"
                  data-testid="submission-attempts-more"
                  onClick={() =>
                    void loadAttempts(cursor).then((page) => {
                      if (!page) return;
                      setAttempts((previous) => [...previous, ...page.items]);
                      setCursor(page.nextCursor);
                    })
                  }
                >
                  {t('paymentSubmission.history.more')}
                </button>
              </div>
            )}
          </section>
          {RECONCILIATION_ELIGIBLE.has(current.submission.state) && (
            <PaymentReconciliationPanel legalEntityId={legalEntityId} batchId={batchId} />
          )}
        </>
      )}
    </section>
  );
}

import type {
  CommitPaymentReconciliation,
  PaymentReconciliationInquiry,
  PaymentReconciliationPreview,
  PaymentReconciliationView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ProviderBadge } from './PaymentBits';
import {
  INQUIRIES_PAGE,
  paymentFailureOf,
  RECONCILIATION_GUIDED,
  RECONCILIATION_IN_FLIGHT,
  RECONCILIATION_REFRESH_MAX,
  RECONCILIATION_REFRESH_MS,
  reconciliationTone,
} from './payments';

type Current =
  | { kind: 'loading' }
  | { kind: 'none' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; reconciliation: PaymentReconciliationView };

type History = 'loading' | 'ready' | 'error';

/** The fixed statement shown on every reconciliation view (D95C-4): acceptance is never paid. */
export function ReconciliationNotice({ testId = 'reconciliation-warning' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="muted" data-testid={testId}>
      <strong>{t('paymentReconciliation.notice')}</strong>
    </p>
  );
}

/**
 * MVP-095C (Issue #190, ADR 0038): "Investigate delivery" / « Vérifier la livraison ». Shown for a
 * submission whose delivery is uncertain (or whose automatic handling ended). Reviews what the
 * status inquiry would ask, asks for both confirmations, then shows the investigation with its
 * inquiry history. The inquiry never resends the payment instruction and the browser never builds
 * a provider request. Provider acceptance is never paid or settled.
 */
export function PaymentReconciliationPanel({
  legalEntityId,
  batchId,
}: {
  legalEntityId: string;
  batchId: string;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [current, setCurrent] = useState<Current>({ kind: 'loading' });
  const [inquiries, setInquiries] = useState<PaymentReconciliationInquiry[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [history, setHistory] = useState<History>('loading');
  const [preview, setPreview] = useState<PaymentReconciliationPreview | null>(null);
  const [understandNoResend, setUnderstandNoResend] = useState(false);
  const [understandNotPaid, setUnderstandNotPaid] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [revision, setRevision] = useState(0);
  const refreshes = useRef(0);

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/reconciliation', {
        params: { path: { legalEntityId, batchId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setCurrent({ kind: 'ready', reconciliation: data });
            return;
          }
          const next = paymentFailureOf(response, error);
          setCurrent(
            next.code === 'PAYMENT_RECONCILIATION_NOT_FOUND'
              ? { kind: 'none' }
              : { kind: 'failed', failure: next },
          );
        },
        () => {
          if (active) setCurrent({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, batchId, revision]);

  const loadInquiries = useCallback(
    async (after: string | null) => {
      try {
        const { data } = await core.GET(
          '/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/reconciliation/inquiries',
          {
            params: {
              path: { legalEntityId, batchId },
              query: after ? { cursor: after, limit: INQUIRIES_PAGE } : { limit: INQUIRIES_PAGE },
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

  const reconciliationVersion = current.kind === 'ready' ? current.reconciliation.version : 0;
  const inFlight =
    current.kind === 'ready' && RECONCILIATION_IN_FLIGHT.has(current.reconciliation.state);

  useEffect(() => {
    if (reconciliationVersion === 0) return undefined;
    let active = true;
    void loadInquiries(null).then((page) => {
      if (!active) return;
      if (!page) {
        setHistory('error');
        return;
      }
      setInquiries(page.items);
      setCursor(page.nextCursor);
      setHistory('ready');
    });
    return () => {
      active = false;
    };
  }, [reconciliationVersion, loadInquiries]);

  // Bounded refresh: only while the investigation is running, at most RECONCILIATION_REFRESH_MAX
  // times per visit, and always cleared on unmount.
  useEffect(() => {
    if (!inFlight) return undefined;
    const timer = window.setInterval(() => {
      if (refreshes.current >= RECONCILIATION_REFRESH_MAX) {
        window.clearInterval(timer);
        return;
      }
      refreshes.current += 1;
      setRevision((value) => value + 1);
    }, RECONCILIATION_REFRESH_MS);
    return () => {
      window.clearInterval(timer);
    };
  }, [inFlight]);

  const discard = () => {
    setPreview(null);
    setUnderstandNoResend(false);
    setUnderstandNotPaid(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/reconciliation/preview',
        { params: { path: { legalEntityId, batchId } }, cache: 'no-store' },
      );
      setUnderstandNoResend(false);
      setUnderstandNotPaid(false);
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
    preview.expiresAt !== null &&
    (preview.submissionState === 'DELIVERY_UNKNOWN' ||
      preview.submissionState === 'MANUAL_INTERVENTION');

  const onCommit = async () => {
    if (!preview || busy || !committable || !understandNoResend || !understandNotPaid) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    if (
      preview.submissionState !== 'DELIVERY_UNKNOWN' &&
      preview.submissionState !== 'MANUAL_INTERVENTION'
    ) {
      return;
    }
    const body: CommitPaymentReconciliation = {
      expectedSubmissionVersion: preview.submissionVersion,
      expectedSubmissionState: preview.submissionState,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      understandInquiryDoesNotResend: true,
      understandAcceptedIsNotPaid: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/reconciliation',
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
        discard();
        refreshes.current = 0;
        setAnnouncement(t('paymentReconciliation.announce'));
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

  const reconciliation = current.kind === 'ready' ? current.reconciliation : null;

  return (
    <section
      className="reconciliation-panel"
      aria-labelledby={`${ids}-title`}
      data-testid="payment-reconciliation-panel"
    >
      <h3 id={`${ids}-title`}>{t('paymentReconciliation.title')}</h3>
      <p className="muted">{t('paymentReconciliation.description')}</p>
      <p role="status" className="visually-hidden">
        {announcement}
      </p>
      {current.kind === 'loading' && (
        <p role="status" data-testid="reconciliation-loading">
          {t('paymentReconciliation.loading')}
        </p>
      )}
      {current.kind === 'failed' && (
        <PayrollAlert failure={current.failure} testId="reconciliation-load-failure" />
      )}
      {current.kind === 'none' && (
        <>
          <p data-testid="reconciliation-none">{t('paymentReconciliation.none')}</p>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={busy !== null}
              data-testid="reconciliation-open"
              onClick={() => void onPreview()}
            >
              {busy === 'preview'
                ? t('paymentReconciliation.previewing')
                : t('paymentReconciliation.open')}
            </button>
          </div>
          {failure && (
            <PayrollAlert
              failure={failure}
              testId="reconciliation-failure"
              alertRef={focus.register('failure')}
            />
          )}
          {preview && (
            <section
              aria-labelledby={`${ids}-review`}
              data-testid="reconciliation-dialog"
              className="reconciliation-review"
            >
              <h4 id={`${ids}-review`} tabIndex={-1} ref={focus.register('preview')}>
                {t('paymentReconciliation.reviewHeading')}
              </h4>
              <p>
                <StatusBadge
                  tone={preview.status === 'READY' ? 'success' : 'danger'}
                  testId="reconciliation-preview-status"
                >
                  {t(`paymentReconciliation.status.${preview.status}`)}
                </StatusBadge>
              </p>
              <dl className="decision-summary decision-summary--wrapping">
                <dt>{t('paymentReconciliation.provider')}</dt>
                <dd>
                  {preview.provider ? (
                    <ProviderBadge provider={preview.provider} />
                  ) : (
                    t('paymentReconciliation.blockers.PROVIDER_NOT_AVAILABLE')
                  )}
                </dd>
                <dt>{t('paymentReconciliation.fingerprint')}</dt>
                <dd>
                  <code data-testid="reconciliation-fingerprint">{preview.requestFingerprint}</code>
                </dd>
                <dt>{t('paymentReconciliation.submissionState')}</dt>
                <dd data-testid="reconciliation-preview-submission-state">
                  {t(`paymentSubmission.state.${preview.submissionState}`)}
                </dd>
                {preview.submissionOutcomeCode && (
                  <>
                    <dt>{t('paymentReconciliation.submissionOutcome')}</dt>
                    <dd data-testid="reconciliation-preview-submission-outcome">
                      {t(`paymentSubmission.outcomeLabel.${preview.submissionOutcomeCode}`)}
                    </dd>
                  </>
                )}
                <dt>{t('paymentReconciliation.previous')}</dt>
                <dd data-testid="reconciliation-preview-previous">
                  {preview.previous
                    ? [
                        t(`paymentReconciliation.stateLabel.${preview.previous.state}`),
                        preview.previous.outcomeCode
                          ? t(`paymentReconciliation.outcomeLabel.${preview.previous.outcomeCode}`)
                          : null,
                      ]
                        .filter(Boolean)
                        .join(' · ')
                    : t('paymentReconciliation.previousNone')}
                </dd>
              </dl>
              <p className="muted">{t('paymentReconciliation.fingerprintHelp')}</p>
              {preview.blockers.length > 0 && (
                <div className="error-summary" role="alert" data-testid="reconciliation-blockers">
                  <ul>
                    {preview.blockers.map((blocker) => (
                      <li key={blocker}>{t(`paymentReconciliation.blockers.${blocker}`)}</li>
                    ))}
                  </ul>
                </div>
              )}
              {committable && preview.expiresAt && (
                <p className="muted">
                  {t('paymentReconciliation.expires', {
                    at: formatInstant(i18n.language, preview.expiresAt),
                  })}
                </p>
              )}
              <ReconciliationNotice />
              <div className="choice">
                <input
                  id={`${ids}-no-resend`}
                  type="checkbox"
                  checked={understandNoResend}
                  disabled={!committable}
                  data-testid="reconciliation-ack-no-resend"
                  onChange={(event) => {
                    setUnderstandNoResend(event.target.checked);
                  }}
                />
                <label htmlFor={`${ids}-no-resend`}>
                  {t('paymentReconciliation.understandNoResend')}
                </label>
              </div>
              <div className="choice">
                <input
                  id={`${ids}-not-paid`}
                  type="checkbox"
                  checked={understandNotPaid}
                  disabled={!committable}
                  data-testid="reconciliation-ack-not-paid"
                  onChange={(event) => {
                    setUnderstandNotPaid(event.target.checked);
                  }}
                />
                <label htmlFor={`${ids}-not-paid`}>
                  {t('paymentReconciliation.understandNotPaid')}
                </label>
              </div>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  data-testid="reconciliation-confirm"
                  disabled={
                    busy !== null || !committable || !understandNoResend || !understandNotPaid
                  }
                  onClick={() => void onCommit()}
                >
                  {busy === 'commit'
                    ? t('paymentReconciliation.confirming')
                    : t('paymentReconciliation.confirm')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  data-testid="reconciliation-discard"
                  disabled={busy !== null}
                  onClick={discard}
                >
                  {t('paymentReconciliation.discard')}
                </button>
              </div>
            </section>
          )}
        </>
      )}
      {reconciliation && (
        <>
          <section aria-labelledby={`${ids}-state`} data-testid="reconciliation-card">
            <h4 id={`${ids}-state`}>{t('paymentReconciliation.current')}</h4>
            <p>
              <StatusBadge
                tone={reconciliationTone(reconciliation.state)}
                testId="reconciliation-state"
              >
                {t(`paymentReconciliation.stateLabel.${reconciliation.state}`)}
              </StatusBadge>
            </p>
            <p data-testid="reconciliation-state-help">
              {t(`paymentReconciliation.stateHelp.${reconciliation.state}`)}
            </p>
            <ReconciliationNotice />
            {RECONCILIATION_GUIDED.has(reconciliation.state) && (
              <div role="note" data-testid="reconciliation-guidance">
                <h5>{t('paymentReconciliation.guidance.title')}</h5>
                <p>
                  {t(
                    reconciliation.state === 'MANUAL_REVIEW_REQUIRED'
                      ? 'paymentReconciliation.guidance.MANUAL_REVIEW_REQUIRED'
                      : 'paymentReconciliation.guidance.STILL_UNRESOLVED',
                  )}
                </p>
              </div>
            )}
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('paymentReconciliation.provider')}</dt>
              <dd>
                <ProviderBadge provider={reconciliation.provider} />
              </dd>
              <dt>{t('paymentReconciliation.fingerprint')}</dt>
              <dd>
                <code data-testid="reconciliation-fingerprint">
                  {reconciliation.requestFingerprint}
                </code>
              </dd>
              <dt>{t('paymentReconciliation.submissionStateUnchanged')}</dt>
              <dd data-testid="reconciliation-submission-state">
                {t(`paymentSubmission.state.${reconciliation.submissionState}`)}
              </dd>
              {reconciliation.outcomeCode && (
                <>
                  <dt>{t('paymentReconciliation.outcome')}</dt>
                  <dd data-testid="reconciliation-outcome">
                    {t(`paymentReconciliation.outcomeLabel.${reconciliation.outcomeCode}`)}
                  </dd>
                </>
              )}
              <dt>{t('paymentReconciliation.attempts')}</dt>
              <dd data-testid="reconciliation-attempt-count">{reconciliation.attemptCount}</dd>
              {reconciliation.state === 'OPEN' && reconciliation.nextAttemptAt && (
                <>
                  <dt>{t('paymentReconciliation.nextAttempt')}</dt>
                  <dd data-testid="reconciliation-next-attempt">
                    {formatInstant(i18n.language, reconciliation.nextAttemptAt)}
                  </dd>
                </>
              )}
              <dt>{t('paymentReconciliation.created')}</dt>
              <dd>{formatInstant(i18n.language, reconciliation.createdAt)}</dd>
              <dt>{t('paymentReconciliation.updated')}</dt>
              <dd>{formatInstant(i18n.language, reconciliation.updatedAt)}</dd>
            </dl>
            {inFlight && <p className="muted">{t('paymentReconciliation.watching')}</p>}
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--secondary"
                data-testid="reconciliation-refresh"
                onClick={() => {
                  setRevision((value) => value + 1);
                }}
              >
                {t('paymentReconciliation.refresh')}
              </button>
            </div>
          </section>
          <section aria-labelledby={`${ids}-history`} data-testid="reconciliation-history">
            <h4 id={`${ids}-history`}>{t('paymentReconciliation.history.title')}</h4>
            <ReconciliationNotice testId="reconciliation-history-warning" />
            {history === 'loading' && (
              <p role="status">{t('paymentReconciliation.history.loading')}</p>
            )}
            {history === 'error' && (
              <p role="alert" data-testid="reconciliation-history-error">
                {t('paymentReconciliation.history.error')}
              </p>
            )}
            {history === 'ready' && inquiries.length === 0 && (
              <p className="muted" data-testid="reconciliation-history-empty">
                {t('paymentReconciliation.history.empty')}
              </p>
            )}
            {history === 'ready' && inquiries.length > 0 && (
              <ScrollRegion label={t('paymentReconciliation.history.caption')}>
                <table data-testid="reconciliation-inquiries">
                  <caption className="visually-hidden">
                    {t('paymentReconciliation.history.caption')}
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('paymentReconciliation.history.sequence')}</th>
                      <th scope="col">{t('paymentReconciliation.history.attempt')}</th>
                      <th scope="col">{t('paymentReconciliation.history.state')}</th>
                      <th scope="col">{t('paymentReconciliation.history.outcome')}</th>
                      <th scope="col">{t('paymentReconciliation.history.authenticated')}</th>
                      <th scope="col">{t('paymentReconciliation.history.time')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {inquiries.map((row) => (
                      <tr key={row.sequence} data-testid="reconciliation-inquiry">
                        <th scope="row">{row.sequence}</th>
                        <td>{row.attemptNumber}</td>
                        <td>{t(`paymentReconciliation.stateLabel.${row.state}`)}</td>
                        <td>
                          {row.outcomeCode
                            ? t(`paymentReconciliation.outcomeLabel.${row.outcomeCode}`)
                            : '—'}
                        </td>
                        <td>
                          {row.authenticated
                            ? t('paymentReconciliation.history.yes')
                            : t('paymentReconciliation.history.no')}
                        </td>
                        <td>{formatInstant(i18n.language, row.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
            )}
            {history === 'ready' && cursor && (
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--secondary"
                  data-testid="reconciliation-history-more"
                  onClick={() =>
                    void loadInquiries(cursor).then((page) => {
                      if (!page) {
                        setHistory('error');
                        return;
                      }
                      setInquiries((previous) => [...previous, ...page.items]);
                      setCursor(page.nextCursor);
                    })
                  }
                >
                  {t('paymentReconciliation.history.more')}
                </button>
              </div>
            )}
          </section>
        </>
      )}
    </section>
  );
}

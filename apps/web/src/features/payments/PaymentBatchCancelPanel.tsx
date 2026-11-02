import type {
  CommitPaymentBatchCancel,
  PaymentBatchCancelPreview,
  PaymentBatchCancelReasonCode,
} from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { CANCEL_REASONS, paymentFailureOf } from './payments';

/**
 * MVP-095A: cancellation of a PREPARED batch. A cancelled batch stays as history and is never
 * reactivated; cancelling releases the payroll void guard for its package. The reason is a closed
 * list, and the acknowledgement states that no money was sent.
 */
export function PaymentBatchCancelPanel({
  legalEntityId,
  batchId,
  onCancelled,
  onGone,
}: {
  legalEntityId: string;
  batchId: string;
  onCancelled: () => void;
  onGone: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [reason, setReason] = useState<PaymentBatchCancelReasonCode | ''>('');
  const [preview, setPreview] = useState<PaymentBatchCancelPreview | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);

  const discard = () => {
    setPreview(null);
    setAcknowledged(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy || reason === '') return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payment-batches/{batchId}/cancel/preview',
        {
          params: { path: { legalEntityId, batchId } },
          body: { reasonCode: reason },
          cache: 'no-store',
        },
      );
      setAcknowledged(false);
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
    if (!preview || reason === '' || busy || !committable || !acknowledged) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitPaymentBatchCancel = {
      reasonCode: reason,
      batchVersion: preview.batchVersion,
      batchDigest: preview.batchDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      noMoneySentAcknowledged: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payment-batches/{batchId}/cancel',
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
        setReason('');
        setAcknowledged(false);
        onCancelled();
        return;
      }
      const next = paymentFailureOf(response, error);
      if (next.kind === 'stale') {
        discard();
        if (next.code === 'PAYMENT_BATCH_CANCELLED') onGone();
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

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="payment-cancel-panel">
      <h2 id={`${ids}-title`}>{t('payments.cancel.title')}</h2>
      <p className="muted">{t('payments.cancel.help')}</p>
      <div className="field">
        <label htmlFor={`${ids}-reason`}>{t('payments.cancel.reason')}</label>
        <select
          id={`${ids}-reason`}
          value={reason}
          data-testid="payment-cancel-reason"
          onChange={(event) => {
            setReason(event.target.value as PaymentBatchCancelReasonCode | '');
            discard();
          }}
        >
          <option value="">{t('payments.destination.choose')}</option>
          {CANCEL_REASONS.map((code) => (
            <option key={code} value={code}>
              {t(`payments.cancelReason.${code}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="dialog-actions">
        <button
          type="button"
          className="button button--secondary"
          disabled={busy !== null || reason === ''}
          data-testid="payment-cancel-preview-button"
          onClick={() => void onPreview()}
        >
          {busy === 'preview' ? t('payrollRuns.previewing') : t('payments.cancel.preview')}
        </button>
      </div>
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="payment-cancel-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="payment-cancel-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('payments.cancel.previewHeading')}
          </h3>
          <p>
            <StatusBadge tone={preview.status === 'READY' ? 'success' : 'danger'}>
              {t(`payments.previewStatus.${preview.status}`)}
            </StatusBadge>
          </p>
          <p>{t('payments.cancel.affects', { count: preview.lineCount })}</p>
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="payment-cancel-blockers">
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker}>{t(`payments.cancelBlockers.${blocker}`)}</li>
                ))}
              </ul>
            </div>
          )}
          {committable && preview.expiresAt && (
            <p className="muted">
              {t('payrollRuns.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <div className="choice">
            <input
              id={`${ids}-ack`}
              type="checkbox"
              checked={acknowledged}
              disabled={!committable}
              data-testid="payment-cancel-ack"
              onChange={(event) => {
                setAcknowledged(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-ack`}>{t('payments.cancel.acknowledge')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="payment-cancel-commit"
              disabled={busy !== null || !committable || !acknowledged}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('payrollRuns.saving') : t('payments.cancel.commit')}
            </button>
          </div>
        </section>
      )}
    </section>
  );
}

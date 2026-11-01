import type {
  CommitPaymentBatch,
  PaymentBatchPreview,
  PaymentBatchSummary,
} from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { BatchLinesTable, MethodCounts, MinorAmount, NoMoneySent } from './PaymentBits';
import { paymentFailureOf } from './payments';

/**
 * MVP-095A: « Préparer le lot de paiement » / "Prepare payment batch" on an APPROVED package.
 * A preview shows the exact approved net amounts and the MASKED destination of every line, the
 * counts by method, the blockers and the warnings; committing needs both confirmations. A prepared
 * batch is a register: nothing is sent and no money moves. Batches already prepared for the package
 * are listed with a link to their detail.
 */
export function PaymentBatchPanel({
  legalEntityId,
  packageId,
  packageStatus,
  revision,
  onChanged,
}: {
  legalEntityId: string;
  packageId: string;
  packageStatus: 'APPROVED' | 'VOIDED';
  revision: number;
  onChanged: (message: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [batches, setBatches] = useState<PaymentBatchSummary[]>([]);
  const [listFailure, setListFailure] = useState<PayrollFailure | null>(null);
  const [preview, setPreview] = useState<PaymentBatchPreview | null>(null);
  const [totalsReviewed, setTotalsReviewed] = useState(false);
  const [noMoney, setNoMoney] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payment-batches', {
        params: { path: { legalEntityId }, query: { packageId, limit: 25 } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setBatches(data.items);
            setListFailure(null);
          } else {
            setListFailure(paymentFailureOf(response, error));
          }
        },
        () => {
          if (active) setListFailure(NETWORK_FAILURE);
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, packageId, version, revision]);

  const discard = () => {
    setPreview(null);
    setTotalsReviewed(false);
    setNoMoney(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payment-batches/preview',
        { params: { path: { legalEntityId, packageId } }, cache: 'no-store' },
      );
      setTotalsReviewed(false);
      setNoMoney(false);
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
  const confirmed = totalsReviewed && noMoney;

  const onCommit = async () => {
    if (!preview || busy || !committable || !confirmed) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitPaymentBatch = {
      expectedVersion: preview.expectedVersion,
      packageVersion: preview.packageVersion,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedTotalsAndDestinations: true,
      noMoneySentAcknowledged: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payment-batches',
        {
          params: {
            path: { legalEntityId, packageId },
            header: { 'Idempotency-Key': key.keyFor({ legalEntityId, packageId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        discard();
        setVersion((value) => value + 1);
        onChanged(t('payments.batch.announcePrepared'));
        return;
      }
      const next = paymentFailureOf(response, error);
      if (next.kind === 'stale') discard();
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const whyKey =
    preview === null
      ? null
      : !committable
        ? 'payments.batch.whyBlocked'
        : !confirmed
          ? 'payments.batch.whyConfirm'
          : null;

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="payment-batch-panel">
      <h2 id={`${ids}-title`}>{t('payments.batch.title')}</h2>
      <p className="muted">{t('payments.batch.help')}</p>
      <NoMoneySent />
      {listFailure && <PayrollAlert failure={listFailure} testId="payment-batches-failure" />}
      {batches.length > 0 && (
        <ul className="plain-list" data-testid="payment-batches">
          {batches.map((batch) => (
            <li key={batch.id} data-testid="payment-batch-item" data-status={batch.status}>
              <Link
                className="table-link"
                to={`/admin/payment-batches/${legalEntityId}/${batch.id}`}
                data-testid="payment-batch-link"
              >
                {t('payments.batch.itemTitle', { version: batch.version })}
              </Link>{' '}
              <StatusBadge tone={batch.status === 'PREPARED' ? 'success' : 'neutral'}>
                {t(`payments.batchStatus.${batch.status}`)}
              </StatusBadge>{' '}
              <span className="muted">
                {t('payments.batch.itemMeta', {
                  count: batch.lineCount,
                  at: formatInstant(i18n.language, batch.preparedAt),
                })}
              </span>
            </li>
          ))}
        </ul>
      )}
      {packageStatus === 'APPROVED' && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            disabled={busy !== null}
            data-testid="payment-batch-preview-button"
            onClick={() => void onPreview()}
          >
            {busy === 'preview' ? t('payrollRuns.previewing') : t('payments.batch.preview')}
          </button>
        </div>
      )}
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="payment-batch-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {failure?.kind === 'stale' && (
        <p role="status" data-testid="payment-batch-stale">
          {t('payments.batch.staleRefresh')}
        </p>
      )}
      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="payment-batch-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('payments.batch.previewHeading')}
          </h3>
          <p>
            <StatusBadge
              tone={preview.status === 'READY' ? 'success' : 'danger'}
              testId="payment-batch-preview-status"
            >
              {t(`payments.previewStatus.${preview.status}`)}
            </StatusBadge>
          </p>
          <dl className="decision-summary decision-summary--wrapping">
            <dt>{t('payments.batch.lineCount')}</dt>
            <dd data-testid="payment-batch-line-count">{preview.lineCount}</dd>
            <dt>{t('payments.batch.total')}</dt>
            <dd data-testid="payment-batch-total">
              <MinorAmount minor={preview.totalNetMinor} currency={preview.currency} />
            </dd>
            <dt>{t('payments.batch.zeroNet')}</dt>
            <dd>{preview.zeroNetExcludedCount}</dd>
          </dl>
          <MethodCounts counts={preview.methodCounts} />
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="payment-batch-blockers">
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker} data-code={blocker}>
                    {t(`payments.batchBlockers.${blocker}`)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {preview.warnings.length > 0 && (
            <ul data-testid="payment-batch-warnings">
              {preview.warnings.map((warning) => (
                <li key={warning}>{t(`payments.batchWarnings.${warning}`)}</li>
              ))}
            </ul>
          )}
          <BatchLinesTable
            lines={preview.lines}
            currency={preview.currency}
            testId="payment-batch-preview-lines"
          />
          {committable && preview.expiresAt && (
            <p className="muted">
              {t('payrollRuns.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <div className="choice">
            <input
              id={`${ids}-totals`}
              type="checkbox"
              checked={totalsReviewed}
              disabled={!committable}
              data-testid="payment-batch-reviewed"
              onChange={(event) => {
                setTotalsReviewed(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-totals`}>{t('payments.batch.reviewed')}</label>
          </div>
          <div className="choice">
            <input
              id={`${ids}-nomoney`}
              type="checkbox"
              checked={noMoney}
              disabled={!committable}
              data-testid="payment-batch-nomoney"
              onChange={(event) => {
                setNoMoney(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-nomoney`}>{t('payments.batch.acknowledge')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="payment-batch-commit"
              disabled={busy !== null || !committable || !confirmed}
              aria-describedby={whyKey ? `${ids}-why` : undefined}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('payrollRuns.saving') : t('payments.batch.commit')}
            </button>
          </div>
          {whyKey && (
            <p id={`${ids}-why`} className="muted" data-testid="payment-batch-why">
              {t(whyKey)}
            </p>
          )}
        </section>
      )}
    </section>
  );
}

import type { PaymentBatchDetail, PaymentBatchLine } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { PaymentBatchCancelPanel } from './PaymentBatchCancelPanel';
import { BatchLinesTable, MethodCounts, MinorAmount, NoMoneySent } from './PaymentBits';
import { LINES_PAGE, paymentFailureOf } from './payments';
import { useRegisterDownload } from './useRegisterDownload';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; detail: PaymentBatchDetail };

/**
 * MVP-095A: one payment batch. The immutable summary (copied from the approved package, never
 * recalculated), the MASKED lines, the CSV and manifest downloads (disclosure-audited), the
 * cancellation, and a link back to the package. Nothing here sends or pays.
 */
export function PaymentBatchDetailPage() {
  const { legalEntityId = '', batchId = '' } = useParams();
  return (
    <Detail key={`${legalEntityId}/${batchId}`} legalEntityId={legalEntityId} batchId={batchId} />
  );
}

function Detail({ legalEntityId, batchId }: { legalEntityId: string; batchId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [lines, setLines] = useState<PaymentBatchLine[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [linesFailure, setLinesFailure] = useState<PayrollFailure | null>(null);
  const [revision, setRevision] = useState(0);
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payment-batches/{batchId}', {
        params: { path: { legalEntityId, batchId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', detail: data }
              : { kind: 'failed', failure: paymentFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, batchId, revision]);

  const applyPage = (
    after: string | null,
    result: {
      data?: { items: PaymentBatchLine[]; nextCursor: string | null };
      failure?: PayrollFailure;
    },
  ) => {
    if (result.data) {
      const page = result.data;
      setLines((previous) => (after ? [...previous, ...page.items] : page.items));
      setCursor(page.nextCursor);
      setLinesFailure(null);
    } else if (result.failure) {
      setLinesFailure(result.failure);
    }
  };

  const loadLines = useCallback(
    async (after: string | null) => {
      try {
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payment-batches/{batchId}/lines',
          {
            params: {
              path: { legalEntityId, batchId },
              query: after ? { cursor: after, limit: LINES_PAGE } : { limit: LINES_PAGE },
            },
            cache: 'no-store',
          },
        );
        return data ? { data } : { failure: paymentFailureOf(response, error) };
      } catch {
        return { failure: NETWORK_FAILURE };
      }
    },
    [core, legalEntityId, batchId],
  );

  useEffect(() => {
    let active = true;
    void loadLines(null).then((result) => {
      if (!active) return;
      if (result.data) {
        setLines(result.data.items);
        setCursor(result.data.nextCursor);
        setLinesFailure(null);
      } else {
        setLinesFailure(result.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [loadLines, revision]);

  const batch = load.kind === 'ready' ? load.detail.batch : null;
  const files = useRegisterDownload(legalEntityId, batchId, batch?.period ?? '0000-00');

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="payment-batch-detail">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payments.detail.title')}
        description={t('payments.detail.description')}
      />
      <p role="status" className="visually-hidden">
        {announcement}
      </p>
      {load.kind === 'loading' && <p role="status">{t('payments.loading')}</p>}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="payment-batch-detail-failure" />
      )}
      {batch && load.kind === 'ready' && (
        <>
          <section
            className="card"
            aria-labelledby={`${ids}-summary`}
            data-testid="payment-summary"
          >
            <h2 id={`${ids}-summary`}>{t('payments.detail.summary')}</h2>
            <p>
              <StatusBadge
                tone={batch.status === 'PREPARED' ? 'success' : 'neutral'}
                testId="payment-batch-status"
              >
                {t(`payments.batchStatus.${batch.status}`)}
              </StatusBadge>
            </p>
            <NoMoneySent />
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('payments.detail.period')}</dt>
              <dd>{formatMonth(i18n.language, batch.period)}</dd>
              <dt>{t('payments.detail.currency')}</dt>
              <dd>{batch.currency}</dd>
              <dt>{t('payments.detail.version')}</dt>
              <dd>{batch.version}</dd>
              <dt>{t('payments.batch.lineCount')}</dt>
              <dd data-testid="payment-detail-line-count">{batch.lineCount}</dd>
              <dt>{t('payments.batch.total')}</dt>
              <dd data-testid="payment-detail-total">
                <MinorAmount minor={batch.totalNetMinor} currency={batch.currency} />
              </dd>
              <dt>{t('payments.batch.zeroNet')}</dt>
              <dd>{batch.zeroNetExcludedCount}</dd>
              <dt>{t('payments.detail.preparedAt')}</dt>
              <dd>{formatInstant(i18n.language, batch.preparedAt)}</dd>
              {batch.cancelledAt && (
                <>
                  <dt>{t('payments.detail.cancelledAt')}</dt>
                  <dd data-testid="payment-detail-cancelled">
                    {formatInstant(i18n.language, batch.cancelledAt)}
                    {batch.cancelReasonCode
                      ? ` · ${t(`payments.cancelReason.${batch.cancelReasonCode}`)}`
                      : ''}
                  </dd>
                </>
              )}
            </dl>
            <MethodCounts counts={batch.methodCounts} />
            <p>
              <Link
                className="table-link"
                to={`/admin/payroll-packages/${legalEntityId}/${batch.packageId}`}
                data-testid="payment-package-link"
              >
                {t('payments.detail.package')}
              </Link>
            </p>
          </section>
          <section className="card" aria-labelledby={`${ids}-lines`}>
            <h2 id={`${ids}-lines`}>{t('payments.detail.lines')}</h2>
            {linesFailure && <PayrollAlert failure={linesFailure} testId="payment-lines-failure" />}
            <BatchLinesTable lines={lines} currency={batch.currency} testId="payment-lines" />
            {cursor && (
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--secondary"
                  data-testid="payment-lines-more"
                  onClick={() =>
                    void loadLines(cursor).then((result) => {
                      applyPage(cursor, result);
                    })
                  }
                >
                  {t('payments.loadMore')}
                </button>
              </div>
            )}
          </section>
          <section className="card" aria-labelledby={`${ids}-downloads`}>
            <h2 id={`${ids}-downloads`}>{t('payments.detail.downloads')}</h2>
            <p className="muted">{t('payments.detail.downloadsHelp')}</p>
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--secondary"
                disabled={files.busy !== null}
                data-testid="payment-download-csv"
                onClick={() => void files.download('csv')}
              >
                {t('payments.detail.downloadCsv')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={files.busy !== null}
                data-testid="payment-download-manifest"
                onClick={() => void files.download('manifest')}
              >
                {t('payments.detail.downloadManifest')}
              </button>
            </div>
            {files.failure && (
              <PayrollAlert failure={files.failure} testId="payment-download-failure" />
            )}
            <p role="status" data-testid="payment-download-done">
              {files.done ? t('payments.detail.downloaded') : ''}
            </p>
          </section>
          {batch.status === 'PREPARED' && (
            <PaymentBatchCancelPanel
              legalEntityId={legalEntityId}
              batchId={batchId}
              onCancelled={() => {
                setAnnouncement(t('payments.cancel.announce'));
                setRevision((value) => value + 1);
              }}
              onGone={() => {
                setRevision((value) => value + 1);
              }}
            />
          )}
        </>
      )}
    </section>
  );
}

import type { PayrollRunDetail } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import {
  DraftLabel,
  LinesTable,
  Money,
  PayrollAlert,
  RunStatus,
  useFocusRequest,
} from './PayrollBits';
import {
  formatMonth,
  LINES_LIMIT,
  NETWORK_FAILURE,
  payDayText,
  payrollFailureOf,
  type PayrollFailure,
} from './payrollRuns';

type DetailLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; detail: PayrollRunDetail };

/**
 * A frozen run: its policy, status, totals and the evidence of every line (eligible ranges, the
 * denominator, the monthly amount, the contractual gross, the explanation codes and the source
 * records by id and version), read as keyset pages of 50 lines. The run never changes after
 * creation. There is no employee-facing payslip or payroll page.
 */
export function PayrollRunDetailPage() {
  const { legalEntityId = '', runId = '' } = useParams();
  return (
    <RunDetail key={`${legalEntityId}/${runId}`} legalEntityId={legalEntityId} runId={runId} />
  );
}

function RunDetail({ legalEntityId, runId }: { legalEntityId: string; runId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const focus = useFocusRequest();
  const [load, setLoad] = useState<DetailLoad>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<PayrollFailure | null>(null);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payroll-runs/{runId}',
          {
            params: {
              path: { legalEntityId, runId },
              query: cursor ? { cursor, limit: LINES_LIMIT } : { limit: LINES_LIMIT },
            },
            cache: 'no-store',
          },
        );
        return data
          ? { data, failure: null }
          : { data: null, failure: payrollFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core, legalEntityId, runId],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((result) => {
      if (!active) return;
      setLoad(
        result.data
          ? { kind: 'ready', detail: result.data }
          : { kind: 'failed', failure: result.failure },
      );
      focus.request('summary');
    });
    return () => {
      active = false;
    };
    // The focus helper is stable in behaviour; a new page must not reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchPage]);

  const loadMore = async () => {
    if (load.kind !== 'ready' || !load.detail.nextCursor || loading.current) return;
    loading.current = true;
    setMoreBusy(true);
    setMoreFailure(null);
    const result = await fetchPage(load.detail.nextCursor);
    if (result.data) {
      const page = result.data;
      setLoad((current) =>
        current.kind === 'ready'
          ? {
              kind: 'ready',
              detail: {
                ...current.detail,
                lines: [...current.detail.lines, ...page.lines],
                nextCursor: page.nextCursor,
              },
            }
          : current,
      );
    } else {
      setMoreFailure(result.failure);
      focus.request('moreError');
    }
    loading.current = false;
    setMoreBusy(false);
  };

  const detail = load.kind === 'ready' ? load.detail : null;
  const run = detail?.run;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="payroll-run-detail">
      <PageHeader
        titleId={`${ids}-title`}
        title={
          run
            ? t('payrollRuns.detail.title', {
                period: formatMonth(i18n.language, run.period),
                currency: run.currency,
                version: run.version,
              })
            : t('payrollRuns.detail.fallbackTitle')
        }
        description={t('payrollRuns.detail.description')}
      />
      <p>
        <Link className="table-link" to="/admin/payroll-runs">
          {t('payrollRuns.detail.back')}
        </Link>
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="payroll-detail-loading">
          {t('payrollRuns.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert
          failure={load.failure}
          testId="payroll-detail-error"
          alertRef={focus.register('summary')}
        />
      )}
      {detail && run && (
        <>
          <section
            className="card"
            aria-labelledby={`${ids}-summary`}
            data-testid="payroll-summary"
          >
            <h2 id={`${ids}-summary`} tabIndex={-1} ref={focus.register('summary')}>
              {t('payrollRuns.detail.summary')}
            </h2>
            <RunStatus status={run.status} />
            <DraftLabel />
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('payrollRuns.list.period')}</dt>
              <dd>{formatMonth(i18n.language, run.period)}</dd>
              <dt>{t('payrollRuns.list.currency')}</dt>
              <dd>{run.currency}</dd>
              <dt>{t('payrollRuns.list.version')}</dt>
              <dd>{run.version}</dd>
              <dt>{t('payrollRuns.employeeCount')}</dt>
              <dd data-testid="payroll-detail-count">{run.employeeCount}</dd>
              <dt>{t('payrollRuns.contractualGross')}</dt>
              <dd data-testid="payroll-detail-total">
                <Money money={run.total} />
              </dd>
              <dt>{t('payrollRuns.detail.createdAt')}</dt>
              <dd>{formatInstant(i18n.language, run.createdAt)}</dd>
            </dl>
            {run.status === 'SUPERSEDED' && run.supersededByRunId && (
              <p data-testid="payroll-detail-superseded">
                {t('payrollRuns.detail.superseded')}{' '}
                <Link
                  className="table-link"
                  to={`/admin/payroll-runs/${run.legalEntityId}/${run.supersededByRunId}`}
                >
                  {t('payrollRuns.detail.openLatest')}
                </Link>
              </p>
            )}
          </section>
          <section
            className="card"
            aria-labelledby={`${ids}-policy`}
            data-testid="payroll-detail-policy"
          >
            <h2 id={`${ids}-policy`}>{t('payrollRuns.detail.policy')}</h2>
            <p>
              {t('payrollRuns.preview.policyText', {
                sequence: detail.policy.sequence,
                payDay: payDayText(t, detail.policy.payDay),
                proration: t(`payrollRuns.proration.${detail.policy.prorationMethod}`),
              })}
            </p>
            <p className="muted">{t('payrollRuns.policy.advisory')}</p>
          </section>
          <section
            className="card"
            aria-labelledby={`${ids}-lines`}
            data-testid="payroll-detail-lines"
          >
            <h2 id={`${ids}-lines`}>{t('payrollRuns.detail.lines')}</h2>
            <DraftLabel testId="payroll-lines-draft-label" />
            {detail.lines.length === 0 ? (
              <p className="muted">{t('payrollRuns.detail.noLines')}</p>
            ) : (
              <LinesTable
                lines={detail.lines}
                caption={t('payrollRuns.detail.linesCaption')}
                testId="payroll-detail-table"
                withSources
              />
            )}
            {moreFailure && (
              <PayrollAlert
                failure={moreFailure}
                testId="payroll-detail-more-error"
                alertRef={focus.register('moreError')}
              />
            )}
            {detail.nextCursor && (
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--secondary"
                  data-testid="payroll-detail-more"
                  disabled={moreBusy}
                  onClick={() => void loadMore()}
                >
                  {moreBusy ? t('payrollRuns.loadingMore') : t('payrollRuns.loadMoreLines')}
                </button>
              </div>
            )}
          </section>
        </>
      )}
    </section>
  );
}

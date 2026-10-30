import type { PayrollRunSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { DraftLabel, Money, PayrollAlert, RunStatus } from './PayrollBits';
import {
  formatMonth,
  LIST_LIMIT,
  NETWORK_FAILURE,
  payrollFailureOf,
  type PayrollFailure,
} from './payrollRuns';
import { PolicyPanel, type PolicyLoad } from './PolicyPanel';
import { RunCreatePanel } from './RunCreatePanel';

type ListLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: PayrollRunSummary[]; nextCursor: string | null };

/** The runs of a legal entity, newest period first, with keyset "load more". */
function RunList({ legalEntityId }: { legalEntityId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<ListLoad>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<PayrollFailure | null>(null);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payroll-runs',
          {
            params: {
              path: { legalEntityId },
              query: cursor ? { cursor, limit: LIST_LIMIT } : { limit: LIST_LIMIT },
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
    [core, legalEntityId],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((result) => {
      if (!active) return;
      setLoad(
        result.data
          ? { kind: 'ready', items: result.data.items, nextCursor: result.data.nextCursor }
          : { kind: 'failed', failure: result.failure },
      );
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  const loadMore = async () => {
    if (load.kind !== 'ready' || !load.nextCursor || loading.current) return;
    loading.current = true;
    setMoreBusy(true);
    setMoreFailure(null);
    const result = await fetchPage(load.nextCursor);
    if (result.data) {
      const page = result.data;
      setLoad((current) =>
        current.kind === 'ready'
          ? { kind: 'ready', items: [...current.items, ...page.items], nextCursor: page.nextCursor }
          : current,
      );
    } else {
      setMoreFailure(result.failure);
    }
    loading.current = false;
    setMoreBusy(false);
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="payroll-list">
      <h2 id={`${ids}-title`}>{t('payrollRuns.list.title')}</h2>
      <DraftLabel />
      {load.kind === 'loading' && (
        <p role="status" data-testid="payroll-list-loading">
          {t('payrollRuns.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="payroll-list-error" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="payroll-list-empty">
            {t('payrollRuns.list.empty')}
          </p>
        ) : (
          <ScrollRegion label={t('payrollRuns.list.tableLabel')}>
            <table className="timesheet-table" data-testid="payroll-list-table">
              <caption className="visually-hidden">{t('payrollRuns.list.tableLabel')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('payrollRuns.list.period')}</th>
                  <th scope="col">{t('payrollRuns.list.currency')}</th>
                  <th scope="col">{t('payrollRuns.list.version')}</th>
                  <th scope="col">{t('payrollRuns.list.status')}</th>
                  <th scope="col">{t('payrollRuns.employeeCount')}</th>
                  <th scope="col">{t('payrollRuns.contractualGross')}</th>
                  <th scope="col">{t('payrollRuns.list.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {load.items.map((run) => (
                  <tr key={run.runId} data-testid="payroll-run-row" data-status={run.status}>
                    <th scope="row">{formatMonth(i18n.language, run.period)}</th>
                    <td>{run.currency}</td>
                    <td>{run.version}</td>
                    <td>
                      <RunStatus status={run.status} />
                    </td>
                    <td>{run.employeeCount}</td>
                    <td>
                      <Money money={run.total} />
                    </td>
                    <td>
                      <Link
                        className="table-link"
                        to={`/admin/payroll-runs/${run.legalEntityId}/${run.runId}`}
                      >
                        {t('payrollRuns.list.open')}
                        <span className="visually-hidden">
                          {' '}
                          {t('payrollRuns.list.openContext', {
                            period: formatMonth(i18n.language, run.period),
                            currency: run.currency,
                            version: run.version,
                          })}
                        </span>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        ))}
      {moreFailure && <PayrollAlert failure={moreFailure} testId="payroll-list-more-error" />}
      {load.kind === 'ready' && load.nextCursor && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            data-testid="payroll-list-more"
            disabled={moreBusy}
            onClick={() => void loadMore()}
          >
            {moreBusy ? t('payrollRuns.loadingMore') : t('payrollRuns.loadMore')}
          </button>
        </div>
      )}
    </section>
  );
}

/**
 * MVP-094A: « Exécutions de paie » / "Payroll runs" under Payroll. A tenant administrator with
 * exact MFA chooses a legal entity, sets its employer policy, previews and creates a monthly draft
 * run and reads its frozen evidence. Contractual gross is monthly base compensation after the
 * policy; it is a draft, not statutory payroll, not net pay and not money owed.
 */
export function PayrollRunsPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [entityId, setEntityId] = useState('');
  const [policyState, setPolicyState] = useState<{ key: string; load: PolicyLoad } | null>(null);
  const [policyRevision, setPolicyRevision] = useState(0);
  const [runRevision, setRunRevision] = useState(0);
  const [announcement, setAnnouncement] = useState('');

  const entities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const selected = entityId || (entities.items.length === 1 ? (entities.items[0]?.id ?? '') : '');
  const single = entities.items.length === 1;

  const policyKey = `${selected}:${String(policyRevision)}`;
  const policy: PolicyLoad =
    policyState?.key === policyKey ? policyState.load : { kind: 'loading' };

  useEffect(() => {
    if (!selected) return;
    let active = true;
    const key = `${selected}:${String(policyRevision)}`;
    const set = (load: PolicyLoad) => {
      if (active) setPolicyState({ key, load });
    };
    core
      .GET('/legal-entities/{legalEntityId}/payroll-policy', {
        params: { path: { legalEntityId: selected } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          set(
            data
              ? { kind: 'ready', view: data }
              : { kind: 'failed', failure: payrollFailureOf(response, error) },
          );
        },
        () => {
          set({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, selected, policyRevision]);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="payroll-runs">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payrollRuns.title')}
        description={t('payrollRuns.description')}
      />
      <p className="card" data-testid="payroll-runs-notice">
        <strong>{t('payrollRuns.notice')}</strong>
      </p>
      <p role="status" className="visually-hidden" data-testid="payroll-runs-announcer">
        {announcement}
      </p>

      <section className="card" aria-labelledby={`${ids}-entity`}>
        <h2 id={`${ids}-entity`}>{t('payrollRuns.entity.title')}</h2>
        {entities.status === 'loading' && <p role="status">{t('payrollRuns.loading')}</p>}
        {entities.status === 'failed' && (
          <p className="field__error" role="alert" data-testid="payroll-entities-error">
            {t('payrollRuns.entity.unavailable')}
          </p>
        )}
        {entities.status === 'ready' && entities.items.length === 0 && (
          <p className="muted" data-testid="payroll-entities-none">
            {t('payrollRuns.entity.none')}
          </p>
        )}
        {single && entities.items[0] && (
          <p data-testid="payroll-entity-name">
            {t('workSchedules.siteName', {
              name: entities.items[0].name,
              code: entities.items[0].code,
            })}
          </p>
        )}
        {entities.items.length > 1 && (
          <div className="field">
            <label htmlFor={`${ids}-entity-select`}>{t('payrollRuns.entity.label')}</label>
            <select
              id={`${ids}-entity-select`}
              value={entityId}
              data-testid="payroll-entity"
              onChange={(event) => {
                setEntityId(event.target.value);
                setRunRevision((value) => value + 1);
              }}
            >
              <option value="">{t('payrollRuns.entity.choose')}</option>
              {entities.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
          </div>
        )}
      </section>

      {selected && (
        <>
          <PolicyPanel
            key={`policy-${selected}`}
            legalEntityId={selected}
            load={policy}
            onReload={() => {
              setPolicyRevision((value) => value + 1);
            }}
            onChanged={setAnnouncement}
          />
          <RunCreatePanel
            key={`create-${selected}`}
            legalEntityId={selected}
            businessDate={policy.kind === 'ready' ? policy.view.businessDate : null}
            onCreated={() => {
              setRunRevision((value) => value + 1);
            }}
          />
          <RunList key={`${selected}-${String(runRevision)}`} legalEntityId={selected} />
        </>
      )}
    </section>
  );
}

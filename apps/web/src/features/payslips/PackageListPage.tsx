import type { PayrollPackageStatus, PayrollPackageSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { Amount } from '../statutory/StatutoryBits';
import { ApprovalLabel, PackageStatus, SyntheticBadge } from './PayslipBits';
import { PACKAGE_STATUSES, packageFailureOf, PAGE_LIMIT } from './payslips';
import { formatInstant } from '../compensation/compensation';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: PayrollPackageSummary[]; nextCursor: string | null };

/** The packages of a legal entity, newest period first, with keyset "load more". */
function PackageList({
  legalEntityId,
  status,
}: {
  legalEntityId: string;
  status: PayrollPackageStatus | '';
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<PayrollFailure | null>(null);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const query = {
          limit: PAGE_LIMIT,
          ...(cursor ? { cursor } : {}),
          ...(status ? { status } : {}),
        };
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payroll-packages',
          { params: { path: { legalEntityId }, query }, cache: 'no-store' },
        );
        return data
          ? { data, failure: null }
          : { data: null, failure: packageFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core, legalEntityId, status],
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
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="package-list">
      <h2 id={`${ids}-title`}>{t('payrollPackages.list.title')}</h2>
      <ApprovalLabel />
      {load.kind === 'loading' && (
        <p role="status" data-testid="package-list-loading">
          {t('payrollPackages.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="package-list-error" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="package-list-empty">
            {t('payrollPackages.list.empty')}
          </p>
        ) : (
          <ScrollRegion label={t('payrollPackages.list.tableLabel')}>
            <table className="timesheet-table" data-testid="package-list-table">
              <caption className="visually-hidden">{t('payrollPackages.list.tableLabel')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('payrollPackages.review.period')}</th>
                  <th scope="col">{t('payrollPackages.list.version')}</th>
                  <th scope="col">{t('payrollPackages.list.status')}</th>
                  <th scope="col">{t('payrollPackages.list.pack')}</th>
                  <th scope="col">{t('payrollPackages.review.payslipCount')}</th>
                  <th scope="col">{t('payrollPackages.totals.approvedNet')}</th>
                  <th scope="col">{t('payrollPackages.detail.approvedAt')}</th>
                  <th scope="col">{t('payrollPackages.list.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {load.items.map((item) => (
                  <tr key={item.packageId} data-testid="package-row" data-status={item.status}>
                    <th scope="row">{formatMonth(i18n.language, item.period)}</th>
                    <td>{item.version}</td>
                    <td>
                      <PackageStatus status={item.status} />
                    </td>
                    <td>
                      {t('payrollPackages.packTitle', {
                        code: item.pack.code,
                        version: item.pack.version,
                      })}{' '}
                      {item.synthetic && <SyntheticBadge />}
                    </td>
                    <td>{item.payslipCount}</td>
                    <td>
                      <Amount money={item.totals.approvedNet} />
                    </td>
                    <td>{formatInstant(i18n.language, item.approvedAt)}</td>
                    <td>
                      <Link
                        className="table-link"
                        to={`/admin/payroll-packages/${item.legalEntityId}/${item.packageId}`}
                        data-testid="package-open"
                      >
                        {t('payrollPackages.list.open')}
                        <span className="visually-hidden">
                          {' '}
                          {t('payrollPackages.list.openContext', {
                            period: formatMonth(i18n.language, item.period),
                            version: item.version,
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
      {moreFailure && <PayrollAlert failure={moreFailure} testId="package-list-more-error" />}
      {load.kind === 'ready' && load.nextCursor && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            data-testid="package-list-more"
            disabled={moreBusy}
            onClick={() => void loadMore()}
          >
            {moreBusy ? t('payrollRuns.loadingMore') : t('payrollPackages.list.loadMore')}
          </button>
        </div>
      )}
    </section>
  );
}

/**
 * MVP-094C: « Paie approuvée » / "Approved payroll" under Payroll. A tenant administrator with
 * exact MFA chooses a legal entity and reads its approved and voided payroll packages. A package
 * is created from a statutory calculation (Payroll runs, Statutory preview), never here.
 */
export function PackageListPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [entityId, setEntityId] = useState('');
  const [status, setStatus] = useState<PayrollPackageStatus | ''>('');
  const entities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const selected = entityId || (entities.items.length === 1 ? (entities.items[0]?.id ?? '') : '');
  const single = entities.items.length === 1;
  return (
    <section
      className="statutory-page payslip-page"
      aria-labelledby={`${ids}-title`}
      data-testid="package-page"
    >
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payrollPackages.title')}
        description={t('payrollPackages.description')}
      />
      <p className="card" data-testid="package-notice">
        <strong>{t('payrollPackages.notice')}</strong>
      </p>
      <section className="card" aria-labelledby={`${ids}-entity`}>
        <h2 id={`${ids}-entity`}>{t('payrollRuns.entity.title')}</h2>
        {entities.status === 'loading' && <p role="status">{t('payrollPackages.loading')}</p>}
        {entities.status === 'failed' && (
          <p className="field__error" role="alert" data-testid="package-entities-error">
            {t('payrollRuns.entity.unavailable')}
          </p>
        )}
        {entities.status === 'ready' && entities.items.length === 0 && (
          <p className="muted" data-testid="package-entities-none">
            {t('payrollRuns.entity.none')}
          </p>
        )}
        {single && entities.items[0] && (
          <p data-testid="package-entity-name">
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
              data-testid="package-entity"
              onChange={(event) => {
                setEntityId(event.target.value);
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
        {selected && (
          <div className="field">
            <label htmlFor={`${ids}-status`}>{t('payrollPackages.list.filter')}</label>
            <select
              id={`${ids}-status`}
              value={status}
              data-testid="package-status-filter"
              onChange={(event) => {
                setStatus(event.target.value as PayrollPackageStatus | '');
              }}
            >
              <option value="">{t('payrollPackages.list.allStatuses')}</option>
              {PACKAGE_STATUSES.map((value) => (
                <option key={value} value={value}>
                  {t(`payrollPackages.status.${value}`)}
                </option>
              ))}
            </select>
          </div>
        )}
      </section>
      {selected && (
        <PackageList key={`${selected}/${status}`} legalEntityId={selected} status={status} />
      )}
    </section>
  );
}

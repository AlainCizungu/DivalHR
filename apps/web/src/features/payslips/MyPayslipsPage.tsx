import type { PayslipSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { Amount } from '../statutory/StatutoryBits';
import { NotProof, PackageStatus } from './PayslipBits';
import { PayslipViewer } from './PayslipViewer';
import { documentLocale, packageFailureOf, PAGE_LIMIT } from './payslips';
import { usePdfDownload } from './usePdfDownload';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: PayslipSummary[]; nextCursor: string | null };

/**
 * MVP-094C: « Mes bulletins de paie » / "My payslips". A linked employee sees only their own
 * published payslips, newest first (opaque keyset, "Load more"), each APPROVED or visibly VOIDED
 * with the closed reason label, opens the HTML view and downloads the PDF. There is no
 * employer-wide total, other employee, approver, administrator note or payment claim.
 */
export function MyPayslipsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<PayrollFailure | null>(null);
  const loading = useRef(false);
  const pdf = usePdfDownload({ kind: 'me' });
  const locale = documentLocale(i18n.language);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET('/me/payslips', {
          params: { query: cursor ? { cursor, limit: PAGE_LIMIT } : { limit: PAGE_LIMIT } },
          cache: 'no-store',
        });
        return data
          ? { data, failure: null }
          : { data: null, failure: packageFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core],
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
    <section
      className="statutory-page payslip-page"
      aria-labelledby={`${ids}-title`}
      data-testid="my-payslips"
    >
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payslips.my.title')}
        description={t('payslips.my.description')}
      />
      <NotProof testId="my-payslips-not-proof" />
      <p role="status" className="visually-hidden" data-testid="my-payslips-announcer">
        {pdf.announcement}
      </p>
      {pdf.failure && <PayrollAlert failure={pdf.failure} testId="my-payslips-download-error" />}
      {load.kind === 'loading' && (
        <p role="status" data-testid="my-payslips-loading">
          {t('payrollPackages.loading')}
        </p>
      )}
      {load.kind === 'failed' && <PayrollAlert failure={load.failure} testId="my-payslips-error" />}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="card muted" data-testid="my-payslips-empty">
            {t('payslips.my.empty')}
          </p>
        ) : (
          <section className="card" aria-labelledby={`${ids}-list`}>
            <h2 id={`${ids}-list`}>{t('payslips.my.listTitle')}</h2>
            <ScrollRegion label={t('payslips.my.tableLabel')}>
              <table className="timesheet-table" data-testid="my-payslips-table">
                <caption className="visually-hidden">{t('payslips.my.tableLabel')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('payrollPackages.review.period')}</th>
                    <th scope="col">{t('payrollPackages.list.status')}</th>
                    <th scope="col">{t('payrollRuns.contractualGross')}</th>
                    <th scope="col">{t('statutory.totals.employeeDeductions')}</th>
                    <th scope="col">{t('payrollPackages.totals.approvedNet')}</th>
                    <th scope="col">{t('payrollPackages.detail.approvedAt')}</th>
                    <th scope="col">{t('payrollPackages.list.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {load.items.map((item) => (
                    <tr key={item.payslipId} data-testid="my-payslip-row" data-status={item.status}>
                      <th scope="row">{formatMonth(i18n.language, item.period)}</th>
                      <td>
                        <PackageStatus status={item.status} />
                        {item.status === 'VOIDED' && (
                          <p className="payslip-voided" data-testid="my-payslip-voided">
                            <strong>{t('payslips.my.voided')}</strong>
                            {item.voidReasonCode && (
                              <>
                                {' '}
                                <span data-code={item.voidReasonCode}>
                                  {t(`payrollPackages.voidReason.${item.voidReasonCode}`)}
                                </span>
                              </>
                            )}
                          </p>
                        )}
                      </td>
                      <td>
                        <Amount money={item.contractualGross} />
                      </td>
                      <td>
                        <Amount money={item.employeeDeductions} />
                      </td>
                      <td data-testid="my-payslip-net">
                        <Amount money={item.approvedNet} />
                      </td>
                      <td>{formatInstant(i18n.language, item.approvedAt)}</td>
                      <td>
                        <Link
                          className="table-link"
                          to={`/me/payslips/${item.payslipId}`}
                          data-testid="my-payslip-open"
                        >
                          {t('payslips.view.open')}
                          <span className="visually-hidden">
                            {' '}
                            {t('payslips.my.context', {
                              period: formatMonth(i18n.language, item.period),
                            })}
                          </span>
                        </Link>{' '}
                        <button
                          type="button"
                          className="button button--secondary"
                          data-testid="my-payslip-download"
                          disabled={pdf.busyId !== null}
                          onClick={() => {
                            void pdf.download(
                              item.payslipId,
                              item.period,
                              locale,
                              t('payslips.downloaded'),
                            );
                          }}
                        >
                          {t('payslips.downloadPdf')}
                          <span className="visually-hidden">
                            {' '}
                            {t('payslips.my.context', {
                              period: formatMonth(i18n.language, item.period),
                            })}
                          </span>
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
            {moreFailure && <PayrollAlert failure={moreFailure} testId="my-payslips-more-error" />}
            {load.nextCursor && (
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--secondary"
                  data-testid="my-payslips-more"
                  disabled={moreBusy}
                  onClick={() => void loadMore()}
                >
                  {moreBusy ? t('payrollRuns.loadingMore') : t('payslips.my.loadMore')}
                </button>
              </div>
            )}
          </section>
        ))}
    </section>
  );
}

/** The HTML view of one of the employee's own payslips, with the document language switch. */
export function MyPayslipPage() {
  const { t } = useTranslation();
  const { payslipId = '' } = useParams();
  return (
    <PayslipViewer
      key={payslipId}
      scope={{ kind: 'me' }}
      payslipId={payslipId}
      backTo="/me/payslips"
      backLabel={t('payslips.view.backMine')}
      titleId="my-payslip-title"
    />
  );
}

/** The HTML view of one payslip of a package, for the administrator. */
export function PackagePayslipPage() {
  const { t } = useTranslation();
  const { legalEntityId = '', packageId = '', payslipId = '' } = useParams();
  return (
    <PayslipViewer
      key={payslipId}
      scope={{ kind: 'admin', legalEntityId, packageId }}
      payslipId={payslipId}
      backTo={`/admin/payroll-packages/${legalEntityId}/${packageId}`}
      backLabel={t('payslips.view.backPackage')}
      titleId="package-payslip-title"
    />
  );
}

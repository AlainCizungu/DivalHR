import type { PayrollPackageDetail, PayslipSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { Amount } from '../statutory/StatutoryBits';
import { TotalsCards } from './ApprovalReviewPage';
import {
  ApprovalLabel,
  NotProof,
  PackageStatus,
  RulePackStatement,
  SyntheticBadge,
  SyntheticNotice,
} from './PayslipBits';
import { packageFailureOf, PAYSLIP_PAGE_LIMIT } from './payslips';
import { usePdfDownload } from './usePdfDownload';
import { VoidPanel } from './VoidPanel';

type DetailLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; detail: PayrollPackageDetail };

type PayslipLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: PayslipSummary[]; nextCursor: string | null };

function PackagePayslips({
  legalEntityId,
  packageId,
  revision,
}: {
  legalEntityId: string;
  packageId: string;
  revision: number;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<PayslipLoad>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<PayrollFailure | null>(null);
  const loading = useRef(false);
  const pdf = usePdfDownload({ kind: 'admin', legalEntityId, packageId });
  const locale = i18n.language.toLowerCase().startsWith('fr') ? 'fr' : 'en';

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payslips',
          {
            params: {
              path: { legalEntityId, packageId },
              query: cursor ? { cursor, limit: PAYSLIP_PAGE_LIMIT } : { limit: PAYSLIP_PAGE_LIMIT },
            },
            cache: 'no-store',
          },
        );
        return data
          ? { data, failure: null }
          : { data: null, failure: packageFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core, legalEntityId, packageId],
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
  }, [fetchPage, revision]);

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
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="package-payslips">
      <h2 id={`${ids}-title`}>{t('payrollPackages.payslips.title')}</h2>
      <p className="muted">{t('payrollPackages.payslips.help')}</p>
      <p role="status" className="visually-hidden" data-testid="package-payslips-announcer">
        {pdf.announcement}
      </p>
      {pdf.failure && (
        <PayrollAlert failure={pdf.failure} testId="package-payslip-download-error" />
      )}
      {load.kind === 'loading' && <p role="status">{t('payrollPackages.loading')}</p>}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="package-payslips-error" />
      )}
      {load.kind === 'ready' && (
        <ScrollRegion label={t('payrollPackages.payslips.tableLabel')}>
          <table className="timesheet-table" data-testid="package-payslips-table">
            <caption className="visually-hidden">
              {t('payrollPackages.payslips.tableLabel')}
            </caption>
            <thead>
              <tr>
                <th scope="col">{t('statutory.lines.employee')}</th>
                <th scope="col">{t('payrollPackages.list.status')}</th>
                <th scope="col">{t('payrollRuns.contractualGross')}</th>
                <th scope="col">{t('statutory.totals.employeeDeductions')}</th>
                <th scope="col">{t('payrollPackages.totals.approvedNet')}</th>
                <th scope="col">{t('payrollPackages.lines.employerSeparate')}</th>
                <th scope="col">{t('payrollPackages.list.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {load.items.map((item) => (
                <tr
                  key={item.payslipId}
                  data-testid="package-payslip-row"
                  data-status={item.status}
                >
                  <th scope="row">{item.employeeNumber}</th>
                  <td>
                    <PackageStatus status={item.status} />
                  </td>
                  <td>
                    <Amount money={item.contractualGross} />
                  </td>
                  <td>
                    <Amount money={item.employeeDeductions} />
                  </td>
                  <td data-testid="package-payslip-net">
                    <Amount money={item.approvedNet} />
                  </td>
                  <td>
                    <Amount money={item.employerContributions} />
                  </td>
                  <td>
                    <Link
                      className="table-link"
                      to={`/admin/payroll-packages/${legalEntityId}/${packageId}/payslips/${item.payslipId}`}
                      data-testid="package-payslip-view"
                    >
                      {t('payslips.view.open')}
                      <span className="visually-hidden">
                        {' '}
                        {t('payslips.view.openContext', { number: item.employeeNumber })}
                      </span>
                    </Link>{' '}
                    <button
                      type="button"
                      className="button button--secondary"
                      data-testid="package-payslip-download"
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
                        {t('payslips.view.openContext', { number: item.employeeNumber })}
                      </span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {moreFailure && <PayrollAlert failure={moreFailure} testId="package-payslips-more-error" />}
      {load.kind === 'ready' && load.nextCursor && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            data-testid="package-payslips-more"
            disabled={moreBusy}
            onClick={() => void loadMore()}
          >
            {moreBusy ? t('payrollRuns.loadingMore') : t('payrollPackages.payslips.loadMore')}
          </button>
        </div>
      )}
    </section>
  );
}

/**
 * MVP-094C: one approved or voided payroll package. Sources, rule pack, totals, the same-preparer
 * fact, the voided evidence (the closed reason; the restricted explanation only here, only for an
 * administrator), the package's payslips with HTML view and PDF download, and the void workflow
 * of an APPROVED package.
 */
export function PackageDetailPage() {
  const { legalEntityId = '', packageId = '' } = useParams();
  return (
    <Detail
      key={`${legalEntityId}/${packageId}`}
      legalEntityId={legalEntityId}
      packageId={packageId}
    />
  );
}

function Detail({ legalEntityId, packageId }: { legalEntityId: string; packageId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const focus = useFocusRequest();
  const [load, setLoad] = useState<DetailLoad>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payroll-packages/{packageId}', {
        params: { path: { legalEntityId, packageId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', detail: data }
              : { kind: 'failed', failure: packageFailureOf(response, error) },
          );
          focus.request('title');
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
    // The focus helper is stable in behaviour.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [core, legalEntityId, packageId, revision]);

  const detail = load.kind === 'ready' ? load.detail : null;
  const pkg = detail?.package;
  const voided = pkg?.status === 'VOIDED';
  return (
    <section
      className="statutory-page payslip-page"
      aria-labelledby={`${ids}-title`}
      data-testid="package-detail"
    >
      <PageHeader
        titleId={`${ids}-title`}
        title={
          pkg
            ? t('payrollPackages.detail.title', {
                period: formatMonth(i18n.language, pkg.period),
                version: pkg.version,
              })
            : t('payrollPackages.detail.fallbackTitle')
        }
        description={t('payrollPackages.detail.description')}
      />
      <ApprovalLabel />
      <p>
        <Link className="table-link" to="/admin/payroll-packages">
          {t('payrollPackages.detail.back')}
        </Link>
      </p>
      <p role="status" className="visually-hidden" data-testid="package-announcer">
        {announcement}
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="package-detail-loading">
          {t('payrollPackages.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert
          failure={load.failure}
          testId="package-detail-error"
          alertRef={focus.register('title')}
        />
      )}
      {detail && pkg && (
        <>
          {voided && (
            <div className="payslip-voided" role="status" data-testid="package-voided-banner">
              <p>
                <strong>{t('payrollPackages.detail.voidedBanner')}</strong>
              </p>
              {pkg.voidReasonCode && (
                <p data-testid="package-void-reason" data-code={pkg.voidReasonCode}>
                  {t('payslips.doc.voidReason', {
                    reason: t(`payrollPackages.voidReason.${pkg.voidReasonCode}`),
                  })}
                </p>
              )}
              {pkg.voidedAt && (
                <p className="muted">
                  {t('payrollPackages.detail.voidedAt', {
                    at: formatInstant(i18n.language, pkg.voidedAt),
                  })}
                </p>
              )}
              {detail.void?.explanation && (
                <p data-testid="package-void-explanation">
                  {t('payrollPackages.detail.explanation', { text: detail.void.explanation })}
                </p>
              )}
            </div>
          )}
          <section
            className="card"
            aria-labelledby={`${ids}-summary`}
            data-testid="package-summary"
          >
            <h2 id={`${ids}-summary`} tabIndex={-1} ref={focus.register('title')}>
              {t('payrollPackages.detail.summary')}
            </h2>
            <p>
              <PackageStatus status={pkg.status} /> {pkg.synthetic && <SyntheticBadge />}
            </p>
            {pkg.synthetic && <SyntheticNotice />}
            <NotProof />
            <RulePackStatement pack={pkg.pack} />
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('payrollPackages.review.run')}</dt>
              <dd data-testid="package-run">
                {t('payrollPackages.review.versionOf', { version: pkg.runVersion })}{' '}
                <code>{pkg.runId}</code>
              </dd>
              <dt>{t('payrollPackages.review.calculation')}</dt>
              <dd data-testid="package-calculation">
                {t('payrollPackages.review.versionOf', { version: pkg.calculationVersion })}{' '}
                <code>{pkg.calculationId}</code>
              </dd>
              <dt>{t('payrollPackages.review.currency')}</dt>
              <dd>{pkg.currency}</dd>
              <dt>{t('payrollPackages.review.policy')}</dt>
              <dd>{detail.evidence.policySequence}</dd>
              <dt>{t('payrollPackages.review.payslipCount')}</dt>
              <dd data-testid="package-count">{pkg.payslipCount}</dd>
              <dt>{t('payrollPackages.detail.approvedAt')}</dt>
              <dd>{formatInstant(i18n.language, pkg.approvedAt)}</dd>
              <dt>{t('payrollPackages.detail.confirmations')}</dt>
              <dd>{t('payrollPackages.detail.confirmationsDone')}</dd>
            </dl>
            {pkg.samePreparer && (
              <p className="muted" data-testid="package-same-preparer">
                {t('payrollPackages.samePreparer.recorded')}
              </p>
            )}
            <TotalsCards totals={pkg.totals} testId="package-totals" />
            <p className="muted">{t('payrollPackages.lines.employerNote')}</p>
          </section>
          <PackagePayslips
            legalEntityId={legalEntityId}
            packageId={packageId}
            revision={revision}
          />
          {pkg.status === 'APPROVED' && (
            <VoidPanel
              legalEntityId={legalEntityId}
              packageId={packageId}
              onVoided={() => {
                setAnnouncement(t('payrollPackages.void.announce'));
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

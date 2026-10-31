import type { CompensationMoney, PayslipView } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { formatMoney } from '../compensation/compensation';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, formatMonth, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { NotProof, PackageStatus, SyntheticBadge } from './PayslipBits';
import { documentLocale, packageFailureOf } from './payslips';
import { usePdfDownload, type PdfScope } from './usePdfDownload';

/**
 * The accessible primary representation of one immutable payslip snapshot: semantic headings and
 * tables, rendered in the chosen document language. The wording sentences come from the server
 * (they are part of the document); the row labels come from the repository locale files in the
 * same language. Amounts are the snapshot's own and are identical in both languages; only the
 * digit grouping follows the document language.
 */
export function PayslipDocument({ view, locale }: { view: PayslipView; locale: 'en' | 'fr' }) {
  const { i18n } = useTranslation();
  const tDoc = i18n.getFixedT(locale);
  const { snapshot, wording } = view;
  const money = (value: CompensationMoney) => formatMoney(locale, value);
  const voided = view.status === 'VOIDED';
  const rows = [
    ['gross', snapshot.contractualGross],
    ['cnss', snapshot.employeeCnss],
    ['irpp', snapshot.irppWithholding],
    ['deductions', snapshot.employeeDeductions],
    ['net', snapshot.approvedNet],
  ] as const;
  return (
    <article
      className="card payslip-document"
      lang={locale}
      aria-labelledby="payslip-doc-title"
      data-testid="payslip-document"
      data-locale={locale}
      data-status={view.status}
    >
      {voided && (
        <div className="payslip-voided" role="status" data-testid="payslip-voided-banner">
          <p>
            <strong data-testid="payslip-voided-label">{wording.statusLabel}</strong>
          </p>
          <p>{wording.voidedNotice ?? tDoc('payslips.doc.voided')}</p>
          {view.voidReasonCode && (
            <p data-testid="payslip-void-reason" data-code={view.voidReasonCode}>
              {tDoc('payslips.doc.voidReason', {
                reason: tDoc(`payrollPackages.voidReason.${view.voidReasonCode}`),
              })}
            </p>
          )}
        </div>
      )}
      <h2 id="payslip-doc-title" data-testid="payslip-doc-title">
        {wording.title}
      </h2>
      <p>
        <strong data-testid="payslip-doc-status">{wording.statusLabel}</strong>
      </p>
      <dl className="decision-summary decision-summary--wrapping">
        <dt>{tDoc('payslips.doc.period')}</dt>
        <dd data-testid="payslip-doc-period">{formatMonth(locale, snapshot.period)}</dd>
        <dt>{tDoc('payslips.doc.employeeNumber')}</dt>
        <dd data-testid="payslip-doc-number">{snapshot.employeeNumber}</dd>
        <dt>{tDoc('payslips.doc.approvedAt')}</dt>
        <dd>
          {new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(
            new Date(snapshot.approvedAt),
          )}
        </dd>
      </dl>
      <p data-testid="payslip-doc-pack">{wording.rulePackStatement}</p>
      {view.synthetic && wording.syntheticNotice && (
        <p className="payslip-synthetic" role="note" data-testid="payslip-doc-synthetic">
          <strong>{wording.syntheticNotice}</strong>
        </p>
      )}
      {snapshot.excluded && (
        <p className="muted" data-testid="payslip-doc-excluded">
          {tDoc('payslips.doc.excluded')}
        </p>
      )}
      <ScrollRegion label={tDoc('payslips.doc.amountsCaption')}>
        <table className="timesheet-table" data-testid="payslip-doc-amounts">
          <caption className="visually-hidden">{tDoc('payslips.doc.amountsCaption')}</caption>
          <thead>
            <tr>
              <th scope="col">{tDoc('payslips.doc.item')}</th>
              <th scope="col">{tDoc('payslips.doc.amount')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(([key, value]) => (
              <tr key={key} data-testid={`payslip-doc-${key}`}>
                <th scope="row">{tDoc(`payslips.doc.rows.${key}`)}</th>
                <td>{money(value)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollRegion>
      <h3>{tDoc('payslips.doc.employerHeading')}</h3>
      <p data-testid="payslip-doc-employer">
        {tDoc('payslips.doc.employerContributions')}:{' '}
        <strong data-testid="payslip-doc-employer-amount">
          {money(snapshot.employerContributions)}
        </strong>
      </p>
      <p className="muted" data-testid="payslip-doc-employer-note">
        {wording.employerContributionsNote}
      </p>
      {snapshot.components.length > 0 && (
        <>
          <h3>{tDoc('payslips.doc.componentsHeading')}</h3>
          <ScrollRegion label={tDoc('payslips.doc.componentsCaption')}>
            <table className="timesheet-table" data-testid="payslip-doc-components">
              <caption className="visually-hidden">
                {tDoc('payslips.doc.componentsCaption')}
              </caption>
              <thead>
                <tr>
                  <th scope="col">{tDoc('payslips.doc.component')}</th>
                  <th scope="col">{tDoc('payslips.doc.party')}</th>
                  <th scope="col">{tDoc('payslips.doc.base')}</th>
                  <th scope="col">{tDoc('payslips.doc.amount')}</th>
                </tr>
              </thead>
              <tbody>
                {snapshot.components.map((component) => (
                  <tr key={component.seq} data-party={component.party}>
                    <th scope="row">
                      {tDoc(`statutory.component.${component.id}`, { defaultValue: component.id })}
                    </th>
                    <td>{tDoc(`statutory.party.${component.party}`)}</td>
                    <td>{money(component.base)}</td>
                    <td>{money(component.amount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        </>
      )}
      <p data-testid="payslip-doc-not-proof">
        <strong>{wording.notProofOfPayment}</strong>
      </p>
      <p className="muted">
        {tDoc('payslips.doc.digest')}{' '}
        <code data-testid="payslip-doc-digest">{view.snapshotDigest}</code>
      </p>
    </article>
  );
}

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; view: PayslipView };

/**
 * One payslip page (employee or administrator): the HTML view with an English / French switch for
 * the document wording, the status, and the PDF download in the chosen language.
 */
const LANGUAGE_OPTIONS = ['en', 'fr'] as const;

export function PayslipViewer({
  scope,
  payslipId,
  backTo,
  backLabel,
  titleId,
}: {
  scope: PdfScope;
  payslipId: string;
  backTo: string;
  backLabel: string;
  titleId: string;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const focus = useFocusRequest();
  const [locale, setLocale] = useState<'en' | 'fr'>(documentLocale(i18n.language));
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const pdf = usePdfDownload(scope);
  const kind = scope.kind;
  const legalEntityId = scope.kind === 'admin' ? scope.legalEntityId : '';
  const packageId = scope.kind === 'admin' ? scope.packageId : '';
  const first = useRef(true);

  const fetchView = useCallback(async () => {
    try {
      const result =
        kind === 'me'
          ? await core.GET('/me/payslips/{payslipId}', {
              params: { path: { payslipId }, query: { locale } },
              cache: 'no-store',
            })
          : await core.GET(
              '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payslips/{payslipId}',
              {
                params: { path: { legalEntityId, packageId, payslipId }, query: { locale } },
                cache: 'no-store',
              },
            );
      return result.data
        ? ({ kind: 'ready', view: result.data } as const)
        : ({ kind: 'failed', failure: packageFailureOf(result.response, result.error) } as const);
    } catch {
      return { kind: 'failed', failure: NETWORK_FAILURE } as const;
    }
  }, [core, kind, legalEntityId, packageId, payslipId, locale]);

  useEffect(() => {
    let active = true;
    void fetchView().then((next) => {
      if (!active) return;
      setLoad(next);
      if (first.current) {
        first.current = false;
        focus.request(next.kind === 'ready' ? 'title' : 'error');
      }
    });
    return () => {
      active = false;
    };
    // The focus helper is stable in behaviour.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchView]);

  const view = load.kind === 'ready' ? load.view : null;
  return (
    <section
      className="statutory-page payslip-page"
      aria-labelledby={titleId}
      data-testid="payslip-page"
    >
      <p>
        <Link className="table-link" to={backTo}>
          {backLabel}
        </Link>
      </p>
      <h1 id={titleId} tabIndex={-1} ref={focus.register('title')} className="page-header__title">
        {t('payslips.view.title')}
      </h1>
      <p className="muted" data-testid="payslip-label">
        <strong>{t('payrollPackages.label')}</strong>
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="payslip-loading">
          {t('payrollPackages.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert
          failure={load.failure}
          testId="payslip-error"
          alertRef={focus.register('error')}
        />
      )}
      {view && (
        <>
          <p>
            <PackageStatus status={view.status} /> {view.synthetic && <SyntheticBadge />}
          </p>
          <NotProof />
          <fieldset className="card" data-testid="payslip-language">
            <legend>{t('payslips.view.language')}</legend>
            {LANGUAGE_OPTIONS.map((option) => (
              <div className="choice" key={option}>
                <input
                  id={`${ids}-${option}`}
                  type="radio"
                  name={`${ids}-locale`}
                  checked={locale === option}
                  data-testid={`payslip-language-${option}`}
                  onChange={() => {
                    setLocale(option);
                  }}
                />
                <label htmlFor={`${ids}-${option}`}>{t(`payslips.view.languages.${option}`)}</label>
              </div>
            ))}
          </fieldset>
          <PayslipDocument view={view} locale={locale} />
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="payslip-download"
              disabled={pdf.busyId !== null}
              onClick={() => {
                void pdf.download(
                  view.payslipId,
                  view.snapshot.period,
                  locale,
                  t('payslips.downloaded'),
                );
              }}
            >
              {pdf.busyId ? t('payslips.downloading') : t('payslips.downloadPdf')}
              <span className="visually-hidden">
                {' '}
                {t('payslips.downloadLanguage', {
                  language: t(`payslips.view.languages.${locale}`),
                })}
              </span>
            </button>
          </div>
          <p role="status" className="visually-hidden" data-testid="payslip-announcer">
            {pdf.announcement}
          </p>
          {pdf.failure && <PayrollAlert failure={pdf.failure} testId="payslip-download-error" />}
        </>
      )}
    </section>
  );
}

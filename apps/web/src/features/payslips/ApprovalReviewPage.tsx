import type {
  CommitPayrollApproval,
  PayrollApprovalPreview,
  PayrollApprovalReceipt,
  PayrollPackageRulePack,
  PayrollPackageTotals,
} from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { Amount } from '../statutory/StatutoryBits';
import {
  ApprovalLabel,
  NotProof,
  PackageStatus,
  RulePackStatement,
  SyntheticBadge,
  SyntheticNotice,
} from './PayslipBits';
import { LINES_PAGE, packageFailureOf } from './payslips';

export function TotalsCards({ totals, testId }: { totals: PayrollPackageTotals; testId: string }) {
  const { t } = useTranslation();
  const entries = [
    ['contractualGross', totals.contractualGross, t('payrollRuns.contractualGross')],
    ['employeeDeductions', totals.employeeDeductions, t('statutory.totals.employeeDeductions')],
    [
      'employerContributions',
      totals.employerContributions,
      t('payrollPackages.totals.employerContributions'),
    ],
    ['approvedNet', totals.approvedNet, t('payrollPackages.totals.approvedNet')],
    ['employerCost', totals.employerCost, t('statutory.totals.employerCost')],
  ] as const;
  return (
    <ul className="statutory-totals" data-testid={testId}>
      {entries.map(([key, money, label]) => (
        <li key={key} data-testid={`package-total-${key}`}>
          <span className="muted">{label}</span>
          <strong>
            <Amount money={money} />
          </strong>
        </li>
      ))}
    </ul>
  );
}

function Receipt({
  receipt,
  pack,
  entityId,
}: {
  receipt: PayrollApprovalReceipt;
  pack: PayrollPackageRulePack | null;
  entityId: string;
}) {
  const { t, i18n } = useTranslation();
  return (
    <section
      className="card"
      aria-labelledby="receipt-title"
      data-testid="approval-receipt"
      data-package-id={receipt.packageId}
    >
      <h2 id="receipt-title" tabIndex={-1} data-testid="approval-receipt-title">
        {t('payrollPackages.receipt.title')}
      </h2>
      <p>
        <PackageStatus status={receipt.status} /> {receipt.synthetic && <SyntheticBadge />}
      </p>
      <NotProof testId="approval-receipt-not-proof" />
      <dl className="decision-summary decision-summary--wrapping">
        <dt>{t('payrollPackages.review.period')}</dt>
        <dd>{formatMonth(i18n.language, receipt.period)}</dd>
        <dt>{t('payrollPackages.list.version')}</dt>
        <dd>{receipt.version}</dd>
        <dt>{t('payrollPackages.review.payslipCount')}</dt>
        <dd data-testid="approval-receipt-count">{receipt.payslipCount}</dd>
        <dt>{t('payrollPackages.detail.approvedAt')}</dt>
        <dd>{formatInstant(i18n.language, receipt.approvedAt)}</dd>
      </dl>
      {pack && <RulePackStatement pack={pack} testId="approval-receipt-pack" />}
      {receipt.samePreparer && (
        <p className="muted" data-testid="approval-receipt-same-preparer">
          {t('payrollPackages.samePreparer.recorded')}
        </p>
      )}
      <p>
        <Link
          className="table-link"
          to={`/admin/payroll-packages/${entityId}/${receipt.packageId}`}
          data-testid="approval-receipt-open"
        >
          {t('payrollPackages.receipt.open')}
        </Link>
      </p>
    </section>
  );
}

/**
 * MVP-094C: the review and approval of one payroll package, reached from a statutory calculation.
 * A server preview (sources, pack, totals, blockers, warnings, the same-preparer fact and the
 * employee lines), the explicit three-part confirmation, and an amount-free receipt. Nothing is
 * calculated in the browser; the commit button states why it is disabled.
 */
export function ApprovalReviewPage() {
  const { legalEntityId = '', runId = '', calculationId = '' } = useParams();
  return (
    <Review
      key={`${legalEntityId}/${runId}/${calculationId}`}
      legalEntityId={legalEntityId}
      runId={runId}
      calculationId={calculationId}
    />
  );
}

function Review({
  legalEntityId,
  runId,
  calculationId,
}: {
  legalEntityId: string;
  runId: string;
  calculationId: string;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const focus = useFocusRequest();
  const [preview, setPreview] = useState<PayrollApprovalPreview | null>(null);
  const [receipt, setReceipt] = useState<{
    value: PayrollApprovalReceipt;
    pack: PayrollPackageRulePack | null;
  } | null>(null);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [totalsOk, setTotalsOk] = useState(false);
  const [exceptionsOk, setExceptionsOk] = useState(false);
  const [noPaymentOk, setNoPaymentOk] = useState(false);
  const [shown, setShown] = useState(LINES_PAGE);

  const path = { legalEntityId, runId, calculationId };
  const resetConfirmations = () => {
    setTotalsOk(false);
    setExceptionsOk(false);
    setNoPaymentOk(false);
    setShown(LINES_PAGE);
  };

  const onPreview = async () => {
    if (busy) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/{calculationId}/approval/preview',
        { params: { path }, body: {}, cache: 'no-store' },
      );
      resetConfirmations();
      commitKey.reset();
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(packageFailureOf(response, error));
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
    preview.rulePackDigest !== null &&
    preview.expiresAt !== null &&
    preview.totals !== null;
  const confirmed = totalsOk && exceptionsOk && noPaymentOk;
  const confirmations = [
    ['totals', totalsOk, setTotalsOk],
    ['exceptions', exceptionsOk, setExceptionsOk],
    ['noPayment', noPaymentOk, setNoPaymentOk],
  ] as const;
  const whyKey =
    preview === null
      ? null
      : preview.status === 'NO_CHANGE'
        ? 'payrollPackages.review.whyNoChange'
        : !committable
          ? 'payrollPackages.review.whyBlocked'
          : !confirmed
            ? 'payrollPackages.review.whyConfirm'
            : null;

  const onCommit = async () => {
    if (!preview || busy || !committable || !confirmed) return;
    if (
      preview.previewDigest === null ||
      preview.rulePackDigest === null ||
      preview.expiresAt === null
    ) {
      return;
    }
    const body: CommitPayrollApproval = {
      expectedVersion: preview.expectedVersion,
      sourceRunDigest: preview.sourceRunDigest,
      calculationDigest: preview.calculationDigest,
      profileDigest: preview.profileDigest,
      rulePackDigest: preview.rulePackDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedTotals: true,
      reviewedExceptions: true,
      understandsNoPayment: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/{calculationId}/approval',
        {
          params: {
            path,
            header: { 'Idempotency-Key': commitKey.keyFor({ ...path, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        setPreview(null);
        resetConfirmations();
        setReceipt({ value: data, pack: preview.pack });
        setAnnouncement(t('payrollPackages.review.announce'));
        focus.request('receipt');
        return;
      }
      const next = packageFailureOf(response, error);
      if (next.kind === 'stale') {
        // The preview is out of date: a new review is needed, with a new key and fresh confirmations.
        commitKey.reset();
        setPreview(null);
        resetConfirmations();
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

  const lines = preview?.lines ?? [];
  const sameWarning =
    preview !== null &&
    (preview.samePreparer || preview.warnings.includes('SAME_PREPARER_AND_APPROVER'));
  const otherWarnings = preview?.warnings.filter((w) => w !== 'SAME_PREPARER_AND_APPROVER') ?? [];
  const stale = failure?.kind === 'stale';
  return (
    <section
      className="statutory-page payslip-page"
      aria-labelledby={`${ids}-title`}
      data-testid="approval-review"
    >
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payrollPackages.review.title')}
        description={t('payrollPackages.review.description')}
      />
      <ApprovalLabel />
      <p>
        <Link
          className="table-link"
          to={`/admin/payroll-runs/${legalEntityId}/${runId}/statutory`}
          data-testid="approval-back"
        >
          {t('payrollPackages.review.back')}
        </Link>
      </p>
      <p role="status" className="visually-hidden" data-testid="approval-announcer">
        {announcement}
      </p>
      {receipt ? (
        <div ref={focus.register('receipt')} tabIndex={-1}>
          <Receipt receipt={receipt.value} pack={receipt.pack} entityId={legalEntityId} />
        </div>
      ) : (
        <section className="card" aria-labelledby={`${ids}-prep`} data-testid="approval-prepare">
          <h2 id={`${ids}-prep`}>{t('payrollPackages.review.prepareTitle')}</h2>
          <p className="muted">{t('payrollPackages.review.prepareHelp')}</p>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={busy !== null}
              data-testid="approval-preview-button"
              onClick={() => void onPreview()}
            >
              {busy === 'preview'
                ? t('payrollRuns.previewing')
                : stale
                  ? t('payrollPackages.review.previewAgain')
                  : t('payrollPackages.review.preview')}
            </button>
          </div>
        </section>
      )}
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="approval-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {stale && (
        <p className="card" role="status" data-testid="approval-stale-refresh">
          {t('payrollPackages.review.staleRefresh')}
        </p>
      )}
      {preview && (
        <section className="card" aria-labelledby={`${ids}-preview`} data-testid="approval-preview">
          <h2 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('payrollPackages.review.heading', {
              period: formatMonth(i18n.language, preview.period),
              currency: preview.currency,
            })}
          </h2>
          <p>
            <StatusBadge
              tone={
                preview.status === 'READY'
                  ? 'success'
                  : preview.status === 'BLOCKED'
                    ? 'danger'
                    : 'neutral'
              }
              testId="approval-preview-status"
            >
              {t(`payrollPackages.previewStatus.${preview.status}`)}
            </StatusBadge>{' '}
            {preview.pack?.synthetic && <SyntheticBadge />}
          </p>
          {preview.pack?.synthetic && <SyntheticNotice />}
          {preview.pack && <RulePackStatement pack={preview.pack} testId="approval-pack" />}
          <dl className="decision-summary decision-summary--wrapping">
            <dt>{t('payrollPackages.review.run')}</dt>
            <dd data-testid="approval-run">
              {t('payrollPackages.review.versionOf', { version: preview.runVersion })}{' '}
              <code>{preview.runId}</code>
            </dd>
            <dt>{t('payrollPackages.review.calculation')}</dt>
            <dd data-testid="approval-calculation">
              {t('payrollPackages.review.versionOf', { version: preview.calculationVersion })}{' '}
              <code>{preview.calculationId}</code>
            </dd>
            <dt>{t('payrollPackages.review.entity')}</dt>
            <dd>
              <code>{preview.legalEntityId}</code>
            </dd>
            <dt>{t('payrollPackages.review.period')}</dt>
            <dd>{formatMonth(i18n.language, preview.period)}</dd>
            <dt>{t('payrollPackages.review.currency')}</dt>
            <dd>{preview.currency}</dd>
            <dt>{t('payrollPackages.review.policy')}</dt>
            <dd>{preview.policySequence}</dd>
            <dt>{t('payrollPackages.review.payslipCount')}</dt>
            <dd data-testid="approval-count">{preview.payslipCount}</dd>
            <dt>{t('payrollPackages.review.excludedCount')}</dt>
            <dd data-testid="approval-excluded">{preview.excludedCount}</dd>
          </dl>
          {sameWarning && (
            <div className="error-summary" role="alert" data-testid="approval-same-preparer">
              <p>{t('payrollPackages.samePreparer.warning')}</p>
            </div>
          )}
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="approval-blockers">
              <p>{t('payrollPackages.review.blockersTitle')}</p>
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker} data-code={blocker} data-testid="approval-blocker">
                    {t(`payrollPackages.blockers.${blocker}`)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {otherWarnings.length > 0 && (
            <div className="card" data-testid="approval-warnings">
              <h3>{t('payrollPackages.review.warningsTitle')}</h3>
              <ul>
                {otherWarnings.map((warning) => (
                  <li key={warning} data-code={warning} data-testid="approval-warning">
                    {t(`payrollPackages.warnings.${warning}`)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {preview.totals ? (
            <TotalsCards totals={preview.totals} testId="approval-totals" />
          ) : (
            <p className="muted" data-testid="approval-no-totals">
              {t('payrollPackages.review.noTotals')}
            </p>
          )}
          {preview.status === 'NO_CHANGE' && (
            <div className="card" role="status" data-testid="approval-no-change">
              <p>{t('payrollPackages.review.identical')}</p>
              {preview.existingPackage && (
                <Link
                  className="table-link"
                  to={`/admin/payroll-packages/${legalEntityId}/${preview.existingPackage.packageId}`}
                  data-testid="approval-existing-link"
                >
                  {t('payrollPackages.review.openExisting', {
                    version: preview.existingPackage.version,
                  })}
                </Link>
              )}
            </div>
          )}
          {lines.length === 0 ? (
            <p className="muted">{t('payrollPackages.review.noLines')}</p>
          ) : (
            <>
              <h3>{t('payrollPackages.review.linesHeading')}</h3>
              <ScrollRegion label={t('payrollPackages.review.linesCaption')}>
                <table className="timesheet-table" data-testid="approval-lines">
                  <caption className="visually-hidden">
                    {t('payrollPackages.review.linesCaption')}
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('statutory.lines.employee')}</th>
                      <th scope="col">{t('statutory.lines.state')}</th>
                      <th scope="col">{t('payrollRuns.contractualGross')}</th>
                      <th scope="col">{t('statutory.totals.employeeDeductions')}</th>
                      <th scope="col">{t('payrollPackages.totals.approvedNet')}</th>
                      <th scope="col">{t('payrollPackages.lines.employerSeparate')}</th>
                      <th scope="col">{t('statutory.totals.employerCost')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {lines.slice(0, shown).map((line) => (
                      <tr
                        key={line.employeeId}
                        data-testid="approval-line"
                        data-excluded={line.excluded ? 'true' : 'false'}
                      >
                        <th scope="row">{line.employeeNumber}</th>
                        <td>
                          {line.excluded
                            ? t('statutory.lines.excluded')
                            : t('statutory.lines.calculated')}
                        </td>
                        <td data-testid="approval-line-gross">
                          <Amount money={line.contractualGross} />
                        </td>
                        <td data-testid="approval-line-deductions">
                          <Amount money={line.employeeDeductions} />
                        </td>
                        <td data-testid="approval-line-net">
                          <Amount money={line.approvedNet} />
                        </td>
                        <td data-testid="approval-line-employer">
                          <Amount money={line.employerContributions} />
                        </td>
                        <td data-testid="approval-line-cost">
                          <Amount money={line.employerCost} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
              <p className="muted">{t('payrollPackages.lines.employerNote')}</p>
              {lines.length > shown && (
                <div className="dialog-actions">
                  <button
                    type="button"
                    className="button button--secondary"
                    data-testid="approval-lines-more"
                    onClick={() => {
                      setShown((value) => value + LINES_PAGE);
                    }}
                  >
                    {t('payrollPackages.lines.showMore', { count: lines.length - shown })}
                  </button>
                </div>
              )}
            </>
          )}
          {committable && preview.expiresAt && (
            <p className="muted">
              {t('payrollRuns.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <fieldset className="card" data-testid="approval-confirmations">
            <legend>{t('payrollPackages.confirm.legend')}</legend>
            <p className="muted">{t('payrollPackages.confirm.help')}</p>
            {confirmations.map(([key, value, set]) => (
              <div className="choice" key={key}>
                <input
                  id={`${ids}-${key}`}
                  type="checkbox"
                  checked={value}
                  disabled={!committable}
                  data-testid={`approval-confirm-${key}`}
                  onChange={(event) => {
                    set(event.target.checked);
                  }}
                />
                <label htmlFor={`${ids}-${key}`}>{t(`payrollPackages.confirm.${key}`)}</label>
              </div>
            ))}
          </fieldset>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="approval-commit"
              disabled={busy !== null || !committable || !confirmed}
              aria-describedby={whyKey ? `${ids}-why` : undefined}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('payrollRuns.saving') : t('payrollPackages.review.commit')}
            </button>
          </div>
          {whyKey && (
            <p id={`${ids}-why`} className="muted" data-testid="approval-commit-why">
              {t(whyKey)}
            </p>
          )}
        </section>
      )}
    </section>
  );
}

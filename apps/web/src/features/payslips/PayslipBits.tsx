import type { PayrollPackageRulePack, PayrollPackageStatus } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';

/**
 * "Approved payroll—Not proof of payment": shown on every package and payslip view. An approval
 * is a review record, not a payment, a filing or a bank instruction.
 */
export function ApprovalLabel({ testId = 'package-label' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="muted" data-testid={testId}>
      <strong>{t('payrollPackages.label')}</strong>
    </p>
  );
}

/** The honest sentence on every payslip surface. */
export function NotProof({ testId = 'not-proof' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p data-testid={testId}>
      <strong>{t('payrollPackages.notProofOfPayment')}</strong>
    </p>
  );
}

export function PackageStatus({ status }: { status: PayrollPackageStatus }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={status === 'APPROVED' ? 'success' : 'danger'} testId="package-status">
      {t(`payrollPackages.status.${status}`)}
    </StatusBadge>
  );
}

/** The prominent synthetic / test marker: a rule pack whose code starts with SYNTHETIC-TEST. */
export function SyntheticBadge() {
  const { t } = useTranslation();
  return (
    <StatusBadge tone="warning" testId="synthetic-badge">
      {t('payrollPackages.synthetic.badge')}
    </StatusBadge>
  );
}

/** "Statutory calculation based on rule pack {code} v{version}". */
export function RulePackStatement({
  pack,
  testId = 'package-pack-statement',
}: {
  pack: Pick<PayrollPackageRulePack, 'code' | 'version'>;
  testId?: string;
}) {
  const { t } = useTranslation();
  return (
    <p data-testid={testId}>
      {t('payrollPackages.rulePackStatement', { code: pack.code, version: pack.version })}
    </p>
  );
}

export function SyntheticNotice() {
  const { t } = useTranslation();
  return (
    <p className="card payslip-synthetic" role="note" data-testid="synthetic-notice">
      <strong>{t('payrollPackages.synthetic.notice')}</strong>
    </p>
  );
}

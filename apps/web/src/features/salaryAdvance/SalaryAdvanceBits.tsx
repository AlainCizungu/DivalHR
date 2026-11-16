import type { SalaryAdvanceRequestStatus } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusTone } from '../../ui/primitives';

/**
 * The permanent notice (D120A-13): DivalHR is not the lender, employer eligibility is not credit
 * approval and no funds are sent. It is shown on every salary-advance page and never hidden.
 */
export function LenderNotice({ testId = 'salary-advance-notice' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="card" role="note" data-testid={testId}>
      <strong>{t('salaryAdvance.notice')}</strong>
    </p>
  );
}

const TONES: Record<SalaryAdvanceRequestStatus, StatusTone> = {
  SUBMITTED: 'info',
  EMPLOYER_CONFIRMED: 'success',
  EMPLOYER_REFUSED: 'warning',
  WITHDRAWN: 'neutral',
};

/** The closed status, always with its text (never colour alone). */
export function RequestStatus({ status }: { status: SalaryAdvanceRequestStatus }) {
  const { t } = useTranslation();
  return (
    <span data-testid="request-status" data-status={status}>
      <StatusBadge tone={TONES[status]}>{t(`salaryAdvance.status.${status}`)}</StatusBadge>
    </span>
  );
}

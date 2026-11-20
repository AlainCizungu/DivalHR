import type {
  SalaryAdvanceHandoffReviewState,
  SalaryAdvanceHandoffState,
} from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusTone } from '../../ui/primitives';

/**
 * The two permanent notices of every handoff screen (D120B-1): DivalHR is not the lender, and this
 * is a controlled test with a synthetic partner. Neither is ever hidden or collapsed.
 */
export function HandoffNotice({ testId = 'handoff-notice' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="card" role="note" data-testid={testId}>
      <strong>{t('salaryAdvance.handoff.notice')}</strong>
    </p>
  );
}

const STATE_TONES: Record<SalaryAdvanceHandoffState, StatusTone> = {
  AUTHORIZED: 'info',
  QUEUED: 'info',
  SENDING: 'info',
  RECEIVED: 'success',
  DELIVERY_UNKNOWN: 'warning',
  FAILED: 'warning',
  WITHDRAWN: 'neutral',
};

/** The delivery state, always with its text (never colour alone). */
export function HandoffState({ state }: { state: SalaryAdvanceHandoffState }) {
  const { t } = useTranslation();
  return (
    <span data-testid="handoff-state" data-state={state}>
      <StatusBadge tone={STATE_TONES[state]}>
        {t(`salaryAdvance.handoff.state.${state}`)}
      </StatusBadge>
    </span>
  );
}

const REVIEW_TONES: Record<SalaryAdvanceHandoffReviewState, StatusTone> = {
  RECEIVED: 'info',
  UNDER_REVIEW: 'info',
  MORE_INFORMATION_REQUIRED: 'warning',
  DECLINED: 'neutral',
  CANCELLED_BY_PARTNER: 'neutral',
};

/** The partner's bounded review status; a status report, never a decision of DivalHR. */
export function HandoffReview({ review }: { review: SalaryAdvanceHandoffReviewState | null }) {
  const { t } = useTranslation();
  if (review === null) return <span className="muted">—</span>;
  return (
    <span data-testid="handoff-review" data-review={review}>
      <StatusBadge tone={REVIEW_TONES[review]}>
        {t(`salaryAdvance.handoff.review.${review}`)}
      </StatusBadge>
    </span>
  );
}

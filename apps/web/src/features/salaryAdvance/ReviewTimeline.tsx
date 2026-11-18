import type {
  SalaryAdvanceHandoffReviewState,
  SalaryAdvanceHandoffView,
} from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { formatInstant } from '../compensation/compensation';
import { CompensationAlert } from '../compensation/CompensationBits';
import {
  NETWORK_FAILURE,
  salaryAdvanceFailureOf,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

/**
 * MVP-120C (D120C-7): the employee's own partner-review status and its immutable history, in plain
 * words. The partner status is a report of the controlled test partner, never a lending decision:
 * the statement that no loan was approved and no funds were disbursed is always shown with it, a
 * partner cancellation is explained as not being the employee's withdrawal, and a request for more
 * information is informational only (nothing can be uploaded or answered here).
 */
export function ReviewTimeline({
  requestId,
  review,
}: {
  requestId: string;
  review: SalaryAdvanceHandoffReviewState;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [open, setOpen] = useState(false);
  const [view, setView] = useState<SalaryAdvanceHandoffView | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);

  const toggle = async () => {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    try {
      const { data, error, response } = await core.GET(
        '/me/salary-advance-requests/{requestId}/handoff',
        { params: { path: { requestId } }, cache: 'no-store' },
      );
      if (data) {
        setView(data);
        setFailure(null);
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
  };

  const status = view?.reviewState ?? review;
  return (
    <div data-testid="review-timeline">
      <p className="muted" data-testid="review-help">
        {t(`salaryAdvance.handoff.lifecycle.help.${status}`)}
      </p>
      <p className="muted" data-testid="review-no-approval">
        {t('salaryAdvance.handoff.lifecycle.noApproval')}
      </p>
      <button
        type="button"
        className="button button--secondary"
        aria-expanded={open}
        aria-controls={`${ids}-history`}
        data-testid="review-timeline-toggle"
        onClick={() => void toggle()}
      >
        {t(open ? 'salaryAdvance.handoff.lifecycle.hide' : 'salaryAdvance.handoff.lifecycle.show')}
      </button>
      <div id={`${ids}-history`} hidden={!open}>
        {open && failure && <CompensationAlert failure={failure} testId="review-timeline-error" />}
        {open && !failure && !view && <p role="status">{t('salaryAdvance.loading')}</p>}
        {open && view && view.reviewEvents.length === 0 && (
          <p className="muted" data-testid="review-timeline-empty">
            {t('salaryAdvance.handoff.lifecycle.empty')}
          </p>
        )}
        {open && view && view.reviewEvents.length > 0 && (
          <ol
            aria-label={t('salaryAdvance.handoff.lifecycle.timeline')}
            data-testid="review-events"
          >
            {view.reviewEvents.map((event) => (
              <li key={event.sequence} data-status={event.toState}>
                {t(`salaryAdvance.handoff.review.${event.toState}`)} ·{' '}
                {formatInstant(i18n.language, event.occurredAt)}
              </li>
            ))}
          </ol>
        )}
      </div>
    </div>
  );
}

import type { PartnerApplicationStatus } from '@divalhr/api-client';
import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { STATUS_TONES, type PartnerFailure } from './partners';

/** The status as text with a tone: never colour alone. */
export function PartnerStatus({ status }: { status: PartnerApplicationStatus }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={STATUS_TONES[status]}
      icon={status === 'APPROVED_FOR_ONBOARDING' ? 'check' : undefined}
      testId="partner-status"
    >
      {t(`partners.status.${status}`)}
    </StatusBadge>
  );
}

/** A refused or failed request: a finished sentence, never the raw code. */
export function PartnerAlert({
  failure,
  testId,
  alertRef,
}: {
  failure: PartnerFailure;
  testId: string;
  alertRef?: Ref<HTMLDivElement>;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid={testId}
      data-kind={failure.kind}
      data-code={failure.code}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** Shown wherever an application is approved or about to be: what approval does not create. */
export function ApprovalBoundary({ testId = 'partner-approval-boundary' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="card" role="note" data-testid={testId}>
      {t('partners.review.approvalBoundary')}
    </p>
  );
}

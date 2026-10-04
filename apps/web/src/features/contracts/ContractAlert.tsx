import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import type { ContractFailure } from './contracts';

/** A refused request: the stable message, an allow-listed detail, retry delay and reference. */
export function ContractAlert({
  failure,
  ref,
  'data-testid': testId,
}: {
  failure: ContractFailure;
  ref?: Ref<HTMLDivElement>;
  'data-testid': string;
}) {
  const { t } = useTranslation();
  return (
    <div className="error-summary" role="alert" tabIndex={-1} ref={ref} data-testid={testId}>
      <p>{t(failure.messageKey)}</p>
      {failure.problem && (
        <p data-testid={`${testId}-problem`}>
          {failure.problem.line === 0
            ? t('contracts.versions.problemTitle', {
                reason: t(`contracts.problems.${failure.problem.reason}`),
              })
            : t('contracts.versions.problemLine', {
                line: failure.problem.line,
                reason: t(`contracts.problems.${failure.problem.reason}`),
              })}
        </p>
      )}
      {failure.placeholder && (
        <p>
          {t('contracts.issue.missingValue', {
            field: t(`contracts.fields.${failure.placeholder}`),
          })}
        </p>
      )}
      {failure.dateField && <p>{t(`contracts.issue.dateFields.${failure.dateField}`)}</p>}
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

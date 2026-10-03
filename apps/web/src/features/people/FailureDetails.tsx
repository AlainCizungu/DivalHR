import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import type { Failure } from './importFailure';

/** A refused request: the stable message, file-level details, retry delay and reference. */
export function FailureDetails({
  failure,
  ref,
  'data-testid': testId,
}: {
  failure: Failure;
  ref?: Ref<HTMLDivElement>;
  'data-testid': string;
}) {
  const { t } = useTranslation();
  return (
    <div className="error-summary" role="alert" tabIndex={-1} ref={ref} data-testid={testId}>
      <p>{t(failure.messageKey)}</p>
      {failure.reason && <p>{t(`employeeImport.fileReasons.${failure.reason}`)}</p>}
      {failure.column && (
        <p>
          {t('employeeImport.upload.column', {
            column: /^\d+$/u.test(failure.column)
              ? failure.column
              : t(`employeeImport.columns.${failure.column}`, { defaultValue: failure.column }),
          })}
        </p>
      )}
      {failure.retryAfter !== undefined && (
        <p>{t('employeeImport.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employeeImport.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

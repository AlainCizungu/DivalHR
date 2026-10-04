import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import type { HistoryFailure } from './history';

/** A refused request: the stable message, an allow-listed detail, retry delay and reference. */
export function HistoryAlert({
  failure,
  ref,
  'data-testid': testId,
}: {
  failure: HistoryFailure;
  ref?: Ref<HTMLDivElement>;
  'data-testid': string;
}) {
  const { t } = useTranslation();
  const values = failure.detailValues ?? {};
  return (
    <div className="error-summary" role="alert" tabIndex={-1} ref={ref} data-testid={testId}>
      <p>{t(failure.messageKey)}</p>
      {failure.detailKey && (
        <p>
          {t(failure.detailKey, {
            field: values.field ? t(`employees.placement.fields.${values.field}`) : '',
            kind: values.kind ? t(`employees.kinds.${values.kind}`) : '',
          })}
        </p>
      )}
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

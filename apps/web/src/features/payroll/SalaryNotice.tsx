import { useTranslation } from 'react-i18next';

/**
 * MVP-092: the fixed monthly-salary notice (English and French are the approved wording). It is
 * shown on the preview, lock, result, history and download areas so that worked minutes are never
 * mistaken for a salary, an overtime or deduction figure, or an authorization of payment.
 */
export function SalaryNotice({ testId }: { testId: string }) {
  const { t } = useTranslation();
  return (
    <p className="card payroll-salary-notice" data-testid={testId}>
      <strong>{t('payrollPreparation.salaryNotice')}</strong>
    </p>
  );
}

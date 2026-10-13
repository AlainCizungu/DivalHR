import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '../../ui/primitives';
import { AttendanceExceptions } from './AttendanceExceptions';

/**
 * MVP-060C: « Suivi de la présence » / "Attendance review" under Operations: the organization's
 * factual attendance exceptions for a tenant administrator, filtered by site, type and dates.
 * Read-only: nothing here corrects, excuses or approves a record.
 */
export function AttendanceReviewPage() {
  const { t } = useTranslation();
  const ids = useId();
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="attendance-review">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('attendanceReview.title')}
        description={t('attendanceReview.description')}
      />
      <AttendanceExceptions view="admin" level={1} labelledBy={`${ids}-title`} />
    </section>
  );
}

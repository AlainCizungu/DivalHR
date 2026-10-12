import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import type { AssistedEvent } from './teamAttendance';

/**
 * MVP-060B: what the employee sees of a check-in or check-out a supervisor recorded for them,
 * right after that time: « Enregistré par un responsable » / "Recorded by a supervisor" and the
 * reason exactly as submitted, marked with its language. Never who recorded it.
 */
export function AssistedNote({ event, testId }: { event: AssistedEvent | null; testId: string }) {
  const { t } = useTranslation();
  if (!event) return null;
  return (
    <div className="assisted-note" data-testid={testId}>
      <StatusBadge tone="neutral" testId={`${testId}-method`}>
        {t('attendance.assisted.label')}
      </StatusBadge>
      <p>
        {t('attendance.assisted.reason')}{' '}
        <q lang={event.reasonLocale} data-testid={`${testId}-reason`}>
          {event.reason}
        </q>
      </p>
    </div>
  );
}

import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant } from './attendance';

/**
 * MVP-062: what an employee sees of their organization's correction of a record: « Corrigé par
 * l’organisation » / "Corrected by the organization", the correction date and that the times shown
 * are the corrected ones. Never who corrected it, the reason or the explanation.
 */
export function CorrectedNote({
  correctedAt,
  zone,
  testId,
}: {
  correctedAt: string | null | undefined;
  zone: string;
  testId: string;
}) {
  const { t, i18n } = useTranslation();
  if (!correctedAt) return null;
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  return (
    <div className="assisted-note" data-testid={testId}>
      <StatusBadge tone="neutral" testId={`${testId}-badge`}>
        {t('attendance.corrected.label')}
      </StatusBadge>
      <p data-testid={`${testId}-date`}>
        {t('attendance.corrected.detail', { date: formatInstant(language, correctedAt, zone) })}
      </p>
    </div>
  );
}

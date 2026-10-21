import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '../../ui/primitives';
import { OperationalStatusView } from './OperationalStatusView';

/**
 * MVP-100: « Opérations du jour » / "Today's operations" under Operations: the organization's
 * shifts, coverage and waiting approvals at one server instant, for a tenant administrator.
 * Read-only: nothing here corrects, approves or concludes anything.
 */
export function OperationsStatusPage() {
  const { t } = useTranslation();
  const ids = useId();
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="operations-status">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('operationsStatus.title')}
        description={t('operationsStatus.description')}
      />
      <OperationalStatusView view="admin" />
    </section>
  );
}

/**
 * MVP-100: « Opérations de l’équipe » / "Team operations" in My space: the same figures for the
 * employees the caller manages, never the caller.
 */
export function TeamOperationsPage() {
  const { t } = useTranslation();
  const ids = useId();
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="team-operations">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('teamOperations.title')}
        description={t('teamOperations.description')}
      />
      <OperationalStatusView view="team" />
    </section>
  );
}

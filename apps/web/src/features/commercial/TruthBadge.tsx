import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { capabilityOf, STATUS_STYLE } from './truth';

/** The customer label of a capability's status, never the engineering code. */
export function TruthBadge({ id }: { id: string }) {
  const { t } = useTranslation();
  const { status } = capabilityOf(id);
  const { tone, icon } = STATUS_STYLE[status];
  return (
    <span data-status={status} data-capability-badge={id}>
      <StatusBadge tone={tone} icon={icon}>
        {t(`commercial.status.${status}`)}
      </StatusBadge>
    </span>
  );
}

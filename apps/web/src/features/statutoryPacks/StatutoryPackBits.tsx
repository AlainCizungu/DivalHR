import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { STATE_TONES, type PackState } from './statutoryPacks';

/** The lifecycle state as text with an icon-free badge: never conveyed by colour alone. */
export function StateBadge({ state }: { state: PackState }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={STATE_TONES[state]} testId="statutory-pack-state">
      {t(`statutoryPacks.state.${state}`)}
    </StatusBadge>
  );
}

/** The permanent warning: technical validation is not legal approval. */
export function LegalNotice() {
  const { t } = useTranslation();
  return (
    <aside
      className="card"
      aria-label={t('statutoryPacks.notice.label')}
      data-testid="statutory-pack-legal-notice"
    >
      <p>
        <strong>{t('statutoryPacks.notice.heading')}</strong>
      </p>
      <p>{t('statutoryPacks.notice.body')}</p>
    </aside>
  );
}

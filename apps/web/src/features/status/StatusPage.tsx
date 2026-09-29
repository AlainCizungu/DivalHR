import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useServiceStatus, type ServiceState } from './useServiceStatus';

function ServiceCard({
  name,
  testId,
  state,
}: {
  name: string;
  testId: string;
  state: ServiceState;
}) {
  const { t, i18n } = useTranslation();
  const label = t(`status.state.${state.kind}`);
  return (
    <li className="card" data-testid={testId} data-state={state.kind}>
      <h2 className="card__title">{name}</h2>
      <p className={`badge badge--${state.kind}`}>{label}</p>
      {'status' in state && (
        <ul className="meta">
          <li>{t('status.version', { version: state.status.version })}</li>
          <li>
            {t('status.checkedAt', {
              time: new Intl.DateTimeFormat(i18n.language, {
                dateStyle: 'medium',
                timeStyle: 'medium',
              }).format(new Date(state.status.checkedAt)),
            })}
          </li>
        </ul>
      )}
    </li>
  );
}

export function StatusPage() {
  const { t } = useTranslation();
  const { core, aiStatus } = useApi();
  const fetchCore = useCallback(() => core.GET('/system/status'), [core]);
  const fetchAi = useCallback(() => aiStatus.GET('/system/status'), [aiStatus]);
  const coreStatus = useServiceStatus(fetchCore);
  const aiServiceStatus = useServiceStatus(fetchAi);

  return (
    <section aria-labelledby="status-title">
      <h1 id="status-title">{t('status.title')}</h1>
      <p className="muted">{t('status.description')}</p>
      <ul className="cards" aria-live="polite">
        <ServiceCard
          name={t('status.service.coreApi')}
          testId="status-core-api"
          state={coreStatus.state}
        />
        <ServiceCard
          name={t('status.service.aiService')}
          testId="status-ai-service"
          state={aiServiceStatus.state}
        />
      </ul>
      <button
        type="button"
        className="button"
        onClick={() => {
          coreStatus.check();
          aiServiceStatus.check();
        }}
      >
        {t('status.refresh')}
      </button>
    </section>
  );
}

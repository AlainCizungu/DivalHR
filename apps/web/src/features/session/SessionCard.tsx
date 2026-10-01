import { useTranslation } from 'react-i18next';
import { useSession } from '../../app/SessionProvider';

export function SessionCard() {
  const { t } = useTranslation();
  const state = useSession();

  return (
    <section className="card" aria-labelledby="session-title" data-testid="session-card">
      <h2 id="session-title" className="card__title">
        {t('session.title')}
      </h2>
      {(state.kind === 'loading' || state.kind === 'anonymous') && (
        <p role="status">{t('session.loading')}</p>
      )}
      {state.kind === 'error' && <p role="alert">{t(`errors.${state.code}`)}</p>}
      {state.kind === 'ready' && (
        <dl className="meta">
          <dt>{t('session.tenant')}</dt>
          <dd data-testid="session-tenant">{state.session.tenantId ?? t('session.noTenant')}</dd>
          <dt>{t('session.roles')}</dt>
          <dd data-testid="session-roles">
            {state.session.roles.length === 0
              ? t('session.noRoles')
              : state.session.roles.map((role) => t(`session.role.${role}`)).join(', ')}
          </dd>
        </dl>
      )}
    </section>
  );
}

import type { CurrentSession, Problem } from '@divalhr/api-client';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';

type State =
  | { kind: 'loading' }
  | { kind: 'ready'; session: CurrentSession }
  | { kind: 'error'; code: string };

export function SessionCard() {
  const { t } = useTranslation();
  const { core } = useApi();
  const [state, setState] = useState<State>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    core
      .GET('/session')
      .then(({ data, error }) => {
        if (!active) return;
        if (data) setState({ kind: 'ready', session: data });
        else setState({ kind: 'error', code: (error as Problem | undefined)?.code ?? 'generic' });
      })
      .catch(() => {
        if (active) setState({ kind: 'error', code: 'generic' });
      });
    return () => {
      active = false;
    };
  }, [core]);

  return (
    <section className="card" aria-labelledby="session-title" data-testid="session-card">
      <h2 id="session-title" className="card__title">
        {t('session.title')}
      </h2>
      {state.kind === 'loading' && <p role="status">{t('session.loading')}</p>}
      {state.kind === 'error' && <p role="alert">{t(`errors.${state.code}`)}</p>}
      {state.kind === 'ready' && (
        <dl className="meta">
          <dt>{t('session.tenant')}</dt>
          <dd data-testid="session-tenant">{state.session.tenantId}</dd>
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

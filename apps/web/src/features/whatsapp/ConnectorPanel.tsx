import type { WhatsAppConnector, WhatsAppVerification } from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { failureOf, type Failure } from './whatsapp';

type Action = 'verify' | 'rotate' | 'disable' | 'enable' | null;

/** The connector: details, and commands behind explicit confirmation. The token is write-only. */
export function ConnectorPanel({
  connector,
  onChanged,
  onEdit,
  onAnnounce,
  format,
}: {
  connector: WhatsAppConnector;
  onChanged: (connector: WhatsAppConnector) => void;
  onEdit: () => void;
  onAnnounce: (messageKey: string) => void;
  format: (iso: string | null) => string;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [pending, setPending] = useState<Action>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [token, setToken] = useState('');
  const [tokenError, setTokenError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [verification, setVerification] = useState<WhatsAppVerification | null>(null);
  const commandKey = useIdempotencyKey();

  const open = (action: Exclude<Action, null>) => {
    setPending(action);
    setConfirmed(false);
    setToken('');
    setTokenError(null);
    setFailure(null);
  };

  const run = async (action: Exclude<Action, null>) => {
    if (action === 'rotate' && !/^[A-Za-z0-9._~+/=-]{20,1024}$/.test(token.trim())) {
      setTokenError(
        token.trim() ? 'whatsapp.errors.tokenInvalid' : 'whatsapp.errors.tokenRequired',
      );
      document.getElementById(`${ids}-token`)?.focus();
      return;
    }
    setBusy(true);
    setFailure(null);
    setTokenError(null);
    const base = { configVersion: connector.configVersion, confirm: true as const };
    const header = (body: unknown) => ({
      'Idempotency-Key': commandKey.keyFor({ action, ...(body as object) }),
    });
    try {
      let result: {
        connector?: WhatsAppConnector;
        verification?: WhatsAppVerification;
        failure?: Failure;
      };
      if (action === 'verify') {
        const { data, error, response } = await core.POST(
          '/integrations/messaging/whatsapp/verify',
          {
            params: { header: header(base) },
            body: base,
          },
        );
        result = data
          ? { connector: data.connector, verification: data }
          : { failure: failureOf(response, error) };
      } else if (action === 'rotate') {
        const body = { ...base, accessToken: token.trim() };
        const { data, error, response } = await core.POST(
          '/integrations/messaging/whatsapp/rotate-token',
          { params: { header: header(body) }, body },
        );
        result = data ? { connector: data } : { failure: failureOf(response, error) };
      } else {
        const path =
          action === 'disable'
            ? ('/integrations/messaging/whatsapp/disable' as const)
            : ('/integrations/messaging/whatsapp/enable' as const);
        const { data, error, response } = await core.POST(path, {
          params: { header: header(base) },
          body: base,
        });
        result = data ? { connector: data } : { failure: failureOf(response, error) };
      }
      if (result.connector) {
        commandKey.reset();
        setToken('');
        setPending(null);
        setConfirmed(false);
        setVerification(result.verification ?? null);
        onChanged(result.connector);
        onAnnounce(
          action === 'verify'
            ? result.verification?.verified
              ? 'whatsapp.announce.verified'
              : 'whatsapp.announce.notVerified'
            : `whatsapp.announce.${action}`,
        );
      } else if (result.failure) {
        setFailure(result.failure);
      }
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const active = connector.state === 'ACTIVE';
  const note =
    connector.state === 'DRAFT'
      ? 'whatsapp.connector.draftNote'
      : connector.state === 'DISABLED'
        ? 'whatsapp.connector.disabledNote'
        : connector.state === 'SUSPENDED'
          ? 'whatsapp.connector.suspendedNote'
          : 'whatsapp.connector.activeNote';

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="whatsapp-connector">
      <h2 id={`${ids}-title`}>{t('whatsapp.connector.title')}</h2>
      <dl className="detail-list">
        <dt>{t('whatsapp.connector.stateLabel')}</dt>
        <dd data-testid="connector-state" data-status={connector.state}>
          {t(`whatsapp.connectorState.${connector.state}`)}
        </dd>
        <dt>{t('whatsapp.connector.wabaId')}</dt>
        <dd>
          <code>{connector.wabaId}</code>
        </dd>
        <dt>{t('whatsapp.connector.phoneNumberId')}</dt>
        <dd>
          <code>{connector.phoneNumberId}</code>
        </dd>
        <dt>{t('whatsapp.connector.displayNumber')}</dt>
        <dd>{connector.displayNumber}</dd>
        <dt>{t('whatsapp.connector.graphVersion')}</dt>
        <dd>{connector.graphApiVersion}</dd>
        <dt>{t('whatsapp.connector.credentialVersion')}</dt>
        <dd>{connector.credentialVersion}</dd>
        <dt>{t('whatsapp.connector.verifiedAt')}</dt>
        <dd>{format(connector.verifiedAt)}</dd>
        <dt>{t('whatsapp.connector.failures')}</dt>
        <dd>{connector.consecutiveFailures}</dd>
        <dt>{t('whatsapp.connector.lastSuccess')}</dt>
        <dd>{format(connector.lastSuccessAt)}</dd>
        <dt>{t('whatsapp.connector.lastFailure')}</dt>
        <dd>{format(connector.lastFailureAt)}</dd>
        <dt>{t('whatsapp.connector.templates')}</dt>
        <dd>
          <ul>
            {connector.templates.map((template) => (
              <li key={`${template.notificationType}/${template.language}`}>
                {t('whatsapp.connector.templateRow', {
                  type: t(`whatsapp.types.${template.notificationType}`),
                  language: t(`whatsapp.language.${template.language}`),
                  name: template.templateName,
                })}{' '}
                <span className="muted">
                  (
                  {t(
                    template.verified
                      ? 'whatsapp.connector.templateVerified'
                      : 'whatsapp.connector.templateUnverified',
                  )}
                  )
                </span>
              </li>
            ))}
          </ul>
        </dd>
      </dl>
      <p role="note" className="muted">
        {t(note)}
      </p>

      {verification && (
        <div role="group" aria-labelledby={`${ids}-verification`} data-testid="verification">
          <h3 id={`${ids}-verification`}>{t('whatsapp.verification.title')}</h3>
          <p>
            {t(
              verification.verified
                ? 'whatsapp.verification.verified'
                : 'whatsapp.verification.notVerified',
            )}
          </p>
          <ul>
            <li>
              {t('whatsapp.verification.credential')}:{' '}
              {t(`whatsapp.credentialResult.${verification.credential}`)}
            </li>
            {verification.templates.map((check) => (
              <li key={`${check.notificationType}/${check.language}`}>
                {t('whatsapp.verification.template', {
                  type: t(`whatsapp.types.${check.notificationType}`),
                  language: t(`whatsapp.language.${check.language}`),
                  result: t(`whatsapp.templateResult.${check.result}`),
                })}
              </li>
            ))}
          </ul>
        </div>
      )}

      {failure && (
        <div className="error-summary" role="alert" data-testid="whatsapp-failure">
          <p>{t(failure.messageKey)}</p>
          {failure.retryAfter !== undefined && (
            <p>{t('whatsapp.retryAfter', { count: failure.retryAfter })}</p>
          )}
          {failure.correlationId && (
            <p className="muted">{t('whatsapp.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}

      <div className="actions">
        <button
          type="button"
          className="button"
          disabled={connector.state !== 'DRAFT' || busy}
          onClick={() => {
            open('verify');
          }}
        >
          {t('whatsapp.actions.verify')}
        </button>
        <button
          type="button"
          className="button button--secondary"
          disabled={active || busy}
          onClick={onEdit}
        >
          {t('whatsapp.actions.edit')}
        </button>
        <button
          type="button"
          className="button button--secondary"
          disabled={busy}
          onClick={() => {
            open('rotate');
          }}
        >
          {t('whatsapp.actions.rotate')}
        </button>
        {active ? (
          <button
            type="button"
            className="button button--secondary"
            disabled={busy}
            onClick={() => {
              open('disable');
            }}
          >
            {t('whatsapp.actions.disable')}
          </button>
        ) : (
          <button
            type="button"
            className="button button--secondary"
            disabled={busy || connector.state === 'DRAFT'}
            onClick={() => {
              open('enable');
            }}
          >
            {t('whatsapp.actions.enable')}
          </button>
        )}
      </div>

      {pending && (
        <div className="card" role="group" aria-labelledby={`${ids}-confirm`} data-testid="confirm">
          <h3 id={`${ids}-confirm`}>{t(`whatsapp.confirmAction.${pending}.title`)}</h3>
          <p>{t(`whatsapp.confirmAction.${pending}.body`)}</p>
          {pending === 'rotate' && (
            <div className="field">
              <label htmlFor={`${ids}-token`}>{t('whatsapp.rotate.token')}</label>
              <input
                id={`${ids}-token`}
                type="password"
                value={token}
                autoComplete="new-password"
                spellCheck={false}
                aria-invalid={tokenError ? true : undefined}
                aria-describedby={`${ids}-token-hint${tokenError ? ` ${ids}-token-error` : ''}`}
                onChange={(event) => {
                  setToken(event.target.value);
                }}
              />
              <p id={`${ids}-token-hint`} className="muted">
                {t('whatsapp.rotate.tokenHint')}
              </p>
              {tokenError && (
                <p id={`${ids}-token-error`} className="field__error">
                  {t(tokenError)}
                </p>
              )}
            </div>
          )}
          <div className="field">
            <label>
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(event) => {
                  setConfirmed(event.target.checked);
                }}
              />{' '}
              {t('whatsapp.confirmAction.understand')}
            </label>
          </div>
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={!confirmed || busy}
              onClick={() => void run(pending)}
            >
              {t(`whatsapp.confirmAction.${pending}.submit`)}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setPending(null);
                setToken('');
              }}
            >
              {t('whatsapp.cancel')}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

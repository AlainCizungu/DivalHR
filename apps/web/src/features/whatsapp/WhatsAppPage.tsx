import type { WhatsAppConnector, WhatsAppRecipient } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ConnectorForm, EMPTY_CONNECTOR, valuesOf } from './ConnectorForm';
import { ConnectorPanel } from './ConnectorPanel';
import { DeliveryHistory } from './DeliveryHistory';
import { RecipientsPanel } from './RecipientsPanel';
import { failureOf, templateList, type ConnectorValues, type Failure } from './whatsapp';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | { kind: 'none' }
  | { kind: 'ready'; connector: WhatsAppConnector };

/**
 * MVP-111: manage the tenant's WhatsApp Business notifications. The access token and recipient
 * numbers are write-only: they live in form state until sent and are never read back, stored in the
 * browser, or put in the URL.
 */
export function WhatsAppPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [editing, setEditing] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const [recipients, setRecipients] = useState<WhatsAppRecipient[]>([]);
  const [refreshToken, setRefreshToken] = useState(0);
  const createKey = useIdempotencyKey();
  const editKey = useIdempotencyKey();

  const fetchConnector = useCallback(async (): Promise<Loaded> => {
    try {
      const { data, error, response } = await core.GET('/integrations/messaging/whatsapp', {
        cache: 'no-store',
      });
      if (data) return { kind: 'ready', connector: data };
      const found = failureOf(response, error);
      return found.messageKey === 'errors.MESSAGING_CONNECTOR_NOT_FOUND'
        ? { kind: 'none' }
        : { kind: 'failed', failure: found };
    } catch {
      return { kind: 'failed', failure: { messageKey: 'errors.network' } };
    }
  }, [core]);

  const reload = useCallback(async () => {
    setLoaded(await fetchConnector());
  }, [fetchConnector]);

  useEffect(() => {
    const status = { active: true };
    void (async () => {
      const result = await fetchConnector();
      if (status.active) setLoaded(result);
    })();
    return () => {
      status.active = false;
    };
  }, [fetchConnector]);

  const format = (iso: string | null) =>
    iso
      ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
          new Date(iso),
        )
      : t('whatsapp.never');

  const announce = useCallback(
    (messageKey: string) => {
      setAnnouncement(t(messageKey));
    },
    [t],
  );

  const connect = async (values: ConnectorValues) => {
    setBusy(true);
    setFailure(null);
    const body = {
      wabaId: values.wabaId.trim(),
      phoneNumberId: values.phoneNumberId.trim(),
      displayNumber: values.displayNumber.trim(),
      accessToken: values.accessToken.trim(),
      templates: templateList(values),
      confirm: true as const,
    };
    try {
      const { data, error, response } = await core.POST('/integrations/messaging/whatsapp', {
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
        body,
      });
      if (data) {
        createKey.reset();
        setLoaded({ kind: 'ready', connector: data });
        announce('whatsapp.announce.created');
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const saveEdit = async (connector: WhatsAppConnector, values: ConnectorValues) => {
    setBusy(true);
    setFailure(null);
    const body = {
      configVersion: connector.configVersion,
      wabaId: values.wabaId.trim(),
      phoneNumberId: values.phoneNumberId.trim(),
      displayNumber: values.displayNumber.trim(),
      templates: templateList(values),
      confirm: true as const,
    };
    try {
      const { data, error, response } = await core.PATCH('/integrations/messaging/whatsapp', {
        params: { header: { 'Idempotency-Key': editKey.keyFor(body) } },
        body,
      });
      if (data) {
        editKey.reset();
        setLoaded({ kind: 'ready', connector: data });
        setEditing(false);
        announce('whatsapp.announce.updated');
        setRefreshToken((n) => n + 1);
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('whatsapp.title')}</h1>
      <p className="muted">{t('whatsapp.description')}</p>
      <p className="muted">{t('whatsapp.stopNote')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      {loaded.kind === 'loading' && <p className="muted">{t('whatsapp.loading')}</p>}
      {loaded.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="whatsapp-error">
          <p>{t(loaded.failure.messageKey)}</p>
          {loaded.failure.correlationId && (
            <p className="muted">{t('whatsapp.reference', { id: loaded.failure.correlationId })}</p>
          )}
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              setLoaded({ kind: 'loading' });
              void reload();
            }}
          >
            {t('whatsapp.retry')}
          </button>
        </div>
      )}

      {loaded.kind === 'none' && (
        <section aria-labelledby={`${ids}-connect`} className="card">
          <h2 id={`${ids}-connect`}>{t('whatsapp.connect.title')}</h2>
          <p>{t('whatsapp.connect.intro')}</p>
          <ConnectorForm
            mode="create"
            initial={EMPTY_CONNECTOR}
            busy={busy}
            failure={failure}
            onSubmit={(values) => void connect(values)}
          />
        </section>
      )}

      {loaded.kind === 'ready' && editing && (
        <section aria-labelledby={`${ids}-edit`} className="card">
          <h2 id={`${ids}-edit`}>{t('whatsapp.actions.edit')}</h2>
          <ConnectorForm
            mode="edit"
            initial={valuesOf(loaded.connector)}
            busy={busy}
            failure={failure}
            onSubmit={(values) => void saveEdit(loaded.connector, values)}
            onCancel={() => {
              setFailure(null);
              setEditing(false);
            }}
          />
        </section>
      )}

      {loaded.kind === 'ready' && !editing && (
        <>
          <ConnectorPanel
            connector={loaded.connector}
            format={format}
            onAnnounce={announce}
            onEdit={() => {
              setFailure(null);
              setEditing(true);
            }}
            onChanged={(connector) => {
              setLoaded({ kind: 'ready', connector });
              setRefreshToken((n) => n + 1);
            }}
          />
          <RecipientsPanel
            connectorState={loaded.connector.state}
            onAnnounce={announce}
            onListed={setRecipients}
            onChanged={() => {
              void reload();
              setRefreshToken((n) => n + 1);
            }}
          />
          <DeliveryHistory recipients={recipients} refreshToken={refreshToken} format={format} />
        </>
      )}
    </section>
  );
}

import type { WhatsAppRecipient } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { EMPTY_RECIPIENT, RecipientForm, recipientValues } from './RecipientForm';
import {
  MAX_RECIPIENTS,
  consentInstant,
  failureOf,
  type Failure,
  type RecipientValues,
} from './whatsapp';

type View = { kind: 'list' } | { kind: 'create' } | { kind: 'edit'; recipient: WhatsAppRecipient };
type Pending = { id: string; action: 'disable' | 'enable' | 'test' } | null;
type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | { kind: 'ready'; items: WhatsAppRecipient[] };

/** The registered recipients, with consent, and the per-recipient commands (including the test). */
export function RecipientsPanel({
  connectorState,
  onChanged,
  onAnnounce,
  onListed,
}: {
  connectorState: string;
  onChanged: () => void;
  onAnnounce: (messageKey: string) => void;
  onListed: (items: WhatsAppRecipient[]) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [view, setView] = useState<View>({ kind: 'list' });
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [pending, setPending] = useState<Pending>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [busy, setBusy] = useState(false);
  const createKey = useIdempotencyKey();
  const editKey = useIdempotencyKey();
  const commandKey = useIdempotencyKey();

  const fetchList = useCallback(async (): Promise<Listing> => {
    try {
      const { data, error, response } = await core.GET(
        '/integrations/messaging/whatsapp/recipients',
        {
          cache: 'no-store',
        },
      );
      return data
        ? { kind: 'ready', items: data.items }
        : { kind: 'failed', failure: failureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: { messageKey: 'errors.network' } };
    }
  }, [core]);

  const reload = useCallback(async () => {
    setListing(await fetchList());
  }, [fetchList]);

  useEffect(() => {
    const status = { active: true };
    void (async () => {
      const result = await fetchList();
      if (status.active) setListing(result);
    })();
    return () => {
      status.active = false;
    };
  }, [fetchList]);

  useEffect(() => {
    if (listing.kind === 'ready') onListed(listing.items);
  }, [listing, onListed]);

  const done = (messageKey: string) => {
    setFailure(null);
    setPending(null);
    setView({ kind: 'list' });
    onAnnounce(messageKey);
    onChanged();
    void reload();
  };

  const create = async (values: RecipientValues) => {
    if (!values.consentSource) return;
    setBusy(true);
    setFailure(null);
    const body = {
      label: values.label,
      phoneNumber: values.phoneNumber,
      language: values.language,
      notificationTypes: values.notificationTypes,
      consent: {
        source: values.consentSource,
        confirmedAt: consentInstant(values.consentDate),
        attested: true as const,
      },
      confirm: true as const,
    };
    try {
      const { data, error, response } = await core.POST(
        '/integrations/messaging/whatsapp/recipients',
        {
          params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
          body,
        },
      );
      if (data) {
        createKey.reset();
        done('whatsapp.announce.recipientCreated');
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const save = async (recipient: WhatsAppRecipient, values: RecipientValues) => {
    setBusy(true);
    setFailure(null);
    const body = {
      version: recipient.version,
      label: values.label,
      language: values.language,
      notificationTypes: values.notificationTypes,
      confirm: true as const,
    };
    try {
      const { data, error, response } = await core.PATCH(
        '/integrations/messaging/whatsapp/recipients/{recipientId}',
        {
          params: {
            path: { recipientId: recipient.id },
            header: { 'Idempotency-Key': editKey.keyFor({ id: recipient.id, ...body }) },
          },
          body,
        },
      );
      if (data) {
        editKey.reset();
        done('whatsapp.announce.recipientUpdated');
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const command = async (recipient: WhatsAppRecipient, action: 'disable' | 'enable' | 'test') => {
    setBusy(true);
    setFailure(null);
    const params = (body: unknown) => ({
      path: { recipientId: recipient.id },
      header: {
        'Idempotency-Key': commandKey.keyFor({ id: recipient.id, action, ...(body as object) }),
      },
    });
    try {
      if (action === 'test') {
        const body = { confirm: true as const };
        const { data, error, response } = await core.POST(
          '/integrations/messaging/whatsapp/recipients/{recipientId}/test-deliveries',
          { params: params(body), body },
        );
        if (data) {
          commandKey.reset();
          done('whatsapp.announce.testQueued');
        } else setFailure(failureOf(response, error));
        return;
      }
      const body = { version: recipient.version, confirm: true as const };
      const path =
        action === 'disable'
          ? ('/integrations/messaging/whatsapp/recipients/{recipientId}/disable' as const)
          : ('/integrations/messaging/whatsapp/recipients/{recipientId}/enable' as const);
      const { data, error, response } = await core.POST(path, { params: params(body), body });
      if (data) {
        commandKey.reset();
        done(
          action === 'disable'
            ? 'whatsapp.announce.recipientDisable'
            : 'whatsapp.announce.recipientEnable',
        );
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const count = listing.kind === 'ready' ? listing.items.length : 0;
  const testable = connectorState === 'ACTIVE';

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="whatsapp-recipients">
      <h2 id={`${ids}-title`}>{t('whatsapp.recipients.title')}</h2>
      <p className="muted">{t('whatsapp.recipients.count', { count, max: MAX_RECIPIENTS })}</p>

      {view.kind === 'create' && (
        <RecipientForm
          mode="create"
          initial={EMPTY_RECIPIENT}
          busy={busy}
          failure={failure}
          onSubmit={(values) => void create(values)}
          onCancel={() => {
            setFailure(null);
            setView({ kind: 'list' });
          }}
        />
      )}
      {view.kind === 'edit' && (
        <RecipientForm
          mode="edit"
          initial={recipientValues(view.recipient)}
          busy={busy}
          failure={failure}
          onSubmit={(values) => void save(view.recipient, values)}
          onCancel={() => {
            setFailure(null);
            setView({ kind: 'list' });
          }}
        />
      )}

      {view.kind === 'list' && (
        <>
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={count >= MAX_RECIPIENTS}
              onClick={() => {
                setFailure(null);
                setView({ kind: 'create' });
              }}
            >
              {t('whatsapp.recipients.add')}
            </button>
          </div>
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
          {listing.kind === 'loading' && (
            <p className="muted">{t('whatsapp.recipients.loading')}</p>
          )}
          {listing.kind === 'failed' && (
            <div className="error-summary" role="alert">
              <p>{t(listing.failure.messageKey)}</p>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setListing({ kind: 'loading' });
                  void reload();
                }}
              >
                {t('whatsapp.retry')}
              </button>
            </div>
          )}
          {listing.kind === 'ready' && listing.items.length === 0 && (
            <p className="muted" data-testid="recipients-empty">
              {t('whatsapp.recipients.empty')}
            </p>
          )}
          {listing.kind === 'ready' && listing.items.length > 0 && (
            <div className="table-wrapper">
              <table data-testid="recipients-table">
                <caption>{t('whatsapp.recipients.title')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('whatsapp.recipients.table.label')}</th>
                    <th scope="col">{t('whatsapp.recipients.table.number')}</th>
                    <th scope="col">{t('whatsapp.recipients.table.language')}</th>
                    <th scope="col">{t('whatsapp.recipients.table.types')}</th>
                    <th scope="col">{t('whatsapp.recipients.table.status')}</th>
                    <th scope="col">{t('whatsapp.recipients.table.consent')}</th>
                    <th scope="col">{t('whatsapp.recipients.table.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {listing.items.map((recipient) => (
                    <tr key={recipient.id} data-testid="recipient-row">
                      <th scope="row">{recipient.label}</th>
                      <td>
                        <code>{recipient.numberMasked}</code>
                      </td>
                      <td>{t(`whatsapp.language.${recipient.language}`)}</td>
                      <td>
                        {recipient.notificationTypes
                          .map((x) => t(`whatsapp.types.${x}`))
                          .join(', ')}
                      </td>
                      <td data-status={recipient.status}>
                        {t(`whatsapp.recipientStatus.${recipient.status}`)}
                      </td>
                      <td>{t(`whatsapp.consentSource.${recipient.consentSource}`)}</td>
                      <td>
                        <div className="actions">
                          <button
                            type="button"
                            className="button button--secondary"
                            aria-label={`${t('whatsapp.recipients.actions.edit')} ${recipient.label}`}
                            onClick={() => {
                              setFailure(null);
                              setView({ kind: 'edit', recipient });
                            }}
                          >
                            {t('whatsapp.recipients.actions.edit')}
                          </button>
                          <button
                            type="button"
                            className="button button--secondary"
                            aria-label={`${t('whatsapp.recipients.actions.test')} ${recipient.label}`}
                            disabled={!testable || recipient.status !== 'ACTIVE' || busy}
                            onClick={() => {
                              setFailure(null);
                              setPending({ id: recipient.id, action: 'test' });
                            }}
                          >
                            {t('whatsapp.recipients.actions.test')}
                          </button>
                          <button
                            type="button"
                            className="button button--secondary"
                            aria-label={`${t(
                              recipient.status === 'ACTIVE'
                                ? 'whatsapp.recipients.actions.disable'
                                : 'whatsapp.recipients.actions.enable',
                            )} ${recipient.label}`}
                            disabled={busy}
                            onClick={() => {
                              setFailure(null);
                              setPending({
                                id: recipient.id,
                                action: recipient.status === 'ACTIVE' ? 'disable' : 'enable',
                              });
                            }}
                          >
                            {t(
                              recipient.status === 'ACTIVE'
                                ? 'whatsapp.recipients.actions.disable'
                                : 'whatsapp.recipients.actions.enable',
                            )}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {pending &&
            listing.kind === 'ready' &&
            (() => {
              const target = listing.items.find((r) => r.id === pending.id);
              if (!target) return null;
              const body = confirmBodyKey(pending.action);
              return (
                <div
                  className="card"
                  role="group"
                  aria-labelledby={`${ids}-pending`}
                  data-testid="confirm"
                >
                  <h3 id={`${ids}-pending`}>{target.label}</h3>
                  <p>{t(body)}</p>
                  <div className="actions">
                    <button
                      type="button"
                      className="button"
                      disabled={busy}
                      onClick={() => void command(target, pending.action)}
                    >
                      {t(
                        pending.action === 'test'
                          ? 'whatsapp.recipients.confirm.testYes'
                          : 'whatsapp.recipients.confirm.yes',
                      )}
                    </button>
                    <button
                      type="button"
                      className="button button--secondary"
                      onClick={() => {
                        setPending(null);
                      }}
                    >
                      {t('whatsapp.cancel')}
                    </button>
                  </div>
                </div>
              );
            })()}
        </>
      )}
    </section>
  );
}

function confirmBodyKey(action: string): string {
  return action === 'test'
    ? 'whatsapp.recipients.confirm.testBody'
    : `whatsapp.recipients.confirm.${action}`;
}

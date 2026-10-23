import type {
  Webhook,
  WebhookDelivery,
  WebhookDeliveryPage,
  WebhookDeliveryAttempt,
  WebhookResult,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { WebhookForm } from './WebhookForm';
import { failureOf, type Failure, type WebhookValues } from './webhooks';

type Action = 'rotate' | 'disable' | 'enable' | null;

type History =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | { kind: 'ready'; items: WebhookDelivery[]; nextCursor: string | null; loadingMore: boolean };

/** One webhook: configuration, commands behind explicit confirmation, and delivery history. */
export function WebhookDetail({
  webhookId,
  onChanged,
  onSecret,
  onBack,
}: {
  webhookId: string;
  onChanged: () => void;
  onSecret: (result: WebhookResult) => void;
  onBack: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [webhook, setWebhook] = useState<Webhook | null>(null);
  const [loadFailure, setLoadFailure] = useState<Failure | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [editing, setEditing] = useState(false);
  const [pending, setPending] = useState<Action>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const [history, setHistory] = useState<History>({ kind: 'loading' });
  const [attempts, setAttempts] = useState<{
    id: string;
    items: WebhookDeliveryAttempt[];
  } | null>(null);
  const editKey = useIdempotencyKey();
  const commandKey = useIdempotencyKey();
  const testKey = useIdempotencyKey();

  const format = (iso: string | null) =>
    iso
      ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
          new Date(iso),
        )
      : t('webhooks.never');

  const fetchWebhook = useCallback(async (): Promise<
    { webhook: Webhook } | { failure: Failure }
  > => {
    try {
      const { data, error, response } = await core.GET('/integrations/webhooks/{webhookId}', {
        params: { path: { webhookId } },
        cache: 'no-store',
      });
      return data ? { webhook: data.webhook } : { failure: failureOf(response, error) };
    } catch {
      return { failure: { messageKey: 'errors.network' } };
    }
  }, [core, webhookId]);

  const fetchHistory = useCallback(
    async (cursor?: string): Promise<{ page: WebhookDeliveryPage } | { failure: Failure }> => {
      try {
        const { data, error, response } = await core.GET(
          '/integrations/webhooks/{webhookId}/deliveries',
          { params: { path: { webhookId }, query: cursor ? { cursor } : {} }, cache: 'no-store' },
        );
        return data ? { page: data } : { failure: failureOf(response, error) };
      } catch {
        return { failure: { messageKey: 'errors.network' } };
      }
    },
    [core, webhookId],
  );

  const loadWebhook = useCallback(async () => {
    const result = await fetchWebhook();
    if ('failure' in result) setLoadFailure(result.failure);
    else {
      setWebhook(result.webhook);
      setLoadFailure(null);
    }
  }, [fetchWebhook]);

  const loadHistory = useCallback(
    async (cursor?: string) => {
      const result = await fetchHistory(cursor);
      if ('failure' in result) {
        setHistory({ kind: 'failed', failure: result.failure });
        return;
      }
      setHistory((current) => ({
        kind: 'ready',
        items:
          cursor && current.kind === 'ready'
            ? [...current.items, ...result.page.items]
            : result.page.items,
        nextCursor: result.page.nextCursor,
        loadingMore: false,
      }));
    },
    [fetchHistory],
  );

  useEffect(() => {
    const status = { active: true };
    void (async () => {
      const [hook, page] = await Promise.all([fetchWebhook(), fetchHistory()]);
      if (!status.active) return;
      if ('failure' in hook) setLoadFailure(hook.failure);
      else setWebhook(hook.webhook);
      setHistory(
        'failure' in page
          ? { kind: 'failed', failure: page.failure }
          : {
              kind: 'ready',
              items: page.page.items,
              nextCursor: page.page.nextCursor,
              loadingMore: false,
            },
      );
    })();
    return () => {
      status.active = false;
    };
  }, [fetchWebhook, fetchHistory]);

  const finish = (message: string) => {
    setAnnouncement(t(message));
    setFailure(null);
    setPending(null);
    setConfirmed(false);
    onChanged();
    void loadWebhook();
    void loadHistory();
  };

  const saveEdit = async (values: WebhookValues) => {
    if (!webhook) return;
    setBusy(true);
    setFailure(null);
    const body = { ...values, configVersion: webhook.configVersion, confirm: true as const };
    try {
      const { data, error, response } = await core.PATCH('/integrations/webhooks/{webhookId}', {
        params: {
          path: { webhookId },
          header: { 'Idempotency-Key': editKey.keyFor({ webhookId, ...body }) },
        },
        body,
      });
      if (data) {
        editKey.reset();
        setEditing(false);
        finish('webhooks.announce.updated');
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const command = async (action: Exclude<Action, null>) => {
    if (!webhook) return;
    setBusy(true);
    setFailure(null);
    const body = { configVersion: webhook.configVersion, confirm: true as const };
    const params = {
      path: { webhookId },
      header: { 'Idempotency-Key': commandKey.keyFor({ webhookId, action, ...body }) },
    };
    try {
      const path =
        action === 'rotate'
          ? ('/integrations/webhooks/{webhookId}/rotate-secret' as const)
          : action === 'disable'
            ? ('/integrations/webhooks/{webhookId}/disable' as const)
            : ('/integrations/webhooks/{webhookId}/enable' as const);
      const { data, error, response } = await core.POST(path, { params, body });
      if (data) {
        commandKey.reset();
        if (action === 'rotate') onSecret(data);
        finish(`webhooks.announce.${action}`);
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const sendTest = async () => {
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/integrations/webhooks/{webhookId}/test-deliveries',
        {
          params: {
            path: { webhookId },
            header: { 'Idempotency-Key': testKey.keyFor({ webhookId }) },
          },
          body: { confirm: true },
        },
      );
      if (data) {
        testKey.reset();
        finish('webhooks.announce.testQueued');
      } else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const showAttempts = async (deliveryId: string) => {
    try {
      const { data, error, response } = await core.GET(
        '/integrations/webhooks/{webhookId}/deliveries/{deliveryId}',
        { params: { path: { webhookId, deliveryId } }, cache: 'no-store' },
      );
      if (data) setAttempts({ id: deliveryId, items: data.attempts });
      else setFailure(failureOf(response, error));
    } catch {
      setFailure({ messageKey: 'errors.network' });
    }
  };

  const failureBox = (value: Failure) => (
    <div className="error-summary" role="alert" data-testid="webhook-failure">
      <p>{t(value.messageKey)}</p>
      {value.retryAfter !== undefined && (
        <p>{t('webhooks.retryAfter', { count: value.retryAfter })}</p>
      )}
      {value.correlationId && (
        <p className="muted">{t('webhooks.reference', { id: value.correlationId })}</p>
      )}
    </div>
  );

  if (loadFailure) {
    return (
      <section aria-labelledby={`${ids}-title`}>
        <h2 id={`${ids}-title`}>{t('webhooks.detail.title')}</h2>
        {failureBox(loadFailure)}
        <button type="button" className="button button--secondary" onClick={onBack}>
          {t('webhooks.back')}
        </button>
      </section>
    );
  }
  if (!webhook) return <p className="muted">{t('webhooks.loading')}</p>;

  const active = webhook.status === 'ACTIVE';
  const failureText = (value: string | null) =>
    value ? t(`webhooks.failure.${value}`) : t('webhooks.none');

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="webhook-detail">
      <h2 id={`${ids}-title`}>{webhook.name}</h2>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>
      <dl className="detail-list">
        <dt>{t('webhooks.table.status')}</dt>
        <dd data-testid="detail-status" data-status={webhook.status}>
          {t(`webhooks.status.${webhook.status}`)}
        </dd>
        <dt>{t('webhooks.table.url')}</dt>
        <dd>
          <code>{webhook.url}</code>
        </dd>
        <dt>{t('webhooks.table.events')}</dt>
        <dd>{webhook.eventTypes.map((e) => t(`webhooks.events.${e}`)).join(', ')}</dd>
        <dt>{t('webhooks.detail.secretVersion')}</dt>
        <dd>{webhook.secretVersion}</dd>
        <dt>{t('webhooks.detail.failures')}</dt>
        <dd>{webhook.consecutiveFailures}</dd>
        <dt>{t('webhooks.table.lastSuccess')}</dt>
        <dd>{format(webhook.lastSuccessAt)}</dd>
        <dt>{t('webhooks.table.lastFailure')}</dt>
        <dd>{format(webhook.lastFailureAt)}</dd>
      </dl>
      {webhook.status === 'SUSPENDED' && (
        <p role="note" className="muted">
          {t('webhooks.detail.suspendedNote')}
        </p>
      )}
      {failure && failureBox(failure)}

      {editing ? (
        <WebhookForm
          initial={{ name: webhook.name, url: webhook.url, eventTypes: webhook.eventTypes }}
          submitKey="webhooks.form.save"
          busy={busy}
          failure={null}
          onSubmit={(values) => void saveEdit(values)}
          onCancel={() => {
            setEditing(false);
          }}
        />
      ) : (
        <div className="actions">
          <button
            type="button"
            className="button"
            onClick={() => {
              setEditing(true);
            }}
          >
            {t('webhooks.actions.edit')}
          </button>
          <button
            type="button"
            className="button button--secondary"
            disabled={!active || busy}
            onClick={() => void sendTest()}
          >
            {t('webhooks.actions.test')}
          </button>
          <button
            type="button"
            className="button button--secondary"
            disabled={busy}
            onClick={() => {
              setPending('rotate');
              setConfirmed(false);
            }}
          >
            {t('webhooks.actions.rotate')}
          </button>
          {webhook.status === 'ACTIVE' ? (
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setPending('disable');
                setConfirmed(false);
              }}
            >
              {t('webhooks.actions.disable')}
            </button>
          ) : (
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setPending('enable');
                setConfirmed(false);
              }}
            >
              {t('webhooks.actions.enable')}
            </button>
          )}
          <button type="button" className="button button--secondary" onClick={onBack}>
            {t('webhooks.back')}
          </button>
        </div>
      )}

      {pending && (
        <div className="card" role="group" aria-labelledby={`${ids}-confirm`} data-testid="confirm">
          <h3 id={`${ids}-confirm`}>{t(`webhooks.confirmAction.${pending}.title`)}</h3>
          <p>{t(`webhooks.confirmAction.${pending}.body`)}</p>
          <div className="field">
            <label>
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(event) => {
                  setConfirmed(event.target.checked);
                }}
              />{' '}
              {t('webhooks.confirmAction.understand')}
            </label>
          </div>
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={!confirmed || busy}
              onClick={() => void command(pending)}
            >
              {t(`webhooks.confirmAction.${pending}.submit`)}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setPending(null);
              }}
            >
              {t('webhooks.cancel')}
            </button>
          </div>
        </div>
      )}

      <section
        aria-labelledby={`${ids}-history`}
        className="card"
        aria-busy={history.kind === 'loading'}
      >
        <h3 id={`${ids}-history`}>{t('webhooks.history.title')}</h3>
        <p className="muted">{t('webhooks.history.note')}</p>
        <button
          type="button"
          className="button button--secondary"
          onClick={() => {
            setHistory({ kind: 'loading' });
            void loadHistory();
          }}
        >
          {t('webhooks.history.refresh')}
        </button>
        {history.kind === 'loading' && <p className="muted">{t('webhooks.loading')}</p>}
        {history.kind === 'failed' && failureBox(history.failure)}
        {history.kind === 'ready' && history.items.length === 0 && (
          <p className="muted" data-testid="history-empty">
            {t('webhooks.history.empty')}
          </p>
        )}
        {history.kind === 'ready' && history.items.length > 0 && (
          <div className="table-wrapper">
            <table data-testid="history-table">
              <caption>{t('webhooks.history.title')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('webhooks.history.event')}</th>
                  <th scope="col">{t('webhooks.history.state')}</th>
                  <th scope="col">{t('webhooks.history.attempts')}</th>
                  <th scope="col">{t('webhooks.history.result')}</th>
                  <th scope="col">{t('webhooks.history.next')}</th>
                  <th scope="col">
                    <span className="visually-hidden">{t('webhooks.history.details')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {history.items.map((delivery) => (
                  <tr key={delivery.id} data-testid="history-row">
                    <td>
                      <code>{delivery.eventType}</code>
                    </td>
                    <td data-state={delivery.state}>{t(`webhooks.state.${delivery.state}`)}</td>
                    <td>{delivery.attemptCount}</td>
                    <td>
                      {delivery.lastHttpStatus
                        ? t('webhooks.history.httpStatus', { status: delivery.lastHttpStatus })
                        : failureText(delivery.lastFailureClass)}
                    </td>
                    <td>{format(delivery.nextAttemptAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        onClick={() => void showAttempts(delivery.id)}
                      >
                        {t('webhooks.history.show')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {history.kind === 'ready' && history.nextCursor && (
          <button
            type="button"
            className="button button--secondary"
            disabled={history.loadingMore}
            onClick={() => {
              setHistory({ ...history, loadingMore: true });
              void loadHistory(history.nextCursor ?? undefined);
            }}
          >
            {t('webhooks.loadMore')}
          </button>
        )}
        {attempts && (
          <div role="group" aria-labelledby={`${ids}-attempts`} data-testid="attempts">
            <h4 id={`${ids}-attempts`}>{t('webhooks.history.attemptsTitle')}</h4>
            <ol>
              {attempts.items.map((attempt) => (
                <li key={attempt.number}>
                  {t('webhooks.history.attemptLine', {
                    number: attempt.number,
                    when: format(attempt.startedAt),
                    result: attempt.httpStatus
                      ? t('webhooks.history.httpStatus', { status: attempt.httpStatus })
                      : failureText(attempt.failureClass),
                    ms: attempt.latencyMillis,
                  })}
                </li>
              ))}
            </ol>
          </div>
        )}
      </section>

      <details className="card">
        <summary>{t('webhooks.receiver.title')}</summary>
        <p>{t('webhooks.receiver.intro')}</p>
        <ol>
          <li>{t('webhooks.receiver.step1')}</li>
          <li>{t('webhooks.receiver.step2')}</li>
          <li>{t('webhooks.receiver.step3')}</li>
          <li>{t('webhooks.receiver.step4')}</li>
        </ol>
        <pre>
          <code>{`key      = base64url_decode(secret)
expected = "v1=" + hex(HMAC_SHA256(key, timestamp + "\\n" + rawBody))
valid    = any(constantTimeEquals(expected, sig) for sig in header.split(","))
fresh    = abs(now - timestamp) <= 300 seconds
dedupe   = remember X-DivalHR-Event-Id; ignore repeats`}</code>
        </pre>
      </details>
    </section>
  );
}

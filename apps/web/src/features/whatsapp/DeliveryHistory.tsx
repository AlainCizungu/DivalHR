import type {
  WhatsAppDelivery,
  WhatsAppDeliveryDetail,
  WhatsAppDeliveryPage,
  WhatsAppRecipient,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { failureOf, type Failure } from './whatsapp';

type History =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | { kind: 'ready'; items: WhatsAppDelivery[]; nextCursor: string | null; loadingMore: boolean };

/** Delivery history: outcomes only (no number, message, link or provider identifier). */
export function DeliveryHistory({
  recipients,
  refreshToken,
  format,
}: {
  recipients: WhatsAppRecipient[];
  refreshToken: number;
  format: (iso: string | null) => string;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [recipientId, setRecipientId] = useState('');
  const [history, setHistory] = useState<History>({ kind: 'loading' });
  const [detail, setDetail] = useState<WhatsAppDeliveryDetail | null>(null);
  const [detailFailure, setDetailFailure] = useState<Failure | null>(null);

  const fetchPage = useCallback(
    async (cursor?: string): Promise<{ page: WhatsAppDeliveryPage } | { failure: Failure }> => {
      try {
        const query = {
          ...(cursor ? { cursor } : {}),
          ...(recipientId ? { recipientId } : {}),
        };
        const { data, error, response } = await core.GET(
          '/integrations/messaging/whatsapp/deliveries',
          {
            params: { query },
            cache: 'no-store',
          },
        );
        return data ? { page: data } : { failure: failureOf(response, error) };
      } catch {
        return { failure: { messageKey: 'errors.network' } };
      }
    },
    [core, recipientId],
  );

  const load = useCallback(
    async (cursor?: string) => {
      const result = await fetchPage(cursor);
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
    [fetchPage],
  );

  useEffect(() => {
    const status = { active: true };
    void (async () => {
      const result = await fetchPage();
      if (!status.active) return;
      setHistory(
        'failure' in result
          ? { kind: 'failed', failure: result.failure }
          : {
              kind: 'ready',
              items: result.page.items,
              nextCursor: result.page.nextCursor,
              loadingMore: false,
            },
      );
    })();
    return () => {
      status.active = false;
    };
  }, [fetchPage, refreshToken]);

  const show = async (deliveryId: string) => {
    setDetailFailure(null);
    try {
      const { data, error, response } = await core.GET(
        '/integrations/messaging/whatsapp/deliveries/{deliveryId}',
        { params: { path: { deliveryId } }, cache: 'no-store' },
      );
      if (data) setDetail(data);
      else setDetailFailure(failureOf(response, error));
    } catch {
      setDetailFailure({ messageKey: 'errors.network' });
    }
  };

  const failureText = (value: string | null) =>
    value ? t(`whatsapp.failure.${value}`) : t('whatsapp.none');
  const resultOf = (status: number | null, failureClass: string | null) =>
    status ? t('whatsapp.history.httpStatus', { status }) : failureText(failureClass);

  return (
    <section
      aria-labelledby={`${ids}-title`}
      className="card"
      aria-busy={history.kind === 'loading'}
      data-testid="whatsapp-history"
    >
      <h2 id={`${ids}-title`}>{t('whatsapp.history.title')}</h2>
      <p className="muted">{t('whatsapp.history.note')}</p>
      <div className="field">
        <label htmlFor={`${ids}-filter`}>{t('whatsapp.history.filter')}</label>
        <select
          id={`${ids}-filter`}
          value={recipientId}
          onChange={(event) => {
            setHistory({ kind: 'loading' });
            setDetail(null);
            setRecipientId(event.target.value);
          }}
        >
          <option value="">{t('whatsapp.history.allRecipients')}</option>
          {recipients.map((recipient) => (
            <option key={recipient.id} value={recipient.id}>
              {recipient.label}
            </option>
          ))}
        </select>
      </div>
      <button
        type="button"
        className="button button--secondary"
        onClick={() => {
          setHistory({ kind: 'loading' });
          void load();
        }}
      >
        {t('whatsapp.history.refresh')}
      </button>
      {history.kind === 'loading' && <p className="muted">{t('whatsapp.loading')}</p>}
      {history.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="whatsapp-failure">
          <p>{t(history.failure.messageKey)}</p>
          {history.failure.correlationId && (
            <p className="muted">
              {t('whatsapp.reference', { id: history.failure.correlationId })}
            </p>
          )}
        </div>
      )}
      {history.kind === 'ready' && history.items.length === 0 && (
        <p className="muted" data-testid="history-empty">
          {t('whatsapp.history.empty')}
        </p>
      )}
      {history.kind === 'ready' && history.items.length > 0 && (
        <div className="table-wrapper">
          <table data-testid="history-table">
            <caption>{t('whatsapp.history.title')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('whatsapp.history.recipient')}</th>
                <th scope="col">{t('whatsapp.history.type')}</th>
                <th scope="col">{t('whatsapp.history.state')}</th>
                <th scope="col">{t('whatsapp.history.attempts')}</th>
                <th scope="col">{t('whatsapp.history.result')}</th>
                <th scope="col">{t('whatsapp.history.completed')}</th>
                <th scope="col">
                  <span className="visually-hidden">{t('whatsapp.history.details')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {history.items.map((delivery) => (
                <tr key={delivery.id} data-testid="history-row">
                  <th scope="row">
                    {delivery.recipientLabel} <code>{delivery.numberMasked}</code>
                  </th>
                  <td>
                    {t(`whatsapp.types.${delivery.notificationType}`)}
                    {delivery.test ? ` (${t('whatsapp.history.test')})` : ''}
                  </td>
                  <td data-state={delivery.state}>{t(`whatsapp.state.${delivery.state}`)}</td>
                  <td>{delivery.attemptCount}</td>
                  <td>{resultOf(delivery.lastHttpStatus, delivery.failureClass)}</td>
                  <td>
                    {format(delivery.completedAt ?? delivery.lastAttemptAt ?? delivery.createdAt)}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="button button--secondary"
                      onClick={() => void show(delivery.id)}
                    >
                      {t('whatsapp.history.show')}
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
            void load(history.nextCursor ?? undefined);
          }}
        >
          {t('whatsapp.loadMore')}
        </button>
      )}
      {detailFailure && (
        <div className="error-summary" role="alert">
          <p>{t(detailFailure.messageKey)}</p>
        </div>
      )}
      {detail && (
        <div role="group" aria-labelledby={`${ids}-detail`} data-testid="delivery-detail">
          <h3 id={`${ids}-detail`}>{t('whatsapp.history.attemptsTitle')}</h3>
          <ol>
            {detail.attempts.map((attempt) => (
              <li key={attempt.number}>
                {t('whatsapp.history.attemptLine', {
                  number: attempt.number,
                  when: format(attempt.startedAt),
                  result: resultOf(attempt.httpStatus, attempt.failureClass),
                  ms: attempt.latencyMs,
                })}
              </li>
            ))}
          </ol>
          <h4>{t('whatsapp.history.callbacksTitle')}</h4>
          {detail.statusReports.length === 0 ? (
            <p className="muted">{t('whatsapp.history.noCallbacks')}</p>
          ) : (
            <ul>
              {detail.statusReports.map((callback) => (
                <li key={`${callback.status}-${callback.receivedAt}`}>
                  {t('whatsapp.history.callbackLine', {
                    when: format(callback.occurredAt),
                    status: t(`whatsapp.callbackStatus.${callback.status}`),
                  })}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}

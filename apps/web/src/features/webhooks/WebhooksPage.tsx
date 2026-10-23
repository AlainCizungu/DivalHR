import type { Webhook, WebhookPage, WebhookResult } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { SecretReveal } from './SecretReveal';
import { WebhookDetail } from './WebhookDetail';
import { WebhookForm } from './WebhookForm';
import { MAX_WEBHOOKS, failureOf, type Failure, type WebhookValues } from './webhooks';

type View = { kind: 'list' } | { kind: 'create' } | { kind: 'detail'; id: string };

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | { kind: 'ready'; items: Webhook[]; nextCursor: string | null; loadingMore: boolean };

const EMPTY: WebhookValues = { name: '', url: '', eventTypes: [] };

/**
 * MVP-110: manage secure outbound webhooks. The signing secret is revealed once, in memory only;
 * the page keeps filters, ids and secrets out of the URL, history and browser storage.
 */
export function WebhooksPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [view, setView] = useState<View>({ kind: 'list' });
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [reveal, setReveal] = useState<WebhookResult | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const createKey = useIdempotencyKey();

  const fetchPage = useCallback(
    async (cursor?: string): Promise<{ page: WebhookPage } | { failure: Failure }> => {
      try {
        const { data, error, response } = await core.GET('/integrations/webhooks', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data ? { page: data } : { failure: failureOf(response, error) };
      } catch {
        return { failure: { messageKey: 'errors.network' } };
      }
    },
    [core],
  );

  const load = useCallback(
    async (cursor?: string) => {
      const result = await fetchPage(cursor);
      if ('failure' in result) {
        setListing({ kind: 'failed', failure: result.failure });
        return;
      }
      setListing((current) => ({
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
      setListing(
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
  }, [fetchPage]);

  const format = (iso: string | null) =>
    iso
      ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
          new Date(iso),
        )
      : t('webhooks.never');

  const create = async (values: WebhookValues) => {
    setBusy(true);
    setFailure(null);
    const body = { ...values, confirm: true as const };
    try {
      const { data, error, response } = await core.POST('/integrations/webhooks', {
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
        body,
      });
      if (data) {
        createKey.reset();
        setReveal(data);
        setView({ kind: 'detail', id: data.webhook.id });
        setAnnouncement(t('webhooks.announce.created'));
        void load();
      } else {
        setFailure(failureOf(response, error));
      }
    } catch {
      setFailure({ messageKey: 'errors.network' });
    } finally {
      setBusy(false);
    }
  };

  const count = listing.kind === 'ready' ? listing.items.length : 0;

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('webhooks.title')}</h1>
      <p className="muted">{t('webhooks.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      {reveal?.secret && (
        <SecretReveal
          secret={reveal.secret}
          previousSecretExpiresAt={reveal.previousSecretExpiresAt}
          onDone={() => {
            setReveal(null);
          }}
        />
      )}

      {view.kind === 'create' && (
        <section aria-labelledby={`${ids}-new`} className="card">
          <h2 id={`${ids}-new`}>{t('webhooks.create.title')}</h2>
          <WebhookForm
            initial={EMPTY}
            submitKey="webhooks.form.create"
            busy={busy}
            failure={failure}
            onSubmit={(values) => void create(values)}
            onCancel={() => {
              setFailure(null);
              setView({ kind: 'list' });
            }}
          />
        </section>
      )}

      {view.kind === 'detail' && (
        <WebhookDetail
          webhookId={view.id}
          onChanged={() => void load()}
          onSecret={setReveal}
          onBack={() => {
            setReveal(null);
            setView({ kind: 'list' });
          }}
        />
      )}

      {view.kind === 'list' && (
        <section
          aria-labelledby={`${ids}-list`}
          className="card"
          aria-busy={listing.kind === 'loading'}
        >
          <h2 id={`${ids}-list`}>{t('webhooks.list.title')}</h2>
          <p className="muted">{t('webhooks.list.count', { count, max: MAX_WEBHOOKS })}</p>
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={count >= MAX_WEBHOOKS}
              onClick={() => {
                setFailure(null);
                setView({ kind: 'create' });
              }}
            >
              {t('webhooks.create.open')}
            </button>
          </div>
          {listing.kind === 'loading' && <p className="muted">{t('webhooks.loading')}</p>}
          {listing.kind === 'failed' && (
            <div className="error-summary" role="alert" data-testid="webhooks-error">
              <p>{t(listing.failure.messageKey)}</p>
              {listing.failure.retryAfter !== undefined && (
                <p>{t('webhooks.retryAfter', { count: listing.failure.retryAfter })}</p>
              )}
              {listing.failure.correlationId && (
                <p className="muted">
                  {t('webhooks.reference', { id: listing.failure.correlationId })}
                </p>
              )}
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setListing({ kind: 'loading' });
                  void load();
                }}
              >
                {t('webhooks.retry')}
              </button>
            </div>
          )}
          {listing.kind === 'ready' && listing.items.length === 0 && (
            <p className="muted" data-testid="webhooks-empty">
              {t('webhooks.list.empty')}
            </p>
          )}
          {listing.kind === 'ready' && listing.items.length > 0 && (
            <div className="table-wrapper">
              <table data-testid="webhooks-table">
                <caption>{t('webhooks.list.title')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('webhooks.table.name')}</th>
                    <th scope="col">{t('webhooks.table.url')}</th>
                    <th scope="col">{t('webhooks.table.status')}</th>
                    <th scope="col">{t('webhooks.table.events')}</th>
                    <th scope="col">{t('webhooks.table.lastSuccess')}</th>
                    <th scope="col">{t('webhooks.table.lastFailure')}</th>
                  </tr>
                </thead>
                <tbody>
                  {listing.items.map((hook) => (
                    <tr key={hook.id} data-testid="webhook-row">
                      <th scope="row">
                        <button
                          type="button"
                          className="link-button"
                          onClick={() => {
                            setView({ kind: 'detail', id: hook.id });
                          }}
                        >
                          {hook.name}
                        </button>
                      </th>
                      <td>
                        <code>{hook.url}</code>
                      </td>
                      <td data-status={hook.status}>{t(`webhooks.status.${hook.status}`)}</td>
                      <td>{hook.eventTypes.length}</td>
                      <td>{format(hook.lastSuccessAt)}</td>
                      <td>{format(hook.lastFailureAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {listing.kind === 'ready' && listing.nextCursor && (
            <button
              type="button"
              className="button button--secondary"
              disabled={listing.loadingMore}
              onClick={() => {
                setListing({ ...listing, loadingMore: true });
                void load(listing.nextCursor ?? undefined);
              }}
            >
              {t('webhooks.loadMore')}
            </button>
          )}
        </section>
      )}
    </section>
  );
}

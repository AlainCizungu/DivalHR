import type {
  PartnerAccountStatus,
  PartnerPage,
  PartnerReadyApplication,
  PartnerSummary,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { AccountBoundary, AccountStatus } from './AccountBits';
import { PARTNER_STATUSES, partnerPath } from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, referencePath, type PartnerFailure } from './partners';

type Ready =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; items: PartnerReadyApplication[]; next: string | null };

type Accounts =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      items: PartnerSummary[];
      next: string | null;
      counts: PartnerPage['statusCounts'];
    };

/** One approved application: creating the partner is a deliberate, confirmed, idempotent act. */
function ReadyRow({ item, onCreated }: { item: PartnerReadyApplication; onCreated: () => void }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const navigate = useNavigate();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [open, setOpen] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const regionNames = new Intl.DisplayNames([i18n.language], { type: 'region' });

  const create = async () => {
    if (busyRef.current || !confirmed) return;
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = { confirmed: true };
    try {
      const { data, error, response } = await core.POST(
        '/platform/partners/from-application/{applicationReference}',
        {
          params: {
            path: { applicationReference: item.applicationReference },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
        onCreated();
        void navigate(partnerPath(data.partner.reference));
        return;
      }
      setFailure(partnerFailureOf(response, error));
      requestAnimationFrame(() => alertRef.current?.focus());
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <li className="card" data-testid="ready-application" data-reference={item.applicationReference}>
      <h3>
        {item.legalName}
        {item.tradingName ? <span className="muted"> · {item.tradingName}</span> : null}
      </h3>
      <p className="muted">
        <Link to={referencePath(item.applicationReference)}>{item.applicationReference}</Link>
        {' · '}
        {regionNames.of(item.countryOfRegistration) ?? item.countryOfRegistration}
        {' · '}
        {t(`partners.category.${item.primaryCategory}`)}
        {' · '}
        <time dateTime={item.approvedAt}>
          {new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium' }).format(
            new Date(item.approvedAt),
          )}
        </time>
      </p>
      {item.partnerReference ? (
        <p>
          <Link to={partnerPath(item.partnerReference)} data-testid="ready-open">
            {t('accounts.ready.open', { reference: item.partnerReference })}
          </Link>
        </p>
      ) : open ? (
        <form
          noValidate
          aria-busy={busy}
          onSubmit={(event) => {
            event.preventDefault();
            void create();
          }}
        >
          <p id={`${ids}-copy`}>{t('accounts.ready.copyRule')}</p>
          {failure && (
            <PartnerAlert failure={failure} testId="ready-create-failure" alertRef={alertRef} />
          )}
          <label className="choice">
            <input
              type="checkbox"
              checked={confirmed}
              aria-describedby={`${ids}-copy`}
              onChange={(event) => {
                setConfirmed(event.target.checked);
              }}
            />
            {t('accounts.ready.confirm')}
          </label>
          <div className="button-row">
            <button
              type="submit"
              className="button"
              disabled={!confirmed || busy}
              data-testid="ready-create"
            >
              {t('accounts.ready.create')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setOpen(false);
                setConfirmed(false);
                setFailure(null);
              }}
            >
              {t('accounts.cancel')}
            </button>
          </div>
        </form>
      ) : (
        <button
          type="button"
          className="button"
          data-testid="ready-start"
          onClick={() => {
            setOpen(true);
          }}
        >
          {t('accounts.ready.start')}
        </button>
      )}
    </li>
  );
}

/**
 * MVP-120E: platform partner accounts. Approved applications ready for activation, and the
 * partners created from them. Filters stay in memory; nothing here is stored in the browser.
 */
export function PartnerAccountsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [filter, setFilter] = useState<PartnerAccountStatus | ''>('');
  const [applied, setApplied] = useState<PartnerAccountStatus | ''>('');
  const [ready, setReady] = useState<Ready>({ kind: 'loading' });
  const [accounts, setAccounts] = useState<Accounts>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    let active = true;
    core
      .GET('/platform/partners/ready-applications', {
        params: { query: { limit: 25 } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setReady(
            data
              ? { kind: 'ready', items: data.items, next: data.nextCursor }
              : { kind: 'failed', failure: partnerFailureOf(response, error) },
          );
        },
        () => {
          if (active) setReady({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, reload]);

  const query = useCallback(
    (cursor?: string) => ({ limit: 25, cursor, status: applied || undefined }),
    [applied],
  );

  useEffect(() => {
    let active = true;
    core.GET('/platform/partners', { params: { query: query() }, cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setAccounts(
          data
            ? { kind: 'ready', items: data.items, next: data.nextCursor, counts: data.statusCounts }
            : { kind: 'failed', failure: partnerFailureOf(response, error) },
        );
      },
      () => {
        if (active) setAccounts({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core, query, reload]);

  const more = async () => {
    if (accounts.kind !== 'ready' || accounts.next === null || loadingMore.current) return;
    loadingMore.current = true;
    setMoreBusy(true);
    try {
      const { data, error, response } = await core.GET('/platform/partners', {
        params: { query: query(accounts.next) },
        cache: 'no-store',
      });
      setAccounts(
        data
          ? {
              kind: 'ready',
              items: [...accounts.items, ...data.items],
              next: data.nextCursor,
              counts: data.statusCounts,
            }
          : { kind: 'failed', failure: partnerFailureOf(response, error) },
      );
    } catch {
      setAccounts({ kind: 'failed', failure: NETWORK_FAILURE });
    } finally {
      loadingMore.current = false;
      setMoreBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="partner-accounts-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('accounts.title')}
        description={t('accounts.description')}
      />
      <AccountBoundary />

      <section aria-labelledby={`${ids}-ready`}>
        <h2 id={`${ids}-ready`}>{t('accounts.ready.title')}</h2>
        <p className="muted">{t('accounts.ready.help')}</p>
        {ready.kind === 'loading' && (
          <p role="status" data-testid="ready-loading">
            {t('accounts.loading')}
          </p>
        )}
        {ready.kind === 'failed' && <PartnerAlert failure={ready.failure} testId="ready-failure" />}
        {ready.kind === 'ready' &&
          (ready.items.length === 0 ? (
            <p className="muted" data-testid="ready-empty">
              {t('accounts.ready.empty')}
            </p>
          ) : (
            <ul className="partner-ready" data-testid="ready-list">
              {ready.items.map((item) => (
                <ReadyRow
                  key={item.applicationReference}
                  item={item}
                  onCreated={() => {
                    setReload((n) => n + 1);
                  }}
                />
              ))}
            </ul>
          ))}
      </section>

      <section aria-labelledby={`${ids}-partners`}>
        <h2 id={`${ids}-partners`}>{t('accounts.list.title')}</h2>
        {accounts.kind === 'ready' && (
          <ul
            className="partner-counts"
            aria-label={t('accounts.list.counts')}
            data-testid="account-counts"
          >
            {PARTNER_STATUSES.map((status) => (
              <li key={status} data-status={status}>
                <span>{t(`accounts.status.${status}`)}</span>{' '}
                <b data-testid={`account-count-${status}`}>{accounts.counts[status]}</b>
              </li>
            ))}
          </ul>
        )}
        <form
          className="partner-filters"
          aria-label={t('accounts.list.filter')}
          onSubmit={(event) => {
            event.preventDefault();
            setApplied(filter);
          }}
        >
          <div className="field">
            <label htmlFor={`${ids}-status`}>{t('accounts.list.status')}</label>
            <select
              id={`${ids}-status`}
              value={filter}
              onChange={(event) => {
                setFilter(event.target.value as PartnerAccountStatus | '');
              }}
            >
              <option value="">{t('accounts.list.any')}</option>
              {PARTNER_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {t(`accounts.status.${status}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="button-row">
            <button type="submit" className="button">
              {t('accounts.list.apply')}
            </button>
          </div>
        </form>

        {accounts.kind === 'loading' && (
          <p role="status" data-testid="accounts-loading">
            {t('accounts.loading')}
          </p>
        )}
        {accounts.kind === 'failed' && (
          <PartnerAlert failure={accounts.failure} testId="accounts-failure" />
        )}
        {accounts.kind === 'ready' &&
          (accounts.items.length === 0 ? (
            <p className="muted" data-testid="accounts-empty">
              {t('accounts.list.empty')}
            </p>
          ) : (
            <>
              <ScrollRegion label={t('accounts.list.tableLabel')}>
                <table className="timesheet-table" data-testid="accounts-table">
                  <caption className="visually-hidden">{t('accounts.list.tableLabel')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('accounts.list.partner')}</th>
                      <th scope="col">{t('accounts.list.category')}</th>
                      <th scope="col">{t('accounts.list.statusColumn')}</th>
                      <th scope="col">{t('accounts.list.products')}</th>
                      <th scope="col">{t('accounts.list.updated')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {accounts.items.map((item) => (
                      <tr
                        key={item.reference}
                        data-testid="account-row"
                        data-reference={item.reference}
                        data-status={item.status}
                      >
                        <th scope="row">
                          <Link to={partnerPath(item.reference)}>{item.legalName}</Link>
                          <span className="muted"> · {item.reference}</span>
                        </th>
                        <td>{t(`partners.category.${item.primaryCategory}`)}</td>
                        <td>
                          <AccountStatus status={item.status} />
                        </td>
                        <td>
                          {t('accounts.list.productCounts', {
                            active: item.activeProductCount,
                            total: item.productCount,
                          })}
                        </td>
                        <td>
                          <time dateTime={item.updatedAt}>
                            {new Intl.DateTimeFormat(i18n.language, {
                              dateStyle: 'medium',
                              timeStyle: 'short',
                            }).format(new Date(item.updatedAt))}
                          </time>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
              {accounts.next !== null && (
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={moreBusy}
                  onClick={() => void more()}
                >
                  {t('accounts.list.more')}
                </button>
              )}
            </>
          ))}
      </section>
    </section>
  );
}

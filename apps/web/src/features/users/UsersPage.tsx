import { useEffect, useId, useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { InvitationRow } from './InvitationRow';
import { InviteForm } from './InviteForm';
import { STATUS_FILTERS, type StatusFilter } from './invitationForm';
import { useInvitationList, type InvitationList } from './useInvitationList';

function InvitationListView({
  list,
  filter,
  onAnnounce,
}: {
  list: InvitationList;
  filter: StatusFilter;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { state } = list;
  if (state.kind === 'loading') {
    return <p className="muted">{t('users.loading')}</p>;
  }
  if (state.kind === 'failed') {
    return (
      <div className="error-summary" data-testid="invitation-list-error">
        <p>{t(state.messageKey)}</p>
        <button
          type="button"
          className="button button--secondary"
          onClick={() => void list.refresh()}
        >
          {t('users.retry')}
        </button>
      </div>
    );
  }
  const focusNew = (id: string) =>
    list.focusId.current === id
      ? (node: HTMLElement | null) => {
          if (node) {
            list.focusId.current = null;
            node.focus();
          }
        }
      : undefined;
  return (
    <>
      {state.items.length === 0 ? (
        <p className="muted" data-testid="invitation-list-empty">
          {t(filter === 'ALL' ? 'users.list.empty' : 'users.list.emptyFiltered')}
        </p>
      ) : (
        <ul className="hierarchy-list" data-testid="invitation-list">
          {state.items.map((row) => (
            <li key={row.id} className="hierarchy-item" data-testid="invitation-row">
              <InvitationRow
                row={row}
                focusRef={focusNew(row.id)}
                onChanged={list.replaceItem}
                onAnnounce={onAnnounce}
              />
            </li>
          ))}
        </ul>
      )}
      {state.nextCursor && (
        <button
          type="button"
          className="button button--secondary"
          disabled={state.loadingMore}
          onClick={list.loadMore}
        >
          {state.loadingMore ? t('users.loading') : t('users.list.loadMore')}
        </button>
      )}
    </>
  );
}

function Invitations({
  filter,
  onAnnounce,
  listRef,
}: {
  filter: StatusFilter;
  onAnnounce: (message: string) => void;
  listRef: RefObject<InvitationList | null>;
}) {
  const { t } = useTranslation();
  const list = useInvitationList(filter);
  // Lets the invite form (outside this keyed subtree) insert the created row into this list.
  useEffect(() => {
    listRef.current = list;
  });
  return (
    <>
      <button
        type="button"
        className="button button--secondary"
        onClick={() => {
          void list.refresh().then((ok) => {
            if (ok) onAnnounce(t('users.announce.refreshed'));
          });
        }}
      >
        {t('users.list.refresh')}
      </button>
      <InvitationListView list={list} filter={filter} onAnnounce={onAnnounce} />
    </>
  );
}

/**
 * Tenant administrators invite people and follow their invitations (MVP-010). Delivery wording is
 * strict: QUEUED is "created, delivery pending", only SENT is "sent", FAILED is an actionable
 * warning. Invitee email addresses are shown only here and are never cached by the browser or the
 * service worker.
 */
export function UsersPage() {
  const { t } = useTranslation();
  const ids = useId();
  // The single polite live region for this page: every announcement goes here exactly once.
  const [announcement, setAnnouncement] = useState('');
  const [filter, setFilter] = useState<StatusFilter>('ALL');
  const listRef = useRef<InvitationList | null>(null);

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('users.title')}</h1>
      <p className="muted">{t('users.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section className="card">
        <InviteForm
          onCreated={(row) => {
            listRef.current?.insertCreated(row);
            setAnnouncement(t('users.announce.created', { email: row.email }));
          }}
        />
      </section>

      <section aria-labelledby={`${ids}-list`} className="card">
        <h2 id={`${ids}-list`}>{t('users.list.title')}</h2>
        <div className="field">
          <label htmlFor={`${ids}-filter`}>{t('users.list.filter')}</label>
          <select
            id={`${ids}-filter`}
            value={filter}
            onChange={(event) => {
              setFilter(event.target.value as StatusFilter);
            }}
          >
            {STATUS_FILTERS.map((option) => (
              <option key={option} value={option}>
                {t(`users.list.filters.${option}`)}
              </option>
            ))}
          </select>
        </div>
        <Invitations key={filter} filter={filter} onAnnounce={setAnnouncement} listRef={listRef} />
      </section>
    </section>
  );
}

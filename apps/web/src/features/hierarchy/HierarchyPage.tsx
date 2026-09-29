import type { LegalEntity, Problem, Site } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { LegalEntityForm, SiteForm } from './HierarchyForms';

type ListState<T> =
  | { kind: 'loading' }
  | { kind: 'failed'; messageKey: string }
  | { kind: 'ready'; items: T[]; nextCursor?: string; loadingMore: boolean };

function errorKey(status: number, error: unknown): string {
  if (status === 403) return 'hierarchy.unauthorized';
  const code = (error as Partial<Problem> | undefined)?.code;
  return code ? `errors.${code}` : 'errors.generic';
}

/**
 * Reusable keyset list state: first page, "load more" (focus moves to the first new item so it is
 * never lost when the button disappears), and prepend-on-create.
 */
function usePagedList<T extends { id: string }>(
  fetchPage: (cursor?: string) => Promise<{
    data?: { data: T[]; nextCursor?: string };
    error?: unknown;
    response: Response;
  }>,
) {
  const [state, setState] = useState<ListState<T>>({ kind: 'loading' });
  const focusId = useRef<string | null>(null);

  type PageResult = Awaited<ReturnType<typeof fetchPage>>;

  const apply = useCallback((cursor: string | undefined, result: PageResult) => {
    const { data, error, response } = result;
    if (!data) {
      setState({ kind: 'failed', messageKey: errorKey(response.status, error) });
      return;
    }
    const first = data.data[0];
    if (cursor && first) focusId.current = first.id;
    setState((current) => ({
      kind: 'ready',
      items: [...(cursor && current.kind === 'ready' ? current.items : []), ...data.data],
      nextCursor: data.nextCursor,
      loadingMore: false,
    }));
  }, []);

  const load = useCallback(
    (cursor?: string) =>
      fetchPage(cursor).then(
        (result) => {
          apply(cursor, result);
        },
        () => {
          setState({ kind: 'failed', messageKey: 'errors.network' });
        },
      ),
    [fetchPage, apply],
  );

  // Lists are keyed by their parent, so each instance loads its first page once.
  useEffect(() => {
    let active = true;
    fetchPage().then(
      (result) => {
        if (active) apply(undefined, result);
      },
      () => {
        if (active) setState({ kind: 'failed', messageKey: 'errors.network' });
      },
    );
    return () => {
      active = false;
    };
  }, [fetchPage, apply]);

  const loadMore = () => {
    if (state.kind !== 'ready' || !state.nextCursor || state.loadingMore) return;
    setState({ ...state, loadingMore: true });
    void load(state.nextCursor);
  };

  const prepend = (item: T) => {
    setState((current) =>
      current.kind === 'ready'
        ? { ...current, items: [item, ...current.items.filter((i) => i.id !== item.id)] }
        : { kind: 'ready', items: [item], loadingMore: false },
    );
  };

  const retry = () => {
    setState({ kind: 'loading' });
    void load();
  };

  return { state, loadMore, prepend, focusId, retry };
}

export function HierarchyPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const periodText = usePeriodText();
  // The single polite live region for this page: every announcement goes here exactly once.
  const [announcement, setAnnouncement] = useState('');
  const [selected, setSelected] = useState<LegalEntity | null>(null);
  const sitesHeading = useRef<HTMLHeadingElement>(null);

  const fetchLegalEntities = useCallback(
    (cursor?: string) => core.GET('/legal-entities', { params: { query: { cursor } } }),
    [core],
  );
  const legalEntities = usePagedList<LegalEntity>(fetchLegalEntities);

  const selectEntity = (entity: LegalEntity) => {
    setSelected(entity);
    // Moves the reading position to the sites section; the heading is focusable only
    // programmatically (tabIndex -1), so Tab continues normally from there.
    requestAnimationFrame(() => sitesHeading.current?.focus());
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('hierarchy.title')}</h1>
      <p className="muted">{t('hierarchy.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-le`} className="card">
        <h2 id={`${ids}-le`}>{t('hierarchy.legalEntities.title')}</h2>
        <PagedList
          list={legalEntities}
          emptyKey="hierarchy.legalEntities.empty"
          loadMoreKey="hierarchy.legalEntities.loadMore"
          testId="legal-entity-list"
          render={(entity, ref) => (
            <>
              <span className="hierarchy-item__main">
                <strong>{entity.name}</strong> <span className="badge">{entity.code}</span>
              </span>
              <span className="muted">
                {t(`country.${entity.countryCode}`)} ·{' '}
                {periodText(entity.effectiveFrom, entity.effectiveTo)}
              </span>
              <button
                ref={ref}
                type="button"
                className="button button--secondary"
                aria-pressed={selected?.id === entity.id}
                aria-label={t('hierarchy.legalEntities.select', {
                  name: entity.name,
                  code: entity.code,
                })}
                onClick={() => {
                  selectEntity(entity);
                }}
              >
                {t('hierarchy.legalEntities.selectShort')}
              </button>
            </>
          )}
        />
        <LegalEntityForm
          onCreated={(entity) => {
            legalEntities.prepend(entity);
            setAnnouncement(
              t('hierarchy.legalEntities.created', { name: entity.name, code: entity.code }),
            );
          }}
        />
      </section>

      {selected && (
        <section aria-labelledby={`${ids}-sites`} className="card" data-testid="sites-section">
          <h2 id={`${ids}-sites`} ref={sitesHeading} tabIndex={-1}>
            {t('hierarchy.sites.title', { name: selected.name, code: selected.code })}
          </h2>
          <SitesOf key={selected.id} parent={selected} onAnnounce={setAnnouncement} />
        </section>
      )}
    </section>
  );
}

function SitesOf({
  parent,
  onAnnounce,
}: {
  parent: LegalEntity;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const periodText = usePeriodText();
  const fetchSites = useCallback(
    (cursor?: string) =>
      core.GET('/sites', { params: { query: { legalEntityId: parent.id, cursor } } }),
    [core, parent.id],
  );
  const sites = usePagedList<Site>(fetchSites);
  return (
    <>
      <PagedList
        list={sites}
        emptyKey="hierarchy.sites.empty"
        loadMoreKey="hierarchy.sites.loadMore"
        testId="site-list"
        render={(site, ref) => (
          <>
            <span className="hierarchy-item__main" ref={ref} tabIndex={-1}>
              <strong>{site.name}</strong> <span className="badge">{site.code}</span>
            </span>
            <span className="muted">
              {t(`timezones.${site.timezone}`)} · {periodText(site.effectiveFrom, site.effectiveTo)}
            </span>
          </>
        )}
      />
      <SiteForm
        parent={parent}
        onCreated={(site) => {
          sites.prepend(site);
          onAnnounce(t('hierarchy.sites.created', { name: site.name, code: site.code }));
        }}
      />
    </>
  );
}

function PagedList<T extends { id: string }>({
  list,
  emptyKey,
  loadMoreKey,
  testId,
  render,
}: {
  list: ReturnType<typeof usePagedList<T>>;
  emptyKey: string;
  loadMoreKey: string;
  testId: string;
  render: (item: T, focusRef: ((node: HTMLElement | null) => void) | undefined) => React.ReactNode;
}) {
  const { t } = useTranslation();
  const { state } = list;

  if (state.kind === 'loading') {
    return <p className="muted">{t('hierarchy.loading')}</p>;
  }
  if (state.kind === 'failed') {
    return (
      <div className="error-summary" data-testid={`${testId}-error`}>
        <p>{t(state.messageKey)}</p>
        <button type="button" className="button button--secondary" onClick={list.retry}>
          {t('hierarchy.retry')}
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
        <p className="muted" data-testid={`${testId}-empty`}>
          {t(emptyKey)}
        </p>
      ) : (
        <ul className="hierarchy-list" data-testid={testId}>
          {state.items.map((item) => (
            <li key={item.id} className="hierarchy-item">
              {render(item, focusNew(item.id))}
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
          {state.loadingMore ? t('hierarchy.loading') : t(loadMoreKey)}
        </button>
      )}
    </>
  );
}

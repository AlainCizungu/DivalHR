import type { Problem } from '@divalhr/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

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
export function usePagedList<T extends { id: string }>(
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

export function PagedList<T extends { id: string }>({
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

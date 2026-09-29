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

/** Rows of every hierarchy list: the API's keyset is (code COLLATE "C", id). */
export interface KeysetRow {
  id: string;
  code: string;
}

/**
 * The API's order: code in byte order, then id. Codes are ASCII (A-Z, 0-9, '-', '_') and ids are
 * lower-case UUID text, so UTF-16 comparison equals PostgreSQL's "C" collation and uuid order.
 */
export function compareKeyset(a: KeysetRow, b: KeysetRow): number {
  if (a.code !== b.code) return a.code < b.code ? -1 : 1;
  if (a.id !== b.id) return a.id < b.id ? -1 : 1;
  return 0;
}

/** Merges rows into a keyset-ordered list, keeping one row per id (the incoming one wins). */
export function mergeKeyset<T extends KeysetRow>(
  current: readonly T[],
  incoming: readonly T[],
): T[] {
  const byId = new Map<string, T>();
  for (const row of current) byId.set(row.id, row);
  for (const row of incoming) byId.set(row.id, row);
  return [...byId.values()].sort(compareKeyset);
}

/**
 * Reusable keyset list state. The rendered list always follows the server's (code, id) order with
 * one row per id: "load more" merges the next page (focus moves to its first row that was not
 * already shown), and a created row is inserted at its keyset position and receives focus. A
 * created row that sorts after the current cursor is shown in place and de-duplicated when its
 * page arrives.
 */
export function usePagedList<T extends KeysetRow>(
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
    setState((current) => {
      const shown = cursor && current.kind === 'ready' ? current.items : [];
      if (cursor) {
        const seen = new Set(shown.map((row) => row.id));
        const firstNew = data.data.find((row) => !seen.has(row.id));
        if (firstNew) focusId.current = firstNew.id;
      }
      return {
        kind: 'ready',
        items: mergeKeyset(shown, data.data),
        nextCursor: data.nextCursor,
        loadingMore: false,
      };
    });
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

  const insertCreated = (item: T) => {
    focusId.current = item.id;
    setState((current) =>
      current.kind === 'ready'
        ? { ...current, items: mergeKeyset(current.items, [item]) }
        : { kind: 'ready', items: [item], loadingMore: false },
    );
  };

  /** Replaces a shown row in place (same id, same keyset position) without moving focus. */
  const replaceItem = (item: T) => {
    setState((current) =>
      current.kind === 'ready'
        ? { ...current, items: mergeKeyset(current.items, [item]) }
        : current,
    );
  };

  const retry = () => {
    setState({ kind: 'loading' });
    void load();
  };

  return { state, loadMore, insertCreated, replaceItem, focusId, retry };
}

export function PagedList<T extends KeysetRow>({
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

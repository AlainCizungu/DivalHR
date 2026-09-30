import type { Invitation, Problem } from '@divalhr/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useApi } from '../../app/ApiProvider';
import { matchesFilter, mergeInvitations, type StatusFilter } from './invitationForm';

type ListState =
  | { kind: 'loading' }
  | { kind: 'failed'; messageKey: string }
  | { kind: 'ready'; items: Invitation[]; nextCursor?: string; loadingMore: boolean };

function errorKey(status: number, error: unknown): string {
  if (status === 403) return 'users.unauthorized';
  const code = (error as Partial<Problem> | undefined)?.code;
  return code ? `errors.${code}` : 'errors.generic';
}

/**
 * Keyset list of the tenant's invitations (newest first). Every request is made with
 * cache: 'no-store' so that neither the browser HTTP cache nor any offline cache retains invitee
 * email addresses; the server also sends Cache-Control: private, no-store and the service worker
 * never caches API responses.
 */
export function useInvitationList(filter: StatusFilter) {
  const { core } = useApi();
  const [state, setState] = useState<ListState>({ kind: 'loading' });
  const focusId = useRef<string | null>(null);

  const fetchPage = useCallback(
    (cursor?: string) =>
      core.GET('/invitations', {
        params: { query: { cursor, status: filter === 'ALL' ? undefined : filter } },
        cache: 'no-store',
      }),
    [core, filter],
  );

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
        items: mergeInvitations(shown, data.data),
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
          return result.data !== undefined;
        },
        () => {
          setState({ kind: 'failed', messageKey: 'errors.network' });
          return false;
        },
      ),
    [fetchPage, apply],
  );

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

  /** Reloads the first page (for example to see delivery states that changed after sending). */
  const refresh = () => {
    setState({ kind: 'loading' });
    return load();
  };

  /** Shows a created invitation at its position, focused, when it matches the filter. */
  const insertCreated = (row: Invitation) => {
    if (!matchesFilter(row.status, filter)) return;
    focusId.current = row.id;
    setState((current) =>
      current.kind === 'ready'
        ? { ...current, items: mergeInvitations(current.items, [row]) }
        : { kind: 'ready', items: [row], loadingMore: false },
    );
  };

  /** Replaces a shown row in place (same id and createdAt, so the same position). */
  const replaceItem = (row: Invitation) => {
    setState((current) =>
      current.kind === 'ready'
        ? { ...current, items: mergeInvitations(current.items, [row]) }
        : current,
    );
  };

  return { state, loadMore, refresh, insertCreated, replaceItem, focusId };
}

export type InvitationList = ReturnType<typeof useInvitationList>;

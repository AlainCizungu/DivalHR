import { useEffect, useState } from 'react';

/**
 * Hierarchy option loading shared by the placement fields (MVP-021) and the contract expiration
 * unit filter (MVP-031A, A31A-3): the existing hierarchy list APIs, cascading from parent to child.
 */

/** Every page of a hierarchy list (at most 200 per page, as the region selects do). */
export async function loadAll<T>(
  fetchPage: (cursor?: string) => Promise<{ data?: { data: T[]; nextCursor?: string } }>,
): Promise<T[]> {
  const all: T[] = [];
  let cursor: string | undefined;
  do {
    const { data } = await fetchPage(cursor);
    if (!data) throw new Error('units unavailable');
    all.push(...data.data);
    cursor = data.nextCursor;
  } while (cursor);
  return all;
}

export type Option = { id: string; code: string; name: string };
export type Options = { status: 'idle' | 'loading' | 'ready' | 'failed'; items: Option[] };
type Loaded = { key: string; status: 'ready' | 'failed'; items: Option[] };

/** Options for a parent key; a different key reads as loading until its own result arrives. */
export function useOptions(key: string | null, load: () => Promise<Option[]>): Options {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  useEffect(() => {
    if (key === null) return;
    let active = true;
    load().then(
      (items) => {
        if (active) setLoaded({ key, status: 'ready', items });
      },
      () => {
        if (active) setLoaded({ key, status: 'failed', items: [] });
      },
    );
    return () => {
      active = false;
    };
    // The loader is keyed by `key`; recreating it on each render must not reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  if (key === null) return { status: 'idle', items: [] };
  if (loaded?.key !== key) return { status: 'loading', items: [] };
  return { status: loaded.status, items: loaded.items };
}

import type { Region } from '@divalhr/api-client';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useApi } from '../../app/ApiProvider';
import { mergeKeyset } from './pagedList';

/** Page size used to load every region of a legal entity for labels and selects. */
export const REGION_OPTIONS_PAGE = 200;

export type RegionOptions = ReturnType<typeof useRegionOptions>;

export type RegionOptionsStatus = 'loading' | 'ready' | 'failed';

/**
 * Every region of one legal entity, in keyset order, for region labels on sites and the region
 * selects (approved for the MVP: all pages at limit 200). The visible regions list stays paginated
 * separately. A newly created region is merged in immediately.
 *
 * Loading is retryable: after a failure the status stays {@code failed} until {@code retry()}
 * succeeds, and callers must not present an empty selector as if there were no regions.
 */
export function useRegionOptions(legalEntityId: string) {
  const { core } = useApi();
  const [regions, setRegions] = useState<Region[]>([]);
  const [status, setStatus] = useState<RegionOptionsStatus>('loading');
  const mounted = useRef(true);

  const load = useCallback(async () => {
    const all: Region[] = [];
    let cursor: string | undefined;
    do {
      const { data } = await core.GET('/regions', {
        params: { query: { legalEntityId, limit: REGION_OPTIONS_PAGE, cursor } },
      });
      if (!data) throw new Error('regions unavailable');
      all.push(...data.data);
      cursor = data.nextCursor;
    } while (cursor && mounted.current);
    return all;
  }, [core, legalEntityId]);

  useEffect(() => {
    mounted.current = true;
    let active = true;
    load().then(
      (all) => {
        if (!active) return;
        setRegions((current) => mergeKeyset(current, all));
        setStatus('ready');
      },
      () => {
        if (active) setStatus('failed');
      },
    );
    return () => {
      active = false;
      mounted.current = false;
    };
  }, [load]);

  /** Loads every page again; resolves to the outcome ({@code cancelled} once unmounted). */
  const retry = useCallback(async (): Promise<'ready' | 'failed' | 'cancelled'> => {
    setStatus('loading');
    try {
      const all = await load();
      if (!mounted.current) return 'cancelled';
      setRegions((current) => mergeKeyset(current, all));
      setStatus('ready');
      return 'ready';
    } catch {
      if (!mounted.current) return 'cancelled';
      setStatus('failed');
      return 'failed';
    }
  }, [load]);

  const add = useCallback((region: Region) => {
    setRegions((current) => mergeKeyset(current, [region]));
  }, []);

  const byId = useCallback(
    (id: string | null | undefined) =>
      id ? regions.find((region) => region.id === id) : undefined,
    [regions],
  );

  return { regions, status, retry, add, byId };
}

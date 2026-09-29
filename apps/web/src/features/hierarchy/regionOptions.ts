import type { Region } from '@divalhr/api-client';
import { useCallback, useEffect, useState } from 'react';
import { useApi } from '../../app/ApiProvider';
import { mergeKeyset } from './pagedList';

/** Page size used to load every region of a legal entity for labels and selects. */
export const REGION_OPTIONS_PAGE = 200;

export type RegionOptions = ReturnType<typeof useRegionOptions>;

/**
 * Every region of one legal entity, in keyset order, for region labels on sites and the region
 * selects (approved for the MVP: all pages at limit 200). The visible regions list stays paginated
 * separately. A newly created region is merged in immediately.
 */
export function useRegionOptions(legalEntityId: string) {
  const { core } = useApi();
  const [regions, setRegions] = useState<Region[]>([]);
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading');

  useEffect(() => {
    let active = true;
    const load = async () => {
      const all: Region[] = [];
      let cursor: string | undefined;
      do {
        const { data } = await core.GET('/regions', {
          params: { query: { legalEntityId, limit: REGION_OPTIONS_PAGE, cursor } },
        });
        if (!data) throw new Error('regions unavailable');
        all.push(...data.data);
        cursor = data.nextCursor;
      } while (cursor && active);
      return all;
    };
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
    };
  }, [core, legalEntityId]);

  const add = useCallback((region: Region) => {
    setRegions((current) => mergeKeyset(current, [region]));
  }, []);

  const byId = useCallback(
    (id: string | null | undefined) =>
      id ? regions.find((region) => region.id === id) : undefined,
    [regions],
  );

  return { regions, status, add, byId };
}

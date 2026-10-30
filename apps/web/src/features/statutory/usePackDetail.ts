import type { StatutoryRulePackDetail } from '@divalhr/api-client';
import { useEffect, useState } from 'react';
import { useApi } from '../../app/ApiProvider';

export type PackDetailState =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'failed' }
  | { kind: 'ready'; detail: StatutoryRulePackDetail };

/**
 * The structured rules and official sources of one pack, read so that an explanation can name each
 * rate, bracket, rounding and source. Rules are shown, never evaluated, in the browser.
 */
export function usePackDetail(packId: string | null): PackDetailState {
  const { core } = useApi();
  const [state, setState] = useState<{ packId: string; value: PackDetailState } | null>(null);

  useEffect(() => {
    if (!packId) return;
    let active = true;
    core
      .GET('/statutory-rule-packs/{packId}', {
        params: { path: { packId } },
        cache: 'no-store',
      })
      .then(
        ({ data }) => {
          if (active) {
            setState({
              packId,
              value: data ? { kind: 'ready', detail: data } : { kind: 'failed' },
            });
          }
        },
        () => {
          if (active) setState({ packId, value: { kind: 'failed' } });
        },
      );
    return () => {
      active = false;
    };
  }, [core, packId]);

  if (!packId) return { kind: 'idle' };
  return state?.packId === packId ? state.value : { kind: 'loading' };
}

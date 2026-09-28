import type { SystemStatus } from '@divalhr/api-client';
import { useCallback, useEffect, useState } from 'react';

export type ServiceState =
  { kind: 'checking' } | { kind: 'up' | 'down'; status: SystemStatus } | { kind: 'unreachable' };

type StatusResult = Promise<{ data?: SystemStatus; error?: unknown; response: Response }>;

/** Calls a public status endpoint and classifies the outcome. */
export async function classifyStatus(fetchStatus: () => StatusResult): Promise<ServiceState> {
  try {
    const { data, error, response } = await fetchStatus();
    const body = data ?? (error as SystemStatus | undefined);
    if (response.ok && body?.status === 'UP') return { kind: 'up', status: body };
    if (body && typeof body === 'object' && 'status' in body) return { kind: 'down', status: body };
    return { kind: 'unreachable' };
  } catch {
    return { kind: 'unreachable' };
  }
}

export function useServiceStatus(fetchStatus: () => StatusResult) {
  const [state, setState] = useState<ServiceState>({ kind: 'checking' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    void classifyStatus(fetchStatus).then((next) => {
      if (active) setState(next);
    });
    return () => {
      active = false;
    };
  }, [fetchStatus, attempt]);

  const check = useCallback(() => {
    setState({ kind: 'checking' });
    setAttempt((value) => value + 1);
  }, []);

  return { state, check };
}

import type { CurrentSession } from '@divalhr/api-client';
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { useApi } from './ApiProvider';

export type SessionState =
  | { kind: 'anonymous' }
  | { kind: 'loading' }
  | { kind: 'ready'; session: CurrentSession }
  | { kind: 'error'; code: string };

/** Exported for tests that need a fixed session. */
export const SessionContext = createContext<SessionState>({ kind: 'anonymous' });

/**
 * Loads the server-verified session (tenant and roles) once per sign-in. Role-based UI uses this,
 * never token contents; the API remains the authority for every decision.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const { core } = useApi();
  const [state, setState] = useState<SessionState>({ kind: 'loading' });

  useEffect(() => {
    if (status !== 'authenticated') return;
    let active = true;
    core
      .GET('/session')
      .then(({ data, error }) => {
        if (!active) return;
        if (data) setState({ kind: 'ready', session: data });
        else
          setState({
            kind: 'error',
            code: (error as { code?: string } | undefined)?.code ?? 'generic',
          });
      })
      .catch(() => {
        if (active) setState({ kind: 'error', code: 'generic' });
      });
    return () => {
      active = false;
    };
  }, [core, status]);

  const value: SessionState = status === 'authenticated' ? state : { kind: 'anonymous' };
  return <SessionContext value={value}>{children}</SessionContext>;
}

export function useSession(): SessionState {
  return useContext(SessionContext);
}

export function useHasRole(role: string): boolean {
  const session = useSession();
  return session.kind === 'ready' && session.session.roles.includes(role as never);
}

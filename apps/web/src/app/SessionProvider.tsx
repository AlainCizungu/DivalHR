import type { CurrentSession } from '@divalhr/api-client';
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { useApi } from './ApiProvider';

export type SessionState =
  | { kind: 'anonymous' }
  | { kind: 'loading' }
  | { kind: 'ready'; session: CurrentSession }
  | { kind: 'error'; code: string };

/** Exported for tests that need a fixed session. */
export const SessionContext = createContext<SessionState>({ kind: 'anonymous' });

/** UI-001: loads the session again after an error (the shell's "Try again"). */
const SessionRetryContext = createContext<() => void>(() => undefined);

/**
 * Loads the server-verified session (tenant and roles) once per sign-in. Role-based UI uses this,
 * never token contents; the API remains the authority for every decision.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const { core } = useApi();
  const [state, setState] = useState<SessionState>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const retry = useCallback(() => {
    setState({ kind: 'loading' });
    setAttempt((value) => value + 1);
  }, []);

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
  }, [core, status, attempt]);

  const value: SessionState = status === 'authenticated' ? state : { kind: 'anonymous' };
  return (
    <SessionRetryContext value={retry}>
      <SessionContext value={value}>{children}</SessionContext>
    </SessionRetryContext>
  );
}

export function useSession(): SessionState {
  return useContext(SessionContext);
}

export function useSessionRetry(): () => void {
  return useContext(SessionRetryContext);
}

export function useHasRole(role: string): boolean {
  const session = useSession();
  return session.kind === 'ready' && session.session.roles.includes(role as never);
}

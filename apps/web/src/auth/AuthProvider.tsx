import type { User, UserManager } from 'oidc-client-ts';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';

type AuthStatus = 'anonymous' | 'authenticated' | 'error';

interface AuthContextValue {
  status: AuthStatus;
  signIn: (returnTo?: string) => Promise<void>;
  signOut: () => Promise<void>;
  completeSignIn: () => Promise<string>;
  getAccessToken: () => string | undefined;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  userManager,
  children,
}: {
  userManager: UserManager;
  children: ReactNode;
}) {
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<AuthStatus>('anonymous');
  // Token kept in a ref so API calls read the latest value without re-rendering the tree.
  const userRef = useRef<User | null>(null);

  useEffect(() => {
    const onLoaded = (loaded: User) => {
      userRef.current = loaded;
      setUser(loaded);
      setStatus('authenticated');
    };
    const onUnloaded = () => {
      userRef.current = null;
      setUser(null);
      setStatus('anonymous');
    };
    userManager.events.addUserLoaded(onLoaded);
    userManager.events.addUserUnloaded(onUnloaded);
    userManager.events.addAccessTokenExpired(onUnloaded);
    userManager.events.addSilentRenewError(onUnloaded);
    return () => {
      userManager.events.removeUserLoaded(onLoaded);
      userManager.events.removeUserUnloaded(onUnloaded);
      userManager.events.removeAccessTokenExpired(onUnloaded);
      userManager.events.removeSilentRenewError(onUnloaded);
    };
  }, [userManager]);

  const signIn = useCallback(
    (returnTo = '/') =>
      userManager.signinRedirect({
        state: { returnTo },
        // Show the identity provider's pages in the user's current language.
        extraQueryParams: { ui_locales: document.documentElement.lang || 'fr' },
      }),
    [userManager],
  );

  const signOut = useCallback(async () => {
    const idTokenHint = userRef.current?.id_token;
    userRef.current = null;
    setUser(null);
    setStatus('anonymous');
    await userManager.signoutRedirect({ id_token_hint: idTokenHint });
  }, [userManager]);

  const completeSignIn = useCallback(async () => {
    try {
      const signedIn = await userManager.signinRedirectCallback();
      userRef.current = signedIn;
      setUser(signedIn);
      setStatus('authenticated');
      const state = signedIn.state as { returnTo?: unknown } | undefined;
      const returnTo = typeof state?.returnTo === 'string' ? state.returnTo : '/';
      // Only same-origin relative paths are honoured to avoid open redirects.
      return returnTo.startsWith('/') && !returnTo.startsWith('//') ? returnTo : '/';
    } catch (error) {
      setStatus('error');
      throw error;
    }
  }, [userManager]);

  const value = useMemo<AuthContextValue>(
    () => ({
      status: user ? 'authenticated' : status,
      signIn,
      signOut,
      completeSignIn,
      getAccessToken: () => userRef.current?.access_token,
    }),
    [user, status, signIn, signOut, completeSignIn],
  );
  return <AuthContext value={value}>{children}</AuthContext>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}

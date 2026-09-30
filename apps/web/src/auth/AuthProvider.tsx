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

/** Level the Core requires for privileged operations (MVP-011, RFC 9470 acr_values). */
export const MFA_ACR = 'urn:divalhr:loa:mfa';

interface AuthContextValue {
  status: AuthStatus;
  signIn: (returnTo?: string) => Promise<void>;
  /**
   * The Core answered MFA_REQUIRED: sign in again at the MFA level and come back to
   * {@code returnTo}. Once per signed-in session; a second refusal shows the MFA-required page
   * instead of redirecting again.
   */
  requireStepUp: (returnTo: string) => void;
  /** Explicit, user-initiated step-up from the MFA-required page. */
  stepUp: (returnTo: string) => Promise<void>;
  /** Set when a step-up was already completed and the Core still refused. */
  mfaBlocked: { returnTo: string } | null;
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
  // In memory like the tokens: a reload starts a new sign-in anyway.
  const steppedUp = useRef(false);
  const stepUpInFlight = useRef(false);
  const [mfaBlocked, setMfaBlocked] = useState<{ returnTo: string } | null>(null);

  useEffect(() => {
    const onLoaded = (loaded: User) => {
      userRef.current = loaded;
      setUser(loaded);
      setStatus('authenticated');
    };
    const onUnloaded = () => {
      steppedUp.current = false;
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

  const stepUp = useCallback(
    (returnTo: string) =>
      userManager.signinRedirect({
        state: { returnTo, stepUp: true },
        extraQueryParams: {
          ui_locales: document.documentElement.lang || 'fr',
          acr_values: MFA_ACR,
        },
      }),
    [userManager],
  );

  const requireStepUp = useCallback(
    (returnTo: string) => {
      if (steppedUp.current) {
        // Already stepped up in this session and still refused: never loop through the IdP.
        setMfaBlocked({ returnTo });
        return;
      }
      if (stepUpInFlight.current) return;
      stepUpInFlight.current = true;
      void stepUp(returnTo);
    },
    [stepUp],
  );

  const signOut = useCallback(async () => {
    steppedUp.current = false;
    setMfaBlocked(null);
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
      const state = signedIn.state as { returnTo?: unknown; stepUp?: unknown } | undefined;
      steppedUp.current = state?.stepUp === true;
      stepUpInFlight.current = false;
      setMfaBlocked(null);
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
      requireStepUp,
      stepUp,
      mfaBlocked,
      signOut,
      completeSignIn,
      getAccessToken: () => userRef.current?.access_token,
    }),
    [user, status, signIn, requireStepUp, stepUp, mfaBlocked, signOut, completeSignIn],
  );
  return <AuthContext value={value}>{children}</AuthContext>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside AuthProvider');
  return context;
}

import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import type { RuntimeConfig } from '../config/runtime';

/**
 * Browser Authorization Code + PKCE client. APPROVED FOR DEVELOPMENT ONLY (Issue #3 review,
 * docs/DECISIONS/0005): a BFF-versus-hardened-SPA decision is required before any real HR data.
 *
 * - Tokens (access, refresh, ID) live in memory only and are lost on reload.
 * - sessionStorage holds only the transient PKCE state/verifier for the redirect round-trip.
 * - No offline_access; refresh uses the in-memory refresh token while the tab is open.
 */
export function createUserManager(config: RuntimeConfig, origin = window.location.origin) {
  return new UserManager({
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: `${origin}/auth/callback`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
    includeIdTokenInSilentRenew: false,
    monitorSession: false,
    revokeTokensOnSignout: true,
    loadUserInfo: false,
  });
}

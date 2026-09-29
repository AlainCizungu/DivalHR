import { render } from '@testing-library/react';
import type { UserManager } from 'oidc-client-ts';
import { App } from '../app/App';
import type { RuntimeConfig } from '../config/runtime';
import { initI18n } from '../i18n';

export const testConfig: RuntimeConfig = {
  environment: 'development',
  coreApiUrl: 'http://core.test/api/v1',
  aiServiceUrl: 'http://ai.test/api/v1',
  oidcAuthority: 'http://idp.test/realms/divalhr-dev',
  oidcClientId: 'divalhr-web',
};

/** Minimal UserManager double: events only; redirects are asserted, never performed. */
export function fakeUserManager() {
  const noop = () => undefined;
  return {
    events: {
      addUserLoaded: noop,
      removeUserLoaded: noop,
      addUserUnloaded: noop,
      removeUserUnloaded: noop,
      addAccessTokenExpired: noop,
      removeAccessTokenExpired: noop,
      addSilentRenewError: noop,
      removeSilentRenewError: noop,
    },
    signinRedirect: () => Promise.resolve(),
    signoutRedirect: () => Promise.resolve(),
    signinRedirectCallback: () => Promise.reject(new Error('not used')),
  } as unknown as UserManager;
}

export async function renderApp(path: string, locale: 'fr' | 'en' = 'en') {
  window.history.pushState({}, '', path);
  await initI18n(locale);
  return render(<App config={testConfig} userManager={fakeUserManager()} />);
}

import { test as setup } from '@playwright/test';
import { sanitizeState, ssoDir, writeState } from './sso-state.ts';
import { IDENTITY_HOST, SSO_SEED_USERS, signIn } from './support.ts';

// DEVX-001A (D2, A75-3): one real Keycloak password-and-MFA sign-in per privileged seed role,
// through the web app, then only that browser's Keycloak SSO session cookies are kept, validated
// and written 0600 into the run's private directory. The app's own state (tokens are in memory;
// the language preference is in localStorage) is never saved.
for (const user of SSO_SEED_USERS) {
  setup(`a real MFA sign-in establishes the SSO session of ${user[0]}`, async ({ browser }) => {
    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      await signIn(page, user, 'en');
      const state = sanitizeState(await context.storageState(), IDENTITY_HOST);
      writeState(ssoDir(), user[0], state, IDENTITY_HOST);
    } finally {
      await context.close();
    }
  });
}

import { InMemoryWebStorage } from 'oidc-client-ts';
import { describe, expect, it } from 'vitest';
import { testConfig } from '../test/renderApp';
import { createUserManager } from './oidc';

describe('OIDC client configuration', () => {
  const manager = createUserManager(testConfig, 'http://localhost:5173');
  const settings = manager.settings;

  it('uses Authorization Code with PKCE and the development redirect URIs', () => {
    expect(settings.response_type).toBe('code');
    expect(settings.redirect_uri).toBe('http://localhost:5173/auth/callback');
    expect(settings.scope).not.toContain('offline_access');
  });

  it('keeps tokens in memory only, never in localStorage', () => {
    const userStore = settings.userStore as unknown as { _store: unknown };
    expect(userStore._store).toBeInstanceOf(InMemoryWebStorage);
    expect(userStore._store).not.toBe(window.localStorage);
    expect(userStore._store).not.toBe(window.sessionStorage);
  });

  it('stores nothing in localStorage when a user is saved', async () => {
    await settings.userStore.set('user:test', JSON.stringify({ access_token: 'secret-token' }));
    for (const storage of [window.localStorage, window.sessionStorage]) {
      const values = Array.from({ length: storage.length }, (_, i) => {
        const key = storage.key(i);
        return key === null ? '' : `${key}=${storage.getItem(key) ?? ''}`;
      });
      expect(values.join('\n')).not.toContain('secret-token');
    }
  });
});

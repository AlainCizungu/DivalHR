import { describe, expect, it } from 'vitest';
import { workboxOptions } from './workbox';

// Workbox's NavigationRoute tests each deny-list expression against pathname + search.
const denied = (path: string) => {
  const url = new URL(path, 'https://hr-dev.example.test');
  return workboxOptions.navigateFallbackDenylist.some((re) => re.test(url.pathname + url.search));
};

describe('service worker caching (MVP-010 guardrails 3 and 6)', () => {
  it('has no runtime caching, so no API response is ever stored by the service worker', () => {
    expect(workboxOptions.runtimeCaching).toEqual([]);
  });

  it('precaches only static shell assets', () => {
    expect(workboxOptions.globPatterns).toEqual(['**/*.{js,css,html,svg}']);
    expect(workboxOptions.globIgnores).toContain('config.js');
  });

  it('never answers API or identity-provider navigations with the cached shell', () => {
    for (const path of [
      '/api/v1/invitations',
      '/api/v1/invitations?status=PENDING',
      '/api/v1/public/invitations/inspect',
      '/api/v1/public/invitations/accept',
      '/auth/callback',
    ]) {
      expect(denied(path), path).toBe(true);
    }
  });

  it('never answers the same-origin identity provider or AI service with the cached shell (OPS-001)', () => {
    for (const path of [
      '/identity/realms/divalhr-test/protocol/openid-connect/auth',
      '/identity/realms/divalhr-test/protocol/openid-connect/logout',
      '/identity/realms/divalhr-test/login-actions/action-token',
      '/ai/api/v1/system/status',
    ]) {
      expect(denied(path), path).toBe(true);
    }
  });

  it('denies every server-owned namespace itself, with or without a query (PR #69 review)', () => {
    for (const path of [
      '/identity',
      '/identity?x=1',
      '/identity/',
      '/ai',
      '/ai?x=1',
      '/api',
      '/api?x=1',
      '/auth',
      '/auth?code=x&state=y',
    ]) {
      expect(denied(path), path).toBe(true);
    }
  });

  it('keeps child paths of those namespaces denied, with or without a query', () => {
    for (const path of [
      '/identity/realms/divalhr-test/protocol/openid-connect/auth?client_id=divalhr-web',
      '/identity/resources/x/login/keycloak.v2/css/styles.css',
      '/ai/api/v1/system/status',
      '/api/v1/session',
      '/auth/callback?code=x&state=y',
    ]) {
      expect(denied(path), path).toBe(true);
    }
  });

  it('leaves application routes that merely share a prefix to the shell', () => {
    for (const path of [
      '/identity-card',
      '/identity-card?x=1',
      '/apiary',
      '/air',
      '/author',
      '/authority/x',
      '/',
      '/?lang=fr',
    ]) {
      expect(denied(path), path).toBe(false);
    }
  });

  it('serves the invitation page from the shell; its token stays in the fragment', () => {
    expect(denied('/invitation')).toBe(false);
    expect(denied('/admin/users')).toBe(false);
  });
});

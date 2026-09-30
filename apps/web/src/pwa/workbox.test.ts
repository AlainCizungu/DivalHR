import { describe, expect, it } from 'vitest';
import { workboxOptions } from './workbox';

const denied = (path: string) =>
  workboxOptions.navigateFallbackDenylist.some((re) => re.test(path));

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

  it('serves the invitation page from the shell; its token stays in the fragment', () => {
    expect(denied('/invitation')).toBe(false);
    expect(denied('/admin/users')).toBe(false);
  });
});

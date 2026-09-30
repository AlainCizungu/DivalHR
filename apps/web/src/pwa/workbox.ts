/**
 * Service-worker (Workbox) options, shared by vite.config.ts and the tests that pin them.
 *
 * The service worker caches the application shell only. It has no runtime caching, so no API
 * response (and in particular no invitation list with email addresses, and no public invitation
 * response) is ever stored by it. Navigations to API paths and the identity-provider callback are
 * never answered with the cached shell.
 */
export const workboxOptions = {
  globPatterns: ['**/*.{js,css,html,svg}'],
  globIgnores: ['config.js'],
  navigateFallbackDenylist: [/^\/auth\//, /^\/api\//],
  runtimeCaching: [],
};

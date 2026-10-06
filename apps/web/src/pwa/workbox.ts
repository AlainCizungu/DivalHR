/**
 * Service-worker (Workbox) options, shared by vite.config.ts and the tests that pin them.
 *
 * The service worker caches the application shell only. It has no runtime caching, so no API
 * response (and in particular no invitation list with email addresses, and no public invitation
 * response) is ever stored by it. Navigations to API paths and the identity-provider callback are
 * never answered with the cached shell. Neither are the server-side routes that share the web app's
 * origin in a same-origin deployment (OPS-001, hr-dev): the identity provider under /identity/
 * (sign-in, setup and logout pages) and the AI service under /ai/. Without that, an installed
 * service worker answers the sign-in redirect with the application's "Page not found".
 */
export const workboxOptions = {
  globPatterns: ['**/*.{js,css,html,svg}'],
  globIgnores: ['config.js'],
  // Workbox tests each expression against pathname + search, so every namespace is matched at a
  // boundary: the bare prefix, a child path or a query string (/identity, /identity?x=1,
  // /identity/...), never a longer application path (/identity-card, /apiary, /air, /author).
  navigateFallbackDenylist: [
    /^\/auth(?:[/?]|$)/,
    /^\/api(?:[/?]|$)/,
    /^\/identity(?:[/?]|$)/,
    /^\/ai(?:[/?]|$)/,
  ],
  runtimeCaching: [],
};

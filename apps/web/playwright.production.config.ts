import { defineConfig, devices } from '@playwright/test';

process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

/**
 * LAUNCH-002 (Issue #230): the two live acceptance journeys of the controlled-pilot production
 * environment: the public and sign-in journey, and the bilingual synthetic tenant journey. They run
 * ONLY after the stack is provisioned and the launch lock is still closed, with synthetic data only.
 * Never part of the normal suite:
 * `pnpm --filter @divalhr/web exec playwright test -c playwright.production.config.ts`.
 *
 *   PROD_PUBLIC_URL   https://divalhr.com          (required)
 *   PROD_APP_URL      https://app.divalhr.com      (required)
 *   PROD_WWW_URL      https://www.divalhr.com      (optional: checks the redirect)
 *   PROD_ACCEPTANCE_USER / PROD_ACCEPTANCE_PASSWORD   the ONE synthetic acceptance tenant's
 *                     employee (created through the normal invitation flow, never seeded)
 *
 * Credentials come from the environment only; traces, screenshots and videos stay off.
 */
export default defineConfig({
  testDir: './e2e/production',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    serviceWorkers: 'block',
  },
  projects: [{ name: 'production', use: { ...devices['Desktop Chrome'] } }],
});

import { defineConfig, devices } from '@playwright/test';

process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

/**
 * LAUNCH-002 (Issue #230): the two live acceptance journeys of the controlled-pilot production
 * environment, exactly two tests in total: the public and sign-in journey (service workers allowed),
 * and the bilingual synthetic tenant journey. They run ONLY after the stack is provisioned and the
 * launch lock is still closed, with synthetic data only. Never part of the normal suite; run by
 * `ops/production/accept.sh`, which fails before Playwright when anything below is missing and then
 * checks the JSON result (exactly two expected tests, none failed, skipped, flaky or retried).
 *
 *   PROD_PUBLIC_URL, PROD_APP_URL, PROD_WWW_URL         https origins (all required)
 *   PROD_PLATFORM_ADMIN_USER / _PASSWORD / _TOTP_SECRET the operator-provisioned platform administrator
 *   PROD_ACCEPTANCE_MAILBOX                             the operator's own mailbox (plus-addressed)
 *   PROD_ACCEPTANCE_MAIL_CMD                            operator command: <recipient> <subject-pattern>
 *
 * Credentials come from the environment only; traces, screenshots and videos stay off.
 */
export default defineConfig({
  testDir: './e2e/production',
  timeout: 1_800_000,
  expect: { timeout: 15_000 },
  retries: 0,
  workers: 1,
  reporter: [
    ['list'],
    [
      'json',
      { outputFile: process.env.PROD_PLAYWRIGHT_JSON ?? 'test-results/production-results.json' },
    ],
  ],
  use: {
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    serviceWorkers: 'allow',
  },
  projects: [{ name: 'production', use: { ...devices['Desktop Chrome'] } }],
});

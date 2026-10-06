import { defineConfig, devices } from '@playwright/test';

process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

/**
 * OPS-001 (Issue #65): acceptance and smoke tests of the deployed test environment, run against
 * https://hr-dev.dival.ai from the instance (or against a rehearsal). Never part of the normal
 * suite: `pnpm --filter @divalhr/web exec playwright test -c playwright.hr-dev.config.ts`.
 *
 *   HR_DEV_BASE_URL       public origin (required)
 *   HR_DEV_ADMIN_URL      operator site through the SSH tunnel or on the instance's loopback
 *   HR_DEV_KC_ADMIN_USER / HR_DEV_KC_ADMIN_PASSWORD   master-realm administrator, used only to
 *                         create and delete temporary synthetic users
 *   HR_DEV_EMPLOYEE_USERNAME / HR_DEV_EMPLOYEE_PASSWORD   the synthetic smoke employee (live only)
 *   HR_DEV_BROWSER_ARGS   extra Chromium arguments (a rehearsal maps the public name to its port)
 *   HR_DEV_REHEARSAL=1    a rehearsal behind Caddy's internal CA: the browser accepts that CA's
 *                         certificate (also for service-worker registration, which Chromium
 *                         otherwise refuses on a certificate error); every curl check still
 *                         validates it with --cacert
 *
 * Credentials come from the environment only; traces, screenshots and videos stay off (MVP-011).
 */
const rehearsal = process.env.HR_DEV_REHEARSAL === '1';
const args = [
  ...(process.env.HR_DEV_BROWSER_ARGS ?? '').split('\n').filter(Boolean),
  ...(rehearsal ? ['--ignore-certificate-errors'] : []),
];

export default defineConfig({
  testDir: './e2e/hr-dev',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: process.env.HR_DEV_BASE_URL,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    serviceWorkers: 'block',
    ignoreHTTPSErrors: rehearsal,
    launchOptions: { args },
  },
  projects: [{ name: 'hr-dev', use: { ...devices['Desktop Chrome'] } }],
});

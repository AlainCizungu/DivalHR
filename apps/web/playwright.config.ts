import { defineConfig, devices } from '@playwright/test';

// MVP-011: on failure Playwright writes an accessibility snapshot of the page, including input
// values such as a one-time code, to error-context.md and the HTML report. This switch turns that
// snapshot off; the error message and step list remain.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1';

/**
 * End-to-end smoke tests against the running Docker Compose stack (see README).
 * They never start services themselves.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    // MVP-011: traces, screenshots and videos would capture one-time codes and the authenticator
    // setup page. They stay off; failures are diagnosed from the step list and assertion messages.
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    serviceWorkers: 'block',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});

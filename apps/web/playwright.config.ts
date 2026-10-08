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
  // OPS-001: the deployed test environment has its own configuration (playwright.hr-dev.config.ts).
  testIgnore: ['**/hr-dev/**'],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  retries: process.env.CI ? 1 : 0,
  // DEVX-001A (A75-4): two workers for the `features` project; `identity` runs alone (see
  // e2e/run-suite.sh). Four only after three clean browser-stage runs.
  workers: Number(process.env.E2E_WORKERS ?? 2),
  // DEVX-001A (A75-3): the private SSO state directory of a plain `playwright test` run.
  globalSetup: './e2e/global-setup.ts',
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  // DEVX-001A (A75-3): in CI Playwright would otherwise embed the commit author and the whole
  // pull-request diff in the HTML report artifact.
  captureGitInfo: { commit: false, diff: false },
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173',
    // MVP-011: traces, screenshots and videos would capture one-time codes and the authenticator
    // setup page. They stay off; failures are diagnosed from the step list and assertion messages.
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    serviceWorkers: 'block',
  },
  // DEVX-001A (D2, A75-4). Only *.spec.ts files are browser tests (e2e/tooling.test.ts is a plain
  // Node test of this tooling and must never be loaded here). Every test belongs to exactly one of `features` and `identity`:
  //  - auth-setup: one real password-and-MFA sign-in per privileged seed role, saving only the
  //    Keycloak SSO session (e2e/sso-state.ts);
  //  - features: reuses those sessions through the normal Authorization Code + PKCE flow, in
  //    parallel, and may never wait for a TOTP period;
  //  - identity (tests tagged @identity): real interactive sign-ins, MFA, wrong and replayed codes,
  //    membership changes and every sign-out, with nothing else running. e2e/run-suite.sh always
  //    runs both and fails if either fails.
  projects: [
    {
      name: 'auth-setup',
      testMatch: /auth\.setup\.ts$/u,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'features',
      testMatch: /\.spec\.ts$/u,
      dependencies: ['auth-setup'],
      grepInvert: /@identity/u,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'identity',
      testMatch: /\.spec\.ts$/u,
      grep: /@identity/u,
      // A plain `playwright test` must never run identity next to parallel feature workers (its
      // sign-outs end their SSO sessions), so it waits for features. run-suite.sh (E2E_SUITE=1)
      // runs it as a separate invocation that always happens, whatever features did.
      dependencies: process.env.E2E_SUITE === '1' ? [] : ['features'],
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});

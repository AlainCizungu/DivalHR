# Browser tests (Playwright)

These tests run against the running Compose stack (`infrastructure/docker/compose.yaml`). They never start services themselves. The deployed test environment has its own suite in `hr-dev/` and its own configuration (`playwright.hr-dev.config.ts`).

## Running

| Command                                                                  | What it runs                                                                                                                                                                     |
| ------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `pnpm --filter @divalhr/web run e2e` (also `e2e:suite`, `make e2e-host`) | The whole suite as CI and `verify-on-host.sh` run it: `auth-setup` + `features` on 2 workers, then `identity` alone, then the credential-hygiene check. Fails if any part fails. |
| `pnpm --filter @divalhr/web run e2e:serial`                              | The same, on one worker: the single-worker diagnostic run.                                                                                                                       |
| `E2E_SPECS="e2e/teams.spec.ts" pnpm --filter @divalhr/web run e2e:suite` | Only these spec files, still split between `features` and `identity` (the `changed` profile uses this).                                                                          |
| `pnpm --filter @divalhr/web run e2e:raw`                                 | Diagnostics only: plain Playwright. `identity` waits for `features` and is skipped when `features` fails, and no hygiene check runs, so a green result proves nothing.           |

Options of `e2e/run-suite.sh`: `--workers N` (1 to 4, default 2 or `E2E_WORKERS`), `--timings FILE` (appends `TIME e2e-features|e2e-identity|e2e-hygiene|e2e-total <s>s exit=<n>` lines), `--log-dir DIR` (an extra directory for the hygiene check). Four workers only after three clean two-worker runs (A75-4).

Every canonical entry point (`e2e`, `e2e:suite`, `e2e:serial`, `make e2e`, `make e2e-host`) runs `e2e/run-suite.sh`; `e2e/tooling.test.ts` fails if one drifts back to plain Playwright (R76-1).

## Projects (DEVX-001A)

- **`auth-setup`** (`auth.setup.ts`): one real password-and-TOTP sign-in for each privileged seed role (`dev-admin-a`, `dev-admin-b`, `dev-platform-admin`). It saves only that role's Keycloak SSO cookies.
- **`features`**: every test not tagged `@identity`, in parallel. `signIn()` for a privileged seed user adds the saved SSO cookies to the test's fresh context and clicks **Sign in**: the normal Authorization Code + PKCE flow runs, Keycloak recognises the session and returns a code, and the app keeps its tokens in memory. Other users (the employee, invited users) sign in interactively, one sign-in per user at a time across workers: concurrent password sign-ins of one user make Keycloak refuse some of them. The employee's SSO session is not reused because the web client's default ACR is the MFA level, and Keycloak then refuses a password-level session. `freshCode()` throws here instead of waiting for a new TOTP period.
- **`identity`**: tests tagged `@identity` (`{ tag: '@identity' }` on the test or describe). It holds everything that must exercise the real identity flow or would disturb shared sessions: interactive privileged sign-ins, MFA enrolment, wrong and replayed codes, membership changes and every sign-out. It runs on one worker after `features`, and always runs unless the stack is down.

A new test that signs out, changes a seed user's credentials or membership, or needs a fresh TOTP code belongs in `identity`.

## Credential rules (A75-3)

The saved SSO state is a session credential.

- It lives only in a per-run directory created with `mktemp` outside the repository and every report or artifact path (directory `0700`, files `0600`), whose path reaches the workers through `DIVALHR_E2E_SSO_DIR`.
- `sso-state.ts` refuses to save anything but the identity host's `KEYCLOAK_IDENTITY`/`KEYCLOAK_SESSION` cookies: no browser storage, no other host, nothing token-shaped outside the identity cookie.
- The Playwright global teardown and the runner's `trap` remove it on success, failure or interruption. It is never printed, uploaded or copied into evidence.
- `hygiene.ts` then scans the run's generated output only (`test-results/`, `playwright-report/`, an optional log directory; never the repository, whose development fixtures are public by design) for a JWT, a session-cookie value, a seed password or TOTP seed, a TOTP code of the run's time window (in Playwright output), and a leftover state directory. It names the file and the kind of finding, never the value.
- Passwords and one-time codes are typed with `typeSecret()` (`typeCode()` for codes), and mailed links that carry a credential (invitations, Keycloak setup links) are opened with `openSecretLink()`. `fill`, `type` and `goto` show their argument in step titles, which reach the HTML report. The hygiene check decodes the archive embedded in the HTML report, so a value that slips into a step title fails the run.

Traces, screenshots, videos and failure page snapshots stay off (MVP-011).

## Tooling tests

`node --test e2e/tooling.test.ts` (part of `pnpm --filter @divalhr/web test`) covers the state validation, file modes, cleanup after a passing and a failing run, the hygiene findings and the runner's exit aggregation, without a browser or a stack.

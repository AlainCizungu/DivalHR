import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Response } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { retryOn429 } from './rate-limit.ts';
import { readState, ssoDir } from './sso-state.ts';

// DEVELOPMENT-ONLY seed users, TOTP seeds and the TOTP generator live in credentials.ts (shared
// with the hygiene check, which runs outside Playwright).
export { TENANT_A, TOTP_SEEDS, USERS, totp } from './credentials.ts';
import { TOTP_SEEDS, USERS, totp } from './credentials.ts';
export const CORE_API = process.env.E2E_CORE_API_URL ?? 'http://localhost:8080/api/v1';
/** DEVX-001A: the identity provider whose SSO session the `features` project reuses. */
export const IDENTITY_HOST = new URL(process.env.E2E_IDENTITY_URL ?? 'http://localhost:8180')
  .hostname;

/** DEVX-001A (D2, A75-4): the parallel project that reuses the SSO sessions of `auth-setup`. */
export const FEATURES_PROJECT = 'features';

/**
 * DEVX-001A (D2): the privileged seed users whose session `auth-setup` prepares. The employee is not
 * one of them: with the web client's default ACR (MFA level), Keycloak refuses to reuse an
 * employee's password-level SSO session ("Invalid username or password"), so employees always sign
 * in interactively, one sign-in per user at a time (withSignInLock).
 */
export const SSO_SEED_USERS = [USERS.adminA, USERS.adminB, USERS.platformAdmin];

/**
 * DEVX-001A (A75-4): in `features`, one interactive password sign-in per user at a time across
 * workers. Concurrent password sign-ins of one user make Keycloak answer "Invalid username or
 * password" to some of them (reproduced with three parallel sign-ins of dev-employee-a). The lock is
 * a directory in the run's private SSO directory, which the runner removes.
 */
async function withSignInLock<T>(page: Page, username: string, action: () => Promise<T>) {
  if (currentProject() !== FEATURES_PROJECT) return action();
  const lock = join(ssoDir(), `signin-${username.replace(/[^a-z0-9-]/giu, '_')}.lock`);
  const deadline = Date.now() + 45_000;
  for (;;) {
    try {
      mkdirSync(lock);
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
    if (Date.now() > deadline) throw new Error(`another worker kept the sign-in of ${username}`);
    await page.waitForTimeout(250);
  }
  try {
    return await action();
  } finally {
    rmSync(lock, { recursive: true, force: true });
  }
}

function currentProject(): string | undefined {
  try {
    return test.info().project.name;
  } catch {
    return undefined; // outside a test (for example a hygiene script)
  }
}
const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));

// DEVELOPMENT-ONLY mail catcher of the compose stack (UI and API bound to 127.0.0.1).
const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://127.0.0.1:8025';

interface MailSummary {
  ID: string;
  Subject: string;
}

/**
 * Accepts an invitation through the public endpoint. The anonymous endpoints allow 10 requests per
 * client per minute (application.yaml) and every spec of a run shares one client address, so a 429
 * is honoured by the shared rule (rate-limit.ts): wait Retry-After (at most 61 s) and try again, at
 * most three attempts. The limit is exercised, never raised.
 */
export async function acceptInvitation(page: Page, token: string): Promise<number> {
  const accepted = await retryOn429(
    () => page.request.post(`${CORE_API}/public/invitations/accept`, { data: { token } }),
    (milliseconds) => page.waitForTimeout(milliseconds),
  );
  return accepted.status();
}

/**
 * R120-3: opens an anonymous invitation link through the real page (openSecretLink: the token
 * stays in the fragment and the page removes it) and returns the page's inspection response. The
 * inspection shares the anonymous endpoints' per-client limit with every other worker, so a 429
 * follows the same rule as the acceptance (rate-limit.ts): wait the bounded Retry-After, then the
 * page's own « Réessayer », which inspects again with the same token; at most three attempts. Any
 * other status (200 for a usable link, 404 for an unusable one) is returned for the caller's
 * assertions. French UI journeys only.
 */
export async function openInvitationInUi(page: Page, link: string): Promise<Response> {
  return retryOn429(
    async (attempt) => {
      const inspected = page.waitForResponse(
        (response) =>
          response.url().endsWith('/public/invitations/inspect') &&
          response.request().method() === 'POST',
      );
      if (attempt === 1) await openSecretLink(page, link);
      else await page.getByRole('button', { name: 'Réessayer', exact: true }).click();
      return inspected;
    },
    (milliseconds) => page.waitForTimeout(milliseconds),
  );
}

/**
 * R120-2: accepts the invitation shown on the page through the real UI. The first attempt clicks
 * « Accepter l’invitation »; after a 429 (the page then offers « Réessayer », which repeats the
 * acceptance with the same token) the same rule as {@link acceptInvitation} applies: wait the
 * bounded Retry-After and click « Réessayer », at most three attempts. Returns the last acceptance
 * response for the caller's assertions. French UI journeys only.
 */
export async function acceptInvitationInUi(page: Page): Promise<Response> {
  return retryOn429(
    async (attempt) => {
      const accepted = page.waitForResponse(
        (response) =>
          response.url().endsWith('/public/invitations/accept') &&
          response.request().method() === 'POST',
      );
      await page
        .getByRole('button', {
          name: attempt === 1 ? 'Accepter l’invitation' : 'Réessayer',
          exact: true,
        })
        .click();
      return accepted;
    },
    (milliseconds) => page.waitForTimeout(milliseconds),
  );
}

/** Waits for a message to the address whose subject matches, and returns its text body. */
export async function mailTo(address: string, subject: RegExp): Promise<string> {
  let found: MailSummary | undefined;
  await expect
    .poll(
      async () => {
        const response = await fetch(
          `${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${address}"`)}`,
        );
        const { messages } = (await response.json()) as { messages: MailSummary[] };
        found = messages.find((message) => subject.test(message.Subject));
        return found !== undefined;
      },
      { timeout: 30_000 },
    )
    .toBe(true);
  const message = (await (await fetch(`${MAILPIT}/api/v1/message/${found?.ID ?? ''}`)).json()) as {
    Text: string;
  };
  return message.Text;
}

export function sql(query: string): string {
  return execFileSync(
    'docker',
    [
      'compose',
      '-f',
      'infrastructure/docker/compose.yaml',
      '--env-file',
      '.env.example',
      'exec',
      '-T',
      'postgres',
      'psql',
      '-U',
      'postgres',
      '-d',
      'divalhr',
      '-tAc',
      query,
    ],
    { cwd: REPO_ROOT, encoding: 'utf8' },
  ).trim();
}

// Codes are single use (otpPolicyCodeReusable=false). Keycloak accepts the previous, current and
// next period (look-ahead 1), so each user can sign in up to three times per period. Playwright
// restarts its worker after a failure, so the periods already used are kept in a file: user names
// and period numbers only, never a code or a secret.
const USED_COUNTERS = join(tmpdir(), 'divalhr-e2e-totp-periods.json');

function usedCounters(): Record<string, number[]> {
  try {
    return JSON.parse(readFileSync(USED_COUNTERS, 'utf8')) as Record<string, number[]>;
  } catch {
    return {};
  }
}

/** A code this user has not used yet; waits for the next period when all three are spent. */
export async function freshCode(page: Page, user: string, secret: string): Promise<string> {
  for (;;) {
    const now = Math.floor(Date.now() / 1000);
    const current = Math.floor(now / 30);
    const all = usedCounters();
    const used = (all[user] ?? []).filter((c) => c >= current - 1);
    // The previous period is safe only well inside the current one.
    const candidates = now % 30 < 25 ? [current, current + 1, current - 1] : [current, current + 1];
    const counter = candidates.find((c) => !used.includes(c));
    if (counter !== undefined) {
      writeFileSync(USED_COUNTERS, JSON.stringify({ ...all, [user]: [...used, counter] }));
      return totp(secret, counter);
    }
    // DEVX-001A (D2): feature tests reuse SSO sessions and never wait for a TOTP period. Only the
    // auth-setup and identity projects may wait, so a slow feature test fails loudly instead.
    if (currentProject() === FEATURES_PROJECT) {
      throw new Error(`a feature test must not wait for a new TOTP period (${user})`);
    }
    await page.waitForTimeout((30 - (now % 30) + 1) * 1000);
  }
}

/**
 * MVP-070E: the spec files that write as the same privileged seed user share that subject's
 * per-minute write limits (`work-schedule-write`: 20 per subject per minute, never raised), so two
 * parallel feature workers running two of them at once make a correct 429 land on a user action
 * of the other file. Such a file takes this lock in `beforeAll` and releases it in `afterAll`
 * (tenant B's administrator: time-exports, timesheet-reopening; tenant A's: attendance-corrections,
 * time-approvals, timesheets, work-schedules):
 * the files run one after the other, and the next one starts only when the previous holder's
 * writes are a full minute old. The lock is a directory in the temporary directory (as the TOTP
 * period file above); a holder that died frees it. Nothing waits on the rate limit itself.
 */
export async function subjectLock(user: string): Promise<() => void> {
  const dir = join(tmpdir(), `divalhr-e2e-subject-${user}.lock`);
  const released = `${dir}.released`;
  const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
  const alive = (pid: number) => {
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  };
  const deadline = Date.now() + 15 * 60_000;
  for (;;) {
    try {
      mkdirSync(dir);
      writeFileSync(join(dir, 'pid'), String(process.pid));
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
    let holder = Number.NaN;
    try {
      holder = Number(readFileSync(join(dir, 'pid'), 'utf8'));
    } catch {
      // The holder has not written its pid yet.
    }
    if (Number.isInteger(holder) && !alive(holder)) {
      rmSync(dir, { recursive: true, force: true });
      continue;
    }
    if (Date.now() > deadline) throw new Error(`the ${user} subject lock was never released`);
    await sleep(1000);
  }
  let last = 0;
  try {
    last = Number(readFileSync(released, 'utf8'));
  } catch {
    // No earlier holder in this run.
  }
  const wait = last + 61_000 - Date.now();
  if (wait > 0) await sleep(wait);
  return () => {
    writeFileSync(released, String(Date.now()));
    rmSync(dir, { recursive: true, force: true });
  };
}

/**
 * Types a secret (a password or a one-time code) without passing it through a Playwright step
 * argument: `fill` and `type` show their value in step titles, which reach the HTML report.
 */
export async function typeSecret(page: Page, selector: string, secret: string) {
  await page.locator(selector).evaluate((input, value) => {
    const field = input as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input', { bubbles: true }));
    field.dispatchEvent(new Event('change', { bubbles: true }));
  }, secret);
}

/** Types a one-time code (MVP-011); see typeSecret. */
export const typeCode = typeSecret;

/**
 * DEVX-001A (A75-3): opens a link carrying a credential (an invitation or a Keycloak action token)
 * without showing it in a navigation step of the report, and waits for the page to load.
 */
export async function openSecretLink(page: Page, link: string) {
  const navigated = page.waitForEvent('framenavigated', (frame) => frame === page.mainFrame());
  await page.evaluate((url) => {
    window.location.assign(url);
  }, link);
  await navigated;
  await page.waitForLoadState();
}

/**
 * Completes the authenticator step of a privileged user (MVP-011). With no known secret (an
 * employee, or a user supplied through E2E_* variables) it does nothing.
 */
export async function enterCode(
  page: Page,
  username: string,
  secret: string | undefined = TOTP_SEEDS[username],
) {
  if (secret === undefined) return;
  // Should a code still be refused (for example after the counters file was removed), one more
  // unused code is tried, more than a second later so the quick-login lockout does not trigger.
  for (let attempt = 0; ; attempt++) {
    await page.locator('#otp').waitFor();
    await typeCode(page, '#otp', await freshCode(page, username, secret));
    await page.locator('#kc-login').click();
    const outcome = async () => {
      if (new URL(page.url()).pathname === '/') return 'accepted';
      try {
        return (await page.locator('#input-error-otp').count()) > 0 ? 'refused' : 'pending';
      } catch {
        return 'pending'; // the page is navigating
      }
    };
    await expect.poll(outcome, { timeout: 15_000 }).not.toBe('pending');
    if ((await outcome()) === 'accepted' || attempt === 1) break;
    await page.waitForTimeout(1500);
  }
}

/** Signs in through the real PKCE flow in French and returns the in-memory bearer the app sends. */
export async function signInFr(page: Page, user: readonly [string, string]) {
  return signIn(page, user, 'fr');
}

/**
 * Signs in through the real PKCE flow and returns the in-memory bearer the app sends. Privileged
 * seed users also enter a code from their development-only authenticator.
 *
 * DEVX-001A (D2): in the `features` project a seed user's Keycloak SSO session, saved once by
 * `auth-setup` after a real sign-in (password and, for privileged roles, MFA), is placed in the
 * browser context first. The flow is unchanged (Sign in, Authorization Code + PKCE, callback, in-memory tokens);
 * Keycloak only skips its login form because the session already exists (with MFA assurance for
 * privileged roles).
 */
export async function signIn(
  page: Page,
  [username, password]: readonly [string, string],
  locale: 'fr' | 'en',
  secret: string | undefined = TOTP_SEEDS[username],
) {
  let sso: Awaited<ReturnType<typeof readState>>;
  if (
    currentProject() === FEATURES_PROJECT &&
    secret === TOTP_SEEDS[username] &&
    SSO_SEED_USERS.some(([user, seedPassword]) => user === username && seedPassword === password)
  ) {
    sso = readState(ssoDir(), username, IDENTITY_HOST);
    if (!sso) throw new Error(`no SSO session was prepared for ${username} (auth-setup)`);
    await page.context().addCookies(sso);
  }
  let bearer: string | undefined;
  page.on('request', (request) => {
    const header = request.headers().authorization;
    if (header && request.url().startsWith(CORE_API)) bearer = header;
  });
  await page.goto('/');
  await page
    .getByRole('button', { name: locale === 'fr' ? 'Français' : 'English', exact: true })
    .click();
  const signInButton = page
    .getByRole('main')
    .getByRole('button', { name: locale === 'fr' ? 'Se connecter' : 'Sign in' });
  if (sso) {
    await signInButton.click();
    await page.waitForURL((url) => url.pathname === '/');
  } else {
    await withSignInLock(page, username, async () => {
      await signInButton.click();
      await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
      await page.locator('#username').fill(username);
      await typeSecret(page, '#password', password);
      await page.locator('#kc-login').click();
      if (secret !== undefined) await enterCode(page, username, secret);
      await page.waitForURL((url) => url.pathname === '/');
    });
  }
  await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
  await expect.poll(() => bearer).toBeDefined();
  return () => bearer ?? '';
}

/**
 * UI-001: the primary navigation landmark (sidebar on desktop) in either language. Home now has
 * cards with the same names as navigation links, so navigation steps are scoped to the landmark.
 */
export function primaryNav(page: Page) {
  return page.getByRole('navigation', { name: /^(Main navigation|Navigation principale)$/ });
}

export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

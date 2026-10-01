import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { createHmac } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// DEVELOPMENT-ONLY seed users and fixture tenants (infrastructure/docker/keycloak/README.md).
// The fixture organizations exist only because the stack runs with DIVALHR_ENVIRONMENT=development.
export const TENANT_A = '00000000-0000-4000-8000-00000000000a';
export const USERS = {
  adminA: ['dev-admin-a', 'dev-only-Admin-A-2026'],
  adminB: ['dev-admin-b', 'dev-only-Admin-B-2026'],
  employeeA: ['dev-employee-a', 'dev-only-Employee-A-2026'],
  platformAdmin: ['dev-platform-admin', 'dev-only-Platform-2026'],
} as const;
/**
 * DEVELOPMENT-ONLY published TOTP seeds of the privileged seed users (MVP-011, realm import).
 * Codes are computed in memory and typed through the DOM, never through a logged step argument.
 */
const TOTP_SEEDS: Readonly<Partial<Record<string, string>>> = {
  'dev-admin-a': 'dev-only-totp-admin-a-2026',
  'dev-admin-b': 'dev-only-totp-admin-b-2026',
  'dev-platform-admin': 'dev-only-totp-platform-2026',
};
export const CORE_API = process.env.E2E_CORE_API_URL ?? 'http://localhost:8080/api/v1';
const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));

// DEVELOPMENT-ONLY mail catcher of the compose stack (UI and API bound to 127.0.0.1).
const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://127.0.0.1:8025';

interface MailSummary {
  ID: string;
  Subject: string;
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

/** RFC 6238 TOTP (HMAC-SHA1, 6 digits, 30 s) over the raw secret, as Keycloak configures it. */
export function totp(secret: string, counter: number): string {
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const hash = createHmac('sha1', Buffer.from(secret, 'utf8')).update(message).digest();
  const offset = (hash[hash.length - 1] ?? 0) & 0x0f;
  return String((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).padStart(6, '0');
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
    await page.waitForTimeout((30 - (now % 30) + 1) * 1000);
  }
}

/** Types a one-time code without passing it through a Playwright step argument. */
export async function typeCode(page: Page, selector: string, code: string) {
  await page.locator(selector).evaluate((input, value) => {
    const field = input as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input', { bubbles: true }));
  }, code);
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
 */
export async function signIn(
  page: Page,
  [username, password]: readonly [string, string],
  locale: 'fr' | 'en',
  secret: string | undefined = TOTP_SEEDS[username],
) {
  let bearer: string | undefined;
  page.on('request', (request) => {
    const header = request.headers().authorization;
    if (header && request.url().startsWith(CORE_API)) bearer = header;
  });
  await page.goto('/');
  await page
    .getByRole('button', { name: locale === 'fr' ? 'Français' : 'English', exact: true })
    .click();
  await page
    .getByRole('main')
    .getByRole('button', { name: locale === 'fr' ? 'Se connecter' : 'Sign in' })
    .click();
  await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  if (secret !== undefined) await enterCode(page, username, secret);
  await page.waitForURL((url) => url.pathname === '/');
  await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
  await expect.poll(() => bearer).toBeDefined();
  return () => bearer ?? '';
}

export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

import { execFile } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { promisify } from 'node:util';
import { expect, type APIResponse, type Page } from '@playwright/test';
import { enterCode, freshCode, typeSecret } from '../support';

/** LAUNCH-002: origins of the production acceptance journeys, from the environment only. */
export const PUBLIC_URL = (process.env.PROD_PUBLIC_URL ?? '').replace(/\/$/, '');
export const APP_URL = (process.env.PROD_APP_URL ?? '').replace(/\/$/, '');
export const WWW_URL = (process.env.PROD_WWW_URL ?? '').replace(/\/$/, '');
export const REALM = 'divalhr-production';
export const SIGN_IN_PATH = `/identity/realms/${REALM}/protocol/openid-connect/auth`;
export const CORE_API = `${APP_URL}/api/v1`;

/** Fails (never skips) when an origin is missing or is not production. */
export function requireOrigins(): void {
  if (!PUBLIC_URL.startsWith('https://') || !APP_URL.startsWith('https://')) {
    throw new Error('PROD_PUBLIC_URL and PROD_APP_URL (https origins) are required');
  }
  if (!WWW_URL.startsWith('https://')) {
    throw new Error('PROD_WWW_URL (the www origin whose redirect is checked) is required');
  }
  if (/hr-dev|dival\.ai/i.test(`${PUBLIC_URL}${APP_URL}${WWW_URL}`)) {
    throw new Error('the production acceptance never runs against the test environment');
  }
}

/** Everything the synthetic-tenant journey needs from the operator; a missing value fails. */
export interface SyntheticEnv {
  adminUser: string;
  adminPassword: string;
  adminTotpSecret: string;
  mailbox: string;
  mailCmd: string;
}

export function requireSyntheticEnv(): SyntheticEnv {
  const read = (name: string) => {
    const value = process.env[name] ?? '';
    if (!value) throw new Error(`${name} is required (the synthetic tenant journey never skips)`);
    return value;
  };
  const env = {
    adminUser: read('PROD_PLATFORM_ADMIN_USER'),
    adminPassword: read('PROD_PLATFORM_ADMIN_PASSWORD'),
    adminTotpSecret: read('PROD_PLATFORM_ADMIN_TOTP_SECRET'),
    mailbox: read('PROD_ACCEPTANCE_MAILBOX'),
    mailCmd: read('PROD_ACCEPTANCE_MAIL_CMD'),
  };
  if (!/^[^@\s+]+@[^@\s]+\.[^@\s]+$/u.test(env.mailbox)) {
    throw new Error('PROD_ACCEPTANCE_MAILBOX must be the operator-controlled mailbox address');
  }
  return env;
}

/** A synthetic identity: a plus-address of the operator's own mailbox, unique to this run. */
export function addressFor(mailbox: string, tag: string): string {
  const [local, domain] = mailbox.split('@') as [string, string];
  return `${local}+${tag}@${domain}`;
}

export const randomPassword = () => `Pw-${randomBytes(18).toString('base64url')}-aA1!`;

const run = promisify(execFile);

/**
 * The text of the newest message to `recipient` whose subject matches, read through the operator's
 * own command (PROD_ACCEPTANCE_MAIL_CMD recipient subject-pattern), which waits for delivery. The
 * command is the operator's mailbox access: nothing here holds a mailbox credential.
 */
export async function mailTo(env: SyntheticEnv, recipient: string, subject: RegExp) {
  const { stdout } = await run(env.mailCmd, [recipient, subject.source], {
    timeout: 300_000,
    maxBuffer: 2_000_000,
  });
  return stdout;
}

/** The real PKCE sign-in on the application origin; returns the in-memory bearer. */
export async function signIn(
  page: Page,
  [username, password]: readonly [string, string],
  locale: 'fr' | 'en',
  secret: string | undefined,
) {
  let bearer: string | undefined;
  page.on('request', (request) => {
    const header = request.headers().authorization;
    if (header && request.url().startsWith(CORE_API)) bearer = header;
  });
  await page.goto(`${APP_URL}/`);
  await page
    .getByRole('button', { name: locale === 'fr' ? 'Français' : 'English', exact: true })
    .click();
  await page
    .getByRole('main')
    .getByRole('button', { name: locale === 'fr' ? 'Se connecter' : 'Sign in' })
    .click();
  await page.waitForURL((url) => url.pathname.startsWith(SIGN_IN_PATH));
  await page.locator('#username').fill(username);
  await typeSecret(page, '#password', password);
  await page.locator('#kc-login').click();
  if (secret !== undefined) await enterCode(page, username, secret);
  await page.waitForURL((url) => url.origin === new URL(APP_URL).origin && url.pathname === '/');
  await expect(page.getByTestId('session-tenant')).toHaveText(/^[0-9a-f-]{36}$/);
  await expect.poll(() => bearer).toBeDefined();
  return () => bearer ?? '';
}

/**
 * Keycloak's one-time action link: sets the password and, for a privileged role, the
 * authenticator. Returns the authenticator secret (never logged or asserted on screen).
 */
export async function completeAccountSetup(
  page: Page,
  user: string,
  password: string,
  withAuthenticator: boolean,
): Promise<string | undefined> {
  let secret: string | undefined;
  const proceed = page.locator('a[href*="login-actions"], #kc-info-message a').first();
  if ((await page.locator('#password-new, #totp').count()) === 0 && (await proceed.count()) > 0) {
    await proceed.click();
  }
  for (let step = 0; step < (withAuthenticator ? 2 : 1); step++) {
    await expect(page.locator('#password-new').or(page.locator('#totp'))).toBeVisible();
    if ((await page.locator('#password-new').count()) > 0) {
      await typeSecret(page, '#password-new', password);
      await typeSecret(page, '#password-confirm', password);
      await page.locator('[type="submit"]').first().click();
    } else {
      secret = await page.locator('#totpSecret').inputValue();
      await typeSecret(page, '#totp', await freshCode(page, user, secret));
      await page.locator('#userLabel').fill('Acceptance authenticator');
      await page.locator('#saveTOTPBtn').click();
    }
  }
  await expect(page.locator('#password-new').or(page.locator('#totp'))).toHaveCount(0);
  return secret;
}

/**
 * A command over the API with the signed-in bearer. A 429 waits the bounded Retry-After and is
 * sent again with the same Idempotency-Key.
 */
export async function post(
  page: Page,
  bearer: () => string,
  path: string,
  key: string | null,
  data?: object,
): Promise<APIResponse> {
  let response: APIResponse | undefined;
  for (let attempt = 0; attempt < 4; attempt++) {
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: { Authorization: bearer(), ...(key === null ? {} : { 'Idempotency-Key': key }) },
      ...(data === undefined ? {} : { data }),
    });
    if (response.status() !== 429) return response;
    await page.waitForTimeout(
      1000 * Math.min(60, Number(response.headers()['retry-after'] ?? '20') || 20),
    );
  }
  return response as APIResponse;
}

export async function get(page: Page, bearer: () => string, path: string): Promise<APIResponse> {
  return page.request.get(`${CORE_API}${path}`, { headers: { Authorization: bearer() } });
}

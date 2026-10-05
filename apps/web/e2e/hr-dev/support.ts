import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { createHash, createHmac, randomBytes } from 'node:crypto';

/**
 * OPS-001: helpers for the test-environment acceptance suite. Temporary users are synthetic
 * (`*@hr-dev.example.test`), created through the operator site's admin API and always deleted.
 * No secret, code or token is ever logged or passed as a step argument.
 */

export const ORIGIN = (process.env.HR_DEV_BASE_URL ?? '').replace(/\/$/, '');
export const ADMIN_URL = (process.env.HR_DEV_ADMIN_URL ?? '').replace(/\/$/, '');
export const REALM = 'divalhr-test';
export const ISSUER = `${ORIGIN}/identity/realms/${REALM}`;
export const MAIL_API = (process.env.HR_DEV_MAIL_API ?? `${ADMIN_URL}/mail/api/v1`).replace(
  /\/$/,
  '',
);

export function requireAdmin() {
  const user = process.env.HR_DEV_KC_ADMIN_USER;
  const password = process.env.HR_DEV_KC_ADMIN_PASSWORD;
  if (!ADMIN_URL || !user || !password) {
    throw new Error(
      'HR_DEV_ADMIN_URL, HR_DEV_KC_ADMIN_USER and HR_DEV_KC_ADMIN_PASSWORD are required',
    );
  }
  return { user, password };
}

async function adminToken(request: APIRequestContext): Promise<string> {
  const { user, password } = requireAdmin();
  const response = await request.post(
    `${ADMIN_URL}/identity/realms/master/protocol/openid-connect/token`,
    {
      form: { grant_type: 'password', client_id: 'admin-cli', username: user, password },
    },
  );
  expect(response.status(), 'master-realm administrator token').toBe(200);
  return ((await response.json()) as { access_token: string }).access_token;
}

export interface TempUser {
  id: string;
  username: string;
  password: string;
}

/** A synthetic realm user. Roles are realm roles; the password is random and never printed. */
export async function createTempUser(
  request: APIRequestContext,
  options: { roles?: string[]; withPassword?: boolean } = {},
): Promise<TempUser> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const suffix = randomBytes(5).toString('hex');
  const username = `hrdev-temp-${suffix}@hr-dev.example.test`;
  const password = `Tmp-${randomBytes(12).toString('base64url')}-9a`;
  const created = await request.post(`${ADMIN_URL}/identity/admin/realms/${REALM}/users`, {
    headers,
    data: {
      username,
      email: username,
      emailVerified: true,
      enabled: true,
      firstName: 'Synthetic',
      lastName: `Tester ${suffix}`,
      ...(options.withPassword === false
        ? {}
        : { credentials: [{ type: 'password', value: password, temporary: false }] }),
    },
  });
  expect(created.status(), 'temporary user created').toBe(201);
  const id = (created.headers()['location'] ?? '').split('/').pop() ?? '';
  for (const role of options.roles ?? []) {
    const roleResponse = await request.get(
      `${ADMIN_URL}/identity/admin/realms/${REALM}/roles/${role}`,
      {
        headers,
      },
    );
    expect(roleResponse.status()).toBe(200);
    const mapped = await request.post(
      `${ADMIN_URL}/identity/admin/realms/${REALM}/users/${id}/role-mappings/realm`,
      { headers, data: [await roleResponse.json()] },
    );
    expect(mapped.status()).toBe(204);
  }
  return { id, username, password };
}

export async function deleteTempUser(request: APIRequestContext, user: TempUser | undefined) {
  if (!user) return;
  const token = await adminToken(request);
  await request.delete(`${ADMIN_URL}/identity/admin/realms/${REALM}/users/${user.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** Sends Keycloak's own "update your account" e-mail; it lands in the stack's Mailpit. */
export async function sendSetupEmail(
  request: APIRequestContext,
  user: TempUser,
  actions: string[],
) {
  const token = await adminToken(request);
  const response = await request.put(
    `${ADMIN_URL}/identity/admin/realms/${REALM}/users/${user.id}/execute-actions-email?client_id=divalhr-web&redirect_uri=${encodeURIComponent(`${ORIGIN}/auth/callback`)}`,
    { headers: { Authorization: `Bearer ${token}` }, data: actions },
  );
  expect(response.status(), 'setup e-mail sent').toBe(204);
}

/** The first link to the public identity origin in the newest message to this address. */
export async function setupLinkFor(request: APIRequestContext, address: string): Promise<string> {
  let link = '';
  await expect
    .poll(
      async () => {
        const search = await request.get(
          `${MAIL_API}/search?query=${encodeURIComponent(`to:"${address}"`)}`,
        );
        if (!search.ok()) return false;
        const { messages } = (await search.json()) as { messages: { ID: string }[] };
        if (messages.length === 0) return false;
        const message = await request.get(`${MAIL_API}/message/${messages[0]?.ID ?? ''}`);
        const { Text } = (await message.json()) as { Text: string };
        link = (Text.match(new RegExp(`${ISSUER}/login-actions/action-token\\?[^\\s]+`)) ?? [
          '',
        ])[0];
        return link !== '';
      },
      { timeout: 60_000 },
    )
    .toBe(true);
  return link;
}

/** RFC 4648 base32, as Keycloak shows the manual authenticator key. */
function base32(text: string): Buffer {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = text.replace(/[\s=]/g, '').toUpperCase();
  let bits = '';
  for (const char of clean) {
    const index = alphabet.indexOf(char);
    if (index < 0) throw new Error('invalid authenticator key');
    bits += index.toString(2).padStart(5, '0');
  }
  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) bytes.push(parseInt(bits.slice(i, i + 8), 2));
  return Buffer.from(bytes);
}

/** RFC 6238 code (HMAC-SHA1, 6 digits, 30 s) for a base32 key; offset selects the period. */
export function totpFromKey(key: string, offset = 0): string {
  const counter = Math.floor(Date.now() / 30_000) + offset;
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const hash = createHmac('sha1', base32(key)).update(message).digest();
  const start = (hash[hash.length - 1] ?? 0) & 0x0f;
  return String((hash.readUInt32BE(start) & 0x7fffffff) % 1_000_000).padStart(6, '0');
}

/** Types a value without passing it through a Playwright step argument. */
export async function typeSecret(page: Page, selector: string, value: string) {
  await page.locator(selector).evaluate((input, text) => {
    const field = input as HTMLInputElement;
    field.value = text;
    field.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
}

/** Authorization Code + PKCE at protocol level; returns the code captured at the callback. */
export async function authorize(
  page: Page,
  user: TempUser,
): Promise<{ code: string; verifier: string }> {
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const state = randomBytes(8).toString('hex');
  // A route handler is not called for redirect targets, so the callback navigation itself cannot
  // be intercepted. Instead the web app's scripts are held back while the callback page loads, so
  // the application never redeems the code this test is about to exchange itself.
  const holdAppScripts = `${ORIGIN}/assets/**`;
  await page.route(holdAppScripts, (route) => route.abort());
  const query = new URLSearchParams({
    client_id: 'divalhr-web',
    response_type: 'code',
    scope: 'openid',
    redirect_uri: `${ORIGIN}/auth/callback`,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  });
  await page.goto(`${ISSUER}/protocol/openid-connect/auth?${query.toString()}`);
  await page.locator('#username').fill(user.username);
  await typeSecret(page, '#password', user.password);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === ORIGIN && url.pathname === '/auth/callback');
  const callback = page.url();
  await page.unroute(holdAppScripts);
  const params = new URL(callback).searchParams;
  expect(params.get('state')).toBe(state);
  expect(params.get('iss')).toBe(ISSUER);
  return { code: params.get('code') ?? '', verifier };
}

/** A same-origin form POST from the page (as the web app would), returning status and JSON. */
export async function postForm(page: Page, path: string, form: Record<string, string>) {
  return page.evaluate(
    async ([url, body]) => {
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(body).toString(),
      });
      const text = await response.text();
      let json: Record<string, unknown> = {};
      try {
        json = JSON.parse(text) as Record<string, unknown>;
      } catch {
        json = {};
      }
      return { status: response.status, json };
    },
    [`${ORIGIN}${path}`, form] as const,
  );
}

/** A same-origin GET from the page (the browser resolves the test host), returning status and JSON. */
export async function getJson(page: Page, url: string) {
  return page.evaluate(async (target) => {
    const response = await fetch(target);
    let json: Record<string, unknown> = {};
    try {
      json = (await response.json()) as Record<string, unknown>;
    } catch {
      json = {};
    }
    return { status: response.status, json };
  }, url);
}

/** A same-origin API GET from the page with an optional bearer token; the token is never logged. */
export async function getJsonWithToken(page: Page, path: string, token: string) {
  return page.evaluate(
    async ([url, bearer]) => {
      const response = await fetch(url, {
        headers: bearer ? { Authorization: `Bearer ${bearer}` } : {},
      });
      let json: Record<string, unknown> = {};
      try {
        json = (await response.json()) as Record<string, unknown>;
      } catch {
        json = {};
      }
      return { status: response.status, json };
    },
    [`${ORIGIN}${path}`, token] as const,
  );
}

/** A string field of a JSON response, or the fallback. */
export function text(value: unknown, fallback: string): string {
  return typeof value === 'string' ? value : fallback;
}

export function claims(token: string): Record<string, unknown> {
  const payload = token.split('.')[1] ?? '';
  return JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as Record<string, unknown>;
}

/** Records every request host; the public pages must stay on the application's origin. */
export function trackHosts(page: Page) {
  const hosts = new Set<string>();
  page.on('request', (request) => hosts.add(new URL(request.url()).host));
  return hosts;
}

/** Records responses from the identity asset paths, with their status. */
export function trackIdentityAssets(page: Page) {
  const assets: { url: string; status: number }[] = [];
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname;
    if (/^\/identity\/(resources|js)\//.test(path))
      assets.push({ url: path, status: response.status() });
  });
  return assets;
}

import { expect, test } from '@playwright/test';
import {
  authorize,
  claims,
  text,
  createTempUser,
  deleteTempUser,
  getJson,
  ISSUER,
  ORIGIN,
  postForm,
  trackIdentityAssets,
  type TempUser,
} from './support';

/**
 * OPS-001 A65-2 items 1 and 2: the public OIDC model of the test environment. Discovery and keys,
 * then Authorization Code + PKCE, callback, refresh and logout for a temporary synthetic user,
 * and the web app's own sign-in and sign-out round trip.
 */

let user: TempUser | undefined;
test.beforeAll(async ({ request }) => {
  user = await createTempUser(request);
});
test.afterAll(async ({ request }) => {
  await deleteTempUser(request, user);
});

test('discovery names the public issuer and the keys are served', async ({ page }) => {
  await page.goto('/');
  const discovery = await getJson(page, `${ISSUER}/.well-known/openid-configuration`);
  expect(discovery.status).toBe(200);
  expect(discovery.json.issuer).toBe(ISSUER);
  expect(discovery.json.authorization_endpoint).toBe(`${ISSUER}/protocol/openid-connect/auth`);
  expect(discovery.json.token_endpoint).toBe(`${ISSUER}/protocol/openid-connect/token`);
  expect(discovery.json.end_session_endpoint).toBe(`${ISSUER}/protocol/openid-connect/logout`);
  expect(discovery.json.jwks_uri).toBe(`${ISSUER}/protocol/openid-connect/certs`);
  const keys = await getJson(page, String(discovery.json.jwks_uri));
  expect(keys.status).toBe(200);
  const list = (keys.json.keys ?? []) as { use?: string; alg?: string }[];
  expect(list.some((key) => key.use === 'sig' && key.alg === 'RS256')).toBe(true);
});

test('authorization, callback, refresh and logout', async ({ page }) => {
  if (!user) throw new Error('no user');
  const assets = trackIdentityAssets(page);
  await page.goto('/');
  const { code, verifier } = await authorize(page, user);
  expect(assets.length).toBeGreaterThan(0);
  expect(assets.filter((asset) => asset.status !== 200)).toEqual([]);

  await page.goto('/');
  const exchange = await postForm(
    page,
    `/identity/realms/divalhr-test/protocol/openid-connect/token`,
    {
      grant_type: 'authorization_code',
      client_id: 'divalhr-web',
      code,
      code_verifier: verifier,
      redirect_uri: `${ORIGIN}/auth/callback`,
    },
  );
  expect(exchange.status).toBe(200);
  const accessToken = text(exchange.json.access_token, '');
  const refreshToken = text(exchange.json.refresh_token, '');
  expect(claims(accessToken).iss).toBe(ISSUER);
  expect(claims(text(exchange.json.id_token, '')).iss).toBe(ISSUER);
  expect(refreshToken).not.toBe('');

  const refreshed = await postForm(
    page,
    `/identity/realms/divalhr-test/protocol/openid-connect/token`,
    {
      grant_type: 'refresh_token',
      client_id: 'divalhr-web',
      refresh_token: refreshToken,
    },
  );
  expect(refreshed.status).toBe(200);
  expect(claims(text(refreshed.json.access_token, '')).iss).toBe(ISSUER);

  const logout = await postForm(
    page,
    `/identity/realms/divalhr-test/protocol/openid-connect/logout`,
    {
      client_id: 'divalhr-web',
      refresh_token: text(refreshed.json.refresh_token, refreshToken),
    },
  );
  expect(logout.status).toBe(204);
  const afterLogout = await postForm(
    page,
    `/identity/realms/divalhr-test/protocol/openid-connect/token`,
    {
      grant_type: 'refresh_token',
      client_id: 'divalhr-web',
      refresh_token: text(refreshed.json.refresh_token, refreshToken),
    },
  );
  expect(afterLogout.status).toBe(400);

  // The same code cannot be used twice. (Done last: Keycloak treats a replayed code as a theft
  // signal and revokes what that code issued, which is the intended behaviour.)
  const replay = await postForm(
    page,
    `/identity/realms/divalhr-test/protocol/openid-connect/token`,
    {
      grant_type: 'authorization_code',
      client_id: 'divalhr-web',
      code,
      code_verifier: verifier,
      redirect_uri: `${ORIGIN}/auth/callback`,
    },
  );
  expect(replay.status).toBe(400);
});

test('the web app signs in through the landing page and signs out back to it', async ({ page }) => {
  if (!user) throw new Error('no user');
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto('/');
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await page.getByRole('banner').getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(/\/identity\/realms\/divalhr-test\/protocol\/openid-connect\/auth/);
  expect(new URL(page.url()).searchParams.get('redirect_uri')).toBe(`${ORIGIN}/auth/callback`);
  expect(new URL(page.url()).searchParams.get('code_challenge_method')).toBe('S256');
  await page.locator('#username').fill(user.username);
  await page.locator('#password').evaluate((input, value) => {
    (input as HTMLInputElement).value = value;
  }, user.password);
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === ORIGIN && url.pathname === '/');
  // A synthetic identity without a DivalHR membership: the sign-in itself succeeds (Core accepted
  // the token), and the application reports that the account is not linked to an organisation.
  await expect(page.getByTestId('landing')).toHaveCount(0);
  await expect(page.getByText('Your account is not linked to an organization.')).toBeVisible();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await page.waitForURL((url) => url.origin === ORIGIN && url.pathname === '/');
  await expect(page.getByTestId('landing')).toBeVisible();
});

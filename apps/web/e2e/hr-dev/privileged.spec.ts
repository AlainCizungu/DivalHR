import { expect, test } from '@playwright/test';
import {
  createTempUser,
  deleteTempUser,
  ISSUER,
  ORIGIN,
  sendSetupEmail,
  setupLinkFor,
  totpFromKey,
  trackIdentityAssets,
  typeSecret,
  type TempUser,
} from './support';

/**
 * OPS-001 A65-2 items 5 and 7: a privileged first sign-in. A temporary synthetic platform
 * administrator receives Keycloak's setup e-mail (caught by the stack's Mailpit), sets a password
 * and an authenticator through the required-action pages, then signs in with password and code.
 * Every identity asset those pages load must answer 200. The user is deleted afterwards.
 */

let user: TempUser | undefined;
test.afterAll(async ({ request }) => {
  await deleteTempUser(request, user);
});

test('privileged first sign-in: setup e-mail, password, authenticator, then MFA sign-in', async ({
  page,
  request,
}) => {
  user = await createTempUser(request, { roles: ['platform-admin'], withPassword: false });
  const assets = trackIdentityAssets(page);
  await sendSetupEmail(request, user, ['UPDATE_PASSWORD', 'CONFIGURE_TOTP']);
  const link = await setupLinkFor(request, user.username);
  expect(link.startsWith(`${ISSUER}/login-actions/`)).toBe(true);

  // Navigate without passing the one-time link through a step or an error message.
  await page.goto('/');
  await page.evaluate((url) => {
    window.location.href = url;
  }, link);
  await page.waitForURL((url) => url.pathname.includes('/login-actions/'));
  // Keycloak may first ask to confirm the actions.
  const proceed = page.locator('a[href*="login-actions"]').first();
  if (
    (await page.locator('#kc-totp-secret-qr-code, #password-new').count()) === 0 &&
    (await proceed.count()) > 0
  ) {
    await proceed.click();
  }
  const newPassword = `Setup-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}Z9`;
  const steps = async () => {
    if (await page.locator('#password-new').count()) {
      await typeSecret(page, '#password-new', newPassword);
      await typeSecret(page, '#password-confirm', newPassword);
      await page.locator('input[type="submit"], button[type="submit"]').first().click();
      return true;
    }
    if (await page.locator('#totp').count()) {
      const manual = page.locator('a[href*="mode=manual"]');
      if (await manual.count()) await manual.click();
      const key = (await page.locator('#kc-totp-secret-key').textContent()) ?? '';
      expect(key.replace(/\s/g, '').length).toBeGreaterThan(15);
      await typeSecret(page, '#totp', totpFromKey(key));
      if (await page.locator('#userLabel').count())
        await page.locator('#userLabel').fill('hr-dev acceptance');
      await page.locator('input[type="submit"], button[type="submit"]').first().click();
      user = { ...(user as TempUser), password: newPassword, key } as TempUser & { key: string };
      return true;
    }
    return false;
  };
  for (let i = 0; i < 4; i++) {
    await page.waitForLoadState('domcontentloaded');
    if (!(await steps())) break;
  }
  const key = (user as TempUser & { key?: string }).key ?? '';
  expect(key, 'the authenticator was configured').not.toBe('');
  expect(assets.length).toBeGreaterThan(0);
  expect(assets.filter((asset) => asset.status !== 200)).toEqual([]);

  // Sign in from the landing page with password and a fresh code (codes are single use).
  await page.context().clearCookies();
  await page.goto('/');
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await page.getByRole('banner').getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(/\/identity\/realms\/divalhr-test\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill(user.username);
  await typeSecret(page, '#password', newPassword);
  await page.locator('#kc-login').click();
  await page.locator('#otp').waitFor();
  await typeSecret(page, '#otp', totpFromKey(key, 1));
  await page.locator('#kc-login').click();
  await page.waitForURL((url) => url.origin === ORIGIN && url.pathname === '/');
  await expect(page.getByTestId('landing')).toHaveCount(0);
  expect(assets.filter((asset) => asset.status !== 200)).toEqual([]);
});

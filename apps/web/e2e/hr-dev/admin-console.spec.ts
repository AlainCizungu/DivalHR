import { expect, test } from '@playwright/test';
import { ADMIN_URL, requireAdmin, typeSecret } from './support';

/**
 * OPS-001 A65-2 item 4: the owner's Keycloak admin console at the separate admin URL
 * (KC_HOSTNAME_ADMIN), reached on the instance's loopback or through the SSH tunnel. The public
 * origin refuses the same paths (http-checks.sh, item 6).
 */
test('the admin console works at the private admin URL', async ({ page }) => {
  const { user, password } = requireAdmin();
  await page.goto(`${ADMIN_URL}/identity/admin/master/console/`);
  await page.locator('#username').waitFor();
  expect(new URL(page.url()).origin).toBe(new URL(ADMIN_URL).origin);
  await page.locator('#username').fill(user);
  await typeSecret(page, '#password', password);
  await page.locator('#kc-login').click();
  await page.waitForURL(/\/identity\/admin\/master\/console\//);
  expect(new URL(page.url()).origin).toBe(new URL(ADMIN_URL).origin);
  // The console application itself loaded, signed in (its navigation is only shown then).
  await expect(page.getByRole('link', { name: 'Manage realms' })).toBeVisible({ timeout: 30_000 });
  // It lists the realms through the admin API on the same private origin, test realm included.
  await page.getByRole('link', { name: 'Manage realms' }).click();
  await expect(page.getByRole('main').getByText('divalhr-test').first()).toBeVisible({
    timeout: 30_000,
  });
});

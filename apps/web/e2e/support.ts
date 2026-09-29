import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
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
export const CORE_API = process.env.E2E_CORE_API_URL ?? 'http://localhost:8080/api/v1';
const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));

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

/** Signs in through the real PKCE flow in French and returns the in-memory bearer the app sends. */
export async function signInFr(page: Page, [username, password]: readonly [string, string]) {
  let bearer: string | undefined;
  page.on('request', (request) => {
    const header = request.headers().authorization;
    if (header && request.url().startsWith(CORE_API)) bearer = header;
  });
  await page.goto('/');
  await page.getByRole('button', { name: 'Français', exact: true }).click();
  await page.getByRole('main').getByRole('button', { name: 'Se connecter' }).click();
  await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
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

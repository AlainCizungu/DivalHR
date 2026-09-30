import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { enterCode } from './support';

// DEVELOPMENT-ONLY seed credentials (infrastructure/docker/keycloak/README.md).
const USERNAME = process.env.E2E_PLATFORM_ADMIN_USERNAME ?? 'dev-platform-admin';
const PASSWORD = process.env.E2E_PLATFORM_ADMIN_PASSWORD ?? 'dev-only-Platform-2026';
const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));

/** Reads from the Compose PostgreSQL (approved persistence assertion for MVP-001). */
function sql(query: string): string {
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

test('platform administrator creates an organization in French and it is persisted', async ({
  page,
}) => {
  const name = `Hôpital Général E2E ${Date.now().toString()}`;

  await page.goto('/');
  await page.getByRole('button', { name: 'Français', exact: true }).click();
  await page.getByRole('main').getByRole('button', { name: 'Se connecter' }).click();
  await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill(USERNAME);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();
  await enterCode(page, USERNAME);
  await page.waitForURL((url) => url.pathname === '/');

  await page.getByRole('link', { name: 'Créer une organisation' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Créer une organisation');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
        .analyze()
    ).violations.map((v) => v.id),
  ).toEqual([]);

  // Client validation in French before anything is sent.
  await page.getByRole('button', { name: 'Créer l’organisation' }).click();
  await expect(page.getByRole('alert')).toContainText('Saisissez le nom de l’organisation.');

  await page.getByLabel('Nom de l’organisation').fill(name);
  await page.getByLabel('Fuseau horaire').selectOption('Africa/Lubumbashi');
  await page.getByRole('checkbox', { name: 'Dollar américain (USD)' }).check();
  await page.getByRole('button', { name: 'Créer l’organisation' }).click();

  const success = page.getByTestId('organization-created');
  await expect(success.getByRole('heading', { level: 1 })).toHaveText('Organisation créée');
  await expect(success.getByTestId('created-name')).toHaveText(name);
  await expect(success).toContainText('Lubumbashi (heure d’Afrique centrale, UTC+2)');
  await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
  const id = (await success.getByTestId('created-id').innerText()).trim();
  expect(id).toMatch(/^[0-9a-f-]{36}$/);

  const escapedName = name.replaceAll("'", "''");
  expect(
    sql(
      `SELECT country_code || '|' || default_locale || '|' || timezone || '|' || status FROM tenant.organization WHERE id = '${id}' AND name = '${escapedName}'`,
    ),
  ).toBe('CD|fr|Africa/Lubumbashi|ACTIVE');
  expect(
    sql(
      `SELECT string_agg(currency_code, ',' ORDER BY currency_code) FROM tenant.organization_currency WHERE organization_id = '${id}'`,
    ),
  ).toBe('CDF,USD');
  expect(sql(`SELECT count(*) FROM platform.audit_event WHERE resource_id = '${id}'`)).toBe('1');
  expect(
    sql(`SELECT envelope->>'eventType' FROM platform.outbox_event WHERE tenant_id = '${id}'`),
  ).toBe('tenant.organization-created.v1');
});

test('non-platform administrators do not see or reach organization creation', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'English', exact: true }).click();
  await page.getByRole('main').getByRole('button', { name: 'Sign in' }).click();
  await page.waitForURL(/\/realms\/divalhr-dev\/protocol\/openid-connect\/auth/);
  const username = process.env.E2E_USERNAME ?? 'dev-admin-a';
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(process.env.E2E_PASSWORD ?? 'dev-only-Admin-A-2026');
  await page.locator('#kc-login').click();
  await enterCode(page, username);
  await page.waitForURL((url) => url.pathname === '/');
  await expect(page.getByTestId('session-roles')).toHaveText('Organization administrator');
  await expect(page.getByRole('link', { name: 'Create organization' })).toHaveCount(0);
});

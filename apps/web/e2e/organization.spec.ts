import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { primaryNav, signIn, USERS } from './support';

// DEVELOPMENT-ONLY seed credentials (infrastructure/docker/keycloak/README.md).
const USERNAME = process.env.E2E_PLATFORM_ADMIN_USERNAME ?? USERS.platformAdmin[0];
const PASSWORD = process.env.E2E_PLATFORM_ADMIN_PASSWORD ?? USERS.platformAdmin[1];
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

  // DEVX-001A: the shared sign-in (in `features`, the SSO session of auth-setup).
  await signIn(page, [USERNAME, PASSWORD], 'fr');

  await primaryNav(page).getByRole('link', { name: 'Créer une organisation' }).click();
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
  await signIn(
    page,
    [process.env.E2E_USERNAME ?? USERS.adminA[0], process.env.E2E_PASSWORD ?? USERS.adminA[1]],
    'en',
  );
  await expect(page.getByTestId('session-roles')).toHaveText('Organization administrator');
  await expect(page.getByRole('link', { name: 'Create organization' })).toHaveCount(0);
});

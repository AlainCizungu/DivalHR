import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

// DEVELOPMENT-ONLY seed users and fixture tenants (infrastructure/docker/keycloak/README.md).
// The fixture organizations exist only because the stack runs with DIVALHR_ENVIRONMENT=development.
const TENANT_A = '00000000-0000-4000-8000-00000000000a';
const USERS = {
  adminA: ['dev-admin-a', 'dev-only-Admin-A-2026'],
  adminB: ['dev-admin-b', 'dev-only-Admin-B-2026'],
  employeeA: ['dev-employee-a', 'dev-only-Employee-A-2026'],
  platformAdmin: ['dev-platform-admin', 'dev-only-Platform-2026'],
} as const;
const CORE_API = process.env.E2E_CORE_API_URL ?? 'http://localhost:8080/api/v1';
const REPO_ROOT = fileURLToPath(new URL('../../../', import.meta.url));

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

/** Signs in through the real PKCE flow in French and returns the in-memory bearer the app sends. */
async function signInFr(page: Page, [username, password]: readonly [string, string]) {
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

async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

test.describe.serial('MVP-002 organizational hierarchy', () => {
  const stamp = Date.now().toString(36).toUpperCase();
  const leCode = `E2E-${stamp}`;
  const leName = `Société Générale de l’Équateur ${stamp}`;
  const siteCode = `GOMBE-${stamp}`.slice(0, 20);
  const siteName = `Siège de la Gombe ${stamp}`;
  let legalEntityId = '';

  test('tenant administrator A creates a legal entity and a site in French', async ({ page }) => {
    await signInFr(page, USERS.adminA);
    await page.getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Structure organisationnelle');
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expectAccessible(page);

    const leForm = page.getByTestId('legal-entity-form');
    await leForm.getByRole('button', { name: 'Créer l’entité juridique' }).click();
    await expect(leForm.getByTestId('form-summary')).toBeFocused();
    await expect(leForm.getByTestId('form-summary')).toContainText('Saisissez un code.');

    await leForm.getByLabel('Code').fill(leCode.toLowerCase());
    await leForm.getByLabel('Raison sociale').fill(leName);
    await leForm.getByLabel('Date de début').fill('2026-01-01');
    await leForm.getByRole('button', { name: 'Créer l’entité juridique' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Entité juridique ${leName} (${leCode}) créée.`,
    );

    const select = page.getByRole('button', { name: `Voir les sites de ${leName} (${leCode})` });
    await select.click();
    await expect(
      page.getByRole('heading', { level: 2, name: `Sites de ${leName} (${leCode})` }),
    ).toBeFocused();
    const siteForm = page.getByTestId('site-form');
    await siteForm.getByLabel('Code').fill(siteCode);
    await siteForm.getByLabel('Nom du site').fill(siteName);
    await siteForm.getByLabel('Fuseau horaire').selectOption('Africa/Kinshasa');
    // A closed site under an open-ended entity starting on its first day is contained.
    await siteForm.getByLabel('Date de fin (facultatif)').fill('2026-12-31');
    await siteForm.getByRole('button', { name: 'Créer le site' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(`Site ${siteName} (${siteCode}) créé.`);
    await expect(page.getByTestId('site-list')).toContainText(siteName);
    await expectAccessible(page);

    legalEntityId = sql(
      `SELECT id FROM tenant.legal_entity WHERE code = '${leCode}' AND tenant_id = '${TENANT_A}'`,
    );
    expect(legalEntityId).toMatch(/^[0-9a-f-]{36}$/);
    expect(
      sql(
        `SELECT tenant_id || '|' || legal_entity_id || '|' || timezone || '|' || effective_to FROM tenant.site WHERE code = '${siteCode}'`,
      ),
    ).toBe(`${TENANT_A}|${legalEntityId}|Africa/Kinshasa|2026-12-31`);
    expect(
      sql(
        `SELECT string_agg(action, ',' ORDER BY action) FROM platform.audit_event WHERE tenant_id = '${TENANT_A}' AND (resource_id = '${legalEntityId}' OR resource_id = (SELECT id FROM tenant.site WHERE code = '${siteCode}'))`,
      ),
    ).toBe('legal-entity.create,site.create');
    expect(
      sql(
        `SELECT count(*) FROM platform.outbox_event WHERE tenant_id = '${TENANT_A}' AND envelope->>'eventType' IN ('tenant.legal-entity-created.v1', 'tenant.site-created.v1') AND (envelope->'data'->>'legalEntityId') = '${legalEntityId}'`,
      ),
    ).toBe('2');
  });

  test('tenant administrator B sees neither record and cannot reach them', async ({
    page,
    request,
  }) => {
    const bearer = await signInFr(page, USERS.adminB);
    await page.getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Structure organisationnelle');
    await expect(page.getByText(/Chargement/)).toHaveCount(0);
    await expect(page.getByRole('main')).not.toContainText(leCode);

    const sites = await request.get(`${CORE_API}/sites?legalEntityId=${legalEntityId}`, {
      headers: { Authorization: bearer() },
    });
    expect(sites.status()).toBe(404);
    expect(((await sites.json()) as { code: string }).code).toBe('LEGAL_ENTITY_NOT_FOUND');
    const list = await request.get(`${CORE_API}/legal-entities?limit=200`, {
      headers: { Authorization: bearer() },
    });
    expect(list.status()).toBe(200);
    expect(JSON.stringify(await list.json())).not.toContain(leCode);
  });

  for (const [label, user] of [
    ['employee', USERS.employeeA],
    ['platform administrator', USERS.platformAdmin],
  ] as const) {
    test(`${label} is denied the hierarchy in the UI and the API`, async ({ page, request }) => {
      const bearer = await signInFr(page, user);
      await expect(page.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);
      // Client-side navigation keeps the in-memory session (a reload would sign the user out).
      await page.evaluate(() => {
        window.history.pushState({}, '', '/admin/hierarchy');
        window.dispatchEvent(new PopStateEvent('popstate'));
      });
      await expect(page.getByTestId('not-authorized')).toHaveText(
        'Seuls les administrateurs de l’organisation peuvent gérer la structure organisationnelle.',
      );
      const response = await request.get(`${CORE_API}/legal-entities`, {
        headers: { Authorization: bearer() },
      });
      expect(response.status()).toBe(403);
    });
  }
});

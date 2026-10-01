import { expect, test } from '@playwright/test';
import { CORE_API, USERS, expectAccessible, signIn, sql } from './support';

// MVP-012A (Issue #37): the tenant membership is the Core-side authority for tenant access. With
// the real Keycloak, a privileged user whose token carries tenant-admin (password and TOTP
// completed) is refused by the Core when no matching membership exists, and the web app shows no
// administrator access. Seed memberships come from the development-only seeder (fixed realm IDs).

const ADMIN_B_SUBJECT = '00000000-0000-4000-8000-0000000000b1';

test.describe.serial('MVP-012A: membership authority with the real identity provider', () => {
  // The seed row of dev-admin-b, kept in memory to be restored (the lookup is not personal data
  // in this form, and nothing here is logged).
  let saved: string | undefined;

  test.afterAll(() => {
    if (saved !== undefined) {
      const [id, lookup, createdAt] = saved.split('|');
      sql(
        `INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup, created_at)
         VALUES ('${id}', '00000000-0000-4000-8000-00000000000b', '${ADMIN_B_SUBJECT}', 'tenant-admin',
                 decode('${lookup}', 'hex'), '${createdAt}') ON CONFLICT DO NOTHING`,
      );
    }
  });

  test('a seeded tenant administrator keeps working behind the gate', async ({ page }) => {
    const bearer = await signIn(page, USERS.adminA, 'en');
    await expect(page.getByTestId('session-roles')).toHaveText('Organization administrator');
    const response = await page.request.get(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer() },
    });
    expect(response.status()).toBe(200);
  });

  test('without a membership, password and TOTP are not enough: the Core and the UI refuse', async ({
    page,
  }) => {
    saved = sql(
      `SELECT id || '|' || encode(email_lookup, 'hex') || '|' || created_at
       FROM identity.tenant_membership WHERE subject = '${ADMIN_B_SUBJECT}'`,
    );
    expect(saved, 'seed membership of dev-admin-b').toMatch(/^[0-9a-f-]{36}\|[0-9a-f]{64}\|/u);
    sql(`DELETE FROM identity.tenant_membership WHERE subject = '${ADMIN_B_SUBJECT}'`);

    // The token still carries tenant-admin with MFA (Keycloak asked for the TOTP code).
    const bearer = await signIn(page, USERS.adminB, 'fr');
    await expect(page.getByTestId('session-roles')).toHaveText('Aucun rôle attribué');
    await expect(page.getByRole('link', { name: 'Utilisateurs et invitations' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);

    const denied = await page.request.get(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer() },
    });
    expect(denied.status()).toBe(403);
    const problem = (await denied.json()) as { code: string; params: object };
    expect(problem.code).toBe('ACCESS_DENIED');
    expect(problem.params).toEqual({});
    expect(JSON.stringify(problem)).not.toContain(ADMIN_B_SUBJECT);

    // Client-side navigation keeps the in-memory session (a reload would sign the user out).
    await page.evaluate(() => {
      window.history.pushState({}, '', '/admin/users');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(
      page.getByText(
        'Seuls les administrateurs de l’organisation peuvent inviter des utilisateurs.',
      ),
    ).toBeVisible();
    await expectAccessible(page);
  });

  test('restoring the membership restores access at the next request', async ({ page }) => {
    const [id, lookup, createdAt] = (saved ?? '').split('|');
    sql(
      `INSERT INTO identity.tenant_membership (id, tenant_id, subject, role, email_lookup, created_at)
       VALUES ('${id}', '00000000-0000-4000-8000-00000000000b', '${ADMIN_B_SUBJECT}', 'tenant-admin',
               decode('${lookup}', 'hex'), '${createdAt}')`,
    );
    saved = undefined;
    const bearer = await signIn(page, USERS.adminB, 'en');
    await expect(page.getByTestId('session-roles')).toHaveText('Organization administrator');
    const response = await page.request.get(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer() },
    });
    expect(response.status()).toBe(200);
    await expectAccessible(page);
  });

  test('a platform administrator keeps the platform role and gains no tenant role', async ({
    page,
  }) => {
    await signIn(page, USERS.platformAdmin, 'en');
    await expect(page.getByTestId('session-roles')).toHaveText('Platform administrator');
    await expect(page.getByRole('link', { name: 'Create organization' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Users and invitations' })).toHaveCount(0);
  });
});

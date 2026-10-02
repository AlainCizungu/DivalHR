import { expect, test } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, signIn, sql } from './support';

// MVP-012B (Issue #37): the read-only access review with the real identity provider. The seed
// tenant administrator steps up with TOTP, reviews by role, legal entity, site and exact address,
// sees inherited scopes, uses the page by keyboard only, and every disclosure is audited.

function audits(): number {
  return Number(
    sql(
      `SELECT count(*) FROM platform.audit_event WHERE action = 'access-review.read'
       AND tenant_id = '${TENANT_A}'`,
    ),
  );
}

test.describe.serial('MVP-012B: access review', () => {
  test('a tenant administrator reviews access in French by role, unit and exact address', async ({
    page,
  }) => {
    const before = audits();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    // A legal entity and a site to filter by (created through the API with the same session).
    const stamp = Date.now().toString(36).toUpperCase().slice(-6);
    const entity = await page.request.post(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-review-le-${stamp}-0001` },
      data: {
        code: `RV-${stamp}`,
        name: `Revue Entité ${stamp}`,
        countryCode: 'CD',
        effectiveFrom: '2026-01-01',
      },
    });
    expect(entity.status()).toBe(201);
    const entityId = ((await entity.json()) as { id: string }).id;
    const site = await page.request.post(`${CORE_API}/sites`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-review-st-${stamp}-0001` },
      data: {
        legalEntityId: entityId,
        code: `RS-${stamp}`,
        name: `Revue Site ${stamp}`,
        timezone: 'Africa/Lubumbashi',
        effectiveFrom: '2026-01-01',
      },
    });
    expect(site.status()).toBe(201);

    await page.getByRole('link', { name: 'Revue des accès' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Revue des accès');
    const table = page.getByTestId('review-table');
    await expect(table).toBeVisible();
    await expect(table.getByRole('row').nth(1)).toBeVisible();
    await expect(page.getByTestId('time-zone-note')).toHaveText(
      'Dates affichées dans le fuseau horaire de votre appareil.',
    );
    await expect(table).toContainText('Toute l’organisation');
    // Seed memberships have no recorded address.
    await expect(table).toContainText('Adresse non enregistrée');
    await expectAccessible(page);

    // Role filter, from the summary.
    await page.getByRole('button', { name: 'Afficher : Administrateur de l’organisation' }).click();
    await expect(page.getByTestId('review-row').first()).toContainText(
      'Administrateur de l’organisation',
    );

    // Legal entity, then site: every member is shown as inherited.
    await page.getByTestId('review-filters').getByLabel('Rôle', { exact: true }).selectOption('');
    await page
      .getByTestId('review-filters')
      .getByLabel('Entité juridique', { exact: true })
      .selectOption(entityId);
    await page.getByRole('button', { name: 'Appliquer les filtres' }).click();
    await expect(page.getByTestId('review-row').first()).toContainText(
      'Accès hérité de l’organisation (couvre cette entité juridique)',
    );
    const siteId = ((await site.json()) as { id: string }).id;
    await page
      .getByTestId('review-filters')
      .getByLabel('Site', { exact: true })
      .selectOption(siteId);
    await page.getByRole('button', { name: 'Appliquer les filtres' }).click();
    await expect(page.getByTestId('review-row').first()).toContainText(
      'Accès hérité de l’organisation (couvre ce site)',
    );

    // Exact address: the seed administrator's own address matches its membership, which is shown
    // without an address (never the submitted value).
    await page.getByLabel('Adresse e-mail exacte').fill('DEV-ADMIN-A@example.com');
    await page.getByRole('button', { name: 'Rechercher' }).click();
    await expect(page.getByTestId('review-row')).toHaveCount(1);
    await expect(page.getByTestId('review-row')).toContainText('Adresse non enregistrée');
    await expect(page.getByTestId('review-table')).not.toContainText('dev-admin-a@');
    expect(page.url()).not.toContain('dev-admin-a');
    await page.getByLabel('Adresse e-mail exacte').fill('personne.inconnue@example.com');
    await page.getByRole('button', { name: 'Rechercher' }).click();
    await expect(page.getByTestId('review-empty')).toHaveText(
      'Aucun membre actif ne correspond à cette adresse.',
    );
    await expectAccessible(page);

    // Every successful disclosure was durably audited.
    expect(audits()).toBeGreaterThanOrEqual(before + 6);
  });

  test('the review is usable by keyboard only, in English', async ({ page }) => {
    await signIn(page, USERS.adminA, 'en');
    await page.getByRole('link', { name: 'Access review' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Access review');
    await expect(page.getByTestId('review-table')).toBeVisible();
    await page.getByTestId('review-filters').getByLabel('Role', { exact: true }).focus();
    await page.keyboard.press('ArrowDown');
    await page.getByRole('button', { name: 'Apply filters' }).focus();
    await page.keyboard.press('Enter');
    await expect(
      page.getByTestId('review-table').or(page.getByTestId('review-empty')),
    ).toBeVisible();
    await page.getByLabel('Exact email address').focus();
    await page.keyboard.type('not-an-address');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alert')).toHaveText(
      'Enter a valid email address, for example name@example.cd.',
    );
    await expectAccessible(page);
  });

  test('an employee has no access review', async ({ page }) => {
    const bearer = await signIn(page, USERS.employeeA, 'en', undefined);
    await expect(page.getByRole('link', { name: 'Access review' })).toHaveCount(0);
    const response = await page.request.get(`${CORE_API}/access-review/summary`, {
      headers: { Authorization: bearer() },
    });
    expect(response.status()).toBe(403);
    expect(response.headers()['cache-control']).toBe('private, no-store');
  });
});

import { expect, test } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, signInFr, sql } from './support';

test.describe.serial('MVP-002 Increment 2: departments and cost centers', () => {
  const stamp = Date.now().toString(36).toUpperCase();
  const leCode = `E2U-${stamp}`;
  const leName = `Compagnie Équatoriale des Opérations ${stamp}`;
  const siteCode = `KAT-${stamp}`.slice(0, 20);
  const siteName = `Site de Katanga ${stamp}`;
  const deptCode = `RH-${stamp}`.slice(0, 20);
  const deptName = `Ressources humaines et relations sociales ${stamp}`;
  const ccCode = `CC-${stamp}`.slice(0, 20);
  const ccName = `Centre de coût Opérations minières ${stamp}`;
  let siteId = '';

  test('tenant administrator A creates a department and a cost center in French', async ({
    page,
  }) => {
    await signInFr(page, USERS.adminA);
    await page.getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');

    const leForm = page.getByTestId('legal-entity-form');
    await leForm.getByLabel('Code').fill(leCode);
    await leForm.getByLabel('Raison sociale').fill(leName);
    await leForm.getByLabel('Date de début').fill('2026-01-01');
    await leForm.getByRole('button', { name: 'Créer l’entité juridique' }).click();
    await page.getByRole('button', { name: `Voir les sites de ${leName} (${leCode})` }).click();

    const siteForm = page.getByTestId('site-form');
    await siteForm.getByLabel('Code').fill(siteCode);
    await siteForm.getByLabel('Nom du site').fill(siteName);
    await siteForm.getByRole('button', { name: 'Créer le site' }).click();
    await expect(page.getByTestId('site-list')).toContainText(siteName);

    await page
      .getByRole('button', {
        name: `Voir les départements et centres de coût de ${siteName} (${siteCode})`,
      })
      .click();
    await expect(
      page.getByRole('heading', {
        level: 2,
        name: `Départements et centres de coût de ${siteName} (${siteCode})`,
      }),
    ).toBeFocused();
    await expect(page.getByTestId('department-list-empty')).toBeVisible();
    await expect(page.getByTestId('costCenter-list-empty')).toBeVisible();
    await expectAccessible(page);

    const deptForm = page.getByTestId('department-form');
    await deptForm.getByRole('button', { name: 'Créer le département' }).click();
    await expect(deptForm.getByTestId('form-summary')).toBeFocused();
    await deptForm.getByLabel('Code').fill(deptCode.toLowerCase());
    await deptForm.getByLabel('Nom du département').fill(deptName);
    await deptForm.getByRole('button', { name: 'Créer le département' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Département ${deptName} (${deptCode}) créé.`,
    );
    await expect(page.getByTestId('department-list')).toContainText(deptName);
    await expect(
      page.getByTestId('department-list').locator('[tabindex="-1"]', { hasText: deptName }),
    ).toBeFocused();

    const ccForm = page.getByTestId('costCenter-form');
    await ccForm.getByLabel('Code').fill(ccCode);
    await ccForm.getByLabel('Nom du centre de coût').fill(ccName);
    await ccForm.getByLabel('Date de fin (facultatif)').fill('2026-12-31');
    await ccForm.getByRole('button', { name: 'Créer le centre de coût' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Centre de coût ${ccName} (${ccCode}) créé.`,
    );
    await expect(page.getByTestId('costCenter-list')).toContainText(ccName);
    await expect(
      page.getByTestId('costCenter-list').locator('[tabindex="-1"]', { hasText: ccName }),
    ).toBeFocused();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');
    await expectAccessible(page);

    siteId = sql(
      `SELECT id FROM tenant.site WHERE code = '${siteCode}' AND tenant_id = '${TENANT_A}'`,
    );
    expect(siteId).toMatch(/^[0-9a-f-]{36}$/);
    expect(
      sql(
        `SELECT tenant_id || '|' || site_id || '|' || effective_from || '|' || coalesce(effective_to::text, 'open') FROM tenant.department WHERE code = '${deptCode}'`,
      ),
    ).toBe(`${TENANT_A}|${siteId}|2026-01-01|open`);
    expect(
      sql(
        `SELECT tenant_id || '|' || site_id || '|' || effective_from || '|' || effective_to FROM tenant.cost_center WHERE code = '${ccCode}'`,
      ),
    ).toBe(`${TENANT_A}|${siteId}|2026-01-01|2026-12-31`);
    for (const [table, action, eventType] of [
      ['tenant.department', 'department.create', 'tenant.department-created.v1'],
      ['tenant.cost_center', 'cost-center.create', 'tenant.cost-center-created.v1'],
    ] as const) {
      const code = table === 'tenant.department' ? deptCode : ccCode;
      const id = sql(
        `SELECT id FROM ${table} WHERE code = '${code}' AND tenant_id = '${TENANT_A}'`,
      );
      expect(
        sql(
          `SELECT count(*) || '|' || min(action) FROM platform.audit_event WHERE resource_id = '${id}'`,
        ),
      ).toBe(`1|${action}`);
      expect(
        sql(
          `SELECT count(*) || '|' || min(event_type) FROM platform.outbox_event WHERE envelope ->> 'subject' = '${id}'`,
        ),
      ).toBe(`1|${eventType}`);
    }
  });

  test('tenant administrator B sees neither item and cannot reach them', async ({
    page,
    request,
  }) => {
    const bearer = await signInFr(page, USERS.adminB);
    for (const path of ['departments', 'cost-centers']) {
      const response = await request.get(`${CORE_API}/${path}?siteId=${siteId}`, {
        headers: { Authorization: bearer() },
      });
      expect(response.status()).toBe(404);
      expect(((await response.json()) as { code: string }).code).toBe('SITE_NOT_FOUND');
    }
    await page.getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.getByText(/Chargement/)).toHaveCount(0);
    await expect(page.getByRole('main')).not.toContainText(siteName);
    await expect(page.getByRole('main')).not.toContainText(deptName);
  });

  for (const [label, user] of [
    ['employee', USERS.employeeA],
    ['platform administrator', USERS.platformAdmin],
  ] as const) {
    test(`${label} cannot list or create departments or cost centers`, async ({
      page,
      request,
    }) => {
      const bearer = await signInFr(page, user);
      await expect(page.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);
      for (const path of ['departments', 'cost-centers']) {
        const list = await request.get(`${CORE_API}/${path}?siteId=${siteId}`, {
          headers: { Authorization: bearer() },
        });
        expect(list.status()).toBe(403);
        const create = await request.post(`${CORE_API}/${path}`, {
          headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-denied-${stamp}-${path}` },
          data: { siteId, code: 'DENIED', name: 'Refusé', effectiveFrom: '2026-01-01' },
        });
        expect(create.status()).toBe(403);
      }
    });
  }
});

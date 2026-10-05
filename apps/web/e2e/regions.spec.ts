import { expect, test } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, signInFr, sql, primaryNav } from './support';

test.describe.serial('MVP-002 Increment 3A: regions and optional site assignment', () => {
  const stamp = Date.now().toString(36).toUpperCase();
  const leCode = `E2R-${stamp}`;
  const leName = `Société des Mines du Haut-Katanga ${stamp}`;
  const regionCode = `RGK-${stamp}`.slice(0, 20);
  const regionName = `Région Grand Katanga et Haut-Lomami ${stamp}`;
  const oldCode = `OLD-${stamp}`.slice(0, 20);
  const oldName = `Site historique de Likasi ${stamp}`;
  const newCode = `NEW-${stamp}`.slice(0, 20);
  const newName = `Site de Kolwezi ${stamp}`;
  const looseCode = `SOLO-${stamp}`.slice(0, 20);
  const looseName = `Site sans région de Kipushi ${stamp}`;
  let legalEntityId = '';
  let regionId = '';
  let oldSiteId = '';
  let newSiteId = '';

  test('tenant administrator A creates a region, a site in it, and assigns an existing site', async ({
    page,
    request,
  }) => {
    const bearer = await signInFr(page, USERS.adminA);
    await primaryNav(page).getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');

    const leForm = page.getByTestId('legal-entity-form');
    await leForm.getByLabel('Code').fill(leCode);
    await leForm.getByLabel('Raison sociale').fill(leName);
    await leForm.getByLabel('Date de début').fill('2026-01-01');
    await leForm.getByRole('button', { name: 'Créer l’entité juridique' }).click();
    await page.getByRole('button', { name: `Voir les sites de ${leName} (${leCode})` }).click();
    await expect(
      page.getByRole('heading', { level: 2, name: `Régions de ${leName} (${leCode})` }),
    ).toBeFocused();
    await expect(page.getByTestId('region-list-empty')).toBeVisible();

    // A site created before any region exists: it stays without a region.
    const siteForm = page.getByTestId('site-form');
    await siteForm.getByLabel('Code').fill(oldCode);
    await siteForm.getByLabel('Nom du site').fill(oldName);
    await siteForm.getByLabel('Date de début').fill('2026-02-01');
    await siteForm.getByLabel('Date de fin (facultatif)').fill('2026-12-31');
    await siteForm.getByRole('button', { name: 'Créer le site' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(`Site ${oldName} (${oldCode}) créé.`);
    const oldRow = page.getByTestId('site-list').getByRole('listitem').filter({ hasText: oldName });
    await expect(oldRow.getByTestId('site-region')).toHaveText('Sans région');

    // Region.
    const regionForm = page.getByTestId('region-form');
    await regionForm.getByRole('button', { name: 'Créer la région' }).click();
    await expect(regionForm.getByTestId('form-summary')).toBeFocused();
    await regionForm.getByLabel('Code').fill(regionCode.toLowerCase());
    await regionForm.getByLabel('Nom de la région').fill(regionName);
    await regionForm.getByRole('button', { name: 'Créer la région' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Région ${regionName} (${regionCode}) créée.`,
    );
    await expect(
      page.getByTestId('region-list').locator('[tabindex="-1"]', { hasText: regionName }),
    ).toBeFocused();
    await expectAccessible(page);

    // A new site created directly in the region.
    await siteForm.getByLabel('Code').fill(newCode);
    await siteForm.getByLabel('Nom du site').fill(newName);
    await siteForm
      .getByLabel('Région (facultatif)')
      .selectOption({ label: `${regionName} (${regionCode})` });
    await siteForm.getByRole('button', { name: 'Créer le site' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(`Site ${newName} (${newCode}) créé.`);
    const newRow = page.getByTestId('site-list').getByRole('listitem').filter({ hasText: newName });
    await expect(newRow.getByTestId('site-region')).toHaveText(
      `Région : ${regionName} (${regionCode})`,
    );

    // First assignment of the pre-existing site.
    const assign = page.getByRole('button', {
      name: `Rattacher ${oldName} (${oldCode}) à une région`,
    });
    await assign.click();
    await expect(assign).toHaveAttribute('aria-expanded', 'true');
    const assignForm = page.getByTestId('assign-region-form');
    await expect(assignForm.getByLabel('Région', { exact: true })).toBeFocused();
    await expectAccessible(page);
    await assignForm
      .getByLabel('Région', { exact: true })
      .selectOption({ label: `${regionName} (${regionCode})` });
    await assignForm.getByRole('button', { name: 'Rattacher à la région' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Site ${oldName} (${oldCode}) rattaché à la région ${regionName} (${regionCode}).`,
    );
    await expect(oldRow.getByTestId('site-region')).toBeFocused();
    await expect(oldRow.getByTestId('site-region')).toHaveText(
      `Région : ${regionName} (${regionCode})`,
    );
    await expect(assign).toHaveCount(0);
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');

    // PostgreSQL: both relationships, one audit and one outbox record per business action.
    legalEntityId = sql(
      `SELECT id FROM tenant.legal_entity WHERE code = '${leCode}' AND tenant_id = '${TENANT_A}'`,
    );
    regionId = sql(
      `SELECT id FROM tenant.region WHERE code = '${regionCode}' AND tenant_id = '${TENANT_A}'`,
    );
    oldSiteId = sql(`SELECT id FROM tenant.site WHERE code = '${oldCode}'`);
    newSiteId = sql(`SELECT id FROM tenant.site WHERE code = '${newCode}'`);
    expect(
      sql(`SELECT tenant_id || '|' || legal_entity_id FROM tenant.region WHERE id = '${regionId}'`),
    ).toBe(`${TENANT_A}|${legalEntityId}`);
    for (const site of [oldSiteId, newSiteId]) {
      expect(
        sql(
          `SELECT tenant_id || '|' || legal_entity_id || '|' || region_id FROM tenant.site WHERE id = '${site}'`,
        ),
      ).toBe(`${TENANT_A}|${legalEntityId}|${regionId}`);
    }
    const audit = (resource: string, action: string) =>
      sql(
        `SELECT count(*) FROM platform.audit_event WHERE resource_id = '${resource}' AND action = '${action}'`,
      );
    const events = (subject: string, type: string) =>
      sql(
        `SELECT count(*) FROM platform.outbox_event WHERE envelope ->> 'subject' = '${subject}' AND event_type = '${type}'`,
      );
    expect(audit(regionId, 'region.create')).toBe('1');
    expect(events(regionId, 'tenant.region-created.v1')).toBe('1');
    expect(audit(newSiteId, 'site.create')).toBe('1');
    expect(audit(newSiteId, 'site.region.assign')).toBe('0');
    expect(events(newSiteId, 'tenant.site-created.v1')).toBe('1');
    expect(events(newSiteId, 'tenant.site-region-assigned.v1')).toBe('0');
    expect(
      sql(
        `SELECT envelope -> 'data' ->> 'regionId' FROM platform.outbox_event WHERE envelope ->> 'subject' = '${newSiteId}'`,
      ),
    ).toBe(regionId);
    expect(audit(oldSiteId, 'site.region.assign')).toBe('1');
    expect(events(oldSiteId, 'tenant.site-region-assigned.v1')).toBe('1');

    // Idempotent retries through the API: the same key replays; the same region with a new key
    // returns the site unchanged and records nothing new.
    const key = `e2e-assign-${stamp}-0001`;
    const retry = await request.put(`${CORE_API}/sites/${oldSiteId}/region`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': key },
      data: { regionId },
    });
    expect(retry.status()).toBe(200);
    expect(retry.headers()['idempotent-replayed']).toBeUndefined();
    const replay = await request.put(`${CORE_API}/sites/${oldSiteId}/region`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': key },
      data: { regionId },
    });
    expect(replay.status()).toBe(200);
    expect(replay.headers()['idempotent-replayed']).toBe('true');
    expect(audit(oldSiteId, 'site.region.assign')).toBe('1');
    expect(events(oldSiteId, 'tenant.site-region-assigned.v1')).toBe('1');
  });

  test('departments and cost centers still work for assigned and unassigned sites', async ({
    page,
  }) => {
    await signInFr(page, USERS.adminA);
    await primaryNav(page).getByRole('link', { name: 'Structure organisationnelle' }).click();
    await page.getByRole('button', { name: `Voir les sites de ${leName} (${leCode})` }).click();

    const siteForm = page.getByTestId('site-form');
    await siteForm.getByLabel('Code').fill(looseCode);
    await siteForm.getByLabel('Nom du site').fill(looseName);
    await siteForm.getByRole('button', { name: 'Créer le site' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Site ${looseName} (${looseCode}) créé.`,
    );

    for (const [name, code, unitCode] of [
      [oldName, oldCode, `DA-${stamp}`.slice(0, 20)],
      [looseName, looseCode, `DU-${stamp}`.slice(0, 20)],
    ] as const) {
      await page
        .getByRole('button', {
          name: `Voir les départements et centres de coût de ${name} (${code})`,
        })
        .click();
      const deptForm = page.getByTestId('department-form');
      await deptForm.getByLabel('Code').fill(unitCode);
      await deptForm.getByLabel('Nom du département').fill(`Département ${unitCode}`);
      await deptForm.getByRole('button', { name: 'Créer le département' }).click();
      await expect(page.getByTestId('announcer')).toHaveText(
        `Département Département ${unitCode} (${unitCode}) créé.`,
      );
      const ccForm = page.getByTestId('costCenter-form');
      await ccForm.getByLabel('Code').fill(`C${unitCode}`.slice(0, 20));
      await ccForm.getByLabel('Nom du centre de coût').fill(`Centre ${unitCode}`);
      await ccForm.getByRole('button', { name: 'Créer le centre de coût' }).click();
      await expect(page.getByTestId('announcer')).toHaveText(
        `Centre de coût Centre ${unitCode} (${`C${unitCode}`.slice(0, 20)}) créé.`,
      );
    }
    expect(sql(`SELECT region_id IS NULL FROM tenant.site WHERE code = '${looseCode}'`)).toBe('t');
    await expectAccessible(page);
  });

  test('tenant administrator B sees no region or relationship of tenant A', async ({
    page,
    request,
  }) => {
    const bearer = await signInFr(page, USERS.adminB);
    const regions = await request.get(`${CORE_API}/regions?legalEntityId=${legalEntityId}`, {
      headers: { Authorization: bearer() },
    });
    expect(regions.status()).toBe(404);
    expect(((await regions.json()) as { code: string }).code).toBe('LEGAL_ENTITY_NOT_FOUND');
    const assign = await request.put(`${CORE_API}/sites/${oldSiteId}/region`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-tenant-b-${stamp}-01` },
      data: { regionId },
    });
    expect(assign.status()).toBe(404);
    expect(((await assign.json()) as { code: string }).code).toBe('SITE_NOT_FOUND');
    await primaryNav(page).getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.getByText(/Chargement/)).toHaveCount(0);
    await expect(page.getByRole('main')).not.toContainText(regionName);
    await expect(page.getByRole('main')).not.toContainText(leName);
  });

  for (const [label, user] of [
    ['employee', USERS.employeeA],
    ['platform administrator', USERS.platformAdmin],
  ] as const) {
    test(`${label} cannot list or create regions or assign sites`, async ({ page, request }) => {
      const bearer = await signInFr(page, user);
      await expect(page.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);
      const list = await request.get(`${CORE_API}/regions?legalEntityId=${legalEntityId}`, {
        headers: { Authorization: bearer() },
      });
      expect(list.status()).toBe(403);
      const create = await request.post(`${CORE_API}/regions`, {
        headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-denied-${stamp}-region` },
        data: { legalEntityId, code: 'DENIED', name: 'Refusé', effectiveFrom: '2026-01-01' },
      });
      expect(create.status()).toBe(403);
      const assign = await request.put(`${CORE_API}/sites/${newSiteId}/region`, {
        headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-denied-${stamp}-assign` },
        data: { regionId },
      });
      expect(assign.status()).toBe(403);
    });
  }
});

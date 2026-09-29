import { expect, test } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, signInFr, sql } from './support';

test.describe.serial('MVP-002 Increment 3B: teams', () => {
  const stamp = Date.now().toString(36).toUpperCase();
  const leCode = `E2T-${stamp}`.slice(0, 20);
  const leName = `Compagnie Équatoriale des Équipes ${stamp}`;
  const siteCode = `LSH-${stamp}`.slice(0, 20);
  const siteName = `Site de Lubumbashi ${stamp}`;
  const deptCode = `OPS-${stamp}`.slice(0, 20);
  const deptName = `Opérations minières et sécurité ${stamp}`;
  const ccCode = `CCM-${stamp}`.slice(0, 20);
  const ccName = `Centre de coût Maintenance générale ${stamp}`;
  const deptTeamCode = `TD-${stamp}`.slice(0, 20);
  const deptTeamName = `Équipe d’exploitation de nuit ${stamp}`;
  const ccTeamCode = `TC-${stamp}`.slice(0, 20);
  const ccTeamName = `Équipe de maintenance préventive ${stamp}`;
  const ids = { site: '', dept: '', cc: '', deptTeam: '', ccTeam: '' };

  test('tenant administrator A creates a team beneath a department and one beneath a cost center', async ({
    page,
  }) => {
    // 1-2. Tenant A administrator selects a legal entity and a site (created here).
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
    // Site selection keeps its focus target.
    await expect(
      page.getByRole('heading', {
        level: 2,
        name: `Départements et centres de coût de ${siteName} (${siteCode})`,
      }),
    ).toBeFocused();

    const deptForm = page.getByTestId('department-form');
    await deptForm.getByLabel('Code').fill(deptCode);
    await deptForm.getByLabel('Nom du département').fill(deptName);
    await deptForm.getByRole('button', { name: 'Créer le département' }).click();
    await expect(page.getByTestId('department-list')).toContainText(deptName);

    const ccForm = page.getByTestId('costCenter-form');
    await ccForm.getByLabel('Code').fill(ccCode);
    await ccForm.getByLabel('Nom du centre de coût').fill(ccName);
    await ccForm.getByLabel('Date de fin (facultatif)').fill('2026-12-31');
    await ccForm.getByRole('button', { name: 'Créer le centre de coût' }).click();
    await expect(page.getByTestId('costCenter-list')).toContainText(ccName);
    await expect(page.getByTestId('team-none')).toBeVisible();

    // 3. Select the department and create a team.
    const selectDept = page.getByRole('button', {
      name: `Afficher les équipes du département ${deptName} (${deptCode})`,
    });
    await selectDept.click();
    await expect(selectDept).toHaveAttribute('aria-pressed', 'true');
    await expect(
      page.getByRole('heading', {
        level: 3,
        name: `Équipes du département ${deptName} (${deptCode})`,
      }),
    ).toBeFocused();
    await expect(page.getByTestId('team-list-empty')).toHaveText(
      'Aucune équipe pour ce département pour le moment.',
    );
    await expectAccessible(page);

    let teamForm = page.getByTestId('team-form');
    await teamForm.getByRole('button', { name: 'Créer l’équipe' }).click();
    await expect(teamForm.getByTestId('form-summary')).toBeFocused();
    await teamForm.getByLabel('Code').fill(deptTeamCode.toLowerCase());
    await teamForm.getByLabel('Nom de l’équipe').fill(deptTeamName);
    await teamForm.getByRole('button', { name: 'Créer l’équipe' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Équipe ${deptTeamName} (${deptTeamCode}) créée.`,
    );
    await expect(
      page.getByTestId('team-list').locator('[tabindex="-1"]', { hasText: deptTeamName }),
    ).toBeFocused();

    // 4. Select the cost center and create another team (dates default to its closed period).
    const selectCc = page.getByRole('button', {
      name: `Afficher les équipes du centre de coût ${ccName} (${ccCode})`,
    });
    await selectCc.click();
    await expect(selectCc).toHaveAttribute('aria-pressed', 'true');
    await expect(selectDept).toHaveAttribute('aria-pressed', 'false');
    await expect(
      page.getByRole('heading', {
        level: 3,
        name: `Équipes du centre de coût ${ccName} (${ccCode})`,
      }),
    ).toBeFocused();
    // 5. The department's team is not shown beneath the cost center.
    await expect(page.getByTestId('team-list-empty')).toBeVisible();
    await expect(page.getByTestId('team-section')).not.toContainText(deptTeamName);
    teamForm = page.getByTestId('team-form');
    await expect(teamForm.getByLabel('Date de fin (facultatif)')).toHaveValue('2026-12-31');
    await teamForm.getByLabel('Code').fill(ccTeamCode);
    await teamForm.getByLabel('Nom de l’équipe').fill(ccTeamName);
    await teamForm.getByRole('button', { name: 'Créer l’équipe' }).click();
    await expect(page.getByTestId('announcer')).toHaveText(
      `Équipe ${ccTeamName} (${ccTeamCode}) créée.`,
    );
    await expect(page.getByTestId('team-list')).toContainText(ccTeamName);
    await expect(page.getByTestId('team-list')).not.toContainText(deptTeamName);
    await expectAccessible(page);

    // 5. Back on the department, only its own team is listed.
    await selectDept.click();
    await expect(page.getByTestId('team-list')).toContainText(deptTeamName);
    await expect(page.getByTestId('team-list')).not.toContainText(ccTeamName);
    await expect(page.locator('html')).toHaveAttribute('lang', 'fr');

    // 6. PostgreSQL: tenant, derived site, exactly one parent and effective dates.
    const lookup = (table: string, code: string) =>
      sql(`SELECT id FROM ${table} WHERE code = '${code}' AND tenant_id = '${TENANT_A}'`);
    ids.site = lookup('tenant.site', siteCode);
    ids.dept = lookup('tenant.department', deptCode);
    ids.cc = lookup('tenant.cost_center', ccCode);
    ids.deptTeam = lookup('tenant.team', deptTeamCode);
    ids.ccTeam = lookup('tenant.team', ccTeamCode);
    for (const id of Object.values(ids)) expect(id).toMatch(/^[0-9a-f-]{36}$/);
    const row = (id: string) =>
      sql(
        `SELECT tenant_id || '|' || site_id || '|' || coalesce(department_id::text, '-') || '|' || coalesce(cost_center_id::text, '-') || '|' || effective_from || '|' || coalesce(effective_to::text, 'open') FROM tenant.team WHERE id = '${id}'`,
      );
    expect(row(ids.deptTeam)).toBe(`${TENANT_A}|${ids.site}|${ids.dept}|-|2026-01-01|open`);
    expect(row(ids.ccTeam)).toBe(`${TENANT_A}|${ids.site}|-|${ids.cc}|2026-01-01|2026-12-31`);

    // 7. Exactly one audit record and one outbox event per creation, without the team name.
    for (const [id, parentType, parentField, parentId] of [
      [ids.deptTeam, 'department', 'departmentId', ids.dept],
      [ids.ccTeam, 'cost-center', 'costCenterId', ids.cc],
    ] as const) {
      expect(
        sql(
          `SELECT count(*) || '|' || min(action) || '|' || min(resource_type) FROM platform.audit_event WHERE resource_id = '${id}'`,
        ),
      ).toBe('1|team.create|team');
      expect(
        sql(
          `SELECT count(*) || '|' || min(event_type) FROM platform.outbox_event WHERE envelope ->> 'subject' = '${id}'`,
        ),
      ).toBe('1|tenant.team-created.v1');
      expect(
        sql(
          `SELECT (envelope -> 'data' ->> 'siteId') || '|' || (envelope -> 'data' ->> 'parentType') || '|' || (envelope -> 'data' ->> '${parentField}') || '|' || (envelope -> 'data' ? 'name')::text FROM platform.outbox_event WHERE envelope ->> 'subject' = '${id}'`,
        ),
      ).toBe(`${ids.site}|${parentType}|${parentId}|false`);
    }
  });

  // 8. Tenant isolation.
  test('tenant administrator B sees neither team and cannot reach either parent', async ({
    page,
    request,
  }) => {
    const bearer = await signInFr(page, USERS.adminB);
    for (const [query, code] of [
      [`departmentId=${ids.dept}`, 'DEPARTMENT_NOT_FOUND'],
      [`costCenterId=${ids.cc}`, 'COST_CENTER_NOT_FOUND'],
    ] as const) {
      const response = await request.get(`${CORE_API}/teams?${query}`, {
        headers: { Authorization: bearer() },
      });
      expect(response.status()).toBe(404);
      expect(((await response.json()) as { code: string }).code).toBe(code);
    }
    const create = await request.post(`${CORE_API}/teams`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-teams-b-${stamp}` },
      data: {
        departmentId: ids.dept,
        code: 'FOREIGN',
        name: 'Étrangère',
        effectiveFrom: '2026-01-01',
      },
    });
    expect(create.status()).toBe(404);
    expect(((await create.json()) as { code: string }).code).toBe('DEPARTMENT_NOT_FOUND');
    await page.getByRole('link', { name: 'Structure organisationnelle' }).click();
    await expect(page.getByText(/Chargement/)).toHaveCount(0);
    await expect(page.getByRole('main')).not.toContainText(deptTeamName);
    await expect(page.getByRole('main')).not.toContainText(ccTeamName);
  });

  // 9. Role denial.
  for (const [label, user] of [
    ['employee', USERS.employeeA],
    ['platform administrator', USERS.platformAdmin],
  ] as const) {
    test(`${label} cannot list or create teams`, async ({ page, request }) => {
      const bearer = await signInFr(page, user);
      await expect(page.getByRole('link', { name: 'Structure organisationnelle' })).toHaveCount(0);
      const list = await request.get(`${CORE_API}/teams?departmentId=${ids.dept}`, {
        headers: { Authorization: bearer() },
      });
      expect(list.status()).toBe(403);
      const create = await request.post(`${CORE_API}/teams`, {
        headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-teams-denied-${stamp}` },
        data: {
          costCenterId: ids.cc,
          code: 'DENIED',
          name: 'Refusée',
          effectiveFrom: '2026-01-01',
        },
      });
      expect(create.status()).toBe(403);
    });
  }
});

import { expect, test, type Page } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, signIn, sql } from './support';

// MVP-021 (Issue #47): the employee directory and employment history with the real identity
// provider and database. The seed tenant administrator finds an employee (accents ignored),
// schedules a placement change through the hierarchy selects, cancels it, and assigns a manager
// by keyboard. Only the employee ID reaches the URL; nothing personal reaches browser storage,
// audit metadata or outbox data. An employee has neither the pages nor the API.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const ENTITY = `HE-${stamp}`;
const SITE = `HS-${stamp}`;
const DEPARTMENT = `HD-${stamp}`;
const COST_CENTER = `HC-${stamp}`;
const FIRST = `H2E-${stamp}-1`;
const SECOND = `H2E-${stamp}-2`;
const FAMILY = `Kabila${stamp.toLowerCase().replace(/[^a-z]/gu, '')}`;

/** Today in the seed organization's time zone (the business date). */
function today(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization
     WHERE id = '${TENANT_A}'`,
  );
}

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function employeeId(number: string): string {
  return sql(
    `SELECT id FROM people.employee WHERE tenant_id = '${TENANT_A}' AND employee_number = '${number}'`,
  );
}

async function storage(page: Page): Promise<string> {
  return page.evaluate(() => {
    const values: string[] = [];
    for (const store of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < store.length; index++) {
        const key = store.key(index) ?? '';
        values.push(key, store.getItem(key) ?? '');
      }
    }
    return values.join('\n');
  });
}

async function create(page: Page, bearer: () => string, path: string, key: string, data: object) {
  const response = await page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-history-${key}-${stamp}-0001` },
    data,
  });
  expect(response.status()).toBe(201);
  return ((await response.json()) as { id: string }).id;
}

test.describe.serial('MVP-021: employment history', () => {
  test('an administrator schedules and cancels a placement change', async ({ page }) => {
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const legalEntityId = await create(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Historique Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    const siteId = await create(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: `Historique Site ${stamp}`,
      timezone: 'Africa/Lubumbashi',
      effectiveFrom: '2026-01-01',
    });
    await create(page, bearer, '/departments', 'dp', {
      siteId,
      code: DEPARTMENT,
      name: `Finances ${stamp}`,
      effectiveFrom: '2026-01-01',
    });
    await create(page, bearer, '/cost-centers', 'cc', {
      siteId,
      code: COST_CENTER,
      name: `Logistique ${stamp}`,
      effectiveFrom: '2026-01-01',
    });
    const start = plusDays(today(), -20);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${FIRST};Élodie;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${SECOND};Jean-Pierre;Mukendi;${start};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-history-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-history-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    const first = employeeId(FIRST);

    // Search: accents and case ignored; the query never reaches the URL.
    await page.getByRole('link', { name: 'Employés', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Employés');
    await page.getByLabel('Matricule ou nom').fill(`ELODIE ${FAMILY.toUpperCase()}`);
    await page.getByRole('button', { name: 'Rechercher', exact: true }).click();
    const results = page.getByTestId('directory-table');
    await expect(results.getByRole('row')).toHaveCount(2);
    expect(page.url()).not.toMatch(/elodie|kabila/iu);
    await expectAccessible(page);
    await results.getByRole('link', { name: `Élodie ${FAMILY}` }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Élodie ${FAMILY}`);
    expect(new URL(page.url()).pathname).toBe(`/admin/people/${first}`);
    await expect(page.getByTestId('current-PLACEMENT')).toContainText(DEPARTMENT);
    await expectAccessible(page);

    // Schedule a move to the cost center in ten days.
    const day = plusDays(today(), 10);
    const form = page.getByTestId('change-form');
    await form.getByLabel('Date d’effet').fill(day);
    await form.getByLabel('Affectation', { exact: true }).check();
    await form.getByLabel('Entité juridique').selectOption({ label: `Historique Entité ${stamp} (${ENTITY})` });
    await form.getByLabel('Site', { exact: true }).selectOption({ label: `Historique Site ${stamp} (${SITE})` });
    await form
      .getByLabel('Département ou centre de coût')
      .selectOption({ label: `Logistique ${stamp} (${COST_CENTER})` });
    await form.getByLabel('Motif (facultatif)').selectOption('REORGANIZATION');
    await form.getByRole('button', { name: 'Prévisualiser le changement' }).click();
    const preview = page.getByTestId('change-preview');
    await expect(preview.getByTestId('preview-timing')).toHaveText(
      'Programmé (commence après aujourd’hui)',
    );
    await expect(preview.getByRole('heading')).toBeFocused();
    await expectAccessible(page);
    await preview.getByRole('button', { name: 'Confirmer et enregistrer' }).click();
    await expect(page.getByTestId('announcer')).toContainText('enregistré');
    await expect(page.getByTestId('timeline-table').getByRole('row')).toHaveCount(3);

    // At 320 px the page does not scroll sideways: wide tables scroll inside their region.
    await page.setViewportSize({ width: 320, height: 800 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.setViewportSize({ width: 1280, height: 800 });

    // Cancel it: the department is restored to the end.
    await page.getByTestId('changes-table').getByRole('button', { name: /Annuler le changement/u }).click();
    const panel = page.getByTestId('cancel-panel');
    await expect(panel.getByTestId('cancel-preview')).toContainText(DEPARTMENT);
    await panel.getByRole('button', { name: 'Confirmer l’annulation' }).click();
    await expect(page.getByTestId('announcer')).toContainText('annulé');
    await expect(page.getByTestId('timeline-table').getByRole('row')).toHaveCount(2);
    await expect(page.getByTestId('changes-table')).toContainText('Annulé');

    // Nothing personal in browser storage; audit and outbox carry identifiers and counts only.
    expect(await storage(page)).not.toMatch(/Élodie|Kabila|H2E-|Mukendi/iu);
    const evidence = sql(
      `SELECT coalesce(string_agg(metadata::text, ' '), '') FROM platform.audit_event
       WHERE tenant_id = '${TENANT_A}' AND resource_id = '${first}'
          OR (tenant_id = '${TENANT_A}' AND action LIKE 'employment-change.%')`,
    );
    expect(evidence).not.toMatch(/Élodie|Kabila|H2E-|REORGANIZATION|elodie/iu);
    expect(evidence).not.toContain(day);
    const published = sql(
      `SELECT coalesce(string_agg((envelope->'data')::text, ' '), '') FROM platform.outbox_event
       WHERE tenant_id = '${TENANT_A}' AND event_type LIKE 'people.employment.%'
       AND envelope->'data'->>'employeeId' = '${first}'`,
    );
    expect(published).toContain(first);
    expect(published).not.toMatch(new RegExp(`${day}|${COST_CENTER}|REORGANIZATION`, 'u'));
  });

  test('an administrator assigns a manager by keyboard in English', async ({ page }) => {
    await signIn(page, USERS.adminA, 'en');
    // Tokens live in memory only: navigate inside the app rather than reloading it.
    await page.getByRole('link', { name: 'Employees', exact: true }).click();
    await page.getByLabel('Employee number or name').fill(FIRST.toLowerCase());
    await page.keyboard.press('Enter');
    await page.getByTestId('directory-table').getByRole('link', { name: `Élodie ${FAMILY}` }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Élodie ${FAMILY}`);
    const form = page.getByTestId('change-form');
    await form.getByLabel('Effective from').fill(plusDays(today(), 3));
    await form.getByLabel('Manager', { exact: true }).focus();
    await page.keyboard.press('Space');
    await form.getByLabel('Find the manager by employee number or name').fill(SECOND.toLowerCase());
    await page.keyboard.press('Enter');
    await form.getByRole('radio', { name: /Jean-Pierre Mukendi/u }).check();
    await form.getByRole('button', { name: 'Preview the change' }).focus();
    await page.keyboard.press('Enter');
    const preview = page.getByTestId('change-preview');
    await expect(preview.getByTestId('preview-table')).toContainText('Jean-Pierre Mukendi');
    await preview.getByRole('button', { name: 'Confirm and record' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('announcer')).toContainText('recorded');
    await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
    await expectAccessible(page);
  });

  test('an employee has neither the pages nor the API', async ({ page }) => {
    const bearer = await signIn(page, USERS.employeeA, 'en', undefined);
    await expect(page.getByRole('link', { name: 'Employees', exact: true })).toHaveCount(0);
    for (const path of ['/employees', `/employees/${employeeId(FIRST)}`]) {
      const response = await page.request.get(`${CORE_API}${path}`, {
        headers: { Authorization: bearer() },
      });
      expect(response.status()).toBe(403);
    }
  });
});

import { expect, test, type Page } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, signIn, sql, primaryNav } from './support';

// MVP-020 (Issue #45): employee import with the real identity provider and database. The seed
// tenant administrator steps up with TOTP, downloads the template, checks a French semicolon file
// and an English comma file, reviews valid and invalid rows, imports, re-imports an existing
// employee number (refused per row) and cancels, by keyboard. Nothing personal reaches the URL or
// browser storage; an employee has neither the page nor the API.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const ENTITY = `IE-${stamp}`;
const SITE = `IS-${stamp}`;
const FIRST = `E2E-${stamp}-1`;
const SECOND = `E2E-${stamp}-2`;
const THIRD = `E2E-${stamp}-3`;

function employees(numbers: readonly string[]): number {
  return Number(
    sql(
      `SELECT count(*) FROM people.employee WHERE tenant_id = '${TENANT_A}'
       AND employee_number IN (${numbers.map((n) => `'${n}'`).join(', ')})`,
    ),
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

test.describe.serial('MVP-020: employee import', () => {
  test('a tenant administrator imports a French semicolon file', async ({ page }) => {
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await page.request.post(`${CORE_API}/legal-entities`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-import-le-${stamp}-0001` },
      data: {
        code: ENTITY,
        name: `Import Entité ${stamp}`,
        countryCode: 'CD',
        effectiveFrom: '2026-01-01',
      },
    });
    expect(entity.status()).toBe(201);
    const site = await page.request.post(`${CORE_API}/sites`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-import-st-${stamp}-0001` },
      data: {
        legalEntityId: ((await entity.json()) as { id: string }).id,
        code: SITE,
        name: `Import Site ${stamp}`,
        timezone: 'Africa/Lubumbashi',
        effectiveFrom: '2026-01-01',
      },
    });
    expect(site.status()).toBe(201);

    await primaryNav(page).getByRole('link', { name: 'Importer des employés' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Importer des employés');
    await expectAccessible(page);

    // The French template is a header-only CSV.
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.getByRole('button', { name: 'Télécharger le modèle en français' }).click(),
    ]);
    expect(download.suggestedFilename()).toBe('divalhr-employee-import-fr.csv');
    const template = await download.createReadStream();
    const chunks: Buffer[] = [];
    for await (const chunk of template) chunks.push(chunk as Buffer);
    expect(Buffer.concat(chunks).toString('utf8')).toContain('Matricule');

    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site',
      `${FIRST};Élodie;Mukendi Kabeya;2026-03-01;${ENTITY};${SITE}`,
      `${SECOND};Jean-Pierre;N’Diaye;2026-03-02;${ENTITY.toLowerCase()};${SITE}`,
      `${THIRD};Ana;Faux;01/03/2026;${ENTITY};${SITE}`,
    ].join('\r\n');
    await page.getByLabel('Fichier CSV').setInputFiles({
      name: 'employes.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(file, 'utf8'),
    });
    await page.getByRole('button', { name: 'Vérifier le fichier' }).click();
    await expect(page.getByTestId('preview-counts')).toHaveText(
      '3 lignes : 2 valides, 1 non valides.',
    );
    await expect(
      page.getByTestId('import-preview').getByRole('heading', { level: 2 }),
    ).toBeFocused();
    const table = page.getByTestId('rows-table');
    await expect(table).toContainText('Élodie Mukendi Kabeya');
    await expect(table).toContainText('Date d’entrée : Date qui n’est pas au format AAAA-MM-JJ');
    await expectAccessible(page);

    await page.getByRole('radio', { name: 'Lignes non valides' }).check();
    await expect(page.getByTestId('import-row')).toHaveCount(1);
    await expect(page.getByTestId('import-row')).not.toContainText('Élodie');

    await page.getByRole('button', { name: 'Importer 2 employés' }).click();
    await expect(page.getByRole('alert')).toHaveText(
      'Confirmez que les lignes non valides ne seront pas importées.',
    );
    await page.getByLabel('Je comprends que 1 ligne non valide ne sera pas importée.').check();
    await page.getByRole('button', { name: 'Importer 2 employés' }).click();
    await page.getByRole('button', { name: 'Oui, importer' }).click();
    await expect(page.getByTestId('result-created')).toHaveText('2 employés créés.');
    await expect(page.getByTestId('result-not-imported')).toHaveText('1 ligne non importée.');
    await expect(page.getByTestId('not-imported-table')).toContainText('Non importée');
    await expect(page.getByTestId('not-imported-table')).toContainText(
      'Date qui n’est pas au format AAAA-MM-JJ',
    );
    await expectAccessible(page);

    expect(employees([FIRST, SECOND, THIRD])).toBe(2);
    expect(page.url()).not.toMatch(/E2E|Mukendi|employee-imports/u);
    expect(await storage(page)).not.toMatch(/Mukendi|E2E-|Élodie/u);
  });

  test('re-importing an existing number is refused per row; English comma file by keyboard', async ({
    page,
  }) => {
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Import employees' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Import employees');
    const file = [
      'Employee number,Given names,Family name,Start date,Legal entity code,Site code',
      `${FIRST},Élodie,Mukendi,2026-03-01,${ENTITY},${SITE}`,
      `${THIRD},Ana,"Ilunga",2026-03-03,${ENTITY},${SITE}`,
    ].join('\n');
    await page.getByLabel('CSV file').setInputFiles({
      name: 'employees.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(file, 'utf8'),
    });
    await page.getByRole('button', { name: 'Check the file' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('preview-counts')).toHaveText('2 rows: 1 valid, 1 invalid.');
    // At 320 px the page does not scroll sideways: wide tables scroll inside their region.
    await page.setViewportSize({ width: 320, height: 800 });
    await expect(page.getByTestId('rows-table')).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await expect(page.getByTestId('rows-table')).toContainText(
      'Employee number: Employee number already exists',
    );
    await expectAccessible(page);
    // Cancel by keyboard: nothing is created.
    await page.getByRole('button', { name: 'Cancel this import' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('import-result')).toContainText('Import cancelled');
    await expect(page.getByTestId('import-result').getByRole('heading')).toBeFocused();
    expect(employees([THIRD])).toBe(0);
    await expectAccessible(page);
  });

  test('an employee has no employee import', async ({ page }) => {
    const bearer = await signIn(page, USERS.employeeA, 'en', undefined);
    await expect(page.getByRole('link', { name: 'Import employees' })).toHaveCount(0);
    const template = await page.request.get(`${CORE_API}/employee-imports/template?lang=en`, {
      headers: { Authorization: bearer() },
    });
    expect(template.status()).toBe(403);
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-import-denied-${stamp}-0001`,
      },
      data: Buffer.from('Matricule\r\nX\r\n', 'utf8'),
    });
    expect(upload.status()).toBe(403);
    expect(upload.headers()['cache-control']).toContain('no-store');
  });
});

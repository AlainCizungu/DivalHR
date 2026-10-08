import { expect, test, type Page } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, primaryNav, signIn, sql } from './support';

// MVP-031A (Issue #73): the contract expiration queue with the real identity provider and
// database. Three synthetic employees get fixed-term contracts that ended three days ago, end in
// ten days and end in seventy days (dates relative to the organization's business date; the exact
// boundaries are covered by the fixed-clock integration tests). A French administrator goes from
// the home card to the queue, searches, filters by department and category and opens a record;
// an English administrator uses the keyboard only; an employee is refused. Nothing personal
// reaches the URL or browser storage, and the page never overflows at 320 or 390 px.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const letters = stamp.toLowerCase().replace(/[^a-z]/gu, '') || 'x';
const ENTITY = `XE-${stamp}`;
const SITE = `XS-${stamp}`;
const DEPARTMENT = `XD-${stamp}`;
const COST_CENTER = `XC-${stamp}`;
const FAMILY = `Echeance${letters}`;
const EXPIRED = `EXP-${stamp}-1`;
const SOON = `EXP-${stamp}-2`;
const LATER = `EXP-${stamp}-3`;
// The digest of the canonical snapshot "{}" (ContractDigests.snapshot): the integrity job passes.
const EMPTY_SNAPSHOT_SHA256 =
  "encode(sha256(convert_to(E'DIVALHR-CONTRACT-SNAPSHOT\\n1\\nSHA-256\\ngrammar=1\\nrenderer=1\\n{}', 'UTF8')), 'hex')";

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

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-exp-${key}-${stamp}-0001` },
    data,
  });
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

async function noOverflow(page: Page) {
  for (const width of [390, 320]) {
    await page.setViewportSize({ width, height: 800 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      `no horizontal overflow at ${width} px`,
    ).toBe(true);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

test.describe.serial('MVP-031A: contract expiration queue', () => {
  test('setup: units, three employees and their fixed-term contracts', async ({ page }) => {
    test.setTimeout(180_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Échéances Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const legalEntityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: `Site Échéances ${stamp}`,
      timezone: 'Africa/Kinshasa',
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    const siteId = ((await site.json()) as { id: string }).id;
    for (const [path, code, name, key] of [
      ['/departments', DEPARTMENT, `Logistique ${stamp}`, 'dp'],
      ['/cost-centers', COST_CENTER, `Atelier ${stamp}`, 'cc'],
    ] as const) {
      expect(
        (
          await post(page, bearer, path, key, { siteId, code, name, effectiveFrom: '2026-01-01' })
        ).status(),
      ).toBe(201);
    }
    const t = today();
    const start = plusDays(t, -200);
    const header =
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département;Code du centre de coût';
    const file = [
      header,
      `${EXPIRED};Bénédicte;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT};`,
      `${SOON};Jean-Pierre;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT};`,
      `${LATER};Zébulon;${FAMILY};${start};${ENTITY};${SITE};;${COST_CENTER}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-exp-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-exp-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    // An approved fixed-term template, through the API.
    const template = await post(page, bearer, '/contract-templates', 'tpl', {
      code: `CDD-${stamp}`,
      name: `Contrat à durée déterminée ${stamp}`,
      contractType: 'FIXED_TERM',
    });
    expect(template.status()).toBe(201);
    const templateId = ((await template.json()) as { id: string }).id;
    const draft = await post(page, bearer, `/contract-templates/${templateId}/versions`, 'ver', {
      locale: 'fr',
      title: 'Contrat',
      body: '# Contrat\nTexte du contrat.',
    });
    expect(draft.status()).toBe(201);
    const drafted = (await draft.json()) as { id: string; version: number };
    const approved = await post(
      page,
      bearer,
      `/contract-templates/${templateId}/versions/${drafted.id}/approve`,
      'apr',
      { expectedVersion: drafted.version, acknowledgements: ['TEXT_VERIFIED'] },
    );
    expect(approved.status()).toBe(200);
    const versionId = ((await approved.json()) as { id: string }).id;

    // Stored contracts (issuing through the UI is the MVP-030 suite's job).
    for (const [number, end] of [
      [EXPIRED, plusDays(t, -3)],
      [SOON, plusDays(t, 10)],
      [LATER, plusDays(t, 70)],
    ] as const) {
      sql(
        `WITH em AS (
           SELECT m.id, m.employee_id FROM people.employment m
           JOIN people.employee e ON e.id = m.employee_id AND e.tenant_id = m.tenant_id
           WHERE m.tenant_id = '${TENANT_A}' AND e.employee_number = '${number}'),
         guard AS (
           INSERT INTO documents.contract_employment_guard (tenant_id, employment_id)
           SELECT '${TENANT_A}', id FROM em ON CONFLICT DO NOTHING)
         INSERT INTO documents.contract (id, tenant_id, employee_id, employment_id, template_id,
           template_version_id, contract_type, locale, start_date, end_date, snapshot,
           snapshot_canonical, snapshot_sha256, digest_version, grammar_version, renderer_version,
           state, issued_at, issued_by)
         SELECT gen_random_uuid(), '${TENANT_A}', employee_id, id, '${templateId}', '${versionId}',
           'FIXED_TERM', 'fr', '${start}', '${end}', '{}'::jsonb, '{}', ${EMPTY_SNAPSHOT_SHA256},
           1, 1, 1, 'ISSUED', now(), 'e2e-setup' FROM em`,
      );
    }
  });

  test('a French administrator goes from the home card to a record', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'fr');
    const card = page.getByTestId('home-contract-attention');
    await expect(card.getByRole('heading', { name: 'Contrats à traiter' })).toBeVisible();
    await expect(card.getByTestId('attention-count')).toContainText(
      /contrats? (est|sont) échus? ou se termin/u,
    );
    await card.getByRole('link', { name: 'Voir les échéances des contrats' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Échéances des contrats');
    await expect(page.getByTestId('as-of')).toContainText('Calcul au');
    await expect(page.getByTestId('as-of')).toContainText('(Africa/Kinshasa)');
    await expect(page.getByTestId('category-EXPIRED')).toContainText('Échus');

    await page.getByLabel('Matricule ou nom').fill(FAMILY.toLowerCase());
    await page.getByRole('button', { name: 'Appliquer les filtres' }).click();
    const rows = page.getByTestId('expiration-row');
    await expect(rows).toHaveCount(3);
    await expect(rows.nth(0)).toContainText(`Bénédicte ${FAMILY}`);
    await expect(rows.nth(0)).toContainText('Échu depuis 3 jours');
    await expect(rows.nth(1)).toContainText('Se termine dans 10 jours');
    await expect(rows.nth(2)).toContainText('Se termine dans 70 jours');
    await expect(rows.nth(2)).toContainText(`Atelier ${stamp} (${COST_CENTER})`);
    await expectAccessible(page);

    // The department, chosen through the hierarchy.
    const unit = page.getByTestId('unit-filter');
    await unit
      .getByLabel('Entité juridique')
      .selectOption({ label: `Échéances Entité ${stamp} (${ENTITY})` });
    await unit
      .getByLabel('Site', { exact: true })
      .selectOption({ label: `Site Échéances ${stamp} (${SITE})` });
    await unit
      .getByLabel('Département ou centre de coût')
      .selectOption({ label: `Logistique ${stamp} (${DEPARTMENT})` });
    await page.getByRole('button', { name: 'Appliquer les filtres' }).click();
    await expect(rows).toHaveCount(2);
    await expect(page.getByTestId('category-DAYS_61_TO_90')).toContainText(': 0');

    // Category: the expired one only.
    for (const category of ['NEXT_30_DAYS', 'DAYS_31_TO_60', 'DAYS_61_TO_90']) {
      await page.getByTestId(`category-${category}`).click();
    }
    await expect(rows).toHaveCount(1);
    await noOverflow(page);

    await rows.getByRole('link', { name: `Bénédicte ${FAMILY}` }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(`Bénédicte ${FAMILY}`);
    await expect(
      page.getByTestId('contracts').getByRole('heading', { name: 'Contrats', exact: true }),
    ).toBeFocused();
    expect(page.url()).toMatch(/\/admin\/people\/[0-9a-f-]{36}#contracts$/u);
    expect(await storage(page)).not.toMatch(new RegExp(`${FAMILY}|EXP-|Bénédicte`, 'iu'));
  });

  test('an English administrator filters with the keyboard only', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Contract expirations', exact: true }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Contract expirations');
    await page.getByLabel('Employee number or name').focus();
    await page.keyboard.type(SOON);
    await page.keyboard.press('Enter');
    const rows = page.getByTestId('expiration-row');
    await expect(rows).toHaveCount(1);
    await expect(rows).toContainText('Ends in 10 days');
    await page.getByTestId('category-NEXT_30_DAYS').focus();
    await page.keyboard.press('Space');
    await expect(page.getByTestId('category-NEXT_30_DAYS')).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    await expect(page.getByTestId('expirations-empty')).toHaveText(
      'No contract matches these filters.',
    );
    await page.getByRole('button', { name: 'Reset filters' }).focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('category-NEXT_30_DAYS')).toHaveAttribute('aria-pressed', 'true');
    await expectAccessible(page);
    await noOverflow(page);

    // A reload ends the in-memory session; the language preference stays, nothing personal does.
    await page.reload();
    // The deep link stays and asks to sign in, still in English.
    await expect(page).toHaveURL(/\/admin\/contract-expirations$/u);
    await expect(page.getByRole('banner').getByRole('button', { name: 'Sign in' })).toBeVisible();
    await expect(
      page.getByText('Sign in as an organization administrator to see contract expirations.', {
        exact: true,
      }),
    ).toBeVisible();
    expect(await storage(page)).not.toMatch(new RegExp(`${FAMILY}|EXP-`, 'iu'));
  });

  test('an employee is refused in the UI and the API', async ({ page }) => {
    const bearer = await signIn(page, USERS.employeeA, 'fr', undefined);
    await expect(
      primaryNav(page).getByRole('link', { name: 'Échéances des contrats' }),
    ).toHaveCount(0);
    await expect(page.getByTestId('home-contract-attention')).toHaveCount(0);
    // In-app navigation keeps the in-memory session (a reload would sign the user out).
    await page.evaluate(() => {
      window.history.pushState({}, '', '/admin/contract-expirations');
      window.dispatchEvent(new PopStateEvent('popstate'));
    });
    await expect(page.getByTestId('not-authorized')).toHaveText(
      'Seuls les administrateurs de l’organisation peuvent consulter les échéances des contrats.',
    );
    for (const response of [
      await page.request.get(`${CORE_API}/contract-expirations/summary`, {
        headers: { Authorization: bearer() },
      }),
      await page.request.post(`${CORE_API}/contract-expirations/search`, {
        headers: { Authorization: bearer() },
        data: {},
      }),
    ]) {
      expect(response.status()).toBe(403);
      // Refusals come from the security filter: never stored, whatever the exact header.
      expect(response.headers()['cache-control']).toContain('no-store');
    }
  });
});

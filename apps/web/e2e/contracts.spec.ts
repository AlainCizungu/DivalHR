import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  CORE_API,
  TENANT_A,
  USERS,
  expectAccessible,
  mailTo,
  signIn,
  sql,
  primaryNav,
  openSecretLink,
  typeSecret,
} from './support';

// MVP-030 (Issue #51): create and acknowledge a contract with the real identity provider and
// database. An administrator creates a template, writes a French draft with the editor, checks
// it and approves it; issues it from an employee's profile to an invited, linked employee; the
// employee opens « Mes contrats », reads and acknowledges receipt; the administrator sees the
// evidence. Another employee and tenant B get 404s; a voided contract cannot be acknowledged.
// Nothing personal reaches the URL, browser storage, audit metadata or outbox data.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `CE-${stamp}`;
const SITE = `CS-${stamp}`;
const DEPARTMENT = `CD-${stamp}`;
const OWNER = `CTR-${stamp}-1`;
const OTHER = `CTR-${stamp}-2`;
const FAMILY = `Kabasele${lower.replace(/[^a-z]/gu, '')}`;
const OWNER_EMAIL = `e2e.contrat.${lower}@example.test`;
const OTHER_EMAIL = `e2e.contrat.autre.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Contrat!`;
const TEMPLATE_CODE = `CDI-${stamp}`;
const TEMPLATE_NAME = `Contrat à durée indéterminée ${stamp}`;

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

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ctr-${key}-${stamp}-0001` },
    data,
  });
}

/** Opens a profile through the directory (a full page load would end the in-memory session). */
async function openProfile(page: Page, number: string, name: string) {
  await primaryNav(page).getByRole('link', { name: 'Employés', exact: true }).click();
  await page.getByLabel('Matricule ou nom').fill(number);
  await page.keyboard.press('Enter');
  await page.getByTestId('directory-table').getByRole('link', { name }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);
}

/** Invites an employee, accepts, sets the password and links the access to the employee record. */
async function inviteAndLink(
  page: Page,
  browser: Browser,
  bearer: () => string,
  email: string,
  employee: string,
  key: string,
) {
  expect(
    (
      await post(page, bearer, '/invitations', `inv-${key}`, {
        email,
        role: 'employee',
        locale: 'fr',
      })
    ).status(),
  ).toBe(201);
  const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
    await mailTo(email, /Invitation à rejoindre/u),
  );
  expect(link, 'invitation link').not.toBeNull();
  const accepted = await page.request.post(`${CORE_API}/public/invitations/accept`, {
    data: { token: link?.[1] ?? '' },
  });
  expect(accepted.status()).toBe(200);
  const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
    await mailTo(email, /mot de passe|password|actions|compte|account/iu),
  );
  expect(setup, 'password setup link').not.toBeNull();
  const context = await browser.newContext({ locale: 'fr-FR' });
  const setupPage = await context.newPage();
  await openSecretLink(setupPage, setup?.[0] ?? '');
  const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
  if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
    await proceed.click();
  }
  await typeSecret(setupPage, '#password-new', PASSWORD);
  await typeSecret(setupPage, '#password-confirm', PASSWORD);
  await setupPage.locator('[type="submit"]').first().click();
  await context.close();

  const lookup = await page.request.post(`${CORE_API}/employees/${employee}/access-link/lookup`, {
    headers: { Authorization: bearer() },
    data: { email },
  });
  expect(lookup.status()).toBe(200);
  const membershipId = ((await lookup.json()) as { membershipId: string }).membershipId;
  expect(
    (
      await post(page, bearer, `/employees/${employee}/access-link`, `link-${key}`, {
        membershipId,
      })
    ).status(),
  ).toBe(201);
}

test.describe.serial('MVP-030: create and acknowledge a contract', () => {
  let contractId = '';

  test('setup: two employees, each with an invited and linked employee access', async ({
    page,
    browser,
  }) => {
    test.setTimeout(240_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Contrat Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const legalEntityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: `Site Gombe ${stamp}`,
      timezone: 'Africa/Kinshasa',
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    const siteId = ((await site.json()) as { id: string }).id;
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId,
          code: DEPARTMENT,
          name: `Ressources ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const start = plusDays(today(), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${OWNER};Bénédicte;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${OTHER};Jean-Pierre;Mukendi;${start};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-ctr-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ctr-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    await inviteAndLink(page, browser, bearer, OWNER_EMAIL, employeeId(OWNER), 'owner');
    await inviteAndLink(page, browser, bearer, OTHER_EMAIL, employeeId(OTHER), 'other');
  });

  test('the administrator writes, checks and approves a French template', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'fr');
    await primaryNav(page).getByRole('link', { name: 'Modèles de contrat', exact: true }).click();
    const create = page.getByTestId('create-template');
    await create.getByLabel('Code').fill(TEMPLATE_CODE);
    await create.getByLabel('Nom').fill(TEMPLATE_NAME);
    await create.getByLabel('Type de contrat').selectOption('PERMANENT');
    await create.getByRole('button', { name: 'Créer le modèle' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(TEMPLATE_NAME);
    await expect(page.getByTestId('announcer')).toHaveText('Le modèle a été créé.');

    await page.getByRole('button', { name: 'Rédiger un nouveau brouillon' }).click();
    const editor = page.getByTestId('editor');
    await expect(editor.getByRole('heading')).toBeFocused();
    await editor.getByLabel('Titre').fill('Contrat de travail');
    const body = editor.getByLabel('Texte', { exact: true });
    // A web address is refused with a closed reason and its line, never the text.
    await body.fill('# Article 1 : Engagement\nVoir www.exemple.cd pour le règlement.');
    await editor.getByRole('button', { name: 'Vérifier le texte' }).click();
    await expect(editor.getByTestId('validation')).toContainText(
      'Ligne 2 : les adresses web ne sont pas autorisées.',
    );
    await body.fill('# Article 1 : Engagement\nEntre ');
    await editor.getByLabel('Champ à insérer').selectOption('organization.name');
    await editor.getByRole('button', { name: 'Insérer le champ' }).click();
    // The page moves the caret after the field on the next frame; type only once it has.
    await expect(body).toBeFocused();
    await body.press('End');
    await body.pressSequentially(' et ');
    await editor.getByLabel('Champ à insérer').selectOption('employee.fullName');
    await editor.getByRole('button', { name: 'Insérer le champ' }).click();
    // The page moves the caret after the field on the next frame; type only once it has.
    await expect(body).toBeFocused();
    await body.press('End');
    await body.pressSequentially('.\n- Début : ');
    await editor.getByLabel('Champ à insérer').selectOption('contract.startDate');
    await editor.getByRole('button', { name: 'Insérer le champ' }).click();
    // The page moves the caret after the field on the next frame; type only once it has.
    await expect(body).toBeFocused();
    await editor.getByRole('button', { name: 'Vérifier le texte' }).click();
    await expect(editor.getByTestId('validation')).toHaveText(
      'Le texte respecte le format des contrats.',
    );
    await expectAccessible(page);
    await editor.getByRole('button', { name: 'Enregistrer le brouillon' }).click();
    await expect(page.getByTestId('announcer')).toHaveText('Le brouillon a été enregistré.');

    const version = page.getByTestId('version').first();
    await version.getByRole('button', { name: 'Afficher le texte' }).click();
    const text = version.getByTestId('version-text');
    await expect(text).toContainText('[Nom complet]');
    await text.getByRole('button', { name: 'Approuver cette version' }).click();
    await expect(text.getByRole('alert')).toHaveText('Cochez d’abord la confirmation.');
    await text
      .getByLabel('J’ai vérifié ce texte ; DivalHR n’en garantit pas la validité juridique.')
      .check();
    await text.getByRole('button', { name: 'Approuver cette version' }).click();
    await expect(page.getByTestId('announcer')).toHaveText('La version a été approuvée.');
    await expect(page.getByRole('heading', { level: 1 })).toBeFocused();
    await expect(page.getByTestId('version').first()).toContainText('Approuvée');
  });

  test('the administrator previews and issues the contract from the profile', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'fr');
    await openProfile(page, OWNER, `Bénédicte ${FAMILY}`);
    const section = page.getByTestId('contracts');
    await expect(section.getByTestId('contracts-none')).toHaveText('Aucun contrat émis.');
    const form = section.getByTestId('issue-form');
    await form
      .getByLabel('Version de modèle approuvée')
      .selectOption({ label: `${TEMPLATE_NAME} (${TEMPLATE_CODE}) – Français version 1` });
    await form.getByLabel('Date de début du contrat').fill(today());
    await form.getByRole('button', { name: 'Prévisualiser le contrat' }).click();
    const preview = section.getByTestId('contract-preview');
    await expect(preview.getByRole('heading', { name: 'Aperçu du contrat' })).toBeFocused();
    const contractText = preview.getByTestId('contract-document');
    await expect(contractText).toHaveAttribute('lang', 'fr');
    await expect(contractText).toContainText(`Bénédicte ${FAMILY}`);
    await expect(contractText).not.toContainText('{{');
    await expectAccessible(page);
    await page.setViewportSize({ width: 320, height: 800 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.setViewportSize({ width: 1280, height: 800 });
    await preview.getByRole('button', { name: 'Confirmer et émettre le contrat' }).click();
    await expect(page.getByTestId('announcer')).toHaveText('Le contrat a été émis.');
    await expect(section.getByTestId('contract-row')).toHaveAttribute('data-state', 'ISSUED');
    contractId = sql(
      `SELECT id FROM documents.contract WHERE tenant_id = '${TENANT_A}'
       AND employee_id = '${employeeId(OWNER)}'`,
    );
    expect(page.url()).not.toMatch(/Bénédicte|Kabasele|CTR-/u);
  });

  test('the employee reads and acknowledges receipt; the evidence is bound', async ({
    page,
    browser,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, [OWNER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mes contrats', exact: true }).click();
    await expect(page.getByTestId('my-contract-state')).toHaveText(
      'Émis – en attente d’accusé de réception',
    );
    await page.getByRole('link', { name: 'Durée indéterminée' }).click();
    await expect(page.getByTestId('contract-document')).toContainText(`Bénédicte ${FAMILY}`);
    const panel = page.getByTestId('acknowledge');
    await expect(panel).toContainText('Ce n’est pas une signature électronique.');
    await expectAccessible(page);
    await panel.getByRole('button', { name: 'Accuser réception' }).click();
    await expect(panel.getByRole('alert')).toHaveText('Cochez d’abord la déclaration.');
    await panel
      .getByLabel(
        'Je confirme avoir reçu ce contrat et en avoir pris connaissance tel qu’il est affiché. Cette confirmation n’est pas une signature électronique.',
      )
      .check();
    await panel.getByRole('button', { name: 'Accuser réception' }).click();
    const evidence = page.getByTestId('evidence');
    await expect(evidence.getByRole('heading', { name: 'Accusé de réception' })).toBeFocused();
    await expect(page.getByTestId('announcer')).toHaveText('Réception confirmée');
    await expect(page.getByTestId('my-contract-state')).toHaveText('Réception confirmée');
    await expectAccessible(page);
    expect(page.url()).toMatch(/\/me\/contracts\/[0-9a-f-]{36}$/u);
    expect(await storage(page)).not.toMatch(/Bénédicte|Kabasele|CTR-|contrat\./iu);

    // The evidence names the employee's membership and active link, never a client value.
    const row = sql(
      `SELECT a.statement_locale || ';' || (a.snapshot_sha256 = c.snapshot_sha256)::text
       FROM documents.contract_acknowledgement a JOIN documents.contract c
       ON c.tenant_id = a.tenant_id AND c.id = a.contract_id WHERE a.contract_id = '${contractId}'`,
    );
    expect(row).toBe('fr;true');

    // Another employee of the tenant: the same 404 as an unknown contract.
    const other = await browser.newContext({ locale: 'fr-FR' });
    const otherPage = await other.newPage();
    const otherBearer = await signIn(otherPage, [OTHER_EMAIL, PASSWORD], 'fr', undefined);
    const foreign = await otherPage.request.get(`${CORE_API}/me/contracts/${contractId}`, {
      headers: { Authorization: otherBearer() },
    });
    expect(foreign.status()).toBe(404);
    await other.close();
  });

  test('the administrator sees the evidence; tenant B cannot; a voided contract stays unacknowledged', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    await openProfile(page, OWNER, `Bénédicte ${FAMILY}`);
    const section = page.getByTestId('contracts');
    await section.getByRole('button', { name: 'Durée indéterminée' }).click();
    const detail = section.getByTestId('contract-detail');
    await expect(detail.getByTestId('contract-state')).toHaveText('Réception confirmée');
    await expect(detail.getByTestId('evidence')).toContainText('Réception confirmée le');
    await expect(detail.getByTestId('void')).toHaveCount(0);

    // Tenant B's administrator: 404.
    const b = await browser.newContext({ locale: 'fr-FR' });
    const bPage = await b.newPage();
    const bBearer = await signIn(bPage, USERS.adminB, 'fr');
    const crossTenant = await bPage.request.get(
      `${CORE_API}/employees/${employeeId(OWNER)}/contracts/${contractId}`,
      { headers: { Authorization: bBearer() } },
    );
    expect(crossTenant.status()).toBe(404);
    await b.close();

    // Issue to the other employee, then void it from the profile.
    const other = employeeId(OTHER);
    const versionId = sql(
      `SELECT v.id FROM documents.contract_template_version v JOIN documents.contract_template t
       ON t.tenant_id = v.tenant_id AND t.id = v.template_id
       WHERE t.tenant_id = '${TENANT_A}' AND t.code = '${TEMPLATE_CODE}' AND v.state = 'APPROVED'`,
    );
    const command = { templateVersionId: versionId, startDate: today(), endDate: null };
    const previewed = await page.request.post(`${CORE_API}/employees/${other}/contracts/preview`, {
      headers: { Authorization: bearer() },
      data: command,
    });
    expect(previewed.status()).toBe(200);
    const preview = (await previewed.json()) as {
      employmentVersion: number;
      previewDigest: string;
    };
    expect(
      (
        await post(page, bearer, `/employees/${other}/contracts`, 'issue-other', {
          ...command,
          expectedEmploymentVersion: preview.employmentVersion,
          previewDigest: preview.previewDigest,
        })
      ).status(),
    ).toBe(201);
    await openProfile(page, OTHER, 'Jean-Pierre Mukendi');
    const otherSection = page.getByTestId('contracts');
    await otherSection.getByRole('button', { name: 'Durée indéterminée' }).click();
    const voiding = otherSection.getByTestId('void');
    await voiding.getByLabel('Motif').selectOption('ISSUED_IN_ERROR');
    await voiding.getByRole('button', { name: 'Confirmer l’annulation' }).click();
    await expect(page.getByTestId('announcer')).toHaveText('Le contrat a été annulé.');
    await expect(otherSection.getByTestId('contract-state')).toHaveText('Annulé');

    const employee = await browser.newContext({ locale: 'fr-FR' });
    const employeePage = await employee.newPage();
    await signIn(employeePage, [OTHER_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(employeePage).getByRole('link', { name: 'Mes contrats', exact: true }).click();
    await employeePage.getByRole('link', { name: 'Durée indéterminée' }).click();
    await expect(employeePage.getByTestId('voided')).toHaveText(
      'Ce contrat a été annulé : sa réception ne peut pas être confirmée.',
    );
    await expect(employeePage.getByTestId('acknowledge')).toHaveCount(0);
    await employee.close();

    // Audit metadata and outbox data carry identifiers, versions and closed states only.
    const metadata = sql(
      `SELECT coalesce(string_agg(metadata::text, ' '), '') FROM platform.audit_event
       WHERE tenant_id = '${TENANT_A}' AND action LIKE 'contract%'`,
    );
    expect(metadata).not.toMatch(
      /Bénédicte|Kabasele|Mukendi|Contrat de travail|\d{4}-\d{2}-\d{2}/u,
    );
    const published = sql(
      `SELECT coalesce(string_agg((envelope->'data')::text, ' '), '') FROM platform.outbox_event
       WHERE tenant_id = '${TENANT_A}' AND event_type LIKE 'documents.%'`,
    );
    expect(published).toContain(contractId);
    expect(published).not.toMatch(/Bénédicte|Kabasele|Contrat|PERMANENT|\d{4}-\d{2}-\d{2}/u);
  });
});

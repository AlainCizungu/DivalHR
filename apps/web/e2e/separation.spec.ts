import { expect, test, type Page } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, mailTo, signIn, sql } from './support';

// MVP-022 (Issue #49): separate an employee with the real identity provider, extension, database
// and jobs. An invited employee signs in; the administrator links the employee record to that
// access and records an immediate separation with a direct report. The Core denies the employee at
// the next request, the job removes the sign-in in Keycloak (the account is disabled), and the
// access review labels the membership REVOKED. A scheduled separation is then recorded, its
// checklist updated and cancelled. Nothing personal reaches the URL, browser storage, audit
// metadata or outbox data. An employee has neither the section nor the API.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const ENTITY = `SE-${stamp}`;
const SITE = `SS-${stamp}`;
const DEPARTMENT = `SD-${stamp}`;
const LEAVER = `SEP-${stamp}-1`;
const REPORT = `SEP-${stamp}-2`;
const STAYER = `SEP-${stamp}-3`;
const FAMILY = `Tshisekedi${lower.replace(/[^a-z]/gu, '')}`;
const EMAIL = `e2e.sortie.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Sortie!`;

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sep-${key}-${stamp}-0001` },
    data,
  });
}

async function openProfile(page: Page, number: string, name: string, label: string) {
  await page.getByRole('link', { name: label, exact: true }).click();
  await page
    .getByLabel(label === 'Employés' ? 'Matricule ou nom' : 'Employee number or name')
    .fill(number);
  await page.keyboard.press('Enter');
  await page.getByTestId('directory-table').getByRole('link', { name }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(name);
}

async function sessionRoles(page: Page, bearer: string): Promise<string[]> {
  const response = await page.request.get(`${CORE_API}/session`, {
    headers: { Authorization: bearer },
  });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { roles: string[] }).roles;
}

test.describe.serial('MVP-022: separate an employee', () => {
  let employeeBearer = '';

  test('setup: three employees, a reporting line and an invited employee who signs in', async ({
    page,
    browser,
  }) => {
    test.setTimeout(180_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Sortie Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const legalEntityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: `Sortie Site ${stamp}`,
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
      `${LEAVER};Élodie;${FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${REPORT};Jean-Pierre;Mukendi;${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${STAYER};Bénédicte;Ilunga;${start};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-sep-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sep-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);

    // The report reports to the leaver from ten days ago.
    const report = employeeId(REPORT);
    const command = {
      type: 'CHANGE',
      effectiveFrom: plusDays(today(), -10),
      placement: null,
      manager: { employeeId: employeeId(LEAVER) },
      contractClassification: null,
      compensationBasis: null,
      reasonCode: 'LATE_NOTIFICATION',
      correctsAssignmentId: null,
    };
    const previewed = await page.request.post(
      `${CORE_API}/employees/${report}/employment-changes/preview`,
      { headers: { Authorization: bearer() }, data: command },
    );
    expect(previewed.status()).toBe(200);
    const preview = (await previewed.json()) as { expectedVersion: number; previewDigest: string };
    expect(
      (
        await post(page, bearer, `/employees/${report}/employment-changes`, 'mgr', {
          ...command,
          expectedVersion: preview.expectedVersion,
          previewDigest: preview.previewDigest,
          acknowledgeRetroactive: true,
        })
      ).status(),
    ).toBe(201);

    // The leaver is invited as an employee, accepts and chooses a password.
    expect(
      (
        await post(page, bearer, '/invitations', 'inv', {
          email: EMAIL,
          role: 'employee',
          locale: 'fr',
        })
      ).status(),
    ).toBe(201);
    const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
      await mailTo(EMAIL, /Invitation à rejoindre/u),
    );
    expect(link, 'invitation link').not.toBeNull();
    const accepted = await page.request.post(`${CORE_API}/public/invitations/accept`, {
      data: { token: link?.[1] ?? '' },
    });
    expect(accepted.status()).toBe(200);
    const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
      await mailTo(EMAIL, /mot de passe|password|actions|compte|account/iu),
    );
    expect(setup, 'password setup link').not.toBeNull();
    const context = await browser.newContext({ locale: 'fr-FR' });
    const setupPage = await context.newPage();
    await setupPage.goto(setup?.[0] ?? '');
    const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
    if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
      await proceed.click();
    }
    await setupPage.locator('#password-new').fill(PASSWORD);
    await setupPage.locator('#password-confirm').fill(PASSWORD);
    await setupPage.locator('[type="submit"]').first().click();
    await context.close();

    const session = await browser.newContext({ locale: 'fr-FR' });
    const invitee = await session.newPage();
    const inviteeBearer = await signIn(invitee, [EMAIL, PASSWORD], 'fr', undefined);
    employeeBearer = inviteeBearer();
    expect(await sessionRoles(invitee, employeeBearer)).toEqual(['employee']);
    await session.close();
  });

  test('an immediate separation denies access at once and the job removes the sign-in', async ({
    page,
    browser,
  }) => {
    test.setTimeout(300_000);
    await signIn(page, USERS.adminA, 'fr');
    await openProfile(page, LEAVER, `Élodie ${FAMILY}`, 'Employés');
    const leaver = employeeId(LEAVER);

    // Link the employee record to the invited access by its exact address.
    const access = page.getByTestId('access-link');
    await expect(access.getByTestId('access-link-state')).toHaveAttribute(
      'data-state',
      'NOT_LINKED',
    );
    await access.getByLabel('Adresse e-mail exacte du membre').fill(EMAIL);
    await access.getByRole('button', { name: 'Trouver l’accès' }).click();
    await access.getByRole('button', { name: 'Lier à cet employé' }).click();
    await expect(access.getByTestId('access-link-state')).toHaveAttribute('data-state', 'ACTIVE');
    await expect(access.getByLabel('Adresse e-mail exacte du membre')).toHaveCount(0);
    expect(page.url()).not.toContain('example');

    // Separate today, immediately, ending the report's reporting line.
    const form = page.getByTestId('separation-form');
    await form.getByLabel('Dernier jour d’emploi').fill(today());
    await form.getByLabel('Motif').selectOption('MUTUAL_AGREEMENT');
    await form.getByLabel('Immédiatement, à l’enregistrement de la sortie').check();
    await form.getByLabel('Mettre fin au rattachement').check();
    await form.getByRole('button', { name: 'Prévisualiser la sortie' }).click();
    const preview = form.getByTestId('separation-preview');
    await expect(preview.getByRole('heading')).toBeFocused();
    await expect(preview.getByTestId('separation-reports')).toContainText('Jean-Pierre Mukendi');
    await expect(preview.getByTestId('separation-access')).toHaveAttribute('data-status', 'LINKED');
    await expectAccessible(page);
    await page.setViewportSize({ width: 320, height: 800 });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.setViewportSize({ width: 1280, height: 800 });
    await preview
      .getByLabel('Je confirme que l’accès DivalHR de cet employé prend fin immédiatement.')
      .check();
    await preview.getByRole('button', { name: 'Confirmer et enregistrer la sortie' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Sortie enregistrée');
    const separation = page.getByTestId('separation');
    // Employment ends after today; DivalHR access has already ended, so it can no longer be
    // cancelled.
    await expect(separation).toHaveAttribute('data-state', 'SCHEDULED');
    await expect(separation.getByTestId('separation-access-state')).toHaveAttribute(
      'data-access',
      /^(SIGN_OUT_PENDING|COMPLETED)$/u,
    );
    await expect(separation.getByRole('button', { name: 'Annuler la sortie' })).toHaveCount(0);
    await expect(page.getByTestId('separation-form')).toHaveCount(0);

    // The Core denies the employee at the next request, before any identity-provider call.
    const check = await browser.newContext();
    const probe = await check.newPage();
    expect(await sessionRoles(probe, employeeBearer)).toEqual([]);

    // The job removes the sign-in: the revocation completes and the account is disabled.
    await expect
      .poll(
        () =>
          sql(
            `SELECT state FROM identity.access_revocation
             WHERE tenant_id = '${TENANT_A}' AND employee_id = '${leaver}'`,
          ),
        { timeout: 240_000, intervals: [5_000] },
      )
      .toBe('COMPLETED');
    await probe.goto('/');
    await probe.getByRole('button', { name: 'Français', exact: true }).click();
    await probe.getByRole('main').getByRole('button', { name: 'Se connecter' }).click();
    await probe.locator('#username').fill(EMAIL);
    await probe.locator('#password').fill(PASSWORD);
    await probe.locator('#kc-login').click();
    await expect(
      probe.locator('#input-error, .kc-feedback-text, [role="alert"]').first(),
    ).toBeVisible();
    expect(new URL(probe.url()).pathname).not.toBe('/');
    await check.close();

    // The profile shows the completed removal; the access review labels the membership REVOKED.
    await page.getByRole('link', { name: 'Employés', exact: true }).click();
    await openProfile(page, LEAVER, `Élodie ${FAMILY}`, 'Employés');
    await expect(page.getByTestId('separation-access-state')).toHaveAttribute(
      'data-access',
      'COMPLETED',
    );
    await expect(page.getByTestId('access-link-state')).toHaveAttribute('data-state', 'REVOKED');
    await page.getByRole('link', { name: 'Revue des accès' }).click();
    await page.getByLabel('Adresse e-mail exacte').fill(EMAIL);
    await page.getByRole('button', { name: 'Rechercher', exact: true }).click();
    await expect(page.getByTestId('review-access')).toHaveAttribute('data-access', 'REVOKED');

    // Nothing personal in browser storage; audit and outbox carry identifiers and counts only.
    expect(await storage(page)).not.toMatch(/Élodie|Tshisekedi|SEP-|Mukendi|sortie\./iu);
    const evidence = sql(
      `SELECT coalesce(string_agg(metadata::text || ' ' || coalesce(after_state_sha256, ''), ' '), '')
       FROM platform.audit_event
       WHERE tenant_id = '${TENANT_A}'
         AND (action LIKE 'employee-separation.%' OR action LIKE 'employee-access-link.%'
              OR action LIKE 'access-revocation.%' OR resource_id = '${leaver}')`,
    );
    expect(evidence).not.toMatch(/Élodie|Tshisekedi|SEP-|MUTUAL_AGREEMENT|example\.test/iu);
    expect(evidence).not.toContain(today());
    const published = sql(
      `SELECT coalesce(string_agg((envelope->'data')::text, ' '), '') FROM platform.outbox_event
       WHERE tenant_id = '${TENANT_A}'
         AND (event_type LIKE 'people.employment.separation-%' OR event_type LIKE 'identity.%access%')
         AND envelope::text LIKE '%${leaver}%'`,
    );
    expect(published).toContain(leaver);
    expect(published).not.toMatch(/MUTUAL_AGREEMENT|example\.test|Tshisekedi/u);
  });

  test('a scheduled separation in English: checklist, then cancellation', async ({ page }) => {
    await signIn(page, USERS.adminA, 'en');
    await openProfile(page, STAYER, 'Bénédicte Ilunga', 'Employees');
    const form = page.getByTestId('separation-form');
    await form.getByLabel('Last day of employment').fill(plusDays(today(), 20));
    await expect(form.getByLabel('Immediately, when the separation is recorded')).toHaveCount(0);
    await form.getByLabel('Reason').selectOption('END_OF_FIXED_TERM');
    await form.getByRole('button', { name: 'Preview the separation' }).focus();
    await page.keyboard.press('Enter');
    const preview = form.getByTestId('separation-preview');
    await expect(preview.getByTestId('separation-access')).toHaveAttribute(
      'data-status',
      'NOT_LINKED',
    );
    await preview
      .getByLabel(
        'I confirm that no DivalHR access is linked to this employee, so none is revoked.',
      )
      .check();
    await preview.getByRole('button', { name: 'Confirm and record the separation' }).click();
    await expect(page.getByTestId('announcer')).toContainText('Separation recorded');
    const separation = page.getByTestId('separation');
    await expect(separation).toHaveAttribute('data-state', 'SCHEDULED');
    await separation
      .getByLabel('Status: Recover assigned equipment')
      .selectOption('NOT_APPLICABLE');
    await expect(page.getByTestId('announcer')).toContainText('Not applicable');
    await expectAccessible(page);

    await separation.getByRole('button', { name: 'Cancel the separation' }).click();
    const panel = page.getByTestId('separation-cancel-panel');
    await expect(panel.getByRole('heading')).toBeFocused();
    await panel.getByRole('button', { name: 'Confirm the cancellation' }).click();
    await expect(page.getByTestId('announcer')).toContainText('was cancelled');
    await expect(page.getByTestId('separation')).toHaveAttribute('data-state', 'CANCELLED');
    await expect(page.getByTestId('separation-form')).toBeVisible();
    await expect(page.getByTestId('profile-summary')).toContainText('Employed');
  });

  test('an employee has neither the section nor the API', async ({ page }) => {
    const bearer = await signIn(page, USERS.employeeA, 'en', undefined);
    const stayer = employeeId(STAYER);
    for (const path of [`/employees/${stayer}/separations`, `/employees/${stayer}/access-link`]) {
      const response = await page.request.get(`${CORE_API}${path}`, {
        headers: { Authorization: bearer() },
      });
      expect(response.status()).toBe(403);
    }
    const created = await page.request.post(`${CORE_API}/employees/${stayer}/separations/preview`, {
      headers: { Authorization: bearer() },
      data: { lastDay: today(), reasonCode: 'RESIGNATION', accessTiming: 'IMMEDIATELY' },
    });
    expect(created.status()).toBe(403);
  });
});

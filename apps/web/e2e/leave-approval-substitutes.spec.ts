import { expect, test, type Browser, type Page } from '@playwright/test';
import {
  CORE_API,
  acceptInvitation,
  TENANT_A,
  USERS,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  sql,
  typeSecret,
} from './support';

// MVP-041G (Issue #97). Setup (API, the administrator's existing SSO session): an employee reports
// to a manager; an unrelated manager has a report of their own; the requester, the manager, the
// substitute and the unrelated manager have invited and linked employee access; one policy routes
// approval to the manager. In « Remplaçants pour approbation des congés » the administrator appoints
// the substitute for the manager in French (directory search, review, confirmation). The requester
// submits three requests (API). The manager and the substitute both see them, the substitute with
// « Remplacement »; the unrelated manager does not. The substitute approves one in French after a
// confirmation that says it is taken as substitute: it leaves both inboxes, the manager's own
// decision is then refused, and the requester sees the outcome and reason without any approver
// identity, in French and English. Revoking the appointment removes the undecided work from the
// substitute at once while the manager keeps it; expired and scheduled appointments of the manager
// give the substitute nothing. One interactive sign-in per invited employee; races are covered by
// the Core tests. axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `GE-${stamp}`;
const SITE = `GS-${stamp}`;
const DEPARTMENT = `GD-${stamp}`;
const REQUESTER = `GSR-${stamp}-1`;
const MANAGER = `GSM-${stamp}-2`;
const SUBSTITUTE = `GSS-${stamp}-3`;
const OUTSIDER = `GSO-${stamp}-4`;
const OUTSIDERS = `GSX-${stamp}-5`;
const REQUESTER_FAMILY = `Mpoyi${letters}`;
const MANAGER_FAMILY = `Kabongo${letters}`;
const SUBSTITUTE_FAMILY = `Lusamba${letters}`;
const OUTSIDER_FAMILY = `Nzuzi${letters}`;
const REQUESTER_EMAIL = `e2e.remplace.req.${lower}@example.test`;
const MANAGER_EMAIL = `e2e.remplace.mgr.${lower}@example.test`;
const SUBSTITUTE_EMAIL = `e2e.remplace.sub.${lower}@example.test`;
const OUTSIDER_EMAIL = `e2e.remplace.oth.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Remplace!`;
const MANAGED = `GM-${stamp}`;
const MANAGED_FR = `Congé encadré ${stamp}`;
const MANAGED_EN = `Managed leave ${stamp}`;
const APPOINT_REASON = `Intérim pendant le congé (${stamp})`;
const DECIDE_REASON = `Validé en remplacement (${stamp})`;
const REVOKE_REASON = `Responsable de retour (${stamp})`;

const ids = { policy: '', a: '', b: '', c: '', appointment: '' };

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

async function noOverflow(page: Page) {
  for (const viewport of [
    { width: 390, height: 800 },
    { width: 320, height: 720 },
    // 200 % zoom of a 1280 px window.
    { width: 640, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      `no horizontal overflow at ${viewport.width} px`,
    ).toBe(true);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

async function clean(page: Page) {
  expect(await storage(page)).not.toMatch(
    new RegExp(`${stamp}|${MANAGER_FAMILY}|${SUBSTITUTE_FAMILY}|Intérim|remplacement`, 'iu'),
  );
  expect(page.url()).not.toContain(stamp);
  expect(page.url()).not.toMatch(/Kabongo|Lusamba/iu);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sb-${key}-${stamp}-0001` },
    data,
  });
}

async function inbox(page: Page, bearer: () => string): Promise<string> {
  const response = await page.request.get(`${CORE_API}/me/leave-approvals?limit=50`, {
    headers: { Authorization: bearer() },
  });
  expect(response.status()).toBe(200);
  return response.text();
}

/** Gives the report a manager from the date (employment-change preview, then commit). */
async function manage(
  page: Page,
  bearer: () => string,
  report: string,
  manager: string,
  from: string,
  key: string,
) {
  const change = {
    type: 'CHANGE',
    effectiveFrom: from,
    manager: { employeeId: manager },
    reasonCode: 'LATE_NOTIFICATION',
  };
  const preview = await page.request.post(
    `${CORE_API}/employees/${report}/employment-changes/preview`,
    { headers: { Authorization: bearer() }, data: change },
  );
  expect(preview.status()).toBe(200);
  const previewed = (await preview.json()) as {
    expectedVersion: number;
    previewDigest: string;
    requiresAcknowledgement: boolean;
  };
  expect(
    (
      await post(page, bearer, `/employees/${report}/employment-changes`, key, {
        ...change,
        expectedVersion: previewed.expectedVersion,
        previewDigest: previewed.previewDigest,
        acknowledgeRetroactive: previewed.requiresAcknowledgement,
      })
    ).status(),
  ).toBe(201);
}

/** Invites, accepts, sets the password and links the access to the employee record. */
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
  expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
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
  expect(
    (
      await post(page, bearer, `/employees/${employee}/access-link`, `link-${key}`, {
        membershipId: ((await lookup.json()) as { membershipId: string }).membershipId,
      })
    ).status(),
  ).toBe(201);
}

/** Picks one employee in a picker of the appointment form by their unique employee number. */
async function pick(page: Page, legend: string, number: string) {
  const picker = page.getByRole('group', { name: legend, exact: true });
  await picker.getByLabel('Rechercher par matricule ou par nom').fill(number);
  await picker.getByRole('button', { name: 'Rechercher', exact: true }).click();
  await picker.getByRole('button', { name: new RegExp(`\\(${number}\\)$`, 'u') }).click();
  await expect(picker.getByText(/^Sélection :/u)).toContainText(number);
}

test.describe.serial('MVP-041G: leave approval substitutes', () => {
  test('setup: a manager, a substitute, an unrelated manager and a manager-routed policy', async ({
    page,
    browser,
  }) => {
    test.setTimeout(420_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Remplacements Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId: ((await entity.json()) as { id: string }).id,
      code: SITE,
      name: `Site Remplacements ${stamp}`,
      timezone: 'Africa/Kinshasa',
      effectiveFrom: '2026-01-01',
    });
    expect(site.status()).toBe(201);
    expect(
      (
        await post(page, bearer, '/departments', 'dp', {
          siteId: ((await site.json()) as { id: string }).id,
          code: DEPARTMENT,
          name: `Opérations ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const hired = plusDays(today(), -10);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${REQUESTER};Bénédicte;${REQUESTER_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${MANAGER};Josué;${MANAGER_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${SUBSTITUTE};Grâce;${SUBSTITUTE_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${OUTSIDER};Amani;${OUTSIDER_FAMILY};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
      `${OUTSIDERS};Chance;Bokele${letters};${hired};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-sb-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sb-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 5, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    expect(
      (
        await post(page, bearer, '/leave-policies', 'pol', {
          code: MANAGED,
          names: { en: MANAGED_EN, fr: MANAGED_FR },
          unit: 'DAYS',
          balanceMode: 'UNTRACKED',
          minimumServiceDays: 0,
          approvalRoute: 'MANAGER',
          payrollEffect: 'PAID',
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    await manage(page, bearer, employeeId(REQUESTER), employeeId(MANAGER), hired, 'line-req');
    await manage(page, bearer, employeeId(OUTSIDERS), employeeId(OUTSIDER), hired, 'line-oth');
    await inviteAndLink(page, browser, bearer, REQUESTER_EMAIL, employeeId(REQUESTER), 'req');
    await inviteAndLink(page, browser, bearer, MANAGER_EMAIL, employeeId(MANAGER), 'mgr');
    await inviteAndLink(page, browser, bearer, SUBSTITUTE_EMAIL, employeeId(SUBSTITUTE), 'sub');
    await inviteAndLink(page, browser, bearer, OUTSIDER_EMAIL, employeeId(OUTSIDER), 'oth');
  });

  test('the administrator appoints the substitute in French', async ({ page }) => {
    test.setTimeout(180_000);
    await signIn(page, USERS.adminA, 'fr');
    await primaryNav(page)
      .getByRole('link', { name: 'Remplaçants pour approbation des congés', exact: true })
      .click();
    await expect(
      page.getByRole('heading', { level: 1, name: 'Remplaçants pour approbation des congés' }),
    ).toBeVisible();
    await expect(page.getByTestId('substitute-rules')).toContainText(
      'Le responsable garde tous ses droits',
    );
    const asOf = today();
    await pick(page, 'Responsable', MANAGER);
    await pick(page, 'Remplaçant', SUBSTITUTE);
    await page.getByLabel('Premier jour', { exact: true }).fill(asOf);
    await page.getByLabel('Dernier jour', { exact: true }).fill(plusDays(asOf, 30));
    await page.getByLabel('Motif', { exact: true }).fill(`  ${APPOINT_REASON}  `);
    await expectAccessible(page);
    await page.getByRole('button', { name: 'Vérifier le remplacement' }).click();
    const confirmation = page.getByTestId('create-confirm');
    await expect(confirmation).toContainText(MANAGER_FAMILY);
    await expect(confirmation).toContainText(SUBSTITUTE_FAMILY);
    await expect(confirmation.getByTestId('create-reason')).toHaveText(APPOINT_REASON);
    const confirm = page.getByRole('button', { name: 'Confirmer le remplacement' });
    await expect(confirm).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.click();
    await expect(page.getByTestId('announcer')).toContainText('a été nommé remplaçant');
    const row = page.getByTestId('substitute-row').filter({ hasText: MANAGER_FAMILY });
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('substitute-state')).toHaveText('En cours');
    await expect(row.locator('p[lang="fr"]').first()).toHaveText(APPOINT_REASON);
    ids.appointment = sql(
      `SELECT id FROM people.leave_approval_substitution WHERE tenant_id = '${TENANT_A}'
       AND manager_employee_id = '${employeeId(MANAGER)}'`,
    );
    await expectAccessible(page);
    await noOverflow(page);
    await clean(page);
  });

  test('primary and substitute share the work; the substitute decides; revocation is immediate', async ({
    page,
    browser,
  }) => {
    test.setTimeout(300_000);
    const asOf = today();
    // The requester (one sign-in) submits three manager-routed requests.
    const requesterContext = await browser.newContext({ locale: 'fr-FR' });
    const requesterPage = await requesterContext.newPage();
    const requester = await signIn(requesterPage, [REQUESTER_EMAIL, PASSWORD], 'fr', undefined);
    const policies = await requesterPage.request.get(`${CORE_API}/me/leave-policies?limit=50`, {
      headers: { Authorization: requester() },
    });
    ids.policy =
      ((await policies.json()) as { items: { id: string; code: string }[] }).items.find(
        (item) => item.code === MANAGED,
      )?.id ?? '';
    expect(ids.policy).not.toBe('');
    for (const [key, offset] of [
      ['a', 10],
      ['b', 12],
      ['c', 14],
    ] as const) {
      const made = await post(requesterPage, requester, '/me/leave-requests', `req-${key}`, {
        policyId: ids.policy,
        startDate: plusDays(asOf, offset),
        endDate: plusDays(asOf, offset),
        amount: 1,
      });
      expect(made.status()).toBe(201);
      ids[key] = ((await made.json()) as { id: string }).id;
    }

    // The manager (one sign-in) and the unrelated manager (one sign-in), through the API.
    const managerContext = await browser.newContext({ locale: 'fr-FR' });
    const managerPage = await managerContext.newPage();
    const manager = await signIn(managerPage, [MANAGER_EMAIL, PASSWORD], 'fr', undefined);
    const outsiderContext = await browser.newContext({ locale: 'fr-FR' });
    const outsiderPage = await outsiderContext.newPage();
    const outsider = await signIn(outsiderPage, [OUTSIDER_EMAIL, PASSWORD], 'fr', undefined);
    const primary = await inbox(managerPage, manager);
    for (const id of [ids.a, ids.b, ids.c]) expect(primary).toContain(id);
    expect(primary).not.toContain('"SUBSTITUTE"');
    const unrelated = await inbox(outsiderPage, outsider);
    for (const id of [ids.a, ids.b, ids.c]) expect(unrelated).not.toContain(id);

    // The substitute (this page, one sign-in) sees them marked « Remplacement ».
    const substitute = await signIn(page, [SUBSTITUTE_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page)
      .getByRole('link', { name: 'Approbations de congé', exact: true })
      .click();
    const items = page.getByTestId('approval-item').filter({ hasText: MANAGED_FR });
    await expect(items).toHaveCount(3);
    await expect(items.getByTestId('substitute-capacity')).toHaveCount(3);
    await expect(items.getByTestId('substitute-capacity').first()).toHaveText('Remplacement');
    await expect(page.getByTestId('approval-items')).not.toContainText(APPOINT_REASON);
    await expectAccessible(page);
    await noOverflow(page);
    const delegated = await inbox(page, substitute);
    expect(delegated).not.toContain(employeeId(MANAGER));
    expect(delegated).not.toContain(ids.appointment);

    // The substitute approves the first-day-earliest request in French, as substitute.
    const first = items.filter({ hasText: REQUESTER_FAMILY }).last();
    await first.getByRole('button', { name: /^Approuver la demande de/u }).click();
    const dialog = page.getByTestId('decision-dialog');
    await expect(dialog.getByTestId('dialog-substitute-notice')).toContainText(
      'en tant que remplaçant du responsable',
    );
    await dialog.getByLabel('Motif', { exact: true }).fill(DECIDE_REASON);
    await dialog.getByRole('button', { name: 'Vérifier la décision' }).click();
    await expect(dialog.getByTestId('decision-confirm')).toContainText(
      'en tant que remplaçant du responsable',
    );
    const confirm = dialog.getByRole('button', {
      name: 'Confirmer l’approbation en remplacement',
    });
    await expect(confirm).toBeFocused();
    await expectAccessible(page);
    await noOverflow(page);
    await confirm.click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('announcer')).toContainText('a été approuvée');
    await expect(items).toHaveCount(2);
    await clean(page);
    const decided = sql(
      `SELECT request_id || ' ' || decision_authority FROM people.leave_request_decision
       WHERE tenant_id = '${TENANT_A}' AND manager_employee_id = '${employeeId(SUBSTITUTE)}'
       AND delegated_from_manager_employee_id = '${employeeId(MANAGER)}'`,
    );
    expect(decided).toMatch(/ MANAGER_SUBSTITUTE$/u);
    const approved = decided.split(' ')[0] ?? '';
    expect([ids.a, ids.b, ids.c]).toContain(approved);

    // It left the manager's inbox too; the manager's own decision is now a conflict.
    expect(await inbox(managerPage, manager)).not.toContain(approved);
    const late = await post(
      managerPage,
      manager,
      `/me/leave-approvals/${approved}/decision`,
      'late',
      {
        decision: 'REJECTED',
        reasonLocale: 'fr',
        reason: 'Trop tard.',
      },
    );
    expect(late.status()).toBe(409);
    expect(((await late.json()) as { code: string }).code).toBe('LEAVE_REQUEST_ALREADY_DECIDED');

    // The requester sees the outcome and reason, never an approver or capacity, in either language.
    await primaryNav(requesterPage).getByRole('link', { name: 'Mes congés', exact: true }).click();
    const row = requesterPage.getByTestId('my-request').filter({ hasText: DECIDE_REASON });
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('request-state')).toHaveText('Approuvée');
    const reason = row.getByTestId('request-decision').locator('p[lang="fr"]').first();
    await expect(reason).toHaveText(DECIDE_REASON);
    await expect(requesterPage.getByRole('main')).not.toContainText(SUBSTITUTE_FAMILY);
    await expect(requesterPage.getByRole('main')).not.toContainText(MANAGER_FAMILY);
    await expectAccessible(requesterPage);
    await requesterPage.getByRole('button', { name: 'English', exact: true }).click();
    await expect(row.getByTestId('request-state')).toHaveText('Approved');
    await expect(reason).toHaveText(DECIDE_REASON);
    await expectAccessible(requesterPage);
    const history = await (
      await requesterPage.request.get(`${CORE_API}/me/leave-requests?limit=50`, {
        headers: { Authorization: requester() },
      })
    ).text();
    for (const leak of [
      employeeId(SUBSTITUTE),
      employeeId(MANAGER),
      ids.appointment,
      'SUBSTITUTE',
      'decisionAuthority',
      'decidedBy',
      'delegated',
    ]) {
      expect(history.includes(leak), 'no approver identity or capacity').toBe(false);
    }
    await requesterContext.close();

    // The administrator revokes the appointment (API, existing session): the substitute's
    // undecided delegated work disappears at once; the manager keeps it.
    const adminContext = await browser.newContext({ locale: 'fr-FR' });
    const adminPage = await adminContext.newPage();
    const admin = await signIn(adminPage, USERS.adminA, 'fr');
    const revoked = await post(
      adminPage,
      admin,
      `/leave-approval-substitutions/${ids.appointment}/revocation`,
      'revoke',
      { reasonLocale: 'fr', reason: REVOKE_REASON },
    );
    expect(revoked.status()).toBe(201);
    // Back to the inbox within the application (the tokens stay in memory): it is read again.
    await primaryNav(page).getByRole('link', { name: 'Accueil', exact: true }).click();
    await primaryNav(page)
      .getByRole('link', { name: 'Approbations de congé', exact: true })
      .click();
    await expect(
      page.getByTestId('approval-items').or(page.getByTestId('inbox-empty')),
    ).toBeVisible();
    await expect(page.getByTestId('approval-item').filter({ hasText: MANAGED_FR })).toHaveCount(0);
    const remaining = await inbox(managerPage, manager);
    const undecided = [ids.a, ids.b, ids.c].filter((id) => id !== approved);
    for (const id of undecided) expect(remaining).toContain(id);
    const refused = await post(
      page,
      substitute,
      `/me/leave-approvals/${undecided[0] ?? ''}/decision`,
      'after',
      { decision: 'APPROVED', reasonLocale: 'fr', reason: 'Après la révocation.' },
    );
    expect(refused.status()).toBe(404);
    expect(((await refused.json()) as { code: string; params: object }).params).toEqual({});

    // Expired and scheduled appointments of the manager give the substitute nothing today.
    for (const [key, from, to] of [
      ['expired', plusDays(asOf, -3), plusDays(asOf, -1)],
      ['scheduled', plusDays(asOf, 31), plusDays(asOf, 40)],
    ] as const) {
      const made = await post(adminPage, admin, '/leave-approval-substitutions', key, {
        managerEmployeeId: employeeId(MANAGER),
        substituteEmployeeId: employeeId(SUBSTITUTE),
        effectiveFrom: from,
        effectiveTo: to,
        reasonLocale: 'fr',
        reason: APPOINT_REASON,
      });
      expect(made.status()).toBe(201);
    }
    const after = await inbox(page, substitute);
    for (const id of undecided) expect(after).not.toContain(id);
    await outsiderContext.close();
    await managerContext.close();

    // The administrators' list shows each derived state, the revocation and its reason.
    await primaryNav(adminPage)
      .getByRole('link', { name: 'Remplaçants pour approbation des congés', exact: true })
      .click();
    const rows = adminPage.getByTestId('substitute-row').filter({ hasText: MANAGER_FAMILY });
    await expect(rows).toHaveCount(3);
    await expect(rows.getByTestId('substitute-state')).toHaveText(['Prévu', 'Terminé', 'Révoqué']);
    await expect(
      rows.filter({ has: adminPage.getByTestId('substitute-revocation') }),
    ).toContainText(REVOKE_REASON);
    await expectAccessible(adminPage);
    await noOverflow(adminPage);
    await clean(adminPage);
    await adminContext.close();
  });
});

import { expect, test, type Browser, type Locator, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
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

// MVP-051 (Issue #105): shift substitution with the real identity provider and database.
// Setup (API, the administrator's existing SSO session): four employees are imported at a new site;
// Mia manages Ana, Ben and Cléo from today; a schedule is published with Ana's and Cléo's shifts
// on one future day. Ana, Cléo and Mia get invited, linked employee access.
// In French, Ana asks for a substitute on her shift from « Mon horaire » after an explicit
// confirmation; in English Cléo does the same. In « Remplacements d’horaire » Mia sees both; she
// rejects Cléo's request with a French reason, then, in English, searches her eligible reports,
// chooses Ben and approves Ana's request after a confirmation naming both employees. Ana then sees
// her request approved with Ben's name and no longer sees the shift; Cléo sees the rejection and
// keeps her shift. axe, reflow at 320 px, 390 px and 200 % zoom, browser storage and URLs are
// checked.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '');
const ENTITY = `RE-${stamp}`;
const SITE = `RS-${stamp}`;
const DEPARTMENT = `RD-${stamp}`;
const ANA = `RPA-${stamp}-1`;
const BEN = `RPB-${stamp}-2`;
const MIA = `RPM-${stamp}-3`;
const CLEO = `RPC-${stamp}-4`;
const ANA_FAMILY = `Kalala${letters}`;
const BEN_FAMILY = `Ilunga${letters}`;
const CLEO_FAMILY = `Banza${letters}`;
const ANA_EMAIL = `e2e.rempl.ana.${lower}@example.test`;
const MIA_EMAIL = `e2e.rempl.mia.${lower}@example.test`;
const CLEO_EMAIL = `e2e.rempl.cleo.${lower}@example.test`;
const PASSWORD = `Dev-only-E2E-${stamp}-Rempl!`;
const SITE_NAME = `Site Remplacements ${stamp}`;
const WORK_EN = `Cashier ${letters}`;
const WORK_FR = `Caissière ${letters}`;
const ANA_REASON = `Formation obligatoire à Lubumbashi (${stamp})`;
const CLEO_REASON = `Medical appointment moved (${stamp})`;
const REJECTION = `Effectif insuffisant cette semaine (${stamp})`;
const APPROVAL = `Ben covers the whole shift (${stamp})`;

/**
 * PR evidence (EVIDENCE=1 only): a full-page screenshot written outside the working tree. The
 * pages hold only this run's generated test data.
 */
async function evidence(page: Page, name: string, region?: Locator) {
  const out = process.env.EVIDENCE_DIR;
  if (!process.env.EVIDENCE || !out) return;
  mkdirSync(out, { recursive: true });
  await page.waitForLoadState('networkidle');
  const path = join(out, `${name}.png`);
  if (region) await region.screenshot({ path, animations: 'disabled' });
  else await page.screenshot({ path, fullPage: true, animations: 'disabled' });
}

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

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-rp-${key}-${stamp}-0001` },
    data,
  });
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
  const membershipId = ((await lookup.json()) as { membershipId: string }).membershipId;
  expect(
    (
      await post(page, bearer, `/employees/${employee}/access-link`, `link-${key}`, {
        membershipId,
      })
    ).status(),
  ).toBe(201);
}

/** The report's manager from today on (employment-history API). */
async function manage(page: Page, bearer: () => string, report: string, manager: string) {
  const change = { type: 'CHANGE', effectiveFrom: today(), manager: { employeeId: manager } };
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
      await post(page, bearer, `/employees/${report}/employment-changes`, `mgr-${report}`, {
        ...change,
        expectedVersion: previewed.expectedVersion,
        previewDigest: previewed.previewDigest,
        acknowledgeRetroactive: previewed.requiresAcknowledgement,
      })
    ).status(),
  ).toBe(201);
}

/** Asks for a substitute on the only shift of the page, with the reason, after confirming. */
async function requestSubstitute(page: Page, locale: 'fr' | 'en', reason: string) {
  const fr = locale === 'fr';
  const shift = page.getByTestId('my-shift').filter({ hasText: fr ? WORK_FR : WORK_EN });
  await expect(shift).toHaveCount(1);
  await shift.getByTestId('request-substitute').click();
  const dialog = page.getByTestId('substitution-request-dialog');
  await expect(
    page.getByRole('dialog', { name: fr ? 'Demander un remplacement' : 'Request a substitute' }),
  ).toBeVisible();
  await expect(dialog.getByLabel(fr ? 'Motif' : 'Reason', { exact: true })).toBeFocused();
  await expect(dialog.getByLabel(fr ? 'Langue du motif' : 'Language of the reason')).toHaveValue(
    locale,
  );
  // An empty reason is refused before anything is sent.
  await dialog.getByRole('button', { name: fr ? 'Vérifier' : 'Review' }).click();
  await expect(dialog.getByTestId('substitution-request-problems')).toBeFocused();
  await dialog.getByLabel(fr ? 'Motif' : 'Reason', { exact: true }).fill(reason);
  await dialog.getByRole('button', { name: fr ? 'Vérifier' : 'Review' }).click();
  await expect(dialog.getByTestId('substitution-request-reason')).toHaveText(reason);
  const confirm = dialog.getByRole('button', {
    name: fr ? 'Envoyer la demande' : 'Send the request',
  });
  await expect(confirm).toBeFocused();
  await expectAccessible(page);
  await noOverflow(page);
  if (fr) await evidence(page, 'mvp051-1-request-confirm-fr');
  await confirm.click();
  await expect(dialog).toBeHidden();
  await expect(page.getByTestId('my-schedule-announcer')).toContainText(
    fr ? 'a été envoyée' : 'was sent',
  );
  const mine = page.getByTestId('my-substitution').first();
  await expect(mine.getByTestId('substitution-state')).toHaveText(
    fr ? 'En attente de décision' : 'Waiting for a decision',
  );
  await expect(mine.getByTestId('my-substitution-reason')).toHaveText(reason);
  await expect(shift.getByTestId('request-substitute')).toHaveCount(0);
  await expectAccessible(page);
  if (!fr) await evidence(page, 'mvp051-2-my-requests-en');
}

test.describe.serial('MVP-051: request and decide shift substitutions', () => {
  test('setup: a team, a published schedule and three linked employees', async ({
    page,
    browser,
  }) => {
    test.setTimeout(360_000);
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const entity = await post(page, bearer, '/legal-entities', 'le', {
      code: ENTITY,
      name: `Remplacements Entité ${stamp}`,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
    });
    expect(entity.status()).toBe(201);
    const legalEntityId = ((await entity.json()) as { id: string }).id;
    const site = await post(page, bearer, '/sites', 'st', {
      legalEntityId,
      code: SITE,
      name: SITE_NAME,
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
          name: `Caisse ${stamp}`,
          effectiveFrom: '2026-01-01',
        })
      ).status(),
    ).toBe(201);
    const start = plusDays(today(), -30);
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      `${ANA};Ana;${ANA_FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${BEN};Ben;${BEN_FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${MIA};Mia;Mbuyi;${start};${ENTITY};${SITE};${DEPARTMENT}`,
      `${CLEO};Cléo;${CLEO_FAMILY};${start};${ENTITY};${SITE};${DEPARTMENT}`,
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-rp-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-rp-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 4, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    for (const report of [ANA, BEN, CLEO]) {
      await manage(page, bearer, employeeId(report), employeeId(MIA));
    }

    const day = plusDays(today(), 5);
    const proposal = {
      siteId,
      firstDate: day,
      lastDate: day,
      slots: [
        {
          clientRef: 'ana',
          start: `${day}T08:00`,
          end: `${day}T16:00`,
          labels: { en: WORK_EN, fr: WORK_FR },
          employeeId: employeeId(ANA),
        },
        {
          clientRef: 'cleo',
          start: `${day}T09:00`,
          end: `${day}T13:00`,
          labels: { en: WORK_EN, fr: WORK_FR },
          employeeId: employeeId(CLEO),
        },
      ],
    };
    const preview = await page.request.post(`${CORE_API}/work-schedules/preview`, {
      headers: { Authorization: bearer() },
      data: proposal,
    });
    expect(preview.status()).toBe(200);
    const digest = ((await preview.json()) as { previewDigest: string }).previewDigest;
    expect(
      (
        await post(page, bearer, '/work-schedules', 'pub', {
          ...proposal,
          previewDigest: digest,
          confirm: true,
        })
      ).status(),
    ).toBe(201);

    await inviteAndLink(page, browser, bearer, ANA_EMAIL, employeeId(ANA), 'ana');
    await inviteAndLink(page, browser, bearer, CLEO_EMAIL, employeeId(CLEO), 'cleo');
    await inviteAndLink(page, browser, bearer, MIA_EMAIL, employeeId(MIA), 'mia');
  });

  test('Ana asks for a substitute in French', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, [ANA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Mon horaire');
    await expect(
      page.getByRole('heading', { level: 2, name: 'Mes demandes de remplacement' }),
    ).toBeVisible();
    await requestSubstitute(page, 'fr', ANA_REASON);
    expect(await storage(page)).not.toMatch(new RegExp(`${stamp}|Formation`, 'iu'));
    expect(page.url()).not.toContain(stamp);
  });

  test('Cléo asks for a substitute in English', async ({ page }) => {
    test.setTimeout(120_000);
    await signIn(page, [CLEO_EMAIL, PASSWORD], 'en', undefined);
    await primaryNav(page).getByRole('link', { name: 'My schedule', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('My schedule');
    await requestSubstitute(page, 'en', CLEO_REASON);
  });

  test('Mia rejects Cléo’s request in French and approves Ana’s with Ben in English', async ({
    page,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, [MIA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page)
      .getByRole('link', { name: 'Remplacements d’horaire', exact: true })
      .click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Remplacements d’horaire');
    const items = page.getByTestId('substitution-item');
    await expect(items).toHaveCount(2);
    const cleo = items.filter({ hasText: CLEO_FAMILY });
    await expect(cleo.getByTestId('substitution-item-reason')).toHaveText(CLEO_REASON);
    await expect(cleo.getByTestId('substitution-item-reason')).toHaveAttribute('lang', 'en');
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp051-3-manager-inbox-fr');

    // Rejection in French.
    await cleo.getByRole('button', { name: `Refuser la demande de Cléo ${CLEO_FAMILY}` }).click();
    const dialog = page.getByTestId('substitution-decision-dialog');
    await expect(page.getByRole('dialog', { name: 'Refuser la demande' })).toBeVisible();
    await expect(dialog.getByTestId('substitute-picker')).toHaveCount(0);
    await dialog.getByLabel('Motif', { exact: true }).fill(REJECTION);
    await dialog.getByRole('button', { name: 'Vérifier' }).click();
    await expect(dialog.getByTestId('substitution-decision-confirm')).toContainText(
      `Cléo ${CLEO_FAMILY}`,
    );
    await expectAccessible(page);
    await dialog.getByRole('button', { name: 'Refuser la demande' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('substitution-announcer')).toContainText('a été refusé');
    await expect(items).toHaveCount(1);

    // Approval in English, after searching the eligible reports.
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Shift substitutions');
    const ana = items.filter({ hasText: ANA_FAMILY });
    await ana.getByRole('button', { name: `Choose a substitute for Ana ${ANA_FAMILY}` }).click();
    await expect(page.getByRole('dialog', { name: 'Approve with a substitute' })).toBeVisible();
    await expect(dialog.getByLabel('Name or employee number')).toBeFocused();
    await dialog.getByLabel('Name or employee number').fill(BEN_FAMILY);
    await dialog.getByRole('button', { name: 'Search', exact: true }).click();
    const options = dialog.getByTestId('substitute-option');
    await expect(options).toHaveCount(1);
    await expect(options).toContainText(BEN);
    await expectAccessible(page);
    await options.click();
    await expect(dialog.getByTestId('substitution-replacement')).toContainText(BEN_FAMILY);
    await dialog.getByLabel('Reason', { exact: true }).fill(APPROVAL);
    await dialog.getByRole('button', { name: 'Review' }).click();
    const confirm = dialog.getByTestId('substitution-decision-confirm');
    await expect(confirm).toContainText(`Ben ${BEN_FAMILY}`);
    await expect(confirm).toContainText(`Ana ${ANA_FAMILY}`);
    await expectAccessible(page);
    await noOverflow(page);
    await evidence(page, 'mvp051-4-approval-confirm-en');
    await dialog.getByRole('button', { name: 'Approve the substitution' }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId('substitution-announcer')).toContainText('was approved');
    await expect(page.getByTestId('substitution-inbox-empty')).toBeVisible();
    // The run's own markers only (stamp, synthetic family names, slot labels), never the stamp's
    // bare letter fragment, which can be a single letter of the route itself.
    const markers = new RegExp(
      `${stamp}|${ANA_FAMILY}|${BEN_FAMILY}|${CLEO_FAMILY}|Caissi|Cashier`,
      'iu',
    );
    expect(await storage(page)).not.toMatch(markers);
    expect(page.url()).not.toMatch(markers);
  });

  test('Ana sees the approval with Ben and no longer works the shift; Cléo keeps hers', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, [ANA_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(page).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    const mine = page.getByTestId('my-substitution').first();
    await expect(mine.getByTestId('substitution-state')).toHaveText('Approuvée');
    await expect(mine.getByTestId('my-substitution-substitute')).toContainText(BEN_FAMILY);
    await expect(mine.getByTestId('my-substitution-decision-reason')).toHaveText(APPROVAL);
    await expect(page.getByTestId('my-shift').filter({ hasText: WORK_FR })).toHaveCount(0);
    await expect(page.getByTestId('my-substitutions')).not.toContainText('Mbuyi');
    await expectAccessible(page);
    await evidence(page, 'mvp051-5-approved-history-fr');
    await page.getByRole('button', { name: 'English', exact: true }).click();
    await expect(mine.getByTestId('substitution-state')).toHaveText('Approved');
    await expect(mine.getByTestId('my-substitution-decision-reason')).toHaveText(APPROVAL);
    await expectAccessible(page);
    await noOverflow(page);

    await page.context().clearCookies();
    const other = await page.context().newPage();
    await signIn(other, [CLEO_EMAIL, PASSWORD], 'fr', undefined);
    await primaryNav(other).getByRole('link', { name: 'Mon horaire', exact: true }).click();
    const cleo = other.getByTestId('my-substitution').first();
    await expect(cleo.getByTestId('substitution-state')).toHaveText('Refusée');
    await expect(cleo.getByTestId('my-substitution-decision-reason')).toHaveText(REJECTION);
    await expect(other.getByTestId('my-shift').filter({ hasText: WORK_FR })).toHaveCount(1);
    await expectAccessible(other);
  });

  test('the administrator sees the published employee beside the current coverage', async ({
    page,
  }) => {
    test.setTimeout(120_000);
    await signIn(page, USERS.adminA, 'en');
    await primaryNav(page).getByRole('link', { name: 'Schedules', exact: true }).click();
    const row = page.getByTestId('schedule-row').filter({ hasText: SITE_NAME });
    await row.getByRole('button', { name: /^View the schedule of/u }).click();
    const detail = page.getByTestId('schedule-detail');
    const ana = detail.getByTestId('detail-slot').filter({ hasText: ANA_FAMILY });
    await expect(ana.getByTestId('slot-effective')).toContainText(BEN_FAMILY);
    await expect(ana.getByTestId('slot-substituted')).toContainText('Substitution approved');
    const cleo = detail.getByTestId('detail-slot').filter({ hasText: CLEO_FAMILY });
    await expect(cleo.getByTestId('slot-effective')).toContainText(CLEO_FAMILY);
    await expect(cleo.getByTestId('slot-substituted')).toHaveCount(0);
    await expect(detail).not.toContainText(APPROVAL);
    await expect(detail).not.toContainText(ANA_REASON);
    await expectAccessible(page);
    await evidence(page, 'mvp051-6-admin-detail-en', detail);
  });
});

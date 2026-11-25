import { expect, test, type Browser, type Page } from '@playwright/test';
import { retryOn429 } from './rate-limit';
import {
  CORE_API,
  USERS,
  expectAccessible,
  primaryNav,
  signIn,
  sql,
  sqlScript,
  subjectLock,
} from './support';
import {
  EMPLOYEE_USER,
  WINDOW_MS,
  call,
  linkedEmployee,
  noOverflow,
  organizationCountry,
  organizationToday,
  publishedOffer,
  stamp,
  targetSize,
  textSpacing,
  type Offer,
} from './privacy-support';

// LAUNCH-001 (Issue #226): exactly two journeys, run without retries, with the seeded organization
// administrator, the seeded platform administrators and one synthetic employee created, invited
// (example.test address) and linked by the organization administrator. Every partner and product is
// synthetic and every referral goes only to the in-process SYNTHETIC-TEST provider: no real
// partner, no endpoint and no credential exist anywhere in these journeys. The retention policy
// created in journey 2 is a clearly SYNTHETIC test policy, never a legal period; it is retired
// before the journey ends.
//
// Journey 1 (English): the employee asks for ACCESS to one partner request, the platform privacy
// operations decide, and the employee downloads their own package. Another employee account and the
// organization administrator are refused; the platform sees a masked contact.
//
// Journey 2 (French): the platform creates, reviews and activates a synthetic policy and applies it
// to a pending referral; the employee asks for ERASURE; a legal hold blocks the purge, its release
// makes three data classes eligible, and a previewed, confirmed minimization deletes the disclosed
// values and leaves value-free evidence. Boundaries are checked at 320, 390 and 640 px.
//
// Core refuses more than 20 platform mutations per administrator per hour and these limits are
// never raised: the platform administrator performs about a dozen in journey 2, the platform
// reviewer two in journey 1. Run the file at most once an hour per stack, or restart Core.

test.describe.configure({ retries: 0 });

const COPY = {
  en: {
    mine: 'My privacy requests',
    governance: 'Privacy governance',
    send: 'Send request',
    type: 'Request type',
    referral: 'Partner request',
    download: 'Download my data',
    moveTo: 'Move to',
    save: 'Save decision',
  },
  fr: {
    mine: 'Mes demandes de confidentialité',
    governance: 'Gouvernance de la confidentialité',
    send: 'Envoyer la demande',
    type: 'Type de demande',
    referral: 'Demande partenaire',
    download: 'Télécharger mes données',
    moveTo: 'Passer à',
    save: 'Enregistrer la décision',
  },
} as const;

let releaseSubject: (() => void) | undefined;
test.beforeAll(async () => {
  // Other specs hold this subject for minutes: waiting for it must not count against the hook.
  test.setTimeout(600_000);
  releaseSubject = await subjectLock(USERS.platformAdmin[0]);
});
test.afterAll(() => {
  releaseSubject?.();
});

async function submitReferral(page: Page, bearer: () => string, productName: string) {
  const products = await call<{
    items: { product: { publicReference: string; name: unknown } }[];
  }>(page, bearer, 'GET', '/me/partner-products?limit=50');
  const found = products.items.find((item) =>
    JSON.stringify(item.product.name).includes(productName),
  );
  expect(found, 'the synthetic offer is listed for the employee').toBeDefined();
  const input = {
    productId: found?.product.publicReference,
    language: 'en',
    includePhone: false,
    phone: null,
    includeOrganizationName: false,
  };
  const preview = await call<{
    previewDigest: string;
    expiresAt: string;
    consent: { version: string };
  }>(page, bearer, 'POST', '/me/partner-referrals/preview', { data: input });
  const created = await call<{ referralId: string }>(
    page,
    bearer,
    'POST',
    '/me/partner-referrals',
    {
      data: {
        ...input,
        previewDigest: preview.previewDigest,
        expiresAt: preview.expiresAt,
        consent: { version: preview.consent.version, affirmed: true },
      },
      key: `referral-${productName.replace(/[^A-Za-z0-9]+/gu, '-').slice(0, 40)}`,
      expected: 201,
    },
  );
  return created.referralId;
}

async function employeePage(browser: Browser, locale: 'en' | 'fr') {
  const context = await browser.newContext();
  const page = await context.newPage();
  const bearer = await signIn(page, EMPLOYEE_USER, locale, undefined);
  return { context, page, bearer };
}

async function openMine(page: Page, locale: 'en' | 'fr') {
  await primaryNav(page).getByRole('link', { name: COPY[locale].mine, exact: true }).click();
  await expect(page.getByTestId('my-privacy-page')).toBeVisible();
}

async function openGovernance(page: Page, locale: 'en' | 'fr') {
  await primaryNav(page).getByRole('link', { name: COPY[locale].governance, exact: true }).click();
  await expect(page.getByTestId('platform-privacy-page')).toBeVisible();
}

async function createRequest(page: Page, locale: 'en' | 'fr', type: string, referral: string) {
  const t = COPY[locale];
  await page.getByLabel(t.type, { exact: true }).selectOption(type);
  await page.getByLabel(t.referral, { exact: true }).selectOption(referral);
  await page.getByRole('button', { name: t.send, exact: true }).click();
  const card = page.locator('[data-testid="my-privacy-request"][data-state="SUBMITTED"]');
  await expect(card).toHaveCount(1);
  return (await card.first().getAttribute('data-reference')) ?? '';
}

async function decide(
  page: Page,
  locale: 'en' | 'fr',
  request: string,
  to: string,
  message: string | null,
  note: string | null,
) {
  const t = COPY[locale];
  const card = page.locator(`[data-testid="privacy-request"][data-reference="${request}"]`);
  await expect(card).toBeVisible();
  const form = card.getByTestId('privacy-decision-form');
  await form.getByLabel(t.moveTo, { exact: true }).selectOption(to);
  if (message !== null) await form.getByLabel(/Message|message/u).fill(message);
  if (note !== null) await form.locator('textarea').last().fill(note);
  await form.getByRole('button', { name: t.save, exact: true }).click();
  await expect(card).toHaveAttribute('data-state', to);
}

test('LAUNCH-001 journey 1: the employee asks for access, the platform decides, and only the employee downloads the package', async ({
  browser,
  page,
}) => {
  test.setTimeout(900_000);
  const country = organizationCountry();
  const offer: Offer = {
    name: `Synthetic Privacy Bank ${stamp}`,
    category: 'BANK',
    capability: 'SALARY_ADVANCE',
    productType: 'SALARY_ADVANCE',
    code: `V${stamp.toUpperCase()}A`,
  };
  const bearer = await signIn(page, USERS.platformAdmin, 'en');
  await page.waitForTimeout(WINDOW_MS);
  await publishedOffer(page, bearer, offer, country);
  await page.waitForTimeout(WINDOW_MS);
  await linkedEmployee(browser);

  const { context, page: employee, bearer: employeeBearer } = await employeePage(browser, 'en');
  const referral = await submitReferral(employee, employeeBearer, offer.name);

  // The employee's request, in the employee's own space.
  await openMine(employee, 'en');
  await expectAccessible(employee);
  await targetSize(employee);
  await expect(employee.getByTestId('my-privacy-private')).toBeVisible();
  const request = await createRequest(employee, 'en', 'ACCESS', referral);
  await expect(
    employee.locator(`[data-testid="my-privacy-request"][data-reference="${request}"]`),
  ).toContainText('Submitted');
  await expect(employee.getByTestId('my-privacy-download')).toHaveCount(0);

  // The platform privacy operations see a masked contact and decide; the note stays internal.
  const reviewerContext = await browser.newContext();
  const reviewer = await reviewerContext.newPage();
  await signIn(reviewer, USERS.platformReviewer, 'en');
  await openGovernance(reviewer, 'en');
  const row = reviewer.locator(`[data-testid="privacy-request"][data-reference="${request}"]`);
  await expect(row).toBeVisible();
  await expect(row.getByTestId('privacy-request-contact')).toContainText(/\*|•|…/u);
  await expect(row.getByTestId('privacy-request-contact')).not.toContainText('@example.test');
  await decide(reviewer, 'en', request, 'UNDER_REVIEW', null, null);
  await decide(
    reviewer,
    'en',
    request,
    'APPROVED',
    'Your package is ready to download.',
    'synthetic internal note',
  );
  await expect(reviewer.locator('main')).not.toContainText('synthetic internal note');
  await noOverflow(reviewer);
  await reviewerContext.close();

  // The employee sees the decision (never the note) and downloads their own package.
  await primaryNav(employee).getByRole('link', { name: 'Home', exact: true }).click();
  await openMine(employee, 'en');
  const mine = employee.locator(`[data-testid="my-privacy-request"][data-reference="${request}"]`);
  await expect(mine).toHaveAttribute('data-state', 'APPROVED');
  await expect(mine.getByTestId('my-privacy-message')).toHaveText(
    'Your package is ready to download.',
  );
  await expect(employee.locator('main')).not.toContainText('synthetic internal note');
  const [download] = await Promise.all([
    employee.waitForEvent('download'),
    mine.getByTestId('my-privacy-download').click(),
  ]);
  expect(download.suggestedFilename()).toBe('divalhr-privacy-access-package.json');
  const chunks: Buffer[] = [];
  for await (const chunk of await download.createReadStream()) chunks.push(chunk as Buffer);
  const text = Buffer.concat(chunks).toString('utf8');
  const pkg = JSON.parse(text) as { manifest: { sha256: string }; referrals: unknown[] };
  expect(pkg.manifest.sha256).toMatch(/^[0-9a-f]{64}$/u);
  expect(text).toContain(referral);
  expect(text).not.toContain('synthetic internal note');
  await noOverflow(employee);
  await textSpacing(employee);

  // Another employee account and the organization administrator are refused.
  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  const otherBearer = await signIn(other, USERS.employeeA, 'en');
  for (const path of [
    `/me/privacy/requests/${request}`,
    `/me/privacy/requests/${request}/export`,
  ]) {
    const refused = await retryOn429(
      () => other.request.get(`${CORE_API}${path}`, { headers: { Authorization: otherBearer() } }),
      (ms) => other.waitForTimeout(ms),
    );
    expect(refused.status(), path).not.toBe(200);
    expect(await refused.text()).not.toContain(referral);
  }
  await otherContext.close();
  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  const adminBearer = await signIn(admin, USERS.adminA, 'en');
  await expect(
    primaryNav(admin).getByRole('link', { name: COPY.en.governance, exact: true }),
  ).toHaveCount(0);
  for (const path of ['/platform/privacy/requests', `/platform/privacy/requests/${request}`]) {
    const refused = await admin.request.get(`${CORE_API}${path}`, {
      headers: { Authorization: adminBearer() },
    });
    expect(refused.status(), path).toBe(403);
  }
  const list = await admin.request.get(`${CORE_API}/me/privacy/requests`, {
    headers: { Authorization: adminBearer() },
  });
  expect(await list.text()).not.toContain(request);
  await adminContext.close();
  await context.close();
});

test('LAUNCH-001 journey 2: the platform activates a synthetic policy, a hold blocks the purge, and a confirmed minimization leaves value-free evidence', async ({
  browser,
  page,
}) => {
  test.setTimeout(900_000);
  const country = organizationCountry();
  const offer: Offer = {
    name: `Synthetic Privacy Insurer ${stamp}`,
    category: 'INSURANCE',
    capability: 'HEALTH_INSURANCE',
    productType: 'HEALTH_INSURANCE',
    code: `V${stamp.toUpperCase()}B`,
  };
  const bearer = await signIn(page, USERS.platformAdmin, 'fr');
  await page.waitForTimeout(WINDOW_MS);
  await publishedOffer(page, bearer, offer, country);
  await page.waitForTimeout(WINDOW_MS);
  await linkedEmployee(browser);

  // Only a synthetic test policy may be active: any earlier one for this country is retired first.
  const existing = await call<{
    items: { id: string; countryCode: string; state: string; version: number }[];
  }>(page, bearer, 'GET', '/platform/privacy/policies?limit=50');
  for (const policy of existing.items.filter(
    (p) => p.countryCode === country && p.state === 'ACTIVE',
  )) {
    await call(page, bearer, 'POST', `/platform/privacy/policies/${policy.id}/transition`, {
      data: { expectedVersion: policy.version, toState: 'RETIRED' },
      key: `retire-${policy.id}`,
    });
  }

  // The referral is delivered by the synthetic provider and closed; its closing is moved into the
  // past (fixture only; SET LOCAL ends with the transaction) so a one-day rule has elapsed.
  const { context, page: employee, bearer: employeeBearer } = await employeePage(browser, 'fr');
  const referral = await submitReferral(employee, employeeBearer, offer.name);
  for (let attempt = 1; ; attempt++) {
    const state = sql(
      `SELECT state FROM integrations.partner_referral WHERE reference = '${referral}'`,
    );
    if (state === 'DELIVERED') break;
    expect(attempt, `the synthetic delivery of ${referral}`).toBeLessThan(24);
    await page.waitForTimeout(5_000);
  }
  sqlScript(
    `BEGIN;
     SET LOCAL session_replication_role = replica;
     UPDATE integrations.partner_referral_event SET occurred_at = now() - interval '10 days'
      WHERE to_state IN ('DELIVERED', 'DELIVERY_UNKNOWN', 'WITHDRAWN')
        AND referral_id = (SELECT id FROM integrations.partner_referral WHERE reference = '${referral}');
     COMMIT;`,
  );

  // The employee asks for erasure of this one partner request.
  await openMine(employee, 'fr');
  const request = await createRequest(employee, 'fr', 'ERASURE', referral);

  // The platform creates, reviews and activates a synthetic policy.
  const platform = await (async () => {
    const platformContext = await browser.newContext();
    const platformPage = await platformContext.newPage();
    await signIn(platformPage, USERS.platformAdmin, 'fr');
    await openGovernance(platformPage, 'fr');
    return { platformContext, platformPage };
  })();
  const p = platform.platformPage;
  await expect(p.getByTestId('privacy-framework-notice')).toContainText('pas un avis juridique');
  await expect(p.locator('main')).not.toContainText(/conforme|certifié|compliant|certified/iu);
  const form = p.getByTestId('privacy-policy-form');
  await form.getByLabel('Code pays', { exact: true }).fill(country);
  await form.getByLabel('Version du texte employé', { exact: true }).fill(`synthetic-${stamp}`);
  await form
    .getByLabel('Source examinée', { exact: true })
    .fill('SYNTHETIC test policy, not a legal period');
  await form.getByLabel('En vigueur à partir du', { exact: true }).fill(organizationToday());
  const rules = form.getByTestId('privacy-rule');
  const plan = [
    ['MINIMIZE', '1'],
    ['MINIMIZE', '5'],
    ['MINIMIZE', '1'],
    ['HOLD', ''],
  ] as const;
  for (const [index, [action, days]] of plan.entries()) {
    const rule = rules.nth(index);
    await rule.getByLabel('Action', { exact: true }).selectOption(action);
    await rule.getByLabel('Compté depuis', { exact: true }).selectOption('REFERRAL_CLOSED');
    if (days !== '') await rule.getByLabel('Jours', { exact: true }).fill(days);
  }
  await form.getByRole('button', { name: 'Créer le brouillon', exact: true }).click();
  const policy = p.locator(
    `[data-testid="privacy-policy"][data-country="${country}"][data-state="DRAFT"]`,
  );
  await expect(policy).toHaveCount(1);
  await policy.getByTestId('privacy-policy-checklist').click();
  const checklist = policy.getByTestId('privacy-checklist-form');
  for (const box of await checklist.getByRole('checkbox').all()) await box.check();
  await checklist.getByLabel('Référence de l’examen', { exact: true }).fill('SYNTHETIC-REVIEW-1');
  await checklist.getByTestId('privacy-checklist-save').click();
  await policy.getByTestId('privacy-policy-to-APPROVED').click();
  await expect(policy).toHaveAttribute('data-state', 'APPROVED');
  await policy.getByTestId('privacy-policy-to-ACTIVE').click();
  await expect(policy).toHaveAttribute('data-state', 'ACTIVE');

  // Applying it stamps the pending referral; nothing is purged by this.
  const application = p.getByTestId('privacy-application');
  await application.getByLabel('Code pays', { exact: true }).fill(country);
  await application.getByRole('button', { name: 'Aperçu', exact: true }).click();
  await expect(application.getByTestId('privacy-application-total')).not.toHaveAttribute(
    'data-total',
    '0',
  );
  await application.getByTestId('privacy-application-confirm').click();
  await expect(application.getByTestId('privacy-application-result')).toBeVisible();

  // The request moves to approved; a legal hold then blocks the purge.
  await decide(p, 'fr', request, 'UNDER_REVIEW', null, null);
  await decide(p, 'fr', request, 'APPROVED', 'Votre demande est approuvée.', null);
  const purge = p.getByTestId('privacy-purge');
  await purge.getByLabel('Référence de la demande partenaire', { exact: true }).fill(referral);
  await purge.getByRole('button', { name: 'Placer la conservation', exact: true }).click();
  const hold = purge.locator('[data-testid="privacy-hold"][data-state="ACTIVE"]');
  await expect(hold).toHaveCount(1);
  const purgeForm = purge.getByTestId('privacy-purge-form');
  await purgeForm.getByRole('button', { name: 'Aperçu', exact: true }).click();
  await expect(purge.getByTestId('privacy-purge-eligible')).toHaveAttribute('data-eligible', '0');
  await expect(purge.getByTestId('privacy-purge-preview')).toContainText(
    'Une conservation légale s’applique',
  );
  await expect(purge.getByTestId('privacy-purge-confirm')).toBeDisabled();
  await hold.getByTestId('privacy-hold-release').click();
  await expect(purge.locator('[data-testid="privacy-hold"][data-state="RELEASED"]')).toHaveCount(1);

  // The released referral previews three eligible classes; the confirmation is explicit.
  await purgeForm.getByRole('button', { name: 'Aperçu', exact: true }).click();
  await expect(purge.getByTestId('privacy-purge-eligible')).toHaveAttribute('data-eligible', '3');
  await expect(purge.getByTestId('privacy-purge-confirm')).toBeDisabled();
  await purge.getByRole('checkbox', { name: /irréversible/u }).check();
  await purge.getByTestId('privacy-purge-confirm').click();
  const result = purge.getByTestId('privacy-purge-result');
  await expect(result).toBeVisible();
  await expect(result).not.toContainText('@example.test');

  // The disclosed values are gone and only value-free evidence remains.
  expect(
    sql(
      `SELECT count(*) FROM integrations.partner_referral_disclosure d JOIN integrations.partner_referral r
         ON r.tenant_id = d.tenant_id AND r.id = d.referral_id WHERE r.reference = '${referral}'`,
    ),
  ).toBe('0');
  expect(sql(`SELECT count(*) FROM integrations.privacy_referral_residue`)).not.toBe('0');
  await decide(p, 'fr', request, 'COMPLETED', null, null);
  await noOverflow(p);
  await textSpacing(p);

  // Retiring the synthetic policy leaves no active test policy behind.
  await openGovernance(p, 'fr');
  const active = p.locator(
    `[data-testid="privacy-policy"][data-country="${country}"][data-state="ACTIVE"]`,
  );
  await active.first().getByTestId('privacy-policy-to-RETIRED').click();
  await expect(
    p.locator(`[data-testid="privacy-policy"][data-country="${country}"][data-state="ACTIVE"]`),
  ).toHaveCount(0);
  await platform.platformContext.close();

  // Boundaries: the organization administrator has no entry and no access; the employee cannot reach the platform.
  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  const adminBearer = await signIn(admin, USERS.adminA, 'fr');
  await expect(
    primaryNav(admin).getByRole('link', { name: COPY.fr.governance, exact: true }),
  ).toHaveCount(0);
  for (const path of ['/platform/privacy/policies', '/platform/privacy/requests']) {
    const refused = await admin.request.get(`${CORE_API}${path}`, {
      headers: { Authorization: adminBearer() },
    });
    expect(refused.status(), path).toBe(403);
  }
  await adminContext.close();
  const denied = await employee.request.get(`${CORE_API}/platform/privacy/policies`, {
    headers: { Authorization: employeeBearer() },
  });
  expect(denied.status()).toBe(403);
  await context.close();
});

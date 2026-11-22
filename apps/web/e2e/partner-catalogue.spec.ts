import { expect, test, type APIResponse, type Page } from '@playwright/test';
import {
  CORE_API,
  USERS,
  expectAccessible,
  primaryNav,
  signIn,
  sql,
  subjectLock,
  TENANT_A,
} from './support';
import { retryOn429 } from './rate-limit';

// MVP-120E (Issue #217, ADR 0044): exactly two journeys, run without retries, with the seeded
// platform administrator, the seeded organization administrator and the seeded employee. No
// invitation is created. Every partner, application and product is synthetic (example.test
// addresses, invented names). The approved application is prepared through the public and review
// APIs of MVP-120D; everything MVP-120E adds is exercised through the browser.
//
// Journey 1 (English): an approved application becomes an inactive partner only after an explicit
// confirmation, is activated, gets a bilingual product that is submitted and published, and the
// product then appears, as information only and with no apply control, to the organization
// administrator and to the employee. Landing page and role homes tell the same truth.
//
// Journey 2 (French): the partner lifecycle. Suspension, pause and closure take the product away
// from the catalogue, closure is refused while a product is active, a retired product and a closed
// partner stay out of the catalogue, and the employee and the organization administrator can
// neither reach the platform screens nor read the platform API.
//
// The account command limit is 10 per subject per minute and is never raised: the journeys hold
// the platform administrator's subject lock and wait one full window before their first write.

test.describe.configure({ retries: 0 });

const stamp = Date.now().toString(36);
const CODE = `E2E${stamp.toUpperCase()}`;
const WINDOW_MS = 61_000;

const COPY = {
  en: {
    language: 'English',
    partners: 'Partners',
    catalogue: 'Partner catalogue',
    myCatalogue: 'Benefits & offers',
    draft: 'Draft',
    active: 'Active',
    suspended: 'Suspended',
    closed: 'Closed',
    confirmCreate: 'I confirm that this approved application should become a partner record.',
    boundary: 'It creates no credentials, no connection to the partner',
    informationOnly: 'Information only',
    landingAvailable: 'Approved partner catalogue available.',
    landingLater: 'Enrollment, applications and provider connections are coming later.',
    comingNext: 'Coming next',
    noApplication: 'No application has been submitted.',
    providerDetermines: 'Availability and final terms are determined by the provider.',
    productName: 'Salary advance',
    code: 'Product code',
    type: 'Product type',
    english: 'English',
    french: 'French',
    save: 'Save draft',
    saved: 'Saved.',
    countries: 'Countries where it is offered',
    cdf: 'Congolese franc (CDF)',
  },
  fr: {
    language: 'Français',
    partners: 'Partenaires',
    catalogue: 'Catalogue partenaires',
    myCatalogue: 'Avantages et offres',
    draft: 'Brouillon',
    active: 'Actif',
    suspended: 'Suspendu',
    closed: 'Clôturé',
    confirmCreate: 'Je confirme que cette candidature approuvée doit devenir une fiche partenaire.',
    boundary: 'Elle ne crée ni identifiants, ni connexion avec le partenaire',
    informationOnly: 'Information uniquement',
    landingAvailable: 'Catalogue de partenaires approuvés disponible.',
    landingLater:
      'L’adhésion, les demandes et les connexions aux fournisseurs viendront ultérieurement.',
    comingNext: 'Prochainement',
    noApplication: 'Aucune demande n’a été soumise.',
    providerDetermines:
      'La disponibilité et les conditions définitives sont déterminées par le prestataire.',
    productName: 'Avance sur salaire',
    code: 'Code du produit',
    type: 'Type de produit',
    english: 'Anglais',
    french: 'Français',
    save: 'Enregistrer le brouillon',
    saved: 'Enregistré.',
    countries: 'Pays où il est proposé',
    cdf: 'Franc congolais (CDF)',
  },
} as const;
type Locale = keyof typeof COPY;

let releaseSubject: (() => void) | undefined;
test.beforeAll(async () => {
  // Other specs hold this subject for minutes: waiting for it must not count against the hook.
  test.setTimeout(600_000);
  releaseSubject = await subjectLock(USERS.platformAdmin[0]);
});
test.afterAll(() => {
  releaseSubject?.();
});

/** The seeded organization's own country: the product is offered exactly there. */
function organizationCountry(): string {
  const country = sql(`SELECT country_code FROM tenant.organization WHERE id = '${TENANT_A}'`);
  expect(country, 'the seeded organization has a country').toMatch(/^[A-Z]{2}$/u);
  return country;
}

/** Today in the organization's business time zone, as Core computes it. */
function organizationToday(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization WHERE id = '${TENANT_A}'`,
  );
}

async function call<T = unknown>(
  page: Page,
  bearer: () => string,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  options: { data?: object; key?: string; expected?: number } = {},
): Promise<T> {
  const response: APIResponse = await retryOn429(
    () =>
      page.request.fetch(`${CORE_API}${path}`, {
        method,
        headers: {
          Authorization: bearer(),
          ...(options.key ? { 'Idempotency-Key': `e2e-pc-${options.key}-${stamp}-0001` } : {}),
        },
        data: options.data,
      }),
    (ms) => page.waitForTimeout(ms),
  );
  expect(response.status(), `${method} ${path}`).toBe(options.expected ?? 200);
  return (response.status() === 204 ? undefined : await response.json()) as T;
}

interface Check {
  checkCode: string;
  version: number;
  mandatory: boolean;
}
interface ApplicationDetail {
  version: number;
  checklist: Check[];
}

/** A synthetic application that is approved for onboarding, prepared through the MVP-120D APIs. */
async function approvedApplication(
  page: Page,
  bearer: () => string,
  name: string,
  country: string,
): Promise<string> {
  const receipt = await page.request.post(`${CORE_API}/public/partner-applications`, {
    headers: { 'Idempotency-Key': `e2e-pc-apply-${name.replace(/\W/gu, '')}-${stamp}-0001` },
    data: {
      legalName: name,
      tradingName: null,
      countryOfRegistration: country,
      countriesOfOperation: [country],
      registrationReference: 'SYN-E2E-0120',
      primaryCategory: 'BANK',
      additionalCategories: [],
      requestedCapabilities: ['SALARY_ADVANCE'],
      website: 'https://example.test',
      contactName: 'Test Contact',
      contactRole: 'Head of partnerships',
      businessEmail: `partner.${stamp}@example.test`,
      businessPhone: null,
      supportedLanguages: ['en', 'fr'],
      apiAvailability: 'PLANNED',
      regulatorName: 'Synthetic Regulator',
      licenceReference: 'LIC-E2E-0120',
      serviceSummary: 'A synthetic end-to-end application for the catalogue journey.',
      privacyContactEmail: `partner.privacy.${stamp}@example.test`,
      acknowledgment: true,
      privacyNoticeVersion: 'PARTNER-PRIVACY-1',
      consentVersion: 'PARTNER-APPLICATION-CONSENT-1',
      locale: 'en',
      faxNumber: '',
    },
  });
  expect(receipt.status()).toBe(201);
  const { reference } = (await receipt.json()) as { reference: string };
  const base = `/platform/partner-applications/${reference}`;
  const detail = () => call<ApplicationDetail>(page, bearer, 'GET', base);
  const move = async (toStatus: string, confirmed: boolean) => {
    const current = await detail();
    await call(page, bearer, 'POST', `${base}:transition`, {
      data: { toStatus, expectedVersion: current.version, reasonCode: null, confirmed },
      key: `${toStatus}-${reference}`,
    });
  };
  await move('UNDER_REVIEW', false);
  for (const check of (await detail()).checklist.filter((c) => c.mandatory)) {
    const fresh = (await detail()).checklist.find((c) => c.checkCode === check.checkCode);
    await call(page, bearer, 'PUT', `${base}/checklist/${check.checkCode}`, {
      data: { result: 'SATISFIED', expectedVersion: fresh?.version ?? check.version, note: null },
      key: `check-${check.checkCode}-${reference}`,
    });
  }
  await move('APPROVED_FOR_ONBOARDING', true);
  return reference;
}

function bilingual(en: string, fr: string) {
  return { en, fr };
}

/** The complete, publishable content of the synthetic product. */
function content(country: string, effectiveDate: string) {
  return {
    name: bilingual('Salary advance', 'Avance sur salaire'),
    summary: bilingual('A short synthetic summary.', 'Un court résumé synthétique.'),
    eligibility: bilingual('Open to permanent employees.', 'Ouvert aux employés permanents.'),
    disclosure: bilingual(
      'Synthetic disclosure text of sufficient length.',
      'Texte de mention synthétique de longueur suffisante.',
    ),
    infoUrl: 'https://example.test/information',
    effectiveDate,
    endDate: null,
    minMonths: 1,
    maxMonths: 6,
    countries: [country],
    currencies: [{ currency: 'CDF', minAmount: '10000', maxAmount: '500000' }],
  };
}

async function noOverflow(page: Page) {
  for (const viewport of [
    { width: 390, height: 800 },
    { width: 320, height: 720 },
    { width: 640, height: 400 },
  ]) {
    await page.setViewportSize(viewport);
    const wide = await page.evaluate(() =>
      document.documentElement.scrollWidth <= window.innerWidth
        ? []
        : Array.from(document.body.querySelectorAll('*'))
            .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1)
            .slice(0, 6)
            .map((element) => `${element.tagName.toLowerCase()}[${element.className}]`),
    );
    expect(wide, `no horizontal overflow at ${viewport.width} px`).toEqual([]);
    await expectAccessible(page);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

async function textSpacing(page: Page) {
  await page.evaluate((css) => {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(css);
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  }, '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }');
  for (const width of [320, 390, 640]) {
    await page.setViewportSize({ width, height: 800 });
    const problems = await page.evaluate(() => {
      const found: string[] = [];
      if (document.documentElement.scrollWidth > window.innerWidth) found.push('page overflow');
      for (const element of Array.from(
        document.querySelectorAll('button, label, legend, h1, h2'),
      )) {
        const box = element.getBoundingClientRect();
        if (box.width < 2 || box.height < 2) continue;
        const style = getComputedStyle(element);
        const clips = style.overflow !== 'visible' || style.overflowX !== 'visible';
        if (clips && element.scrollWidth > element.clientWidth + 1) {
          found.push(`${element.tagName.toLowerCase()} clipped`);
        }
      }
      return found;
    });
    expect(problems, `text spacing at ${width} px`).toEqual([]);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

async function targetSize(page: Page) {
  const small = await page.evaluate(() =>
    Array.from(
      document.querySelectorAll(
        'main input:not([tabindex="-1"]):not([type="checkbox"]):not([type="radio"]), main select, main textarea, main button',
      ),
    )
      .map((element) => ({ element, box: element.getBoundingClientRect() }))
      .filter(({ box }) => box.width > 0 && (box.width < 24 || box.height < 24))
      .map(
        ({ element }) => `${element.tagName.toLowerCase()}[${element.getAttribute('name') ?? ''}]`,
      ),
  );
  expect(small).toEqual([]);
}

/** Information only: the three sentences, no control that applies for anything, no internal data. */
async function expectInformationOnly(page: Page, locale: Locale) {
  const t = COPY[locale];
  const notice = page.getByTestId('catalogue-notice');
  await expect(notice).toContainText(t.informationOnly);
  await expect(notice).toContainText(t.noApplication);
  await expect(notice).toContainText(t.providerDetermines);
  await expect(
    page.getByRole('button', {
      name: /apply for|enrol|enroll|request|contact|postuler|demander|inscrire|contacter/iu,
    }),
  ).toHaveCount(0);
  await expect(page.getByRole('link', { name: /apply|postuler/iu })).toHaveCount(0);
  const text = await page.locator('main').innerText();
  expect(text).not.toMatch(/\bP[TAP]-[0-9A-HJKMNP-TV-Z]{12,16}\b/u);
  expect(text).not.toMatch(/@example\.test|LIC-E2E|SYN-E2E|Synthetic Regulator/u);
}

async function openCatalogue(page: Page, locale: Locale, role: 'tenant' | 'employee') {
  const t = COPY[locale];
  await primaryNav(page)
    .getByRole('link', { name: role === 'tenant' ? t.catalogue : t.myCatalogue, exact: true })
    .click();
  await expect(page.getByTestId('catalogue-page')).toHaveAttribute('data-audience', role);
}

/** The catalogue card of this journey's partner (other journeys' products may be listed too). */
const productCard = (page: Page, partnerName: string) =>
  page.locator('[data-testid="catalogue-product"]', { hasText: partnerName });

async function platformDetail(page: Page, locale: Locale, reference: string) {
  await primaryNav(page).getByRole('link', { name: COPY[locale].partners, exact: true }).click();
  await expect(page.getByTestId('partner-accounts-page')).toBeVisible();
  await page.locator(`[data-testid="account-row"][data-reference="${reference}"] a`).click();
  await expect(page.getByTestId('account-reference')).toHaveText(reference);
}

async function lifecycle(
  page: Page,
  action: string,
  options: { reason?: string; confirm?: boolean } = {},
) {
  await page.getByTestId('lifecycle-action').selectOption(action);
  if (options.reason) await page.getByTestId('lifecycle-reason').selectOption(options.reason);
  if (options.confirm) await page.getByTestId('lifecycle-confirm').check();
  await page.getByTestId('lifecycle-submit').click();
}

async function versionAction(
  page: Page,
  version: number,
  action: string,
  options: { reason?: string; confirm?: boolean } = {},
) {
  const form = page
    .locator(`[data-testid="version"][data-version="${version}"]`)
    .getByTestId('version-transition');
  await form.getByTestId('version-action').selectOption(action);
  if (options.reason) await form.getByTestId('version-reason').selectOption(options.reason);
  if (options.confirm) await form.getByTestId('version-confirm').check();
  await form.getByTestId('version-submit').click();
}

test('MVP-120E journey 1: an approved application becomes a partner whose published product is shown as information only', async ({
  browser,
  page,
}) => {
  test.setTimeout(240_000);
  const t = COPY.en;
  const country = organizationCountry();
  const name = `Synthetic Catalogue Bank ${stamp}`;

  const bearer = await signIn(page, USERS.platformAdmin, 'en');
  const application = await approvedApplication(page, bearer, name, country);
  await page.waitForTimeout(WINDOW_MS);

  // Landing page and platform home tell the truth before anything else changes.
  await expect(page.getByTestId('home-card-partner-accounts')).toBeVisible();

  // The partner is created only after an explicit confirmation, and only from the approved identity.
  await primaryNav(page).getByRole('link', { name: t.partners, exact: true }).click();
  await expect(page.getByTestId('partner-accounts-page')).toBeVisible();
  await expect(page.getByTestId('account-boundary')).toContainText(t.boundary);
  const ready = page.locator(`[data-testid="ready-application"][data-reference="${application}"]`);
  await expect(ready).toContainText(name);
  await ready.getByTestId('ready-start').click();
  await expect(ready.getByTestId('ready-create')).toBeDisabled();
  await ready.getByLabel(t.confirmCreate).check();
  await expectAccessible(page);
  await ready.getByTestId('ready-create').click();

  await expect(page.getByTestId('account-detail-page')).toBeVisible();
  const reference = (await page.getByTestId('account-reference').innerText()).trim();
  expect(reference).toMatch(/^PT-[0-9A-HJKMNP-TV-Z]{16}$/u);
  await expect(page.getByTestId('account-status')).toHaveText(t.draft);
  await expect(page.getByTestId('account-boundary')).toContainText(t.boundary);
  // Only the identity was copied: no contact, e-mail, licence or registration reference.
  const detailText = await page.getByTestId('account-detail-page').innerText();
  expect(detailText).not.toMatch(/@example\.test|LIC-E2E|SYN-E2E|Test Contact/u);
  expect(
    sql(
      `SELECT count(*) FROM integrations.partner p JOIN integrations.partner_application a ON a.id = p.application_id WHERE a.reference = '${application}'`,
    ),
  ).toBe('1');
  await noOverflow(page);
  await textSpacing(page);
  await targetSize(page);

  // Activation needs a confirmation, and creates no connection.
  await expect(page.getByTestId('lifecycle-submit')).toBeDisabled();
  await lifecycle(page, 'ACTIVATE', { confirm: true });
  await expect(page.getByTestId('account-status')).toHaveText(t.active);

  // A bilingual product: a draft first, then submitted and published with an explicit confirmation.
  await page.getByLabel(t.code, { exact: true }).fill(CODE);
  await page.getByLabel(t.type, { exact: true }).selectOption('SALARY_ADVANCE');
  await page.getByTestId('new-product-submit').click();
  await expect(page.getByTestId('product-page')).toBeVisible();
  await expect(page.getByTestId('product-public-reference')).toHaveText(
    /^PP-[0-9A-HJKMNP-TV-Z]{16}$/u,
  );
  const spec = content(country, organizationToday());
  const group = (legend: string) => page.getByRole('group', { name: legend, exact: true });
  const bilingualFields: [string, { en: string; fr: string }][] = [
    ['Name', spec.name],
    ['Summary', spec.summary],
    ['Eligibility', spec.eligibility],
    ['Disclosure', spec.disclosure],
  ];
  for (const [legend, value] of bilingualFields) {
    await group(legend).getByLabel(t.english, { exact: true }).fill(value.en);
    await group(legend).getByLabel(t.french, { exact: true }).fill(value.fr);
  }
  await page.getByLabel('Information address').fill(spec.infoUrl);
  await page.getByLabel('Effective from').fill(spec.effectiveDate);
  await page.getByLabel('Minimum duration (months)').fill('1');
  await page.getByLabel('Maximum duration (months)').fill('6');
  await group(t.countries).getByRole('checkbox').first().check();
  await page.getByRole('checkbox', { name: t.cdf }).check();
  await page.getByLabel('Minimum amount (CDF)').fill('10000');
  await page.getByLabel('Maximum amount (CDF)').fill('500000');
  await expectAccessible(page);
  await noOverflow(page);
  await page.getByTestId('editor-save').click();
  await expect(page.getByTestId('editor-saved')).toHaveText(t.saved);
  await versionAction(page, 1, 'SUBMIT');
  await expect(page.locator('[data-testid="version"][data-version="1"]')).toHaveAttribute(
    'data-status',
    'UNDER_REVIEW',
  );
  await versionAction(page, 1, 'PUBLISH', { confirm: true });
  await expect(page.locator('[data-testid="version"][data-version="1"]')).toHaveAttribute(
    'data-status',
    'ACTIVE',
  );
  await expect(page.getByTestId('product-history')).toContainText('Published');

  // The organization administrator sees the product: information only, nothing to apply for.
  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  await signIn(admin, USERS.adminA, 'en');
  await expect(admin.getByTestId('home-card-catalogue')).toBeVisible();
  await openCatalogue(admin, 'en', 'tenant');
  const adminCard = productCard(admin, name);
  await expect(adminCard).toContainText(name);
  await expect(adminCard).toContainText(t.informationOnly);
  await expectInformationOnly(admin, 'en');
  await expectAccessible(admin);
  await noOverflow(admin);
  await textSpacing(admin);
  await adminContext.close();

  // The employee sees the same information in their own space, and nothing internal.
  const employeeContext = await browser.newContext();
  const employee = await employeeContext.newPage();
  const employeeBearer = await signIn(employee, USERS.employeeA, 'en', undefined);
  await expect(employee.getByTestId('home-card-my-catalogue')).toBeVisible();
  await openCatalogue(employee, 'en', 'employee');
  await expect(productCard(employee, name)).toContainText(name);
  await expectInformationOnly(employee, 'en');
  await expect(
    primaryNav(employee).getByRole('link', { name: t.partners, exact: true }),
  ).toHaveCount(0);
  await expectAccessible(employee);
  await noOverflow(employee);
  // The employee cannot read the platform screens or the platform API.
  const refused = await employee.request.get(`${CORE_API}/platform/partners`, {
    headers: { Authorization: employeeBearer() },
  });
  expect(refused.status()).toBe(403);
  await employeeContext.close();

  // The public landing page says the catalogue is available and what is not, without listing a partner.
  const visitor = await (await browser.newContext()).newPage();
  await visitor.goto('/');
  const benefits = visitor.locator('li[data-module="benefits"]');
  await expect(benefits).toHaveAttribute('data-status', 'capabilities');
  await expect(benefits).toContainText(t.landingAvailable);
  await expect(benefits).toContainText(t.landingLater);
  await expect(benefits).not.toContainText(t.comingNext);
  await expect(visitor.locator('body')).not.toContainText(name);
  await visitor.context().close();
});

test('MVP-120E journey 2: suspension, pause and closure take the product away, and isolation holds', async ({
  browser,
  page,
}) => {
  test.setTimeout(420_000);
  const t = COPY.fr;
  const country = organizationCountry();
  const name = `Banque Synthétique Catalogue ${stamp}`;

  const bearer = await signIn(page, USERS.platformAdmin, 'fr');
  await page.waitForTimeout(WINDOW_MS);
  const application = await approvedApplication(page, bearer, name, country);

  // Setup through the API (the creation and publication flow is exercised in journey 1).
  const created = await call<{ partner: { reference: string; version: number } }>(
    page,
    bearer,
    'POST',
    `/platform/partner-activations/from-application/${application}`,
    { data: { confirmed: true }, key: `create-${application}` },
  );
  const reference = created.partner.reference;
  await call(page, bearer, 'POST', `/platform/partners/${reference}/transitions`, {
    data: { action: 'ACTIVATE', expectedVersion: created.partner.version, confirmed: true },
    key: `activate-${reference}`,
  });
  const body = content(country, organizationToday());
  const product = await call<{ versions: { version: number; rowVersion: number }[] }>(
    page,
    bearer,
    'POST',
    `/platform/partners/${reference}/products`,
    {
      data: { productCode: CODE, productType: 'SALARY_ADVANCE', content: body },
      key: `product-${reference}`,
    },
  );
  const draft = product.versions[0];
  await call(page, bearer, 'POST', `/platform/partners/${reference}/products/${CODE}/transitions`, {
    data: { action: 'SUBMIT', version: 1, expectedRowVersion: draft.rowVersion },
    key: `submit-${reference}`,
  });
  const submitted = await call<{ versions: { rowVersion: number }[] }>(
    page,
    bearer,
    'GET',
    `/platform/partners/${reference}/products/${CODE}`,
  );
  await call(page, bearer, 'POST', `/platform/partners/${reference}/products/${CODE}/transitions`, {
    data: {
      action: 'PUBLISH',
      version: 1,
      expectedRowVersion: submitted.versions.at(0)?.rowVersion,
      confirmed: true,
    },
    key: `publish-${reference}`,
  });

  // A fresh window for the lifecycle written through the browser.
  await page.waitForTimeout(WINDOW_MS);
  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  await signIn(admin, USERS.adminA, 'fr');
  await openCatalogue(admin, 'fr', 'tenant');
  await expect(productCard(admin, name)).toContainText(name);
  await expectInformationOnly(admin, 'fr');
  const showsProduct = async (visible: boolean) => {
    await primaryNav(admin)
      .getByRole('link', { name: /^(Accueil|Home)$/u })
      .click();
    await openCatalogue(admin, 'fr', 'tenant');
    // The list is loaded before the assertion: the notice is rendered with the page.
    await expect(admin.getByTestId('catalogue-notice')).toBeVisible();
    await expect(admin.getByTestId('catalogue-loading')).toHaveCount(0);
    await expect(productCard(admin, name)).toHaveCount(visible ? 1 : 0);
  };

  await platformDetail(page, 'fr', reference);
  await expect(page.getByTestId('account-status')).toHaveText(t.active);
  await expectAccessible(page);
  await noOverflow(page);

  // Suspension needs a reason and hides every product of the partner.
  await lifecycle(page, 'SUSPEND', { reason: 'COMPLIANCE_REVIEW' });
  await expect(page.getByTestId('account-status')).toHaveText(t.suspended);
  await showsProduct(false);
  await lifecycle(page, 'REACTIVATE', { confirm: true });
  await expect(page.getByTestId('account-status')).toHaveText(t.active);
  await showsProduct(true);

  // Pausing the product hides it; resuming shows it again.
  await page.getByTestId('product-row').filter({ hasText: CODE }).getByRole('link').click();
  await expect(page.getByTestId('product-page')).toBeVisible();
  await versionAction(page, 1, 'PAUSE', { reason: 'COMMERCIAL_HOLD' });
  await expect(page.locator('[data-testid="version"][data-version="1"]')).toHaveAttribute(
    'data-status',
    'PAUSED',
  );
  await showsProduct(false);
  await versionAction(page, 1, 'RESUME', { confirm: true });
  await expect(page.locator('[data-testid="version"][data-version="1"]')).toHaveAttribute(
    'data-status',
    'ACTIVE',
  );
  await showsProduct(true);
  await expectAccessible(page);

  // Closure is refused while a product is active, and the refusal is a finished sentence.
  await page.getByRole('link', { name: 'Retour au partenaire' }).first().click();
  await expect(page.getByTestId('account-detail-page')).toBeVisible();
  await lifecycle(page, 'CLOSE', { confirm: true });
  await expect(page.getByTestId('lifecycle-failure')).toHaveAttribute(
    'data-code',
    'PARTNER_HAS_ACTIVE_PRODUCTS',
  );
  await expect(page.getByTestId('lifecycle-failure')).not.toContainText('PARTNER_HAS_ACTIVE');
  await expect(page.getByTestId('account-status')).toHaveText(t.active);

  // Retire the product, then close the partner: both stay out of the catalogue for good.
  await page.getByTestId('product-row').filter({ hasText: CODE }).getByRole('link').click();
  await versionAction(page, 1, 'RETIRE', { reason: 'PROGRAM_ENDED', confirm: true });
  await expect(page.locator('[data-testid="version"][data-version="1"]')).toHaveAttribute(
    'data-status',
    'RETIRED',
  );
  await page.getByRole('link', { name: 'Retour au partenaire' }).first().click();
  await lifecycle(page, 'CLOSE', { confirm: true });
  await expect(page.getByTestId('account-status')).toHaveText(t.closed);
  await expect(page.getByTestId('lifecycle-closed')).toBeVisible();
  await expect(page.getByTestId('new-product-closed')).toBeVisible();
  await showsProduct(false);
  await textSpacing(page);
  await targetSize(page);

  // Isolation: the organization administrator and the employee have no platform screen and no
  // platform API, and an anonymous caller has no catalogue.
  const adminBearer = await signIn(admin, USERS.adminA, 'fr');
  await expect(primaryNav(admin).getByRole('link', { name: t.partners, exact: true })).toHaveCount(
    0,
  );
  const adminRefused = await admin.request.get(`${CORE_API}/platform/partners`, {
    headers: { Authorization: adminBearer() },
  });
  expect(adminRefused.status()).toBe(403);
  await adminContext.close();

  const employeeContext = await browser.newContext();
  const employee = await employeeContext.newPage();
  const employeeBearer = await signIn(employee, USERS.employeeA, 'fr', undefined);
  await openCatalogue(employee, 'fr', 'employee');
  await expect(employee.getByTestId('catalogue-loading')).toHaveCount(0);
  await expect(productCard(employee, name)).toHaveCount(0);
  await expectInformationOnly(employee, 'fr');
  const employeeRefused = await employee.request.get(`${CORE_API}/platform/partners`, {
    headers: { Authorization: employeeBearer() },
  });
  expect(employeeRefused.status()).toBe(403);
  const employeeSees = await employee.request.get(`${CORE_API}/catalogue/products`, {
    headers: { Authorization: employeeBearer() },
  });
  expect(employeeSees.status()).toBe(200);
  expect(JSON.stringify(await employeeSees.json())).not.toContain(reference);
  const anonymous = await employee.request.get(`${CORE_API}/catalogue/products`);
  expect(anonymous.status()).toBe(401);
  await employeeContext.close();
});

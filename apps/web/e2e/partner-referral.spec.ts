import { expect, test, type APIResponse, type Browser, type Page } from '@playwright/test';
import {
  CORE_API,
  USERS,
  acceptInvitation,
  mailTo,
  openSecretLink,
  typeSecret,
  expectAccessible,
  primaryNav,
  signIn,
  sql,
  subjectLock,
  TENANT_A,
} from './support';
import { retryOn429 } from './rate-limit';

// MVP-120F (Issue #224, ADR 0045): exactly two journeys, run without retries, with the seeded
// organization administrator and the seeded platform administrator; the one synthetic employee is
// created, invited (example.test address) and linked by the administrator. Every partner and product is synthetic (example.test addresses, invented
// names) and every referral goes only to the in-process SYNTHETIC-TEST provider: no real partner,
// no endpoint, no credential and no money movement exist anywhere in these journeys.
//
// Journey 1 (English): the employee discovers an eligible product, inspects the exact disclosure
// preview, is refused without consent, consents and submits, observes the synthetic delivery truth
// (Delivered, with the provider acknowledgement), and sees the history only in their own space. The
// organization administrator cannot read it; the platform administrator sees a masked, read-only
// operational row with no control to approve, edit, resend, override or delete.
//
// Journey 2 (French): another category. A validation refusal keeps the safe form state, the
// referral is submitted and, in a controlled test state (the delivery of this journey's product is
// held by a test-only database trigger that is removed afterwards), withdrawn before delivery. The
// withdrawn referral is never delivered; tenant-administrator and platform boundaries and
// accessibility are verified.
//
// The account command limit (10 per subject per minute) and every referral limit are never raised:
// the journeys hold the platform administrator's subject lock and wait one full window between the
// groups of setup writes.

test.describe.configure({ retries: 0 });

const stamp = Date.now().toString(36);
const WINDOW_MS = 61_000;
const HOLD = `e2e_hold_${stamp}`;

const COPY = {
  en: {
    offers: 'Partner offers',
    requests: 'My partner requests',
    platform: 'Partner referrals',
    review: 'Review what will be shared',
    send: 'Send request',
    consentRequired: 'Tick the box to agree before sending.',
    phoneFormat: 'Use the international format, for example +243810000000.',
    delivered: 'Delivered',
    withdrawn: 'Withdrawn',
    withdrawLabel: 'I confirm that I want to withdraw this request.',
    withdrawButton: 'Withdraw request',
    withdrawDone: 'This request was withdrawn.',
    english: 'English',
    french: 'French',
  },
  fr: {
    offers: 'Offres de partenaires',
    requests: 'Mes demandes aux partenaires',
    platform: 'Recommandations partenaires',
    review: 'Vérifier ce qui sera partagé',
    send: 'Envoyer la demande',
    consentRequired: 'Cochez la case pour accepter avant d’envoyer.',
    phoneFormat: 'Utilisez le format international, par exemple +243810000000.',
    delivered: 'Livrée',
    withdrawn: 'Retirée',
    withdrawLabel: 'Je confirme que je veux retirer cette demande.',
    withdrawButton: 'Retirer la demande',
    withdrawDone: 'Cette demande a été retirée.',
    english: 'Anglais',
    french: 'Français',
  },
} as const;

let releaseSubject: (() => void) | undefined;
test.beforeAll(async () => {
  // Other specs hold this subject for minutes: waiting for it must not count against the hook.
  test.setTimeout(600_000);
  releaseSubject = await subjectLock(USERS.platformAdmin[0]);
});
test.afterAll(() => {
  try {
    sql(`DROP TRIGGER IF EXISTS ${HOLD} ON integrations.partner_referral`);
    sql(`DROP FUNCTION IF EXISTS integrations.${HOLD}()`);
  } finally {
    releaseSubject?.();
  }
});

// The seeded employee account has no employee record, and a referral needs one (legal name and a
// verified membership email). Like my-leave, one synthetic employee is created, invited and linked
// by the organization administrator; both journeys use it. All values are synthetic.
const SHORT = stamp.toUpperCase().slice(-6);
const EMPLOYEE_NUMBER = `PRE-${SHORT}-1`;
const EMPLOYEE_EMAIL = `e2e.referral.${stamp}@example.test`;
const EMPLOYEE_PASSWORD = `Dev-only-E2E-${SHORT}-Referral!`;
const EMPLOYEE_USER = [EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD] as const;
let employeeReady = false;

async function adminPost(
  page: Page,
  bearer: () => string,
  path: string,
  key: string,
  data: object,
) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pr-${key}-${stamp}-0001` },
    data,
  });
}

async function linkedEmployee(browser: Browser) {
  if (
    employeeReady ||
    sql(
      `SELECT count(*) FROM people.employee WHERE tenant_id = '${TENANT_A}' AND employee_number = '${EMPLOYEE_NUMBER}'`,
    ) !== '0'
  ) {
    employeeReady = true;
    return;
  }
  // Held only for these few writes (not for the whole file): other specs hold this subject for minutes.
  const releaseAdmin = await subjectLock(USERS.adminA[0]);
  const adminContext = await browser.newContext();
  const page = await adminContext.newPage();
  const bearer = await signIn(page, USERS.adminA, 'fr');
  const entityCode = `PE-${SHORT}`;
  const siteCode = `PS-${SHORT}`;
  const departmentCode = `PD-${SHORT}`;
  const entity = await adminPost(page, bearer, '/legal-entities', 'le', {
    code: entityCode,
    name: `Référence Entité ${stamp}`,
    countryCode: 'CD',
    effectiveFrom: '2026-01-01',
  });
  expect(entity.status()).toBe(201);
  const legalEntityId = ((await entity.json()) as { id: string }).id;
  const site = await adminPost(page, bearer, '/sites', 'st', {
    legalEntityId,
    code: siteCode,
    name: `Site Référence ${stamp}`,
    timezone: 'Africa/Kinshasa',
    effectiveFrom: '2026-01-01',
  });
  expect(site.status()).toBe(201);
  const siteId = ((await site.json()) as { id: string }).id;
  expect(
    (
      await adminPost(page, bearer, '/departments', 'dp', {
        siteId,
        code: departmentCode,
        name: `Ressources ${stamp}`,
        effectiveFrom: '2026-01-01',
      })
    ).status(),
  ).toBe(201);
  const start = sql(
    `SELECT ((now() AT TIME ZONE timezone)::date - 30)::text FROM tenant.organization WHERE id = '${TENANT_A}'`,
  );
  const file = [
    'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
    `${EMPLOYEE_NUMBER};Bénédicte;Ilunga${stamp.replace(/[^a-z]/gu, '')};${start};${entityCode};${siteCode};${departmentCode}`,
  ].join('\n');
  const upload = await page.request.post(`${CORE_API}/employee-imports`, {
    headers: {
      Authorization: bearer(),
      'Content-Type': 'text/csv',
      'Idempotency-Key': `e2e-pr-import-${stamp}-0001`,
    },
    data: Buffer.from(file, 'utf8'),
  });
  expect(upload.status()).toBe(201);
  const imported = (await upload.json()) as { id: string; previewDigest: string };
  const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pr-commit-${stamp}-0001` },
    data: { previewDigest: imported.previewDigest, validRows: 1, acknowledgeInvalidRows: false },
  });
  expect(commit.status()).toBe(200);
  const employee = sql(
    `SELECT id FROM people.employee WHERE tenant_id = '${TENANT_A}' AND employee_number = '${EMPLOYEE_NUMBER}'`,
  );
  expect(employee).toMatch(/^[0-9a-f-]{36}$/u);

  expect(
    (
      await adminPost(page, bearer, '/invitations', 'inv', {
        email: EMPLOYEE_EMAIL,
        role: 'employee',
        locale: 'fr',
      })
    ).status(),
  ).toBe(201);
  const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
    await mailTo(EMPLOYEE_EMAIL, /Invitation à rejoindre/u),
  );
  expect(link, 'invitation link').not.toBeNull();
  expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
  const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
    await mailTo(EMPLOYEE_EMAIL, /mot de passe|password|actions|compte|account/iu),
  );
  expect(setup, 'password setup link').not.toBeNull();
  const context = await browser.newContext({ locale: 'fr-FR' });
  const setupPage = await context.newPage();
  await openSecretLink(setupPage, setup?.[0] ?? '');
  const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
  if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
    await proceed.click();
  }
  await typeSecret(setupPage, '#password-new', EMPLOYEE_PASSWORD);
  await typeSecret(setupPage, '#password-confirm', EMPLOYEE_PASSWORD);
  await setupPage.locator('[type="submit"]').first().click();
  await context.close();

  const lookup = await page.request.post(`${CORE_API}/employees/${employee}/access-link/lookup`, {
    headers: { Authorization: bearer() },
    data: { email: EMPLOYEE_EMAIL },
  });
  expect(lookup.status()).toBe(200);
  const membershipId = ((await lookup.json()) as { membershipId: string }).membershipId;
  expect(
    (
      await adminPost(page, bearer, `/employees/${employee}/access-link`, 'link', { membershipId })
    ).status(),
  ).toBe(201);
  employeeReady = true;
  await adminContext.close();
  releaseAdmin();
}

function organizationCountry(): string {
  const country = sql(`SELECT country_code FROM tenant.organization WHERE id = '${TENANT_A}'`);
  expect(country, 'the seeded organization has a country').toMatch(/^[A-Z]{2}$/u);
  return country;
}

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
          ...(options.key ? { 'Idempotency-Key': `e2e-pr-${options.key}-${stamp}-0001` } : {}),
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

interface Offer {
  name: string;
  category: 'BANK' | 'INSURANCE';
  capability: 'SALARY_ADVANCE' | 'HEALTH_INSURANCE';
  productType: 'SALARY_ADVANCE' | 'HEALTH_INSURANCE';
  code: string;
}

async function approvedApplication(
  page: Page,
  bearer: () => string,
  offer: Offer,
  country: string,
): Promise<string> {
  const slug = offer.code.toLowerCase();
  const receipt = await page.request.post(`${CORE_API}/public/partner-applications`, {
    headers: { 'Idempotency-Key': `e2e-pr-apply-${slug}-${stamp}-0001` },
    data: {
      legalName: offer.name,
      tradingName: null,
      countryOfRegistration: country,
      countriesOfOperation: [country],
      registrationReference: 'SYN-E2E-0120F',
      primaryCategory: offer.category,
      additionalCategories: [],
      requestedCapabilities: [offer.capability],
      website: 'https://example.test',
      contactName: 'Test Contact',
      contactRole: 'Head of partnerships',
      businessEmail: `partner.${slug}.${stamp}@example.test`,
      businessPhone: null,
      supportedLanguages: ['en', 'fr'],
      apiAvailability: 'PLANNED',
      regulatorName: 'Synthetic Regulator',
      licenceReference: 'LIC-E2E-0120F',
      serviceSummary: 'A synthetic end-to-end application for the referral journey.',
      privacyContactEmail: `partner.privacy.${slug}.${stamp}@example.test`,
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

/** An active synthetic partner with one published product offered in the organization's country. */
async function publishedOffer(
  page: Page,
  bearer: () => string,
  offer: Offer,
  country: string,
): Promise<string> {
  const application = await approvedApplication(page, bearer, offer, country);
  await page.waitForTimeout(WINDOW_MS);
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
  const product = await call<{ versions: { version: number; rowVersion: number }[] }>(
    page,
    bearer,
    'POST',
    `/platform/partners/${reference}/products`,
    {
      data: {
        productCode: offer.code,
        productType: offer.productType,
        content: {
          name: { en: offer.name, fr: offer.name },
          summary: { en: 'A short synthetic summary.', fr: 'Un court résumé synthétique.' },
          eligibility: {
            en: 'Open to permanent employees.',
            fr: 'Ouvert aux employés permanents.',
          },
          disclosure: {
            en: 'Synthetic disclosure text of sufficient length.',
            fr: 'Texte de mention synthétique de longueur suffisante.',
          },
          infoUrl: 'https://example.test/information',
          effectiveDate: organizationToday(),
          endDate: null,
          minMonths: 1,
          maxMonths: 6,
          countries: [country],
          currencies: [{ currency: 'CDF', minAmount: '10000', maxAmount: '500000' }],
        },
      },
      key: `product-${reference}`,
    },
  );
  const transitions = `/platform/partners/${reference}/products/${offer.code}/transitions`;
  const draft = product.versions[0];
  await call(page, bearer, 'POST', transitions, {
    data: { action: 'SUBMIT', version: 1, expectedRowVersion: draft.rowVersion },
    key: `submit-${reference}`,
  });
  const submitted = await call<{ versions: { rowVersion: number }[] }>(
    page,
    bearer,
    'GET',
    `/platform/partners/${reference}/products/${offer.code}`,
  );
  await call(page, bearer, 'POST', transitions, {
    data: {
      action: 'PUBLISH',
      version: 1,
      expectedRowVersion: submitted.versions.at(0)?.rowVersion,
      confirmed: true,
    },
    key: `publish-${reference}`,
  });
  return reference;
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

/** Keyboard: Tab reaches the control and Space toggles the checkbox (no pointer involved). */
async function toggleByKeyboard(page: Page, testId: string) {
  const box = page.getByTestId(testId);
  await box.focus();
  await expect(box).toBeFocused();
  await page.keyboard.press('Space');
}

async function openOffer(page: Page, locale: 'en' | 'fr', name: string) {
  await primaryNav(page).getByRole('link', { name: COPY[locale].offers, exact: true }).click();
  await expect(page.getByTestId('partner-products-page')).toBeVisible();
  const card = page.locator('[data-testid="partner-product"]', { hasText: name });
  await expect(card).toHaveCount(1);
  return card;
}

/**
 * Repeats `refresh` (an in-app navigation: a page reload would drop the in-memory tokens) until the
 * assertion holds: the delivery job runs on its own interval.
 */
async function eventually(
  page: Page,
  check: () => Promise<void>,
  refresh: () => Promise<void>,
  attempts = 12,
) {
  for (let attempt = 1; ; attempt++) {
    try {
      await check();
      return;
    } catch (failure) {
      if (attempt >= attempts) throw failure;
      await page.waitForTimeout(5_000);
      await refresh();
    }
  }
}

/** The organization administrator and the platform administrator boundaries for one referral. */
async function expectBoundaries(
  browser: import('@playwright/test').Browser,
  locale: 'en' | 'fr',
  referral: string,
  emailValue: string,
  state: 'DELIVERED' | 'WITHDRAWN',
) {
  const t = COPY[locale];
  // The organization administrator: no navigation entry, no route content, no API access.
  const adminContext = await browser.newContext();
  const admin = await adminContext.newPage();
  const adminBearer = await signIn(admin, USERS.adminA, locale);
  await expect(primaryNav(admin).getByRole('link', { name: t.platform, exact: true })).toHaveCount(
    0,
  );
  const platformApi = await admin.request.get(`${CORE_API}/platform/partner-referrals`, {
    headers: { Authorization: adminBearer() },
  });
  expect(platformApi.status()).toBe(403);
  const platformOne = await admin.request.get(
    `${CORE_API}/platform/partner-referrals/${referral}`,
    { headers: { Authorization: adminBearer() } },
  );
  expect(platformOne.status()).toBe(403);
  // Whatever the administrator's own space answers, it never contains the employee's referral.
  const mine = await admin.request.get(`${CORE_API}/me/partner-referrals`, {
    headers: { Authorization: adminBearer() },
  });
  expect(await mine.text()).not.toContain(referral);
  const one = await admin.request.get(`${CORE_API}/me/partner-referrals/${referral}`, {
    headers: { Authorization: adminBearer() },
  });
  expect(one.status()).not.toBe(200);
  await admin.goto(`/admin/partner-referrals/${referral}`);
  await expect(admin.getByTestId('platform-referral-page')).toHaveCount(0);
  await expect(admin.locator('body')).not.toContainText(referral);
  await adminContext.close();

  // The platform administrator: a masked, read-only operational view.
  const platformContext = await browser.newContext();
  const platform = await platformContext.newPage();
  await signIn(platform, USERS.platformAdmin, locale);
  await primaryNav(platform).getByRole('link', { name: t.platform, exact: true }).click();
  await expect(platform.getByTestId('platform-referrals-page')).toBeVisible();
  const row = platform.locator(`[data-testid="platform-referral"][data-reference="${referral}"]`);
  await expect(row).toBeVisible();
  const contact = (await row.getByTestId('platform-referral-contact').innerText()).trim();
  expect(contact).not.toContain(emailValue);
  expect(contact).toMatch(/\*|•|…|•/u);
  await expect(
    platform.getByRole('button', {
      name: /approve|edit|resend|retry|override|delete|approuver|modifier|renvoyer|réessayer|supprimer/iu,
    }),
  ).toHaveCount(0);
  await expectAccessible(platform);
  await row.getByTestId('platform-referral-open').click();
  await expect(platform.getByTestId('platform-referral-page')).toBeVisible();
  await expect(platform.locator('main')).not.toContainText(emailValue);
  await expect(platform.getByTestId('platform-referral-attempts')).toHaveText(
    state === 'WITHDRAWN' ? '0' : /^[1-9]/u,
  );
  await expect(
    platform.getByRole('button', {
      name: /approve|edit|resend|retry|override|delete|approuver|modifier|renvoyer|réessayer|supprimer/iu,
    }),
  ).toHaveCount(0);
  await noOverflow(platform);
  await platformContext.close();
}

test('MVP-120F journey 1: the employee consents, the synthetic delivery is observed, and only the employee reads the history', async ({
  browser,
  page,
}) => {
  test.setTimeout(900_000);
  const t = COPY.en;
  const country = organizationCountry();
  const offer: Offer = {
    name: `Synthetic Referral Bank ${stamp}`,
    category: 'BANK',
    capability: 'SALARY_ADVANCE',
    productType: 'SALARY_ADVANCE',
    code: `R${stamp.toUpperCase()}A`,
  };

  const bearer = await signIn(page, USERS.platformAdmin, 'en');
  await page.waitForTimeout(WINDOW_MS);
  await publishedOffer(page, bearer, offer, country);

  await page.waitForTimeout(WINDOW_MS);
  await linkedEmployee(browser);
  const employeeContext = await browser.newContext();
  const employee = await employeeContext.newPage();
  await signIn(employee, EMPLOYEE_USER, 'en', undefined);

  // Discovery: the offer is listed in the employee's own space, with no decision or price.
  const card = await openOffer(employee, 'en', offer.name);
  await expect(card).toHaveAttribute('data-category', 'BANK');
  await expectAccessible(employee);
  await card.getByTestId('partner-product-open').click();
  await expect(employee.getByTestId('partner-referral-page')).toBeVisible();

  // The consent-bound form: nothing optional is preselected, and a bad phone number is refused.
  await expect(employee.getByTestId('include-phone')).not.toBeChecked();
  await expect(employee.getByTestId('include-organization')).not.toBeChecked();
  await toggleByKeyboard(employee, 'include-phone');
  await expect(employee.getByTestId('include-phone')).toBeChecked();
  await employee.getByTestId('phone').fill('0810000001');
  await employee.getByTestId('referral-review').click();
  await expect(employee.getByTestId('phone-error')).toHaveText(t.phoneFormat);
  await expect(employee.getByTestId('phone')).toHaveValue('0810000001');
  await employee.getByTestId('phone').fill('+243810000001');
  await noOverflow(employee);
  await textSpacing(employee);
  await targetSize(employee);

  // The exact preview, before anything is sent.
  await employee.getByRole('button', { name: t.review }).click();
  const preview = employee.getByTestId('referral-preview');
  await expect(preview).toBeVisible();
  await expect(preview).toHaveAttribute('data-blocked', 'false');
  const fields = employee.getByTestId('referral-field');
  const names = await fields.evaluateAll((items) =>
    items.map((item) => item.getAttribute('data-field') ?? ''),
  );
  expect(names.length, 'a bounded, explicit list of fields').toBeGreaterThan(0);
  // Exactly the allow-listed fields: the optional phone is included (chosen), the optional organization name is not.
  expect([...names].sort()).toEqual(
    [
      'CONSENT_TIMESTAMP',
      'CONSENT_VERSION',
      'EMPLOYEE_COUNTRY',
      'EMPLOYEE_EMAIL',
      'EMPLOYEE_LEGAL_NAME',
      'EMPLOYEE_PHONE',
      'PARTNER_NAME',
      'PREFERRED_LANGUAGE',
      'PRODUCT_NAME',
      'PRODUCT_REFERENCE',
      'REFERRAL_REFERENCE',
    ].sort(),
  );
  expect(names).toContain('EMPLOYEE_PHONE');
  expect(names).toContain('EMPLOYEE_EMAIL');
  expect(names).not.toContain('ORGANIZATION_LEGAL_NAME');
  expect(names.some((name) => /SALARY|PAYROLL|NATIONAL/u.test(name))).toBe(false);
  await expect(employee.getByTestId('referral-never-shared')).toContainText(/salary|payroll/iu);
  const emailValue = (
    await employee
      .locator(
        '[data-testid="referral-field"][data-field="EMPLOYEE_EMAIL"] [data-testid="referral-field-value"]',
      )
      .innerText()
  ).trim();
  expect(emailValue).toContain('@');

  // Consent is explicit and starts unchecked: sending without it is refused with no request made.
  await expect(employee.getByTestId('referral-consent-checkbox')).not.toBeChecked();
  await employee.getByTestId('referral-send').click();
  await expect(employee.getByTestId('referral-consent')).toContainText(t.consentRequired);
  await expect(employee.getByTestId('referral-confirmation')).toHaveCount(0);
  await noOverflow(employee);
  await textSpacing(employee);

  await toggleByKeyboard(employee, 'referral-consent-checkbox');
  await expect(employee.getByTestId('referral-consent-checkbox')).toBeChecked();
  await employee.getByTestId('referral-send').click();
  await expect(employee.getByTestId('referral-confirmation')).toBeVisible();
  const reference = (await employee.getByTestId('referral-reference').innerText()).trim();
  expect(reference).toMatch(/^PR-[0-9A-HJKMNP-TV-Z]{16}$/u);
  await expectAccessible(employee);
  await noOverflow(employee);

  // Synthetic delivery truth: Delivered only after the provider acknowledged it, and nothing else.
  await employee.getByTestId('referral-view').click();
  await expect(employee.getByTestId('my-referral-page')).toBeVisible();
  await eventually(
    employee,
    async () => {
      await expect(employee.getByTestId('referral-state')).toHaveText(t.delivered, {
        timeout: 2_000,
      });
    },
    async () => {
      await primaryNav(employee).getByRole('link', { name: t.requests, exact: true }).click();
      await employee
        .locator(`[data-testid="my-referral"][data-reference="${reference}"]`)
        .getByTestId('my-referral-open')
        .click();
      await expect(employee.getByTestId('my-referral-page')).toBeVisible();
    },
  );
  await expect(employee.getByTestId('referral-state-help')).toBeVisible();
  await expect(employee.getByTestId('referral-history')).toBeVisible();
  await expect(employee.getByTestId('referral-withdraw-form')).toHaveCount(0);
  await expect(employee.getByTestId('referral-not-withdrawable')).toBeVisible();
  const shared = await employee.getByTestId('referral-shared-fields').innerText();
  expect(shared).not.toMatch(/\+243810000001/u);
  expect(
    sql(
      `SELECT state || ':' || (attempt_count >= 1)::text FROM integrations.partner_referral WHERE reference = '${reference}'`,
    ),
  ).toBe('DELIVERED:true');
  expect(
    sql(
      `SELECT count(*) FROM integrations.partner_referral_ack a JOIN integrations.partner_referral r ON r.tenant_id = a.tenant_id AND r.id = a.referral_id WHERE r.reference = '${reference}'`,
    ),
  ).toBe('1');
  await noOverflow(employee);
  await textSpacing(employee);

  // The history is in the employee's own list.
  await primaryNav(employee).getByRole('link', { name: t.requests, exact: true }).click();
  await expect(
    employee.locator(`[data-testid="my-referral"][data-reference="${reference}"]`),
  ).toHaveAttribute('data-state', 'DELIVERED');
  await expectAccessible(employee);
  await employeeContext.close();

  await expectBoundaries(browser, 'en', reference, emailValue, 'DELIVERED');
});

test('MVP-120F journey 2: a refused form keeps its state, the request is withdrawn before delivery, and the boundaries hold', async ({
  browser,
  page,
}) => {
  test.setTimeout(600_000);
  const t = COPY.fr;
  const country = organizationCountry();
  const offer: Offer = {
    name: `Assurance Synthétique Référence ${stamp}`,
    category: 'INSURANCE',
    capability: 'HEALTH_INSURANCE',
    productType: 'HEALTH_INSURANCE',
    code: `R${stamp.toUpperCase()}B`,
  };

  const bearer = await signIn(page, USERS.platformAdmin, 'fr');
  await page.waitForTimeout(WINDOW_MS);
  const partner = await publishedOffer(page, bearer, offer, country);

  // Controlled test state: the delivery of this journey's product only is held for an hour, so
  // the withdrawal below meets a referral that is certainly not delivered. Removed in afterAll.
  const productId = sql(
    `SELECT x.id FROM integrations.partner_product x JOIN integrations.partner p ON p.id = x.partner_id WHERE p.reference = '${partner}' AND x.product_code = '${offer.code}'`,
  );
  expect(productId).toMatch(/^[0-9a-f-]{36}$/u);
  sql(
    `CREATE FUNCTION integrations.${HOLD}() RETURNS trigger LANGUAGE plpgsql AS $f$ BEGIN IF NEW.product_id = '${productId}' THEN NEW.next_attempt_at := now() + interval '1 hour'; END IF; RETURN NEW; END $f$; CREATE TRIGGER ${HOLD} BEFORE INSERT ON integrations.partner_referral FOR EACH ROW EXECUTE FUNCTION integrations.${HOLD}()`,
  );

  const employeeContext = await browser.newContext();
  const employee = await employeeContext.newPage();
  await linkedEmployee(browser);
  await signIn(employee, EMPLOYEE_USER, 'fr', undefined);
  const card = await openOffer(employee, 'fr', offer.name);
  await expect(card).toHaveAttribute('data-category', 'INSURANCE');
  await expectAccessible(employee);
  await card.getByTestId('partner-product-open').click();
  await expect(employee.getByTestId('partner-referral-page')).toBeVisible();

  // Validation refusal: the typed values and choices survive, and the message is announced.
  await employee.getByLabel(t.french, { exact: true }).check();
  await employee.getByTestId('include-organization').check();
  await employee.getByTestId('include-phone').check();
  await employee.getByTestId('phone').fill('810 000');
  await employee.getByTestId('referral-review').click();
  await expect(employee.getByTestId('phone-error')).toHaveText(t.phoneFormat);
  await expect(employee.getByTestId('phone')).toHaveAttribute('aria-invalid', 'true');
  await expect(employee.getByTestId('phone')).toBeFocused();
  await expect(employee.getByTestId('phone')).toHaveValue('810 000');
  await expect(employee.getByTestId('include-organization')).toBeChecked();
  await expect(employee.getByLabel(t.french, { exact: true })).toBeChecked();
  await expect(employee.getByTestId('referral-preview')).toHaveCount(0);
  await noOverflow(employee);
  await textSpacing(employee);
  await targetSize(employee);

  await employee.getByTestId('phone').fill('+243810000002');
  await employee.getByRole('button', { name: t.review }).click();
  await expect(employee.getByTestId('referral-preview')).toHaveAttribute('data-blocked', 'false');
  const emailValue = (
    await employee
      .locator(
        '[data-testid="referral-field"][data-field="EMPLOYEE_EMAIL"] [data-testid="referral-field-value"]',
      )
      .innerText()
  ).trim();
  await expect(
    employee.locator('[data-testid="referral-field"][data-field="EMPLOYEE_PHONE"]'),
  ).toBeVisible();
  await expect(employee.getByTestId('referral-consent-checkbox')).not.toBeChecked();
  await expectAccessible(employee);
  await noOverflow(employee);
  await employee.getByTestId('referral-consent-checkbox').check();
  await employee.getByTestId('referral-send').click();
  await expect(employee.getByTestId('referral-confirmation')).toBeVisible();
  const reference = (await employee.getByTestId('referral-reference').innerText()).trim();
  expect(reference).toMatch(/^PR-[0-9A-HJKMNP-TV-Z]{16}$/u);

  // Not delivered yet: the held state is certain, and withdrawal needs an explicit confirmation.
  await employee.getByTestId('referral-view').click();
  await expect(employee.getByTestId('my-referral-page')).toBeVisible();
  expect(
    sql(
      `SELECT state || ':' || attempt_count FROM integrations.partner_referral WHERE reference = '${reference}'`,
    ),
  ).toBe('SUBMITTED:0');
  await expect(employee.getByTestId('referral-withdraw-form')).toBeVisible();
  await expectAccessible(employee);
  await noOverflow(employee);
  await textSpacing(employee);
  await targetSize(employee);
  await employee.getByTestId('referral-withdraw').click();
  await expect(employee.getByTestId('referral-withdraw-form')).toContainText(
    /Cochez la case pour confirmer\./u,
  );
  await employee.getByLabel(t.withdrawLabel).check();
  await employee.getByRole('button', { name: t.withdrawButton }).click();
  await expect(employee.getByTestId('referral-withdrawn')).toContainText(t.withdrawDone);
  await expect(employee.getByTestId('referral-state')).toHaveText(t.withdrawn);
  await expect(employee.getByTestId('referral-withdraw-form')).toHaveCount(0);

  // A withdrawn referral is never delivered: release the hold and let the job run twice.
  sql(`DROP TRIGGER IF EXISTS ${HOLD} ON integrations.partner_referral`);
  await employee.waitForTimeout(12_000);
  expect(
    sql(
      `SELECT state || ':' || attempt_count FROM integrations.partner_referral WHERE reference = '${reference}'`,
    ),
  ).toBe('WITHDRAWN:0');
  expect(
    sql(
      `SELECT count(*) FROM integrations.partner_referral_ack a JOIN integrations.partner_referral r ON r.tenant_id = a.tenant_id AND r.id = a.referral_id WHERE r.reference = '${reference}'`,
    ),
  ).toBe('0');
  await noOverflow(employee);
  await employeeContext.close();

  await expectBoundaries(browser, 'fr', reference, emailValue, 'WITHDRAWN');
});

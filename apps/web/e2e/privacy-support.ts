import { expect, type APIResponse, type Browser, type Page } from '@playwright/test';
import {
  CORE_API,
  USERS,
  acceptInvitation,
  mailTo,
  openSecretLink,
  typeSecret,
  expectAccessible,
  signIn,
  sql,
  subjectLock,
  TENANT_A,
} from './support';
import { retryOn429 } from './rate-limit';

// LAUNCH-001 (Issue #226): fixtures shared by the two privacy journeys. They mirror the MVP-120F
// journeys' synthetic setup (one synthetic employee created, invited with an example.test address
// and linked by the organization administrator; one synthetic partner and product that only the
// in-process SYNTHETIC-TEST provider receives). Nothing here is real data and nothing leaves the
// stack.

export const stamp = Date.now().toString(36);
export const WINDOW_MS = 61_000;
export const SHORT = stamp.toUpperCase().slice(-6);
export const EMPLOYEE_NUMBER = `PVY-${SHORT}-1`;
export const EMPLOYEE_EMAIL = `e2e.privacy.${stamp}@example.test`;
export const EMPLOYEE_PASSWORD = `Dev-only-E2E-${SHORT}-Privacy!`;
export const EMPLOYEE_USER = [EMPLOYEE_EMAIL, EMPLOYEE_PASSWORD] as const;
let employeeReady = false;

export async function adminPost(
  page: Page,
  bearer: () => string,
  path: string,
  key: string,
  data: object,
) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pv-${key}-${stamp}-0001` },
    data,
  });
}

export async function linkedEmployee(browser: Browser) {
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
      'Idempotency-Key': `e2e-pv-import-${stamp}-0001`,
    },
    data: Buffer.from(file, 'utf8'),
  });
  expect(upload.status()).toBe(201);
  const imported = (await upload.json()) as { id: string; previewDigest: string };
  const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pv-commit-${stamp}-0001` },
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

export function organizationCountry(): string {
  const country = sql(`SELECT country_code FROM tenant.organization WHERE id = '${TENANT_A}'`);
  expect(country, 'the seeded organization has a country').toMatch(/^[A-Z]{2}$/u);
  return country;
}

export function organizationToday(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization WHERE id = '${TENANT_A}'`,
  );
}

export async function call<T = unknown>(
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
          ...(options.key ? { 'Idempotency-Key': `e2e-pv-${options.key}-${stamp}-0001` } : {}),
        },
        data: options.data,
      }),
    (ms) => page.waitForTimeout(ms),
  );
  expect(response.status(), `${method} ${path}`).toBe(options.expected ?? 200);
  return (response.status() === 204 ? undefined : await response.json()) as T;
}

export interface Check {
  checkCode: string;
  version: number;
  mandatory: boolean;
}
export interface ApplicationDetail {
  version: number;
  checklist: Check[];
}

export interface Offer {
  name: string;
  category: 'BANK' | 'INSURANCE';
  capability: 'SALARY_ADVANCE' | 'HEALTH_INSURANCE';
  productType: 'SALARY_ADVANCE' | 'HEALTH_INSURANCE';
  code: string;
}

export async function approvedApplication(
  page: Page,
  bearer: () => string,
  offer: Offer,
  country: string,
): Promise<string> {
  const slug = offer.code.toLowerCase();
  const receipt = await page.request.post(`${CORE_API}/public/partner-applications`, {
    headers: { 'Idempotency-Key': `e2e-pv-apply-${slug}-${stamp}-0001` },
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
export async function publishedOffer(
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

export async function noOverflow(page: Page) {
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

export async function textSpacing(page: Page) {
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

export async function targetSize(page: Page) {
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
export async function toggleByKeyboard(page: Page, testId: string) {
  const box = page.getByTestId(testId);
  await box.focus();
  await expect(box).toBeFocused();
  await page.keyboard.press('Space');
}

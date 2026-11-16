import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  CORE_API,
  TENANT_A,
  USERS,
  acceptInvitation,
  expectAccessible,
  mailTo,
  openSecretLink,
  primaryNav,
  signIn,
  sql,
  subjectLock,
  typeSecret,
} from './support';
import { createSyntheticVerifiedPack } from './synthetic-rule-pack';

// MVP-120A (Issue #204): exactly two journeys, with synthetic data only (the partner code is
// SYNTHETIC-TEST and no lender, bank, bureau or endpoint exists).
// (1) English: the administrator adds a salary-advance participation policy for a synthetic legal
//     entity (preview, then an explicit commit); the linked employee sees the permanent notice, the
//     employer-policy ceiling, reads and affirms the consent and submits a request; the administrator
//     confirms the employer's participation; the employee sees the confirmed status. No URL, storage
//     entry or visible text carries lending wording.
// (2) French: the policy is recorded through the API; the employee of the second legal entity
//     submits in French; the administrator refuses with a closed reason and an internal note; the
//     employee sees the refusal and the waiting period, never the note; a non-administrator cannot
//     read the administrator's list. Accessibility, keyboard and reflow are checked.
// Setup uses only public Core API endpoints (legal entities, sites, people import, compensation,
// payroll policy and runs, statutory profiles, the test-only synthetic pack, calculation and
// approval) plus, for the two employees, an invitation, a password and an access link. Eligibility
// arithmetic, authorization, limits, races and rollbacks are covered by the Core tests, not here.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-${stamp}`;
const NOTE = `Note interne synthétique ${stamp}`;
const POLICY_CODE = `SAP-${stamp}`;
const FR_POLICY_CODE = `SAF-${stamp}`;
const AMOUNT = '5000';

interface Site {
  entityCode: string;
  siteCode: string;
  departmentCode: string;
  name: string;
  id: string;
  runId: string;
  calculationId: string;
}
const site = (suffix: string, name: string): Site => ({
  entityCode: `SE${suffix}-${stamp}`,
  siteCode: `ST${suffix}-${stamp}`,
  departmentCode: `SD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
  calculationId: '',
});
const EN_ENTITY = site('E', 'Advance Entity');
const FR_ENTITY = site('F', 'Entité Avances');

interface Person {
  number: string;
  given: string;
  family: string;
  email: string;
  password: string;
  entity: Site;
  employee: string;
  linked: boolean;
}
const person = (suffix: string, given: string, entity: Site, linked: boolean): Person => ({
  number: `SAD-${stamp}-${suffix}`,
  given,
  family: `Mutombo${letters}${suffix.toLowerCase()}`,
  email: `e2e.avance.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Avance${suffix}!`,
  entity,
  employee: '',
  linked,
});
// Anna (English journey) and Clovis (French journey) have a login.
const ANA = person('A', 'Anna', EN_ENTITY, true);
const CLO = person('C', 'Clovis', FR_ENTITY, true);

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/** Today in the organization's business time zone, as the Core computes it. */
function today(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization
     WHERE id = '${TENANT_A}'`,
  );
}

const TODAY = today();
const FIRST_OF_THIS_MONTH = `${TODAY.slice(0, 8)}01`;
const LAST_DAY = plusDays(FIRST_OF_THIS_MONTH, -1);
const FIRST_DAY = `${LAST_DAY.slice(0, 8)}01`;
const PERIOD = LAST_DAY.slice(0, 7);
/** The Core accepts a compensation start up to exactly 60 days in the past (boundary included). */
const earliestCompensation = plusDays(TODAY, -60);
const later = (a: string, b: string) => (a > b ? a : b);
const HIRE = plusDays(FIRST_DAY, -5);

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
    const wide = await page.evaluate(() =>
      document.documentElement.scrollWidth <= window.innerWidth
        ? []
        : Array.from(document.body.querySelectorAll('*'))
            .filter((element) => element.getBoundingClientRect().right > window.innerWidth + 1)
            .slice(0, 6)
            .map(
              (element) =>
                `${element.tagName.toLowerCase()}[${element.getAttribute('data-testid') ?? element.className}]=${String(Math.round(element.getBoundingClientRect().right))}`,
            ),
    );
    expect(wide, `no horizontal overflow at ${viewport.width} px`).toEqual([]);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

/** Keyboard-only: tab forward until the control has focus (bounded). */
async function keyboardTabReaches(page: Page, target: Locator) {
  for (let step = 0; step < 160; step++) {
    if (await target.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await target.evaluate((el) => el === document.activeElement)).toBe(true);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sa-${key}-${stamp}-0001` },
    data,
  });
}

async function postJson<T>(
  page: Page,
  bearer: () => string,
  path: string,
  data: object,
  key?: string,
  expected = 200,
): Promise<T> {
  // The tenant shares one rate-limit window with every other journey; a 429 carries the seconds to
  // wait and the identical request is safe to repeat (same idempotency key, nothing recorded yet).
  let response = await page.request.post(`${CORE_API}${path}`, {
    headers: {
      Authorization: bearer(),
      ...(key ? { 'Idempotency-Key': `e2e-sa-${key}-${stamp}-0001` } : {}),
    },
    data,
  });
  for (let attempt = 0; attempt < 3 && response.status() === 429; attempt += 1) {
    const wait = Number(response.headers()['retry-after'] ?? '5');
    await page.waitForTimeout((Number.isFinite(wait) ? Math.min(wait, 65) : 5) * 1000 + 250);
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key ? { 'Idempotency-Key': `e2e-sa-${key}-${stamp}-0001` } : {}),
      },
      data,
    });
  }
  expect(response.status(), path).toBe(expected);
  return (await response.json()) as T;
}

/** Establishes a monthly CDF compensation through the public preview and commit endpoints. */
async function establish(page: Page, bearer: () => string, p: Person, amount: string) {
  const effectiveFrom = later(HIRE, earliestCompensation);
  const command = { action: 'ESTABLISHED', effectiveFrom, currency: 'CDF', amount };
  const body = await postJson<{
    version: number;
    employmentDigest: string;
    previewDigest: string;
    expiresAt: string;
    blockers: string[];
  }>(page, bearer, `/employees/${p.employee}/compensation/preview`, command);
  expect(body.blockers).toEqual([]);
  await postJson(
    page,
    bearer,
    `/employees/${p.employee}/compensation/commit`,
    {
      ...command,
      expectedVersion: body.version,
      employmentDigest: body.employmentDigest,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      acknowledgeRetroactive: true,
      reasonCode: null,
      explanation: null,
      targetChangeId: null,
      confirmed: true,
    },
    `cp-${p.number}`,
    201,
  );
}

async function recordPolicy(page: Page, bearer: () => string, entity: Site) {
  const command = {
    effectiveFrom: FIRST_DAY,
    payDay: { kind: 'LAST_DAY', day: null },
    prorationMethod: 'FIXED_30_DAYS',
  };
  const body = await postJson<{ version: number; previewDigest: string; expiresAt: string }>(
    page,
    bearer,
    `/legal-entities/${entity.id}/payroll-policy/preview`,
    command,
  );
  await postJson(
    page,
    bearer,
    `/legal-entities/${entity.id}/payroll-policy`,
    {
      ...command,
      expectedVersion: body.version,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      confirmed: true,
    },
    `pol-${entity.entityCode}`,
    201,
  );
}

async function createRun(page: Page, bearer: () => string, entity: Site) {
  const command = { period: PERIOD, currency: 'CDF' };
  const body = await postJson<{
    expectedVersion: number;
    inputDigest: string;
    previewDigest: string;
    expiresAt: string;
    blockedEmployeeCount: number;
  }>(page, bearer, `/legal-entities/${entity.id}/payroll-runs/preview`, command);
  expect(body.blockedEmployeeCount).toBe(0);
  const run = await postJson<{ runId: string }>(
    page,
    bearer,
    `/legal-entities/${entity.id}/payroll-runs`,
    {
      ...command,
      expectedVersion: body.expectedVersion,
      inputDigest: body.inputDigest,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      confirmed: true,
    },
    `run-${entity.entityCode}`,
    201,
  );
  entity.runId = run.runId;
}

async function recordProfile(page: Page, bearer: () => string, p: Person) {
  const command = {
    effectiveFrom: HIRE,
    classification: 'STANDARD_RESIDENT',
    cnssSubject: true,
    irppSubject: true,
    evidenceCode: 'EMPLOYMENT_CONTRACT',
  };
  const body = await postJson<{
    version: number;
    previewDigest: string;
    expiresAt: string;
    blockers: string[];
  }>(page, bearer, `/employees/${p.employee}/statutory-profile/preview`, command);
  expect(body.blockers).toEqual([]);
  await postJson(
    page,
    bearer,
    `/employees/${p.employee}/statutory-profile`,
    {
      ...command,
      expectedVersion: body.version,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      confirmed: true,
    },
    `sp-${p.number}`,
    201,
  );
}

/** Activates the synthetic pack for the month, then freezes the statutory calculation. */
async function calculate(page: Page, bearer: () => string, entity: Site, packId: string) {
  const view = await page.request.get(
    `${CORE_API}/legal-entities/${entity.id}/statutory-rule-pack?month=${PERIOD}`,
    { headers: { Authorization: bearer() } },
  );
  expect(view.status()).toBe(200);
  await postJson(
    page,
    bearer,
    `/legal-entities/${entity.id}/statutory-rule-pack/activation`,
    {
      packId,
      fromMonth: PERIOD,
      expectedVersion: ((await view.json()) as { version: number }).version,
      acknowledged: true,
    },
    `act-${entity.entityCode}`,
    201,
  );
  const base = `/legal-entities/${entity.id}/payroll-runs/${entity.runId}/statutory-calculations`;
  const preview = await postJson<{
    status: string;
    expectedVersion: number;
    sourceRunDigest: string;
    profileDigest: string;
    rulePackDigest: string;
    calculationDigest: string;
    expiresAt: string;
  }>(page, bearer, `${base}/preview`, {});
  expect(preview.status).toBe('READY');
  const created = await postJson<{ calculationId: string }>(
    page,
    bearer,
    base,
    {
      expectedVersion: preview.expectedVersion,
      sourceRunDigest: preview.sourceRunDigest,
      profileDigest: preview.profileDigest,
      rulePackDigest: preview.rulePackDigest,
      calculationDigest: preview.calculationDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    },
    `calc-${entity.entityCode}`,
    201,
  );
  entity.calculationId = created.calculationId;
}

/** Approves the French legal entity's calculation through the public endpoints (setup only). */
async function approveViaApi(page: Page, bearer: () => string, entity: Site) {
  const base = `/legal-entities/${entity.id}/payroll-runs/${entity.runId}/statutory-calculations/${entity.calculationId}/approval`;
  const preview = await postJson<{
    status: string;
    expectedVersion: number;
    sourceRunDigest: string;
    calculationDigest: string;
    profileDigest: string;
    rulePackDigest: string;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `${base}/preview`, {});
  expect(preview.status).toBe('READY');
  const receipt = await postJson<{ packageId: string }>(
    page,
    bearer,
    base,
    {
      expectedVersion: preview.expectedVersion,
      sourceRunDigest: preview.sourceRunDigest,
      calculationDigest: preview.calculationDigest,
      profileDigest: preview.profileDigest,
      rulePackDigest: preview.rulePackDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedTotals: true,
      reviewedExceptions: true,
      understandsNoPayment: true,
    },
    `appr-${entity.entityCode}`,
    201,
  );
  return receipt.packageId;
}

/** Invites an employee, sets the password through the emailed link and links the record. */
async function inviteAndLink(
  browser: import('@playwright/test').Browser,
  page: Page,
  bearer: () => string,
  p: Person,
) {
  expect(
    (
      await post(page, bearer, '/invitations', `inv-${p.number}`, {
        email: p.email,
        role: 'employee',
        locale: 'fr',
      })
    ).status(),
  ).toBe(201);
  const link = /https?:\/\/[^\s"<>]+\/invitation#token=([A-Za-z0-9_-]{43})/u.exec(
    await mailTo(p.email, /Invitation (à rejoindre|to join)/u),
  );
  expect(link, 'invitation link').not.toBeNull();
  expect(await acceptInvitation(page, link?.[1] ?? '')).toBe(200);
  const setup = /https?:\/\/[^\s"<>]+\/login-actions\/action-token\?[^\s"<>]+/u.exec(
    await mailTo(p.email, /mot de passe|password|actions|compte|account/iu),
  );
  expect(setup, 'password setup link').not.toBeNull();
  const context = await browser.newContext({ locale: 'fr-FR' });
  const setupPage = await context.newPage();
  await openSecretLink(setupPage, setup?.[0] ?? '');
  const proceed = setupPage.locator('a[href*="login-actions"], #kc-info-message a').first();
  if ((await setupPage.locator('#password-new').count()) === 0 && (await proceed.count()) > 0) {
    await proceed.click();
  }
  await typeSecret(setupPage, '#password-new', p.password);
  await typeSecret(setupPage, '#password-confirm', p.password);
  await setupPage.locator('[type="submit"]').first().click();
  await context.close();
  const lookup = await page.request.post(`${CORE_API}/employees/${p.employee}/access-link/lookup`, {
    headers: { Authorization: bearer() },
    data: { email: p.email },
  });
  expect(lookup.status()).toBe(200);
  expect(
    (
      await post(page, bearer, `/employees/${p.employee}/access-link`, `link-${p.number}`, {
        membershipId: ((await lookup.json()) as { membershipId: string }).membershipId,
      })
    ).status(),
  ).toBe(201);
}

async function choose(page: Page, testId: string, entity: Site) {
  await page.getByTestId(testId).selectOption({ label: `${entity.name} (${entity.entityCode})` });
}

/** Previews and commits a salary-advance participation policy through the public endpoints. */
async function recordAdvancePolicy(page: Page, bearer: () => string, entity: Site, code: string) {
  const command = {
    currency: 'CDF',
    code,
    effectiveFrom: TODAY,
    effectiveTo: null,
    enabled: true,
    minTenureMonths: 0,
    maxPercent: 30,
    absoluteMax: null,
    minRequest: { currency: 'CDF', amount: '1000' },
    maxOpenRequests: 1,
    waitingDays: 30,
    consentVersion: 'SA-CONSENT-1',
    partnerCode: 'SYNTHETIC-TEST',
  };
  const preview = await postJson<{
    version: number;
    previewDigest: string;
    expiresAt: string;
    blockers: string[];
  }>(page, bearer, `/legal-entities/${entity.id}/salary-advance-policies/preview`, command);
  expect(preview.blockers).toEqual([]);
  await postJson(
    page,
    bearer,
    `/legal-entities/${entity.id}/salary-advance-policies`,
    {
      policy: command,
      expectedVersion: preview.version,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    },
    `sap-${entity.entityCode}`,
    201,
  );
}

const BANNED =
  /approved loan|guarantee|funded|interest rate|\bAPR\b|credit score|prêt approuvé|garanti|financé|taux d.intérêt|score de crédit/iu;

async function expectNoLendingClaim(page: Page) {
  expect(await page.locator('main').innerText()).not.toMatch(BANNED);
}

/** Nothing about an amount, a note or a consent is in the URL or in browser storage. */
async function expectNothingLeaks(page: Page, ...secrets: string[]) {
  const url = page.url();
  const kept = await storage(page);
  for (const secret of secrets) {
    expect(url).not.toContain(secret);
    expect(kept).not.toContain(secret);
  }
}

let releaseSubject: () => void = () => undefined;
let packId = '';

test.describe.serial('MVP-120A: consented salary-advance requests', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
    // The other specs writing as tenant A's administrator never run at the same time.
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    for (const entity of [EN_ENTITY, FR_ENTITY]) {
      const created = await post(page, bearer, '/legal-entities', `le-${entity.entityCode}`, {
        code: entity.entityCode,
        name: entity.name,
        countryCode: 'CD',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(created.status()).toBe(201);
      entity.id = ((await created.json()) as { id: string }).id;
      const site = await post(page, bearer, '/sites', `st-${entity.entityCode}`, {
        legalEntityId: entity.id,
        code: entity.siteCode,
        name: `Site ${entity.name}`,
        timezone: 'Africa/Kinshasa',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(site.status()).toBe(201);
      expect(
        (
          await post(page, bearer, '/departments', `dp-${entity.entityCode}`, {
            siteId: ((await site.json()) as { id: string }).id,
            code: entity.departmentCode,
            name: `Opérations ${entity.name}`,
            effectiveFrom: plusDays(TODAY, -400),
          })
        ).status(),
      ).toBe(201);
    }
    const people = [ANA, CLO];
    const file = [
      'Matricule;Prénoms;Nom de famille;Date d’entrée;Code de l’entité juridique;Code du site;Code du département',
      ...people.map(
        (p) =>
          `${p.number};${p.given};${p.family};${HIRE};${p.entity.entityCode};${p.entity.siteCode};${p.entity.departmentCode}`,
      ),
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-sa-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sa-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 2, acknowledgeInvalidRows: false },
    });
    expect(commit.status()).toBe(200);
    for (const p of people) {
      const found = await page.request.post(`${CORE_API}/employees/search`, {
        headers: { Authorization: bearer() },
        data: { query: p.number, limit: 5 },
      });
      expect(found.status()).toBe(200);
      p.employee =
        ((await found.json()) as { items: { id: string; employeeNumber: string }[] }).items.find(
          (item) => item.employeeNumber === p.number,
        )?.id ?? '';
      expect(p.employee).not.toBe('');
    }
    await establish(page, bearer, ANA, '1000000');
    await establish(page, bearer, CLO, '800000');
    for (const entity of [EN_ENTITY, FR_ENTITY]) {
      await recordPolicy(page, bearer, entity);
      await createRun(page, bearer, entity);
    }
    for (const p of people) await recordProfile(page, bearer, p);
    // The clearly synthetic VERIFIED pack exists in the database only (no API can verify a pack).
    packId = createSyntheticVerifiedPack(PACK_CODE);
    for (const entity of [EN_ENTITY, FR_ENTITY]) await calculate(page, bearer, entity, packId);
    // Both payrolls are approved through the public endpoints: the salary-advance reference is the
    // employee's latest approved payslip.
    await approveViaApi(page, bearer, EN_ENTITY);
    await approveViaApi(page, bearer, FR_ENTITY);
    // The French legal entity's participation policy is recorded here; the English one is added
    // through the page in journey 1.
    await recordAdvancePolicy(page, bearer, FR_ENTITY, FR_POLICY_CODE);
    for (const p of people) await inviteAndLink(browser, page, bearer, p);
    await page.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('English administrator adds a policy; the employee requests with consent; the employer confirms', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    await signIn(admin, USERS.adminA, 'en');

    // 1. Policy: preview, then an explicit commit.
    await primaryNav(admin)
      .getByRole('link', { name: 'Salary-advance policy', exact: true })
      .click();
    await expect(admin.getByTestId('salary-advance-notice')).toHaveText(
      'DivalHR is not the lender. Employer eligibility is not credit approval. No funds are sent in this step.',
    );
    await choose(admin, 'policy-entity', EN_ENTITY);
    await expect(admin.getByTestId('policy-empty')).toBeVisible();
    await admin.getByTestId('policy-code').fill(POLICY_CODE);
    await admin.getByTestId('policy-effectiveFrom').fill(TODAY);
    await admin.getByTestId('policy-minTenureMonths').fill('0');
    await admin.getByTestId('policy-minRequest').fill('1000');
    await admin.getByTestId('policy-preview').click();
    await expect(admin.getByTestId('policy-preview-result')).toBeVisible();
    await expectAccessible(admin);
    await admin.getByTestId('policy-commit').click();
    await expect(admin.getByTestId('policy-committed')).toBeVisible();
    await expect(admin.getByTestId('policy-row')).toHaveAttribute('data-status', 'IN_FORCE');
    await expectNoLendingClaim(admin);
    await noOverflow(admin);

    // 2. The employee: notice, ceiling, bilingual consent, submission.
    const annaContext = await browser.newContext({ locale: 'en-US' });
    const anna = await annaContext.newPage();
    await signIn(anna, [ANA.email, ANA.password], 'en', undefined);
    await primaryNav(anna).getByRole('link', { name: 'Salary advance', exact: true }).click();
    await expect(anna.getByTestId('salary-advance-notice')).toHaveText(
      'DivalHR is not the lender. Employer eligibility is not credit approval. No funds are sent in this step.',
    );
    const card = anna.locator('[data-testid="salary-advance-currency"][data-currency="CDF"]');
    await expect(card).toHaveAttribute('data-status', 'ELIGIBLE');
    await expect(card.getByTestId('eligibility-ceiling')).toBeVisible();
    await card.getByTestId('request-amount').fill(AMOUNT);
    await card.getByTestId('request-preview').click();
    const consent = card.getByTestId('consent-document');
    await expect(consent).toHaveAttribute('data-version', 'SA-CONSENT-1');
    await expect(consent).toContainText('What is never shared');
    await expect(card.getByTestId('request-submit')).toBeDisabled();
    await keyboardTabReaches(anna, card.getByTestId('request-consent'));
    await anna.keyboard.press('Space');
    await expect(card.getByTestId('request-submit')).toBeEnabled();
    await expectAccessible(anna);
    await card.getByTestId('request-submit').click();
    await expect(card.getByTestId('request-submitted')).toBeVisible();
    await expect(anna.getByTestId('my-request-row')).toHaveAttribute('data-status', 'SUBMITTED');
    await expectNoLendingClaim(anna);
    await expectNothingLeaks(anna, AMOUNT);
    await noOverflow(anna);

    // 3. The employer confirms its participation (not credit approval).
    await primaryNav(admin)
      .getByRole('link', { name: 'Salary-advance requests', exact: true })
      .click();
    await admin
      .getByTestId('request-row')
      .filter({ hasText: ANA.number })
      .getByTestId('request-open')
      .click();
    await admin.getByTestId('request-confirm-open').click();
    await expect(admin.getByTestId('request-confirm-panel')).toContainText('not credit approval');
    await admin.getByTestId('request-confirm').click();
    await expect(admin.getByTestId('request-row').filter({ hasText: ANA.number })).toHaveAttribute(
      'data-status',
      'EMPLOYER_CONFIRMED',
    );
    await expectNoLendingClaim(admin);
    await expectNothingLeaks(admin, AMOUNT);

    // 4. The employee sees the confirmed status; the amount never left the page body.
    await anna.reload();
    // The reload ended the in-memory session; the identity session is kept.
    await anna.getByRole('banner').getByRole('button', { name: 'Sign in' }).click();
    await expect(anna.getByTestId('my-request-row')).toHaveAttribute(
      'data-status',
      'EMPLOYER_CONFIRMED',
    );
    await expect(anna.getByTestId('my-request-row')).toContainText('Employer confirmed');
    await adminContext.close();
    await annaContext.close();
  });

  test('French employee requests; the administrator refuses with a closed reason; the note stays internal', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const FR_NOTICE =
      'DivalHR n’est pas le prêteur. L’admissibilité de l’employeur n’est pas une approbation de crédit. Aucun fonds n’est envoyé à cette étape.';
    const cloContext = await browser.newContext({ locale: 'fr-FR' });
    const clo = await cloContext.newPage();
    const cloBearer = await signIn(clo, [CLO.email, CLO.password], 'fr', undefined);
    await primaryNav(clo).getByRole('link', { name: 'Avance sur salaire', exact: true }).click();
    await expect(clo.getByTestId('salary-advance-notice')).toHaveText(FR_NOTICE);
    const card = clo.locator('[data-testid="salary-advance-currency"][data-currency="CDF"]');
    await expect(card).toHaveAttribute('data-status', 'ELIGIBLE');
    await card.getByTestId('request-amount').fill(AMOUNT);
    await card.getByTestId('request-preview').click();
    await expect(card.getByTestId('consent-document')).toContainText('Ce qui n’est jamais partagé');
    await card.getByTestId('request-consent').check();
    await expectAccessible(clo);
    await card.getByTestId('request-submit').click();
    await expect(card.getByTestId('request-submitted')).toBeVisible();
    await expectNoLendingClaim(clo);
    await noOverflow(clo);
    await expectNothingLeaks(clo, AMOUNT);

    // The employee cannot read the administrator's list.
    const denied = await clo.request.get(`${CORE_API}/salary-advance-requests`, {
      headers: { Authorization: cloBearer() },
    });
    expect(denied.status()).toBe(403);

    const adminContext = await browser.newContext({ locale: 'fr-FR' });
    const admin = await adminContext.newPage();
    await signIn(admin, USERS.adminA, 'fr');
    await primaryNav(admin)
      .getByRole('link', { name: 'Demandes d’avance sur salaire', exact: true })
      .click();
    await admin
      .getByTestId('request-row')
      .filter({ hasText: CLO.number })
      .getByTestId('request-open')
      .click();
    await admin.getByTestId('request-refuse-open').click();
    await admin.getByTestId('request-refuse').click();
    await expect(admin.getByRole('alert')).toBeVisible();
    await admin.getByTestId('request-refuse-reason').selectOption('OTHER_POLICY_REASON');
    await admin.getByTestId('request-refuse-note').fill(NOTE);
    await admin.getByTestId('request-refuse').click();
    await expect(admin.getByTestId('request-row').filter({ hasText: CLO.number })).toHaveAttribute(
      'data-status',
      'EMPLOYER_REFUSED',
    );
    await expectNoLendingClaim(admin);
    await expectNothingLeaks(admin, AMOUNT, NOTE);

    // The employee sees the refusal and the waiting period, never the internal note.
    await clo.reload();
    await clo.getByRole('banner').getByRole('button', { name: 'Se connecter' }).click();
    await expect(clo.getByTestId('my-request-row')).toHaveAttribute(
      'data-status',
      'EMPLOYER_REFUSED',
    );
    await expect(
      clo.locator('[data-testid="salary-advance-currency"][data-currency="CDF"]'),
    ).toHaveAttribute('data-status', 'NOT_ELIGIBLE');
    await expect(clo.locator('[data-reason="WAITING_PERIOD"]')).toBeVisible();
    expect(await clo.locator('main').innerText()).not.toContain(NOTE);
    await expectNoLendingClaim(clo);
    await adminContext.close();
    await cloContext.close();
  });
});

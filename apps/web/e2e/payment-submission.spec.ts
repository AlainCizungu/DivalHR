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

// MVP-095B (Issue #187): exactly two journeys against the SYNTHETIC-TEST provider (an HTTPS receiver
// inside the dev network; no real bank, no real money, nothing is ever "paid").
// (1) English: the administrator reviews a prepared batch, confirms by keyboard, submits it, sees
//     the provider accept it for processing (reference, attempt history, the "not paid" notice),
//     the cancellation panel disappears and the package stays blocked from voiding. The synthetic
//     provider's own log proves one signed delivery with the right counts and no destination data.
// (2) French: the provider rejects a second batch (state, reason, no resend, cancellation closed);
//     a third batch is delivered to a provider that never answers and ends as "livraison inconnue"
//     and is never resent; the linked employee and another tenant's administrator are refused; no
//     account number, holder or phone appears in the page, storage or provider log.
// Setup uses only public Core API endpoints plus the test-only synthetic pack. The provider's control
// port (E2E_PAYMENT_PROVIDER_URL) scripts its answer. No retry, no arbitrary sleep: every wait is an
// auto-waiting assertion on the visible state (the 429 wait of the shared setup helper is the one
// documented exception, as in payments.spec.ts).

const PROVIDER_CONTROL = process.env.E2E_PAYMENT_PROVIDER_URL ?? 'http://127.0.0.1:8097';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-SB-${stamp}`;
const ACCOUNT = `SYNTHSUB${stamp}88 4411`;
const ACCOUNT_NORMALIZED = ACCOUNT.replaceAll(' ', '');
const PHONE = '+243990003344';
const HOLDER = `Holder Soumission ${stamp}`;
const SENTINELS = [ACCOUNT_NORMALIZED, PHONE, HOLDER];

interface Site {
  entityCode: string;
  siteCode: string;
  departmentCode: string;
  name: string;
  id: string;
  runId: string;
  calculationId: string;
  packageId: string;
  batchId: string;
}
const site = (suffix: string, name: string): Site => ({
  entityCode: `SB${suffix}-${stamp}`,
  siteCode: `SBT${suffix}-${stamp}`,
  departmentCode: `SBD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
  calculationId: '',
  packageId: '',
  batchId: '',
});
// One legal entity per batch: a batch can be submitted once, forever.
const ACCEPTED = site('A', 'Submission Accepted');
const REJECTED = site('R', 'Submission Rejected');
const UNKNOWN = site('U', 'Submission Unknown');

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
  number: `SUB-${stamp}-${suffix}`,
  given,
  family: `Kabongo${letters}${suffix.toLowerCase()}`,
  email: `e2e.soumission.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Soumission${suffix}!`,
  entity,
  employee: '',
  linked,
});
const ANA = person('A', 'Anna', ACCEPTED, true);
const ROB = person('R', 'Roger', REJECTED, false);
const UGO = person('U', 'Ursule', UNKNOWN, false);

/** axe at the top of the page (the sticky top bar would otherwise cover the last control). */
async function accessible(page: Page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
  });
  await expectAccessible(page);
}

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sb-${key}-${stamp}-0001` },
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
      ...(key ? { 'Idempotency-Key': `e2e-sb-${key}-${stamp}-0001` } : {}),
    },
    data,
  });
  for (let attempt = 0; attempt < 3 && response.status() === 429; attempt += 1) {
    const wait = Number(response.headers()['retry-after'] ?? '5');
    await page.waitForTimeout((Number.isFinite(wait) ? Math.min(wait, 65) : 5) * 1000 + 250);
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key ? { 'Idempotency-Key': `e2e-sb-${key}-${stamp}-0001` } : {}),
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

const get = (page: Page, bearer: () => string, path: string) =>
  page.request.get(`${CORE_API}${path}`, { headers: { Authorization: bearer() } });

/** Client-side navigation keeps the in-memory session (a reload would sign the user out). */
async function navigateInApp(page: Page, path: string) {
  await page.evaluate((target) => {
    window.history.pushState({}, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/** Records a destination through the public preview and commit endpoints (setup only). */
async function recordDestination(
  page: Page,
  bearer: () => string,
  p: Person,
  command: Record<string, unknown>,
) {
  const body = await postJson<{
    status: string;
    expectedVersion: number;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `/employees/${p.employee}/payment-destination/preview`, command);
  expect(body.status).toBe('READY');
  await postJson(
    page,
    bearer,
    `/employees/${p.employee}/payment-destination/commit`,
    {
      ...command,
      expectedVersion: body.expectedVersion,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      reviewedDetails: true,
    },
    `dest-${p.number}`,
    201,
  );
}

/** Prepares the payment batch of an approved package through the public endpoints (setup only). */
async function prepareBatch(page: Page, bearer: () => string, entity: Site) {
  const base = `/legal-entities/${entity.id}/payroll-packages/${entity.packageId}/payment-batches`;
  const preview = await postJson<{
    status: string;
    expectedVersion: number;
    packageVersion: number;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `${base}/preview`, {});
  expect(preview.status).toBe('READY');
  const receipt = await postJson<{ batchId: string }>(
    page,
    bearer,
    base,
    {
      expectedVersion: preview.expectedVersion,
      packageVersion: preview.packageVersion,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedTotalsAndDestinations: true,
      noMoneySentAcknowledged: true,
    },
    `batch-${entity.entityCode}`,
    201,
  );
  entity.batchId = receipt.batchId;
}

/** Submits a prepared batch through the public endpoints (the third batch only). */
async function submitViaApi(page: Page, bearer: () => string, entity: Site) {
  const base = `/legal-entities/${entity.id}/payment-batches/${entity.batchId}/submissions`;
  const preview = await postJson<{
    status: string;
    batchVersion: number;
    batchDigest: string;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `${base}/preview`, {});
  expect(preview.status).toBe('READY');
  await postJson(
    page,
    bearer,
    base,
    {
      expectedBatchVersion: preview.batchVersion,
      batchDigest: preview.batchDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedProviderAndTotals: true,
      understandAcceptedIsNotPaid: true,
    },
    `sub-${entity.entityCode}`,
    201,
  );
}

interface Received {
  requestId: string | null;
  signatureValid: boolean;
  replay: boolean;
  outcome: string | null;
  lineCount: number;
}

/** The synthetic provider's own log: what it received, never what it was told to hide. */
async function received(page: Page): Promise<Received[]> {
  const response = await page.request.get(`${PROVIDER_CONTROL}/control/received`);
  expect(response.status()).toBe(200);
  return (await response.json()) as Received[];
}

async function control(page: Page, path: string, data?: object) {
  const response = await page.request.post(`${PROVIDER_CONTROL}/control/${path}`, { data });
  expect(response.status()).toBe(200);
}

/** The submission panel opens, reviews and confirms by keyboard; returns once queued. */
async function submitByKeyboard(page: Page, panel: Locator) {
  await expect(panel.getByTestId('submission-none')).toBeVisible();
  await panel.getByTestId('submission-review-button').click();
  const review = panel.getByTestId('submission-preview');
  await expect(review.getByTestId('submission-preview-status')).toBeVisible();
  await expect(review.getByTestId('submission-synthetic-badge')).toHaveText('SYNTHETIC-TEST');
  await expect(review.getByTestId('submission-preview-lines')).toHaveText('1');
  await expect(review.getByTestId('submission-commit')).toBeDisabled();
  for (const key of ['reviewed', 'understand']) {
    const box = review.getByTestId(`submission-ack-${key}`);
    await keyboardTabReaches(page, box);
    await page.keyboard.press('Space');
    await expect(box).toBeChecked();
  }
  await review.getByTestId('submission-commit').click();
}

async function expectNoSentinels(page: Page, scope: Locator) {
  for (const value of SENTINELS) await expect(scope).not.toContainText(value);
  const stored = await storage(page);
  for (const value of SENTINELS) expect(stored).not.toContain(value);
}

let releaseSubject: () => void = () => undefined;
let packId = '';

test.describe.serial('MVP-095B: provider submission of a payment batch', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(900_000);
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    await control(page, 'reset');
    const entities = [ACCEPTED, REJECTED, UNKNOWN];
    for (const entity of entities) {
      const created = await post(page, bearer, '/legal-entities', `le-${entity.entityCode}`, {
        code: entity.entityCode,
        name: entity.name,
        countryCode: 'CD',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(created.status()).toBe(201);
      entity.id = ((await created.json()) as { id: string }).id;
      const createdSite = await post(page, bearer, '/sites', `st-${entity.entityCode}`, {
        legalEntityId: entity.id,
        code: entity.siteCode,
        name: `Site ${entity.name}`,
        timezone: 'Africa/Kinshasa',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(createdSite.status()).toBe(201);
      expect(
        (
          await post(page, bearer, '/departments', `dp-${entity.entityCode}`, {
            siteId: ((await createdSite.json()) as { id: string }).id,
            code: entity.departmentCode,
            name: `Opérations ${entity.name}`,
            effectiveFrom: plusDays(TODAY, -400),
          })
        ).status(),
      ).toBe(201);
    }
    const people = [ANA, ROB, UGO];
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
        'Idempotency-Key': `e2e-sb-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sb-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 3, acknowledgeInvalidRows: false },
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
      await establish(page, bearer, p, '800000');
    }
    packId = createSyntheticVerifiedPack(PACK_CODE);
    for (const entity of entities) await recordPolicy(page, bearer, entity);
    for (const entity of entities) await createRun(page, bearer, entity);
    for (const p of people) await recordProfile(page, bearer, p);
    for (const entity of entities) {
      await calculate(page, bearer, entity, packId);
      entity.packageId = await approveViaApi(page, bearer, entity);
    }
    const bank = (reason: string) => ({
      method: 'BANK_ACCOUNT',
      effectiveFrom: HIRE,
      reasonCode: reason,
      explanation: null,
      countryCode: 'CD',
      providerCode: 'RAWBANK',
      accountNumber: ACCOUNT_NORMALIZED,
      holderName: HOLDER,
      branchCode: 'KIN-01',
      phoneNumber: null,
    });
    const mobile = {
      method: 'MOBILE_MONEY',
      effectiveFrom: HIRE,
      reasonCode: 'INITIAL_SETUP',
      explanation: null,
      countryCode: 'CD',
      providerCode: 'MPESA',
      accountNumber: null,
      holderName: HOLDER,
      branchCode: null,
      phoneNumber: PHONE,
    };
    await recordDestination(page, bearer, ANA, bank('INITIAL_SETUP'));
    await recordDestination(page, bearer, ROB, mobile);
    await recordDestination(page, bearer, UGO, bank('INITIAL_SETUP'));
    for (const entity of entities) await prepareBatch(page, bearer, entity);
    await inviteAndLink(browser, page, bearer, ANA);
    await page.close();
  });

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    await control(page, 'scenario', { scenario: 'accept' });
    await page.close();
    releaseSubject();
  });

  test('English administrator submits a prepared batch, the synthetic provider accepts it for processing, and the batch can no longer be cancelled or voided', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'en');
    await control(admin, 'scenario', { scenario: 'accept' });

    // 1. The prepared batch offers the submission and, while unsubmitted, the cancellation.
    await navigateInApp(admin, `/admin/payment-batches/${ACCEPTED.id}/${ACCEPTED.batchId}`);
    const panel = admin.getByTestId('payment-submission-panel');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Provider submission');
    await expect(admin.getByTestId('payment-cancel-panel')).toBeVisible();

    // 2. Review and keyboard confirmations, then the commit.
    await submitByKeyboard(admin, panel);

    // 3. The provider accepts it for processing: state, reference, history and the fixed notice.
    const badge = panel.getByTestId('submission-state-badge');
    await expect(badge).toHaveText('Accepted for processing', { timeout: 60_000 });
    await expect(panel.getByTestId('submission-reference')).toHaveText(/^SYNTH-[A-F0-9]{12}$/u);
    await expect(panel.getByTestId('submission-notice')).toHaveText(
      'Accepted for processing does not mean paid. DivalHR has not confirmed settlement.',
    );
    await expect(panel.getByTestId('submission-attempt').first()).toBeVisible();
    await expect(panel.getByTestId('submission-lines')).toHaveText('1');
    await expect(admin.getByTestId('payment-cancel-panel')).toHaveCount(0);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);
    await noOverflow(admin);

    // 4. The provider's own log: one signed first delivery with the batch's counts.
    const log = await received(admin);
    expect(log).toHaveLength(1);
    expect(log[0]?.signatureValid).toBe(true);
    expect(log[0]?.replay).toBe(false);
    expect(log[0]?.lineCount).toBe(1);
    expect(log[0]?.outcome).toBe('ACCEPTED');

    // 5. The package stays blocked from voiding.
    await admin.getByTestId('payment-package-link').click();
    const voidPanel = admin.getByTestId('void-panel');
    await voidPanel.getByTestId('void-reason').selectOption('ADMINISTRATIVE_ERROR');
    await voidPanel.getByTestId('void-preview-button').click();
    await expect(voidPanel.getByTestId('void-blockers')).toBeVisible();
    await expect(voidPanel.getByTestId('void-commit')).toBeDisabled();
    await accessible(admin);
    await context.close();
  });

  test('French administrator sees a rejection and an unknown delivery that are never resent, and the employee and a foreign administrator are refused', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(ACCEPTED.batchId, 'setup ran').not.toBe('');

    // 1. Anna reads nothing of the submission: the administrator endpoints are closed to her.
    const annaContext = await browser.newContext({ locale: 'fr-FR' });
    const anna = await annaContext.newPage();
    const annaBearer = await signIn(anna, [ANA.email, ANA.password], 'fr', undefined);
    await primaryNav(anna)
      .getByRole('link', { name: 'Ma destination de paiement', exact: true })
      .click();
    await expect(anna.getByTestId('my-payment-destination')).not.toContainText('SYNTH-');
    for (const path of [
      `/legal-entities/${ACCEPTED.id}/payment-batches/${ACCEPTED.batchId}/submission`,
      `/legal-entities/${ACCEPTED.id}/payment-batches/${ACCEPTED.batchId}/submission/attempts`,
    ]) {
      expect([403, 404], path).toContain((await get(anna, annaBearer, path)).status());
    }
    for (const suffix of ['submissions/preview', 'submissions']) {
      const refused = await anna.request.post(
        `${CORE_API}/legal-entities/${ACCEPTED.id}/payment-batches/${ACCEPTED.batchId}/${suffix}`,
        {
          headers: { Authorization: annaBearer(), 'Idempotency-Key': `e2e-sb-anna-${stamp}-0001` },
          data: {},
        },
      );
      expect([403, 404], suffix).toContain(refused.status());
    }
    await annaContext.close();

    // 2. The provider rejects the second batch: French state, reason, no resend, no cancellation.
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const adminBearer = await signIn(admin, USERS.adminA, 'fr');
    await control(admin, 'reset');
    await control(admin, 'scenario', { scenario: 'reject' });
    await navigateInApp(admin, `/admin/payment-batches/${REJECTED.id}/${REJECTED.batchId}`);
    let panel = admin.getByTestId('payment-submission-panel');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Soumission au prestataire');
    await submitByKeyboard(admin, panel);
    await expect(panel.getByTestId('submission-state-badge')).toHaveText(
      'Rejeté par le prestataire',
      {
        timeout: 60_000,
      },
    );
    await expect(panel.getByTestId('submission-outcome')).toHaveText('Rejeté : non pris en charge');
    await expect(panel.getByTestId('submission-notice')).toHaveText(
      'Accepté pour traitement ne signifie pas payé. DivalHR n’a pas confirmé le règlement.',
    );
    await expect(admin.getByTestId('payment-cancel-panel')).toHaveCount(0);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);
    await noOverflow(admin);
    const afterReject = await received(admin);
    expect(afterReject).toHaveLength(1);
    expect(afterReject[0]?.outcome).toBe('REJECTED_UNSUPPORTED');

    // 3. A provider that never answers: delivery unknown, one delivery, never resent.
    await control(admin, 'reset');
    await control(admin, 'scenario', { scenario: 'unknown' });
    await submitViaApi(admin, adminBearer, UNKNOWN);
    await navigateInApp(admin, `/admin/payment-batches/${UNKNOWN.id}/${UNKNOWN.batchId}`);
    panel = admin.getByTestId('payment-submission-panel');
    await expect(panel.getByTestId('submission-state-badge')).toHaveText('Livraison inconnue', {
      timeout: 90_000,
    });
    await expect(panel.getByTestId('submission-state-help')).toContainText('jamais renvoyées');
    await expect(panel.getByTestId('submission-reference')).toHaveCount(0);
    await expect(admin.getByTestId('payment-cancel-panel')).toHaveCount(0);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);
    const afterUnknown = await received(admin);
    expect(afterUnknown).toHaveLength(1);
    expect(afterUnknown[0]?.outcome).toBe('ACCEPTED_SILENT');
    await context.close();

    // 4. Another tenant's administrator reaches nothing of this one.
    const otherContext = await browser.newContext({ locale: 'fr-FR' });
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    for (const path of [
      `/legal-entities/${REJECTED.id}/payment-batches/${REJECTED.batchId}/submission`,
      `/legal-entities/${REJECTED.id}/payment-batches/${REJECTED.batchId}/submission/attempts`,
    ]) {
      const foreign = await get(other, otherBearer, path);
      expect(foreign.status(), path).toBe(404);
    }
    await navigateInApp(other, `/admin/payment-batches/${REJECTED.id}/${REJECTED.batchId}`);
    await expect(other.getByTestId('payment-batch-detail-failure')).toHaveAttribute(
      'data-kind',
      'notFound',
    );
    await expect(other.getByTestId('payment-submission-panel')).toHaveCount(0);
    await accessible(other);
    await otherContext.close();
  });
});

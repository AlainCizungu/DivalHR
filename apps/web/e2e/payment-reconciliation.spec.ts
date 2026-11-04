import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  CORE_API,
  TENANT_A,
  USERS,
  acceptInvitation,
  expectAccessible,
  mailTo,
  openSecretLink,
  signIn,
  sql,
  subjectLock,
  typeSecret,
} from './support';
import { createSyntheticVerifiedPack } from './synthetic-rule-pack';

// MVP-095C (Issue #190): exactly two journeys against the SYNTHETIC-TEST provider (an HTTPS receiver
// inside the dev network; no real bank, no real money, nothing is ever "paid" or "settled").
// Three prepared batches are submitted while the provider stays silent, so each submission ends as
// DELIVERY_UNKNOWN. A delivery investigation then asks the provider for the STATUS of the existing
// request (it never resends the payment instruction).
// (1) English: the provider answers "accepted"; the administrator opens the investigation by
//     keyboard, sees it run and conclude "Provider confirmed it accepted the payment for processing",
//     reads an authenticated history row and the fixed warning, and the provider's own logs show one
//     payment delivery per request (no resend) and signature-valid inquiries only.
// (2) French: a "pending" answer ends as STILL_UNRESOLVED with manual-follow-up guidance; a "rejected"
//     answer (UNSUPPORTED) ends as CONFIRMED_REJECTED with the translated code; the linked employee
//     and unauthenticated callers are refused, another tenant's administrator gets 404, every
//     reconciliation response is Cache-Control private, no-store, and no destination, holder,
//     phone, amount, raw request ID or signature reaches the page or a response.
// MANUAL_REVIEW_REQUIRED needs six failed inquiries (hours of backoff in production) and an
// administrator without MFA cannot sign in through the web client, so both are covered by the unit
// and Java integration tests instead.
// Setup uses only public Core API endpoints plus the test-only synthetic pack. The provider's control
// port (E2E_PAYMENT_PROVIDER_URL) scripts its answers. No retry and no arbitrary sleep: every wait is
// an auto-waiting assertion on the visible state (the 429 wait of the shared API setup helper is the
// one documented exception, exactly as in payment-submission.spec.ts: the development stack leaves
// the payment limits at their defaults, see scripts/dev/dev-rate-limits.test.mjs).

const PROVIDER_CONTROL = process.env.E2E_PAYMENT_PROVIDER_URL ?? 'http://127.0.0.1:8097';
const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-RC-${stamp}`;
const ACCOUNT = `SYNTHREC${stamp}88 4411`;
const ACCOUNT_NORMALIZED = ACCOUNT.replaceAll(' ', '');
const PHONE = '+243990003344';
const HOLDER = `Holder Verification ${stamp}`;
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
  entityCode: `RC${suffix}-${stamp}`,
  siteCode: `RCT${suffix}-${stamp}`,
  departmentCode: `RCD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
  calculationId: '',
  packageId: '',
  batchId: '',
});
// One legal entity per batch: a batch can be submitted once, forever.
const ACCEPTED = site('A', 'Reconciliation Accepted');
const PENDING = site('P', 'Reconciliation Pending');
const REJECTED = site('R', 'Reconciliation Rejected');

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
  number: `REC-${stamp}-${suffix}`,
  given,
  family: `Kabongo${letters}${suffix.toLowerCase()}`,
  email: `e2e.verification.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Verification${suffix}!`,
  entity,
  employee: '',
  linked,
});
const ANA = person('A', 'Anna', ACCEPTED, true);
const PEN = person('P', 'Paulin', PENDING, false);
const ROB = person('R', 'Roger', REJECTED, false);

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-rc-${key}-${stamp}-0001` },
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
  const send = () =>
    page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key ? { 'Idempotency-Key': `e2e-rc-${key}-${stamp}-0001` } : {}),
      },
      data,
    });
  let response = await send();
  for (let attempt = 0; attempt < 3 && response.status() === 429; attempt += 1) {
    const wait = Number(response.headers()['retry-after'] ?? '5');
    await page.waitForTimeout((Number.isFinite(wait) ? Math.min(wait, 65) : 5) * 1000 + 250);
    response = await send();
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

async function expectNoSentinels(page: Page, scope: Locator) {
  for (const value of SENTINELS) await expect(scope).not.toContainText(value);
  const stored = await storage(page);
  for (const value of SENTINELS) expect(stored).not.toContain(value);
}

const WARNING = {
  en: 'This inquiry does not resend the payment instruction. Provider acceptance does not mean paid or settled.',
  fr: 'Cette vérification ne renvoie pas l’instruction de paiement. L’acceptation du prestataire ne signifie pas que le paiement est effectué ou réglé.',
} as const;
const PAID_WORDS = {
  en: /\b(paid|settled)\b/iu,
  // Whole words only: the French label « Demandes effectuées » (requests made) is not a payment claim.
  fr: /(?<![\p{L}])(payé|payée|réglé|réglée|effectué)(?![\p{L}])/iu,
} as const;

interface Seen {
  url: string;
  status: number;
  cache: string | undefined;
  body: Promise<string>;
}

/** Every reconciliation response the page receives (headers now, body when read). */
function watchReconciliation(page: Page): Seen[] {
  const seen: Seen[] = [];
  page.on('response', (response) => {
    if (response.request().method() === 'OPTIONS') return;
    if (!response.url().includes('/submission/reconciliation')) return;
    seen.push({
      url: response.url(),
      status: response.status(),
      cache: response.headers()['cache-control'],
      body: response.text().catch(() => ''),
    });
  });
  return seen;
}

const FORBIDDEN_KEY =
  /^(requestId|.*amount.*|.*total.*|.*account.*|.*holder.*|.*phone.*|.*destination.*|.*signature.*)$/iu;

function keysOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.flatMap(keysOf);
  if (value !== null && typeof value === 'object') {
    return Object.entries(value).flatMap(([key, inner]) => [key, ...keysOf(inner)]);
  }
  return [];
}

/** Cache-Control, no destination/amount/signature keys, no sentinel and no raw request ID. */
async function expectPrivateResponses(seen: Seen[], requestIds: string[]) {
  expect(seen.length).toBeGreaterThan(0);
  for (const response of seen) {
    // Success responses carry the controller's header; an error problem (for example the 404 before
    // an investigation exists) carries the platform default, which is no-store as well.
    if (response.status < 300) expect(response.cache, response.url).toBe('private, no-store');
    else expect(response.cache, response.url).toContain('no-store');
    const text = await response.body;
    for (const value of [...SENTINELS, ...requestIds])
      expect(text, response.url).not.toContain(value);
    expect(text, response.url).not.toMatch(/v1=[0-9a-f]{16,}/iu);
    try {
      const keys = keysOf(JSON.parse(text) as unknown);
      expect(
        keys.filter((key) => FORBIDDEN_KEY.test(key)),
        response.url,
      ).toEqual([]);
    } catch (error) {
      if (!(error instanceof SyntaxError)) throw error;
    }
  }
}

interface Inquiry {
  inquiryId: string | null;
  requestId: string | null;
  signatureValid: boolean;
  replay: boolean;
  mismatch: boolean;
  result: string | null;
}

/** The synthetic provider's inquiry log (metadata only). */
async function inquiries(page: Page): Promise<Inquiry[]> {
  const response = await page.request.get(`${PROVIDER_CONTROL}/control/inquiries`);
  expect(response.status()).toBe(200);
  return (await response.json()) as Inquiry[];
}

/** The panel text without the fixed warnings and the submission's own notice (which negate "paid"). */
async function textWithoutNotices(scope: Locator, locale: 'en' | 'fr'): Promise<string> {
  let text = await scope.innerText();
  for (const notice of [
    WARNING[locale],
    locale === 'en'
      ? 'Accepted for processing does not mean paid. DivalHR has not confirmed settlement.'
      : 'Accepté pour traitement ne signifie pas payé. DivalHR n’a pas confirmé le règlement.',
  ]) {
    text = text.replaceAll(notice, '');
  }
  return text;
}

/** Opens the investigation by keyboard and confirms both acknowledgements; returns the dialog. */
async function openByKeyboard(page: Page, panel: Locator): Promise<Locator> {
  const open = panel.getByTestId('reconciliation-open');
  await keyboardTabReaches(page, open);
  await expect(open).toBeFocused();
  await page.keyboard.press('Enter');
  const dialog = panel.getByTestId('reconciliation-dialog');
  await expect(dialog.getByTestId('reconciliation-preview-status')).toBeVisible();
  await expect(dialog.getByTestId('reconciliation-fingerprint')).toHaveText(/^[0-9a-f]{12}$/u);
  await expect(dialog.getByTestId('submission-synthetic-badge')).toHaveText('SYNTHETIC-TEST');
  await expect(dialog.getByTestId('reconciliation-confirm')).toBeDisabled();
  for (const key of ['no-resend', 'not-paid']) {
    const box = dialog.getByTestId(`reconciliation-ack-${key}`);
    await keyboardTabReaches(page, box);
    await page.keyboard.press('Space');
    await expect(box).toBeChecked();
  }
  await expect(dialog.getByTestId('reconciliation-confirm')).toBeEnabled();
  return dialog;
}

/** Waits (auto-waiting poll) until the submission of an entity reached DELIVERY_UNKNOWN. */
async function untilUnknown(page: Page, bearer: () => string, entity: Site) {
  await expect
    .poll(
      async () => {
        const response = await get(
          page,
          bearer,
          `/legal-entities/${entity.id}/payment-batches/${entity.batchId}/submission`,
        );
        return response.status() === 200
          ? ((await response.json()) as { state: string }).state
          : '';
      },
      { timeout: 120_000, message: `${entity.entityCode} reaches DELIVERY_UNKNOWN` },
    )
    .toBe('DELIVERY_UNKNOWN');
}

/** Previews and opens an investigation through the public endpoints (the third one only). */
async function investigateViaApi(page: Page, bearer: () => string, entity: Site) {
  const base = `/legal-entities/${entity.id}/payment-batches/${entity.batchId}/submission/reconciliation`;
  const preview = await postJson<{
    status: string;
    submissionVersion: number;
    submissionState: string;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `${base}/preview`, {});
  expect(preview.status).toBe('READY');
  await postJson(
    page,
    bearer,
    base,
    {
      expectedSubmissionVersion: preview.submissionVersion,
      expectedSubmissionState: preview.submissionState,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      understandInquiryDoesNotResend: true,
      understandAcceptedIsNotPaid: true,
    },
    `rec-${entity.entityCode}`,
    201,
  );
}

let releaseSubject: () => void = () => undefined;
let packId = '';

test.describe.serial('MVP-095C: reconciliation of uncertain payment submissions', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(900_000);
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    await control(page, 'reset');
    const entities = [ACCEPTED, PENDING, REJECTED];
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
    const people = [ANA, PEN, ROB];
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
        'Idempotency-Key': `e2e-rc-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-rc-commit-${stamp}-0001` },
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
    const bank = {
      method: 'BANK_ACCOUNT',
      effectiveFrom: HIRE,
      reasonCode: 'INITIAL_SETUP',
      explanation: null,
      countryCode: 'CD',
      providerCode: 'RAWBANK',
      accountNumber: ACCOUNT_NORMALIZED,
      holderName: HOLDER,
      branchCode: 'KIN-01',
      phoneNumber: null,
    };
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
    await recordDestination(page, bearer, ANA, bank);
    await recordDestination(page, bearer, PEN, bank);
    await recordDestination(page, bearer, ROB, mobile);
    for (const entity of entities) await prepareBatch(page, bearer, entity);
    await inviteAndLink(browser, page, bearer, ANA);
    // The provider swallows every payment request: each submission ends as DELIVERY_UNKNOWN.
    await control(page, 'scenario', { scenario: 'unknown' });
    for (const entity of entities) await submitViaApi(page, bearer, entity);
    for (const entity of entities) await untilUnknown(page, bearer, entity);
    await page.close();
  });

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    await control(page, 'reset');
    await page.close();
    releaseSubject();
  });

  test('English administrator investigates an unknown delivery by keyboard, the provider confirms it accepted the payment for processing, and nothing is resent', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(ACCEPTED.batchId, 'setup ran').not.toBe('');
    const context = await browser.newContext();
    const admin = await context.newPage();
    const seen = watchReconciliation(admin);
    await signIn(admin, USERS.adminA, 'en');
    await control(admin, 'inquiry-scenario', { scenario: 'accepted' });
    const deliveriesBefore = await received(admin);
    expect(deliveriesBefore).toHaveLength(3);
    expect(await inquiries(admin)).toEqual([]);

    // 1. The unknown submission offers the investigation; nothing exists yet.
    await navigateInApp(admin, `/admin/payment-batches/${ACCEPTED.id}/${ACCEPTED.batchId}`);
    const submission = admin.getByTestId('payment-submission-panel');
    await expect(submission.getByTestId('submission-state-badge')).toHaveText('Delivery unknown', {
      timeout: 60_000,
    });
    const panel = admin.getByTestId('payment-reconciliation-panel');
    await expect(panel.getByRole('heading', { name: 'Delivery investigation' })).toBeVisible();
    await expect(panel.getByTestId('reconciliation-none')).toBeVisible();
    await expect(panel.getByTestId('reconciliation-open')).toHaveText('Investigate delivery');

    // 2. The review, by keyboard: provider, fingerprint, submission state and the fixed warning.
    const dialog = await openByKeyboard(admin, panel);
    await expect(dialog.getByTestId('reconciliation-preview-status')).toHaveText(
      'Ready to investigate',
    );
    await expect(dialog.getByTestId('reconciliation-preview-submission-state')).toHaveText(
      'Delivery unknown',
    );
    await expect(dialog.getByTestId('reconciliation-warning')).toHaveText(WARNING.en);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);
    await dialog.getByTestId('reconciliation-confirm').click();

    // 3. It runs, then concludes: acceptance for processing, never paid or settled.
    const state = panel.getByTestId('reconciliation-state');
    await expect(state).toHaveText(
      /^(Open: waiting for the next inquiry|Inquiry in progress|Provider confirmed it accepted the payment for processing)$/u,
    );
    await expect(state).toHaveText('Provider confirmed it accepted the payment for processing', {
      timeout: 90_000,
    });
    await expect(panel.getByTestId('reconciliation-outcome')).toHaveText('Accepted for processing');
    await expect(panel.getByTestId('reconciliation-submission-state')).toHaveText(
      'Delivery unknown',
    );
    await expect(submission.getByTestId('submission-state-badge')).toHaveText('Delivery unknown');
    await expect(panel.getByTestId('reconciliation-warning')).toHaveText(WARNING.en);
    await expect(panel.getByTestId('reconciliation-history-warning')).toHaveText(WARNING.en);
    await expect(panel.getByTestId('reconciliation-open')).toHaveCount(0);

    // 4. The history shows an authenticated accepted-for-processing inquiry.
    const history = panel.getByTestId('reconciliation-history');
    const accepted = history
      .getByTestId('reconciliation-inquiry')
      .filter({ hasText: 'Accepted for processing' });
    await expect(accepted.first()).toBeVisible();
    await expect(accepted.first()).toContainText('Yes');
    expect(await textWithoutNotices(panel, 'en')).not.toMatch(PAID_WORDS.en);
    await expect(state).not.toContainText(PAID_WORDS.en);
    await expect(panel.getByTestId('reconciliation-outcome')).not.toContainText(PAID_WORDS.en);

    // 5. Keyboard: the refresh button is reachable and focus is visible (outline, not removed).
    const refresh = panel.getByTestId('reconciliation-refresh');
    await keyboardTabReaches(admin, refresh);
    await expect(refresh).toBeFocused();
    expect(
      await refresh.evaluate((el) => getComputedStyle(el).outlineStyle !== 'none'),
      'visible focus',
    ).toBe(true);

    // 6. The provider's own logs: one delivery per request (no resend), signed inquiries only.
    const deliveriesAfter = await received(admin);
    expect(deliveriesAfter).toHaveLength(3);
    expect(deliveriesAfter.every((entry) => entry.signatureValid && !entry.replay)).toBe(true);
    const asked = await inquiries(admin);
    expect(asked.length).toBeGreaterThan(0);
    expect(asked.every((entry) => entry.signatureValid && !entry.mismatch)).toBe(true);
    expect(asked.some((entry) => entry.result === 'ACCEPTED_FOR_PROCESSING')).toBe(true);
    const requestIds = [...new Set(asked.map((entry) => entry.requestId ?? ''))];
    expect(requestIds).toHaveLength(1);
    expect(deliveriesAfter.filter((entry) => entry.requestId === requestIds[0])).toHaveLength(1);

    // 7. Privacy, accessibility and reflow.
    await expectNoSentinels(admin, admin.getByRole('main'));
    for (const requestId of requestIds) {
      await expect(admin.getByRole('main')).not.toContainText(requestId);
    }
    await expectPrivateResponses(seen, requestIds);
    await accessible(admin);
    await noOverflow(admin);
    await context.close();
  });

  test('French administrator sees a pending answer stay unresolved and a rejection with its translated code, while the employee, anonymous callers and a foreign administrator are refused', async ({
    browser,
  }) => {
    test.setTimeout(420_000);
    expect(PENDING.batchId, 'setup ran').not.toBe('');
    const reconciliation = (entity: Site) =>
      `/legal-entities/${entity.id}/payment-batches/${entity.batchId}/submission/reconciliation`;
    const commitBody = {
      expectedSubmissionVersion: 1,
      expectedSubmissionState: 'DELIVERY_UNKNOWN',
      previewDigest: 'a'.repeat(64),
      expiresAt: '2030-01-01T00:00:00Z',
      understandInquiryDoesNotResend: true,
      understandAcceptedIsNotPaid: true,
    };

    // 1. The linked employee and anonymous callers reach nothing.
    const annaContext = await browser.newContext({ locale: 'fr-FR' });
    const anna = await annaContext.newPage();
    const annaBearer = await signIn(anna, [ANA.email, ANA.password], 'fr', undefined);
    for (const path of [reconciliation(ACCEPTED), `${reconciliation(ACCEPTED)}/inquiries`]) {
      expect([403, 404], path).toContain((await get(anna, annaBearer, path)).status());
      expect((await anna.request.get(`${CORE_API}${path}`)).status(), `${path} anonymous`).toBe(
        401,
      );
    }
    for (const [suffix, data] of [
      ['/preview', {}],
      ['', commitBody],
    ] as const) {
      const refused = await anna.request.post(`${CORE_API}${reconciliation(ACCEPTED)}${suffix}`, {
        headers: { Authorization: annaBearer(), 'Idempotency-Key': `e2e-rc-anna-${stamp}-0001` },
        data,
      });
      expect([403, 404], suffix).toContain(refused.status());
    }
    await annaContext.close();

    // 2. A pending answer: the investigation ends unresolved and asks for a human follow-up.
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const seen = watchReconciliation(admin);
    const adminBearer = await signIn(admin, USERS.adminA, 'fr');
    await control(admin, 'inquiry-scenario', { scenario: 'pending' });
    const deliveries = await received(admin);
    expect(deliveries).toHaveLength(3);
    const askedBefore = (await inquiries(admin)).length;
    await navigateInApp(admin, `/admin/payment-batches/${PENDING.id}/${PENDING.batchId}`);
    await expect(
      admin.getByTestId('payment-submission-panel').getByTestId('submission-state-badge'),
    ).toHaveText('Livraison inconnue', { timeout: 60_000 });
    let panel = admin.getByTestId('payment-reconciliation-panel');
    await expect(
      panel.getByRole('heading', { name: 'Vérification de la livraison' }),
    ).toBeVisible();
    await expect(panel.getByTestId('reconciliation-open')).toHaveText('Vérifier la livraison');
    const dialog = await openByKeyboard(admin, panel);
    await expect(dialog.getByTestId('reconciliation-preview-status')).toHaveText(
      'Prêt pour la vérification',
    );
    await expect(dialog.getByTestId('reconciliation-warning')).toHaveText(WARNING.fr);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);
    await dialog.getByTestId('reconciliation-confirm').click();
    const state = panel.getByTestId('reconciliation-state');
    await expect(state).toHaveText('Toujours non résolue : le prestataire n’a pas pu confirmer', {
      timeout: 90_000,
    });
    await expect(panel.getByTestId('reconciliation-outcome')).toHaveText(
      'Le prestataire détient la demande et n’a pas encore décidé',
    );
    const guidance = panel.getByTestId('reconciliation-guidance');
    await expect(guidance).toContainText('Contactez-le par le canal convenu');
    await expect(guidance).toContainText('DivalHR ne renvoie pas les paiements.');
    await expect(panel.getByTestId('reconciliation-submission-state')).toHaveText(
      'Livraison inconnue',
    );
    await expect(panel.getByTestId('reconciliation-warning')).toHaveText(WARNING.fr);
    await expect(panel.getByTestId('reconciliation-history-warning')).toHaveText(WARNING.fr);
    await expect(
      panel
        .getByTestId('reconciliation-history')
        .getByTestId('reconciliation-inquiry')
        .filter({ hasText: 'Oui' })
        .first(),
    ).toBeVisible();
    expect(await textWithoutNotices(panel, 'fr')).not.toMatch(PAID_WORDS.fr);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);
    await noOverflow(admin);
    expect((await inquiries(admin)).length).toBeGreaterThan(askedBefore);
    expect(await received(admin)).toHaveLength(3);

    // 3. A rejection with an allow-listed code (opened through the public API, shown in the UI).
    await control(admin, 'inquiry-scenario', { scenario: 'rejected', code: 'UNSUPPORTED' });
    await investigateViaApi(admin, adminBearer, REJECTED);
    await navigateInApp(admin, `/admin/payment-batches/${REJECTED.id}/${REJECTED.batchId}`);
    panel = admin.getByTestId('payment-reconciliation-panel');
    await expect(panel.getByTestId('reconciliation-state')).toHaveText(
      'Le prestataire confirme avoir rejeté la demande',
      { timeout: 90_000 },
    );
    await expect(panel.getByTestId('reconciliation-outcome')).toHaveText(
      'Rejeté : non pris en charge',
    );
    await expect(panel.getByTestId('reconciliation-guidance')).toHaveCount(0);
    await expect(panel.getByTestId('reconciliation-warning')).toHaveText(WARNING.fr);
    expect(await textWithoutNotices(panel, 'fr')).not.toMatch(PAID_WORDS.fr);
    await expectNoSentinels(admin, admin.getByRole('main'));
    await accessible(admin);

    // 4. Still one payment delivery per request; every inquiry was signed; no raw request ID leaks.
    const finalDeliveries = await received(admin);
    expect(finalDeliveries).toHaveLength(3);
    const asked = await inquiries(admin);
    expect(asked.every((entry) => entry.signatureValid && !entry.mismatch)).toBe(true);
    expect(asked.some((entry) => entry.result === 'PENDING')).toBe(true);
    expect(asked.some((entry) => entry.result === 'REJECTED')).toBe(true);
    const requestIds = [...new Set(asked.map((entry) => entry.requestId ?? ''))];
    for (const requestId of requestIds) {
      expect(finalDeliveries.filter((entry) => entry.requestId === requestId)).toHaveLength(1);
      await expect(admin.getByRole('main')).not.toContainText(requestId);
    }
    await expectPrivateResponses(seen, requestIds);
    await context.close();

    // 5. Another tenant's administrator gets the same 404 for everything, and no panel.
    const otherContext = await browser.newContext({ locale: 'fr-FR' });
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    for (const path of [reconciliation(PENDING), `${reconciliation(PENDING)}/inquiries`]) {
      const foreign = await get(other, otherBearer, path);
      expect(foreign.status(), path).toBe(404);
      expect(foreign.headers()['cache-control'], path).toContain('no-store');
      expect(((await foreign.json()) as { code: string }).code).toBe(
        'PAYMENT_RECONCILIATION_NOT_FOUND',
      );
    }
    for (const [suffix, data] of [
      ['/preview', {}],
      ['', commitBody],
    ] as const) {
      const foreign = await other.request.post(`${CORE_API}${reconciliation(PENDING)}${suffix}`, {
        headers: { Authorization: otherBearer(), 'Idempotency-Key': `e2e-rc-other-${stamp}-0001` },
        data,
      });
      expect(foreign.status(), suffix).toBe(404);
      expect(foreign.headers()['cache-control'], suffix).toContain('no-store');
    }
    await navigateInApp(other, `/admin/payment-batches/${PENDING.id}/${PENDING.batchId}`);
    await expect(other.getByTestId('payment-batch-detail-failure')).toHaveAttribute(
      'data-kind',
      'notFound',
    );
    await expect(other.getByTestId('payment-reconciliation-panel')).toHaveCount(0);
    await accessible(other);
    await otherContext.close();
  });
});

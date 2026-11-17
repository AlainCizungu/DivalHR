import { expect, test, type Locator, type Page } from '@playwright/test';
import {
  CORE_API,
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

// MVP-120B (Issue #206): exactly two journeys, with synthetic data only (the partner is the
// controlled SYNTHETIC-TEST receiver; no lender, bank, bureau or real endpoint exists).
// (1) English: an employer-confirmed request; the employee previews the handoff, reads the fresh
//     consent, affirms it and authorizes; the controlled receiver acknowledges; the partner reports
//     "under review"; the administrator and the employee see the same bounded status. Nothing about
//     the amount or the consent is in the URL or in browser storage.
// (2) French: consent is refused (the control stays disabled and the API refuses it); a handoff
//     waiting for a retry is withdrawn by the employee; a handoff whose delivery is uncertain is
//     never resent by DivalHR, the administrator reconciles it with the partner, and the receiver
//     holds exactly one copy. A non-administrator cannot read the administrator's list.
// Setup uses only public Core API endpoints plus the test-only synthetic pack; requests are created
// and employer-confirmed through the public endpoints. The receiver's control port
// (E2E_PARTNER_RECEIVER_URL) scripts its answers. No arbitrary sleep: every wait polls state.
// Authorization, signing, replay, tenant isolation and races are covered by the Core tests.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-${stamp}`;
const EN_POLICY_CODE = `SAP-${stamp}`;
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
// Anna (English journey), Clovis and Denise (French journey) have a login.
const ANA = person('A', 'Anna', EN_ENTITY, true);
const CLO = person('C', 'Clovis', FR_ENTITY, true);
const DEN = person('D', 'Denise', FR_ENTITY, true);

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/**
 * Tenant B hosts this journey: tenant A's administrator and its 50-per-hour invitation quota are
 * already close to their limits across the other journeys.
 */
const TENANT_B = '00000000-0000-4000-8000-00000000000b';

/** Today in the organization's business time zone, as the Core computes it. */
function today(): string {
  return sql(
    `SELECT (now() AT TIME ZONE timezone)::date::text FROM tenant.organization
     WHERE id = '${TENANT_B}'`,
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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sh-${key}-${stamp}-0001` },
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
      ...(key ? { 'Idempotency-Key': `e2e-sh-${key}-${stamp}-0001` } : {}),
    },
    data,
  });
  for (let attempt = 0; attempt < 3 && response.status() === 429; attempt += 1) {
    const wait = Number(response.headers()['retry-after'] ?? '5');
    await page.waitForTimeout((Number.isFinite(wait) ? Math.min(wait, 65) : 5) * 1000 + 250);
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key ? { 'Idempotency-Key': `e2e-sh-${key}-${stamp}-0001` } : {}),
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
  /approved loan|guarantee|funded|interest rate|(?<!\p{L})APR(?!\p{L})|credit score|prêt approuvé|garanti|financé|taux d.intérêt|score de crédit/iu;

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

const RECEIVER = process.env.E2E_PARTNER_RECEIVER_URL ?? 'http://127.0.0.1:8096';
const EN_NOTICE =
  'This is a controlled test with a synthetic partner. No real lender, bank or institution takes part, nothing is decided or paid out, and nothing is deducted from your salary.';
const FR_NOTICE =
  'Il s’agit d’un test contrôlé avec un partenaire synthétique. Aucun prêteur, aucune banque ni institution réelle n’y participe, rien n’est décidé ni versé, et rien n’est retenu sur votre salaire.';

interface HandoffItem {
  handoffId: string;
  requestId: string;
  state: string;
  reviewState: string | null;
  version: number;
  applicationId: string | null;
  employeeNumber: string | null;
}

async function control(page: Page, path: string, data?: object) {
  const response = await page.request.post(`${RECEIVER}/control/${path}`, { data });
  expect(response.status(), `receiver ${path}`).toBe(200);
  return (await response.json()) as Record<string, unknown>;
}

async function receivedBy(page: Page, applicationId: string): Promise<number> {
  const response = await page.request.get(`${RECEIVER}/control/received`);
  expect(response.status()).toBe(200);
  return ((await response.json()) as { applicationId: string | null }[]).filter(
    (entry) => entry.applicationId === applicationId,
  ).length;
}

async function listHandoffs(page: Page, bearer: () => string, path: string) {
  const response = await page.request.get(`${CORE_API}${path}`, {
    headers: { Authorization: bearer() },
  });
  expect(response.status(), path).toBe(200);
  return ((await response.json()) as { items: HandoffItem[] }).items;
}

/** The opaque application id of a handoff, as only the administrator's detail view carries it. */
async function applicationOf(page: Page, bearer: () => string, handoffId: string): Promise<string> {
  const response = await page.request.get(`${CORE_API}/salary-advance-handoffs/${handoffId}`, {
    headers: { Authorization: bearer() },
  });
  expect(response.status()).toBe(200);
  const id = ((await response.json()) as { applicationId: string | null }).applicationId ?? '';
  expect(id).toMatch(/^[0-9a-f-]{36}$/u);
  return id;
}

/** The employee's own handoff of a request, polled until a condition holds (no fixed sleep). */
async function waitForMine(
  page: Page,
  bearer: () => string,
  requestId: string,
  accept: (item: HandoffItem) => boolean,
): Promise<HandoffItem> {
  let found: HandoffItem | undefined;
  await expect
    .poll(
      async () => {
        found = (await listHandoffs(page, bearer, '/me/salary-advance-handoffs')).find(
          (item) => item.requestId === requestId,
        );
        return found !== undefined && accept(found);
      },
      { timeout: 90_000, intervals: [500, 1000, 2000] },
    )
    .toBe(true);
  return found as HandoffItem;
}

/** An employer-confirmed request through the public endpoints (setup only). */
async function confirmedRequest(
  page: Page,
  employee: () => string,
  admin: () => string,
  p: Person,
  language: 'en' | 'fr',
): Promise<string> {
  const command = { currency: 'CDF', amount: { currency: 'CDF', amount: AMOUNT } };
  const preview = await postJson<{ previewDigest: string; expiresAt: string; blockers: string[] }>(
    page,
    employee,
    '/me/salary-advance-requests/preview',
    command,
  );
  expect(preview.blockers).toEqual([]);
  const created = await postJson<{ requestId: string; version: number }>(
    page,
    employee,
    '/me/salary-advance-requests',
    {
      ...command,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      consent: { version: 'SA-CONSENT-1', language, affirmed: true },
    },
    `req-${p.number}`,
    201,
  );
  await postJson(
    page,
    admin,
    `/salary-advance-requests/${created.requestId}/confirm-employer`,
    { expectedVersion: created.version, confirmed: true },
    `conf-${p.number}`,
  );
  return created.requestId;
}

/** Leaves the page and returns, so that the list is read again from Core. */
async function revisit(page: Page, other: string, own: string) {
  await primaryNav(page).getByRole('link', { name: other, exact: true }).click();
  await primaryNav(page).getByRole('link', { name: own, exact: true }).click();
}

let releaseSubject: () => void = () => undefined;
let packId = '';

test.describe.serial('MVP-120B: controlled partner handoff', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
    // The other specs writing as tenant B's administrator never run at the same time.
    releaseSubject = await subjectLock(USERS.adminB[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminB, 'fr');
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
    const people = [ANA, CLO, DEN];
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
        'Idempotency-Key': `e2e-sh-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-sh-commit-${stamp}-0001` },
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
      await establish(page, bearer, p, '1000000');
    }
    for (const entity of [EN_ENTITY, FR_ENTITY]) {
      await recordPolicy(page, bearer, entity);
      await createRun(page, bearer, entity);
    }
    for (const p of people) await recordProfile(page, bearer, p);
    // The clearly synthetic VERIFIED pack exists in the database only (no API can verify a pack).
    packId = await createSyntheticVerifiedPack(PACK_CODE);
    for (const entity of [EN_ENTITY, FR_ENTITY]) await calculate(page, bearer, entity, packId);
    await approveViaApi(page, bearer, EN_ENTITY);
    await approveViaApi(page, bearer, FR_ENTITY);
    // Participation policies: one open request per employee is enough here.
    await recordAdvancePolicy(page, bearer, EN_ENTITY, EN_POLICY_CODE);
    await recordAdvancePolicy(page, bearer, FR_ENTITY, FR_POLICY_CODE);
    for (const p of people) await inviteAndLink(browser, page, bearer, p);
    await page.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('English employee authorizes the handoff; the receiver acknowledges; both sides see the bounded status', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const adminContext = await browser.newContext();
    const admin = await adminContext.newPage();
    const adminBearer = await signIn(admin, USERS.adminB, 'en');
    await control(admin, 'reset');

    const annaContext = await browser.newContext({ locale: 'en-US' });
    const anna = await annaContext.newPage();
    const annaBearer = await signIn(anna, [ANA.email, ANA.password], 'en', undefined);
    const requestId = await confirmedRequest(anna, annaBearer, adminBearer, ANA, 'en');

    // 1. The employee previews the handoff, reads the fresh consent and authorizes.
    await primaryNav(anna).getByRole('link', { name: 'Salary advance', exact: true }).click();
    await expect(anna.getByTestId('handoff-notice')).toHaveText(EN_NOTICE);
    const candidate = anna.locator(
      `[data-testid="handoff-candidate"][data-request="${requestId}"]`,
    );
    await candidate.getByTestId('handoff-preview').click();
    await expect(anna.getByTestId('handoff-disclosure')).toHaveAttribute(
      'data-version',
      'SA-HANDOFF-1',
    );
    await expect(candidate.getByTestId('handoff-authorize')).toBeDisabled();
    await keyboardTabReaches(anna, candidate.getByTestId('handoff-affirm'));
    await anna.keyboard.press('Space');
    await expect(candidate.getByTestId('handoff-authorize')).toBeDisabled();
    await keyboardTabReaches(anna, candidate.getByTestId('handoff-understand'));
    await anna.keyboard.press('Space');
    await expect(candidate.getByTestId('handoff-authorize')).toBeEnabled();
    await expectAccessible(anna);
    await noOverflow(anna);
    await candidate.getByTestId('handoff-authorize').click();

    // 2. The controlled receiver acknowledges exactly once.
    const mine = await waitForMine(
      anna,
      annaBearer,
      requestId,
      (item) => item.state === 'RECEIVED',
    );
    // The opaque application id is administrator-only: the employee's own view never carries it.
    expect(mine.applicationId ?? null).toBeNull();
    const applicationId = await applicationOf(admin, adminBearer, mine.handoffId);
    expect(applicationId).toMatch(/^[0-9a-f-]{36}$/u);
    expect(await receivedBy(admin, applicationId)).toBe(1);

    // 3. The partner reports a bounded review status through its signed callback.
    const callback = await control(admin, 'callback', {
      applicationId,
      reviewState: 'UNDER_REVIEW',
    });
    expect(callback.status).toBe(204);
    await expect
      .poll(async () => {
        const rows = await listHandoffs(admin, adminBearer, '/salary-advance-handoffs');
        return rows.find((item) => item.handoffId === mine.handoffId)?.reviewState;
      })
      .toBe('UNDER_REVIEW');

    // 4. The employee sees the delivery and the review status, and cannot withdraw any more.
    await revisit(anna, 'My leave', 'Salary advance');
    const row = anna.getByTestId('handoff-row');
    await expect(row).toHaveAttribute('data-state', 'RECEIVED');
    await expect(row).toContainText('Received by the partner');
    await expect(row.getByTestId('handoff-review')).toHaveAttribute('data-review', 'UNDER_REVIEW');
    await expect(row.getByTestId('handoff-review')).toContainText('Under review');
    await expect(row.getByTestId('handoff-withdraw')).toHaveCount(0);
    await expectNoLendingClaim(anna);
    await expectNothingLeaks(anna, AMOUNT);
    await noOverflow(anna);

    // 5. The administrator sees the same bounded status, with no way to resend it.
    await primaryNav(admin).getByRole('link', { name: 'Partner handoffs', exact: true }).click();
    const adminRow = admin.getByTestId('handoff-admin-row').filter({ hasText: ANA.number });
    await expect(adminRow).toHaveAttribute('data-state', 'RECEIVED');
    await expect(adminRow.getByTestId('handoff-review')).toHaveAttribute(
      'data-review',
      'UNDER_REVIEW',
    );
    await adminRow.getByTestId('handoff-open').click();
    await expect(admin.getByTestId('handoff-application-id')).toHaveText(applicationId);
    await expect(admin.getByTestId('handoff-events')).toBeVisible();
    await expect(admin.getByTestId('handoff-reconcile-open')).toHaveCount(0);
    await expectAccessible(admin);
    await expectNoLendingClaim(admin);
    await expectNothingLeaks(admin, AMOUNT, applicationId);
    await noOverflow(admin);
    expect(await receivedBy(admin, applicationId)).toBe(1);
    await adminContext.close();
    await annaContext.close();
  });

  test('French consent can be refused, a waiting handoff withdrawn, and an uncertain delivery is never resent', async ({
    browser,
  }) => {
    test.setTimeout(420_000);
    const adminContext = await browser.newContext({ locale: 'fr-FR' });
    const admin = await adminContext.newPage();
    const adminBearer = await signIn(admin, USERS.adminB, 'fr');
    await control(admin, 'reset');

    // 1. Refusal of consent and withdrawal. One connection closed before any byte is written is the
    //    only failure the sender retries by itself: the handoff waits for its retry, which is when
    //    the employee may still withdraw it. (A 429 or 5xx is never a retry: see part 2.)
    await control(admin, 'scenario', { scenario: 'refuse-connection-once' });
    const cloContext = await browser.newContext({ locale: 'fr-FR' });
    const clo = await cloContext.newPage();
    const cloBearer = await signIn(clo, [CLO.email, CLO.password], 'fr', undefined);
    const cloRequest = await confirmedRequest(clo, cloBearer, adminBearer, CLO, 'fr');
    await primaryNav(clo).getByRole('link', { name: 'Avance sur salaire', exact: true }).click();
    await expect(clo.getByTestId('handoff-notice')).toHaveText(FR_NOTICE);
    const candidate = clo.locator(
      `[data-testid="handoff-candidate"][data-request="${cloRequest}"]`,
    );
    await candidate.getByTestId('handoff-preview').click();
    await expect(candidate.getByTestId('handoff-authorize')).toBeDisabled();
    await candidate.getByTestId('handoff-affirm').check();
    await expect(candidate.getByTestId('handoff-authorize')).toBeDisabled();
    // The API refuses a consent that is not affirmed, with the closed code.
    const preview = await postJson<{
      requestVersion: number;
      previewDigest: string;
      expiresAt: string;
      consent: { version: string };
    }>(clo, cloBearer, `/me/salary-advance-requests/${cloRequest}/handoff/preview`, {});
    const refused = await clo.request.post(
      `${CORE_API}/me/salary-advance-requests/${cloRequest}/handoff`,
      {
        headers: { Authorization: cloBearer(), 'Idempotency-Key': `e2e-sh-refuse-${stamp}-0001` },
        data: {
          expectedRequestVersion: preview.requestVersion,
          previewDigest: preview.previewDigest,
          expiresAt: preview.expiresAt,
          consent: { version: preview.consent.version, language: 'fr', affirmed: false },
          understandsSyntheticTest: true,
        },
      },
    );
    expect(refused.status()).toBe(422);
    expect(((await refused.json()) as { code: string }).code).toBe(
      'SALARY_ADVANCE_HANDOFF_CONSENT_REQUIRED',
    );
    expect(await listHandoffs(clo, cloBearer, '/me/salary-advance-handoffs')).toEqual([]);
    await candidate.getByTestId('handoff-understand').check();
    await expectAccessible(clo);
    await candidate.getByTestId('handoff-authorize').click();
    const waiting = await waitForMine(
      clo,
      cloBearer,
      cloRequest,
      (item) => item.state === 'QUEUED',
    );
    // The list summary carries no attempt count; the administrator's detail does. Wait for the first
    // delivery attempt (answered 503 by the receiver) to be recorded while the handoff is queued again.
    await expect
      .poll(
        async () => {
          const response = await admin.request.get(
            `${CORE_API}/salary-advance-handoffs/${waiting.handoffId}`,
            { headers: { Authorization: adminBearer() } },
          );
          expect(response.status()).toBe(200);
          const detail = (await response.json()) as { state: string; attemptCount: number };
          return detail.state === 'QUEUED' && detail.attemptCount >= 1;
        },
        { timeout: 60_000, intervals: [500, 1000, 2000] },
      )
      .toBe(true);
    await revisit(clo, 'Mes congés', 'Avance sur salaire');
    const waitingRow = clo.getByTestId('handoff-row');
    await expect(waitingRow).toHaveAttribute('data-state', 'QUEUED');
    await expect(waitingRow).toContainText('En attente d’envoi');
    await waitingRow.getByTestId('handoff-withdraw').click();
    await waitingRow.getByTestId('handoff-withdraw-confirm').click();
    await expect(clo.getByTestId('handoff-row')).toHaveAttribute('data-state', 'WITHDRAWN');
    await expect(clo.getByTestId('handoff-row')).toContainText('Retirée');
    await expect(clo.getByTestId('handoff-withdraw')).toHaveCount(0);
    expect((await listHandoffs(clo, cloBearer, '/me/salary-advance-handoffs'))[0]?.state).toBe(
      'WITHDRAWN',
    );
    expect(waiting.requestId).toBe(cloRequest);
    await expectNoLendingClaim(clo);
    await expectNothingLeaks(clo, AMOUNT);
    await noOverflow(clo);

    // A non-administrator cannot read the administrator's list.
    const denied = await clo.request.get(`${CORE_API}/salary-advance-handoffs`, {
      headers: { Authorization: cloBearer() },
    });
    expect(denied.status()).toBe(403);

    // 2. Uncertain delivery: the partner keeps the application and then answers an unsigned 503.
    //    The request was already written, so the sender cannot tell this from a refusal that kept
    //    nothing: the handoff is DELIVERY_UNKNOWN and is never resent without a signed proof.
    await control(admin, 'reset');
    await control(admin, 'scenario', { scenario: 'stored-then-503' });
    const denContext = await browser.newContext({ locale: 'fr-FR' });
    const den = await denContext.newPage();
    const denBearer = await signIn(den, [DEN.email, DEN.password], 'fr', undefined);
    const denRequest = await confirmedRequest(den, denBearer, adminBearer, DEN, 'fr');
    await primaryNav(den).getByRole('link', { name: 'Avance sur salaire', exact: true }).click();
    const denCandidate = den.locator(
      `[data-testid="handoff-candidate"][data-request="${denRequest}"]`,
    );
    await denCandidate.getByTestId('handoff-preview').click();
    await denCandidate.getByTestId('handoff-affirm').check();
    await denCandidate.getByTestId('handoff-understand').check();
    await denCandidate.getByTestId('handoff-authorize').click();
    const unknown = await waitForMine(
      den,
      denBearer,
      denRequest,
      (item) => item.state === 'DELIVERY_UNKNOWN',
    );
    expect(unknown.applicationId ?? null).toBeNull();
    const applicationId = await applicationOf(admin, adminBearer, unknown.handoffId);
    expect(await receivedBy(admin, applicationId)).toBe(1);
    await revisit(den, 'Mes congés', 'Avance sur salaire');
    const unknownRow = den.getByTestId('handoff-row');
    await expect(unknownRow).toHaveAttribute('data-state', 'DELIVERY_UNKNOWN');
    await expect(unknownRow).toContainText('Livraison incertaine');
    await expect(unknownRow.getByTestId('handoff-withdraw')).toHaveCount(0);
    await expectAccessible(den);

    // The administrator checks with the partner; the partner confirms it holds the application.
    await primaryNav(admin)
      .getByRole('link', { name: 'Transmissions au partenaire', exact: true })
      .click();
    const adminRow = admin.getByTestId('handoff-admin-row').filter({ hasText: DEN.number });
    await expect(adminRow).toHaveAttribute('data-state', 'DELIVERY_UNKNOWN');
    await adminRow.getByTestId('handoff-open').click();
    await expect(admin.getByTestId('handoff-application-id')).toHaveText(applicationId);
    await admin.getByTestId('handoff-reconcile-open').click();
    await expectAccessible(admin);
    await admin.getByTestId('handoff-reconcile').click();
    await expect(admin.getByTestId('handoff-detail').getByTestId('handoff-state')).toHaveAttribute(
      'data-state',
      'RECEIVED',
    );
    await expectNoLendingClaim(admin);
    await expectNothingLeaks(admin, AMOUNT, applicationId);
    await noOverflow(admin);

    // 3. Never resent: the receiver still holds exactly one copy, and the employee sees it received.
    expect(await receivedBy(admin, applicationId)).toBe(1);
    await revisit(den, 'Mes congés', 'Avance sur salaire');
    await expect(den.getByTestId('handoff-row')).toHaveAttribute('data-state', 'RECEIVED');
    await expect(den.getByTestId('handoff-withdraw')).toHaveCount(0);
    await expectNoLendingClaim(den);
    await expectNothingLeaks(den, AMOUNT);
    await control(admin, 'reset');
    await adminContext.close();
    await cloContext.close();
    await denContext.close();
  });
});

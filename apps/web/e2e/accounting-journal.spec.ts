import { expect, test, type Download, type Locator, type Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import {
  CORE_API,
  TENANT_A,
  USERS,
  expectAccessible,
  primaryNav,
  signIn,
  sql,
  subjectLock,
} from './support';
import { createSyntheticVerifiedPack } from './synthetic-rule-pack';

// MVP-096 (Issue #194): exactly two journeys, with synthetic data only. The accounting journal is an
// export for manual import: nothing is posted anywhere and nothing proves a payment.
// (1) English: the administrator schedules a CDF accounting mapping through preview and a keyboard
//     confirmation, reviews the journal of a synthetic approved package (exact lines, equal totals),
//     generates it, and downloads the CSV and the manifest: the CSV starts with the UTF-8 BOM, has
//     the contract header and CRLF line ends, its lines balance, and the manifest's csvSha256 equals
//     the SHA-256 of the downloaded bytes.
// (2) French: an employee, another tenant's administrator and an anonymous caller are refused in the
//     UI and the API; a USD mapping does not serve the CDF package (MAPPING_NOT_FOUND with a link to
//     the mappings); a journal review goes stale after a new mapping version
//     (ACCOUNTING_JOURNAL_PREVIEW_STALE, translated) and is repeated; after the package is voided
//     the history shows SOURCE_VOIDED and both downloads are byte-identical to the ones before;
//     no amount or account code reaches a URL or the browser storage; axe and reflow at 320, 390
//     and 640 px.
// A manager and an administrator without MFA cannot be signed in through the web client (the seed has
// no manager, and the web client's default level is MFA): both are covered by the Core integration
// tests of the same story. Setup uses only public Core API endpoints plus the test-only synthetic
// pack. No screenshot gate, no retry and no arbitrary sleep (the 429 wait of the shared API setup
// helper is the one documented exception, as in payments.spec.ts). Previews share 20 per subject per
// hour: a run spends about ten of them.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-AC-${stamp}`;

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
  entityCode: `AC${suffix}-${stamp}`,
  siteCode: `ACT${suffix}-${stamp}`,
  departmentCode: `ACD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
  calculationId: '',
});
// Journey 1 uses the first legal entity, journey 2 the second (a package is voided in journey 2).
const ENTITY_EN = site('E', 'Accounting Entity En');
const ENTITY_FR = site('F', 'Entité Comptable Fr');

interface Person {
  number: string;
  given: string;
  family: string;
  entity: Site;
  employee: string;
}
const person = (suffix: string, given: string, entity: Site): Person => ({
  number: `ACC-${stamp}-${suffix}`,
  given,
  family: `Mukendi${letters}${suffix.toLowerCase()}`,
  entity,
  employee: '',
});
const ANA = person('A', 'Anna', ENTITY_EN);
const CLO = person('C', 'Clovis', ENTITY_FR);

type Codes = Record<
  | 'salaryExpense'
  | 'employeeDeductionLiability'
  | 'employerContributionExpense'
  | 'employerContributionLiability'
  | 'netSalaryPayable',
  string
>;
const codes = (prefix: string): Codes => ({
  salaryExpense: `${prefix}-6411`,
  employeeDeductionLiability: `${prefix}-4311`,
  employerContributionExpense: `${prefix}-6451`,
  employerContributionLiability: `${prefix}-4312`,
  netSalaryPayable: `${prefix}-4211`,
});
const CODES_EN = codes('E2E-EN');
const CODES_USD = codes('E2E-USD');
const CODES_FR_1 = codes('E2E-FR1');
const CODES_FR_2 = codes('E2E-FR2');
const ALL_CODES = [CODES_EN, CODES_USD, CODES_FR_1, CODES_FR_2].flatMap((c) => Object.values(c));
const NOTICE_EN = 'Accounting export only — not posted and not proof of payment.';
const NOTICE_FR = 'Export comptable uniquement — ni comptabilisé ni preuve de paiement.';
const CSV_HEADER =
  'journal_id,package_id,legal_entity_id,currency,line_number,account_code,direction,amount_minor,cost_center_code,site_code';

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ac-${key}-${stamp}-0001` },
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
      ...(key ? { 'Idempotency-Key': `e2e-ac-${key}-${stamp}-0001` } : {}),
    },
    data,
  });
  for (let attempt = 0; attempt < 3 && response.status() === 429; attempt += 1) {
    const wait = Number(response.headers()['retry-after'] ?? '5');
    await page.waitForTimeout((Number.isFinite(wait) ? Math.min(wait, 65) : 5) * 1000 + 250);
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key ? { 'Idempotency-Key': `e2e-ac-${key}-${stamp}-0001` } : {}),
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

const get = (page: Page, bearer: () => string, path: string) =>
  page.request.get(`${CORE_API}${path}`, { headers: { Authorization: bearer() } });

/** Client-side navigation keeps the in-memory session (a reload would sign the user out). */
async function navigateInApp(page: Page, path: string) {
  await page.evaluate((target) => {
    window.history.pushState({}, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

/** The mapping version of a currency through the public preview and commit endpoints (setup only). */
async function mappingViaApi(
  page: Page,
  bearer: () => string,
  entity: Site,
  currency: 'CDF' | 'USD',
  effectiveFrom: string,
  accounts: Codes,
  key: string,
) {
  const command = { currency, effectiveFrom, accounts };
  const preview = await postJson<{
    status: string;
    nextVersion: number;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `/legal-entities/${entity.id}/accounting-mappings/preview`, command);
  expect(preview.status).toBe('READY');
  await postJson(
    page,
    bearer,
    `/legal-entities/${entity.id}/accounting-mappings`,
    {
      ...command,
      expectedVersion: preview.nextVersion,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      understandExportOnly: true,
    },
    `map-${key}`,
    201,
  );
}

/** Voids the package through the existing preview and commit endpoints (no payment batch exists). */
async function voidViaApi(page: Page, bearer: () => string, entity: Site, packageId: string) {
  const command = { reasonCode: 'ADMINISTRATIVE_ERROR', explanation: null };
  const base = `/legal-entities/${entity.id}/payroll-packages/${packageId}/void`;
  const preview = await postJson<{
    status: string;
    packageVersion: number;
    approvalDigest: string;
    previewDigest: string;
    expiresAt: string;
  }>(page, bearer, `${base}/preview`, command);
  expect(preview.status).toBe('READY');
  await postJson(
    page,
    bearer,
    base,
    {
      ...command,
      packageVersion: preview.packageVersion,
      approvalDigest: preview.approvalDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      noPaymentAcknowledged: true,
    },
    `void-${entity.entityCode}`,
    201,
  );
}

async function savedBytes(download: Download): Promise<Buffer> {
  return readFileSync(await download.path());
}

const sha256 = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');

/** Clicks a download button and returns the response, the saved bytes and the suggested name. */
async function fetchFile(
  page: Page,
  button: Locator,
  journalId: string,
  file: 'csv' | 'manifest',
): Promise<{ bytes: Buffer; name: string; headers: Record<string, string> }> {
  const [response, download] = await Promise.all([
    page.waitForResponse((r) => r.url().endsWith(`/accounting-journals/${journalId}/${file}`)),
    page.waitForEvent('download'),
    button.click(),
  ]);
  expect(response.status()).toBe(200);
  expect(response.headers()['cache-control']).toMatch(/no-store/u);
  expect(response.headers()['x-content-type-options']).toBe('nosniff');
  expect(response.headers()['content-disposition']).toMatch(/^attachment/u);
  return {
    bytes: await savedBytes(download),
    name: download.suggestedFilename(),
    headers: response.headers(),
  };
}

interface CsvLine {
  accountCode: string;
  direction: string;
  amount: number;
}

/** Checks the BOM, the header, the CRLF line ends and the balance; returns the parsed lines. */
function checkCsv(bytes: Buffer, journalId: string): CsvLine[] {
  expect([...bytes.subarray(0, 3)], 'UTF-8 BOM').toEqual([0xef, 0xbb, 0xbf]);
  const text = bytes.subarray(3).toString('utf8');
  expect(text.endsWith('\r\n'), 'CRLF after the last row').toBe(true);
  expect(text.replaceAll('\r\n', '')).not.toMatch(/[\r\n]/u);
  const rows = text.slice(0, -2).split('\r\n');
  expect(rows[0]).toBe(CSV_HEADER);
  const parsed = rows.slice(1).map((row) => {
    const cells = row.split(',');
    expect(cells[0]).toBe(journalId);
    expect(cells[3]).toBe('CDF');
    return { accountCode: cells[5] ?? '', direction: cells[6] ?? '', amount: Number(cells[7]) };
  });
  const total = (direction: string) =>
    parsed.filter((l) => l.direction === direction).reduce((sum, l) => sum + l.amount, 0);
  expect(total('DEBIT')).toBeGreaterThan(0);
  expect(total('DEBIT')).toBe(total('CREDIT'));
  return parsed;
}

interface Manifest {
  journalId: string;
  journalVersion: number;
  mappingVersion: number;
  currency: string;
  period: string;
  lineCount: number;
  totalDebitsMinor: number;
  totalCreditsMinor: number;
  csvFileName: string;
  csvBytes: number;
  csvSha256: string;
}

function checkManifest(bytes: Buffer, csv: Buffer, lines: CsvLine[], journalId: string): Manifest {
  const manifest = JSON.parse(bytes.toString('utf8')) as Manifest;
  expect(manifest.journalId).toBe(journalId);
  expect(manifest.currency).toBe('CDF');
  expect(manifest.period).toBe(PERIOD);
  expect(manifest.lineCount).toBe(lines.length);
  expect(manifest.csvBytes).toBe(csv.length);
  expect(manifest.csvSha256).toBe(sha256(csv));
  expect(manifest.totalDebitsMinor).toBe(manifest.totalCreditsMinor);
  return manifest;
}

/** No amount and no account code in any URL (identifiers are removed first), nor in the storage. */
async function expectNoLeak(page: Page, urls: string[], lines: CsvLine[]) {
  const amounts = lines.map((line) => String(line.amount)).filter((value) => value.length >= 5);
  for (const url of urls) {
    const bare = url.replaceAll(
      /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gu,
      '',
    );
    for (const code of ALL_CODES) expect(bare, 'account code in a URL').not.toContain(code);
    for (const amount of amounts) expect(bare, 'amount in a URL').not.toContain(amount);
  }
  const stored = await storage(page);
  for (const code of ALL_CODES) expect(stored).not.toContain(code);
}

/** Fills the mapping form and goes through the review and the keyboard confirmation. */
async function scheduleMappingInUi(
  page: Page,
  entity: Site,
  currency: 'CDF' | 'USD',
  effectiveFrom: string,
  accounts: Codes,
) {
  const form = page.getByTestId('accounting-mapping-form');
  await page.getByTestId('accounting-entity').selectOption(entity.id);
  await form.getByTestId('accounting-mapping-currency').selectOption(currency);
  await form.getByTestId('accounting-mapping-effective').fill(effectiveFrom);
  for (const [field, value] of Object.entries(accounts)) {
    await form.getByTestId(`accounting-account-${field}`).fill(value);
  }
  await form.getByTestId('accounting-mapping-preview-button').click();
  const preview = form.getByTestId('accounting-mapping-preview');
  await expect(preview.getByTestId('accounting-mapping-preview-status')).toBeVisible();
  await expect(preview.getByTestId('accounting-mapping-next-version')).toHaveText('1');
  await expect(preview.getByTestId('accounting-mapping-commit')).toBeDisabled();
  const understand = preview.getByTestId('accounting-mapping-understand');
  await keyboardTabReaches(page, understand);
  await page.keyboard.press('Space');
  await expect(understand).toBeChecked();
  await preview.getByTestId('accounting-mapping-commit').click();
  const list = page.locator(`[data-testid="accounting-mapping-list"][data-currency="${currency}"]`);
  await expect(list.getByTestId('accounting-mapping-row')).toHaveCount(1);
  await expect(list.getByTestId('accounting-mapping-row')).toHaveAttribute('data-status', 'ACTIVE');
  await expect(form.getByTestId('accounting-mapping-preview')).toHaveCount(0);
}

let releaseSubject: () => void = () => undefined;
let packageEn = '';
let packageFr = '';
let journalEn = '';

test.describe.serial('MVP-096: accounting mappings and the accounting journal export', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    for (const entity of [ENTITY_EN, ENTITY_FR]) {
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
        'Idempotency-Key': `e2e-acc-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-acc-commit-${stamp}-0001` },
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
    await establish(page, bearer, CLO, '500000');
    for (const entity of [ENTITY_EN, ENTITY_FR]) await recordPolicy(page, bearer, entity);
    for (const entity of [ENTITY_EN, ENTITY_FR]) await createRun(page, bearer, entity);
    for (const p of people) await recordProfile(page, bearer, p);
    const packId = createSyntheticVerifiedPack(PACK_CODE);
    for (const entity of [ENTITY_EN, ENTITY_FR]) await calculate(page, bearer, entity, packId);
    packageEn = await approveViaApi(page, bearer, ENTITY_EN);
    packageFr = await approveViaApi(page, bearer, ENTITY_FR);
    await page.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('English administrator schedules a CDF mapping, reviews and generates the balanced journal of a synthetic approved package and downloads the CSV and the manifest', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    const urls: string[] = [];
    admin.on('request', (request) => urls.push(request.url()));
    await signIn(admin, USERS.adminA, 'en');

    // 1. The mappings page, reached from the navigation: notice, empty histories, a keyboard flow.
    await primaryNav(admin).getByRole('link', { name: 'Accounting mappings', exact: true }).click();
    await expect(admin.getByRole('heading', { level: 1 })).toHaveText('Accounting mappings');
    await expect(admin.getByTestId('accounting-notice')).toHaveText(NOTICE_EN);
    await admin.getByTestId('accounting-entity').selectOption(ENTITY_EN.id);
    const cdfList = admin.locator('[data-testid="accounting-mapping-list"][data-currency="CDF"]');
    const usdList = admin.locator('[data-testid="accounting-mapping-list"][data-currency="USD"]');
    await expect(cdfList.getByTestId('accounting-mapping-empty')).toBeVisible();
    await expect(usdList.getByTestId('accounting-mapping-empty')).toBeVisible();
    await scheduleMappingInUi(admin, ENTITY_EN, 'CDF', FIRST_DAY, CODES_EN);
    await expect(cdfList.getByTestId('accounting-mapping-row')).toContainText(
      CODES_EN.netSalaryPayable,
    );
    await expect(usdList.getByTestId('accounting-mapping-empty')).toBeVisible();
    await expect(admin.getByTestId('accounting-mappings-announcer')).toHaveText(
      'The mapping version was scheduled.',
    );
    await accessible(admin);
    await noOverflow(admin);

    // 2. The approved package: the review shows the exact lines and equal totals.
    await navigateInApp(admin, `/admin/payroll-packages/${ENTITY_EN.id}/${packageEn}`);
    const panel = admin.getByTestId('accounting-journal-panel');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Accounting journal');
    await expect(panel.getByTestId('accounting-notice')).toHaveText(NOTICE_EN);
    await expect(panel.getByTestId('accounting-journals-empty')).toBeVisible();
    await panel.getByTestId('accounting-journal-preview-button').click();
    const preview = panel.getByTestId('accounting-journal-preview');
    await expect(preview.getByTestId('accounting-journal-preview-status')).toHaveText(
      'Ready to confirm',
    );
    await expect(preview.getByTestId('accounting-journal-mapping')).toContainText('Version 1');
    const lineCount = Number(
      await preview.getByTestId('accounting-journal-line-count').innerText(),
    );
    expect(lineCount).toBeGreaterThanOrEqual(3);
    await expect(preview.getByTestId('accounting-line')).toHaveCount(lineCount);
    const debits = await preview.getByTestId('accounting-journal-debits').innerText();
    await expect(preview.getByTestId('accounting-journal-credits')).toHaveText(debits);
    await expect(preview.getByTestId('accounting-journal-generate')).toBeDisabled();
    await accessible(admin);
    await noOverflow(admin);

    // 3. One confirmation by keyboard, then the generated journal.
    const understand = preview.getByTestId('accounting-journal-understand');
    await keyboardTabReaches(admin, understand);
    await admin.keyboard.press('Space');
    await expect(understand).toBeChecked();
    await preview.getByTestId('accounting-journal-generate').click();
    await expect(admin.getByTestId('accounting-journal-detail')).toBeVisible();
    journalEn =
      /\/admin\/accounting-journals\/[0-9a-f-]{36}\/([0-9a-f-]{36})$/u.exec(admin.url())?.[1] ?? '';
    expect(journalEn).not.toBe('');
    const detail = admin.getByTestId('accounting-journal-detail');
    await expect(detail.getByRole('heading', { level: 1 })).toHaveText('Accounting journal');
    await expect(detail.getByTestId('accounting-notice')).toHaveText(NOTICE_EN);
    await expect(detail.getByTestId('accounting-detail-line-count')).toHaveText(String(lineCount));
    await expect(detail.getByTestId('accounting-detail-debits')).toHaveText(debits);
    await expect(detail.getByTestId('accounting-detail-credits')).toHaveText(debits);
    await expect(detail.getByTestId('accounting-source-voided')).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);

    // 4. The files: BOM, header, CRLF, balance; the manifest digest is the digest of the bytes.
    const csv = await fetchFile(
      admin,
      detail.getByTestId('accounting-download-csv'),
      journalEn,
      'csv',
    );
    expect(csv.name).toBe(`accounting-journal-${ENTITY_EN.entityCode}-${PERIOD}-CDF-v1.csv`);
    expect(csv.headers['content-disposition']).toContain(csv.name);
    const lines = checkCsv(csv.bytes, journalEn);
    expect(lines).toHaveLength(lineCount);
    for (const line of lines) expect(Object.values(CODES_EN)).toContain(line.accountCode);
    const manifestFile = await fetchFile(
      admin,
      detail.getByTestId('accounting-download-manifest'),
      journalEn,
      'manifest',
    );
    expect(manifestFile.name).toBe(
      `accounting-journal-${ENTITY_EN.entityCode}-${PERIOD}-CDF-v1.manifest.json`,
    );
    const manifest = checkManifest(manifestFile.bytes, csv.bytes, lines, journalEn);
    expect(manifest.mappingVersion).toBe(1);
    expect(manifest.csvFileName).toBe(csv.name);
    await expect(detail.getByTestId('accounting-detail-sha')).toHaveText(manifest.csvSha256);
    await expect(detail.getByTestId('accounting-download-done')).toHaveText(
      'The file was downloaded.',
    );

    // 5. Back to the history; nothing sensitive reached a URL or the storage.
    await detail.getByTestId('accounting-history-link').click();
    const item = admin
      .getByTestId('accounting-journal-panel')
      .getByTestId('accounting-journal-item');
    await expect(item).toHaveCount(1);
    await expect(item).toHaveAttribute('data-source-voided', 'false');
    await expectNoLeak(admin, urls, lines);
    await accessible(admin);
    await context.close();
  });

  test('French journeys: authority, USD and CDF mappings stay separate, a stale journal review, the voided source history with byte-identical downloads, and the privacy and accessibility boundaries', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(journalEn, 'journey 1 ran').not.toBe('');

    // 1. Authority: an employee, another tenant's administrator and an anonymous caller.
    const employeeContext = await browser.newContext({ locale: 'fr-FR' });
    const employee = await employeeContext.newPage();
    const employeeBearer = await signIn(employee, USERS.employeeA, 'fr', undefined);
    await expect(
      primaryNav(employee).getByRole('link', { name: 'Paramètres comptables' }),
    ).toHaveCount(0);
    await navigateInApp(employee, '/admin/accounting-mappings');
    await expect(employee.getByTestId('not-authorized')).toHaveText(
      'Seuls les administrateurs de l’organisation peuvent gérer les paramètres et les journaux comptables.',
    );
    await expect(employee.getByTestId('accounting-mappings')).toHaveCount(0);
    await navigateInApp(employee, `/admin/accounting-journals/${ENTITY_EN.id}/${journalEn}`);
    await expect(employee.getByTestId('not-authorized')).toBeVisible();
    await expect(employee.getByTestId('accounting-journal-detail')).toHaveCount(0);
    const mappingBody = {
      currency: 'CDF',
      effectiveFrom: FIRST_DAY,
      accounts: codes('E2E-DENIED'),
    };
    const journalBase = `/legal-entities/${ENTITY_EN.id}/accounting-journals/${journalEn}`;
    const packageBase = `/legal-entities/${ENTITY_EN.id}/payroll-packages/${packageEn}`;
    for (const path of [
      `/legal-entities/${ENTITY_EN.id}/accounting-mappings`,
      `${packageBase}/accounting-journals`,
      journalBase,
      `${journalBase}/csv`,
      `${journalBase}/manifest`,
    ]) {
      expect([403, 404], path).toContain((await get(employee, employeeBearer, path)).status());
    }
    for (const [path, data] of [
      [`/legal-entities/${ENTITY_EN.id}/accounting-mappings/preview`, mappingBody],
      [`${packageBase}/accounting-journals/preview`, {}],
    ] as const) {
      const refused = await employee.request.post(`${CORE_API}${path}`, {
        headers: { Authorization: employeeBearer() },
        data,
      });
      expect([403, 404], path).toContain(refused.status());
    }
    const anonymous = await employee.request.get(`${CORE_API}${journalBase}/csv`);
    expect(anonymous.status()).toBe(401);
    await employeeContext.close();

    const otherContext = await browser.newContext({ locale: 'fr-FR' });
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    for (const path of [
      `/legal-entities/${ENTITY_EN.id}/accounting-mappings`,
      `${packageBase}/accounting-journals`,
      journalBase,
      `${journalBase}/csv`,
      `${journalBase}/manifest`,
    ]) {
      const foreign = await get(other, otherBearer, path);
      expect(foreign.status(), path).toBe(404);
      expect([
        'ACCOUNTING_JOURNAL_NOT_FOUND',
        'LEGAL_ENTITY_NOT_FOUND',
        'PAYROLL_PACKAGE_NOT_FOUND',
      ]).toContain(((await foreign.json()) as { code: string }).code);
    }
    await navigateInApp(other, `/admin/accounting-journals/${ENTITY_EN.id}/${journalEn}`);
    await expect(other.getByTestId('accounting-detail-failure')).toHaveAttribute(
      'data-kind',
      'notFound',
    );
    await expect(other.getByTestId('accounting-journal-summary')).toHaveCount(0);
    await accessible(other);
    await otherContext.close();

    // 2. The administrator, in French. A USD mapping is scheduled in the interface.
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    const urls: string[] = [];
    admin.on('request', (request) => urls.push(request.url()));
    const bearer = await signIn(admin, USERS.adminA, 'fr');
    await primaryNav(admin)
      .getByRole('link', { name: 'Paramètres comptables', exact: true })
      .click();
    await expect(admin.getByRole('heading', { level: 1 })).toHaveText('Paramètres comptables');
    await expect(admin.getByTestId('accounting-notice')).toHaveText(NOTICE_FR);
    await scheduleMappingInUi(admin, ENTITY_FR, 'USD', FIRST_DAY, CODES_USD);
    const cdfList = admin.locator('[data-testid="accounting-mapping-list"][data-currency="CDF"]');
    await expect(cdfList.getByTestId('accounting-mapping-empty')).toBeVisible();
    await expect(
      admin
        .locator('[data-testid="accounting-mapping-list"][data-currency="USD"]')
        .getByTestId('accounting-mapping-row'),
    ).toContainText('Active');
    await accessible(admin);
    await noOverflow(admin);

    // 3. The USD mapping does not serve the CDF package: blocked, with a link to the mappings.
    await navigateInApp(admin, `/admin/payroll-packages/${ENTITY_FR.id}/${packageFr}`);
    const panel = admin.getByTestId('accounting-journal-panel');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Journal comptable');
    await expect(panel.getByTestId('accounting-notice')).toHaveText(NOTICE_FR);
    await panel.getByTestId('accounting-journal-preview-button').click();
    const blocked = panel.getByTestId('accounting-journal-blockers');
    await expect(blocked).toContainText(
      'Aucun paramètre comptable ne s’applique à la devise et au mois de ce dossier.',
    );
    await expect(panel.getByTestId('accounting-journal-preview-status')).toHaveText('Bloqué');
    await expect(panel.getByTestId('accounting-journal-generate')).toBeDisabled();
    await accessible(admin);
    await noOverflow(admin);
    await blocked.getByTestId('accounting-mappings-link').click();
    await expect(admin.getByTestId('accounting-entity')).toHaveValue(ENTITY_FR.id);
    await expect(
      admin
        .locator('[data-testid="accounting-mapping-list"][data-currency="CDF"]')
        .getByTestId('accounting-mapping-empty'),
    ).toBeVisible();

    // 4. A CDF mapping, a ready review, then a newer mapping version makes the review stale.
    await mappingViaApi(
      admin,
      bearer,
      ENTITY_FR,
      'CDF',
      plusDays(FIRST_DAY, -31),
      CODES_FR_1,
      'fr1',
    );
    await navigateInApp(admin, `/admin/payroll-packages/${ENTITY_FR.id}/${packageFr}`);
    await panel.getByTestId('accounting-journal-preview-button').click();
    const preview = panel.getByTestId('accounting-journal-preview');
    await expect(preview.getByTestId('accounting-journal-preview-status')).toHaveText(
      'Prêt à confirmer',
    );
    await expect(preview.getByTestId('accounting-journal-mapping')).toContainText('Version 1');
    await mappingViaApi(admin, bearer, ENTITY_FR, 'CDF', FIRST_DAY, CODES_FR_2, 'fr2');
    await preview.getByTestId('accounting-journal-understand').check();
    await preview.getByTestId('accounting-journal-generate').click();
    const stale = panel.getByTestId('accounting-journal-failure');
    await expect(stale).toHaveAttribute('data-code', 'ACCOUNTING_JOURNAL_PREVIEW_STALE');
    await expect(stale).toContainText(
      'L’aperçu du journal a expiré ou le dossier de paie ou le paramétrage a changé. Relancez l’aperçu avant de générer.',
    );
    await expect(panel.getByTestId('accounting-journal-stale')).toBeVisible();
    await expect(panel.getByTestId('accounting-journal-preview')).toHaveCount(0);
    await expect(panel.getByTestId('accounting-journal-item')).toHaveCount(0);
    await accessible(admin);

    // 5. A new review uses the second version; the journal is generated and downloaded.
    await panel.getByTestId('accounting-journal-preview-button').click();
    await expect(preview.getByTestId('accounting-journal-mapping')).toContainText('Version 2');
    await preview.getByTestId('accounting-journal-understand').check();
    await preview.getByTestId('accounting-journal-generate').click();
    await expect(admin.getByTestId('accounting-journal-detail')).toBeVisible();
    const journalFr =
      /\/admin\/accounting-journals\/[0-9a-f-]{36}\/([0-9a-f-]{36})$/u.exec(admin.url())?.[1] ?? '';
    expect(journalFr).not.toBe('');
    const detail = admin.getByTestId('accounting-journal-detail');
    await expect(detail.getByRole('heading', { level: 1 })).toHaveText('Journal comptable');
    await expect(detail.getByTestId('accounting-notice')).toHaveText(NOTICE_FR);
    await expect(detail.getByTestId('accounting-source-voided')).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);
    const csvBefore = await fetchFile(
      admin,
      detail.getByTestId('accounting-download-csv'),
      journalFr,
      'csv',
    );
    const manifestBefore = await fetchFile(
      admin,
      detail.getByTestId('accounting-download-manifest'),
      journalFr,
      'manifest',
    );
    const lines = checkCsv(csvBefore.bytes, journalFr);
    const csvText = csvBefore.bytes.toString('utf8');
    // The second CDF version and only it: neither the first version nor the USD codes are used.
    for (const line of lines) expect(Object.values(CODES_FR_2)).toContain(line.accountCode);
    for (const code of [...Object.values(CODES_FR_1), ...Object.values(CODES_USD)]) {
      expect(csvText).not.toContain(code);
    }
    expect(
      checkManifest(manifestBefore.bytes, csvBefore.bytes, lines, journalFr).mappingVersion,
    ).toBe(2);

    // 6. The package is voided through the existing endpoints: the history says so, the files stay.
    await voidViaApi(admin, bearer, ENTITY_FR, packageFr);
    await detail.getByTestId('accounting-history-link').click();
    await expect(admin.getByTestId('package-voided-banner')).toBeVisible();
    const item = admin
      .getByTestId('accounting-journal-panel')
      .getByTestId('accounting-journal-item');
    await expect(item).toHaveCount(1);
    await expect(item).toHaveAttribute('data-source-voided', 'true');
    await expect(item.getByTestId('accounting-source-voided')).toHaveText('Source annulée');
    await expect(admin.getByTestId('accounting-journal-preview-button')).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);
    await item.getByTestId('accounting-journal-link').click();
    const voided = admin.getByTestId('accounting-journal-detail');
    await expect(voided.getByTestId('accounting-source-voided')).toHaveText('Source annulée');
    await expect(voided.getByTestId('accounting-voided-note')).toContainText(
      'Le journal et ses fichiers sont inchangés.',
    );
    await expect(voided.getByTestId('accounting-notice')).toHaveText(NOTICE_FR);
    const csvAfter = await fetchFile(
      admin,
      voided.getByTestId('accounting-download-csv'),
      journalFr,
      'csv',
    );
    const manifestAfter = await fetchFile(
      admin,
      voided.getByTestId('accounting-download-manifest'),
      journalFr,
      'manifest',
    );
    expect(csvAfter.bytes.equals(csvBefore.bytes), 'CSV identical after the void').toBe(true);
    expect(
      manifestAfter.bytes.equals(manifestBefore.bytes),
      'manifest identical after the void',
    ).toBe(true);
    expect(csvAfter.name).toBe(csvBefore.name);
    await accessible(admin);
    await noOverflow(admin);

    // 7. Privacy: no amount and no account code in a URL or in the storage.
    await expectNoLeak(admin, urls, lines);
    await context.close();
  });
});

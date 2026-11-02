import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
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
import type { Locator } from '@playwright/test';
import { createSyntheticVerifiedPack } from './synthetic-rule-pack';

// MVP-095A (Issue #183): exactly two journeys, with synthetic data only. Nothing is sent and no
// money moves: a payment batch is a register.
// (1) English: the administrator records a bank-account destination and a mobile-money destination
//     for two employees on their records (only masked values come back), prepares a payment batch
//     from a synthetic approved package (preview with masked lines, two confirmations by keyboard),
//     opens the batch, downloads the CSV register and the manifest (content, headers, safe names),
//     and sees that the package cannot be voided while the batch is prepared.
// (2) French: the linked employee reads only their own masked destination (no edit control); the
//     administrator cancels the batch, after which the void is no longer blocked; an employee and
//     another tenant's administrator are refused; accessibility and reflow are checked.
// Setup uses only public Core API endpoints with the administrator's session plus the test-only
// synthetic pack. Amounts, encryption, races and limits are covered by the Core tests, so no amount
// is computed here. No screenshot gate, no retry and no arbitrary sleep (the 429 wait of the shared
// setup helper is the one documented exception, as in payslips.spec.ts).

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-${stamp}`;
const ACCOUNT = `SYNTHACCT${stamp}77 6655`;
const ACCOUNT_NORMALIZED = ACCOUNT.replaceAll(' ', '');
const ACCOUNT_LAST4 = ACCOUNT_NORMALIZED.slice(-4);
const PHONE = '+243990001122';
const HOLDER = `Holder Synthetique ${stamp}`;

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
  entityCode: `PY${suffix}-${stamp}`,
  siteCode: `PYT${suffix}-${stamp}`,
  departmentCode: `PYD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
  calculationId: '',
});
const ENTITY = site('E', 'Payment Entity');

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
  number: `PAY-${stamp}-${suffix}`,
  given,
  family: `Mukendi${letters}${suffix.toLowerCase()}`,
  email: `e2e.paiement.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Paiement${suffix}!`,
  entity,
  employee: '',
  linked,
});
// Anna (bank account, has a login) and Béatrice (mobile money).
const ANA = person('A', 'Anna', ENTITY, true);
const BEA = person('B', 'Béatrice', ENTITY, false);

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
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ps-${key}-${stamp}-0001` },
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
      ...(key ? { 'Idempotency-Key': `e2e-ps-${key}-${stamp}-0001` } : {}),
    },
    data,
  });
  for (let attempt = 0; attempt < 3 && response.status() === 429; attempt += 1) {
    const wait = Number(response.headers()['retry-after'] ?? '5');
    await page.waitForTimeout((Number.isFinite(wait) ? Math.min(wait, 65) : 5) * 1000 + 250);
    response = await page.request.post(`${CORE_API}${path}`, {
      headers: {
        Authorization: bearer(),
        ...(key ? { 'Idempotency-Key': `e2e-ps-${key}-${stamp}-0001` } : {}),
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

async function savedText(download: import('@playwright/test').Download): Promise<string> {
  return readFileSync(await download.path(), 'utf8');
}

let releaseSubject: () => void = () => undefined;
let packId = '';
let packageId = '';
let batchId = '';

test.describe.serial('MVP-095A: payment destinations and payment batches', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(600_000);
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const created = await post(page, bearer, '/legal-entities', `le-${ENTITY.entityCode}`, {
      code: ENTITY.entityCode,
      name: ENTITY.name,
      countryCode: 'CD',
      effectiveFrom: plusDays(TODAY, -400),
    });
    expect(created.status()).toBe(201);
    ENTITY.id = ((await created.json()) as { id: string }).id;
    const createdSite = await post(page, bearer, '/sites', `st-${ENTITY.entityCode}`, {
      legalEntityId: ENTITY.id,
      code: ENTITY.siteCode,
      name: `Site ${ENTITY.name}`,
      timezone: 'Africa/Kinshasa',
      effectiveFrom: plusDays(TODAY, -400),
    });
    expect(createdSite.status()).toBe(201);
    expect(
      (
        await post(page, bearer, '/departments', `dp-${ENTITY.entityCode}`, {
          siteId: ((await createdSite.json()) as { id: string }).id,
          code: ENTITY.departmentCode,
          name: `Opérations ${ENTITY.name}`,
          effectiveFrom: plusDays(TODAY, -400),
        })
      ).status(),
    ).toBe(201);
    const people = [ANA, BEA];
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
        'Idempotency-Key': `e2e-pay-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pay-commit-${stamp}-0001` },
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
    await establish(page, bearer, BEA, '500000');
    await recordPolicy(page, bearer, ENTITY);
    await createRun(page, bearer, ENTITY);
    for (const p of people) await recordProfile(page, bearer, p);
    packId = createSyntheticVerifiedPack(PACK_CODE);
    await calculate(page, bearer, ENTITY, packId);
    packageId = await approveViaApi(page, bearer, ENTITY);
    await inviteAndLink(browser, page, bearer, ANA);
    await page.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('English administrator records destinations, prepares a payment batch from a synthetic approved package, downloads the register and sees the void blocked', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'en');

    // 1. A bank-account destination on Anna's record: only the masked value comes back.
    await navigateInApp(admin, `/admin/people/${ANA.employee}`);
    let section = admin.getByTestId('payment-section');
    await expect(section.getByRole('heading', { level: 2 })).toHaveText('Payment destination');
    await expect(section.getByTestId('payment-none')).toBeVisible();
    await section.getByTestId('payment-open-form').click();
    await section.getByTestId('payment-method').selectOption('BANK_ACCOUNT');
    await section.getByTestId('payment-provider').selectOption('RAWBANK');
    await section.getByTestId('payment-holder').fill(HOLDER);
    await section.getByTestId('payment-account').fill(ACCOUNT);
    await section.getByTestId('payment-branch').fill('KIN-01');
    await section.getByTestId('payment-effective').fill(HIRE);
    await section.getByTestId('payment-reason').selectOption('INITIAL_SETUP');
    await section.getByTestId('payment-preview-button').click();
    const preview = section.getByTestId('payment-preview');
    await expect(preview.getByTestId('payment-preview-status')).toHaveText('Ready to record');
    await expect(preview.getByTestId('payment-preview-line')).toContainText(`****${ACCOUNT_LAST4}`);
    await expect(preview).not.toContainText(ACCOUNT_NORMALIZED);
    await expect(preview.getByTestId('payment-commit')).toBeDisabled();
    const reviewed = preview.getByTestId('payment-reviewed');
    await keyboardTabReaches(admin, reviewed);
    await admin.keyboard.press('Space');
    await expect(reviewed).toBeChecked();
    await preview.getByTestId('payment-commit').click();
    await expect(section.getByTestId('payment-current')).toContainText(`****${ACCOUNT_LAST4}`);
    await expect(section.getByTestId('payment-history-row')).toHaveCount(1);
    await expect(admin.getByRole('main')).not.toContainText(ACCOUNT_NORMALIZED);
    await expect(admin.getByRole('main')).not.toContainText(HOLDER);
    expect(await storage(admin)).not.toContain(ACCOUNT_NORMALIZED);
    await accessible(admin);
    await noOverflow(admin);

    // 2. A mobile-money destination on Béatrice's record.
    await navigateInApp(admin, `/admin/people/${BEA.employee}`);
    section = admin.getByTestId('payment-section');
    await section.getByTestId('payment-open-form').click();
    await section.getByTestId('payment-method').selectOption('MOBILE_MONEY');
    await section.getByTestId('payment-provider').selectOption('MPESA');
    await section.getByTestId('payment-holder').fill(HOLDER);
    await section.getByTestId('payment-phone').fill(PHONE);
    await section.getByTestId('payment-effective').fill(HIRE);
    await section.getByTestId('payment-reason').selectOption('INITIAL_SETUP');
    await section.getByTestId('payment-preview-button').click();
    await expect(section.getByTestId('payment-preview-line')).toContainText(
      `******${PHONE.slice(-3)}`,
    );
    await section.getByTestId('payment-reviewed').check();
    await section.getByTestId('payment-commit').click();
    await expect(section.getByTestId('payment-current')).toContainText(`******${PHONE.slice(-3)}`);
    await expect(admin.getByRole('main')).not.toContainText(PHONE);

    // 3. The package: preview of the payment batch with masked lines and the counts by method.
    await navigateInApp(admin, `/admin/payroll-packages/${ENTITY.id}/${packageId}`);
    const panel = admin.getByTestId('payment-batch-panel');
    await expect(panel.getByRole('heading', { level: 2 })).toHaveText('Prepare payment batch');
    await expect(panel.getByTestId('payment-no-money')).toContainText('no money moves');
    await panel.getByTestId('payment-batch-preview-button').click();
    const batchPreview = panel.getByTestId('payment-batch-preview');
    await expect(batchPreview.getByTestId('payment-batch-preview-status')).toHaveText(
      'Ready to record',
    );
    await expect(batchPreview.getByTestId('payment-batch-line-count')).toHaveText('2');
    await expect(batchPreview.getByTestId('payment-count-bank')).toHaveText('1');
    await expect(batchPreview.getByTestId('payment-count-mobile')).toHaveText('1');
    await expect(batchPreview.getByTestId('payment-line')).toHaveCount(2);
    await expect(batchPreview).toContainText(`****${ACCOUNT_LAST4}`);
    await expect(batchPreview).not.toContainText(ACCOUNT_NORMALIZED);
    await expect(batchPreview).not.toContainText(PHONE);
    await expect(batchPreview.getByTestId('payment-batch-commit')).toBeDisabled();
    await accessible(admin);
    await noOverflow(admin);

    // 4. Two confirmations by keyboard, then the batch is prepared.
    for (const key of ['reviewed', 'nomoney']) {
      const box = batchPreview.getByTestId(`payment-batch-${key}`);
      await keyboardTabReaches(admin, box);
      await admin.keyboard.press('Space');
      await expect(box).toBeChecked();
    }
    await batchPreview.getByTestId('payment-batch-commit').click();
    const item = panel.getByTestId('payment-batch-item');
    await expect(item).toHaveCount(1);
    await expect(item).toHaveAttribute('data-status', 'PREPARED');

    // 5. The batch detail: immutable summary, masked lines, downloads.
    await item.getByTestId('payment-batch-link').click();
    batchId =
      /\/admin\/payment-batches\/[0-9a-f-]{36}\/([0-9a-f-]{36})$/u.exec(admin.url())?.[1] ?? '';
    expect(batchId).not.toBe('');
    const detail = admin.getByTestId('payment-batch-detail');
    await expect(detail.getByTestId('payment-batch-status')).toHaveText('Prepared');
    await expect(detail.getByTestId('payment-detail-line-count')).toHaveText('2');
    await expect(detail.getByTestId('payment-line')).toHaveCount(2);
    await expect(detail).not.toContainText(ACCOUNT_NORMALIZED);
    await accessible(admin);
    await noOverflow(admin);

    const [csvResponse, csvDownload] = await Promise.all([
      admin.waitForResponse((r) => r.url().includes(`/payment-batches/${batchId}/register.csv`)),
      admin.waitForEvent('download'),
      detail.getByTestId('payment-download-csv').click(),
    ]);
    expect(csvResponse.status()).toBe(200);
    expect(csvResponse.headers()['cache-control']).toMatch(/no-store/u);
    expect(csvResponse.headers()['x-content-type-options']).toBe('nosniff');
    expect(csvDownload.suggestedFilename()).toBe(
      `payment-register-${PERIOD}-${batchId.replaceAll('-', '').slice(0, 8)}.csv`,
    );
    const csv = await savedText(csvDownload);
    expect(csv.startsWith('packageId,packageVersion,batchId,')).toBe(true);
    expect(csv).toContain(`****${ACCOUNT_LAST4}`);
    expect(csv).toContain('\r\n');
    expect(csv).not.toContain(ACCOUNT_NORMALIZED);
    expect(csv).not.toContain(PHONE);
    expect(csv).not.toContain(HOLDER);
    const [manifestResponse, manifestDownload] = await Promise.all([
      admin.waitForResponse((r) => r.url().includes(`/payment-batches/${batchId}/manifest.json`)),
      admin.waitForEvent('download'),
      detail.getByTestId('payment-download-manifest').click(),
    ]);
    expect(manifestResponse.status()).toBe(200);
    const manifest = await savedText(manifestDownload);
    expect(manifest).not.toContain(ACCOUNT_NORMALIZED);
    expect(() => JSON.parse(manifest) as unknown).not.toThrow();

    // 6. While the batch is prepared the package cannot be voided.
    await detail.getByTestId('payment-package-link').click();
    const voidPanel = admin.getByTestId('void-panel');
    await voidPanel.getByTestId('void-reason').selectOption('ADMINISTRATIVE_ERROR');
    await voidPanel.getByTestId('void-preview-button').click();
    await expect(voidPanel.getByTestId('void-blockers')).toBeVisible();
    await expect(voidPanel.getByTestId('void-commit')).toBeDisabled();
    await accessible(admin);
    await context.close();
  });

  test('French employee reads only their masked destination, the administrator cancels the batch which releases the void, and foreign access is refused', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(batchId, 'journey 1 ran').not.toBe('');

    // 1. Anna: masked and read-only, no control, no other employee.
    const annaContext = await browser.newContext({ locale: 'fr-FR' });
    const anna = await annaContext.newPage();
    const annaBearer = await signIn(anna, [ANA.email, ANA.password], 'fr', undefined);
    await primaryNav(anna)
      .getByRole('link', { name: 'Ma destination de paiement', exact: true })
      .click();
    await expect(anna.getByRole('heading', { level: 1 })).toHaveText('Ma destination de paiement');
    const mine = anna.getByTestId('my-payment-destination');
    await expect(mine.getByTestId('my-payment-masked')).toHaveText(`****${ACCOUNT_LAST4}`);
    await expect(mine.getByTestId('my-payment-request')).toContainText('adressez-vous');
    await expect(mine.locator('input, select, textarea, button')).toHaveCount(0);
    await expect(mine).not.toContainText(ACCOUNT_NORMALIZED);
    await expect(mine).not.toContainText(HOLDER);
    await expect(mine).not.toContainText(BEA.number);
    await accessible(anna);
    await noOverflow(anna);
    expect(await storage(anna)).not.toContain(ACCOUNT_NORMALIZED);
    // The administrator endpoints are closed to an employee.
    for (const path of [
      `/employees/${BEA.employee}/payment-destination`,
      `/employees/${ANA.employee}/payment-destination/history`,
      `/legal-entities/${ENTITY.id}/payment-batches`,
      `/legal-entities/${ENTITY.id}/payment-batches/${batchId}/register.csv`,
    ]) {
      expect([403, 404], path).toContain((await get(anna, annaBearer, path)).status());
    }
    await annaContext.close();

    // 2. The administrator cancels the batch (in French); the void is no longer blocked.
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'fr');
    await navigateInApp(admin, `/admin/payment-batches/${ENTITY.id}/${batchId}`);
    const cancel = admin.getByTestId('payment-cancel-panel');
    await expect(cancel.getByRole('heading', { level: 2 })).toHaveText('Annuler ce lot');
    await cancel.getByTestId('payment-cancel-reason').selectOption('PREPARED_IN_ERROR');
    await cancel.getByTestId('payment-cancel-preview-button').click();
    const cancelPreview = cancel.getByTestId('payment-cancel-preview');
    await expect(cancelPreview.getByTestId('payment-cancel-commit')).toBeDisabled();
    await cancelPreview.getByTestId('payment-cancel-ack').check();
    await cancelPreview.getByTestId('payment-cancel-commit').click();
    await expect(admin.getByTestId('payment-batch-status')).toHaveText('Annulé');
    await expect(admin.getByTestId('payment-detail-cancelled')).toContainText('Préparé par erreur');
    await expect(admin.getByTestId('payment-cancel-panel')).toHaveCount(0);
    await accessible(admin);
    await noOverflow(admin);
    await admin.getByTestId('payment-package-link').click();
    const voidPanel = admin.getByTestId('void-panel');
    await voidPanel.getByTestId('void-reason').selectOption('ADMINISTRATIVE_ERROR');
    await voidPanel.getByTestId('void-preview-button').click();
    await expect(voidPanel.getByTestId('void-preview')).toBeVisible();
    await expect(voidPanel.getByTestId('void-blockers')).toHaveCount(0);
    await expect(voidPanel.getByTestId('void-ack')).toBeEnabled();
    await accessible(admin);
    await context.close();

    // 3. Another tenant's administrator reaches nothing of this one.
    const otherContext = await browser.newContext({ locale: 'fr-FR' });
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    for (const path of [
      `/employees/${ANA.employee}/payment-destination`,
      `/legal-entities/${ENTITY.id}/payment-batches/${batchId}`,
      `/legal-entities/${ENTITY.id}/payment-batches/${batchId}/lines`,
      `/legal-entities/${ENTITY.id}/payment-batches/${batchId}/register.csv`,
      `/legal-entities/${ENTITY.id}/payment-batches/${batchId}/manifest.json`,
    ]) {
      const foreign = await get(other, otherBearer, path);
      expect(foreign.status(), path).toBe(404);
      expect([
        'PAYMENT_DESTINATION_NOT_FOUND',
        'PAYMENT_BATCH_NOT_FOUND',
        'LEGAL_ENTITY_NOT_FOUND',
      ]).toContain(((await foreign.json()) as { code: string }).code);
    }
    await navigateInApp(other, `/admin/payment-batches/${ENTITY.id}/${batchId}`);
    const alert = other.getByTestId('payment-batch-detail-failure');
    await expect(alert).toHaveAttribute('data-kind', 'notFound');
    await expect(other.getByTestId('payment-summary')).toHaveCount(0);
    await accessible(other);
    await otherContext.close();
  });
});

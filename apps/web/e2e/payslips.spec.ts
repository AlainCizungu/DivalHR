import { expect, test, type Locator, type Page } from '@playwright/test';
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
import { createSyntheticVerifiedPack } from './synthetic-rule-pack';

// MVP-094C (Issue #181): exactly two journeys, with synthetic data only.
// (1) English: the administrator opens a frozen statutory calculation that uses the clearly
//     synthetic VERIFIED pack (database only; see synthetic-verified-rule-pack.sql), reviews the
//     approval (synthetic badge, same-preparer warning, totals, employee lines), gives the three
//     confirmations and approves; opens the package, the employee's HTML payslip (English and
//     French, same amounts) and downloads its PDF through the page (content type, size, %PDF,
//     filename pattern); then voids the package with a controlled reason and an explanation.
// (2) French: a linked employee reads only their own payslips (the voided one of journey 1, with
//     the reason label and never the administrator's explanation; a second linked employee reads
//     the approved one of another legal entity); another employee, a non-administrator and another
//     tenant's administrator cannot read or download them; accessibility and reflow are checked.
// Setup uses only public Core API endpoints with the administrator's existing SSO session (legal
// entities, sites, people import, compensation, payroll policy and runs, statutory profiles, pack
// activation, statutory calculations, one approval) plus the test-only synthetic pack and, for the
// two employees, an invitation, a password and an access link. Calculation vectors, authorization
// matrices, limits, races, rollbacks and PDF determinism are covered by the Core tests, not here,
// so no amount is computed in this file. No screenshot gate, no retry and no arbitrary sleep: every
// wait is a web-first assertion. The rate limits of one subject per minute (profile commit 5,
// activation 2, run creation 2, statutory calculation creation 2, approval and void 2) hold.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-${stamp}`;
const EXPLANATION = `Explication administrateur synthétique ${stamp} pour le motif contrôlé`;

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
  entityCode: `PS${suffix}-${stamp}`,
  siteCode: `PT${suffix}-${stamp}`,
  departmentCode: `PD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
  calculationId: '',
});
const EN_ENTITY = site('E', 'Payslip Entity');
const FR_ENTITY = site('F', 'Entité Bulletins');

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
  number: `PSL-${stamp}-${suffix}`,
  given,
  family: `Kabongo${letters}${suffix.toLowerCase()}`,
  email: `e2e.bulletin.${suffix.toLowerCase()}.${lower}@example.test`,
  password: `Dev-only-E2E-${stamp}-Bulletin${suffix}!`,
  entity,
  employee: '',
  linked,
});
// Anna (voided in journey 1) and Clovis (approved in the setup) have a login; the others do not.
const ANA = person('A', 'Anna', EN_ENTITY, true);
const BEA = person('B', 'Béatrice', EN_ENTITY, false);
const CLO = person('C', 'Clovis', FR_ENTITY, true);
const DAN = person('D', 'Daniel', FR_ENTITY, false);

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

async function choose(page: Page, testId: string, entity: Site) {
  await page.getByTestId(testId).selectOption({ label: `${entity.name} (${entity.entityCode})` });
}

const digits = (text: string | null) => (text ?? '').replace(/\D/gu, '');

/** Downloads the payslip PDF through the page and checks the response and the saved name. */
async function downloadAndCheck(page: Page, button: Locator, payslipId: string) {
  const [response, download] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.url().includes(`/payslips/${payslipId}.pdf`) &&
        candidate.request().method() === 'GET',
    ),
    page.waitForEvent('download'),
    button.click(),
  ]);
  expect(response.status()).toBe(200);
  expect(response.headers()['content-type']).toMatch(/^application\/pdf/u);
  expect(response.headers()['cache-control']).toMatch(/no-store/u);
  expect(response.headers()['x-content-type-options']).toBe('nosniff');
  // The bytes are read from the file the browser saved: a fetched response body is not always
  // retained by the test driver, the saved download is what the person receives.
  const saved = await download.path();
  const body = readFileSync(saved);
  expect(body.subarray(0, 5).toString('latin1')).toBe('%PDF-');
  expect(body.length).toBeGreaterThan(500);
  expect(body.length).toBeLessThanOrEqual(256 * 1024);
  expect(download.suggestedFilename()).toMatch(
    new RegExp(`^payslip-${PERIOD}-${payslipId.replaceAll('-', '').slice(0, 8)}\\.pdf$`, 'u'),
  );
}

let releaseSubject: () => void = () => undefined;
let packId = '';
let enPackageId = '';
let frPackageId = '';
let anaPayslipId = '';
let cloPayslipId = '';

test.describe.serial('MVP-094C: payroll approval and bilingual payslips', () => {
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
      const created2 = await post(page, bearer, '/sites', `st-${entity.entityCode}`, {
        legalEntityId: entity.id,
        code: entity.siteCode,
        name: `Site ${entity.name}`,
        timezone: 'Africa/Kinshasa',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(created2.status()).toBe(201);
      expect(
        (
          await post(page, bearer, '/departments', `dp-${entity.entityCode}`, {
            siteId: ((await created2.json()) as { id: string }).id,
            code: entity.departmentCode,
            name: `Opérations ${entity.name}`,
            effectiveFrom: plusDays(TODAY, -400),
          })
        ).status(),
      ).toBe(201);
    }
    const people = [ANA, BEA, CLO, DAN];
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
        'Idempotency-Key': `e2e-ps-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-ps-commit-${stamp}-0001` },
      data: { previewDigest: imported.previewDigest, validRows: 4, acknowledgeInvalidRows: false },
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
    await establish(page, bearer, CLO, '800000');
    await establish(page, bearer, DAN, '400000');
    for (const entity of [EN_ENTITY, FR_ENTITY]) {
      await recordPolicy(page, bearer, entity);
      await createRun(page, bearer, entity);
    }
    for (const p of people) await recordProfile(page, bearer, p);
    // The clearly synthetic VERIFIED pack exists in the database only (no API can verify a pack).
    packId = createSyntheticVerifiedPack(PACK_CODE);
    for (const entity of [EN_ENTITY, FR_ENTITY]) await calculate(page, bearer, entity, packId);
    // The French legal entity's payroll is approved here, through the public endpoints, for the
    // employee journey; the English one is approved through the page in journey 1.
    frPackageId = await approveViaApi(page, bearer, FR_ENTITY);
    const slips = await page.request.get(
      `${CORE_API}/legal-entities/${FR_ENTITY.id}/payroll-packages/${frPackageId}/payslips`,
      { headers: { Authorization: bearer() } },
    );
    expect(slips.status()).toBe(200);
    cloPayslipId =
      (
        (await slips.json()) as { items: { payslipId: string; employeeNumber: string }[] }
      ).items.find((item) => item.employeeNumber === CLO.number)?.payslipId ?? '';
    expect(cloPayslipId).not.toBe('');
    for (const p of people.filter((candidate) => candidate.linked)) {
      await inviteAndLink(browser, page, bearer, p);
    }
    await page.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('English administrator reviews and approves a synthetic-pack payroll, checks the employee payslip HTML and PDF, then voids the package', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'en');

    // 1. From the payroll run to the frozen statutory calculation and its approval link.
    await primaryNav(admin).getByRole('link', { name: 'Payroll runs', exact: true }).click();
    await choose(admin, 'payroll-entity', EN_ENTITY);
    await admin.getByTestId('payroll-run-row').first().getByRole('link').click();
    await admin.getByTestId('payroll-detail-statutory-link').click();
    await admin
      .getByTestId('statutory-version-row')
      .first()
      .getByTestId('statutory-version-open')
      .click();
    const frozen = admin.getByTestId('statutory-frozen');
    await expect(frozen.getByTestId('statutory-frozen-pack')).toContainText(PACK_CODE);
    const card = frozen.getByTestId('statutory-approval-card');
    await card.getByTestId('statutory-approval-link').click();
    await expect(admin).toHaveURL(
      new RegExp(
        `/admin/payroll-runs/${EN_ENTITY.id}/${EN_ENTITY.runId}/statutory/${EN_ENTITY.calculationId}/approval$`,
        'u',
      ),
    );
    const review = admin.getByTestId('approval-review');
    await expect(review.getByRole('heading', { level: 1 })).toHaveText('Review payroll approval');
    await expect(review.getByTestId('package-label')).toHaveText(
      'Approved payroll—Not proof of payment',
    );

    // 2. The review: synthetic badge, pack statement, same-preparer warning, totals, lines.
    await review.getByTestId('approval-preview-button').click();
    const preview = review.getByTestId('approval-preview');
    await expect(preview.getByTestId('approval-preview-status')).toHaveText('Ready to approve');
    await expect(preview.getByTestId('synthetic-badge')).toHaveText('Synthetic / test');
    await expect(preview.getByTestId('synthetic-notice')).toBeVisible();
    await expect(preview.getByTestId('approval-pack')).toHaveText(
      `Statutory calculation based on rule pack ${PACK_CODE} v1`,
    );
    await expect(preview.getByTestId('approval-count')).toHaveText('2');
    await expect(preview.getByTestId('approval-same-preparer')).toContainText(
      'you are also approving it',
    );
    await expect(preview.getByTestId('approval-totals')).toContainText('CDF');
    await expect(preview.getByTestId('approval-line')).toHaveCount(2);
    await expect(
      preview.getByRole('columnheader', { name: 'Employer contributions (separate)' }),
    ).toBeVisible();
    await expect(preview.getByTestId('approval-commit')).toBeDisabled();
    await expect(preview.getByTestId('approval-commit-why')).toHaveText(
      'Check the three confirmations to approve.',
    );
    await expectAccessible(admin);
    await noOverflow(admin);

    // 3. The three-part confirmation, by keyboard, then the amount-free receipt.
    for (const key of ['totals', 'exceptions', 'noPayment']) {
      const box = preview.getByTestId(`approval-confirm-${key}`);
      await keyboardTabReaches(admin, box);
      await admin.keyboard.press('Space');
      await expect(box).toBeChecked();
    }
    const commitButton = preview.getByTestId('approval-commit');
    await expect(commitButton).toBeEnabled();
    await keyboardTabReaches(admin, commitButton);
    await admin.keyboard.press('Enter');
    const receipt = review.getByTestId('approval-receipt');
    await expect(receipt.getByTestId('approval-receipt-title')).toHaveText('Payroll approved');
    await expect(receipt.getByTestId('approval-receipt-not-proof')).toHaveText(
      'Not proof of payment',
    );
    await expect(receipt.getByTestId('approval-receipt-count')).toHaveText('2');
    await expect(receipt.getByTestId('approval-receipt-same-preparer')).toBeVisible();
    await expect(review.getByTestId('approval-announcer')).toContainText('Payroll approved.');
    await expectAccessible(admin);
    await noOverflow(admin);

    // 4. The package: approved, synthetic, two payslips.
    await receipt.getByTestId('approval-receipt-open').click();
    enPackageId =
      /\/admin\/payroll-packages\/[0-9a-f-]{36}\/([0-9a-f-]{36})$/u.exec(admin.url())?.[1] ?? '';
    expect(enPackageId).not.toBe('');
    const summary = admin.getByTestId('package-summary');
    await expect(summary.getByTestId('package-status')).toHaveText('Approved payroll');
    await expect(summary.getByTestId('synthetic-badge')).toBeVisible();
    await expect(summary.getByTestId('package-pack-statement')).toHaveText(
      `Statutory calculation based on rule pack ${PACK_CODE} v1`,
    );
    await expect(summary.getByTestId('package-count')).toHaveText('2');
    await expect(admin.getByTestId('package-payslip-row')).toHaveCount(2);
    await expectAccessible(admin);
    await noOverflow(admin);

    // 5. Anna's HTML payslip, English then French (same amounts), and its PDF.
    const row = admin.getByTestId('package-payslip-row').filter({ hasText: ANA.number });
    await row.getByTestId('package-payslip-view').click();
    anaPayslipId = /\/payslips\/([0-9a-f-]{36})$/u.exec(admin.url())?.[1] ?? '';
    expect(anaPayslipId).not.toBe('');
    const doc = admin.getByTestId('payslip-document');
    await expect(doc).toHaveAttribute('data-locale', 'en');
    await expect(doc.getByTestId('payslip-doc-title')).toHaveText('Payslip');
    await expect(doc.getByTestId('payslip-doc-number')).toHaveText(ANA.number);
    await expect(doc.getByTestId('payslip-doc-pack')).toHaveText(
      `Statutory calculation based on rule pack ${PACK_CODE} v1`,
    );
    await expect(doc.getByTestId('payslip-doc-synthetic')).toBeVisible();
    await expect(doc.getByTestId('payslip-doc-not-proof')).toHaveText('Not proof of payment');
    await expect(doc.getByTestId('payslip-doc-employer-note')).toContainText('do not reduce');
    const amounts = async () => ({
      net: digits(await doc.getByTestId('payslip-doc-net').locator('td').textContent()),
      gross: digits(await doc.getByTestId('payslip-doc-gross').locator('td').textContent()),
      employer: digits(await doc.getByTestId('payslip-doc-employer-amount').textContent()),
    });
    const english = await amounts();
    expect(english.gross).toBe('1000000');
    await expectAccessible(admin);
    await noOverflow(admin);
    await admin.getByTestId('payslip-language-fr').check();
    await expect(doc).toHaveAttribute('data-locale', 'fr');
    await expect(doc.getByTestId('payslip-doc-title')).toHaveText('Bulletin de paie');
    await expect(doc.getByTestId('payslip-doc-not-proof')).toHaveText(
      'Ne constitue pas une preuve de paiement',
    );
    expect(await amounts()).toEqual(english);
    await admin.getByTestId('payslip-language-en').check();
    await expect(doc).toHaveAttribute('data-locale', 'en');
    await downloadAndCheck(admin, admin.getByTestId('payslip-download'), anaPayslipId);
    await expect(admin.getByTestId('payslip-announcer')).toContainText('PDF was downloaded');

    // 6. Void with a controlled reason: the explanation is required only for OTHER_CONTROLLED.
    await admin.getByRole('link', { name: 'Back to the payroll package' }).click();
    const voidPanel = admin.getByTestId('void-panel');
    await expect(voidPanel.getByTestId('void-explanation')).toHaveCount(0);
    await voidPanel.getByTestId('void-reason').selectOption('OTHER_CONTROLLED');
    await expect(voidPanel.getByTestId('void-preview-button')).toBeDisabled();
    await voidPanel.getByTestId('void-explanation').fill(EXPLANATION);
    await expect(voidPanel.getByTestId('void-preview-button')).toBeEnabled();
    await voidPanel.getByTestId('void-preview-button').click();
    const voidPreview = voidPanel.getByTestId('void-preview');
    await expect(voidPreview.getByTestId('void-preview-status')).toHaveText('Ready to approve');
    await expect(voidPreview.getByTestId('void-commit')).toBeDisabled();
    await expect(voidPreview.getByTestId('void-commit-why')).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);
    await voidPreview.getByTestId('void-ack').check();
    await keyboardTabReaches(admin, voidPreview.getByTestId('void-commit'));
    await admin.keyboard.press('Enter');
    const banner = admin.getByTestId('package-voided-banner');
    await expect(banner).toContainText('VOIDED');
    await expect(banner.getByTestId('package-void-reason')).toHaveAttribute(
      'data-code',
      'OTHER_CONTROLLED',
    );
    await expect(banner.getByTestId('package-void-explanation')).toContainText(EXPLANATION);
    await expect(admin.getByTestId('void-panel')).toHaveCount(0);
    await expect(
      admin.getByTestId('package-payslip-row').first().getByTestId('package-status'),
    ).toHaveText('Voided');
    await expectAccessible(admin);
    await noOverflow(admin);

    // 7. The package list shows it voided; nothing sensitive reached the URL or the storage.
    await primaryNav(admin).getByRole('link', { name: 'Approved payroll', exact: true }).click();
    await choose(admin, 'package-entity', EN_ENTITY);
    await expect(admin.getByTestId('package-row')).toHaveCount(1);
    await expect(admin.getByTestId('package-row')).toHaveAttribute('data-status', 'VOIDED');
    expect(admin.url()).not.toMatch(/1000000|500000|OTHER_CONTROLLED/u);
    expect(await storage(admin)).not.toMatch(
      new RegExp(`${stamp}|${ANA.family}|1000000|${EXPLANATION}`, 'iu'),
    );
    await context.close();
  });

  test('French employees read only their own payslips; another employee, a non-administrator and another tenant cannot read or download them', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(anaPayslipId, 'journey 1 ran').not.toBe('');

    // 1. Anna: her payslip was voided; the list and the view show the closed reason only.
    const annaContext = await browser.newContext({ locale: 'fr-FR' });
    const anna = await annaContext.newPage();
    const annaBearer = await signIn(anna, [ANA.email, ANA.password], 'fr', undefined);
    await primaryNav(anna)
      .getByRole('link', { name: 'Mes bulletins de paie', exact: true })
      .click();
    await expect(anna.getByRole('heading', { level: 1 })).toHaveText('Mes bulletins de paie');
    await expect(anna.getByTestId('my-payslips-not-proof')).toHaveText(
      'Ne constitue pas une preuve de paiement',
    );
    const annaRows = anna.getByTestId('my-payslip-row');
    await expect(annaRows).toHaveCount(1);
    await expect(annaRows.first()).toHaveAttribute('data-status', 'VOIDED');
    await expect(annaRows.first().getByTestId('my-payslip-voided')).toContainText('ANNULÉ');
    await expect(annaRows.first().getByTestId('my-payslip-voided')).toContainText(
      'Autre motif contrôlé',
    );
    // Only her own payslip: no other employee, no employer-wide total, no administrator text.
    const main = anna.getByRole('main');
    await expect(main).not.toContainText(BEA.number);
    await expect(main).not.toContainText(CLO.number);
    await expect(main).not.toContainText(EXPLANATION);
    await expect(main).not.toContainText(/synthétique ou test|Cotisations patronales/iu);
    await expectAccessible(anna);
    await noOverflow(anna);
    await annaRows.first().getByTestId('my-payslip-open').click();
    await expect(anna).toHaveURL(new RegExp(`/me/payslips/${anaPayslipId}$`, 'u'));
    const annaDoc = anna.getByTestId('payslip-document');
    await expect(annaDoc).toHaveAttribute('data-locale', 'fr');
    await expect(annaDoc.getByTestId('payslip-doc-title')).toHaveText('Bulletin de paie');
    await expect(annaDoc.getByTestId('payslip-voided-banner')).toContainText('ANNULÉ');
    await expect(annaDoc.getByTestId('payslip-void-reason')).toHaveAttribute(
      'data-code',
      'OTHER_CONTROLLED',
    );
    await expect(annaDoc.getByTestId('payslip-doc-number')).toHaveText(ANA.number);
    await expect(anna.getByRole('main')).not.toContainText(EXPLANATION);
    await expect(annaDoc.getByTestId('payslip-doc-not-proof')).toHaveText(
      'Ne constitue pas une preuve de paiement',
    );
    await expectAccessible(anna);
    await noOverflow(anna);
    await downloadAndCheck(anna, anna.getByTestId('payslip-download'), anaPayslipId);
    expect(anna.url()).not.toMatch(/1000000|OTHER_CONTROLLED/u);
    expect(await storage(anna)).not.toMatch(
      new RegExp(`${stamp}|${ANA.family}|1000000|${EXPLANATION}`, 'iu'),
    );

    // 2. Clovis: another legal entity, an approved payslip, in French.
    const cloContext = await browser.newContext({ locale: 'fr-FR' });
    const clo = await cloContext.newPage();
    const cloBearer = await signIn(clo, [CLO.email, CLO.password], 'fr', undefined);
    await primaryNav(clo).getByRole('link', { name: 'Mes bulletins de paie', exact: true }).click();
    const cloRows = clo.getByTestId('my-payslip-row');
    await expect(cloRows).toHaveCount(1);
    await expect(cloRows.first()).toHaveAttribute('data-status', 'APPROVED');
    await expect(cloRows.first().getByTestId('package-status')).toHaveText('Paie approuvée');
    await expect(clo.getByRole('main')).not.toContainText(ANA.number);
    await expect(clo.getByRole('main')).not.toContainText(DAN.number);
    await expectAccessible(clo);
    await noOverflow(clo);
    await cloRows.first().getByTestId('my-payslip-open').click();
    const cloDoc = clo.getByTestId('payslip-document');
    await expect(cloDoc.getByTestId('payslip-doc-number')).toHaveText(CLO.number);
    await expect(cloDoc.getByTestId('payslip-doc-pack')).toHaveText(
      `Calcul statutaire fondé sur le jeu de règles ${PACK_CODE} v1`,
    );
    await expect(cloDoc.getByTestId('payslip-doc-synthetic')).toBeVisible();
    await expect(clo.getByTestId('payslip-voided-banner')).toHaveCount(0);
    await expect(cloDoc.getByTestId('payslip-doc-employer-note')).toContainText(
      'ne réduisent pas le net',
    );
    const frenchGross = digits(
      await cloDoc.getByTestId('payslip-doc-gross').locator('td').textContent(),
    );
    expect(frenchGross).toBe('800000');
    await clo.getByTestId('payslip-language-en').check();
    await expect(cloDoc).toHaveAttribute('data-locale', 'en');
    expect(digits(await cloDoc.getByTestId('payslip-doc-gross').locator('td').textContent())).toBe(
      frenchGross,
    );
    await expectAccessible(clo);
    await noOverflow(clo);
    await downloadAndCheck(clo, clo.getByTestId('payslip-download'), cloPayslipId);

    // 3. Isolation through the API: the same safe 404 for another employee's payslip, in the page
    // and for the PDF; the administrator endpoints are closed to an employee.
    const get = (page: Page, bearer: () => string, path: string) =>
      page.request.get(`${CORE_API}${path}`, { headers: { Authorization: bearer() } });
    for (const [page, bearer, foreignId] of [
      [anna, annaBearer, cloPayslipId],
      [clo, cloBearer, anaPayslipId],
    ] as const) {
      for (const suffix of ['', '.pdf']) {
        const foreign = await get(page, bearer, `/me/payslips/${foreignId}${suffix}`);
        expect(foreign.status()).toBe(404);
        expect(((await foreign.json()) as { code: string }).code).toBe('PAYSLIP_NOT_FOUND');
      }
    }
    const adminList = await get(
      anna,
      annaBearer,
      `/legal-entities/${EN_ENTITY.id}/payroll-packages/${enPackageId}`,
    );
    expect([403, 404]).toContain(adminList.status());
    const adminPdf = await get(
      anna,
      annaBearer,
      `/legal-entities/${EN_ENTITY.id}/payroll-packages/${enPackageId}/payslips/${anaPayslipId}.pdf`,
    );
    expect([403, 404]).toContain(adminPdf.status());
    // In the page: another employee's payslip id is the same safe not-found state.
    await navigateInApp(clo, `/me/payslips/${anaPayslipId}`);
    const error = clo.getByTestId('payslip-error');
    await expect(error).toHaveAttribute('data-kind', 'notFound');
    await expect(clo.getByTestId('payslip-document')).toHaveCount(0);
    await expectAccessible(clo);
    await annaContext.close();
    await cloContext.close();

    // 4. Another tenant's administrator cannot read or download either package's payslips.
    const otherContext = await browser.newContext({ locale: 'fr-FR' });
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    for (const path of [
      `/legal-entities/${EN_ENTITY.id}/payroll-packages/${enPackageId}`,
      `/legal-entities/${EN_ENTITY.id}/payroll-packages/${enPackageId}/payslips`,
      `/legal-entities/${EN_ENTITY.id}/payroll-packages/${enPackageId}/payslips/${anaPayslipId}`,
      `/legal-entities/${EN_ENTITY.id}/payroll-packages/${enPackageId}/payslips/${anaPayslipId}.pdf`,
      `/legal-entities/${FR_ENTITY.id}/payroll-packages/${frPackageId}/payslips/${cloPayslipId}.pdf`,
    ]) {
      const foreign = await get(other, otherBearer, path);
      expect(foreign.status(), path).toBe(404);
      expect([
        'LEGAL_ENTITY_NOT_FOUND',
        'PAYROLL_PACKAGE_NOT_FOUND',
        'PAYSLIP_NOT_FOUND',
      ]).toContain(((await foreign.json()) as { code: string }).code);
    }
    const foreignMe = await get(other, otherBearer, `/me/payslips/${anaPayslipId}`);
    expect([403, 404]).toContain(foreignMe.status());
    // In the page: the same safe not-found state for the foreign package.
    await navigateInApp(other, `/admin/payroll-packages/${EN_ENTITY.id}/${enPackageId}`);
    const alert = other.getByTestId('package-detail-error');
    await expect(alert).toHaveAttribute('data-kind', 'notFound');
    await expect(alert).toContainText(/n’existe pas/u);
    await expect(other.getByTestId('package-summary')).toHaveCount(0);
    await expectAccessible(other);
    await otherContext.close();
  });
});

/** Client-side navigation keeps the in-memory session (a reload would sign the user out). */
async function navigateInApp(page: Page, path: string) {
  await page.evaluate((target) => {
    window.history.pushState({}, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

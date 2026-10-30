import { expect, test, type Locator, type Page } from '@playwright/test';
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

// MVP-094B (Issue #179): exactly two journeys, with synthetic data only.
// (1) English: the administrator opens the statutory preview of a frozen payroll run and sees the
//     real, repository-shipped DRAFT rule pack with "Professional verification required" (activation
//     impossible). A synthetic VERIFIED test pack is then created in the database (no API can verify
//     a pack; see synthetic-verified-rule-pack.sql), activated through the page after the
//     acknowledgement, the employees' statutory profiles are completed (a blocker link, preview and
//     confirmation), and a statutory calculation is previewed, created and its calculation trace
//     opened.
// (2) French: the administrator activates the synthetic pack for a second legal entity, previews a
//     calculation with a missing-profile blocker, follows its link, completes the profile, previews
//     again, creates the calculation, and another tenant's administrator cannot read it (safe
//     not-found state, in the page and in the API).
// Setup uses only public Core API endpoints (organization, people import, compensation, payroll
// policy and runs, one statutory profile) with the administrator's existing SSO session, plus the
// test-only synthetic pack. Calculation vectors, authorization, limits, races and rollbacks are
// covered by the Core tests, not here, so no amount is computed in this file. axe, a keyboard-only
// step, reflow at 320 px, 390 px and 640 px (200 % zoom of a 1280 px window), browser storage and
// URLs are checked. No screenshot gate, no retry and no arbitrary sleep: every wait is a web-first
// assertion. The rate limits of one subject per minute (profile commit 5, activation 2, calculation
// preview 5, calculation creation 2, payroll run creation 2) hold for both journeys together.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';
const PACK_CODE = `SYNTHETIC-TEST-${stamp}`;

interface Site {
  code: string;
  entityCode: string;
  siteCode: string;
  departmentCode: string;
  name: string;
  id: string;
  runId: string;
}
const site = (suffix: string, name: string): Site => ({
  code: `ST${suffix}-${stamp}`,
  entityCode: `ST${suffix}-${stamp}`,
  siteCode: `SS${suffix}-${stamp}`,
  departmentCode: `SD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
  runId: '',
});
const EN_ENTITY = site('E', 'Statutory Entity');
const FR_ENTITY = site('F', 'Entité Statutaire');

interface Person {
  number: string;
  given: string;
  family: string;
  hire: string;
  entity: Site;
  employee: string;
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

/** The first day a compensation can start: the Core accepts at most 60 days in the past. */
const earliestCompensation = plusDays(TODAY, -59);
const later = (a: string, b: string) => (a > b ? a : b);
/** Employees are hired before the month; a statutory profile starts on the hire date. */
const HIRE = plusDays(FIRST_DAY, -5);

const person = (suffix: string, given: string, entity: Site): Person => ({
  number: `STA-${stamp}-${suffix}`,
  given,
  family: `Mulumba${letters}${suffix.toLowerCase()}`,
  hire: HIRE,
  entity,
  employee: '',
});
const ANA = person('A', 'Anna', EN_ENTITY);
const BEA = person('B', 'Béatrice', EN_ENTITY);
// Clovis has a statutory profile from the setup; Daniel has none until the French journey.
const CLO = person('C', 'Clovis', FR_ENTITY);
const DAN = person('D', 'Daniel', FR_ENTITY);

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
  for (let step = 0; step < 120; step++) {
    if (await target.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await target.evaluate((el) => el === document.activeElement)).toBe(true);
}

/** Client-side navigation keeps the in-memory session (a reload would sign the user out). */
async function navigateInApp(page: Page, path: string) {
  await page.evaluate((target) => {
    window.history.pushState({}, '', target);
    window.dispatchEvent(new PopStateEvent('popstate'));
  }, path);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-st-${key}-${stamp}-0001` },
    data,
  });
}

/** Establishes a monthly CDF compensation through the public preview and commit endpoints. */
async function establish(page: Page, bearer: () => string, p: Person, amount: string) {
  const effectiveFrom = later(p.hire, earliestCompensation);
  const command = { action: 'ESTABLISHED', effectiveFrom, currency: 'CDF', amount };
  const preview = await page.request.post(
    `${CORE_API}/employees/${p.employee}/compensation/preview`,
    { headers: { Authorization: bearer() }, data: command },
  );
  expect(preview.status()).toBe(200);
  const body = (await preview.json()) as {
    version: number;
    employmentDigest: string;
    previewDigest: string;
    expiresAt: string;
    blockers: string[];
  };
  expect(body.blockers).toEqual([]);
  const commit = await post(
    page,
    bearer,
    `/employees/${p.employee}/compensation/commit`,
    `cp-${p.number}`,
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
  );
  expect(commit.status()).toBe(201);
}

/** Records a FIXED_30_DAYS policy through the public preview and commit endpoints. */
async function recordPolicy(page: Page, bearer: () => string, entity: Site) {
  const command = {
    effectiveFrom: FIRST_DAY,
    payDay: { kind: 'LAST_DAY', day: null },
    prorationMethod: 'FIXED_30_DAYS',
  };
  const preview = await page.request.post(
    `${CORE_API}/legal-entities/${entity.id}/payroll-policy/preview`,
    { headers: { Authorization: bearer() }, data: command },
  );
  expect(preview.status()).toBe(200);
  const body = (await preview.json()) as {
    version: number;
    previewDigest: string;
    expiresAt: string;
  };
  const commit = await post(
    page,
    bearer,
    `/legal-entities/${entity.id}/payroll-policy`,
    `pol-${entity.code}`,
    {
      ...command,
      expectedVersion: body.version,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      confirmed: true,
    },
  );
  expect(commit.status()).toBe(201);
}

/** Creates the CDF payroll run of the closed month: the frozen source of the statutory preview. */
async function createRun(page: Page, bearer: () => string, entity: Site) {
  const command = { period: PERIOD, currency: 'CDF' };
  const preview = await page.request.post(
    `${CORE_API}/legal-entities/${entity.id}/payroll-runs/preview`,
    { headers: { Authorization: bearer() }, data: command },
  );
  expect(preview.status()).toBe(200);
  const body = (await preview.json()) as {
    expectedVersion: number;
    inputDigest: string;
    previewDigest: string;
    expiresAt: string;
    blockedEmployeeCount: number;
  };
  expect(body.blockedEmployeeCount).toBe(0);
  const commit = await post(
    page,
    bearer,
    `/legal-entities/${entity.id}/payroll-runs`,
    `run-${entity.code}`,
    {
      ...command,
      expectedVersion: body.expectedVersion,
      inputDigest: body.inputDigest,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      confirmed: true,
    },
  );
  expect(commit.status()).toBe(201);
  entity.runId = ((await commit.json()) as { runId: string }).runId;
}

/** Records a standard resident profile through the public preview and commit endpoints. */
async function recordProfile(page: Page, bearer: () => string, p: Person) {
  const command = {
    effectiveFrom: p.hire,
    classification: 'STANDARD_RESIDENT',
    cnssSubject: true,
    irppSubject: true,
    evidenceCode: 'EMPLOYMENT_CONTRACT',
  };
  const preview = await page.request.post(
    `${CORE_API}/employees/${p.employee}/statutory-profile/preview`,
    { headers: { Authorization: bearer() }, data: command },
  );
  expect(preview.status()).toBe(200);
  const body = (await preview.json()) as {
    version: number;
    previewDigest: string;
    expiresAt: string;
    blockers: string[];
  };
  expect(body.blockers).toEqual([]);
  const commit = await post(
    page,
    bearer,
    `/employees/${p.employee}/statutory-profile`,
    `sp-${p.number}`,
    {
      ...command,
      expectedVersion: body.version,
      previewDigest: body.previewDigest,
      expiresAt: body.expiresAt,
      confirmed: true,
    },
  );
  expect(commit.status()).toBe(201);
}

async function choose(page: Page, entity: Site) {
  await page
    .getByTestId('payroll-entity')
    .selectOption({ label: `${entity.name} (${entity.code})` });
}

/** The payroll runs page, the legal entity's run, its detail and the statutory link (no reload). */
async function openStatutory(page: Page, entity: Site, runs: string) {
  await primaryNav(page).getByRole('link', { name: runs, exact: true }).click();
  await choose(page, entity);
  await page.getByTestId('payroll-run-row').first().getByRole('link').click();
  await expect(page).toHaveURL(
    new RegExp(`/admin/payroll-runs/${entity.id}/${entity.runId}$`, 'u'),
  );
  await page.getByTestId('payroll-detail-statutory-link').click();
  await expect(page).toHaveURL(
    new RegExp(`/admin/payroll-runs/${entity.id}/${entity.runId}/statutory$`, 'u'),
  );
  await expect(page.getByTestId('statutory-label').first()).toBeVisible();
}

let releaseSubject: () => void = () => undefined;
let packId = '';

test.describe.serial('MVP-094B: source-versioned DRC statutory preview', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300_000);
    // The other specs writing as tenant A's administrator never run at the same time.
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const page = await browser.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    for (const entity of [EN_ENTITY, FR_ENTITY]) {
      const created = await post(page, bearer, '/legal-entities', `le-${entity.code}`, {
        code: entity.entityCode,
        name: entity.name,
        countryCode: 'CD',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(created.status()).toBe(201);
      entity.id = ((await created.json()) as { id: string }).id;
      const created2 = await post(page, bearer, '/sites', `st-${entity.code}`, {
        legalEntityId: entity.id,
        code: entity.siteCode,
        name: `Site ${entity.name}`,
        timezone: 'Africa/Kinshasa',
        effectiveFrom: plusDays(TODAY, -400),
      });
      expect(created2.status()).toBe(201);
      expect(
        (
          await post(page, bearer, '/departments', `dp-${entity.code}`, {
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
          `${p.number};${p.given};${p.family};${p.hire};${p.entity.entityCode};${p.entity.siteCode};${p.entity.departmentCode}`,
      ),
    ].join('\n');
    const upload = await page.request.post(`${CORE_API}/employee-imports`, {
      headers: {
        Authorization: bearer(),
        'Content-Type': 'text/csv',
        'Idempotency-Key': `e2e-st-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-st-commit-${stamp}-0001` },
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
    // Monthly CDF compensation (statutory previews are CDF only), the policy and the frozen run.
    await establish(page, bearer, ANA, '1000000');
    await establish(page, bearer, BEA, '500000');
    await establish(page, bearer, CLO, '800000');
    await establish(page, bearer, DAN, '400000');
    for (const entity of [EN_ENTITY, FR_ENTITY]) {
      await recordPolicy(page, bearer, entity);
      await createRun(page, bearer, entity);
    }
    // The French journey starts with one complete profile (Clovis) and one missing (Daniel).
    await recordProfile(page, bearer, CLO);
    await page.close();
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('English administrator sees the DRAFT pack blocked, activates a synthetic verified pack, completes profiles and creates a calculation with its trace', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'en');
    await openStatutory(admin, EN_ENTITY, 'Payroll runs');
    await expect(admin.getByRole('heading', { level: 1 })).toContainText('Statutory preview');
    await expect(admin.getByTestId('statutory-label').first()).toHaveText(
      'Statutory preview—not a payslip or payment',
    );

    // 1. The repository ships only a DRAFT pack: verification is required, activation impossible.
    const rulePack = admin.getByTestId('statutory-rule-pack');
    await expect(rulePack.getByTestId('statutory-verification-required')).toContainText(
      'Professional verification required',
    );
    const real = rulePack.locator('[data-testid="statutory-pack"][data-code="CD-SALARY-PREVIEW"]');
    await expect(real).toHaveAttribute('data-status', 'DRAFT');
    await expect(real.getByTestId('statutory-pack-status')).toHaveText('Draft pack');
    await expect(real.getByTestId('statutory-source-unhashed').first()).toHaveText('Not hashed');
    const link = real.getByTestId('statutory-source-link').first();
    await expect(link).toHaveAttribute('target', '_blank');
    await expect(link).toHaveAttribute('rel', 'noopener noreferrer');
    await expect(real.getByTestId('statutory-activate')).toBeDisabled();
    await expect(real.getByTestId('statutory-activation-why')).toContainText(
      'not been professionally verified',
    );
    await expectAccessible(admin);
    await noOverflow(admin);

    // 2. A synthetic VERIFIED test pack (database only, never the API or the seed) appears.
    packId = createSyntheticVerifiedPack(PACK_CODE);
    expect(packId).not.toBe('');
    await admin.getByRole('link', { name: 'Back to the payroll run' }).click();
    await admin.getByTestId('payroll-detail-statutory-link').click();
    const synthetic = admin
      .getByTestId('statutory-rule-pack')
      .locator(`[data-testid="statutory-pack"][data-code="${PACK_CODE}"]`);
    await expect(synthetic).toHaveAttribute('data-status', 'VERIFIED');
    await expect(synthetic.getByTestId('statutory-pack-reviewer')).toContainText(
      'Synthetic Test Reviewer',
    );
    await expect(synthetic.getByTestId('statutory-source-hash').first()).toBeVisible();
    // Verification is still required until the pack is activated; activation needs the acknowledgement.
    await expect(admin.getByTestId('statutory-verification-required')).toBeVisible();
    await expect(synthetic.getByTestId('statutory-activate')).toBeDisabled();
    await expect(synthetic.getByTestId('statutory-activation-month')).toHaveValue(PERIOD);
    await synthetic.getByLabel(/I understand that my organization remains responsible/u).check();
    await expect(synthetic.getByTestId('statutory-activate')).toBeEnabled();
    await expectAccessible(admin);
    await keyboardTabReaches(admin, synthetic.getByTestId('statutory-activate'));
    await admin.keyboard.press('Enter');
    await expect(admin.getByTestId('statutory-announcer')).toContainText('Rule pack activated.');
    await expect(admin.getByTestId('statutory-in-effect')).toContainText(PACK_CODE);
    await expect(admin.getByTestId('statutory-verification-required')).toHaveCount(0);

    // 3. Both employees lack a profile: blocked, with a link to each profile page.
    const calculation = admin.getByTestId('statutory-calculation');
    await calculation.getByTestId('statutory-preview-button').click();
    const blocked = calculation.getByTestId('statutory-preview');
    await expect(blocked.getByTestId('statutory-preview-status')).toHaveText('Blocked');
    await expect(blocked.getByTestId('statutory-preview-blocked')).toHaveText('2');
    await expect(blocked.getByTestId('statutory-create-confirm')).toBeDisabled();
    await expect(blocked.getByTestId('statutory-create-why')).toBeVisible();
    await expect(blocked.getByTestId('statutory-employee-blocker')).toHaveCount(2);
    await expectAccessible(admin);
    await noOverflow(admin);

    // 4. Complete Anna's profile through the blocker link, then Béatrice's.
    await blocked
      .getByTestId('statutory-employee-blockers')
      .getByRole('link', {
        name: new RegExp(`Open the statutory profile of employee ${ANA.number}`, 'u'),
      })
      .click();
    await expect(admin).toHaveURL(
      new RegExp(`/admin/people/${ANA.employee}/statutory-profile$`, 'u'),
    );
    await expect(admin.getByRole('heading', { level: 1, name: 'Statutory profile' })).toBeVisible();
    await expect(admin.getByTestId('statutory-label')).toHaveText(
      'Statutory preview—not a payslip or payment',
    );
    for (const person of [ANA, BEA]) {
      if (person === BEA)
        await navigateInApp(admin, `/admin/people/${BEA.employee}/statutory-profile`);
      const profile = admin.getByTestId('statutory-profile-form');
      await expect(admin.getByTestId('statutory-profile-none')).toBeVisible();
      await profile.getByLabel('Effective date', { exact: true }).fill(HIRE);
      await profile.getByRole('button', { name: 'Preview the profile entry' }).click();
      const preview = admin.getByTestId('statutory-profile-preview');
      await expect(preview.getByRole('heading', { level: 3, name: 'Preview' })).toBeFocused();
      await expect(preview.getByTestId('statutory-profile-after')).toContainText(
        'Standard resident employee',
      );
      if (person === ANA) {
        await expectAccessible(admin);
        await noOverflow(admin);
      }
      await keyboardTabReaches(admin, preview.getByTestId('statutory-profile-confirm'));
      await admin.keyboard.press('Enter');
      await expect(admin.getByTestId('statutory-profile-announcer')).toContainText(
        'Statutory profile recorded.',
      );
      await expect(admin.getByTestId('statutory-profile-timeline')).toContainText(
        'Employment contract',
      );
    }

    // 5. The preview is ready: aggregates, a semantic per-employee table, no payslip or payment.
    await navigateInApp(admin, `/admin/payroll-runs/${EN_ENTITY.id}/${EN_ENTITY.runId}/statutory`);
    await expect(admin.getByTestId('statutory-in-effect')).toContainText(PACK_CODE);
    await admin.getByTestId('statutory-preview-button').click();
    const ready = admin.getByTestId('statutory-preview');
    await expect(ready.getByTestId('statutory-preview-status')).toHaveText('Ready to create');
    await expect(ready.getByTestId('statutory-preview-supported')).toHaveText('2');
    await expect(ready.getByTestId('statutory-preview-blocked')).toHaveText('0');
    for (const label of [
      'Contractual gross',
      'Employee deductions',
      'Employer contributions',
      'Preview net',
      'Employer cost',
    ]) {
      await expect(ready.getByRole('columnheader', { name: label })).toBeVisible();
      await expect(ready.getByTestId('statutory-preview-totals')).toContainText(label);
    }
    await expect(ready.getByTestId('statutory-preview-totals')).toContainText('CDF');
    const anaRow = ready.getByTestId('statutory-line').filter({ hasText: ANA.number });
    await expect(anaRow.getByTestId('statutory-line-net')).toContainText('CDF');
    await expect(anaRow.getByTestId('statutory-line-employer')).toContainText('CDF');
    await expect(ready.getByTestId('statutory-create-confirm')).toBeEnabled();
    await expect(
      admin.getByRole('button', { name: /approve|payslip|disburse|\bpay\b/iu }),
    ).toHaveCount(0);
    await expect(admin.getByRole('link', { name: /payslip|disburse/iu })).toHaveCount(0);

    // 6. The explanation opens by keyboard and names components, rates, brackets and sources.
    const explanation = ready.getByTestId('statutory-explanation').filter({ hasText: ANA.number });
    await keyboardTabReaches(admin, explanation.locator('summary'));
    await admin.keyboard.press('Enter');
    await expect(explanation).toHaveAttribute('open', '');
    const pension = explanation.locator('[data-component-id="CNSS_EMPLOYEE_PENSION"]');
    await expect(pension).toContainText('CNSS pension, employee');
    await expect(pension.getByTestId('statutory-component-method')).toContainText(
      'Flat rate of 5%',
    );
    await expect(
      pension.getByTestId('statutory-component-source').getByRole('link'),
    ).toHaveAttribute('href', 'https://cnss.cd/?page_id=1388');
    const tax = explanation.locator('[data-component-id="IRPP_SALARY_WITHHOLDING"]');
    await expect(tax.getByTestId('statutory-brackets').locator('li')).toHaveCount(4);
    await expect(tax.getByTestId('statutory-cap')).toContainText('30%');
    await expect(explanation.locator('[data-component-id="CNSS_EMPLOYER_PENSION"]')).toContainText(
      'Employer contribution',
    );
    await expectAccessible(admin);
    await noOverflow(admin);

    // 7. Create the calculation, then open the frozen calculation trace.
    await ready.getByTestId('statutory-create-confirm').click();
    await expect(admin.getByTestId('statutory-calculation-announcer')).toContainText(
      'Statutory calculation created.',
    );
    const frozen = admin.getByTestId('statutory-frozen');
    await expect(
      frozen.getByRole('heading', { level: 3, name: 'Statutory calculation, version 1' }),
    ).toBeFocused();
    await expect(frozen.getByTestId('statutory-frozen-label')).toHaveText(
      'Statutory preview—not a payslip or payment',
    );
    await expect(frozen.getByTestId('statutory-frozen-pack')).toContainText(PACK_CODE);
    await expect(frozen.getByTestId('statutory-line')).toHaveCount(2);
    await expect(admin.getByTestId('statutory-version-row')).toHaveCount(1);
    const frozenExplanation = frozen
      .getByTestId('statutory-explanation')
      .filter({ hasText: ANA.number });
    await frozenExplanation.locator('summary').click();
    const trace = frozenExplanation.locator('[data-component-id="IRPP_SALARY_WITHHOLDING"]');
    await expect(trace.getByTestId('statutory-trace-annual')).toContainText('CDF');
    await expect(trace.getByTestId('statutory-trace-slices').locator('li').first()).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);
    // Amounts, profiles and employee data never reach the URL or the browser storage.
    expect(admin.url()).not.toMatch(/1000000|500000/u);
    expect(await storage(admin)).not.toMatch(
      new RegExp(`${stamp}|${ANA.family}|1000000|1,000,000|STANDARD_RESIDENT`, 'iu'),
    );
    await context.close();
  });

  test('French administrator resolves a missing-profile blocker, creates the calculation; another tenant cannot read it', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminA, 'fr');
    await openStatutory(admin, FR_ENTITY, 'Exécutions de paie');
    await expect(admin.getByTestId('statutory-label').first()).toHaveText(
      'Aperçu statutaire — ni bulletin de paie ni paiement',
    );

    // 1. The pack exists but is not activated for this legal entity: verification is required.
    await expect(admin.getByTestId('statutory-verification-required')).toContainText(
      'Vérification professionnelle requise',
    );
    const synthetic = admin
      .getByTestId('statutory-rule-pack')
      .locator(`[data-testid="statutory-pack"][data-code="${PACK_CODE}"]`);
    await expect(synthetic).toHaveAttribute('data-status', 'VERIFIED');
    await expect(synthetic.getByTestId('statutory-activate')).toBeDisabled();
    await synthetic
      .getByLabel(/Je comprends que mon organisation reste responsable de la revue juridique/u)
      .check();
    await synthetic.getByRole('button', { name: 'Activer le jeu de règles' }).click();
    await expect(admin.getByTestId('statutory-announcer')).toContainText('Jeu de règles activé.');
    await expect(admin.getByTestId('statutory-in-effect')).toContainText(PACK_CODE);
    await noOverflow(admin);

    // 2. Daniel has no profile: a blocker with a link; Clovis is fine.
    await admin.getByTestId('statutory-preview-button').click();
    const blocked = admin.getByTestId('statutory-preview');
    await expect(blocked.getByTestId('statutory-preview-status')).toHaveText('Bloqué');
    await expect(blocked.getByTestId('statutory-preview-blocked')).toHaveText('1');
    const entry = blocked.getByTestId('statutory-employee-blocker');
    await expect(entry).toHaveCount(1);
    await expect(entry).toContainText(DAN.number);
    await expect(entry).toContainText(
      'Aucun profil statutaire n’est en vigueur le premier jour d’éligibilité.',
    );
    await expect(blocked.getByTestId('statutory-create-confirm')).toBeDisabled();
    await expect(blocked.getByTestId('statutory-create-why')).toBeVisible();
    await expect(
      blocked.getByRole('columnheader', { name: 'Brut contractuel' }).first(),
    ).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);

    // 3. Resolve it on Daniel's profile page, then come back.
    await entry
      .getByRole('link', {
        name: new RegExp(`Ouvrir le profil statutaire de l’employé ${DAN.number}`, 'u'),
      })
      .click();
    await expect(admin).toHaveURL(
      new RegExp(`/admin/people/${DAN.employee}/statutory-profile$`, 'u'),
    );
    await expect(admin.getByRole('heading', { level: 1, name: 'Profil statutaire' })).toBeVisible();
    await expect(admin.getByTestId('statutory-label')).toHaveText(
      'Aperçu statutaire — ni bulletin de paie ni paiement',
    );
    const profile = admin.getByTestId('statutory-profile-form');
    await profile.getByLabel('Date d’effet', { exact: true }).fill(HIRE);
    await profile.getByRole('button', { name: 'Simuler l’entrée de profil' }).click();
    const preview = admin.getByTestId('statutory-profile-preview');
    await expect(preview.getByRole('heading', { level: 3, name: 'Aperçu' })).toBeFocused();
    await expectAccessible(admin);
    await noOverflow(admin);
    await preview.getByTestId('statutory-profile-confirm').click();
    await expect(admin.getByTestId('statutory-profile-announcer')).toContainText(
      'Profil statutaire enregistré.',
    );
    await expect(admin.getByTestId('statutory-profile-timeline')).toContainText(
      'Contrat de travail',
    );
    await admin.goBack();
    await expect(admin.getByTestId('statutory-in-effect')).toContainText(PACK_CODE);

    // 4. The preview is ready; create the calculation and read its trace in French.
    await admin.getByTestId('statutory-preview-button').click();
    const ready = admin.getByTestId('statutory-preview');
    await expect(ready.getByTestId('statutory-preview-status')).toHaveText('Prêt à créer');
    await expect(ready.getByTestId('statutory-preview-supported')).toHaveText('2');
    await expect(ready.getByTestId('statutory-preview-blocked')).toHaveText('0');
    await expect(ready.getByTestId('statutory-preview-totals')).toContainText('Net d’aperçu');
    await expect(ready.getByTestId('statutory-preview-totals')).toContainText('CDF');
    await expect(
      admin.getByRole('button', { name: /approuver|bulletin|verser|payer/iu }),
    ).toHaveCount(0);
    await expectAccessible(admin);
    await noOverflow(admin);
    await ready.getByTestId('statutory-create-confirm').click();
    await expect(admin.getByTestId('statutory-calculation-announcer')).toContainText(
      'Calcul statutaire créé.',
    );
    const frozen = admin.getByTestId('statutory-frozen');
    await expect(
      frozen.getByRole('heading', { level: 3, name: 'Calcul statutaire, version 1' }),
    ).toBeFocused();
    await expect(frozen.getByTestId('statutory-line')).toHaveCount(2);
    const explanation = frozen.getByTestId('statutory-explanation').filter({ hasText: DAN.number });
    await explanation.locator('summary').click();
    await expect(explanation.locator('[data-component-id="CNSS_EMPLOYEE_PENSION"]')).toContainText(
      'Pension CNSS, part salariale',
    );
    await expect(
      explanation
        .locator('[data-component-id="CNSS_EMPLOYEE_PENSION"]')
        .getByTestId('statutory-component-method'),
    ).toContainText(/Taux fixe de 5\s%/u);
    await expect(
      explanation
        .locator('[data-component-id="IRPP_SALARY_WITHHOLDING"]')
        .getByTestId('statutory-trace-annual'),
    ).toContainText('CDF');
    await expectAccessible(admin);
    await noOverflow(admin);
    expect(admin.url()).not.toMatch(/400000|800000/u);
    expect(await storage(admin)).not.toMatch(new RegExp(`${stamp}|${DAN.family}|400000`, 'iu'));

    // 5. The calculation id (read as the owner) is not readable by another tenant's administrator.
    const owned = await admin.request.get(
      `${CORE_API}/legal-entities/${FR_ENTITY.id}/payroll-runs/${FR_ENTITY.runId}/statutory-calculations`,
      { headers: { Authorization: bearer() } },
    );
    expect(owned.status()).toBe(200);
    const calculationId = ((await owned.json()) as { items: { calculationId: string }[] }).items[0]
      ?.calculationId;
    expect(calculationId).toBeTruthy();
    await context.close();

    const otherContext = await browser.newContext();
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    const calculationPath = `/legal-entities/${FR_ENTITY.id}/payroll-runs/${FR_ENTITY.runId}/statutory-calculations`;
    for (const path of [calculationPath, `${calculationPath}/${calculationId}`]) {
      const foreign = await other.request.get(`${CORE_API}${path}`, {
        headers: { Authorization: otherBearer() },
      });
      expect(foreign.status()).toBe(404);
      expect([
        'LEGAL_ENTITY_NOT_FOUND',
        'PAYROLL_RUN_NOT_FOUND',
        'STATUTORY_CALCULATION_NOT_FOUND',
      ]).toContain(((await foreign.json()) as { code: string }).code);
    }
    const foreignProfile = await other.request.get(
      `${CORE_API}/employees/${DAN.employee}/statutory-profile`,
      { headers: { Authorization: otherBearer() } },
    );
    expect(foreignProfile.status()).toBe(404);
    expect(((await foreignProfile.json()) as { code: string }).code).toBe('COMPENSATION_NOT_FOUND');
    // In the page: the same safe not-found state, with no rule-pack or calculation panel.
    await navigateInApp(other, `/admin/payroll-runs/${FR_ENTITY.id}/${FR_ENTITY.runId}/statutory`);
    const alert = other.getByTestId('statutory-run-error');
    await expect(alert).toHaveAttribute('data-kind', 'notFound');
    await expect(alert).toContainText(/n’existe pas/u);
    await expect(other.getByTestId('statutory-rule-pack')).toHaveCount(0);
    await expect(other.getByTestId('statutory-calculation')).toHaveCount(0);
    await expect(other.getByTestId('statutory-label').first()).toHaveText(
      'Aperçu statutaire — ni bulletin de paie ni paiement',
    );
    await expectAccessible(other);
    await otherContext.close();
  });
});

import { expect, test, type Page } from '@playwright/test';
import { CORE_API, TENANT_A, USERS, expectAccessible, primaryNav, signIn, sql } from './support';

// MVP-094A (Issue #177): exactly two journeys, with synthetic data only.
// (1) English: the administrator configures a legal entity's monthly payroll policy, previews and
//     creates a monthly run with one full-month and one partial-month employee, then inspects the
//     frozen evidence (employee lines, eligible ranges, source records, "Contractual gross" and
//     "Draft—not statutory payroll").
// (2) French: the administrator sees a blocker (an employee without compensation), uses an
//     eligible state (adds the compensation through the public API) to proceed, creates the run,
//     and proves that another tenant's administrator cannot read it.
// Setup uses only public Core API endpoints (organization, people import, compensation, payroll
// policy) with the administrator's existing SSO session. Calculation, authorization, limits, races
// and rollbacks are covered by the Core tests, not here. axe, reflow at 320 px, 390 px and 640 px
// (200 % zoom of a 1280 px window), browser storage and URLs are checked. No screenshot gate, no
// retry and no arbitrary sleep: every wait is a web-first assertion.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const lower = stamp.toLowerCase();
const letters = lower.replace(/[^a-z]/gu, '') || 'x';

interface Site {
  code: string;
  entityCode: string;
  siteCode: string;
  departmentCode: string;
  name: string;
  id: string;
}
const site = (suffix: string, name: string): Site => ({
  code: `PR${suffix}-${stamp}`,
  entityCode: `PR${suffix}-${stamp}`,
  siteCode: `PS${suffix}-${stamp}`,
  departmentCode: `PD${suffix}-${stamp}`,
  name: `${name} ${stamp}`,
  id: '',
});
const EN_ENTITY = site('E', 'Payroll Entity');
const FR_ENTITY = site('F', 'Entité Paie');

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
const DAYS_IN_MONTH = Number(LAST_DAY.slice(8, 10));

/** The first day a compensation can start: the Core accepts at most 60 days in the past. */
const earliestCompensation = plusDays(TODAY, -59);
const later = (a: string, b: string) => (a > b ? a : b);

const person = (suffix: string, given: string, hire: string, entity: Site): Person => ({
  number: `PAY-${stamp}-${suffix}`,
  given,
  family: `Kabongo${letters}${suffix.toLowerCase()}`,
  hire,
  entity,
  employee: '',
});
// Anna: employed before the month; Bea: hired in the middle of the month (a partial month).
const ANA = person('A', 'Anna', plusDays(FIRST_DAY, -5), EN_ENTITY);
const BEA = person('B', 'Béatrice', plusDays(FIRST_DAY, 14), EN_ENTITY);
// Clovis has compensation from the start; Daniel has none until the journey adds it.
const CLO = person('C', 'Clovis', plusDays(FIRST_DAY, -5), FR_ENTITY);
const DAN = person('D', 'Daniel', plusDays(FIRST_DAY, -5), FR_ENTITY);

/** The days of the month from a start date (the Core's eligible range for these employees). */
function eligibleFrom(start: string): { from: string; days: number } {
  const from = later(start, FIRST_DAY);
  return { from, days: Math.round((Date.parse(LAST_DAY) - Date.parse(from)) / 86_400_000) + 1 };
}

/** CALENDAR_DAYS, rounded once HALF_UP in minor units (cents), as the Core does. */
function calendarGrossCents(monthlyCents: bigint, days: number): bigint {
  if (days === DAYS_IN_MONTH) return monthlyCents;
  const n = monthlyCents * BigInt(days);
  const d = BigInt(DAYS_IN_MONTH);
  return (2n * n + d) / (2n * d);
}
function usdText(cents: bigint): RegExp {
  const whole = (cents / 100n).toString().replace(/\B(?=(\d{3})+(?!\d))/gu, ',');
  return new RegExp(`USD\\s${whole}\\.${(cents % 100n).toString().padStart(2, '0')}`, 'u');
}

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
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
      `no horizontal overflow at ${viewport.width} px`,
    ).toBe(true);
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

async function keyboardTabReaches(page: Page, testId: string) {
  // Keyboard-only: tab forward until the control has focus (bounded).
  for (let step = 0; step < 80; step++) {
    if (await page.getByTestId(testId).evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await page.getByTestId(testId).evaluate((el) => el === document.activeElement)).toBe(true);
}

async function post(page: Page, bearer: () => string, path: string, key: string, data: object) {
  return page.request.post(`${CORE_API}${path}`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pr-${key}-${stamp}-0001` },
    data,
  });
}

/** Establishes a monthly compensation through the public preview and commit endpoints. */
async function establish(
  page: Page,
  bearer: () => string,
  p: Person,
  currency: 'USD' | 'CDF',
  amount: string,
) {
  const effectiveFrom = later(p.hire, earliestCompensation);
  const command = { action: 'ESTABLISHED', effectiveFrom, currency, amount };
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

/** Records a policy through the public preview and commit endpoints. */
async function recordPolicy(
  page: Page,
  bearer: () => string,
  entity: Site,
  method: 'CALENDAR_DAYS' | 'FIXED_30_DAYS',
) {
  const command = {
    effectiveFrom: FIRST_DAY,
    payDay: { kind: 'LAST_DAY', day: null },
    prorationMethod: method,
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

async function choose(page: Page, entity: Site) {
  await page
    .getByTestId('payroll-entity')
    .selectOption({ label: `${entity.name} (${entity.code})` });
}

test.describe.serial('MVP-094A: monthly payroll runs and contractual gross', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300_000);
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
        'Idempotency-Key': `e2e-pr-import-${stamp}-0001`,
      },
      data: Buffer.from(file, 'utf8'),
    });
    expect(upload.status()).toBe(201);
    const imported = (await upload.json()) as { id: string; previewDigest: string };
    const commit = await page.request.post(`${CORE_API}/employee-imports/${imported.id}/commit`, {
      headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-pr-commit-${stamp}-0001` },
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
    // Compensation: Anna and Bea in USD (English journey); Clovis in CDF. Daniel has none.
    await establish(page, bearer, ANA, 'USD', '1250.00');
    await establish(page, bearer, BEA, 'USD', '1000.00');
    await establish(page, bearer, CLO, 'CDF', '450000');
    // The French entity's policy is recorded through the API; the English journey uses the page.
    await recordPolicy(page, bearer, FR_ENTITY, 'FIXED_30_DAYS');
    await page.close();
  });

  test('English administrator configures a policy, creates a monthly run and inspects its frozen evidence', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'en');
    await primaryNav(admin).getByRole('link', { name: 'Payroll runs', exact: true }).click();
    await expect(admin.getByRole('heading', { level: 1, name: 'Payroll runs' })).toBeVisible();
    await choose(admin, EN_ENTITY);

    // The policy: none yet, then added by preview and explicit confirmation.
    const policy = admin.getByTestId('payroll-policy');
    await expect(policy.getByTestId('payroll-policy-none')).toBeVisible();
    await expect(policy.getByTestId('payroll-policy-advisory')).toContainText('legal advice');
    await expectAccessible(admin);
    await policy.getByRole('button', { name: 'Add a policy' }).click();
    const form = policy.getByTestId('payroll-policy-form');
    await expect(
      form.getByRole('heading', { level: 3, name: 'Add a payroll policy' }),
    ).toBeFocused();
    await form.getByLabel('Effective date', { exact: true }).fill(FIRST_DAY);
    await form.getByLabel('Pay day', { exact: true }).selectOption('LAST_DAY');
    await form.getByLabel('Proration method', { exact: true }).selectOption('CALENDAR_DAYS');
    await form.getByRole('button', { name: 'Preview', exact: true }).click();
    const policyPreview = policy.getByTestId('payroll-policy-preview');
    await expect(policyPreview.getByRole('heading', { level: 4, name: 'Preview' })).toBeFocused();
    await expect(policyPreview.getByTestId('policy-preview-after')).toContainText('Calendar days');
    await expectAccessible(admin);
    await noOverflow(admin);
    await keyboardTabReaches(admin, 'payroll-policy-confirm');
    await admin.keyboard.press('Enter');
    await expect(admin.getByTestId('payroll-runs-announcer')).toContainText(
      'Payroll policy recorded.',
    );
    await expect(policy.getByTestId('payroll-policy-current')).toContainText('Calendar days');
    await expect(policy.getByTestId('payroll-policy-timeline')).toBeVisible();

    // The run: the last closed month is the default; preview shows both employees.
    const create = admin.getByTestId('payroll-create');
    await expect(admin.getByTestId('payroll-create-period')).toHaveValue(PERIOD);
    await create.getByLabel('Currency', { exact: true }).selectOption('USD');
    await create.getByRole('button', { name: 'Preview the run' }).click();
    const preview = create.getByTestId('payroll-create-preview-panel');
    await expect(preview.getByTestId('payroll-draft-label')).toHaveText(
      'Draft—not statutory payroll',
    );
    await expect(preview.getByTestId('payroll-preview-count')).toHaveText('2');
    await expect(preview.getByTestId('payroll-preview-blocked')).toHaveText('0');
    const anaDays = eligibleFrom(later(ANA.hire, earliestCompensation));
    const beaDays = eligibleFrom(later(BEA.hire, earliestCompensation));
    const anaGross = calendarGrossCents(125_000n, anaDays.days);
    const beaGross = calendarGrossCents(100_000n, beaDays.days);
    const anaRow = preview.getByTestId('payroll-line').filter({ hasText: ANA.number });
    const beaRow = preview.getByTestId('payroll-line').filter({ hasText: BEA.number });
    await expect(anaRow.getByTestId('payroll-line-gross')).toContainText(usdText(anaGross));
    await expect(beaRow.getByTestId('payroll-line-gross')).toContainText(usdText(beaGross));
    await expect(beaRow).toContainText(`${beaDays.days} days`);
    await expect(preview.getByTestId('payroll-preview-total')).toContainText(
      usdText(anaGross + beaGross),
    );
    await expect(
      preview.getByRole('columnheader', { name: 'Contractual gross' }).first(),
    ).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);
    await preview.getByTestId('payroll-create-confirm').click();

    // The frozen run: summary, policy, lines with ranges and source records.
    await expect(admin).toHaveURL(
      new RegExp(`/admin/payroll-runs/${EN_ENTITY.id}/[0-9a-f-]{36}$`, 'u'),
    );
    const summary = admin.getByTestId('payroll-summary');
    await expect(summary.getByRole('heading', { level: 2, name: 'Run summary' })).toBeFocused();
    await expect(summary.getByTestId('payroll-draft-label')).toHaveText(
      'Draft—not statutory payroll',
    );
    await expect(summary.getByTestId('payroll-detail-count')).toHaveText('2');
    await expect(summary.getByTestId('payroll-detail-total')).toContainText(
      usdText(anaGross + beaGross),
    );
    await expect(summary).toContainText('Contractual gross');
    const lines = admin.getByTestId('payroll-detail-table');
    const detailAna = lines.getByTestId('payroll-line').filter({ hasText: ANA.number });
    await expect(detailAna.getByTestId('payroll-range')).toHaveCount(1);
    await expect(detailAna.locator('[data-kind="EMPLOYMENT"]')).toHaveCount(1);
    await expect(detailAna.locator('[data-kind="PLACEMENT"]')).toHaveCount(1);
    await expect(detailAna.locator('[data-kind="COMPENSATION"]')).toHaveCount(1);
    await expect(
      lines
        .getByTestId('payroll-line')
        .filter({ hasText: BEA.number })
        .getByTestId('payroll-line-gross'),
    ).toContainText(usdText(beaGross));
    await expect(admin.getByTestId('payroll-detail-policy')).toContainText('Calendar days');
    await expectAccessible(admin);
    await noOverflow(admin);
    // Amounts and employee data never reach the URL or the browser storage.
    expect(admin.url()).not.toMatch(/1250|1000|\d{4}\.\d\d/u);
    expect(await storage(admin)).not.toMatch(new RegExp(`${stamp}|${ANA.family}|1250|1,250`, 'iu'));

    // The list shows the run with its status label and a link back to the detail.
    await admin.getByRole('link', { name: 'Back to payroll runs' }).click();
    await choose(admin, EN_ENTITY);
    const row = admin.getByTestId('payroll-run-row').first();
    await expect(row).toContainText('USD');
    await expect(row.getByTestId('payroll-run-status')).toHaveText('Draft—not statutory payroll');
    await expectAccessible(admin);
    await context.close();
  });

  test('French administrator sees a blocker, fixes the source state, creates the run; another tenant cannot read it', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext();
    const admin = await context.newPage();
    const bearer = await signIn(admin, USERS.adminA, 'fr');
    await primaryNav(admin).getByRole('link', { name: 'Exécutions de paie', exact: true }).click();
    await expect(
      admin.getByRole('heading', { level: 1, name: 'Exécutions de paie' }),
    ).toBeVisible();
    await choose(admin, FR_ENTITY);
    await expect(admin.getByTestId('payroll-policy-current')).toContainText('30 jours fixes');
    await expect(admin.getByTestId('payroll-policy-advisory')).toContainText('avis juridique');
    await noOverflow(admin);

    // 1. Daniel has no compensation: a blocker, and the creation is disabled.
    const create = admin.getByTestId('payroll-create');
    await create.getByLabel('Devise', { exact: true }).selectOption('CDF');
    await create.getByRole('button', { name: 'Simuler l’exécution' }).click();
    const blocked = create.getByTestId('payroll-create-preview-panel');
    await expect(blocked.getByTestId('payroll-draft-label')).toHaveText(
      'Brouillon — pas une paie statutaire',
    );
    await expect(blocked.getByTestId('payroll-preview-blocked')).toHaveText('1');
    const danRow = blocked.getByTestId('payroll-line').filter({ hasText: DAN.number });
    await expect(danRow).toContainText(
      'La rémunération manque pour une partie de l’emploi dans cette entité juridique.',
    );
    await expect(blocked.getByTestId('payroll-create-confirm')).toBeDisabled();
    await expect(blocked.getByTestId('payroll-create-disabled-why')).toBeVisible();
    await expect(
      blocked.getByRole('columnheader', { name: 'Brut contractuel' }).first(),
    ).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);

    // 2. Daniel's compensation is recorded (public API); a new preview has no blocker.
    await establish(admin, bearer, DAN, 'CDF', '300000');
    await create.getByRole('button', { name: 'Simuler l’exécution' }).click();
    const ready = create.getByTestId('payroll-create-preview-panel');
    await expect(ready.getByTestId('payroll-preview-blocked')).toHaveText('0');
    await expect(ready.getByTestId('payroll-preview-count')).toHaveText('2');
    await expect(ready.getByTestId('payroll-line').filter({ hasText: DAN.number })).toContainText(
      /300\s000\sCDF/u,
    );
    await expect(ready.getByTestId('payroll-create-confirm')).toBeEnabled();
    await expectAccessible(admin);
    await ready.getByTestId('payroll-create-confirm').click();

    // 3. The frozen run, in French.
    await expect(admin).toHaveURL(
      new RegExp(`/admin/payroll-runs/${FR_ENTITY.id}/[0-9a-f-]{36}$`, 'u'),
    );
    const runId = /\/([0-9a-f-]{36})$/u.exec(admin.url())?.[1] ?? '';
    expect(runId).not.toBe('');
    const summary = admin.getByTestId('payroll-summary');
    await expect(
      summary.getByRole('heading', { level: 2, name: 'Résumé de l’exécution' }),
    ).toBeFocused();
    await expect(summary.getByTestId('payroll-draft-label')).toHaveText(
      'Brouillon — pas une paie statutaire',
    );
    await expect(summary).toContainText('Brut contractuel');
    await expect(admin.getByTestId('payroll-detail-count')).toHaveText('2');
    await expect(
      admin
        .getByTestId('payroll-detail-table')
        .getByTestId('payroll-line')
        .filter({ hasText: CLO.number }),
    ).toContainText(/450\s000\sCDF/u);
    await expect(admin.locator('[data-kind="COMPENSATION"]').first()).toBeVisible();
    await expectAccessible(admin);
    await noOverflow(admin);
    expect(admin.url()).not.toMatch(/450000|300000/u);
    expect(await storage(admin)).not.toMatch(new RegExp(`${stamp}|${CLO.family}|450000`, 'iu'));
    await context.close();

    // 4. Another tenant's administrator cannot read the run, its entity or its policy.
    const otherContext = await browser.newContext();
    const other = await otherContext.newPage();
    const otherBearer = await signIn(other, USERS.adminB, 'fr');
    const foreignRun = await other.request.get(
      `${CORE_API}/legal-entities/${FR_ENTITY.id}/payroll-runs/${runId}`,
      { headers: { Authorization: otherBearer() } },
    );
    expect(foreignRun.status()).toBe(404);
    expect(((await foreignRun.json()) as { code: string }).code).toBe('PAYROLL_RUN_NOT_FOUND');
    const foreignList = await other.request.get(
      `${CORE_API}/legal-entities/${FR_ENTITY.id}/payroll-runs`,
      { headers: { Authorization: otherBearer() } },
    );
    expect(foreignList.status()).toBe(404);
    const foreignPolicy = await other.request.get(
      `${CORE_API}/legal-entities/${FR_ENTITY.id}/payroll-policy`,
      { headers: { Authorization: otherBearer() } },
    );
    expect(foreignPolicy.status()).toBe(404);
    expect(['LEGAL_ENTITY_NOT_FOUND', 'PAYROLL_RUN_NOT_FOUND']).toContain(
      ((await foreignPolicy.json()) as { code: string }).code,
    );
    await otherContext.close();
  });
});

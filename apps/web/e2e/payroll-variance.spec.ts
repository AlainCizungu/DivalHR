import { randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { TENANT_A } from './credentials';
import { CORE_API, USERS, expectAccessible, signIn, sql, subjectLock, primaryNav } from './support';

// MVP-091 (Issue #143): reviewing the worked-time variance between two payroll-input runs with the
// real identity provider and database. The fixtures are built without any invitation, extra
// sign-in or shared rate-limit pressure: two legal entities and their sites over the API as tenant
// A's administrator, and the immutable runs written by a database helper that satisfies the V37
// exactness constraints (identifiers, dates, counts and minutes only; the generation of real runs
// from locked weeks is the MVP-090A journey). Exactly two journeys:
//  - English: a run of 4 employees is compared with its default prior run: one unchanged (its
//    employment changed between the runs), two changed (one material, one below the defaults), one
//    new and one missing; the totals, the signed minutes and the percentages are the server's, the
//    material filter and the other filters narrow the rows, new thresholds recompute the review,
//    the page size and "Show more" are exercised by the source runs opening from the review; the
//    review writes nothing (no run, row, event, or generation changes);
//  - French : « Examiner les écarts » of the earliest run and of a six-day run show the empty
//    baseline (nothing is invented), then the third run compares with the only eligible earlier run
//    (the six-day run is not offered), with the fixed notice, the differences and the source run
//    opening.
// Classifications, boundaries, cursors, tenant isolation, audits and logs are proven at domain and
// API level, not here.

const stamp = Date.now().toString(36).toUpperCase().slice(-6);
const ENTITY_ONE = `XV-${stamp}`;
const ENTITY_TWO = `XU-${stamp}`;
const SITE_ONE = `XT-${stamp}`;
const SITE_TWO = `XS-${stamp}`;
const ENTITY_ONE_NAME = `Entité Variation Nord ${stamp}`;
const ENTITY_TWO_NAME = `Entité Variation Sud ${stamp}`;
const ZONE = 'Africa/Lubumbashi';

const E1 = randomUUID();
const E2 = randomUUID();
const E3 = randomUUID();
const E4 = randomUUID();
const E5 = randomUUID();
const E6 = randomUUID();
const E7 = randomUUID();
const E8 = randomUUID();

let entityOne = '';
let entityTwo = '';
let siteOne = '';
let siteTwo = '';
let runPrior = '';
let runCurrent = '';
let runFirst = '';
let runShort = '';
let runLast = '';

interface Seed {
  employee: string;
  employment: string;
  minutes: number;
}

/** PR evidence (EVIDENCE=1 only): a screenshot written outside the working tree. */
async function evidence(page: Page, name: string, region?: Locator) {
  const out = process.env.EVIDENCE_DIR;
  if (!process.env.EVIDENCE || !out) return;
  mkdirSync(out, { recursive: true });
  await page.waitForLoadState('networkidle');
  const path = join(out, `${name}.png`);
  if (region) await region.screenshot({ path, animations: 'disabled' });
  else await page.screenshot({ path, fullPage: true, animations: 'disabled' });
}

function plusDays(date: string, days: number): string {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/**
 * Writes one immutable payroll-input run in one transaction (the deferred exactness constraint is
 * proven at commit): one locked week starting on `start` (a Monday), one row per employee on that
 * Monday. `age` orders the runs by generation instant, the oldest first.
 */
function seedRun(
  entityId: string,
  siteId: string,
  start: string,
  end: string,
  employees: Seed[],
  age: number,
): string {
  const run = randomUUID();
  const generation = randomUUID();
  const lock = randomUUID();
  const total = employees.reduce((sum, e) => sum + e.minutes, 0);
  const n = employees.length;
  const digest = randomUUID().replaceAll('-', '') + randomUUID().replaceAll('-', '');
  const statements = [
    'BEGIN',
    `INSERT INTO payroll.input_run (tenant_id, id, legal_entity_id, version, status, schema_version,
       period_start, period_end, business_timezone, week_count, employee_count, timesheet_count,
       row_count, worked_minutes, excluded_rows, excluded_timesheets, evidence_digest, generated_at,
       generated_by)
     VALUES ('${TENANT_A}', '${run}', '${entityId}', 1, 'GENERATED', 1, '${start}', '${end}',
       '${ZONE}', 1, ${n}, ${n}, ${n}, ${total}, 0, 0, '${digest}',
       now() - interval '${age} seconds', 'e2e-seed')`,
    `INSERT INTO payroll.input_source_week (tenant_id, run_id, generation_id, site_id, week_start,
       site_timezone, generation_version, lock_action_id, lock_version, lock_evidence_digest,
       timesheet_count, row_count, worked_minutes)
     VALUES ('${TENANT_A}', '${run}', '${generation}', '${siteId}', '${start}', '${ZONE}', 1,
       '${lock}', 1, '${digest}', ${n}, ${n}, ${total})`,
    ...employees.map(
      (e) =>
        `INSERT INTO payroll.input_employee (tenant_id, run_id, employee_id, employment_id,
           first_work_date, last_work_date, timesheet_count, row_count, worked_minutes)
         VALUES ('${TENANT_A}', '${run}', '${e.employee}', '${e.employment}', '${start}',
           '${start}', 1, 1, ${e.minutes})`,
    ),
    ...employees.map(
      (e, index) =>
        `INSERT INTO payroll.input_source_row (tenant_id, run_id, line, employee_id, employment_id,
           generation_id, site_id, cost_center_id, work_date, shift_id, timesheet_id,
           timesheet_revision, approval_action_id, approval_cycle, lock_action_id, worked_minutes)
         VALUES ('${TENANT_A}', '${run}', ${index + 1}, '${e.employee}', '${e.employment}',
           '${generation}', '${siteId}', NULL, '${start}', '${randomUUID()}', '${randomUUID()}', 1,
           '${randomUUID()}', 1, '${lock}', ${e.minutes})`,
    ),
    'COMMIT',
  ];
  sql(statements.join(';\n') + ';');
  return run;
}

/** Everything a review must leave exactly as it was. */
function evidenceOf(): string {
  return sql(
    `SELECT (SELECT count(*) FROM payroll.input_run WHERE tenant_id = '${TENANT_A}') || ':' ||
            (SELECT count(*) FROM payroll.input_employee WHERE tenant_id = '${TENANT_A}') || ':' ||
            (SELECT count(*) FROM payroll.input_source_row WHERE tenant_id = '${TENANT_A}') || ':' ||
            (SELECT count(*) FROM payroll.input_source_week WHERE tenant_id = '${TENANT_A}') || ':' ||
            (SELECT coalesce(sum(worked_minutes), 0) FROM payroll.input_run
              WHERE tenant_id = '${TENANT_A}') || ':' ||
            (SELECT count(*) FROM platform.outbox_event
              WHERE envelope ->> 'tenantId' = '${TENANT_A}'
                AND envelope ->> 'eventType' LIKE 'payroll.%')`,
  );
}

function audits(action: string): number {
  return Number(
    sql(
      `SELECT count(*) FROM platform.audit_event WHERE tenant_id = '${TENANT_A}'
       AND action = '${action}'`,
    ),
  );
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

/** axe at the top of the page (the sticky top bar would otherwise cover the last control). */
async function accessible(page: Page) {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
  });
  await expectAccessible(page);
}

/** No run, employee, cursor or threshold reaches browser storage or the URL. */
async function clean(page: Page, secrets: string[]) {
  const stored = await storage(page);
  for (const secret of [...secrets, stamp, 'threshold']) {
    expect(stored).not.toContain(secret);
    expect(page.url()).not.toContain(secret);
  }
  expect(page.url()).not.toMatch(
    /[?&#](code|state|session_state|runId|comparisonRunId|cursor|absoluteMinuteThreshold)=/u,
  );
}

/** Opens "Payroll preparation" from the navigation; the page's title and statement are exact. */
async function openPage(page: Page, language: 'en' | 'fr') {
  const en = language === 'en';
  await primaryNav(page)
    .getByRole('link', { name: en ? 'Payroll preparation' : 'Préparation de la paie', exact: true })
    .click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(
    en ? 'Payroll preparation' : 'Préparation de la paie',
  );
  await expect(page.getByTestId('payroll-prep-history')).toBeVisible();
}

/** The history row of an entity's run starting on a date. */
function historyRow(page: Page, entityName: string, start: string): Locator {
  return page
    .getByTestId('payroll-prep-item')
    .filter({ hasText: entityName })
    .and(page.locator(`[data-period-start="${start}"]`));
}

async function createEntity(
  page: Page,
  bearer: () => string,
  codes: { entity: string; site: string },
  name: string,
  key: string,
) {
  const entity = await page.request.post(`${CORE_API}/legal-entities`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-var-le-${key}-${stamp}-0001` },
    data: { code: codes.entity, name, countryCode: 'CD', effectiveFrom: '2026-01-01' },
  });
  expect(entity.status()).toBe(201);
  const entityId = ((await entity.json()) as { id: string }).id;
  const site = await page.request.post(`${CORE_API}/sites`, {
    headers: { Authorization: bearer(), 'Idempotency-Key': `e2e-var-st-${key}-${stamp}-0001` },
    data: {
      legalEntityId: entityId,
      code: codes.site,
      name: `Site ${name}`,
      timezone: ZONE,
      effectiveFrom: '2026-01-01',
    },
  });
  expect(site.status()).toBe(201);
  return { entityId, siteId: ((await site.json()) as { id: string }).id };
}

let releaseSubject: () => void = () => undefined;

test.describe.serial('MVP-091: review payroll-input variance', () => {
  test.beforeAll(async ({ browser }) => {
    test.setTimeout(300_000);
    // The other specs writing as tenant A's administrator never run at the same time.
    releaseSubject = await subjectLock(USERS.adminA[0]);
    const context = await browser.newContext();
    const page = await context.newPage();
    const bearer = await signIn(page, USERS.adminA, 'fr');
    const one = await createEntity(
      page,
      bearer,
      { entity: ENTITY_ONE, site: SITE_ONE },
      ENTITY_ONE_NAME,
      'one',
    );
    const two = await createEntity(
      page,
      bearer,
      { entity: ENTITY_TWO, site: SITE_TWO },
      ENTITY_TWO_NAME,
      'two',
    );
    await context.close();
    entityOne = one.entityId;
    siteOne = one.siteId;
    entityTwo = two.entityId;
    siteTwo = two.siteId;

    // Entity one: the prior run (Monday 1 June to Sunday 7 June) and the current one (8 to 14 June).
    runPrior = seedRun(
      entityOne,
      siteOne,
      '2026-06-01',
      '2026-06-07',
      [
        { employee: E1, employment: randomUUID(), minutes: 480 },
        { employee: E2, employment: randomUUID(), minutes: 600 },
        { employee: E3, employment: randomUUID(), minutes: 300 },
        { employee: E4, employment: randomUUID(), minutes: 120 },
      ],
      50,
    );
    runCurrent = seedRun(
      entityOne,
      siteOne,
      '2026-06-08',
      '2026-06-14',
      [
        // The same person under another employment: one identity, both employments returned.
        { employee: E1, employment: randomUUID(), minutes: 480 },
        { employee: E2, employment: randomUUID(), minutes: 780 },
        { employee: E3, employment: randomUUID(), minutes: 330 },
        { employee: E5, employment: randomUUID(), minutes: 240 },
      ],
      40,
    );
    // Entity two: two seven-day runs and, between them, one of six days.
    runFirst = seedRun(
      entityTwo,
      siteTwo,
      '2026-06-01',
      '2026-06-07',
      [
        { employee: E6, employment: randomUUID(), minutes: 600 },
        { employee: E7, employment: randomUUID(), minutes: 300 },
      ],
      30,
    );
    runShort = seedRun(
      entityTwo,
      siteTwo,
      '2026-06-08',
      '2026-06-13',
      [{ employee: E6, employment: randomUUID(), minutes: 100 }],
      20,
    );
    runLast = seedRun(
      entityTwo,
      siteTwo,
      '2026-06-15',
      '2026-06-21',
      [
        { employee: E6, employment: randomUUID(), minutes: 660 },
        { employee: E8, employment: randomUUID(), minutes: 200 },
      ],
      10,
    );
  });

  test.afterAll(() => {
    releaseSubject();
  });

  test('a run is compared with its default prior run, filtered and re-thresholded, and the review writes nothing, in English', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    expect(plusDays('2026-06-01', 7)).toBe('2026-06-08');
    const context = await browser.newContext({ locale: 'en-US' });
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'en');
    await openPage(admin, 'en');
    const before = evidenceOf();
    const reviewsBefore = audits('payroll-variance.review');

    const row = historyRow(admin, ENTITY_ONE_NAME, '2026-06-08');
    await expect(row).toHaveCount(1);
    await row.getByTestId('payroll-prep-item-variance').click();

    const review = admin.getByTestId('payroll-variance');
    await expect(review.getByRole('heading', { level: 2 })).toHaveText(
      'Worked-time input differences',
    );
    await expect(review.getByTestId('payroll-variance-notice')).toHaveText(
      'Worked-time inputs only. Monthly salary is not calculated from these minutes. These differences do not automatically change pay, tax, deductions or payment.',
    );
    // The default is the only eligible earlier run; the defaults are in the fields.
    const lines = review.getByTestId('payroll-variance-line');
    await expect(lines).toHaveCount(5);
    await expect(review.getByTestId('payroll-variance-comparison').locator('option')).toHaveCount(
      1,
    );
    await expect(review.getByTestId('payroll-variance-absolute')).toHaveValue('120');
    await expect(review.getByTestId('payroll-variance-percentage')).toHaveValue('20');
    await expect(review.getByTestId('payroll-variance-current-period')).toContainText('2026');
    await expect(review.getByTestId('payroll-variance-comparison-period')).toContainText('2026');

    // The server's totals: 4 employees each side, 30 h 30 min against 25 h 0 min, +5 h 30 min.
    await expect(review.getByTestId('payroll-variance-total-current-employees')).toHaveText('4');
    await expect(review.getByTestId('payroll-variance-total-comparison-employees')).toHaveText('4');
    await expect(review.getByTestId('payroll-variance-total-current-minutes')).toHaveText(
      '30 h 30 min',
    );
    await expect(review.getByTestId('payroll-variance-total-comparison-minutes')).toHaveText(
      '25 h 0 min',
    );
    await expect(review.getByTestId('payroll-variance-total-delta')).toHaveText('+5 h 30 min');
    await expect(review.getByTestId('payroll-variance-total-new')).toHaveText('1');
    await expect(review.getByTestId('payroll-variance-total-missing')).toHaveText('1');
    await expect(review.getByTestId('payroll-variance-total-changed')).toHaveText('2');
    await expect(review.getByTestId('payroll-variance-total-unchanged')).toHaveText('1');
    await expect(review.getByTestId('payroll-variance-total-material')).toHaveText('3');

    // Material first, then the largest absolute difference: new (+4 h), changed (+3 h), missing
    // (−2 h), then the changed one below the defaults and the unchanged one.
    await expect(lines.nth(0)).toHaveAttribute('data-classification', 'NEW');
    await expect(lines.nth(0)).toContainText('Present only in current inputs');
    await expect(lines.nth(0)).toContainText(E5);
    await expect(lines.nth(0).getByTestId('payroll-variance-line-delta')).toHaveText('+4 h 0 min');
    await expect(lines.nth(0).getByTestId('payroll-variance-line-percent')).toHaveText('—');
    await expect(lines.nth(1)).toHaveAttribute('data-classification', 'CHANGED');
    await expect(lines.nth(1)).toContainText(E2);
    await expect(lines.nth(1)).toHaveAttribute('data-material', 'true');
    await expect(lines.nth(1).getByTestId('payroll-variance-line-delta')).toHaveText('+3 h 0 min');
    await expect(lines.nth(1).getByTestId('payroll-variance-line-percent')).toHaveText('+30%');
    await expect(lines.nth(2)).toHaveAttribute('data-classification', 'MISSING');
    await expect(lines.nth(2)).toContainText('Present only in comparison inputs');
    await expect(lines.nth(2)).toContainText(E4);
    await expect(lines.nth(2).getByTestId('payroll-variance-line-delta')).toHaveText('−2 h 0 min');
    await expect(lines.nth(2).getByTestId('payroll-variance-line-percent')).toHaveText('−100%');
    await expect(lines.nth(3)).toHaveAttribute('data-classification', 'CHANGED');
    await expect(lines.nth(3)).toContainText(E3);
    await expect(lines.nth(3)).toHaveAttribute('data-material', 'false');
    await expect(lines.nth(3).getByTestId('payroll-variance-line-delta')).toHaveText('+0 h 30 min');
    await expect(lines.nth(3).getByTestId('payroll-variance-line-percent')).toHaveText('+10%');
    await expect(lines.nth(4)).toHaveAttribute('data-classification', 'UNCHANGED');
    await expect(lines.nth(4)).toContainText(E1);
    await expect(lines.nth(4).getByTestId('payroll-variance-line-delta')).toHaveText('0 h 0 min');
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp091-1-review-en', review);

    // Filters: the material differences, then each classification.
    const view = review.getByTestId('payroll-variance-view');
    await view.selectOption('MATERIAL');
    await expect(lines).toHaveCount(3);
    await expect(review.getByTestId('payroll-variance-matching')).toHaveText(
      '3 employees match this filter.',
    );
    await expect(review.getByTestId('payroll-variance-total-current-employees')).toHaveText('4');
    for (const [filter, count] of [
      ['NEW', 1],
      ['MISSING', 1],
      ['CHANGED', 2],
      ['UNCHANGED', 1],
    ] as const) {
      await view.selectOption(filter);
      await expect(lines).toHaveCount(count);
    }
    await view.selectOption('ALL');
    await expect(lines).toHaveCount(5);

    // Thresholds: a refused value sends nothing; thresholds of 30 minutes and 0 % make the +30
    // minutes material too; the largest thresholds leave only the new and the missing employee.
    const absolute = review.getByTestId('payroll-variance-absolute');
    const percentage = review.getByTestId('payroll-variance-percentage');
    await absolute.fill('10081');
    await review.getByTestId('payroll-variance-apply').click();
    await expect(
      review.getByText('Enter a whole number of minutes from 0 to 10,080.'),
    ).toBeVisible();
    await expect(absolute).toHaveAttribute('aria-invalid', 'true');
    await expect(review.getByTestId('payroll-variance-total-material')).toHaveText('3');
    await accessible(admin);
    await absolute.fill('30');
    await percentage.fill('0');
    await review.getByTestId('payroll-variance-apply').click();
    await expect(review.getByTestId('payroll-variance-total-material')).toHaveText('4');
    await expect(lines.nth(3)).toHaveAttribute('data-material', 'true');
    await expect(lines.nth(4)).toHaveAttribute('data-material', 'false');
    await absolute.fill('10080');
    await percentage.fill('100');
    await review.getByTestId('payroll-variance-apply').click();
    await expect(review.getByTestId('payroll-variance-total-material')).toHaveText('2');
    await expect(lines.nth(1)).toHaveAttribute('data-material', 'true');
    await expect(lines.nth(1)).toHaveAttribute('data-classification', 'MISSING');
    await evidence(admin, 'mvp091-2-thresholds-en', review);

    // Both source runs open from the review; the comparison is the earlier one.
    await review.getByTestId('payroll-variance-open-comparison').click();
    const detail = admin.getByTestId('payroll-prep-detail');
    await expect(detail.getByTestId('payroll-prep-detail-id')).toHaveText(runPrior);
    await expect(detail.getByTestId('payroll-prep-detail-counts-employees')).toHaveText('4');
    await review.getByTestId('payroll-variance-open-current').click();
    await expect(detail.getByTestId('payroll-prep-detail-id')).toHaveText(runCurrent);
    await clean(admin, [runPrior, runCurrent, E1, E2, E3, E4, E5]);

    // The review changed nothing, and disclosed itself.
    expect(evidenceOf(), 'no run, row, week, minute total or event changed').toBe(before);
    expect(audits('payroll-variance.review')).toBeGreaterThan(reviewsBefore);
    expect(
      sql(
        `SELECT count(*) FROM platform.audit_event WHERE tenant_id = '${TENANT_A}'
         AND action = 'payroll-variance.review' AND metadata::text ~ '(${runPrior}|${runCurrent}|${E2})'`,
      ),
    ).toBe('0');
    await context.close();
  });

  test('the empty baseline and an incompatible selection are explained, then a valid comparison is reviewed, in French', async ({
    browser,
  }) => {
    test.setTimeout(300_000);
    const context = await browser.newContext({ locale: 'fr-FR' });
    const admin = await context.newPage();
    await signIn(admin, USERS.adminA, 'fr');
    await openPage(admin, 'fr');
    const before = evidenceOf();

    // The earliest run, and the six-day run (no earlier run of six days exists): nothing is
    // invented, no comparison is requested.
    for (const start of ['2026-06-01', '2026-06-08']) {
      await historyRow(admin, ENTITY_TWO_NAME, start)
        .getByTestId('payroll-prep-item-variance')
        .click();
      const empty = admin.getByTestId('payroll-variance-empty');
      await expect(empty).toHaveText(
        'Aucune génération antérieure de cette entité juridique, de même durée, ne se termine avant cette période : il n’y a encore rien à comparer.',
      );
      await expect(admin.getByTestId('payroll-variance-notice')).toHaveText(
        'Données de temps travaillé uniquement. Le salaire mensuel n’est pas calculé à partir de ces minutes. Ces écarts ne modifient pas automatiquement le salaire, les impôts, les retenues ou le paiement.',
      );
      await expect(admin.getByTestId('payroll-variance-totals')).toHaveCount(0);
      await expect(admin.getByTestId('payroll-variance-form')).toHaveCount(0);
      await accessible(admin);
      if (start === '2026-06-01') await evidence(admin, 'mvp091-3-empty-fr', admin.locator('main'));
      await admin.getByTestId('payroll-variance-close').click();
      await expect(admin.getByTestId('payroll-variance')).toHaveCount(0);
    }

    // The last run: the six-day run is not offered; the only eligible earlier run is the default.
    await historyRow(admin, ENTITY_TWO_NAME, '2026-06-15')
      .getByTestId('payroll-prep-item-variance')
      .click();
    const review = admin.getByTestId('payroll-variance');
    await expect(review.getByRole('heading', { level: 2 })).toHaveText(
      'Écarts de données de temps travaillé',
    );
    const lines = review.getByTestId('payroll-variance-line');
    await expect(lines).toHaveCount(3);
    await expect(review.getByTestId('payroll-variance-comparison').locator('option')).toHaveCount(
      1,
    );
    await expect(review.getByTestId('payroll-variance-comparison')).toHaveValue(runFirst);
    // 14 h 20 min against 15 h 0 min: −0 h 40 min; one new, one missing, one changed (not material).
    await expect(review.getByTestId('payroll-variance-total-current-minutes')).toHaveText(
      '14 h 20 min',
    );
    await expect(review.getByTestId('payroll-variance-total-comparison-minutes')).toHaveText(
      '15 h 0 min',
    );
    await expect(review.getByTestId('payroll-variance-total-delta')).toHaveText('−0 h 40 min');
    await expect(review.getByTestId('payroll-variance-total-material')).toHaveText('2');
    await expect(lines.nth(0)).toHaveAttribute('data-classification', 'MISSING');
    await expect(lines.nth(0)).toContainText(E7);
    await expect(lines.nth(0)).toContainText('Présent uniquement dans les données de comparaison');
    await expect(lines.nth(1)).toHaveAttribute('data-classification', 'NEW');
    await expect(lines.nth(1)).toContainText(E8);
    await expect(lines.nth(1)).toContainText('Présent uniquement dans les données courantes');
    await expect(lines.nth(2)).toHaveAttribute('data-classification', 'CHANGED');
    await expect(lines.nth(2)).toContainText(E6);
    await expect(lines.nth(2)).toHaveAttribute('data-material', 'false');
    await expect(lines.nth(2)).toContainText('Non important');
    await expect(lines.nth(2).getByTestId('payroll-variance-line-percent')).toContainText('10');
    await expect(review.getByTestId('payroll-variance-matching')).toHaveText(
      '3 employés correspondent à ce filtre.',
    );
    await review.getByTestId('payroll-variance-view').selectOption('MATERIAL');
    await expect(lines).toHaveCount(2);
    await expect(admin.getByTestId('payroll-variance-announcer')).toHaveText(
      '2 employés correspondent à ce filtre.',
    );
    await review.getByTestId('payroll-variance-view').selectOption('ALL');
    await expect(lines).toHaveCount(3);
    await accessible(admin);
    await noOverflow(admin);
    await evidence(admin, 'mvp091-4-review-fr', review);

    // The comparison run opens from the review.
    await review.getByTestId('payroll-variance-open-comparison').click();
    await expect(admin.getByTestId('payroll-prep-detail-id')).toHaveText(runFirst);
    await clean(admin, [runFirst, runShort, runLast, E6, E7, E8]);
    expect(evidenceOf(), 'no run, row, week, minute total or event changed').toBe(before);
    await context.close();
  });
});

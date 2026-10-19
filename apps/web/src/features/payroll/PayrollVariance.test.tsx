import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { PayrollPreparationPage } from './PayrollPreparationPage';
import {
  formatBasisPoints,
  signOf,
  thresholdProblemsOf,
  varianceFailureOf,
} from './payrollVariance';

const LEGAL = '22222222-0000-4000-8000-000000000002';
const CURRENT = '99999999-0000-4000-8000-000000000009';
const PREVIOUS = '99999999-0000-4000-8000-000000000008';
const OLDER = '99999999-0000-4000-8000-000000000007';
const EVIDENCE = 'd'.repeat(64);
const CURSOR = 'opaque-cursor-value';
const E1 = '66666666-0000-4000-8000-000000000001';
const E2 = '66666666-0000-4000-8000-000000000002';
const E3 = '66666666-0000-4000-8000-000000000003';
const E4 = '66666666-0000-4000-8000-000000000004';
const EMP = '77777777-0000-4000-8000-000000000007';

const en = resources.en.common;
const fr = resources.fr.common;

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-var-1234',
});

type Reply = { status: number; body?: unknown };
type Recorded = { method: string; path: string };

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded: Recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
      };
      requests.push(recorded);
      const reply = await route(recorded);
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
        },
      });
    }),
  );
  return requests;
}

function stored(): string {
  const values: string[] = [];
  for (const storage of [window.localStorage, window.sessionStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key) values.push(`${key}=${storage.getItem(key) ?? ''}`);
    }
  }
  return values.join('\n');
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

const scope = (start: string, end: string) => ({
  legalEntityId: LEGAL,
  legalEntityCode: 'DRC',
  legalEntityName: 'Dival RDC',
  periodStart: start,
  periodEnd: end,
  businessTimezone: 'Africa/Kinshasa',
});

const counts = { timesheets: 6, employees: 3, rows: 8, workedMinutes: 3795 };

const run = (runId: string, start: string, end: string, generatedAt: string) => ({
  runId,
  version: 1,
  status: 'GENERATED',
  schemaVersion: 1,
  scope: scope(start, end),
  weekCount: 2,
  counts,
  excluded: [
    { code: 'ROW_OUTSIDE_PERIOD', count: 0 },
    { code: 'TIMESHEET_WITHOUT_TIME', count: 0 },
  ],
  evidenceDigest: EVIDENCE,
  generatedAt,
});

const current = run(CURRENT, '2026-03-16', '2026-03-29', '2026-03-30T11:05:00Z');
const previous = run(PREVIOUS, '2026-03-02', '2026-03-15', '2026-03-16T11:05:00Z');
const older = run(OLDER, '2026-02-16', '2026-03-01', '2026-03-02T11:05:00Z');

const candidates = (list = [previous, older]) => ({
  current,
  candidates: list,
  defaultRunId: list.length ? list[0]?.runId : null,
});

const figures = (minutes: number) => ({
  employmentIds: [EMP],
  timesheets: 2,
  rows: 3,
  workedMinutes: minutes,
});

const line = (
  employeeId: string,
  classification: string,
  now: number | null,
  before: number | null,
  basisPoints: number | null,
  material: boolean,
) => ({
  employeeId,
  classification,
  current: now === null ? null : figures(now),
  comparison: before === null ? null : figures(before),
  currentMinutes: now ?? 0,
  comparisonMinutes: before ?? 0,
  minuteDelta: (now ?? 0) - (before ?? 0),
  percentageDeltaBasisPoints: basisPoints,
  material,
});

const lines = [
  line(E1, 'NEW', 480, null, null, true),
  line(E2, 'MISSING', null, 600, -10000, true),
  line(E3, 'CHANGED', 690, 600, 1500, false),
  line(E4, 'UNCHANGED', 300, 300, 0, false),
];

const totals = {
  currentEmployees: 3,
  comparisonEmployees: 3,
  currentMinutes: 1470,
  comparisonMinutes: 1500,
  minuteDelta: -30,
  newCount: 1,
  missingCount: 1,
  changedCount: 1,
  unchangedCount: 1,
  materialCount: 2,
};

const review = (
  items: unknown[],
  overrides: Record<string, unknown> = {},
  comparison = previous,
) => ({
  current,
  comparison,
  comparisonIsDefault: true,
  thresholds: { absoluteMinuteThreshold: 120, percentageThreshold: 20 },
  view: 'ALL',
  totals,
  matchingCount: items.length,
  items,
  nextCursor: null,
  ...overrides,
});

const units = (request: Recorded): Reply | null =>
  request.path.startsWith('/legal-entities')
    ? { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } }
    : null;

function query(request: Recorded): URLSearchParams {
  return new URL(request.path, 'https://x.invalid').searchParams;
}

describe('payroll variance rules in the browser', () => {
  it('validates thresholds, signs and percentages for display only, and maps the codes', () => {
    expect(thresholdProblemsOf({ absolute: '120', percentage: '20' }).size).toBe(0);
    expect(thresholdProblemsOf({ absolute: '0', percentage: '0' }).size).toBe(0);
    expect(thresholdProblemsOf({ absolute: '10080', percentage: '100' }).size).toBe(0);
    expect([...thresholdProblemsOf({ absolute: '10081', percentage: '101' })]).toEqual([
      'absoluteMinuteThreshold',
      'percentageThreshold',
    ]);
    for (const bad of ['', '-1', '1.5', 'abc', '1e3', ' 5']) {
      expect(thresholdProblemsOf({ absolute: bad, percentage: '20' }).size).toBe(1);
    }
    expect(signOf(5)).toBe('+');
    expect(signOf(-5)).toBe('−');
    expect(signOf(0)).toBe('');
    expect(formatBasisPoints('en', 2000)).toBe('+20%');
    expect(formatBasisPoints('en', -10000)).toBe('−100%');
    expect(formatBasisPoints('en', 3333)).toBe('+33.33%');
    expect(formatBasisPoints('fr', 2000).replace(/\s/gu, ' ')).toBe('+20 %');
    expect(formatBasisPoints('en', 0)).toBe('0%');
    const response = (status: number) => new Response(null, { status });
    expect(
      varianceFailureOf(response(404), problem('PAYROLL_VARIANCE_TARGET_NOT_FOUND', 404)),
    ).toMatchObject({
      kind: 'notFound',
      messageKey: 'errors.PAYROLL_VARIANCE_TARGET_NOT_FOUND',
    });
    expect(varianceFailureOf(response(400), problem('CURSOR_INVALID', 400))).toMatchObject({
      kind: 'cursor',
    });
    expect(varianceFailureOf(response(403), problem('ACCESS_DENIED', 403))).toMatchObject({
      kind: 'forbidden',
    });
    expect(varianceFailureOf(response(429), problem('RATE_LIMITED', 429))).toMatchObject({
      kind: 'rateLimited',
    });
    expect(
      varianceFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'percentageThreshold', constraint: 'RANGE' },
            { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
          ],
        }),
      ),
    ).toMatchObject({ kind: 'validation', fields: ['percentageThreshold'] });
    expect(en.payrollPreparation.variance.notice).toBe(
      'Worked-time inputs only. These differences are not pay, tax, deduction or payment differences.',
    );
    expect(fr.payrollPreparation.variance.notice).toBe(
      'Données de temps travaillé uniquement. Ces écarts ne sont pas des écarts de salaire, d’impôt, de retenue ou de paiement.',
    );
    expect(en.payrollPreparation.variance.open).toBe('Review variance');
    expect(fr.payrollPreparation.variance.open).toBe('Examiner les écarts');
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('MVP-091 payroll variance review (%s)', (locale, c) => {
  const k = c.payrollPreparation.variance;

  const render = () =>
    renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );

  it('opens from the history, defaults to the newest eligible run and filters, pages and recomputes on the server', async () => {
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [current], nextCursor: null } };
      }
      if (request.path === `/payroll-inputs/${CURRENT}/variance-candidates`) {
        return { status: 200, body: candidates() };
      }
      if (request.path.startsWith(`/payroll-inputs/${CURRENT}/variance?`)) {
        const q = query(request);
        if (q.get('comparisonRunId') === OLDER) {
          return {
            status: 200,
            body: review(lines.slice(0, 1), { comparisonIsDefault: false }, older),
          };
        }
        if (q.get('view') === 'MATERIAL') {
          return {
            status: 200,
            body: review(lines.slice(0, 2), { view: 'MATERIAL', matchingCount: 2 }),
          };
        }
        if (q.get('cursor') === CURSOR) {
          return { status: 200, body: review(lines.slice(2), { matchingCount: 4 }) };
        }
        return {
          status: 200,
          body: review(lines.slice(0, 2), { matchingCount: 4, nextCursor: CURSOR }),
        };
      }
      if (request.path === `/payroll-inputs/${CURRENT}`) {
        return {
          status: 200,
          body: { run: current, weeks: [], employees: [] },
        };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await render();

    await user.click(await screen.findByTestId('payroll-prep-item-variance'));
    expect(await screen.findByTestId('payroll-variance')).toBeVisible();
    expect(screen.getByRole('heading', { level: 2, name: k.title })).toBeVisible();
    expect(screen.getByTestId('payroll-variance-notice')).toHaveTextContent(k.notice);
    await waitFor(() => {
      expect(screen.getAllByTestId('payroll-variance-line')).toHaveLength(2);
    });
    // The default is the newest eligible run, with the documented default thresholds.
    const first = requests.find((r) => r.path.includes('/variance?'));
    expect(first).toBeDefined();
    const q = query(first as Recorded);
    expect(q.get('comparisonRunId')).toBe(PREVIOUS);
    expect(q.get('absoluteMinuteThreshold')).toBe('120');
    expect(q.get('percentageThreshold')).toBe('20');
    expect(q.get('view')).toBe('ALL');
    expect(q.get('limit')).toBe('25');
    expect(q.get('cursor')).toBeNull();
    expect(screen.getByTestId('payroll-variance-comparison')).toHaveValue(PREVIOUS);
    expect(screen.getByTestId('payroll-variance-current-period')).toHaveTextContent(/2026/u);
    expect(screen.getByTestId('payroll-variance-comparison-period')).toHaveTextContent(/2026/u);

    // The server's totals and lines are shown as given.
    expect(screen.getByTestId('payroll-variance-total-new')).toHaveTextContent('1');
    expect(screen.getByTestId('payroll-variance-total-missing')).toHaveTextContent('1');
    expect(screen.getByTestId('payroll-variance-total-material')).toHaveTextContent('2');
    expect(screen.getByTestId('payroll-variance-total-delta')).toHaveTextContent('−0 h 30 min');
    expect(screen.getByTestId('payroll-variance-matching')).toHaveTextContent(
      k.matching_other.replace('{{count}}', '4'),
    );
    const rows = screen.getAllByTestId('payroll-variance-line');
    expect(rows[0]).toHaveAttribute('data-classification', 'NEW');
    expect(within(rows[0] as HTMLElement).getByText(k.classification.NEW)).toBeVisible();
    expect(within(rows[0] as HTMLElement).getByText(E1)).toBeVisible();
    expect(
      within(rows[0] as HTMLElement).getByTestId('payroll-variance-line-delta'),
    ).toHaveTextContent('+8 h 0 min');
    expect(
      within(rows[0] as HTMLElement).getByTestId('payroll-variance-line-percent'),
    ).toHaveTextContent(k.table.noPercent);
    expect(rows[1]).toHaveAttribute('data-classification', 'MISSING');
    expect(
      within(rows[1] as HTMLElement).getByTestId('payroll-variance-line-percent'),
    ).toHaveTextContent(/100/u);
    // No name, employee number or e-mail address is ever on the page.
    expect(container.textContent).not.toMatch(/@|Kalala|employee number/iu);
    await expectAccessible(container);

    // More: the cursor is sent unchanged and the rows are appended.
    await user.click(screen.getByTestId('payroll-variance-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('payroll-variance-line')).toHaveLength(4);
    });
    const paged = requests.filter((r) => query(r).get('cursor') === CURSOR);
    expect(paged).toHaveLength(1);
    expect(query(paged[0] as Recorded).get('limit')).toBe('25');
    expect(screen.queryByTestId('payroll-variance-more')).toBeNull();
    const changed = screen.getAllByTestId('payroll-variance-line')[2] as HTMLElement;
    expect(changed).toHaveAttribute('data-material', 'false');
    expect(within(changed).getByTestId('payroll-variance-line-percent')).toHaveTextContent(/15/u);
    expect(within(changed).getByText(k.table.materialNo)).toBeVisible();

    // A filter and another comparison run restart from the first page.
    await user.selectOptions(screen.getByTestId('payroll-variance-view'), 'MATERIAL');
    await waitFor(() => {
      expect(screen.getAllByTestId('payroll-variance-line')).toHaveLength(2);
    });
    const filtered = requests.filter((r) => query(r).get('view') === 'MATERIAL');
    expect(filtered).toHaveLength(1);
    expect(query(filtered[0] as Recorded).get('cursor')).toBeNull();
    await user.selectOptions(screen.getByTestId('payroll-variance-comparison'), OLDER);
    await waitFor(() => {
      expect(screen.getAllByTestId('payroll-variance-line')).toHaveLength(1);
    });
    expect(requests.some((r) => query(r).get('comparisonRunId') === OLDER)).toBe(true);

    // The source runs open through the existing detail.
    await user.click(screen.getByTestId('payroll-variance-open-current'));
    expect(await screen.findByTestId('payroll-prep-detail')).toBeVisible();

    // Nothing of the review is kept in browser storage (only the language choice) or the address.
    for (const secret of [CURRENT, PREVIOUS, OLDER, E1, E2, E3, CURSOR, 'threshold']) {
      expect(stored()).not.toContain(secret);
    }
    expect(window.location.href).not.toContain(CURRENT);
    expect(window.location.href).not.toContain(PREVIOUS);
    expect(window.location.href).not.toContain(CURSOR);
  });

  it('validates the thresholds before any request, then sends the new ones and asks the server again', async () => {
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [current], nextCursor: null } };
      }
      if (request.path.endsWith('/variance-candidates')) return { status: 200, body: candidates() };
      if (request.path.includes('/variance?')) {
        const q = query(request);
        return {
          status: 200,
          body: review(lines, {
            thresholds: {
              absoluteMinuteThreshold: Number(q.get('absoluteMinuteThreshold')),
              percentageThreshold: Number(q.get('percentageThreshold')),
            },
          }),
        };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await render();
    await user.click(await screen.findByTestId('payroll-prep-item-variance'));
    await screen.findAllByTestId('payroll-variance-line');
    const before = requests.length;

    const absolute = screen.getByTestId('payroll-variance-absolute');
    await user.clear(absolute);
    await user.type(absolute, '10081');
    await user.click(screen.getByTestId('payroll-variance-apply'));
    expect(await screen.findByText(k.thresholds.problem.absoluteMinuteThreshold)).toBeVisible();
    expect(absolute).toHaveAttribute('aria-invalid', 'true');
    expect(requests).toHaveLength(before);
    await expectAccessible(container);

    await user.clear(absolute);
    await user.type(absolute, '0');
    const percentage = screen.getByTestId('payroll-variance-percentage');
    await user.clear(percentage);
    await user.type(percentage, '101');
    await user.click(screen.getByTestId('payroll-variance-apply'));
    expect(await screen.findByText(k.thresholds.problem.percentageThreshold)).toBeVisible();
    expect(requests).toHaveLength(before);

    await user.clear(percentage);
    await user.type(percentage, '0');
    await user.click(screen.getByTestId('payroll-variance-apply'));
    await waitFor(() => {
      expect(requests.length).toBeGreaterThan(before);
    });
    const sent = query(requests[requests.length - 1] as Recorded);
    expect(sent.get('absoluteMinuteThreshold')).toBe('0');
    expect(sent.get('percentageThreshold')).toBe('0');
    expect(screen.queryByText(k.thresholds.problem.absoluteMinuteThreshold)).toBeNull();
    // A zero threshold is accepted; the thresholds and runs are never stored.
    expect(stored()).not.toContain(PREVIOUS);
    expect(stored()).not.toMatch(/threshold|absolute|percentage/iu);
  });

  it('shows the empty state when no earlier run is eligible and never invents a baseline', async () => {
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [current], nextCursor: null } };
      }
      if (request.path.endsWith('/variance-candidates')) {
        return { status: 200, body: candidates([]) };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await render();
    await user.click(await screen.findByTestId('payroll-prep-item-variance'));
    expect(await screen.findByTestId('payroll-variance-empty')).toHaveTextContent(k.empty);
    expect(screen.getByTestId('payroll-variance-notice')).toHaveTextContent(k.notice);
    expect(screen.queryByTestId('payroll-variance-form')).toBeNull();
    expect(screen.queryByTestId('payroll-variance-totals')).toBeNull();
    expect(requests.some((r) => r.path.includes('/variance?'))).toBe(false);
    await expectAccessible(container);
    await user.click(screen.getByTestId('payroll-variance-close'));
    expect(screen.queryByTestId('payroll-variance')).toBeNull();
  });

  it('says so, with the reference only, when the run is not found, rate limited or unreachable', async () => {
    let mode: 'notFound' | 'rate' | 'network' = 'notFound';
    stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [current], nextCursor: null } };
      }
      if (request.path.endsWith('/variance-candidates')) {
        if (mode === 'network') throw new TypeError('offline');
        return mode === 'rate'
          ? { status: 429, body: problem('RATE_LIMITED', 429) }
          : { status: 404, body: problem('PAYROLL_VARIANCE_TARGET_NOT_FOUND', 404) };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await render();
    await user.click(await screen.findByTestId('payroll-prep-item-variance'));
    expect(await screen.findByTestId('payroll-variance-error')).toHaveTextContent(
      c.errors.PAYROLL_VARIANCE_TARGET_NOT_FOUND,
    );
    expect(screen.getByTestId('payroll-variance-error')).toHaveTextContent('corr-var-1234');
    expect(container.textContent).not.toContain(CURRENT);
    await expectAccessible(container);

    mode = 'rate';
    await user.click(screen.getByTestId('payroll-variance-close'));
    await user.click(screen.getByTestId('payroll-prep-item-variance'));
    expect(await screen.findByTestId('payroll-variance-error')).toHaveTextContent(
      c.errors.RATE_LIMITED,
    );

    mode = 'network';
    await user.click(screen.getByTestId('payroll-variance-close'));
    await user.click(screen.getByTestId('payroll-prep-item-variance'));
    expect(await screen.findByTestId('payroll-variance-error')).toHaveTextContent(c.errors.network);
  });
});

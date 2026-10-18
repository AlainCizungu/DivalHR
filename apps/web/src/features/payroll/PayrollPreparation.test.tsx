import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { PayrollPreparationPage } from './PayrollPreparationPage';
import { payrollFailureOf, periodProblemsOf } from './payrollInputs';

const LEGAL = '22222222-0000-4000-8000-000000000002';
const SITE = '33333333-0000-4000-8000-000000000003';
const GENERATION = '44444444-0000-4000-8000-000000000004';
const LOCK = '55555555-0000-4000-8000-000000000005';
const RUN = '99999999-0000-4000-8000-000000000009';
const EMPLOYEE = '66666666-0000-4000-8000-000000000006';
const EMPLOYMENT = '77777777-0000-4000-8000-000000000007';
const DIGEST = 'b'.repeat(64);
const VERSIONS = 'c'.repeat(64);
const EVIDENCE = 'd'.repeat(64);
const START = '2026-03-02';
const END = '2026-03-15';

const en = resources.en.common;
const fr = resources.fr.common;

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-pay-1234',
});

type Reply = { status: number; body?: unknown };
type Recorded = {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
  key: string | null;
};

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const text = incoming.method === 'GET' ? '' : await incoming.text();
      const recorded: Recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: text ? (JSON.parse(text) as Record<string, unknown>) : null,
        key: incoming.headers.get('Idempotency-Key'),
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

const scope = {
  legalEntityId: LEGAL,
  legalEntityCode: 'DRC',
  legalEntityName: 'Dival RDC',
  periodStart: START,
  periodEnd: END,
  businessTimezone: 'Africa/Kinshasa',
};

const counts = { timesheets: 6, employees: 3, rows: 8, workedMinutes: 3795 };

const week = (weekStart: string, status: string, overrides: Record<string, unknown> = {}) => ({
  siteId: SITE,
  siteCode: 'KIN',
  siteName: 'Kinshasa Gombe',
  weekStart,
  timezone: 'Africa/Kinshasa',
  status,
  generationId: status === 'NOT_GENERATED' ? null : GENERATION,
  generationVersion: status === 'NOT_GENERATED' ? null : 1,
  lockActionId: status === 'LOCKED' ? LOCK : null,
  lockVersion: status === 'LOCKED' ? 1 : null,
  counts:
    status === 'LOCKED' ? { timesheets: 3, employees: 3, rows: 4, workedMinutes: 1900 } : null,
  ...overrides,
});

const ready = (overrides: Record<string, unknown> = {}) => ({
  scope,
  weeks: [week('2026-03-02', 'LOCKED'), week('2026-03-09', 'LOCKED')],
  eligible: counts,
  excluded: [
    { code: 'ROW_OUTSIDE_PERIOD', count: 0 },
    { code: 'TIMESHEET_WITHOUT_TIME', count: 0 },
  ],
  blockers: [],
  ready: true,
  sourceVersionsDigest: VERSIONS,
  previewDigest: DIGEST,
  expiresAt: '2026-03-16T11:30:00Z',
  existingRunId: null,
  existingRunUnchanged: null,
  ...overrides,
});

const blocked = () =>
  ready({
    weeks: [
      week('2026-03-02', 'LOCKED'),
      week('2026-03-09', 'NOT_LOCKED'),
      week('2026-03-16', 'NOT_GENERATED'),
    ],
    eligible: { timesheets: 3, employees: 3, rows: 4, workedMinutes: 1900 },
    excluded: [
      { code: 'ROW_OUTSIDE_PERIOD', count: 2 },
      { code: 'TIMESHEET_WITHOUT_TIME', count: 1 },
    ],
    blockers: [
      { code: 'WEEK_NOT_GENERATED', count: 1 },
      { code: 'WEEK_NOT_LOCKED', count: 1 },
    ],
    ready: false,
  });

const run = (generatedAt = '2026-03-16T11:05:00Z') => ({
  runId: RUN,
  version: 1,
  status: 'GENERATED',
  schemaVersion: 1,
  scope,
  weekCount: 2,
  counts,
  excluded: [
    { code: 'ROW_OUTSIDE_PERIOD', count: 0 },
    { code: 'TIMESHEET_WITHOUT_TIME', count: 0 },
  ],
  evidenceDigest: EVIDENCE,
  generatedAt,
});

const detail = () => ({
  run: run(),
  weeks: [
    {
      siteId: SITE,
      siteCode: 'KIN',
      siteName: 'Kinshasa Gombe',
      weekStart: '2026-03-02',
      timezone: 'Africa/Kinshasa',
      generationId: GENERATION,
      generationVersion: 1,
      lockActionId: LOCK,
      lockVersion: 1,
      lockEvidenceDigest: EVIDENCE,
      counts: { timesheets: 3, employees: 3, rows: 4, workedMinutes: 1900 },
    },
    {
      siteId: SITE,
      siteCode: 'KIN',
      siteName: 'Kinshasa Gombe',
      weekStart: '2026-03-09',
      timezone: 'Africa/Kinshasa',
      generationId: '44444444-0000-4000-8000-000000000005',
      generationVersion: 2,
      lockActionId: '55555555-0000-4000-8000-000000000006',
      lockVersion: 3,
      lockEvidenceDigest: 'e'.repeat(64),
      counts: { timesheets: 3, employees: 3, rows: 4, workedMinutes: 1895 },
    },
  ],
  employees: [
    {
      employeeId: EMPLOYEE,
      employmentId: EMPLOYMENT,
      firstWorkDate: '2026-03-02',
      lastWorkDate: '2026-03-10',
      counts: { timesheets: 2, employees: 1, rows: 4, workedMinutes: 1900 },
    },
  ],
});

const units = (request: Recorded): Reply | null => {
  if (request.path.startsWith('/legal-entities')) {
    return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
  }
  return null;
};

async function chooseScope(user: ReturnType<typeof userEvent.setup>) {
  const legal = screen.getByTestId('payroll-prep-legal-entity');
  await waitFor(() => {
    expect(within(legal).getAllByRole('option')).toHaveLength(2);
  });
  await user.selectOptions(legal, LEGAL);
  const start = screen.getByTestId('payroll-prep-period-start');
  await user.clear(start);
  await user.type(start, START);
  const end = screen.getByTestId('payroll-prep-period-end');
  await user.clear(end);
  await user.type(end, END);
}

describe('payroll-input rules in the browser', () => {
  it('maps the payroll codes, the refusal and the field errors, and keeps the fixed statements', () => {
    const response = (status: number) => new Response(null, { status });
    expect(
      payrollFailureOf(response(409), problem('PAYROLL_INPUT_PREVIEW_STALE', 409)),
    ).toMatchObject({ kind: 'stale', messageKey: 'errors.PAYROLL_INPUT_PREVIEW_STALE' });
    expect(payrollFailureOf(response(409), problem('PAYROLL_INPUT_NOT_READY', 409))).toMatchObject({
      kind: 'notReady',
    });
    expect(payrollFailureOf(response(409), problem('PAYROLL_INPUT_RUN_EXISTS', 409))).toMatchObject(
      { kind: 'exists' },
    );
    expect(
      payrollFailureOf(response(404), problem('PAYROLL_INPUT_TARGET_NOT_FOUND', 404)),
    ).toMatchObject({ kind: 'notFound' });
    expect(payrollFailureOf(response(403), problem('ACCESS_DENIED', 403))).toMatchObject({
      kind: 'forbidden',
      messageKey: 'payrollPreparation.unauthorized',
    });
    expect(
      payrollFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'periodEnd', constraint: 'RANGE' },
            { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
          ],
        }),
      ),
    ).toMatchObject({ kind: 'validation', fields: ['periodEnd'] });
    expect(periodProblemsOf({ legalEntityId: '', periodStart: START, periodEnd: END })).toEqual(
      new Map([['legalEntityId', 'required']]),
    );
    expect(
      periodProblemsOf({ legalEntityId: LEGAL, periodStart: END, periodEnd: START }).get(
        'periodEnd',
      ),
    ).toBe('order');
    expect(
      periodProblemsOf({ legalEntityId: LEGAL, periodStart: START, periodEnd: '2026-04-02' }).get(
        'periodEnd',
      ),
    ).toBe('length');
    expect(
      periodProblemsOf({ legalEntityId: LEGAL, periodStart: START, periodEnd: '2026-04-01' }).size,
    ).toBe(0);
    expect(en.payrollPreparation.notice).toBe(
      'Payroll inputs only. DivalHR has not calculated gross pay, deductions, tax, net pay or payment.',
    );
    expect(fr.payrollPreparation.notice).toBe(
      'Données de préparation uniquement. DivalHR n’a calculé ni salaire brut, ni retenues, ni impôts, ni salaire net, ni paiement.',
    );
    expect(en.payrollPreparation.title).toBe('Payroll preparation');
    expect(fr.payrollPreparation.title).toBe('Préparation de la paie');
    expect(en.nav.groups.payroll).toBe('Payroll');
    expect(fr.nav.groups.payroll).toBe('Paie');
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('MVP-090A payroll preparation (%s)', (locale, c) => {
  const k = c.payrollPreparation;

  it('previews a ready period, generates after confirming, shows the run and the history', async () => {
    let generated = false;
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: generated ? [run()] : [], nextCursor: null } };
      }
      if (request.path === '/payroll-inputs/preview') return { status: 200, body: ready() };
      if (request.path === '/payroll-inputs' && request.method === 'POST') {
        generated = true;
        return { status: 200, body: { outcome: 'GENERATED', run: run() } };
      }
      if (request.path === `/payroll-inputs/${RUN}`) return { status: 200, body: detail() };
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );
    expect(screen.getByRole('heading', { level: 1, name: k.title })).toBeVisible();
    expect(screen.getByTestId('payroll-prep-notice')).toHaveTextContent(k.notice);
    expect(await screen.findByTestId('payroll-prep-history-empty')).toHaveTextContent(
      k.history.empty,
    );
    await expectAccessible(container);

    // A missing legal entity is refused before any request.
    await user.click(screen.getByTestId('payroll-prep-preview-button'));
    expect(await screen.findByText(k.form.problem.legalEntityId.required)).toBeVisible();
    expect(requests.filter((r) => r.path.startsWith('/payroll-inputs/'))).toHaveLength(0);

    await chooseScope(user);
    await user.click(screen.getByTestId('payroll-prep-preview-button'));
    const shown = await screen.findByTestId('payroll-prep-preview');
    expect(requests.find((r) => r.path === '/payroll-inputs/preview')?.body).toEqual({
      legalEntityId: LEGAL,
      periodStart: START,
      periodEnd: END,
    });
    expect(shown).toHaveAttribute('data-ready', 'true');
    expect(within(shown).getAllByTestId('payroll-prep-week-row')).toHaveLength(2);
    expect(within(shown).getAllByTestId('payroll-prep-week-row')[0]).toHaveAttribute(
      'data-status',
      'LOCKED',
    );
    expect(within(shown).getAllByText(k.weekStatus.LOCKED)).toHaveLength(2);
    expect(within(shown).getByTestId('payroll-prep-eligible-employees')).toHaveTextContent('3');
    expect(within(shown).getByTestId('payroll-prep-eligible-rows')).toHaveTextContent('8');
    expect(within(shown).getByTestId('payroll-prep-eligible-worked')).toHaveTextContent(
      '63 h 15 min',
    );
    expect(within(shown).getByTestId('payroll-prep-excluded-none')).toHaveTextContent(
      k.excluded.none,
    );
    expect(within(shown).getByTestId('payroll-prep-ready')).toHaveTextContent(k.preview.ready);
    expect(within(shown).getByTestId('payroll-prep-scope')).toHaveTextContent('Dival RDC');
    await waitFor(() => {
      expect(within(shown).getByRole('heading', { level: 2, name: k.preview.title })).toHaveFocus();
    });
    const generate = within(shown).getByRole('button', { name: k.preview.generate });
    expect(generate).toBeDisabled();
    await expectAccessible(container);
    await user.click(within(shown).getByLabelText(k.preview.confirmCheck));
    expect(generate).toBeEnabled();
    await user.click(generate);

    const result = await screen.findByTestId('payroll-prep-detail');
    const command = requests.find((r) => r.path === '/payroll-inputs' && r.method === 'POST');
    expect(command?.body).toEqual({
      legalEntityId: LEGAL,
      periodStart: START,
      periodEnd: END,
      previewDigest: DIGEST,
      sourceVersionsDigest: VERSIONS,
      confirm: true,
    });
    expect(command?.key).toMatch(/^web-[0-9a-f-]{36}$/u);
    expect(screen.getByTestId('payroll-prep-announcer')).toHaveTextContent(k.generated);
    expect(screen.queryByTestId('payroll-prep-preview')).toBeNull();
    expect(within(result).getByTestId('payroll-prep-detail-id')).toHaveTextContent(RUN);
    expect(within(result).getByTestId('payroll-prep-detail-weeks')).toHaveTextContent('2');
    expect(within(result).getByTestId('payroll-prep-detail-digest')).toHaveTextContent(EVIDENCE);
    expect(within(result).getByTestId('payroll-prep-detail-counts-worked')).toHaveTextContent(
      '63 h 15 min',
    );
    expect(within(result).getAllByTestId('payroll-prep-detail-week-row')).toHaveLength(2);
    expect(within(result).getAllByTestId('payroll-prep-employee')).toHaveLength(1);
    expect(within(result).getByTestId('payroll-prep-employee-worked')).toHaveTextContent(
      '31 h 40 min',
    );
    expect(within(result).getByText(k.status.GENERATED)).toBeVisible();
    expect(within(result).getByText(k.detail.immutable)).toBeVisible();
    await waitFor(() => {
      expect(within(result).getByRole('heading', { level: 2, name: k.detail.title })).toHaveFocus();
    });
    const history = screen.getByTestId('payroll-prep-history');
    expect(within(history).getAllByTestId('payroll-prep-item')).toHaveLength(1);
    expect(within(history).getByTestId('payroll-prep-item-employees')).toHaveTextContent('3');
    await expectAccessible(container);
    // Nothing of the run in browser storage or the URL; no name anywhere (the API gives none).
    expect(stored()).not.toMatch(
      new RegExp(`${RUN}|${EVIDENCE}|${DIGEST}|${LEGAL}|${EMPLOYEE}`, 'u'),
    );
    expect(window.location.search + window.location.hash).toBe('');
    expect(container).not.toHaveTextContent('Kalala');
  });

  it('shows the blockers of a period that is not ready and offers no generation', async () => {
    stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [], nextCursor: null } };
      }
      if (request.path === '/payroll-inputs/preview') return { status: 200, body: blocked() };
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );
    await screen.findByTestId('payroll-prep-history-empty');
    await chooseScope(user);
    await user.click(screen.getByTestId('payroll-prep-preview-button'));
    const shown = await screen.findByTestId('payroll-prep-preview');
    expect(shown).toHaveAttribute('data-ready', 'false');
    const rows = within(shown).getAllByTestId('payroll-prep-week-row');
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual([
      'LOCKED',
      'NOT_LOCKED',
      'NOT_GENERATED',
    ]);
    expect(within(shown).getByText(k.weekStatus.NOT_LOCKED)).toBeVisible();
    expect(within(shown).getByText(k.weekStatus.NOT_GENERATED)).toBeVisible();
    const blockers = within(shown).getByTestId('payroll-prep-blocked');
    expect(within(blockers).getByRole('heading', { level: 3 })).toHaveTextContent(
      k.preview.blocked,
    );
    expect(
      within(blockers).getByTestId('payroll-prep-blocker-WEEK_NOT_GENERATED'),
    ).toHaveTextContent(k.blockers.WEEK_NOT_GENERATED_one.replace('{{count}}', '1'));
    expect(within(blockers).getByTestId('payroll-prep-blocker-WEEK_NOT_LOCKED')).toHaveTextContent(
      k.blockers.WEEK_NOT_LOCKED_one.replace('{{count}}', '1'),
    );
    expect(within(shown).getByTestId('payroll-prep-excluded-ROW_OUTSIDE_PERIOD')).toHaveTextContent(
      k.excluded.ROW_OUTSIDE_PERIOD_other.replace('{{count}}', '2'),
    );
    expect(
      within(shown).getByTestId('payroll-prep-excluded-TIMESHEET_WITHOUT_TIME'),
    ).toHaveTextContent(k.excluded.TIMESHEET_WITHOUT_TIME_one.replace('{{count}}', '1'));
    expect(within(shown).queryByRole('button', { name: k.preview.generate })).toBeNull();
    expect(within(shown).queryByLabelText(k.preview.confirmCheck)).toBeNull();
    await expectAccessible(container);
  });

  it('turns a stale, an existing and a validation refusal into stable messages and drops the preview', async () => {
    let attempt = 0;
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [run()], nextCursor: null } };
      }
      if (request.path === '/payroll-inputs/preview') {
        attempt += 1;
        if (attempt === 3) {
          return {
            status: 400,
            body: problem('VALIDATION_FAILED', 400, {
              fields: [{ field: 'periodEnd', constraint: 'RANGE' }],
            }),
          };
        }
        return {
          status: 200,
          body: ready({ existingRunId: RUN, existingRunUnchanged: attempt === 1 ? false : true }),
        };
      }
      if (request.path === '/payroll-inputs' && request.method === 'POST') {
        return attempt === 1
          ? { status: 409, body: problem('PAYROLL_INPUT_RUN_EXISTS', 409) }
          : { status: 409, body: problem('PAYROLL_INPUT_PREVIEW_STALE', 409) };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );
    expect(await screen.findAllByTestId('payroll-prep-item')).toHaveLength(1);
    await chooseScope(user);
    await user.click(screen.getByTestId('payroll-prep-preview-button'));
    let shown = await screen.findByTestId('payroll-prep-preview');
    expect(within(shown).getByTestId('payroll-prep-existing')).toHaveTextContent(
      k.preview.existingChanged,
    );
    await user.click(within(shown).getByLabelText(k.preview.confirmCheck));
    await user.click(within(shown).getByRole('button', { name: k.preview.generate }));
    const refused = await screen.findByTestId('payroll-prep-error');
    expect(refused).toHaveTextContent(c.errors.PAYROLL_INPUT_RUN_EXISTS);
    expect(refused).toHaveTextContent('corr-pay-1234');
    expect(screen.queryByTestId('payroll-prep-preview')).toBeNull();

    // A second preview names the run as unchanged; the generation is stale: the preview is dropped
    // and the key is not reused across previews.
    await user.click(screen.getByTestId('payroll-prep-preview-button'));
    shown = await screen.findByTestId('payroll-prep-preview');
    expect(within(shown).getByTestId('payroll-prep-existing')).toHaveTextContent(
      k.preview.existingUnchanged,
    );
    await user.click(within(shown).getByLabelText(k.preview.confirmCheck));
    await user.click(within(shown).getByRole('button', { name: k.preview.generate }));
    expect(await screen.findByTestId('payroll-prep-error')).toHaveTextContent(
      c.errors.PAYROLL_INPUT_PREVIEW_STALE,
    );
    expect(screen.queryByTestId('payroll-prep-preview')).toBeNull();
    const keys = requests
      .filter((r) => r.path === '/payroll-inputs' && r.method === 'POST')
      .map((r) => r.key);
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(2);

    // A server-side period refusal lands on the field.
    await user.click(screen.getByTestId('payroll-prep-preview-button'));
    expect(await screen.findByText(k.form.problem.periodEnd.server)).toBeVisible();
    expect(screen.getByTestId('payroll-prep-period-end')).toHaveAttribute('aria-invalid', 'true');
    await expectAccessible(container);
    expect(stored()).not.toMatch(new RegExp(`${RUN}|${DIGEST}|${LEGAL}`, 'u'));
  });
});

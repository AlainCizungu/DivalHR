import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MyTimesheetsPage } from './MyTimesheetsPage';
import { TimesheetsPage } from './TimesheetsPage';
import {
  currentMonday,
  durationParts,
  mondayOf,
  timesheetFailureOf,
  weekProblemsOf,
} from './timesheets';

const LEGAL = '22222222-0000-4000-8000-000000000002';
const SITE = '33333333-0000-4000-8000-000000000003';
const GENERATION = '44444444-0000-4000-8000-000000000004';
const SHEET = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const SLOT_2 = '77777777-0000-4000-8000-000000000007';
const RECORD = '88888888-0000-4000-8000-000000000008';
const ANA = '11111111-0000-4000-8000-000000000001';
const DIGEST = 'b'.repeat(64);
const MONDAY = '2099-11-02';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-ts-1234',
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

const period = {
  siteId: SITE,
  siteCode: 'KIN',
  siteName: 'Kinshasa Gombe',
  timezone: 'Africa/Kinshasa',
  weekStart: MONDAY,
  weekEnd: '2099-11-09',
  lastDay: '2099-11-08',
  periodStartAt: '2099-11-01T23:00:00Z',
  periodEndAt: '2099-11-08T23:00:00Z',
};

const totals = (overrides: Record<string, number> = {}) => ({
  rows: 2,
  readyRows: 1,
  missingRows: 1,
  openRows: 0,
  changedRows: 0,
  blockedRows: 1,
  workedMinutes: 479,
  ...overrides,
});

const summary = (overrides: Record<string, unknown> = {}) => ({
  generationId: GENERATION,
  period,
  status: 'DRAFT',
  version: 1,
  generatedAt: '2099-11-08T11:00:00Z',
  refreshedAt: null,
  drafts: 1,
  totals: totals(),
  ...overrides,
});

const ANA_PERSON = { id: ANA, employeeNumber: 'E-0042', givenNames: 'Ana', familyName: 'Kalala' };

const generationView = (overrides: Record<string, unknown> = {}) => ({
  generation: summary(overrides),
  timesheets: [
    {
      timesheetId: SHEET,
      employee: ANA_PERSON,
      status: 'DRAFT',
      revision: (overrides.version as number | undefined) ?? 1,
      totals: (overrides.totals as ReturnType<typeof totals> | undefined) ?? totals(),
      updatedAt: '2099-11-08T11:00:00Z',
    },
  ],
});

const readyRow = {
  slotId: SLOT,
  scheduleId: '99999999-0000-4000-8000-000000000009',
  employmentId: '12121212-0000-4000-8000-000000000012',
  siteId: SITE,
  costCenter: { id: '13131313-0000-4000-8000-000000000013', code: 'CC-OPS', name: 'Opérations' },
  labels: { en: 'Cashier', fr: 'Caissière' },
  scheduledStartAt: '2099-11-02T07:00:00Z',
  scheduledEndAt: '2099-11-02T15:00:00Z',
  state: 'READY',
  attendanceRecordId: RECORD,
  checkedInAt: '2099-11-02T07:00:10Z',
  checkedOutAt: '2099-11-02T14:59:40Z',
  correctionVersion: 1,
  corrected: true,
  workedMinutes: 479,
};

const missingRow = {
  ...readyRow,
  slotId: SLOT_2,
  costCenter: null,
  scheduledStartAt: '2099-11-03T07:00:00Z',
  scheduledEndAt: '2099-11-03T15:00:00Z',
  state: 'MISSING_ATTENDANCE',
  attendanceRecordId: null,
  checkedInAt: null,
  checkedOutAt: null,
  correctionVersion: null,
  corrected: false,
  workedMinutes: null,
};

const detail = {
  timesheetId: SHEET,
  generationId: GENERATION,
  employee: ANA_PERSON,
  period,
  status: 'DRAFT',
  revision: 1,
  totals: totals(),
  rows: [readyRow, missingRow],
  revisions: [
    { revision: 1, generationVersion: 1, createdAt: '2099-11-08T11:00:00Z', totals: totals() },
  ],
};

const units = (request: Recorded): Reply | null => {
  if (request.path.startsWith('/legal-entities')) {
    return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
  }
  if (request.path.startsWith('/sites')) {
    return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
  }
  return null;
};

async function chooseWeek(user: ReturnType<typeof userEvent.setup>, k: typeof en.timesheets) {
  const legal = screen.getByLabelText(k.form.legalEntity);
  await waitFor(() => {
    expect(within(legal).getAllByRole('option')).toHaveLength(2);
  });
  await user.selectOptions(legal, LEGAL);
  const site = screen.getByLabelText(k.form.site);
  await waitFor(() => {
    expect(within(site).getAllByRole('option')).toHaveLength(2);
  });
  await user.selectOptions(site, SITE);
  const week = screen.getByLabelText(k.form.week);
  await user.clear(week);
  await user.type(week, MONDAY);
}

const en = resources.en.common;
const fr = resources.fr.common;

describe('timesheet helpers', () => {
  it('finds the Monday of a week, checks the form and splits whole minutes', () => {
    expect(mondayOf('2099-11-02')).toBe('2099-11-02');
    expect(mondayOf('2099-11-08')).toBe('2099-11-02');
    expect(mondayOf('2026-03-01')).toBe('2026-02-23');
    expect(currentMonday(new Date(2026, 9, 14, 23, 30))).toBe('2026-10-12');
    expect([...weekProblemsOf({ siteId: '', weekStart: '' })]).toEqual([
      ['siteId', 'siteRequired'],
      ['weekStart', 'weekRequired'],
    ]);
    expect([...weekProblemsOf({ siteId: SITE, weekStart: '2099-11-03' })]).toEqual([
      ['weekStart', 'notMonday'],
    ]);
    expect(weekProblemsOf({ siteId: SITE, weekStart: MONDAY }).size).toBe(0);
    expect(durationParts(479)).toEqual({ hours: 7, minutes: 59 });
    expect(durationParts(0)).toEqual({ hours: 0, minutes: 0 });
  });

  it('maps problems to stable codes and allow-listed fields only', () => {
    const response = (status: number) => new Response(null, { status });
    expect(
      timesheetFailureOf('admin', response(409), problem('TIMESHEET_REFRESH_PREVIEW_STALE', 409)),
    ).toMatchObject({ kind: 'stale', messageKey: 'errors.TIMESHEET_REFRESH_PREVIEW_STALE' });
    expect(
      timesheetFailureOf(
        'admin',
        response(422),
        problem('TIMESHEET_GENERATION_LIMIT_EXCEEDED', 422),
      ),
    ).toMatchObject({ kind: 'limit' });
    expect(
      timesheetFailureOf(
        'admin',
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [{ field: 'weekStart' }, { field: 'employeeId' }, { field: '<script>' }],
        }),
      ),
    ).toMatchObject({ kind: 'validation', fields: ['weekStart'] });
    expect(timesheetFailureOf('self', response(403), problem('ACCESS_DENIED', 403))).toMatchObject({
      kind: 'forbidden',
      messageKey: 'myTimesheets.unauthorized',
    });
    expect(
      timesheetFailureOf('self', response(403), problem('EMPLOYEE_LINK_REQUIRED', 403)),
    ).toMatchObject({ kind: 'linkRequired' });
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('Timesheets (%s)', (locale, c) => {
  const k = c.timesheets;

  it('previews and generates a week after confirmation, then opens a draft', async () => {
    let generated = false;
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path === '/timesheets/generation-preview') {
        return {
          status: 200,
          body: {
            period,
            generation: null,
            drafts: 1,
            totals: totals(),
            maxDrafts: 500,
            maxRows: 5000,
          },
        };
      }
      if (request.path === '/timesheets/generations') {
        generated = true;
        return { status: 200, body: { outcome: 'CREATED', generation: summary() } };
      }
      if (request.path === `/timesheets/generations/${GENERATION}`) {
        return { status: 200, body: generationView() };
      }
      if (request.path === `/timesheets/${SHEET}`) return { status: 200, body: detail };
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimesheetsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/timesheets',
    );
    expect(screen.getByRole('heading', { level: 1, name: k.title })).toBeVisible();
    expect(screen.getByTestId('timesheets-scope')).toHaveTextContent(k.scope);
    await expectAccessible(container);

    // A non-Monday is refused before any request.
    await chooseWeek(user, k);
    const week = screen.getByLabelText(k.form.week);
    await user.clear(week);
    await user.type(week, '2099-11-03');
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    expect(await screen.findByText(k.form.problem.notMonday)).toBeVisible();
    expect(requests.filter((r) => r.path.startsWith('/timesheets'))).toHaveLength(0);
    await user.clear(week);
    await user.type(week, MONDAY);
    await user.click(screen.getByRole('button', { name: k.form.preview }));

    const preview = await screen.findByTestId('timesheets-generation-preview');
    expect(within(preview).getByTestId('timesheets-preview-drafts')).toHaveTextContent('1');
    expect(within(preview).getByTestId('timesheets-preview-totals-worked')).toHaveTextContent(
      '7 h 59 min',
    );
    expect(within(preview).getByTestId('timesheet-period')).toHaveTextContent('Kinshasa Gombe');
    expect(within(preview).getByTestId('timesheets-preview-totals-blocked')).toHaveTextContent(
      locale === 'fr' ? 'Présence manquante : 1' : 'Missing attendance: 1',
    );
    const generate = within(preview).getByRole('button', { name: k.preview.generate });
    expect(generate).toBeDisabled();
    await expectAccessible(container);
    await user.click(within(preview).getByLabelText(k.preview.confirmCheck));
    await user.click(generate);

    const drafts = await screen.findByTestId('timesheets-generation');
    expect(generated).toBe(true);
    const command = requests.find((r) => r.path === '/timesheets/generations');
    expect(command?.body).toEqual({ siteId: SITE, weekStart: MONDAY, confirm: true });
    expect(command?.key).toMatch(/^web-/u);
    expect(screen.getByTestId('timesheets-announcer')).toHaveTextContent(
      locale === 'fr' ? '1 brouillon généré.' : '1 draft generated.',
    );
    expect(within(drafts).getByRole('rowheader', { name: 'Ana Kalala (E-0042)' })).toBeVisible();
    await expectAccessible(container);

    await user.click(within(drafts).getByTestId('timesheets-draft-view'));
    const opened = await screen.findByTestId('timesheets-detail');
    const rows = within(opened).getAllByTestId('timesheet-row');
    expect(rows.map((row) => row.dataset.state)).toEqual(['READY', 'MISSING_ATTENDANCE']);
    expect(within(rows[0]!).getByTestId('timesheet-row-state')).toHaveTextContent(k.state.READY);
    expect(within(rows[0]!).getByTestId('timesheet-row-corrected')).toHaveTextContent(
      locale === 'fr' ? 'Corrigé (version 1)' : 'Corrected (version 1)',
    );
    expect(within(rows[0]!).getByTestId('timesheet-row-worked')).toHaveTextContent('7 h 59 min');
    expect(within(rows[0]!).getByTestId('timesheet-row-cost-center')).toHaveTextContent(
      'Opérations (CC-OPS)',
    );
    expect(within(rows[0]!).getByText(locale === 'fr' ? 'Caissière' : 'Cashier')).toBeVisible();
    expect(within(rows[1]!).getByTestId('timesheet-row-state')).toHaveTextContent(
      k.state.MISSING_ATTENDANCE,
    );
    expect(within(rows[1]!).getByTestId('timesheet-row-worked')).toHaveTextContent('—');
    expect(within(opened).getAllByTestId('timesheets-revision')).toHaveLength(1);
    await expectAccessible(container);
    expect(stored()).not.toMatch(/Kalala|Cashier|Caissi|E-0042|55555555|2099-11/u);
  });

  it('opens an existing week and refreshes only with the exact fresh preview', async () => {
    let version = 1;
    let stale = true;
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path === '/timesheets/generation-preview') {
        return {
          status: 200,
          body: {
            period,
            generation: summary(),
            drafts: 1,
            totals: totals(),
            maxDrafts: 500,
            maxRows: 5000,
          },
        };
      }
      if (request.path === `/timesheets/generations/${GENERATION}`) {
        return {
          status: 200,
          body: generationView(
            version === 1
              ? {}
              : {
                  version,
                  refreshedAt: '2099-11-08T12:00:00Z',
                  totals: totals({
                    rows: 2,
                    readyRows: 2,
                    missingRows: 0,
                    blockedRows: 0,
                    workedMinutes: 959,
                  }),
                },
          ),
        };
      }
      if (request.path === `/timesheets/generations/${GENERATION}/refresh-preview`) {
        return {
          status: 200,
          body: {
            generationId: GENERATION,
            version,
            period,
            previewDigest: DIGEST,
            newDrafts: 0,
            changedTimesheets: 1,
            unchangedTimesheets: 0,
            changedRows: 1,
            totals: totals({ readyRows: 2, missingRows: 0, blockedRows: 0, workedMinutes: 959 }),
          },
        };
      }
      if (request.path === `/timesheets/generations/${GENERATION}/refreshes`) {
        if (stale) {
          stale = false;
          return { status: 409, body: problem('TIMESHEET_REFRESH_PREVIEW_STALE', 409) };
        }
        version = 2;
        return {
          status: 200,
          body: {
            generation: summary({ version: 2, refreshedAt: '2099-11-08T12:00:00Z' }),
            newDrafts: 0,
            changedTimesheets: 1,
            changedRows: 1,
          },
        };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimesheetsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/timesheets',
    );
    await chooseWeek(user, k);
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    expect(await screen.findByTestId('timesheets-existing')).toBeVisible();
    await user.click(screen.getByRole('button', { name: k.preview.open }));
    const drafts = await screen.findByTestId('timesheets-generation');
    expect(within(drafts).getByTestId('timesheets-generation-version')).toHaveTextContent(
      k.generation.version.replace('{{version}}', '1'),
    );

    // The first commit is stale: refused, the preview is dropped and must be taken again.
    await user.click(within(drafts).getByRole('button', { name: k.refresh.action }));
    const refresh = await screen.findByTestId('timesheets-refresh');
    expect(within(refresh).getByTestId('timesheets-refresh-changed')).toHaveTextContent('1');
    await expectAccessible(container);
    const apply = within(refresh).getByRole('button', { name: k.refresh.apply });
    expect(apply).toBeDisabled();
    await user.click(within(refresh).getByLabelText(k.refresh.confirmCheck));
    await user.click(apply);
    expect(await screen.findByTestId('timesheets-error')).toHaveTextContent(
      c.errors.TIMESHEET_REFRESH_PREVIEW_STALE,
    );
    expect(screen.queryByTestId('timesheets-refresh')).toBeNull();

    await user.click(screen.getByRole('button', { name: k.refresh.action }));
    const again = await screen.findByTestId('timesheets-refresh');
    await user.click(within(again).getByLabelText(k.refresh.confirmCheck));
    await user.click(within(again).getByRole('button', { name: k.refresh.apply }));
    await waitFor(() => {
      expect(screen.getByTestId('timesheets-generation-version')).toHaveTextContent(
        k.generation.version.replace('{{version}}', '2'),
      );
    });
    const commits = requests.filter((r) => r.path.endsWith('/refreshes'));
    expect(commits).toHaveLength(2);
    expect(commits[1]?.body).toEqual({
      siteId: SITE,
      weekStart: MONDAY,
      version: 1,
      previewDigest: DIGEST,
      confirm: true,
    });
    // A new preview means a new key.
    expect(commits[0]?.key).not.toBe(commits[1]?.key);
    expect(screen.getByTestId('timesheets-announcer')).toHaveTextContent(
      k.refresh.done.replace('{{version}}', '2'),
    );
    await expectAccessible(container);
  });

  it('shows a refused week without guessing', async () => {
    stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      return { status: 422, body: problem('TIMESHEET_GENERATION_LIMIT_EXCEEDED', 422) };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <TimesheetsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/timesheets',
    );
    await chooseWeek(user, k);
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    const error = await screen.findByTestId('timesheets-error');
    expect(error).toHaveTextContent(c.errors.TIMESHEET_GENERATION_LIMIT_EXCEEDED);
    expect(error).toHaveTextContent('corr-ts-1234');
    expect(screen.queryByTestId('timesheets-generation-preview')).toBeNull();
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('My timesheets (%s)', (locale, c) => {
  const k = c.myTimesheets;
  const myRow = {
    labels: readyRow.labels,
    scheduledStartAt: readyRow.scheduledStartAt,
    scheduledEndAt: readyRow.scheduledEndAt,
    state: 'READY',
    checkedInAt: readyRow.checkedInAt,
    checkedOutAt: readyRow.checkedOutAt,
    corrected: true,
    workedMinutes: 479,
  };

  it('lists only the caller’s drafts with a neutral notice and opens one', async () => {
    const requests = stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return {
          status: 200,
          body: {
            items: [
              {
                timesheetId: SHEET,
                period,
                status: 'DRAFT',
                revision: 1,
                totals: totals(),
                updatedAt: '2099-11-08T11:00:00Z',
              },
            ],
            nextCursor: null,
          },
        };
      }
      if (request.path === `/me/timesheets/${SHEET}`) {
        return {
          status: 200,
          body: {
            timesheetId: SHEET,
            period,
            status: 'DRAFT',
            revision: 1,
            totals: totals(),
            updatedAt: '2099-11-08T11:00:00Z',
            rows: [
              myRow,
              {
                ...myRow,
                scheduledStartAt: missingRow.scheduledStartAt,
                scheduledEndAt: missingRow.scheduledEndAt,
                state: 'MISSING_ATTENDANCE',
                checkedInAt: null,
                checkedOutAt: null,
                corrected: false,
                workedMinutes: null,
              },
            ],
          },
        };
      }
      return { status: 404, body: problem('TIMESHEET_TARGET_NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MyTimesheetsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/timesheets',
    );
    expect(screen.getByRole('heading', { level: 1, name: k.title })).toBeVisible();
    expect(screen.getByTestId('my-timesheets-notice')).toHaveTextContent(k.notice);
    const cards = await screen.findAllByTestId('my-timesheet');
    expect(cards).toHaveLength(1);
    expect(within(cards[0]!).getByTestId('my-timesheet-status')).toHaveTextContent(k.status);
    expect(within(cards[0]!).getByTestId('my-timesheet-worked')).toHaveTextContent('7 h 59 min');
    expect(within(cards[0]!).getByTestId('my-timesheet-site')).toHaveTextContent('Kinshasa Gombe');
    await expectAccessible(container);

    await user.click(within(cards[0]!).getByTestId('my-timesheet-view'));
    const opened = await screen.findByTestId('my-timesheet-detail');
    const rows = await within(opened).findAllByTestId('timesheet-row');
    expect(rows.map((row) => row.dataset.state)).toEqual(['READY', 'MISSING_ATTENDANCE']);
    expect(within(rows[0]!).getByTestId('timesheet-row-corrected')).toHaveTextContent(
      c.attendance.corrected.label,
    );
    // No cost center column, no internal identifier, no administrator.
    expect(within(opened).queryByTestId('timesheet-row-cost-center')).toBeNull();
    expect(opened.textContent).not.toMatch(/Opérations|CC-OPS|88888888|66666666/u);
    expect(requests.every((r) => r.method === 'GET')).toBe(true);
    await expectAccessible(container);
    expect(stored()).not.toMatch(/Cashier|Caissi|55555555|2099-11/u);
  });

  it('shows the employee link requirement without a retry', async () => {
    stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return { status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) };
      }
      return { status: 404, body: problem('TIMESHEET_TARGET_NOT_FOUND', 404) };
    });
    await renderWithSession(
      <MyTimesheetsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/timesheets',
    );
    const error = await screen.findByTestId('my-timesheets-error');
    expect(error).toHaveTextContent(c.errors.EMPLOYEE_LINK_REQUIRED);
    expect(within(error).queryByRole('button')).toBeNull();
  });
});

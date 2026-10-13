import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { AttendanceReviewPage } from './AttendanceReviewPage';
import { MyAttendancePage } from './MyAttendancePage';
import { TeamAttendancePage } from './TeamAttendancePage';
import {
  EMPTY_FILTERS,
  exceptionFailureOf,
  exceptionQueryOf,
  filterProblemsOf,
} from './attendanceExceptions';

const SITE = '33333333-0000-4000-8000-000000000003';
const LEGAL = '44444444-0000-4000-8000-000000000004';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const SLOT_2 = '77777777-0000-4000-8000-000000000007';
const RECORD = '88888888-0000-4000-8000-000000000008';
const ANA = '11111111-0000-4000-8000-000000000001';
const BEN = '22222222-0000-4000-8000-000000000002';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-exc-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string };

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
      };
      requests.push(recorded);
      const reply = await route(recorded);
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
          ...reply.headers,
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

const shift = (overrides: Record<string, unknown> = {}) => ({
  scheduleId: SCHEDULE,
  slotId: SLOT,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  siteTimezone: 'Africa/Kinshasa',
  start: '2099-11-02T08:00',
  end: '2099-11-02T16:00',
  startAt: '2099-11-02T07:00:00Z',
  endAt: '2099-11-02T15:00:00Z',
  overnight: false,
  labels: { en: 'Cashier', fr: 'Caissière' },
  ...overrides,
});

const ANA_PERSON = { id: ANA, employeeNumber: 'E-0042', givenNames: 'Ana', familyName: 'Kalala' };
const BEN_PERSON = { id: BEN, employeeNumber: 'E-0043', givenNames: 'Ben', familyName: 'Ilunga' };

/** One row of every type: a missing check-in, a late and early record, an open record. */
const rows = (person?: typeof ANA_PERSON) => {
  const who = person ? { employee: person } : {};
  return [
    {
      type: 'MISSING_CHECK_IN',
      ...who,
      ...shift({ slotId: SLOT_2 }),
      attendanceRecordId: null,
      checkedInAt: null,
      checkedOutAt: null,
      varianceMinutes: null,
    },
    {
      type: 'LATE_CHECK_IN',
      ...who,
      ...shift(),
      attendanceRecordId: RECORD,
      checkedInAt: '2099-11-02T07:12:00Z',
      checkedOutAt: '2099-11-02T14:40:00Z',
      varianceMinutes: 12,
    },
    {
      type: 'EARLY_CHECK_OUT',
      ...who,
      ...shift(),
      attendanceRecordId: RECORD,
      checkedInAt: '2099-11-02T07:12:00Z',
      checkedOutAt: '2099-11-02T14:40:00Z',
      varianceMinutes: 20,
    },
    {
      type: 'MISSING_CHECK_OUT',
      ...who,
      ...shift({ slotId: SLOT_2, start: '2099-11-01T08:00', end: '2099-11-01T16:00' }),
      attendanceRecordId: RECORD,
      checkedInAt: '2099-11-01T06:58:00Z',
      checkedOutAt: null,
      varianceMinutes: null,
    },
  ];
};

const page = (items: unknown[], nextCursor: string | null = null) => ({
  status: 200,
  body: { items, nextCursor, asOfInstant: '2099-11-03T06:00:00Z' },
  headers: { 'Cache-Control': 'private, no-store' },
});

const exceptionRequests = (requests: Recorded[]) =>
  requests.filter((request) => /\/exceptions(\?|$)/u.test(request.path));

describe('MVP-060C exception rules in the browser', () => {
  it('requires both dates or neither, in order, at most 93 inclusive dates', () => {
    expect(filterProblemsOf(EMPTY_FILTERS).size).toBe(0);
    expect(filterProblemsOf({ ...EMPTY_FILTERS, from: '2099-11-01' })).toEqual(
      new Map([['to', 'required']]),
    );
    expect(filterProblemsOf({ ...EMPTY_FILTERS, to: '2099-11-01' })).toEqual(
      new Map([['from', 'required']]),
    );
    expect(filterProblemsOf({ ...EMPTY_FILTERS, from: '2099-11-02', to: '2099-11-01' })).toEqual(
      new Map([['to', 'range']]),
    );
    expect(filterProblemsOf({ ...EMPTY_FILTERS, from: '2099-01-01', to: '2099-04-03' }).size).toBe(
      0,
    );
    expect(filterProblemsOf({ ...EMPTY_FILTERS, from: '2099-01-01', to: '2099-04-04' })).toEqual(
      new Map([['to', 'range']]),
    );
  });

  it('sends only present filters, and a site only from the organization view', () => {
    const filters = { type: 'LATE_CHECK_IN', from: '2099-11-01', to: '2099-11-30', siteId: SITE };
    expect(exceptionQueryOf('self', filters as never, null)).toEqual({
      type: 'LATE_CHECK_IN',
      from: '2099-11-01',
      to: '2099-11-30',
    });
    expect(exceptionQueryOf('team', filters as never, 'AEc')).toEqual({
      type: 'LATE_CHECK_IN',
      from: '2099-11-01',
      to: '2099-11-30',
      cursor: 'AEc',
    });
    expect(exceptionQueryOf('admin', filters as never, null)).toEqual({
      siteId: SITE,
      type: 'LATE_CHECK_IN',
      from: '2099-11-01',
      to: '2099-11-30',
    });
    expect(exceptionQueryOf('admin', { ...EMPTY_FILTERS, from: '2099-11-01' }, null)).toEqual({});
  });

  it('maps problems to stable kinds and shows only allow-listed fields', () => {
    const response = (status: number) => new Response(null, { status });
    expect(
      exceptionFailureOf(
        'admin',
        response(404),
        problem('ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND', 404),
      ),
    ).toMatchObject({
      kind: 'scopeNotFound',
      messageKey: 'errors.ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND',
    });
    expect(
      exceptionFailureOf(
        'team',
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'to', code: 'RANGE' },
            { field: 'employeeId', code: 'FORMAT' },
            { field: 'type', code: 'FORMAT' },
          ],
        }),
      ),
    ).toMatchObject({ kind: 'validation', fields: ['type', 'to'] });
    expect(exceptionFailureOf('self', response(400), problem('CURSOR_INVALID', 400))).toMatchObject(
      { kind: 'cursor' },
    );
    expect(exceptionFailureOf('admin', response(403), problem('ACCESS_DENIED', 403))).toMatchObject(
      { kind: 'forbidden', messageKey: 'attendanceReview.unauthorized' },
    );
    expect(
      exceptionFailureOf('self', response(403), problem('EMPLOYEE_LINK_REQUIRED', 403)),
    ).toMatchObject({ kind: 'linkRequired' });
  });
});

describe.each(['en', 'fr'] as const)('MVP-060C attendance exceptions (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.attendanceExceptions;

  it('shows my own exceptions with neutral labels, the difference and no action', async () => {
    const requests = stubApi((request) =>
      request.path.startsWith('/me/attendance/exceptions') ? page(rows()) : page([], null),
    );
    const { container } = await renderWithSession(
      <MyAttendancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/attendance',
    );
    const section = await screen.findByTestId('attendance-exceptions-self');
    expect(within(section).getByRole('heading', { level: 2, name: k.title.self })).toBeVisible();
    const cards = await within(section).findAllByTestId('attendance-exception');
    expect(cards.map((card) => card.dataset.type)).toEqual([
      'MISSING_CHECK_IN',
      'LATE_CHECK_IN',
      'EARLY_CHECK_OUT',
      'MISSING_CHECK_OUT',
    ]);
    expect(
      cards.map((card) => within(card).getByTestId('attendance-exception-type').textContent),
    ).toEqual([
      k.type.MISSING_CHECK_IN,
      k.type.LATE_CHECK_IN,
      k.type.EARLY_CHECK_OUT,
      k.type.MISSING_CHECK_OUT,
    ]);
    expect(within(cards[1]!).getByTestId('attendance-exception-variance')).toHaveTextContent(
      k.variance.LATE_CHECK_IN.replace('{{minutes}}', '12'),
    );
    expect(within(cards[2]!).getByTestId('attendance-exception-variance')).toHaveTextContent(
      k.variance.EARLY_CHECK_OUT.replace('{{minutes}}', '20'),
    );
    expect(within(cards[0]!).queryByTestId('attendance-exception-variance')).toBeNull();
    expect(within(cards[0]!).getByTestId('attendance-exception-checked-in')).toHaveTextContent(
      k.notRecorded,
    );
    // Times are shown in the site's zone (UTC+1): 07:12Z is 08:12 at the site.
    expect(within(cards[1]!).getByTestId('attendance-exception-checked-in')).toHaveTextContent(
      /8:12|08:12/u,
    );
    expect(within(cards[0]!).getByTestId('attendance-exception-label')).toHaveTextContent(
      locale === 'fr' ? 'Caissière' : 'Cashier',
    );
    expect(within(section).getByTestId('attendance-exceptions-disclaimer')).toHaveTextContent(
      k.disclaimer,
    );
    expect(within(section).getByTestId('attendance-exceptions-corrections')).toHaveTextContent(
      k.correctionsLater,
    );
    // Read-only: no correct, excuse or approve control, and no employee identity in my own view.
    const buttons = within(section)
      .getAllByRole('button')
      .map((button) => button.textContent);
    expect(buttons).toEqual([k.filters.apply]);
    expect(section).not.toHaveTextContent('E-0042');
    const sent = exceptionRequests(requests);
    expect(sent.map((request) => request.path)).toEqual(['/me/attendance/exceptions']);
    expect(stored()).not.toMatch(
      /Kalala|Cashier|Caissi|E-0042|88888888|66666666|LATE_CHECK_IN|2099-11/u,
    );
    await expectAccessible(container);
  });

  it('filters my team’s exceptions, validates dates first and pages with the cursor', async () => {
    const requests = stubApi((request) => {
      if (!request.path.startsWith('/me/team-attendance/exceptions')) return page([]);
      if (request.path.includes('cursor=AE2')) return page(rows(BEN_PERSON).slice(0, 1));
      return request.path.includes('type=')
        ? page(rows(ANA_PERSON).slice(1, 2), 'AE2')
        : page(rows(ANA_PERSON));
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TeamAttendancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/team-attendance',
    );
    const section = await screen.findByTestId('attendance-exceptions-team');
    expect(within(section).getByRole('heading', { level: 2, name: k.title.team })).toBeVisible();
    const cards = await within(section).findAllByTestId('attendance-exception');
    expect(cards).toHaveLength(4);
    expect(
      within(cards[0]!).getByRole('heading', { level: 3, name: 'Ana Kalala (E-0042)' }),
    ).toBeVisible();

    // A first date alone is refused in the browser: nothing is sent.
    const before = exceptionRequests(requests).length;
    await user.type(within(section).getByLabelText(k.filters.from), '2099-11-01');
    await user.click(within(section).getByRole('button', { name: k.filters.apply }));
    expect(within(section).getByText(k.problems.to.required)).toBeVisible();
    expect(within(section).getByLabelText(k.filters.to)).toHaveFocus();
    expect(exceptionRequests(requests)).toHaveLength(before);

    await user.type(within(section).getByLabelText(k.filters.to), '2099-11-30');
    await user.selectOptions(within(section).getByLabelText(k.filters.type), k.type.LATE_CHECK_IN);
    await user.click(within(section).getByRole('button', { name: k.filters.apply }));
    await waitFor(() => {
      expect(within(section).getAllByTestId('attendance-exception')).toHaveLength(1);
    });
    expect(exceptionRequests(requests).at(-1)?.path).toBe(
      '/me/team-attendance/exceptions?type=LATE_CHECK_IN&from=2099-11-01&to=2099-11-30',
    );
    await user.click(within(section).getByRole('button', { name: k.more }));
    await waitFor(() => {
      expect(within(section).getAllByTestId('attendance-exception')).toHaveLength(2);
    });
    expect(exceptionRequests(requests).at(-1)?.path).toBe(
      '/me/team-attendance/exceptions?type=LATE_CHECK_IN&from=2099-11-01&to=2099-11-30&cursor=AE2',
    );
    await waitFor(() => {
      expect(within(section).getAllByTestId('attendance-exception')[1]).toHaveFocus();
    });
    await user.click(within(section).getByRole('button', { name: k.filters.reset }));
    await waitFor(() => {
      expect(within(section).getAllByTestId('attendance-exception')).toHaveLength(4);
    });
    expect(exceptionRequests(requests).at(-1)?.path).toBe('/me/team-attendance/exceptions');
    expect(stored()).not.toMatch(
      /Kalala|Cashier|Caissi|E-0042|88888888|66666666|LATE_CHECK_IN|2099-11/u,
    );
    await expectAccessible(container);
  });

  it('reviews the organization by site and shows an undisclosable site neutrally', async () => {
    const requests = stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return {
          status: 200,
          body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] },
        };
      }
      if (request.path.startsWith('/sites')) {
        return {
          status: 200,
          body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] },
        };
      }
      if (request.path.includes(`siteId=${SITE}`)) {
        return { status: 404, body: problem('ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND', 404) };
      }
      return page(rows(ANA_PERSON));
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <AttendanceReviewPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/attendance-review',
    );
    expect(
      await screen.findByRole('heading', { level: 1, name: c.attendanceReview.title }),
    ).toBeVisible();
    const cards = await screen.findAllByTestId('attendance-exception');
    expect(
      within(cards[0]!).getByRole('heading', { level: 2, name: 'Ana Kalala (E-0042)' }),
    ).toBeVisible();
    expect(
      screen.queryByRole('button', { name: /correct|excuse|approve|corrig|justif|approuv/iu }),
    ).toBeNull();
    await expectAccessible(container);

    const legal = screen.getByLabelText(k.filters.legalEntity);
    await waitFor(() => {
      expect(within(legal).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(legal, LEGAL);
    const site = screen.getByLabelText(k.filters.siteLabel);
    await waitFor(() => {
      expect(within(site).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(site, SITE);
    await user.click(screen.getByRole('button', { name: k.filters.apply }));
    const error = await screen.findByTestId('attendance-exceptions-error');
    expect(error).toHaveAttribute('data-kind', 'scopeNotFound');
    expect(error).toHaveTextContent(c.errors.ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND);
    expect(error).not.toHaveTextContent(SITE);
    expect(exceptionRequests(requests).at(-1)?.path).toBe(`/attendance/exceptions?siteId=${SITE}`);
    expect(stored()).not.toMatch(
      /Kalala|Cashier|Caissi|E-0042|88888888|66666666|LATE_CHECK_IN|2099-11/u,
    );
    await expectAccessible(container);
  });

  it('says so when there is nothing, and when a page cannot be read', async () => {
    stubApi((request) =>
      request.path.startsWith('/attendance/exceptions')
        ? { status: 429, body: problem('RATE_LIMITED', 429), headers: { 'Retry-After': '30' } }
        : page([]),
    );
    const { container } = await renderWithSession(
      <AttendanceReviewPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/attendance-review',
    );
    const error = await screen.findByTestId('attendance-exceptions-error');
    expect(error).toHaveAttribute('data-kind', 'rateLimited');
    expect(within(error).getByRole('button', { name: k.retry })).toBeVisible();
    await expectAccessible(container);
  });

  it('shows the empty state of my own view', async () => {
    stubApi(() => page([]));
    await renderWithSession(
      <MyAttendancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/attendance',
    );
    const section = await screen.findByTestId('attendance-exceptions-self');
    expect(await within(section).findByTestId('attendance-exceptions-empty')).toHaveTextContent(
      k.empty.self,
    );
  });
});

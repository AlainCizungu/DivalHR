import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MyAttendancePage } from './MyAttendancePage';
import { MySchedulePage } from './MySchedulePage';
import { attendanceFailureOf, formatInstant } from './attendance';

const SITE = '33333333-0000-4000-8000-000000000003';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const RECORD = '88888888-0000-4000-8000-000000000008';
const OTHER = '99999999-0000-4000-8000-000000000009';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-attendance-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const body = incoming.method === 'GET' ? '' : await incoming.clone().text();
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body,
        key: incoming.headers.get('Idempotency-Key'),
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

const attendance = (overrides: Record<string, unknown> = {}) => ({
  status: 'NOT_RECORDED',
  attendanceRecordId: null,
  checkedInAt: null,
  checkedOutAt: null,
  checkInAvailable: false,
  checkOutAvailable: false,
  checkInRecordingMethod: null,
  checkInSupervisorReasonLocale: null,
  checkInSupervisorReason: null,
  checkOutRecordingMethod: null,
  checkOutSupervisorReasonLocale: null,
  checkOutSupervisorReason: null,
  ...overrides,
});

const shiftOf = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  scheduleId: SCHEDULE,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  start: '2099-11-02T08:00',
  end: '2099-11-02T16:00',
  siteTimezone: 'Africa/Kinshasa',
  overnight: false,
  labels: { en: 'Cashier', fr: 'Caissière' },
  assignmentSource: 'PUBLISHED',
  attendance: attendance(),
  ...overrides,
});

const page = (items: unknown[]) => ({
  items,
  nextCursor: null,
  asOfInstant: '2099-11-02T06:00:00Z',
  from: null,
  to: null,
});

const record = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  status: 'COMPLETED',
  checkedInAt: '2099-11-02T06:55:00Z',
  checkedOutAt: '2099-11-02T15:05:00Z',
  checkOutAvailable: false,
  // MVP-060B: the employee's own times (SELF); a supervisor's are covered in TeamAttendance.test.
  checkInRecordingMethod: 'SELF',
  checkInSupervisorReasonLocale: null,
  checkInSupervisorReason: null,
  checkOutRecordingMethod: 'SELF',
  checkOutSupervisorReasonLocale: null,
  checkOutSupervisorReason: null,
  shift: {
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
  },
  ...overrides,
});

const emptyHistory = { status: 200, body: { items: [], nextCursor: null, asOfInstant: 'x' } };

describe('MVP-060A attendance rules in the browser', () => {
  it('maps every refusal to a stable message without showing a param', () => {
    const at = (status: number, code: string, headers: Record<string, string> = {}) =>
      attendanceFailureOf(
        new Response(null, { status, headers }),
        problem(code, status, { slotId: SLOT }),
      );
    expect(at(404, 'ATTENDANCE_SHIFT_NOT_FOUND').kind).toBe('gone');
    expect(at(404, 'ATTENDANCE_RECORD_NOT_FOUND').kind).toBe('gone');
    expect(at(409, 'ATTENDANCE_CHECK_IN_TOO_EARLY').kind).toBe('notYet');
    expect(at(409, 'ATTENDANCE_CHECK_IN_WINDOW_CLOSED').kind).toBe('closed');
    expect(at(409, 'ATTENDANCE_CHECK_OUT_WINDOW_CLOSED').kind).toBe('closed');
    expect(at(409, 'ATTENDANCE_ALREADY_CHECKED_IN').kind).toBe('already');
    expect(at(409, 'ATTENDANCE_ALREADY_CHECKED_OUT').kind).toBe('already');
    expect(at(409, 'ATTENDANCE_OPEN_RECORD_EXISTS').kind).toBe('openElsewhere');
    expect(at(409, 'ATTENDANCE_STATE_CONFLICT').kind).toBe('conflict');
    expect(at(403, 'EMPLOYEE_LINK_REQUIRED').kind).toBe('linkRequired');
    expect(at(403, 'ACCESS_DENIED').kind).toBe('forbidden');
    const limited = at(429, 'RATE_LIMITED', { 'Retry-After': '30' });
    expect(limited).toMatchObject({ kind: 'rateLimited', retryAfter: 30 });
    expect(at(500, 'SOMETHING_ELSE').messageKey).toBe('errors.generic');
    expect(JSON.stringify(at(404, 'ATTENDANCE_SHIFT_NOT_FOUND'))).not.toContain(SLOT);
    for (const locale of ['en', 'fr'] as const) {
      const errors = resources[locale].common.errors as Record<string, string>;
      for (const code of [
        'ATTENDANCE_SHIFT_NOT_FOUND',
        'ATTENDANCE_CHECK_IN_TOO_EARLY',
        'ATTENDANCE_CHECK_IN_WINDOW_CLOSED',
        'ATTENDANCE_ALREADY_CHECKED_IN',
        'ATTENDANCE_OPEN_RECORD_EXISTS',
        'ATTENDANCE_RECORD_NOT_FOUND',
        'ATTENDANCE_ALREADY_CHECKED_OUT',
        'ATTENDANCE_CHECK_OUT_WINDOW_CLOSED',
        'ATTENDANCE_STATE_CONFLICT',
      ]) {
        expect(errors[code], `${locale} ${code}`).toBeTruthy();
      }
    }
  });

  it('shows server instants in the site’s own time zone', () => {
    expect(formatInstant('en', '2099-11-02T06:55:00Z', 'Africa/Kinshasa')).toContain('7:55');
    expect(formatInstant('fr', '2099-11-02T06:55:00Z', 'Africa/Lubumbashi')).toContain('08:55');
  });
});

describe('MVP-060A My schedule: check in and check out', () => {
  it('offers only the action the server allows and confirms a check-in once, in French', async () => {
    const c = resources.fr.common;
    let checkedIn = false;
    const requests = stubApi((request) => {
      if (request.path.startsWith('/me/work-schedule-substitutions')) return emptyHistory;
      if (request.path.startsWith('/me/work-schedule')) {
        return {
          status: 200,
          body: page([
            shiftOf(SLOT, {
              attendance: checkedIn
                ? attendance({
                    status: 'OPEN',
                    attendanceRecordId: RECORD,
                    checkedInAt: '2099-11-02T06:55:00Z',
                    checkOutAvailable: true,
                  })
                : attendance({ checkInAvailable: true }),
            }),
            shiftOf(OTHER, { start: '2099-11-03T08:00', end: '2099-11-03T16:00' }),
          ]),
        };
      }
      if (request.path === '/me/attendance/check-in') {
        checkedIn = true;
        return {
          status: 201,
          body: {
            attendanceRecordId: RECORD,
            slotId: SLOT,
            status: 'OPEN',
            checkedInAt: '2099-11-02T06:55:00Z',
            checkedOutAt: null,
          },
        };
      }
      return { status: 500, body: problem('INTERNAL_ERROR', 500) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MySchedulePage />,
      sessionWithRoles(['employee']),
      'fr',
    );
    const shifts = await screen.findAllByTestId('my-shift');
    expect(shifts).toHaveLength(2);
    const [first, second] = shifts as [HTMLElement, HTMLElement];
    expect(within(first).getByTestId('my-shift-attendance-status')).toHaveTextContent(
      c.attendance.shiftStatus.NOT_RECORDED,
    );
    // The other shift is outside its window: no action, a neutral explanation.
    expect(within(second).queryByTestId('check-in')).toBeNull();
    expect(within(second).getByTestId('my-shift-check-in-not-open')).toHaveTextContent(
      c.attendance.checkInNotOpen,
    );
    await expectAccessible(container);
    // One attendance dialog beside the substitution dialog, closed.
    expect(screen.getAllByTestId('attendance-dialog')).toHaveLength(1);

    await user.click(within(first).getByTestId('check-in'));
    const dialog = await screen.findByRole('dialog', { name: c.attendance.checkIn.title });
    expect(within(dialog).getByTestId('attendance-dialog-shift')).toHaveTextContent('Caissière');
    expect(dialog).toHaveTextContent(c.attendance.checkIn.confirm);
    const confirm = within(dialog).getByRole('button', {
      name: c.attendance.checkIn.confirmButton,
    });
    await waitFor(() => expect(confirm).toHaveFocus());
    await expectAccessible(container);
    await user.dblClick(confirm);
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]?.path).toBe('/me/attendance/check-in');
    expect(JSON.parse(posts[0]?.body ?? '{}')).toEqual({ slotId: SLOT });
    expect(posts[0]?.key).toMatch(/^web-/u);
    expect(screen.getByTestId('my-schedule-announcer')).toHaveTextContent(/Votre arrivée/u);
    // Refreshed from the server: open, with the recorded time and the check-out action.
    await waitFor(() =>
      expect(screen.getAllByTestId('my-shift')[0]).toHaveAttribute('data-source', 'PUBLISHED'),
    );
    const refreshed = (await screen.findAllByTestId('my-shift-checked-in'))[0] as HTMLElement;
    expect(refreshed).toHaveTextContent('07:55');
    expect(screen.getAllByTestId('check-out')).toHaveLength(1);
    expect(screen.queryAllByTestId('check-in')).toHaveLength(0);
    expect(stored()).not.toMatch(/Caissi|7:55|88888888/u);
  });

  it('shows a refusal, refreshes the page and never sends again, in English', async () => {
    const c = resources.en.common;
    let lists = 0;
    const requests = stubApi((request) => {
      if (request.path.startsWith('/me/work-schedule-substitutions')) return emptyHistory;
      if (request.path.startsWith('/me/work-schedule')) {
        lists += 1;
        return {
          status: 200,
          body: page([shiftOf(SLOT, { attendance: attendance({ checkInAvailable: true }) })]),
        };
      }
      return { status: 409, body: problem('ATTENDANCE_OPEN_RECORD_EXISTS', 409, { x: SLOT }) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MySchedulePage />,
      sessionWithRoles(['employee']),
      'en',
    );
    await user.click(await screen.findByTestId('check-in'));
    const dialog = await screen.findByRole('dialog', { name: c.attendance.checkIn.title });
    await user.click(
      within(dialog).getByRole('button', { name: c.attendance.checkIn.confirmButton }),
    );
    const alert = await within(dialog).findByTestId('attendance-dialog-error');
    expect(alert).toHaveTextContent(c.errors.ATTENDANCE_OPEN_RECORD_EXISTS);
    expect(alert).not.toHaveTextContent(SLOT);
    expect(
      within(dialog).getByRole('button', { name: c.attendance.checkIn.confirmButton }),
    ).toBeDisabled();
    await waitFor(() => {
      expect(lists).toBe(2);
    });
    await expectAccessible(container);
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(1);
  });

  it('shows a completed shift with both times and no action', async () => {
    const c = resources.en.common;
    stubApi((request) => {
      if (request.path.startsWith('/me/work-schedule-substitutions')) return emptyHistory;
      return {
        status: 200,
        body: page([
          shiftOf(SLOT, {
            attendance: attendance({
              status: 'COMPLETED',
              attendanceRecordId: RECORD,
              checkedInAt: '2099-11-02T06:55:00Z',
              checkedOutAt: '2099-11-02T14:05:00Z',
            }),
          }),
        ]),
      };
    });
    await renderWithSession(<MySchedulePage />, sessionWithRoles(['employee']), 'en');
    const shift = await screen.findByTestId('my-shift');
    expect(within(shift).getByTestId('my-shift-attendance-status')).toHaveTextContent(
      c.attendance.shiftStatus.COMPLETED,
    );
    expect(within(shift).getByTestId('my-shift-checked-in')).toHaveTextContent('7:55');
    expect(within(shift).getByTestId('my-shift-checked-out')).toHaveTextContent('3:05');
    expect(within(shift).queryByRole('button', { name: /check (in|out)/iu })).toBeNull();
  });
});

describe('MVP-060A My attendance', () => {
  it('lists only my records with times in the site zone and checks out an open one, in English', async () => {
    const c = resources.en.common;
    let closed = false;
    const requests = stubApi((request) => {
      if (request.method === 'GET') {
        return {
          status: 200,
          body: {
            items: [
              closed
                ? record(RECORD)
                : record(RECORD, { status: 'OPEN', checkedOutAt: null, checkOutAvailable: true }),
              record(OTHER, { status: 'OPEN', checkedOutAt: null, checkOutAvailable: false }),
            ],
            nextCursor: null,
            asOfInstant: '2099-11-02T16:00:00Z',
          },
          headers: { 'Cache-Control': 'private, no-store' },
        };
      }
      closed = true;
      return {
        status: 200,
        body: {
          attendanceRecordId: RECORD,
          slotId: SLOT,
          status: 'COMPLETED',
          checkedInAt: '2099-11-02T06:55:00Z',
          checkedOutAt: '2099-11-02T15:05:00Z',
        },
      };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MyAttendancePage />,
      sessionWithRoles(['employee']),
      'en',
    );
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(c.attendance.title);
    const records = await screen.findAllByTestId('my-attendance-record');
    expect(records).toHaveLength(2);
    const [open, missed] = records as [HTMLElement, HTMLElement];
    expect(within(open).getByTestId('my-attendance-status')).toHaveTextContent(
      c.attendance.status.OPEN,
    );
    expect(within(open).getByTestId('my-attendance-label')).toHaveTextContent('Cashier');
    expect(within(open).getByTestId('my-attendance-checked-in')).toHaveTextContent('7:55');
    expect(within(open).getByTestId('my-attendance-checked-out')).toHaveTextContent(
      c.attendance.notCheckedOut,
    );
    expect(within(missed).queryByTestId('check-out')).toBeNull();
    expect(within(missed).getByTestId('my-attendance-check-out-closed')).toHaveTextContent(
      c.attendance.checkOutClosed,
    );
    await expectAccessible(container);

    await user.click(within(open).getByTestId('check-out'));
    const dialog = await screen.findByRole('dialog', { name: c.attendance.checkOut.title });
    expect(within(dialog).getByTestId('attendance-dialog-checked-in')).toHaveTextContent('7:55');
    await expectAccessible(container);
    await user.click(
      within(dialog).getByRole('button', { name: c.attendance.checkOut.confirmButton }),
    );
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/me/attendance/${RECORD}/check-out`);
    expect(post?.body).toBe('');
    expect(post?.key).toMatch(/^web-/u);
    await waitFor(() =>
      expect(screen.getAllByTestId('my-attendance-status')[0]).toHaveTextContent(
        c.attendance.status.COMPLETED,
      ),
    );
    expect(screen.getByTestId('my-attendance-announcer')).toHaveTextContent(/check-out/iu);
    expect(screen.getAllByTestId('my-attendance-checked-out')[0]).toHaveTextContent('4:05');
    expect(stored()).not.toMatch(/Cashier|88888888/u);
  });

  it('shows the empty history and a missing link in French', async () => {
    const c = resources.fr.common;
    stubApi(() => ({ status: 200, body: { items: [], nextCursor: null, asOfInstant: 'x' } }));
    const { container, unmount } = await renderWithSession(
      <MyAttendancePage />,
      sessionWithRoles(['employee']),
      'fr',
    );
    expect(await screen.findByTestId('my-attendance-empty')).toHaveTextContent(c.attendance.empty);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Ma présence');
    await expectAccessible(container);
    unmount();

    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await renderWithSession(<MyAttendancePage />, sessionWithRoles(['employee']), 'fr');
    const error = await screen.findByTestId('my-attendance-error');
    expect(error).toHaveTextContent(c.errors.EMPLOYEE_LINK_REQUIRED);
    expect(within(error).queryByRole('button')).toBeNull();
  });
});

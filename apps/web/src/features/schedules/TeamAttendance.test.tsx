import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { TeamAttendanceCard } from '../home/TeamAttendanceCard';
import { MyAttendancePage } from './MyAttendancePage';
import { MySchedulePage } from './MySchedulePage';
import { TeamAttendancePage } from './TeamAttendancePage';
import { assistedOf, teamFailureOf } from './teamAttendance';

const SITE = '33333333-0000-4000-8000-000000000003';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const OTHER_SLOT = '77777777-0000-4000-8000-000000000007';
const RECORD = '88888888-0000-4000-8000-000000000008';
const ANA = '11111111-0000-4000-8000-000000000001';
const BEN = '22222222-0000-4000-8000-000000000002';
const REASON_EN = 'Badge reader out of order';
const REASON_FR = 'Téléphone de la salariée en panne à Lubumbashi';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-team-1234',
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
      // MVP-060C: the read-only exceptions section has its own tests (AttendanceExceptions.test).
      const reply = /\/exceptions(\?|$)/u.test(recorded.path)
        ? {
            status: 200,
            body: { items: [], nextCursor: null, asOfInstant: '2099-11-02T06:00:00Z' },
          }
        : await route(recorded);
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

const action = (overrides: Record<string, unknown> = {}) => ({
  requiredAction: 'CHECK_IN',
  actionDeadline: '2099-11-02T15:00:00Z',
  slotId: SLOT,
  scheduleId: SCHEDULE,
  attendanceRecordId: null,
  employee: { id: ANA, employeeNumber: 'E-0042', givenNames: 'Ana', familyName: 'Kalala' },
  attendanceStatus: 'NOT_RECORDED',
  checkedInAt: null,
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

const checkOutAction = () =>
  action({
    requiredAction: 'CHECK_OUT',
    actionDeadline: '2099-11-03T15:00:00Z',
    slotId: OTHER_SLOT,
    attendanceRecordId: RECORD,
    employee: { id: BEN, employeeNumber: 'E-0043', givenNames: 'Ben', familyName: 'Ilunga' },
    attendanceStatus: 'OPEN',
    checkedInAt: '2099-11-02T06:55:00Z',
  });

const queue = (items: unknown[]) => ({
  status: 200,
  body: { items, nextCursor: null, asOfInstant: '2099-11-02T06:00:00Z' },
  headers: { 'Cache-Control': 'private, no-store' },
});

const receipt = (status: 'OPEN' | 'COMPLETED') => ({
  attendanceRecordId: RECORD,
  slotId: SLOT,
  employeeId: ANA,
  status,
  recordingMethod: 'SUPERVISOR',
  checkedInAt: '2099-11-02T06:55:00Z',
  checkedOutAt: status === 'COMPLETED' ? '2099-11-02T15:05:00Z' : null,
});

const SELF = {
  checkInRecordingMethod: 'SELF',
  checkInSupervisorReasonLocale: null,
  checkInSupervisorReason: null,
  checkOutRecordingMethod: null,
  checkOutSupervisorReasonLocale: null,
  checkOutSupervisorReason: null,
};

describe('MVP-060B team attendance rules in the browser', () => {
  it('maps every refusal to a stable message, keeping only allow-listed fields', () => {
    const at = (status: number, code: string, params: Record<string, unknown> = {}) =>
      teamFailureOf(new Response(null, { status }), problem(code, status, params));
    expect(at(404, 'TEAM_ATTENDANCE_ACTION_NOT_FOUND').kind).toBe('gone');
    expect(at(403, 'TEAM_ATTENDANCE_MANAGER_REQUIRED')).toMatchObject({
      kind: 'notManager',
      messageKey: 'teamAttendance.notManager',
    });
    expect(at(409, 'ATTENDANCE_CHECK_IN_TOO_EARLY').kind).toBe('notYet');
    expect(at(409, 'ATTENDANCE_CHECK_OUT_WINDOW_CLOSED').kind).toBe('closed');
    expect(at(409, 'ATTENDANCE_ALREADY_CHECKED_IN').kind).toBe('already');
    expect(at(409, 'ATTENDANCE_OPEN_RECORD_EXISTS').kind).toBe('openElsewhere');
    expect(at(403, 'ACCESS_DENIED').kind).toBe('forbidden');
    const invalid = at(400, 'VALIDATION_FAILED', {
      fields: [
        { field: 'reason', constraint: 'LENGTH' },
        { field: 'slotId', constraint: 'FORMAT' },
        { field: 'reasonLocale', constraint: 'FORMAT' },
      ],
    });
    expect(invalid).toMatchObject({ kind: 'validation', fields: ['reasonLocale', 'reason'] });
    expect(
      JSON.stringify(at(404, 'TEAM_ATTENDANCE_ACTION_NOT_FOUND', { slotId: SLOT })),
    ).not.toContain(SLOT);
    for (const locale of ['en', 'fr'] as const) {
      const errors = resources[locale].common.errors as Record<string, string>;
      for (const code of ['TEAM_ATTENDANCE_ACTION_NOT_FOUND', 'TEAM_ATTENDANCE_MANAGER_REQUIRED']) {
        expect(errors[code], `${locale} ${code}`).toBeTruthy();
      }
    }
    expect(resources.en.common.attendance.assisted.label).toBe('Recorded by a supervisor');
    expect(resources.fr.common.attendance.assisted.label).toBe('Enregistré par un responsable');
    expect(resources.en.common.nav.teamAttendance).toBe('Team attendance');
    expect(resources.fr.common.nav.teamAttendance).toBe('Présence de l’équipe');
  });

  it('marks only a supervisor-recorded event, with its reason and language', () => {
    expect(assistedOf('SELF', null, null)).toBeNull();
    expect(assistedOf(null, null, null)).toBeNull();
    expect(assistedOf(undefined, undefined, undefined)).toBeNull();
    expect(assistedOf('SUPERVISOR', 'fr', REASON_FR)).toEqual({
      reasonLocale: 'fr',
      reason: REASON_FR,
    });
  });
});

describe('MVP-060B Team attendance page', () => {
  it('records a direct report’s check-in once with a reason and refreshes, in English', async () => {
    const c = resources.en.common;
    let recorded = false;
    let posts = 0;
    const requests = stubApi((request) => {
      if (request.method === 'GET') {
        return queue(recorded ? [checkOutAction()] : [action(), checkOutAction()]);
      }
      posts += 1;
      if (posts === 1) throw new TypeError('network down');
      recorded = true;
      return { status: 201, body: receipt('OPEN') };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TeamAttendancePage />,
      sessionWithRoles(['employee']),
      'en',
    );
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      c.teamAttendance.title,
    );
    const rows = await screen.findAllByTestId('team-attendance-action');
    expect(rows).toHaveLength(2);
    const [checkIn, checkOut] = rows as [HTMLElement, HTMLElement];
    expect(within(checkIn).getByTestId('team-attendance-employee')).toHaveTextContent(
      'Ana Kalala (E-0042)',
    );
    expect(within(checkIn).getByTestId('team-attendance-required')).toHaveTextContent(
      c.teamAttendance.required.CHECK_IN,
    );
    expect(within(checkIn).getByTestId('team-attendance-scheduled')).toHaveTextContent('8:00');
    expect(within(checkIn).getByTestId('team-attendance-deadline')).toHaveTextContent('4:00');
    expect(within(checkIn).getByTestId('team-attendance-site')).toHaveTextContent('Kinshasa Gombe');
    expect(within(checkIn).queryByTestId('team-attendance-checked-in')).toBeNull();
    expect(within(checkOut).getByTestId('team-attendance-checked-in')).toHaveTextContent('7:55');
    expect(within(checkOut).getByTestId('team-check-out')).toHaveAccessibleName(
      /Record check-out for Ben Ilunga \(E-0043\)/u,
    );
    await expectAccessible(container);

    await user.click(within(checkIn).getByTestId('team-check-in'));
    const dialog = await screen.findByRole('dialog', { name: c.teamAttendance.checkIn.title });
    expect(within(dialog).getByTestId('team-attendance-dialog-employee')).toHaveTextContent(
      'Ana Kalala (E-0042)',
    );
    expect(within(dialog).getByTestId('team-attendance-dialog-shift')).toHaveTextContent(
      'Kinshasa Gombe',
    );
    await expectAccessible(container);
    // No reason: the problem is listed and linked; nothing is sent.
    await user.click(within(dialog).getByTestId('team-attendance-confirm'));
    const problems = await within(dialog).findByTestId('team-attendance-problems');
    expect(within(problems).getAllByRole('link')).toHaveLength(1);
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
    await expectAccessible(container);

    await user.selectOptions(
      within(dialog).getByLabelText(c.scheduleSubstitutions.form.reasonLocale),
      'en',
    );
    await user.type(
      within(dialog).getByLabelText(c.scheduleSubstitutions.form.reason),
      `  ${REASON_EN} `,
    );
    // The first attempt fails on the network; the retry reuses the same key.
    await user.click(within(dialog).getByTestId('team-attendance-confirm'));
    expect(await within(dialog).findByTestId('team-attendance-dialog-error')).toHaveTextContent(
      c.errors.network,
    );
    await user.dblClick(within(dialog).getByTestId('team-attendance-confirm'));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    const sent = requests.filter((r) => r.method === 'POST');
    expect(sent).toHaveLength(2);
    expect(sent[0]?.path).toBe('/me/team-attendance/check-in');
    expect(JSON.parse(sent[0]?.body ?? '{}')).toEqual({
      slotId: SLOT,
      reasonLocale: 'en',
      reason: REASON_EN,
    });
    expect(sent[0]?.key).toMatch(/^web-/u);
    expect(sent[1]?.key).toBe(sent[0]?.key);
    expect(sent[1]?.body).toBe(sent[0]?.body);
    await waitFor(() => {
      expect(screen.getAllByTestId('team-attendance-action')).toHaveLength(1);
    });
    expect(screen.getByTestId('team-attendance-announcer')).toHaveTextContent(
      /Check-in recorded for Ana Kalala \(E-0042\)/u,
    );
    expect(stored()).not.toMatch(/Kalala|Badge|E-0042|66666666/u);
  });

  it('records a check-out in French, and a vanished action refreshes the queue', async () => {
    const c = resources.fr.common;
    let gone = false;
    const requests = stubApi((request) => {
      if (request.method === 'GET') return queue(gone ? [] : [checkOutAction()]);
      gone = true;
      return { status: 404, body: problem('TEAM_ATTENDANCE_ACTION_NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TeamAttendancePage />,
      sessionWithRoles(['employee']),
      'fr',
    );
    const row = await screen.findByTestId('team-attendance-action');
    expect(within(row).getByTestId('team-attendance-required')).toHaveTextContent(
      'Départ à enregistrer',
    );
    expect(within(row).getByTestId('team-attendance-label')).toHaveTextContent('Caissière');
    await user.click(within(row).getByRole('button', { name: /Enregistrer le départ de Ben/u }));
    const dialog = await screen.findByRole('dialog', { name: c.teamAttendance.checkOut.title });
    expect(within(dialog).getByTestId('team-attendance-dialog-checked-in')).toHaveTextContent(
      '07:55',
    );
    await user.type(within(dialog).getByLabelText(c.scheduleSubstitutions.form.reason), REASON_FR);
    await user.click(within(dialog).getByRole('button', { name: 'Confirmer le départ' }));
    const error = await within(dialog).findByTestId('team-attendance-dialog-error');
    expect(error).toHaveTextContent(c.errors.TEAM_ATTENDANCE_ACTION_NOT_FOUND);
    expect(error).not.toHaveTextContent(RECORD);
    expect(within(dialog).getByTestId('team-attendance-confirm')).toBeDisabled();
    await expectAccessible(container);
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/me/team-attendance/${RECORD}/check-out`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ reasonLocale: 'fr', reason: REASON_FR });
    await user.click(within(dialog).getByRole('button', { name: c.attendance.form.cancel }));
    expect(await screen.findByTestId('team-attendance-empty')).toHaveTextContent(
      c.teamAttendance.empty,
    );
  });

  it('tells a colleague without reports that the page is for managers', async () => {
    const c = resources.fr.common;
    stubApi(() => ({ status: 403, body: problem('TEAM_ATTENDANCE_MANAGER_REQUIRED', 403) }));
    const { container } = await renderWithSession(
      <TeamAttendancePage />,
      sessionWithRoles(['employee']),
      'fr',
    );
    const error = await screen.findByTestId('team-attendance-error');
    expect(error).toHaveTextContent(c.teamAttendance.notManager);
    expect(within(error).queryByRole('button')).toBeNull();
    await expectAccessible(container);
  });
});

describe('MVP-060B the employee sees why, never who', () => {
  it('marks supervisor-recorded times on My attendance, in English', async () => {
    const c = resources.en.common;
    stubApi(() => ({
      status: 200,
      body: {
        items: [
          {
            id: RECORD,
            status: 'COMPLETED',
            checkedInAt: '2099-11-02T06:55:00Z',
            checkedOutAt: '2099-11-02T15:05:00Z',
            checkOutAvailable: false,
            checkInRecordingMethod: 'SUPERVISOR',
            checkInSupervisorReasonLocale: 'en',
            checkInSupervisorReason: REASON_EN,
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
          },
        ],
        nextCursor: null,
        asOfInstant: '2099-11-02T16:00:00Z',
      },
    }));
    const { container } = await renderWithSession(
      <MyAttendancePage />,
      sessionWithRoles(['employee']),
      'en',
    );
    const assisted = await screen.findByTestId('my-attendance-check-in-assisted');
    expect(
      within(assisted).getByTestId('my-attendance-check-in-assisted-method'),
    ).toHaveTextContent(c.attendance.assisted.label);
    const reason = within(assisted).getByTestId('my-attendance-check-in-assisted-reason');
    expect(reason).toHaveTextContent(REASON_EN);
    expect(reason).toHaveAttribute('lang', 'en');
    expect(screen.queryByTestId('my-attendance-check-out-assisted')).toBeNull();
    expect(container).not.toHaveTextContent(/Mia|Mbuyi|supervisor@/u);
    await expectAccessible(container);
  });

  it('marks a supervisor-recorded check-out on My schedule, in French', async () => {
    const c = resources.fr.common;
    stubApi((request) => {
      if (request.path.startsWith('/me/work-schedule-substitutions')) {
        return { status: 200, body: { items: [], nextCursor: null, asOfInstant: 'x' } };
      }
      return {
        status: 200,
        body: {
          items: [
            {
              id: SLOT,
              scheduleId: SCHEDULE,
              site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
              start: '2099-11-02T08:00',
              end: '2099-11-02T16:00',
              siteTimezone: 'Africa/Kinshasa',
              overnight: false,
              labels: { en: 'Cashier', fr: 'Caissière' },
              assignmentSource: 'PUBLISHED',
              attendance: {
                status: 'COMPLETED',
                attendanceRecordId: RECORD,
                checkedInAt: '2099-11-02T06:55:00Z',
                checkedOutAt: '2099-11-02T15:05:00Z',
                checkInAvailable: false,
                checkOutAvailable: false,
                ...SELF,
                checkOutRecordingMethod: 'SUPERVISOR',
                checkOutSupervisorReasonLocale: 'fr',
                checkOutSupervisorReason: REASON_FR,
              },
            },
          ],
          nextCursor: null,
          asOfInstant: '2099-11-02T16:00:00Z',
          from: null,
          to: null,
        },
      };
    });
    const { container } = await renderWithSession(
      <MySchedulePage />,
      sessionWithRoles(['employee']),
      'fr',
    );
    const assisted = await screen.findByTestId('my-shift-check-out-assisted');
    expect(assisted).toHaveTextContent(c.attendance.assisted.label);
    expect(within(assisted).getByTestId('my-shift-check-out-assisted-reason')).toHaveTextContent(
      REASON_FR,
    );
    expect(screen.queryByTestId('my-shift-check-in-assisted')).toBeNull();
    await expectAccessible(container);
  });
});

describe('MVP-060B home task card', () => {
  it('appears only while the queue has an action, without a count', async () => {
    const c = resources.en.common;
    const requests = stubApi(() => queue([action()]));
    const { unmount } = await renderWithSession(
      <TeamAttendanceCard />,
      sessionWithRoles(['employee']),
      'en',
    );
    const card = await screen.findByTestId('home-team-attendance');
    expect(card).toHaveTextContent(c.home.employee.teamAttendance.title);
    expect(card).not.toHaveTextContent(/\d|Kalala/u);
    expect(within(card).getByRole('link')).toHaveAttribute('href', '/me/team-attendance');
    expect(requests[0]?.path).toBe('/me/team-attendance/actions?limit=1');
    unmount();

    for (const reply of [
      queue([]),
      { status: 403, body: problem('TEAM_ATTENDANCE_MANAGER_REQUIRED', 403) },
    ]) {
      const calls = stubApi(() => reply);
      const rendered = await renderWithSession(
        <TeamAttendanceCard />,
        sessionWithRoles(['employee']),
        'fr',
      );
      await waitFor(() => {
        expect(calls).toHaveLength(1);
      });
      expect(screen.queryByTestId('home-team-attendance')).toBeNull();
      rendered.unmount();
    }
  });
});

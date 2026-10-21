import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { OperationsStatusPage, TeamOperationsPage } from './OperationsStatusPage';
import { statusFailureOf, statusQueryOf } from './operationalStatus';

const SITE = '33333333-0000-4000-8000-000000000003';
const LEGAL = '44444444-0000-4000-8000-000000000004';
const SLOT = '66666666-0000-4000-8000-000000000006';
const ANA = '11111111-0000-4000-8000-000000000001';

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-ops-1234',
});

type Reply = { status: number; body?: unknown };
type Recorded = { method: string; path: string };

function stubApi(route: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
      };
      requests.push(recorded);
      const reply = route(recorded);
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
          },
        }),
      );
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

const shift = {
  scheduleId: '55555555-0000-4000-8000-000000000005',
  slotId: SLOT,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  siteTimezone: 'Africa/Kinshasa',
  start: '2099-11-02T08:00',
  end: '2099-11-02T16:00',
  startAt: '2099-11-02T07:00:00Z',
  endAt: '2099-11-02T15:00:00Z',
  overnight: false,
  labels: { en: 'Cashier', fr: 'Caissière' },
};
const ANA_PERSON = { id: ANA, employeeNumber: 'E-0042', givenNames: 'Ana', familyName: 'Kalala' };

const THRESHOLDS = {
  lateMinutes: 5,
  notCheckedInGraceMinutes: 30,
  leaveOverdueHours: 48,
  timesheetOverdueHours: 72,
  detailLimit: 20,
};

const status = (overrides: Record<string, unknown> = {}) => ({
  asOfInstant: '2099-11-02T09:30:00Z',
  thresholds: THRESHOLDS,
  shifts: { scheduled: 12, expected: 2, notCheckedIn: 3, present: 4, completed: 3, late: 2 },
  coverage: { uncovered: 1 },
  approvals: { leave: { pending: 5, overdue: 2 }, timesheets: { pending: 4, overdue: 1 } },
  details: {
    notCheckedIn: {
      items: [{ employee: ANA_PERSON, shift, minutesSinceStart: 150 }],
      truncated: false,
    },
    uncovered: { items: [shift], truncated: true },
    overdueLeave: null,
    overdueTimesheets: null,
  },
  ...overrides,
});

const teamStatus = () =>
  status({
    coverage: null,
    details: {
      notCheckedIn: {
        items: [{ employee: ANA_PERSON, shift, minutesSinceStart: 150 }],
        truncated: false,
      },
      uncovered: null,
      overdueLeave: {
        items: [
          {
            requestId: SLOT,
            employee: ANA_PERSON,
            submittedAt: '2099-10-30T09:00:00Z',
            ageHours: 72,
          },
        ],
        truncated: false,
      },
      overdueTimesheets: {
        items: [
          {
            timesheetId: SLOT,
            employee: ANA_PERSON,
            site: shift.site,
            weekStart: '2099-10-26',
            submittedAt: '2099-10-29T09:00:00Z',
            ageHours: 96,
          },
        ],
        truncated: true,
      },
    },
  });

describe('MVP-100 request rules in the browser', () => {
  it('sends only present filters, and only from the organization view', () => {
    const filters = { legalEntityId: LEGAL, siteId: SITE };
    expect(statusQueryOf('admin', filters)).toEqual({ legalEntityId: LEGAL, siteId: SITE });
    expect(statusQueryOf('admin', { legalEntityId: LEGAL, siteId: '' })).toEqual({
      legalEntityId: LEGAL,
    });
    expect(statusQueryOf('admin', { legalEntityId: '', siteId: '' })).toEqual({});
    expect(statusQueryOf('team', filters)).toEqual({});
  });

  it('maps problems to stable kinds and never shows a parameter', () => {
    const response = (code: number) => new Response(null, { status: code });
    expect(
      statusFailureOf('admin', response(404), problem('OPERATIONAL_STATUS_SCOPE_NOT_FOUND', 404)),
    ).toMatchObject({
      kind: 'scopeNotFound',
      messageKey: 'errors.OPERATIONAL_STATUS_SCOPE_NOT_FOUND',
    });
    expect(statusFailureOf('team', response(403), problem('ACCESS_DENIED', 403))).toMatchObject({
      kind: 'forbidden',
      messageKey: 'teamOperations.unauthorized',
    });
    expect(statusFailureOf('admin', response(403), problem('ACCESS_DENIED', 403))).toMatchObject({
      kind: 'forbidden',
      messageKey: 'operationsStatus.unauthorized',
    });
    expect(
      statusFailureOf('team', response(403), problem('EMPLOYEE_LINK_REQUIRED', 403)),
    ).toMatchObject({ kind: 'linkRequired' });
    expect(statusFailureOf('admin', response(429), problem('RATE_LIMITED', 429))).toMatchObject({
      kind: 'rateLimited',
    });
    expect(statusFailureOf('admin', response(500), problem('X', 500))).toMatchObject({
      kind: 'general',
    });
  });
});

describe.each(['en', 'fr'] as const)('MVP-100 operational status (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.operationsStatus;

  it('shows the organization’s counts with their instant, thresholds and neutral wording', async () => {
    const requests = stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
      }
      if (request.path.startsWith('/sites')) {
        return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
      }
      return { status: 200, body: status() };
    });
    const { container } = await renderWithSession(
      <OperationsStatusPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/operations',
    );
    expect(await screen.findByRole('heading', { level: 1, name: k.title })).toBeVisible();
    const shifts = await screen.findByTestId('status-shifts');
    expect(within(shifts).getByRole('heading', { level: 2, name: k.shifts.title })).toBeVisible();
    const value = (id: string) => within(screen.getByTestId(id)).getByRole('definition');
    expect(value('tile-scheduled')).toHaveTextContent('12');
    expect(value('tile-expected')).toHaveTextContent('2');
    expect(value('tile-not-checked-in')).toHaveTextContent('3');
    expect(value('tile-present')).toHaveTextContent('4');
    expect(value('tile-completed')).toHaveTextContent('3');
    expect(value('tile-uncovered')).toHaveTextContent('1');
    // Late is a labelled subset, and the partition is explained with the server's grace.
    expect(screen.getByTestId('tile-late')).toHaveTextContent(
      k.shifts.late_other.replace('{{value}}', '2').replace('{{minutes}}', '5'),
    );
    expect(shifts).toHaveTextContent(k.shifts.explanation.replace('{{grace}}', '30'));
    expect(screen.getByTestId('tile-not-checked-in')).toHaveTextContent(k.shifts.notCheckedIn);
    // Approvals: pending, within threshold and overdue, with the printed thresholds.
    expect(screen.getByTestId('tile-leave-pending')).toHaveTextContent('5');
    expect(screen.getByTestId('tile-leave-within')).toHaveTextContent('3');
    expect(screen.getByTestId('tile-leave-overdue')).toHaveTextContent('2');
    expect(screen.getByTestId('tile-leave-within')).toHaveTextContent(
      k.approvals.within.replace('{{value}}', '3'),
    );
    expect(screen.getByTestId('tile-timesheets-within')).toHaveTextContent('3');
    expect(screen.getByTestId('status-thresholds')).toHaveTextContent(
      k.approvals.thresholds.replace('{{leave}}', '48').replace('{{timesheets}}', '72'),
    );
    expect(screen.getByTestId('operations-status-asof')).toBeVisible();
    expect(screen.getByTestId('operations-status-disclaimer')).toHaveTextContent(k.disclaimer);
    // Never an absence conclusion, in any wording of the page.
    expect(container.textContent).not.toMatch(/\babsen(t|ce)|Pointage absent/iu);
    // Details: names only for shifts not checked in; uncovered shifts carry no person; truncation.
    const unchecked = screen.getByTestId('details-not-checked-in');
    expect(within(unchecked).getByText('Ana Kalala (E-0042)')).toBeVisible();
    expect(within(screen.getByTestId('details-uncovered')).queryByText(/Kalala/u)).toBeNull();
    expect(screen.getByTestId('details-uncovered-truncated')).toHaveTextContent(
      k.details.truncated.replace('{{limit}}', '20'),
    );
    expect(screen.queryByTestId('details-overdue-leave')).toBeNull();
    // Drill-down: existing routes only.
    const links = within(screen.getByTestId('status-drill-down')).getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/admin/attendance-review',
      '/admin/work-schedules',
      '/admin/leave-approvals',
      '/admin/timesheets',
    ]);
    // Read-only: no decision control, no URL parameters, nothing kept in browser storage.
    expect(screen.queryByRole('button', { name: /approve|approuv|decline|refus/iu })).toBeNull();
    expect(requests.filter((r) => r.path.startsWith('/operations/status'))).toEqual([
      { method: 'GET', path: '/operations/status' },
    ]);
    expect(stored()).not.toMatch(/Kalala|Cashier|Caissi|E-0042|66666666|2099-11|operations/u);
    await expectAccessible(container);
  });

  it('filters by legal entity and site, refreshes on request and shows a hidden scope neutrally', async () => {
    const requests = stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
      }
      if (request.path.startsWith('/sites')) {
        return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
      }
      if (request.path.includes(`siteId=${SITE}`)) {
        return { status: 404, body: problem('OPERATIONAL_STATUS_SCOPE_NOT_FOUND', 404) };
      }
      return { status: 200, body: status() };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <OperationsStatusPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/operations',
    );
    await screen.findByTestId('status-shifts');
    const statusCalls = () => requests.filter((r) => r.path.startsWith('/operations/status'));
    expect(screen.getByLabelText(k.filters.site)).toBeDisabled();

    await user.click(screen.getByTestId('operations-status-refresh'));
    await waitFor(() => {
      expect(statusCalls()).toHaveLength(2);
    });
    await screen.findByTestId('status-shifts');

    await user.selectOptions(await screen.findByLabelText(k.filters.legalEntity), LEGAL);
    await user.click(screen.getByTestId('operations-status-apply'));
    await waitFor(() => {
      expect(statusCalls().at(-1)?.path).toBe(`/operations/status?legalEntityId=${LEGAL}`);
    });
    await screen.findByTestId('status-shifts');

    await user.selectOptions(await screen.findByLabelText(k.filters.site), SITE);
    await user.click(screen.getByTestId('operations-status-apply'));
    const error = await screen.findByTestId('operations-status-error');
    expect(error).toHaveAttribute('data-kind', 'scopeNotFound');
    expect(error).toHaveTextContent(c.errors.OPERATIONAL_STATUS_SCOPE_NOT_FOUND);
    expect(statusCalls().at(-1)?.path).toBe(
      `/operations/status?legalEntityId=${LEGAL}&siteId=${SITE}`,
    );
    // No retry for a hidden scope; clearing the filters reads the whole organization again.
    expect(within(error).queryByRole('button')).toBeNull();
    await user.click(screen.getByTestId('operations-status-reset'));
    await screen.findByTestId('status-shifts');
    expect(statusCalls().at(-1)?.path).toBe('/operations/status');
  });

  it('shows a manager’s own team with overdue people, no uncovered notion and her routes', async () => {
    const requests = stubApi(() => ({ status: 200, body: teamStatus() }));
    const { container } = await renderWithSession(
      <TeamOperationsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/team-operations',
    );
    expect(
      await screen.findByRole('heading', { level: 1, name: c.teamOperations.title }),
    ).toBeVisible();
    await screen.findByTestId('status-shifts');
    expect(screen.queryByTestId('tile-uncovered')).toBeNull();
    expect(screen.queryByTestId('details-uncovered')).toBeNull();
    expect(screen.queryByTestId('operations-status-filters')).toBeNull();
    const leave = within(screen.getByTestId('details-overdue-leave'));
    expect(leave.getByText('Ana Kalala (E-0042)')).toBeVisible();
    const timesheet = within(screen.getByTestId('details-overdue-timesheets'));
    expect(timesheet.getByText('Ana Kalala (E-0042)')).toBeVisible();
    expect(screen.getByTestId('details-overdue-timesheets-truncated')).toBeVisible();
    expect(screen.getByTestId('status-approvals')).toHaveTextContent(k.approvals.scope.team);
    const links = within(screen.getByTestId('status-drill-down')).getAllByRole('link');
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/me/team-attendance',
      '/me/schedule',
      '/me/leave/approvals',
      '/me/time-approvals',
    ]);
    expect(requests.map((request) => request.path)).toEqual(['/me/team-operations/status']);
    expect(stored()).not.toMatch(/Kalala|Cashier|Caissi|E-0042|66666666|2099-11|operations/u);
    await expectAccessible(container);
  });

  it('shows zeros, a missing link and an unauthorized caller without detail', async () => {
    stubApi(() => ({
      status: 200,
      body: status({
        shifts: { scheduled: 0, expected: 0, notCheckedIn: 0, present: 0, completed: 0, late: 0 },
        approvals: { leave: { pending: 0, overdue: 0 }, timesheets: { pending: 0, overdue: 0 } },
        coverage: null,
        details: {
          notCheckedIn: { items: [], truncated: false },
          uncovered: null,
          overdueLeave: { items: [], truncated: false },
          overdueTimesheets: { items: [], truncated: false },
        },
      }),
    }));
    await renderWithSession(
      <TeamOperationsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/team-operations',
    );
    await screen.findByTestId('status-shifts');
    expect(within(screen.getByTestId('tile-scheduled')).getByRole('definition')).toHaveTextContent(
      '0',
    );
    expect(
      within(screen.getByTestId('details-not-checked-in')).getByText(k.details.none),
    ).toBeVisible();
  });

  it('explains a missing employee link and a refused caller, with a reference', async () => {
    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await renderWithSession(
      <TeamOperationsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/team-operations',
    );
    const error = await screen.findByTestId('operations-status-error');
    expect(error).toHaveAttribute('data-kind', 'linkRequired');
    expect(error).toHaveTextContent(c.errors.EMPLOYEE_LINK_REQUIRED);
    expect(error).toHaveTextContent('corr-ops-1234');
    expect(within(error).queryByRole('button')).toBeNull();
  });
});

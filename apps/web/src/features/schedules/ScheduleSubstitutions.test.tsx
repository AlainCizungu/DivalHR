import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MySchedulePage } from './MySchedulePage';
import { ScheduleSubstitutionApprovalsPage } from './ScheduleSubstitutionApprovalsPage';
import { WorkSchedulesPage } from './WorkSchedulesPage';
import {
  candidateQueryOf,
  decisionBodyOf,
  decisionProblemsOf,
  localNow,
  pendingSlotsOf,
  requestable,
  substitutionFailureOf,
} from './substitutions';

const SITE = '33333333-0000-4000-8000-000000000003';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const REQUEST = '77777777-0000-4000-8000-000000000007';
const BEN = {
  id: 'bbbbbbbb-0000-4000-8000-0000000000b1',
  employeeNumber: 'E-0002',
  givenNames: 'Ben',
  familyName: '<b>Ilunga</b>',
};

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-substitution-1234',
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

/** Everything kept in browser storage (only interface preferences may be). */
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

const shiftOf = (overrides: Record<string, unknown> = {}) => ({
  id: SLOT,
  scheduleId: SCHEDULE,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  start: '2099-11-02T08:00',
  end: '2099-11-02T16:00',
  siteTimezone: 'Africa/Kinshasa',
  overnight: false,
  labels: { en: 'Cashier', fr: 'Caissière' },
  assignmentSource: 'PUBLISHED',
  ...overrides,
});

const substitutionShift = {
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
};

const historyItem = (overrides: Record<string, unknown> = {}) => ({
  id: REQUEST,
  requestedAt: '2026-10-11T08:00:00Z',
  state: 'PENDING',
  shift: substitutionShift,
  reasonLocale: 'fr',
  reason: 'Formation obligatoire',
  decision: null,
  ...overrides,
});

describe('MVP-051 substitution rules in the browser', () => {
  it('offers a request only for my own published shift not yet started without a pending one', () => {
    const now = new Date('2026-11-02T06:30:00Z'); // 07:30 in Kinshasa.
    expect(localNow('Africa/Kinshasa', now)).toBe('2026-11-02T07:30');
    const shift = shiftOf({ start: '2026-11-02T08:00' }) as never;
    expect(requestable(shift, new Set(), now)).toBe(true);
    expect(requestable(shift, new Set([SLOT]), now)).toBe(false);
    expect(
      requestable(
        { ...(shift as object), assignmentSource: 'SUBSTITUTION' } as never,
        new Set(),
        now,
      ),
    ).toBe(false);
    expect(requestable(shift, new Set(), new Date('2026-11-02T07:00:00Z'))).toBe(false);
    expect(
      pendingSlotsOf([
        historyItem() as never,
        historyItem({
          id: 'x',
          state: 'REJECTED',
          shift: { ...substitutionShift, slotId: 'other' },
        }) as never,
      ]),
    ).toEqual(new Set([SLOT]));
  });

  it('builds decisions without a substitute for a rejection and requires one for an approval', () => {
    expect(
      decisionProblemsOf({
        decision: 'APPROVED',
        substitute: null,
        reasonLocale: 'fr',
        reason: 'x',
      }),
    ).toEqual(new Set(['substitute', 'reason']));
    expect(
      decisionBodyOf({
        decision: 'REJECTED',
        substitute: BEN,
        reasonLocale: 'en',
        reason: '  No  ',
      }),
    ).toEqual({ decision: 'REJECTED', reasonLocale: 'en', reason: 'No' });
    expect(
      decisionBodyOf({ decision: 'APPROVED', substitute: BEN, reasonLocale: 'fr', reason: 'Oui' }),
    ).toEqual({
      decision: 'APPROVED',
      substituteEmployeeId: BEN.id,
      reasonLocale: 'fr',
      reason: 'Oui',
    });
    expect(candidateQueryOf(' a ')).toBeNull();
    expect(candidateQueryOf(' Ben ')).toBe('Ben');
  });

  it('keeps only allow-listed problem details', () => {
    const response = new Response(null, { status: 409 });
    expect(
      substitutionFailureOf(
        response,
        problem('WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE', 409, { reason: 'NO_MANAGER' }),
      ).messageKey,
    ).toBe('scheduleSubstitutions.notAvailable.NO_MANAGER');
    expect(
      substitutionFailureOf(
        response,
        problem('WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE', 409, { reason: '<script>' }),
      ).messageKey,
    ).toBe('errors.WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE');
    const invalid = substitutionFailureOf(
      new Response(null, { status: 400 }),
      problem('VALIDATION_FAILED', 400, {
        fields: [
          { field: 'substituteEmployeeId', constraint: 'FORMAT' },
          { field: 'secret', constraint: 'FORMAT' },
        ],
      }),
    );
    expect(invalid.fields).toEqual(['substitute']);
  });
});

describe.each(['fr', 'en'] as const)('MVP-051 my schedule substitutions (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.scheduleSubstitutions;
  const show = () =>
    renderWithSession(<MySchedulePage />, sessionWithRoles(['employee']), locale, '/me/schedule');

  it('requests a substitute with a reviewed reason and lists the request', async () => {
    let history: unknown[] = [];
    const requests = stubApi((request) => {
      if (request.method === 'POST') {
        const created = historyItem({ reasonLocale: locale, reason: 'Training day' });
        history = [created];
        return { status: 201, body: created };
      }
      if (request.path.startsWith('/me/work-schedule-substitutions')) {
        return {
          status: 200,
          body: { items: history, nextCursor: null, asOfInstant: '2026-10-11T08:00:00Z' },
        };
      }
      return {
        status: 200,
        body: {
          items: [
            shiftOf(),
            shiftOf({ id: 'given', assignmentSource: 'SUBSTITUTION', start: '2099-11-03T08:00' }),
          ],
          nextCursor: null,
          asOfInstant: '2026-10-11T08:00:00Z',
          from: null,
          to: null,
        },
      };
    });
    const user = userEvent.setup();
    const { container } = await show();
    const shifts = await screen.findAllByTestId('my-shift');
    // A shift given to me by a substitution carries the badge and offers no request.
    expect(within(shifts[1]!).getByTestId('my-shift-substitution')).toHaveTextContent(k.badge);
    expect(within(shifts[1]!).queryByTestId('request-substitute')).toBeNull();
    expect(await screen.findByTestId('my-substitutions-empty')).toHaveTextContent(k.mine.empty);
    await expectAccessible(container);

    await user.click(within(shifts[0]!).getByTestId('request-substitute'));
    const dialog = await screen.findByTestId('substitution-request-dialog');
    expect(within(dialog).getByTestId('substitution-request-shift')).toHaveTextContent(
      locale === 'fr' ? 'Caissière' : 'Cashier',
    );
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    expect(await within(dialog).findByTestId('substitution-request-problems')).toHaveTextContent(
      k.problems.reason,
    );
    await user.type(within(dialog).getByLabelText(k.form.reason), '  Training day ');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    const confirm = await within(dialog).findByTestId('substitution-request-confirm');
    expect(within(confirm).getByTestId('substitution-request-reason')).toHaveTextContent(
      'Training day',
    );
    await expectAccessible(container);
    await user.click(within(confirm).getByRole('button', { name: k.request.confirmButton }));
    await waitFor(() => {
      expect(screen.getByTestId('my-schedule-announcer')).toHaveTextContent(k.request.sent);
    });
    const post = requests.find((r) => r.method === 'POST')!;
    expect(post.path).toBe('/me/work-schedule-substitutions');
    expect(post.key).toMatch(/^web-/u);
    const sent = JSON.parse(post.body) as Record<string, unknown>;
    expect(Object.keys(sent).sort()).toEqual(['reason', 'reasonLocale', 'slotId']);
    expect(sent).toEqual({ slotId: SLOT, reasonLocale: locale, reason: 'Training day' });
    // The new request is listed; the shift shows it is pending and offers no second request.
    const listed = await screen.findByTestId('my-substitution');
    expect(within(listed).getByTestId('substitution-state')).toHaveTextContent(k.states.PENDING);
    const first = screen.getAllByTestId('my-shift')[0]!;
    expect(within(first).getByTestId('my-shift-pending')).toBeInTheDocument();
    expect(within(first).queryByTestId('request-substitute')).toBeNull();
    // Nothing is kept in the browser.
    expect(stored()).not.toMatch(/Training|Ben|available/u);
  });

  it('shows decisions with the substitute as plain text and explains voided and expired requests', async () => {
    stubApi((request) =>
      request.path.startsWith('/me/work-schedule-substitutions')
        ? {
            status: 200,
            body: {
              items: [
                historyItem({
                  state: 'APPROVED',
                  decision: {
                    outcome: 'APPROVED',
                    decidedAt: '2026-10-12T08:00:00Z',
                    reasonLocale: 'en',
                    reason: 'Ben covers it',
                    substitute: BEN,
                  },
                }),
                historyItem({ id: 'v', state: 'VOIDED' }),
                historyItem({ id: 'e', state: 'EXPIRED' }),
              ],
              nextCursor: null,
              asOfInstant: '2026-10-11T08:00:00Z',
            },
          }
        : {
            status: 200,
            body: { items: [], nextCursor: null, asOfInstant: null, from: null, to: null },
          },
    );
    const { container } = await show();
    const items = await screen.findAllByTestId('my-substitution');
    expect(within(items[0]!).getByTestId('substitution-state')).toHaveTextContent(
      k.states.APPROVED,
    );
    expect(within(items[0]!).getByTestId('my-substitution-substitute')).toHaveTextContent(
      '<b>Ilunga</b>',
    );
    expect(within(items[0]!).getByText('Ben covers it')).toHaveAttribute('lang', 'en');
    expect(items[1]).toHaveTextContent(k.mine.voided);
    expect(items[2]).toHaveTextContent(k.mine.expired);
    await expectAccessible(container);
  });

  it('explains a refused request and refreshes', async () => {
    stubApi((request) => {
      if (request.method === 'POST') {
        return {
          status: 409,
          body: problem('WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE', 409, {
            reason: 'NO_MANAGER',
          }),
        };
      }
      return request.path.startsWith('/me/work-schedule-substitutions')
        ? {
            status: 200,
            body: { items: [], nextCursor: null, asOfInstant: '2026-10-11T08:00:00Z' },
          }
        : {
            status: 200,
            body: { items: [shiftOf()], nextCursor: null, asOfInstant: null, from: null, to: null },
          };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('request-substitute'));
    const dialog = await screen.findByTestId('substitution-request-dialog');
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Formation');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    await user.click(within(dialog).getByRole('button', { name: k.request.confirmButton }));
    const alert = await within(dialog).findByTestId('substitution-request-error');
    expect(alert).toHaveTextContent(k.notAvailable.NO_MANAGER);
    expect(within(dialog).getByRole('button', { name: k.request.confirmButton })).toBeDisabled();
  });
});

describe.each(['fr', 'en'] as const)('MVP-051 manager substitutions (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.scheduleSubstitutions;
  const show = () =>
    renderWithSession(
      <ScheduleSubstitutionApprovalsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/schedule/substitutions',
    );
  const approval = {
    id: REQUEST,
    requestedAt: '2026-10-11T08:00:00Z',
    employee: { employeeNumber: 'E-0001', givenNames: 'Ana', familyName: '<i>Kalala</i>' },
    reasonLocale: 'fr',
    reason: 'Formation obligatoire',
    shift: substitutionShift,
  };

  const managerApi = (decision: (request: Recorded) => Reply) =>
    stubApi((request) => {
      if (request.method === 'POST') return decision(request);
      if (request.path.includes('/candidates')) {
        return { status: 200, body: { items: [BEN], nextCursor: null } };
      }
      return {
        status: 200,
        body: { items: [approval], nextCursor: null, asOfInstant: '2026-10-11T08:00:00Z' },
      };
    });

  it('approves with a searched eligible substitute after confirming both employees', async () => {
    const requests = managerApi(() => ({
      status: 200,
      body: {
        requestId: REQUEST,
        decisionId: 'd',
        state: 'APPROVED',
        decidedAt: '2026-10-12T08:00:00Z',
      },
    }));
    const user = userEvent.setup();
    const { container } = await show();
    const item = await screen.findByTestId('substitution-item');
    expect(item).toHaveTextContent('<i>Kalala</i>');
    expect(within(item).getByTestId('substitution-item-reason')).toHaveAttribute('lang', 'fr');
    await expectAccessible(container);
    await user.click(
      within(item).getByRole('button', { name: new RegExp(k.approvals.approve, 'u') }),
    );
    const dialog = await screen.findByTestId('substitution-decision-dialog');
    // The search needs two characters; nothing is requested before.
    await user.type(within(dialog).getByLabelText(k.approvals.picker.find), 'B');
    await user.click(within(dialog).getByRole('button', { name: k.approvals.picker.search }));
    expect(within(dialog).getByText(k.approvals.picker.short)).toBeInTheDocument();
    expect(requests.some((r) => r.path.includes('/candidates'))).toBe(false);
    await user.type(within(dialog).getByLabelText(k.approvals.picker.find), 'en');
    await user.click(within(dialog).getByRole('button', { name: k.approvals.picker.search }));
    const option = await within(dialog).findByTestId('substitute-option');
    const search = requests.find((r) => r.path.includes('/candidates'))!;
    expect(search.path).toBe(
      `/me/work-schedule-substitution-approvals/${REQUEST}/candidates?query=Ben`,
    );
    await user.click(option);
    expect(within(dialog).getByTestId('substitution-replacement')).toHaveTextContent(
      '<b>Ilunga</b>',
    );
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Ben is available');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    const confirm = await within(dialog).findByTestId('substitution-decision-confirm');
    expect(confirm).toHaveTextContent('<i>Kalala</i>');
    expect(confirm).toHaveTextContent('<b>Ilunga</b>');
    await expectAccessible(container);
    await user.click(
      within(confirm).getByRole('button', { name: k.approvals.confirmButton.APPROVED }),
    );
    await waitFor(() => {
      expect(screen.queryByTestId('substitution-item')).toBeNull();
    });
    const post = requests.find((r) => r.method === 'POST')!;
    expect(post.path).toBe(`/me/work-schedule-substitution-approvals/${REQUEST}/decision`);
    expect(post.key).toMatch(/^web-/u);
    expect(JSON.parse(post.body)).toEqual({
      decision: 'APPROVED',
      substituteEmployeeId: BEN.id,
      reasonLocale: locale,
      reason: 'Ben is available',
    });
    expect(stored()).not.toMatch(/Training|Ben|available/u);
  });

  it('rejects with a reason and no substitute, and returns to the search when one is refused', async () => {
    let refuse = true;
    const requests = managerApi((request) => {
      const body = JSON.parse(request.body) as { decision: string };
      if (body.decision === 'APPROVED' && refuse) {
        refuse = false;
        return { status: 422, body: problem('WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE', 422) };
      }
      return {
        status: 200,
        body: {
          requestId: REQUEST,
          decisionId: 'd',
          state: body.decision,
          decidedAt: '2026-10-12T08:00:00Z',
        },
      };
    });
    const user = userEvent.setup();
    await show();
    let item = await screen.findByTestId('substitution-item');
    await user.click(
      within(item).getByRole('button', { name: new RegExp(k.approvals.approve, 'u') }),
    );
    let dialog = await screen.findByTestId('substitution-decision-dialog');
    await user.type(within(dialog).getByLabelText(k.approvals.picker.find), 'Ben');
    await user.click(within(dialog).getByRole('button', { name: k.approvals.picker.search }));
    await user.click(await within(dialog).findByTestId('substitute-option'));
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Ben is available');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    await user.click(
      within(dialog).getByRole('button', { name: k.approvals.confirmButton.APPROVED }),
    );
    expect(await within(dialog).findByTestId('substitution-decision-error')).toHaveTextContent(
      c.errors.WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE,
    );
    // Back on the form, with the refused substitute cleared.
    expect(within(dialog).queryByTestId('substitute-selected')).toBeNull();
    expect(within(dialog).getByLabelText(k.approvals.picker.find)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: k.form.cancel }));

    item = await screen.findByTestId('substitution-item');
    await user.click(
      within(item).getByRole('button', { name: new RegExp(k.approvals.reject, 'u') }),
    );
    dialog = await screen.findByTestId('substitution-decision-dialog');
    expect(within(dialog).queryByTestId('substitute-picker')).toBeNull();
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Not this week');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    await user.click(
      within(dialog).getByRole('button', { name: k.approvals.confirmButton.REJECTED }),
    );
    await waitFor(() => {
      expect(screen.queryByTestId('substitution-item')).toBeNull();
    });
    const rejection = requests.filter((r) => r.method === 'POST').at(-1)!;
    expect(JSON.parse(rejection.body)).toEqual({
      decision: 'REJECTED',
      reasonLocale: locale,
      reason: 'Not this week',
    });
  });

  it('shows the empty inbox and the link-required refusal', async () => {
    stubApi(() => ({
      status: 200,
      body: { items: [], nextCursor: null, asOfInstant: '2026-10-11T08:00:00Z' },
    }));
    const { container } = await show();
    expect(await screen.findByTestId('substitution-inbox-empty')).toHaveTextContent(
      k.approvals.empty,
    );
    await expectAccessible(container);
    vi.unstubAllGlobals();
    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await show();
    const alerts = await screen.findAllByTestId('substitution-inbox-error');
    expect(alerts.at(-1)).toHaveTextContent(c.errors.EMPLOYEE_LINK_REQUIRED);
  });
});

describe.each(['fr', 'en'] as const)('MVP-051 administrator detail (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.workSchedules;
  it('shows the published employee beside the current coverage and the approval', async () => {
    const ana = { id: 'a', employeeNumber: 'E-0001', givenNames: 'Ana', familyName: 'Kalala' };
    const detail = {
      id: SCHEDULE,
      site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
      firstDate: '2099-11-02',
      lastDate: '2099-11-02',
      siteTimezone: 'Africa/Kinshasa',
      totals: { slots: 1, assigned: 1, uncovered: 0 },
      publishedAt: '2026-10-11T10:00:00Z',
      status: 'PUBLISHED',
      changedAt: null,
      changeReason: null,
      replacesScheduleId: null,
      replacedByScheduleId: null,
      slots: [
        {
          id: SLOT,
          position: 1,
          start: '2099-11-02T08:00',
          end: '2099-11-02T16:00',
          startAt: '2099-11-02T07:00:00Z',
          endAt: '2099-11-02T15:00:00Z',
          overnight: false,
          labels: { en: 'Cashier', fr: 'Caissière' },
          employee: ana,
          effectiveEmployee: BEN,
          substitution: { requestId: REQUEST, decisionId: 'd', approvedAt: '2026-10-12T08:00:00Z' },
        },
      ],
    };
    stubApi((request) => {
      if (request.path.startsWith(`/work-schedules/${SCHEDULE}`))
        return { status: 200, body: detail };
      if (request.path.startsWith('/work-schedules')) {
        const summary = { ...detail, slots: undefined };
        return { status: 200, body: { items: [summary], nextCursor: null, asOf: '2026-10-11' } };
      }
      return { status: 200, body: { data: [] } };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <WorkSchedulesPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/work-schedules',
    );
    const rows = await screen.findAllByTestId('schedule-row');
    await user.click(
      within(rows[0]!).getByRole('button', { name: new RegExp(k.actions.view, 'u') }),
    );
    const slot = (await screen.findAllByTestId('detail-slot'))[0]!;
    expect(slot).toHaveTextContent('Kalala');
    expect(within(slot).getByTestId('slot-effective')).toHaveTextContent('<b>Ilunga</b>');
    expect(within(slot).getByTestId('slot-substituted')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: k.columns.effective })).toBeInTheDocument();
    await expectAccessible(container);
  });
});

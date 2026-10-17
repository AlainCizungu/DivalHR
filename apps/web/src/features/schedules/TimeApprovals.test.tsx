import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MyTimesheetsPage } from './MyTimesheetsPage';
import { TimeApprovalsPage } from './TimeApprovalsPage';
import { TimesheetsPage } from './TimesheetsPage';
import { reasonProblemOf, timesheetFailureOf } from './timesheets';

const SITE = '33333333-0000-4000-8000-000000000003';
const SHEET = '55555555-0000-4000-8000-000000000005';
const ACTION = '66666666-0000-4000-8000-000000000006';
const RETURN_ACTION = '67676767-0000-4000-8000-000000000067';
const MANAGER = '99999999-0000-4000-8000-000000000099';
const REASON = 'Le lundi doit être corrigé avec la pause déclarée.';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-ta-1234',
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
  weekStart: '2099-11-02',
  weekEnd: '2099-11-09',
  lastDay: '2099-11-08',
  periodStartAt: '2099-11-01T23:00:00Z',
  periodEndAt: '2099-11-08T23:00:00Z',
};

const clean = {
  rows: 2,
  readyRows: 2,
  missingRows: 0,
  openRows: 0,
  changedRows: 0,
  blockedRows: 0,
  workedMinutes: 948,
};

const row = (day: string) => ({
  labels: { en: 'Cashier', fr: 'Caissière' },
  scheduledStartAt: `2099-11-0${day}T07:00:00Z`,
  scheduledEndAt: `2099-11-0${day}T15:00:00Z`,
  state: 'READY',
  checkedInAt: `2099-11-0${day}T07:05:00Z`,
  checkedOutAt: `2099-11-0${day}T14:59:00Z`,
  corrected: false,
  workedMinutes: 474,
});

const submittedAction = {
  action: 'SUBMITTED',
  cycle: 1,
  revision: 1,
  workedMinutes: 948,
  returnReason: null,
  occurredAt: '2099-11-08T11:00:00Z',
};

const returnedAction = {
  action: 'RETURNED',
  cycle: 1,
  revision: 1,
  workedMinutes: 948,
  returnReason: REASON,
  occurredAt: '2099-11-08T12:00:00Z',
};

const mine = (overrides: Record<string, unknown> = {}) => ({
  timesheetId: SHEET,
  period,
  status: 'DRAFT',
  revision: 1,
  generationVersion: 3,
  submissionCycle: 0,
  totals: clean,
  updatedAt: '2099-11-08T11:00:00Z',
  rows: [row('2'), row('3')],
  submission: { eligible: true, blockers: [] },
  actions: [],
  ...overrides,
});

const item = (overrides: Record<string, unknown> = {}) => ({
  timesheetId: SHEET,
  period,
  status: 'DRAFT',
  revision: 1,
  submissionCycle: 0,
  totals: clean,
  updatedAt: '2099-11-08T11:00:00Z',
  ...overrides,
});

const ANA = { employeeNumber: 'E-0042', givenNames: 'Ana', familyName: 'Kalala' };

const queueItem = {
  timesheetId: SHEET,
  employee: ANA,
  period,
  revision: 1,
  cycle: 1,
  rowCount: 2,
  workedMinutes: 948,
  submittedAt: '2099-11-08T11:00:00Z',
};

const approval = {
  timesheetId: SHEET,
  employee: ANA,
  period,
  status: 'SUBMITTED',
  revision: 1,
  generationVersion: 3,
  cycle: 1,
  submissionActionId: ACTION,
  submittedAt: '2099-11-08T11:00:00Z',
  totals: clean,
  rows: [row('2'), row('3')],
  actions: [{ ...submittedAction, actionId: ACTION, generationVersion: 3, rowCount: 2 }],
};

const en = resources.en.common;
const fr = resources.fr.common;

describe('time approval helpers', () => {
  it('checks a return reason as the server does, and maps the new codes', () => {
    expect(reasonProblemOf('')).toBe('required');
    expect(reasonProblemOf('   ')).toBe('required');
    expect(reasonProblemOf('Trop bref')).toBe('length');
    expect(reasonProblemOf('x'.repeat(501))).toBe('length');
    expect(reasonProblemOf('Voir https://exemple.test ici')).toBe('format');
    expect(reasonProblemOf('Balise <b> ici dans le texte')).toBe('format');
    expect(reasonProblemOf('Caractère​ invisible ici')).toBe('format');
    expect(reasonProblemOf(`  ${REASON}  `)).toBeNull();
    const response = (status: number) => new Response(null, { status });
    expect(
      timesheetFailureOf('self', response(409), problem('TIMESHEET_SUBMISSION_NOT_ALLOWED', 409)),
    ).toMatchObject({ kind: 'notAllowed' });
    expect(
      timesheetFailureOf(
        'self',
        response(409),
        problem('TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE', 409),
      ),
    ).toMatchObject({
      kind: 'route',
      messageKey: 'errors.TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE',
    });
    expect(
      timesheetFailureOf('manager', response(409), problem('TIMESHEET_DECISION_STALE', 409)),
    ).toMatchObject({ kind: 'stale' });
    expect(
      timesheetFailureOf('manager', response(409), problem('TIMESHEET_DECISION_NOT_ALLOWED', 409)),
    ).toMatchObject({ kind: 'decided' });
    expect(
      timesheetFailureOf(
        'manager',
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [{ field: 'reason', constraint: 'FORMAT' }, { field: 'secret' }],
        }),
      ),
    ).toEqual({
      kind: 'validation',
      messageKey: 'timeApprovals.validation',
      reason: true,
      correlationId: 'corr-ta-1234',
    });
    expect(
      timesheetFailureOf('manager', response(403), problem('ACCESS_DENIED', 403)),
    ).toMatchObject({ kind: 'forbidden', messageKey: 'timeApprovals.unauthorized' });
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('My timesheets: submission and history (%s)', (locale, c) => {
  const k = c.myTimesheets;

  it('submits a clean draft only after an explicit confirmation and never names the manager', async () => {
    let state: 'draft' | 'submitted' = 'draft';
    const requests = stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return {
          status: 200,
          body: {
            items: [state === 'draft' ? item() : item({ status: 'SUBMITTED', submissionCycle: 1 })],
            nextCursor: null,
          },
        };
      }
      if (request.path === `/me/timesheets/${SHEET}`) {
        return {
          status: 200,
          body:
            state === 'draft'
              ? mine()
              : mine({
                  status: 'SUBMITTED',
                  submissionCycle: 1,
                  submission: { eligible: false, blockers: [] },
                  actions: [submittedAction],
                }),
        };
      }
      if (request.path === `/me/timesheets/${SHEET}/submissions`) {
        state = 'submitted';
        return {
          status: 200,
          body: {
            timesheetId: SHEET,
            actionId: ACTION,
            action: 'SUBMITTED',
            status: 'SUBMITTED',
            cycle: 1,
            revision: 1,
            generationVersion: 3,
            occurredAt: '2099-11-08T11:00:00Z',
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
    await user.click(await screen.findByTestId('my-timesheet-view'));
    const opened = await screen.findByTestId('my-timesheet-detail');
    const section = await within(opened).findByTestId('my-timesheet-submission');
    expect(section).toHaveTextContent(k.submission.eligible);
    expect(within(opened).getByTestId('my-timesheet-history-none')).toHaveTextContent(
      c.timesheets.history.none,
    );
    await expectAccessible(container);

    // Keyboard: the submit button opens the confirmation; nothing is sent before the check.
    within(section).getByTestId('my-timesheet-submit').focus();
    await user.keyboard('{Enter}');
    const check = await within(opened).findByTestId('my-timesheet-confirm-check');
    await waitFor(() => expect(check).toHaveFocus());
    const confirm = within(opened).getByTestId('my-timesheet-submit-confirm');
    expect(confirm).toBeDisabled();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.keyboard(' ');
    expect(confirm).toBeEnabled();
    await expectAccessible(container);
    await user.click(confirm);

    await waitFor(() =>
      expect(screen.getByTestId('my-timesheets-announcer')).toHaveTextContent(
        k.submission.done.replace('{{cycle}}', '1'),
      ),
    );
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.body).toEqual({ revision: 1, generationVersion: 3, confirm: true });
    expect(posts[0]!.key).toMatch(/^web-/u);
    const after = await screen.findByTestId('my-timesheet-detail');
    expect(await within(after).findByTestId('my-timesheet-detail-status')).toHaveTextContent(
      c.timesheets.status.SUBMITTED,
    );
    expect(within(after).getByTestId('my-timesheet-awaiting')).toBeVisible();
    expect(within(after).queryByTestId('my-timesheet-submission')).toBeNull();
    const history = within(after).getAllByTestId('timesheet-action');
    expect(history.map((h) => h.dataset.action)).toEqual(['SUBMITTED']);
    expect(after.textContent).not.toMatch(/99999999|Mbuyi|Mia/u);
    await expectAccessible(container);
    expect(stored()).not.toMatch(/55555555|Cashier|Caissi|web-/u);
  });

  it('shows what blocks a draft and how to resolve it, without a submit button', async () => {
    stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return { status: 200, body: { items: [item()], nextCursor: null } };
      }
      return {
        status: 200,
        body: mine({
          submission: {
            eligible: false,
            blockers: ['OPEN_ATTENDANCE', 'NO_READY_ROWS', 'APPROVAL_ROUTE_UNAVAILABLE'],
          },
        }),
      };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MyTimesheetsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/timesheets',
    );
    await user.click(await screen.findByTestId('my-timesheet-view'));
    const blockers = await screen.findByTestId('my-timesheet-blockers');
    expect(
      within(blockers)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      k.submission.blockers.OPEN_ATTENDANCE,
      k.submission.blockers.NO_READY_ROWS,
      k.submission.blockers.APPROVAL_ROUTE_UNAVAILABLE,
    ]);
    expect(screen.getByTestId('my-timesheet-submission')).toHaveTextContent(k.submission.resolve);
    expect(screen.queryByTestId('my-timesheet-submit')).toBeNull();
    await expectAccessible(container);
  });

  it('shows a returned timesheet with its reason and the instruction, then resubmits', async () => {
    stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return { status: 200, body: { items: [item({ submissionCycle: 1 })], nextCursor: null } };
      }
      if (request.path === `/me/timesheets/${SHEET}/submissions`) {
        return { status: 409, body: problem('TIMESHEET_DECISION_STALE', 409) };
      }
      return {
        status: 200,
        body: mine({ submissionCycle: 1, actions: [returnedAction, submittedAction] }),
      };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MyTimesheetsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/timesheets',
    );
    await user.click(await screen.findByTestId('my-timesheet-view'));
    const returned = await screen.findByTestId('my-timesheet-returned');
    expect(returned).toHaveTextContent(k.returned.title);
    expect(within(returned).getByTestId('my-timesheet-returned-reason')).toHaveTextContent(REASON);
    expect(returned).toHaveTextContent(k.returned.instruction);
    const reasons = screen.getAllByTestId('timesheet-action-reason').map((r) => r.textContent);
    expect(reasons).toEqual([REASON, c.timesheets.history.noReason]);
    await expectAccessible(container);
    // A stale submission is refused with the server's message; nothing is guessed.
    await user.click(screen.getByTestId('my-timesheet-submit'));
    await user.click(screen.getByTestId('my-timesheet-confirm-check'));
    await user.click(screen.getByTestId('my-timesheet-submit-confirm'));
    const error = await screen.findByTestId('my-timesheet-submit-error');
    expect(error).toHaveTextContent(c.errors.TIMESHEET_DECISION_STALE);
    await waitFor(() => expect(error).toHaveFocus());
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('Time approvals (%s)', (locale, c) => {
  const k = c.timeApprovals;

  it('lists the submitted timesheets, approves one after confirmation and removes it', async () => {
    let decided = false;
    const requests = stubApi((request) => {
      if (request.path === '/me/time-approvals') {
        return {
          status: 200,
          body: { items: decided ? [] : [queueItem], nextCursor: null },
        };
      }
      if (request.path === `/me/time-approvals/${SHEET}`) {
        return { status: 200, body: approval };
      }
      if (request.path === `/me/time-approvals/${SHEET}/approval`) {
        decided = true;
        return {
          status: 200,
          body: {
            timesheetId: SHEET,
            actionId: RETURN_ACTION,
            action: 'APPROVED',
            status: 'APPROVED',
            cycle: 1,
            revision: 1,
            generationVersion: 3,
            occurredAt: '2099-11-09T08:00:00Z',
          },
        };
      }
      return { status: 404, body: problem('TIMESHEET_TARGET_NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimeApprovalsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/time-approvals',
    );
    expect(screen.getByRole('heading', { level: 1, name: k.title })).toBeVisible();
    const cards = await screen.findAllByTestId('time-approval');
    expect(cards).toHaveLength(1);
    expect(cards[0]).toHaveTextContent('Ana Kalala (E-0042)');
    expect(within(cards[0]!).getByTestId('time-approval-worked')).toHaveTextContent('15 h 48 min');
    await expectAccessible(container);

    await user.click(within(cards[0]!).getByTestId('time-approval-open'));
    const detail = await screen.findByTestId('time-approval-detail');
    expect(await within(detail).findByTestId('time-approval-status')).toHaveTextContent(
      c.timesheets.status.SUBMITTED,
    );
    expect(within(detail).getAllByTestId('timesheet-row')).toHaveLength(2);
    expect(within(detail).getAllByTestId('timesheet-action')).toHaveLength(1);
    await expectAccessible(container);

    await user.click(within(detail).getByTestId('time-approval-approve'));
    const check = await within(detail).findByTestId('time-approval-confirm-check');
    await waitFor(() => expect(check).toHaveFocus());
    const confirm = within(detail).getByTestId('time-approval-confirm');
    expect(confirm).toBeDisabled();
    await user.keyboard(' ');
    await user.click(confirm);
    await waitFor(() => expect(screen.getByTestId('time-approvals-empty')).toBeVisible());
    expect(screen.getByTestId('time-approvals-receipt')).toHaveTextContent(
      k.decision.approved.replace('{{name}}', 'Ana Kalala (E-0042)'),
    );
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/me/time-approvals/${SHEET}/approval`);
    expect(post?.body).toEqual({
      revision: 1,
      generationVersion: 3,
      cycle: 1,
      submissionActionId: ACTION,
      confirm: true,
    });
    await expectAccessible(container);
    expect(stored()).not.toMatch(/55555555|Kalala|web-/u);
  });

  it('returns a timesheet only with a valid reason and shows the server’s refusal', async () => {
    let attempts = 0;
    const requests = stubApi((request) => {
      if (request.path === '/me/time-approvals') {
        return { status: 200, body: { items: [queueItem], nextCursor: null } };
      }
      if (request.path === `/me/time-approvals/${SHEET}`) {
        return { status: 200, body: approval };
      }
      if (request.path === `/me/time-approvals/${SHEET}/return`) {
        attempts += 1;
        return attempts === 1
          ? { status: 409, body: problem('TIMESHEET_DECISION_STALE', 409) }
          : {
              status: 200,
              body: {
                timesheetId: SHEET,
                actionId: RETURN_ACTION,
                action: 'RETURNED',
                status: 'DRAFT',
                cycle: 1,
                revision: 1,
                generationVersion: 3,
                occurredAt: '2099-11-09T08:00:00Z',
              },
            };
      }
      return { status: 404, body: problem('TIMESHEET_TARGET_NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimeApprovalsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/time-approvals',
    );
    await user.click(await screen.findByTestId('time-approval-open'));
    const detail = await screen.findByTestId('time-approval-detail');
    await user.click(await within(detail).findByTestId('time-approval-return'));
    const reason = await within(detail).findByTestId('time-approval-reason');
    await waitFor(() => expect(reason).toHaveFocus());
    expect(within(detail).getByLabelText(k.decision.reason)).toBe(reason);
    await user.type(reason, 'Trop bref');
    await user.click(within(detail).getByTestId('time-approval-confirm-check'));
    await user.click(within(detail).getByTestId('time-approval-confirm'));
    expect(await within(detail).findByTestId('time-approval-reason-error')).toHaveTextContent(
      k.decision.problem.length,
    );
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await expectAccessible(container);

    await user.clear(reason);
    await user.type(reason, `  ${REASON}  `);
    await user.click(within(detail).getByTestId('time-approval-confirm'));
    const error = await within(detail).findByTestId('time-approval-error');
    expect(error).toHaveTextContent(c.errors.TIMESHEET_DECISION_STALE);
    await user.click(within(detail).getByTestId('time-approval-confirm'));
    await waitFor(() =>
      expect(screen.getByTestId('time-approvals-receipt')).toHaveTextContent(
        k.decision.returned.replace('{{name}}', 'Ana Kalala (E-0042)'),
      ),
    );
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[0]!.body?.reason).toBe(REASON);
    // A retry of the same reviewed decision reuses its key.
    expect(posts[1]!.key).toBe(posts[0]!.key);
    expect(stored()).not.toMatch(/pause|Kalala/u);
  });

  it('shows an empty queue, and the employee-link requirement without a retry', async () => {
    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await renderWithSession(
      <TimeApprovalsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/time-approvals',
    );
    const error = await screen.findByTestId('time-approvals-error');
    expect(error).toHaveTextContent(c.errors.EMPLOYEE_LINK_REQUIRED);
    expect(within(error).queryByRole('button')).toBeNull();
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('Timesheets: administrators read the state and history (%s)', (locale, c) => {
  it('shows each draft’s state and a timesheet’s history with the return reason', async () => {
    const generation = {
      generation: {
        generationId: '44444444-0000-4000-8000-000000000004',
        period,
        status: 'DRAFT',
        version: 1,
        generatedAt: '2099-11-08T11:00:00Z',
        refreshedAt: null,
        drafts: 1,
        totals: clean,
        periodLock: { state: 'OPEN', version: 0, currentActionId: null, updatedAt: null },
      },
      timesheets: [
        {
          timesheetId: SHEET,
          employee: { id: MANAGER, ...ANA },
          status: 'SUBMITTED',
          revision: 1,
          totals: clean,
          updatedAt: '2099-11-08T11:00:00Z',
        },
      ],
    };
    stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return {
          status: 200,
          body: {
            data: [{ id: '22222222-0000-4000-8000-000000000002', code: 'DRC', name: 'Dival RDC' }],
          },
        };
      }
      if (request.path.startsWith('/sites')) {
        return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
      }
      if (request.path === '/timesheets/generation-preview') {
        return {
          status: 200,
          body: {
            period,
            generation: generation.generation,
            drafts: 1,
            totals: clean,
            maxDrafts: 500,
            maxRows: 5000,
          },
        };
      }
      if (request.path.startsWith('/timesheets/generations/')) {
        if (request.path.endsWith('/refresh-preview')) {
          return { status: 409, body: problem('TIMESHEET_GENERATION_NOT_ALLOWED', 409) };
        }
        return { status: 200, body: generation };
      }
      if (request.path === `/timesheets/${SHEET}`) {
        return {
          status: 200,
          body: {
            timesheetId: SHEET,
            generationId: generation.generation.generationId,
            employee: { id: MANAGER, ...ANA },
            period,
            status: 'DRAFT',
            revision: 1,
            submissionCycle: 1,
            totals: clean,
            rows: [],
            revisions: [],
            actions: [
              { ...returnedAction, actionId: RETURN_ACTION, generationVersion: 1, rowCount: 2 },
              { ...submittedAction, actionId: ACTION, generationVersion: 1, rowCount: 2 },
            ],
          },
        };
      }
      return { status: 404, body: problem('TIMESHEET_TARGET_NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimesheetsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/timesheets',
    );
    const k = c.timesheets;
    const legal = screen.getByLabelText(k.form.legalEntity);
    await waitFor(() => {
      expect(within(legal).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(legal, '22222222-0000-4000-8000-000000000002');
    const site = screen.getByLabelText(k.form.site);
    await waitFor(() => {
      expect(within(site).getAllByRole('option')).toHaveLength(2);
    });
    await user.selectOptions(site, SITE);
    const week = screen.getByLabelText(k.form.week);
    await user.clear(week);
    await user.type(week, '2099-11-02');
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    await user.click(await screen.findByRole('button', { name: k.preview.open }));
    const status = await screen.findByTestId('timesheets-draft-status');
    expect(status).toHaveTextContent(k.status.SUBMITTED);
    await user.click(screen.getByTestId('timesheets-draft-view'));
    const detail = await screen.findByTestId('timesheets-detail');
    expect(within(detail).getByTestId('timesheets-detail-status')).toHaveTextContent(
      k.status.DRAFT,
    );
    expect(within(detail).getByTestId('timesheets-detail-cycle')).toHaveTextContent(
      k.detail.cycle.replace('{{cycle}}', '1'),
    );
    const actions = within(detail).getAllByTestId('timesheet-action');
    expect(actions.map((a) => a.dataset.action)).toEqual(['RETURNED', 'SUBMITTED']);
    expect(within(actions[0]!).getByTestId('timesheet-action-reason')).toHaveTextContent(REASON);
    // No decision is offered to an administrator.
    expect(
      within(detail).queryByRole('button', { name: c.timeApprovals.decision.approve }),
    ).toBeNull();
    await expectAccessible(container);
    // A week with submitted or approved time is never refreshed.
    await user.click(
      within(screen.getByTestId('timesheets-generation')).getByRole('button', {
        name: k.refresh.action,
      }),
    );
    expect(await screen.findByTestId('timesheets-error')).toHaveTextContent(k.refresh.locked);
  });
});

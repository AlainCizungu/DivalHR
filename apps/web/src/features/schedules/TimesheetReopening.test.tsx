import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MyTimesheetsPage } from './MyTimesheetsPage';
import { TimesheetsPage } from './TimesheetsPage';

const SITE = '33333333-0000-4000-8000-000000000003';
const SHEET = '55555555-0000-4000-8000-000000000005';
const ACTION = '66666666-0000-4000-8000-000000000006';
const MANAGER = '99999999-0000-4000-8000-000000000099';

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

const REOPEN_ACTION = '68686868-0000-4000-8000-000000000068';
const APPROVE_ACTION = '69696969-0000-4000-8000-000000000069';

const approvedAction = {
  action: 'APPROVED',
  cycle: 1,
  revision: 1,
  workedMinutes: 948,
  returnReason: null,
  occurredAt: '2099-11-08T12:00:00Z',
};

const reopenedAction = {
  action: 'REOPENED',
  cycle: 1,
  revision: 1,
  workedMinutes: 948,
  returnReason: null,
  occurredAt: '2099-11-09T08:30:00Z',
};

const en = resources.en.common;
const fr = resources.fr.common;

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('Reopened time, the employee (%s)', (locale, c) => {
  const k = c.myTimesheets;

  it('shows a reopened timesheet, why another review is needed and its whole history', async () => {
    stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return {
          status: 200,
          body: {
            items: [item({ status: 'REOPENED', submissionCycle: 1 })],
            nextCursor: null,
          },
        };
      }
      return {
        status: 200,
        body: mine({
          status: 'REOPENED',
          submissionCycle: 1,
          submission: { eligible: false, blockers: ['REFRESH_REQUIRED'] },
          actions: [reopenedAction, approvedAction, submittedAction],
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
    expect(await screen.findByTestId('my-timesheet-status')).toHaveTextContent(
      c.timesheets.status.REOPENED,
    );
    const view = screen.getByTestId('my-timesheet-view');
    view.focus();
    await user.keyboard('{Enter}');
    const detail = await screen.findByTestId('my-timesheet-detail');
    expect(within(detail).getByTestId('my-timesheet-detail-status')).toHaveTextContent(
      c.timesheets.status.REOPENED,
    );
    const reopened = within(detail).getByTestId('my-timesheet-reopened');
    expect(reopened).toHaveTextContent(k.reopened.title);
    expect(within(reopened).getByTestId('my-timesheet-reopened-next')).toHaveTextContent(
      k.reopened.refreshTitle,
    );
    expect(within(reopened).getByTestId('my-timesheet-reopened-next')).toHaveTextContent(
      k.reopened.refresh,
    );
    // The earlier approval stays visible; nothing can be submitted before the refresh.
    const actions = within(detail).getAllByTestId('timesheet-action');
    expect(actions.map((a) => a.dataset.action)).toEqual(['REOPENED', 'APPROVED', 'SUBMITTED']);
    expect(actions[0]).toHaveTextContent(c.timesheets.history.action.REOPENED);
    expect(actions[1]).toHaveTextContent(c.timesheets.history.action.APPROVED);
    expect(within(detail).queryByTestId('my-timesheet-submission')).toBeNull();
    expect(within(detail).queryByTestId('my-timesheet-submit')).toBeNull();
    expect(within(detail).queryByTestId('my-timesheet-approved')).toBeNull();
    await expectAccessible(container);
    expect(stored()).not.toMatch(/Kalala|2099-11|55555555/u);
  });

  it('after the refresh, shows the new revision and the next cycle, then submits it', async () => {
    const requests = stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return { status: 200, body: { items: [item({ submissionCycle: 1 })], nextCursor: null } };
      }
      if (request.path === `/me/timesheets/${SHEET}/submissions`) {
        return {
          status: 200,
          body: {
            timesheetId: SHEET,
            actionId: ACTION,
            action: 'SUBMITTED',
            status: 'SUBMITTED',
            cycle: 2,
            revision: 2,
            generationVersion: 4,
            occurredAt: '2099-11-09T10:00:00Z',
          },
        };
      }
      return {
        status: 200,
        body: mine({
          revision: 2,
          generationVersion: 4,
          submissionCycle: 1,
          actions: [reopenedAction, approvedAction, submittedAction],
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
    const reopened = await screen.findByTestId('my-timesheet-reopened');
    expect(within(reopened).getByTestId('my-timesheet-reopened-next')).toHaveTextContent(
      k.reopened.refreshed.replace('{{revision}}', '2').replace('{{cycle}}', '2'),
    );
    await expectAccessible(container);
    await user.click(screen.getByTestId('my-timesheet-submit'));
    await user.click(screen.getByTestId('my-timesheet-confirm-check'));
    await user.click(screen.getByTestId('my-timesheet-submit-confirm'));
    await waitFor(() => {
      expect(requests.some((r) => r.path.endsWith('/submissions'))).toBe(true);
    });
    expect(requests.find((r) => r.path.endsWith('/submissions'))?.body).toEqual({
      revision: 2,
      generationVersion: 4,
      confirm: true,
    });
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('Reopened time, the administrator (%s)', (locale, c) => {
  const k = c.timesheets;

  it('previews a refresh with the reopened and locked timesheets and shows the history', async () => {
    const generation = {
      generation: {
        generationId: '44444444-0000-4000-8000-000000000004',
        period,
        status: 'DRAFT',
        version: 1,
        generatedAt: '2099-11-08T11:00:00Z',
        refreshedAt: null,
        drafts: 2,
        totals: clean,
      },
      timesheets: [
        {
          timesheetId: SHEET,
          employee: { id: MANAGER, ...ANA },
          status: 'REOPENED',
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
            drafts: 2,
            totals: clean,
            maxDrafts: 500,
            maxRows: 5000,
          },
        };
      }
      if (request.path.endsWith('/refresh-preview')) {
        return {
          status: 200,
          body: {
            generationId: generation.generation.generationId,
            version: 1,
            period,
            previewDigest: 'b'.repeat(64),
            newDrafts: 0,
            changedTimesheets: 1,
            unchangedTimesheets: 1,
            changedRows: 1,
            totals: clean,
            reopenedTimesheets: 1,
            lockedTimesheets: 1,
          },
        };
      }
      if (request.path.startsWith('/timesheets/generations/')) {
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
            status: 'REOPENED',
            revision: 1,
            submissionCycle: 1,
            totals: clean,
            rows: [],
            revisions: [],
            actions: [
              { ...reopenedAction, actionId: REOPEN_ACTION, generationVersion: 1, rowCount: 2 },
              { ...approvedAction, actionId: APPROVE_ACTION, generationVersion: 1, rowCount: 2 },
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
    expect(await screen.findByTestId('timesheets-draft-status')).toHaveTextContent(
      k.status.REOPENED,
    );
    await user.click(screen.getByTestId('timesheets-draft-view'));
    const detail = await screen.findByTestId('timesheets-detail');
    expect(within(detail).getByTestId('timesheets-detail-status')).toHaveTextContent(
      k.status.REOPENED,
    );
    const note = within(detail).getByTestId('timesheets-detail-reopened');
    expect(note).toHaveTextContent(k.reopened.title);
    expect(note).toHaveTextContent(k.reopened.refresh);
    const actions = within(detail).getAllByTestId('timesheet-action');
    expect(actions.map((a) => a.dataset.action)).toEqual(['REOPENED', 'APPROVED', 'SUBMITTED']);
    // No decision is offered to an administrator.
    expect(
      within(detail).queryByRole('button', { name: c.timeApprovals.decision.approve }),
    ).toBeNull();
    expect(
      within(detail).queryByRole('button', { name: c.timeApprovals.decision.giveBack }),
    ).toBeNull();
    await expectAccessible(container);
    await user.click(
      within(screen.getByTestId('timesheets-generation')).getByRole('button', {
        name: k.refresh.action,
      }),
    );
    const refresh = await screen.findByTestId('timesheets-refresh');
    expect(within(refresh).getByTestId('timesheets-refresh-reopened')).toHaveTextContent('1');
    expect(within(refresh).getByTestId('timesheets-refresh-locked')).toHaveTextContent('1');
    expect(within(refresh).getByTestId('timesheets-refresh-reopened-note')).toHaveTextContent(
      k.refresh.reopenedNote_one.replace('{{count}}', '1'),
    );
    expect(within(refresh).getByTestId('timesheets-refresh-locked-note')).toHaveTextContent(
      k.refresh.lockedNote_one.replace('{{count}}', '1'),
    );
    expect(within(refresh).getByTestId('timesheets-refresh-apply')).toBeDisabled();
    await expectAccessible(container);
  });
});

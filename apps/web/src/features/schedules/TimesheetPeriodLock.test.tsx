import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MyTimesheetsPage } from './MyTimesheetsPage';
import { TimeApprovalsPage } from './TimeApprovalsPage';
import { TimesheetsPage } from './TimesheetsPage';
import { UNLOCK_REASONS, timesheetFailureOf } from './timesheets';

const LEGAL = '22222222-0000-4000-8000-000000000002';
const SITE = '33333333-0000-4000-8000-000000000003';
const GENERATION = '44444444-0000-4000-8000-000000000004';
const SHEET = '55555555-0000-4000-8000-000000000005';
const LOCK_ACTION = '66666666-0000-4000-8000-000000000006';
const UNLOCK_ACTION = '77777777-0000-4000-8000-000000000007';
const ACTION = '99999999-0000-4000-8000-000000000009';
const ANA = {
  id: '11111111-0000-4000-8000-000000000001',
  employeeNumber: 'E-0042',
  givenNames: 'Ana',
  familyName: 'Kalala',
};
const DIGEST = 'b'.repeat(64);
const EVIDENCE = 'e'.repeat(64);
const MONDAY = '2099-11-02';
const EXPLANATION = 'Pointage du lundi à corriger après vérification.';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-pl-1234',
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

const totals = {
  rows: 2,
  readyRows: 2,
  missingRows: 0,
  openRows: 0,
  changedRows: 0,
  blockedRows: 0,
  workedMinutes: 948,
};

const scope = {
  legalEntityId: LEGAL,
  legalEntityCode: 'DRC',
  legalEntityName: 'Dival RDC',
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

const counts = { timesheets: 1, employees: 1, rows: 2, workedMinutes: 948 };

const open = { state: 'OPEN', version: 0, currentActionId: null, updatedAt: null };
const lockedState = {
  state: 'LOCKED',
  version: 1,
  currentActionId: LOCK_ACTION,
  updatedAt: '2099-11-09T08:00:00Z',
};
const reopenedState = {
  state: 'OPEN',
  version: 2,
  currentActionId: UNLOCK_ACTION,
  updatedAt: '2099-11-09T09:00:00Z',
};

const lockAction = {
  actionId: LOCK_ACTION,
  sequence: 1,
  action: 'LOCKED',
  generationVersion: 1,
  counts,
  evidenceDigest: EVIDENCE,
  lockedActionId: null,
  unlockReason: null,
  occurredAt: '2099-11-09T08:00:00Z',
};

const unlockAction = {
  ...lockAction,
  actionId: UNLOCK_ACTION,
  sequence: 2,
  action: 'UNLOCKED',
  lockedActionId: LOCK_ACTION,
  unlockReason: 'ATTENDANCE_CORRECTION_REQUIRED',
  occurredAt: '2099-11-09T09:00:00Z',
};

const summary = (periodLock: Record<string, unknown>) => ({
  generationId: GENERATION,
  period,
  status: 'DRAFT',
  version: 1,
  generatedAt: '2099-11-08T11:00:00Z',
  refreshedAt: null,
  drafts: 1,
  totals,
  periodLock,
});

const generationView = (periodLock: Record<string, unknown>) => ({
  generation: summary(periodLock),
  timesheets: [
    {
      timesheetId: SHEET,
      employee: ANA,
      status: 'APPROVED',
      revision: 1,
      totals,
      updatedAt: '2099-11-08T11:00:00Z',
    },
  ],
});

const units = (request: Recorded): Reply | null => {
  if (request.path.startsWith('/legal-entities')) {
    return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
  }
  if (request.path.startsWith('/sites')) {
    return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
  }
  return null;
};

const en = resources.en.common;
const fr = resources.fr.common;

async function openWeek(user: ReturnType<typeof userEvent.setup>, c: typeof en) {
  const k = c.timesheets;
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
  await user.click(screen.getByRole('button', { name: k.form.preview }));
  await user.click(await screen.findByRole('button', { name: k.preview.open }));
  return screen.findByTestId('period-lock');
}

describe('period lock helpers', () => {
  it('maps the period-lock codes to stable kinds and flags the unlock reason fields', () => {
    const response = (status: number) => new Response(null, { status });
    expect(UNLOCK_REASONS).toHaveLength(4);
    expect(
      timesheetFailureOf('admin', response(409), problem('TIMESHEET_PERIOD_LOCKED', 409)),
    ).toMatchObject({ kind: 'locked', messageKey: 'errors.TIMESHEET_PERIOD_LOCKED' });
    expect(
      timesheetFailureOf('admin', response(409), problem('TIMESHEET_PERIOD_NOT_READY', 409)),
    ).toMatchObject({ kind: 'notAllowed', messageKey: 'errors.TIMESHEET_PERIOD_NOT_READY' });
    expect(
      timesheetFailureOf('admin', response(409), problem('TIMESHEET_PERIOD_NOT_LOCKED', 409)),
    ).toMatchObject({ kind: 'notAllowed' });
    expect(
      timesheetFailureOf('admin', response(409), problem('TIMESHEET_PERIOD_PREVIEW_STALE', 409)),
    ).toMatchObject({ kind: 'stale', messageKey: 'errors.TIMESHEET_PERIOD_PREVIEW_STALE' });
    expect(
      timesheetFailureOf(
        'admin',
        response(422),
        problem('VALIDATION_FAILED', 422, {
          fields: [{ field: 'explanation', constraint: 'FORMAT' }],
        }),
      ),
    ).toMatchObject({ kind: 'validation', reason: true, fields: [] });
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('Period lock (%s)', (locale, c) => {
  const k = c.timesheetPeriod;

  it('locks an approved week only with its fresh preview, then unlocks it with a reason', async () => {
    let state: Record<string, unknown> = open;
    let actions: Record<string, unknown>[] = [];
    let stale = true;
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path === '/timesheets/generation-preview') {
        return {
          status: 200,
          body: {
            period,
            generation: summary(state),
            drafts: 1,
            totals,
            maxDrafts: 500,
            maxRows: 5000,
          },
        };
      }
      if (request.path === `/timesheets/generations/${GENERATION}`) {
        return { status: 200, body: generationView(state) };
      }
      if (request.path === `/timesheet-periods/${GENERATION}`) {
        return {
          status: 200,
          body: {
            scope,
            generationId: GENERATION,
            generationVersion: 1,
            lock: state,
            actions: [...actions].reverse(),
          },
        };
      }
      if (request.path === '/timesheet-periods/lock-preview') {
        return {
          status: 200,
          body: {
            scope,
            generationId: GENERATION,
            generationVersion: 1,
            lock: state,
            counts,
            states: { draft: 0, submitted: 0, approved: 1, reopened: 0 },
            ready: true,
            blockers: [],
            exported: true,
            previewDigest: DIGEST,
            expiresAt: '2099-11-09T08:30:00Z',
          },
        };
      }
      if (request.path === '/timesheet-periods/locks') {
        if (stale) {
          stale = false;
          return { status: 409, body: problem('TIMESHEET_PERIOD_PREVIEW_STALE', 409) };
        }
        state = lockedState;
        actions = [lockAction];
        return {
          status: 200,
          body: {
            outcome: 'LOCKED',
            scope,
            generationId: GENERATION,
            lock: lockedState,
            action: lockAction,
          },
        };
      }
      if (request.path === '/timesheet-periods/unlock-preview') {
        return {
          status: 200,
          body: {
            scope,
            generationId: GENERATION,
            lock: lockedState,
            currentLock: lockAction,
            reasonCode: 'ATTENDANCE_CORRECTION_REQUIRED',
            warnings: ['EXPORTS_UNCHANGED', 'APPROVALS_UNCHANGED'],
            previewDigest: DIGEST,
            expiresAt: '2099-11-09T09:30:00Z',
          },
        };
      }
      if (request.path === '/timesheet-periods/unlocks') {
        state = reopenedState;
        actions = [lockAction, unlockAction];
        return {
          status: 200,
          body: {
            outcome: 'UNLOCKED',
            scope,
            generationId: GENERATION,
            lock: reopenedState,
            action: unlockAction,
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
    const panel = await openWeek(user, c);
    expect(panel.dataset.state).toBe('OPEN');
    expect(within(panel).getByTestId('period-lock-state')).toHaveTextContent(k.state.OPEN);
    expect(await within(panel).findByTestId('period-lock-history-none')).toBeVisible();
    expect(within(panel).queryByTestId('period-unlock-open')).toBeNull();
    expect(screen.getByTestId('timesheets-refresh-preview')).toBeEnabled();
    await expectAccessible(container);

    // The lock preview: counts, states, the export note; nothing before the confirmation.
    await user.click(within(panel).getByRole('button', { name: k.lock.action }));
    const preview = await within(panel).findByTestId('period-lock-preview-card');
    await waitFor(() => expect(within(preview).getByRole('heading', { level: 4 })).toHaveFocus());
    expect(within(preview).getByTestId('period-lock-states-approved')).toHaveTextContent('1');
    expect(within(preview).getByTestId('period-lock-counts-worked')).toHaveTextContent(
      '15 h 48 min',
    );
    expect(within(preview).getByTestId('period-lock-exported')).toHaveTextContent(k.lock.exported);
    expect(within(preview).getByTestId('period-lock-ready')).toBeVisible();
    const apply = within(preview).getByRole('button', { name: k.lock.confirm });
    expect(apply).toBeDisabled();
    await user.click(within(preview).getByLabelText(k.lock.confirmCheck));
    await expectAccessible(container);

    // Stale: refused, the preview is dropped and taken again with a new key.
    await user.click(apply);
    expect(await within(panel).findByTestId('period-lock-error')).toHaveTextContent(
      c.errors.TIMESHEET_PERIOD_PREVIEW_STALE,
    );
    expect(within(panel).queryByTestId('period-lock-preview-card')).toBeNull();
    await user.click(within(panel).getByRole('button', { name: k.lock.action }));
    const again = await within(panel).findByTestId('period-lock-preview-card');
    await user.click(within(again).getByLabelText(k.lock.confirmCheck));
    await user.click(within(again).getByRole('button', { name: k.lock.confirm }));
    await waitFor(() => {
      expect(screen.getByTestId('period-lock').dataset.state).toBe('LOCKED');
    });
    const locked = screen.getByTestId('period-lock');
    expect(within(locked).getByTestId('period-lock-state')).toHaveTextContent(k.state.LOCKED);
    expect(within(locked).getByTestId('period-lock-banner')).toHaveTextContent(k.lockedFor);
    expect(within(locked).getByTestId('period-lock-receipt')).toHaveTextContent(
      k.receipt.replace('{{version}}', '1').split(' ·')[0]!,
    );
    expect(screen.getByTestId('period-lock-announcer')).toHaveTextContent(k.lock.done);
    const history = await within(locked).findAllByTestId('period-lock-action');
    expect(history.map((row) => row.dataset.action)).toEqual(['LOCKED']);
    expect(within(locked).getByTestId('period-lock-receipt-counts')).toHaveTextContent(
      '15 h 48 min',
    );
    // A locked week is not refreshed; the preview button says why.
    expect(screen.getByTestId('timesheets-refresh-preview')).toBeDisabled();
    expect(screen.getByTestId('timesheets-refresh-locked-period')).toHaveTextContent(
      k.refreshLocked,
    );
    const locks = requests.filter((r) => r.path === '/timesheet-periods/locks');
    expect(locks).toHaveLength(2);
    expect(locks[1]?.body).toEqual({
      legalEntityId: LEGAL,
      siteId: SITE,
      weekStart: MONDAY,
      generationVersion: 1,
      lockVersion: 0,
      previewDigest: DIGEST,
      confirm: true,
    });
    expect(locks[0]?.key).not.toBe(locks[1]?.key);
    await expectAccessible(container);

    // The unlock: a reason and an explanation checked as the server does, then its preview.
    await user.click(within(locked).getByRole('button', { name: k.unlock.action }));
    const unlock = await within(locked).findByTestId('period-unlock');
    await user.click(within(unlock).getByRole('button', { name: k.unlock.preview }));
    expect(within(unlock).getByTestId('period-unlock-reason')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(within(unlock).getByText(k.unlock.problem.reasonRequired)).toBeVisible();
    expect(within(unlock).getByText(k.unlock.problem.required)).toBeVisible();
    expect(requests.some((r) => r.path === '/timesheet-periods/unlock-preview')).toBe(false);
    await user.selectOptions(
      within(unlock).getByLabelText(k.unlock.reason),
      'ATTENDANCE_CORRECTION_REQUIRED',
    );
    await user.type(within(unlock).getByLabelText(k.unlock.explanation), 'Trop bref');
    await user.click(within(unlock).getByRole('button', { name: k.unlock.preview }));
    expect(within(unlock).getByText(k.unlock.problem.length)).toBeVisible();
    await user.clear(within(unlock).getByLabelText(k.unlock.explanation));
    await user.type(within(unlock).getByLabelText(k.unlock.explanation), `  ${EXPLANATION}  `);
    await expectAccessible(container);
    await user.click(within(unlock).getByRole('button', { name: k.unlock.preview }));
    const unlockPreview = await within(unlock).findByTestId('period-unlock-preview-card');
    expect(within(unlockPreview).getByTestId('period-unlock-reason-text')).toHaveTextContent(
      k.reason.ATTENDANCE_CORRECTION_REQUIRED,
    );
    expect(within(unlockPreview).getByTestId('period-unlock-warnings')).toHaveTextContent(
      k.unlock.warning.EXPORTS_UNCHANGED,
    );
    const confirm = within(unlockPreview).getByRole('button', { name: k.unlock.confirm });
    expect(confirm).toBeDisabled();
    await user.click(within(unlockPreview).getByLabelText(k.unlock.confirmCheck));
    await user.click(confirm);
    await waitFor(() => {
      expect(screen.getByTestId('period-lock').dataset.state).toBe('OPEN');
    });
    const reopened = screen.getByTestId('period-lock');
    expect(screen.getByTestId('period-lock-announcer')).toHaveTextContent(k.unlock.done);
    expect(within(reopened).queryByTestId('period-lock-banner')).toBeNull();
    expect(within(reopened).getByRole('button', { name: k.lock.action })).toBeEnabled();
    expect(screen.getByTestId('timesheets-refresh-preview')).toBeEnabled();
    const rows = await within(reopened).findAllByTestId('period-lock-action');
    expect(rows.map((row) => row.dataset.action)).toEqual(['UNLOCKED', 'LOCKED']);
    expect(within(rows[0]!).getByTestId('period-lock-action-reason')).toHaveTextContent(
      k.reason.ATTENDANCE_CORRECTION_REQUIRED,
    );
    const unlockPost = requests.find((r) => r.path === '/timesheet-periods/unlocks');
    expect(unlockPost?.body).toEqual({
      legalEntityId: LEGAL,
      siteId: SITE,
      weekStart: MONDAY,
      reasonCode: 'ATTENDANCE_CORRECTION_REQUIRED',
      explanation: EXPLANATION,
      lockVersion: 1,
      lockActionId: LOCK_ACTION,
      previewDigest: DIGEST,
      confirm: true,
    });
    expect(unlockPost?.key).toMatch(/^web-/u);
    // The explanation is never shown back, not even in the history.
    expect(reopened.textContent).not.toContain('Pointage du lundi');
    expect(stored()).not.toMatch(/Pointage|66666666|2099-11|Kinshasa/u);
    await expectAccessible(container);
  });

  it('names what blocks a lock and offers no confirmation', async () => {
    stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path === '/timesheets/generation-preview') {
        return {
          status: 200,
          body: {
            period,
            generation: summary(open),
            drafts: 1,
            totals,
            maxDrafts: 500,
            maxRows: 5000,
          },
        };
      }
      if (request.path === `/timesheets/generations/${GENERATION}`) {
        return { status: 200, body: generationView(open) };
      }
      if (request.path === `/timesheet-periods/${GENERATION}`) {
        return {
          status: 200,
          body: { scope, generationId: GENERATION, generationVersion: 1, lock: open, actions: [] },
        };
      }
      if (request.path === '/timesheet-periods/lock-preview') {
        return {
          status: 200,
          body: {
            scope,
            generationId: GENERATION,
            generationVersion: 1,
            lock: open,
            counts: { timesheets: 2, employees: 2, rows: 3, workedMinutes: 1400 },
            states: { draft: 0, submitted: 1, approved: 1, reopened: 0 },
            ready: false,
            blockers: ['NOT_ALL_APPROVED'],
            exported: false,
            previewDigest: DIGEST,
            expiresAt: '2099-11-09T08:30:00Z',
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
    const panel = await openWeek(user, c);
    await user.click(within(panel).getByRole('button', { name: k.lock.action }));
    const preview = await within(panel).findByTestId('period-lock-preview-card');
    expect(within(preview).getByTestId('period-lock-not-ready')).toHaveTextContent(k.lock.notReady);
    const blockers = within(preview).getAllByTestId('period-lock-blocker');
    expect(blockers.map((b) => b.dataset.blocker)).toEqual(['NOT_ALL_APPROVED']);
    expect(blockers[0]).toHaveTextContent(k.lock.blocker.NOT_ALL_APPROVED);
    expect(within(preview).getByTestId('period-lock-states-submitted')).toHaveTextContent('1');
    expect(within(preview).getByTestId('period-lock-exported')).toHaveTextContent(
      k.lock.notExported,
    );
    expect(within(preview).queryByRole('button', { name: k.lock.confirm })).toBeNull();
    expect(within(preview).queryByLabelText(k.lock.confirmCheck)).toBeNull();
    // No employee is named by the readiness.
    expect(preview.textContent).not.toMatch(/Kalala|E-0042/u);
    await expectAccessible(container);
    await user.click(within(preview).getByRole('button', { name: k.lock.cancel }));
    await waitFor(() =>
      expect(within(panel).getByRole('button', { name: k.lock.action })).toHaveFocus(),
    );
  });

  it('shows the employee a neutral lock badge on their own approved week only', async () => {
    const myRow = {
      labels: { en: 'Cashier', fr: 'Caissière' },
      scheduledStartAt: '2099-11-02T07:00:00Z',
      scheduledEndAt: '2099-11-02T15:00:00Z',
      state: 'READY',
      checkedInAt: '2099-11-02T07:00:10Z',
      checkedOutAt: '2099-11-02T14:59:40Z',
      corrected: false,
      workedMinutes: 474,
    };
    stubApi((request) => {
      if (request.path === '/me/timesheets') {
        return {
          status: 200,
          body: {
            items: [
              {
                timesheetId: SHEET,
                period,
                status: 'APPROVED',
                revision: 1,
                submissionCycle: 1,
                totals,
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
            status: 'APPROVED',
            revision: 1,
            generationVersion: 1,
            submissionCycle: 1,
            totals,
            updatedAt: '2099-11-08T11:00:00Z',
            submission: { eligible: false, blockers: [] },
            actions: [
              {
                actionId: ACTION,
                action: 'APPROVED',
                cycle: 1,
                revision: 1,
                generationVersion: 1,
                workedMinutes: 948,
                returnReason: null,
                occurredAt: '2099-11-08T12:00:00Z',
              },
            ],
            rows: [myRow, myRow],
            periodLocked: true,
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
    const cards = await screen.findAllByTestId('my-timesheet');
    await user.click(within(cards[0]!).getByTestId('my-timesheet-view'));
    const opened = await screen.findByTestId('my-timesheet-detail');
    expect(await within(opened).findByTestId('my-timesheet-period-locked')).toHaveTextContent(
      k.badge,
    );
    expect(within(opened).getByTestId('my-timesheet-period-locked-hint')).toHaveTextContent(
      k.badgeHint,
    );
    // Nothing administrative: no lock or unlock control, no receipt, no reason.
    expect(within(opened).queryByRole('button', { name: k.lock.action })).toBeNull();
    expect(within(opened).queryByRole('button', { name: k.unlock.action })).toBeNull();
    expect(opened.textContent).not.toMatch(/66666666|Pointage/u);
    await expectAccessible(container);
  });

  it('shows the routed manager the badge inside the authorized approval view only', async () => {
    const row = {
      slotId: '12121212-0000-4000-8000-000000000012',
      scheduleId: '13131313-0000-4000-8000-000000000013',
      employmentId: '14141414-0000-4000-8000-000000000014',
      siteId: SITE,
      costCenter: null,
      labels: { en: 'Cashier', fr: 'Caissière' },
      scheduledStartAt: '2099-11-02T07:00:00Z',
      scheduledEndAt: '2099-11-02T15:00:00Z',
      state: 'READY',
      attendanceRecordId: '15151515-0000-4000-8000-000000000015',
      checkedInAt: '2099-11-02T07:00:10Z',
      checkedOutAt: '2099-11-02T14:59:40Z',
      correctionVersion: null,
      corrected: false,
      workedMinutes: 474,
    };
    stubApi((request) => {
      if (request.path === '/me/time-approvals') {
        return {
          status: 200,
          body: {
            items: [
              {
                timesheetId: SHEET,
                employee: ANA,
                period,
                revision: 1,
                cycle: 1,
                rowCount: 2,
                workedMinutes: 948,
                submittedAt: '2099-11-08T11:00:00Z',
              },
            ],
            nextCursor: null,
          },
        };
      }
      if (request.path === `/me/time-approvals/${SHEET}`) {
        return {
          status: 200,
          body: {
            timesheetId: SHEET,
            employee: ANA,
            period,
            status: 'SUBMITTED',
            revision: 1,
            generationVersion: 1,
            cycle: 1,
            submissionActionId: ACTION,
            submittedAt: '2099-11-08T11:00:00Z',
            totals,
            rows: [row, row],
            actions: [
              {
                actionId: ACTION,
                action: 'SUBMITTED',
                cycle: 1,
                revision: 1,
                generationVersion: 1,
                rowCount: 2,
                workedMinutes: 948,
                returnReason: null,
                occurredAt: '2099-11-08T11:00:00Z',
              },
            ],
            periodLocked: true,
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
    const cards = await screen.findAllByTestId('time-approval');
    await user.click(within(cards[0]!).getByTestId('time-approval-open'));
    const detail = await screen.findByTestId('time-approval-detail');
    expect(await within(detail).findByTestId('time-approval-period-locked')).toHaveTextContent(
      k.badge,
    );
    expect(within(detail).getByTestId('time-approval-period-locked-hint')).toHaveTextContent(
      k.badgeHint,
    );
    expect(within(detail).queryByRole('button', { name: k.unlock.action })).toBeNull();
    await expectAccessible(container);
  });
});

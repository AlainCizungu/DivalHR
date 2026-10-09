import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { MyLeavePage } from './MyLeavePage';

const ANNUAL = {
  id: 'aaaaaaaa-0000-4000-8000-00000000000a',
  versionId: 'aaaaaaaa-0000-4000-8000-0000000000a1',
  code: 'ANNUAL',
  versionNumber: 1,
  names: { en: 'Annual leave', fr: 'Congé annuel' },
  unit: 'DAYS',
  balanceMode: 'TRACKED',
  annualEntitlement: 18.5,
  minimumServiceDays: 0,
  approvalRoute: 'MANAGER',
  payrollEffect: 'PAID',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  status: 'ACTIVE',
};

const HOURS = {
  ...ANNUAL,
  id: 'bbbbbbbb-0000-4000-8000-00000000000b',
  versionId: 'bbbbbbbb-0000-4000-8000-0000000000b1',
  code: 'MEDICAL',
  names: { en: 'Medical appointment', fr: 'Rendez-vous médical — été' },
  unit: 'HOURS',
  balanceMode: 'UNTRACKED',
  annualEntitlement: null,
  minimumServiceDays: 90,
  status: 'PLANNED',
  effectiveFrom: '2026-11-01',
};

const request = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  policyId: ANNUAL.id,
  policyVersionId: ANNUAL.versionId,
  policyCode: 'ANNUAL',
  policyNames: ANNUAL.names,
  unit: 'DAYS',
  amount: 2.5,
  startDate: '2026-10-20',
  endDate: '2026-10-22',
  state: 'PENDING',
  submittedAt: '2026-10-09T10:00:00Z',
  ...overrides,
});

const policyPage = (items: unknown[], overrides: Record<string, unknown> = {}) => ({
  items,
  nextCursor: null,
  asOf: '2026-10-09',
  timezone: 'Africa/Kinshasa',
  ...overrides,
});

const historyPage = (items: unknown[], nextCursor: string | null = null) => ({
  items,
  nextCursor,
});

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-myleave-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

function stubApi(route: (request: Recorded) => Reply | Promise<Reply> | undefined) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const body = incoming.method === 'GET' ? '' : await incoming.clone().text();
      const path = url.pathname.replace(/^.*\/api\/v1/u, '') + url.search;
      const recorded = {
        method: incoming.method,
        path,
        body,
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = (await route(recorded)) ?? { status: 404, body: problem('NOT_FOUND', 404) };
      if (reply.status === 0) throw new TypeError('network down');
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

/** Policies and an empty history, plus whatever the test answers for the rest. */
function standard(
  post?: (request: Recorded) => Reply | Promise<Reply>,
  history: () => Reply = () => ({ status: 200, body: historyPage([]) }),
) {
  return stubApi((r) => {
    if (r.method === 'GET' && r.path.startsWith('/me/leave-policies')) {
      return { status: 200, body: policyPage([ANNUAL, HOURS]) };
    }
    if (r.method === 'GET' && r.path.startsWith('/me/leave-requests')) return history();
    if (r.method === 'POST' && post) return post(r);
    return undefined;
  });
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['fr', 'en'] as const)('MVP-041A my leave (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.myLeave;
  const other = locale === 'fr' ? 'en' : 'fr';
  const show = () =>
    renderWithSession(<MyLeavePage />, sessionWithRoles(['employee']), locale, '/me/leave');

  async function fill(
    user: ReturnType<typeof userEvent.setup>,
    values: Partial<Record<'policy' | 'start' | 'end' | 'amount', string>> = {},
  ) {
    const v = {
      policy: ANNUAL.id,
      start: '2026-10-20',
      end: '2026-10-22',
      amount: locale === 'fr' ? '2,5' : '2.5',
      ...values,
    };
    await user.selectOptions(screen.getByLabelText(k.form.policy), v.policy);
    await user.type(screen.getByLabelText(k.form.startDate), v.start);
    await user.type(screen.getByLabelText(k.form.endDate), v.end);
    const amount = screen.getByRole('textbox');
    await user.type(amount, v.amount);
  }

  it('shows the policies with the current-language name first, the scope note and an empty history', async () => {
    standard();
    const { container } = await show();
    const list = await screen.findByTestId('my-policies');
    const items = within(list).getAllByTestId('my-policy');
    expect(items).toHaveLength(2);
    const heading = within(items[0]!).getByRole('heading');
    expect(heading.querySelector(`[lang="${locale}"]`)).toHaveTextContent(ANNUAL.names[locale]);
    expect(items[0]!.querySelector(`p[lang="${other}"]`)).toHaveTextContent(ANNUAL.names[other]);
    expect(within(items[1]!).getByTestId('policy-status')).toHaveTextContent(
      c.leavePolicies.status.PLANNED,
    );
    expect(screen.getByTestId('my-leave-scope')).toHaveTextContent(k.notCalculated);
    expect(await screen.findByTestId('history-empty')).toHaveTextContent(k.history.empty);
    // Nothing is preselected.
    expect(screen.getByLabelText(k.form.policy)).toHaveValue('');
    expect(screen.getByLabelText(k.form.startDate)).toHaveValue('');
    expect(screen.getByLabelText(k.form.amount.UNKNOWN)).toHaveValue('');
    await expectAccessible(container);
  });

  it('shows loading states, then the no-policy state', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    stubApi(async (r) => {
      await held;
      return r.path.startsWith('/me/leave-policies')
        ? { status: 200, body: policyPage([]) }
        : { status: 200, body: historyPage([]) };
    });
    await show();
    expect(screen.getByText(k.policies.loading)).toHaveAttribute('role', 'status');
    expect(screen.getByText(k.history.loading)).toHaveAttribute('role', 'status');
    release();
    expect(await screen.findByTestId('policies-empty')).toHaveTextContent(k.policies.empty);
  });

  it('labels the amount by the policy unit, submits the exact command and refreshes the history', async () => {
    const user = userEvent.setup();
    let submitted = false;
    const requests = standard(
      () => {
        submitted = true;
        return { status: 201, body: request('cccccccc-0000-4000-8000-00000000000c') };
      },
      () => ({
        status: 200,
        body: historyPage(submitted ? [request('cccccccc-0000-4000-8000-00000000000c')] : []),
      }),
    );
    await show();
    await screen.findByTestId('history-empty');
    await user.selectOptions(screen.getByLabelText(k.form.policy), HOURS.id);
    expect(screen.getByLabelText(k.form.amount.HOURS)).toBeInTheDocument();
    expect(
      screen.getByTestId('chosen-policy').querySelector(`[lang="${other}"]`),
    ).toHaveTextContent(HOURS.names[other]);
    await user.selectOptions(screen.getByLabelText(k.form.policy), '');
    await fill(user);
    expect(screen.getByLabelText(k.form.amount.DAYS)).toHaveValue(locale === 'fr' ? '2,5' : '2.5');
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const rows = await screen.findAllByTestId('my-request');
    expect(rows).toHaveLength(1);
    expect(within(rows[0]!).getByTestId('request-state')).toHaveTextContent(k.state.PENDING);
    expect(rows[0]).toHaveTextContent(
      k.amount.DAYS.replace('{{amount}}', new Intl.NumberFormat(locale).format(2.5)),
    );
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      policyId: ANNUAL.id,
      startDate: '2026-10-20',
      endDate: '2026-10-22',
      amount: 2.5,
    });
    expect(posts[0]!.key).toMatch(/^web-[0-9a-f-]{36}$/u);
    // The form is cleared and the success is announced.
    expect(screen.getByLabelText(k.form.policy)).toHaveValue('');
    expect(screen.getByTestId('announcer')).toHaveTextContent(ANNUAL.names[locale]);
    // No request data in the URL or browser storage.
    expect(window.location.search).toBe('');
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('keeps the key for a retried command and uses a new key once it changes', async () => {
    const user = userEvent.setup();
    let posts = 0;
    const requests = standard(() => {
      posts += 1;
      return posts === 1
        ? { status: 0 }
        : posts === 2
          ? { status: 500, body: problem('INTERNAL_ERROR', 500) }
          : { status: 201, body: request('dddddddd-0000-4000-8000-00000000000d') };
    });
    await show();
    await screen.findByTestId('history-empty');
    await fill(user);
    const submit = screen.getByRole('button', { name: k.form.submit });
    await user.click(submit);
    const network = await screen.findByTestId('request-error');
    expect(network).toHaveTextContent(c.errors.network);
    expect(network).toHaveFocus();
    await user.click(submit);
    expect(await screen.findByTestId('request-error')).toHaveTextContent(c.errors.generic);
    await user.clear(screen.getByLabelText(k.form.amount.DAYS));
    await user.type(screen.getByLabelText(k.form.amount.DAYS), '3');
    await user.click(submit);
    await waitFor(() => {
      expect(screen.queryByTestId('request-error')).toBeNull();
    });
    const keys = requests.filter((r) => r.method === 'POST').map((r) => r.key);
    expect(keys).toHaveLength(3);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it('validates in the browser against the server business date and sends nothing', async () => {
    const user = userEvent.setup();
    const requests = standard();
    const { container } = await show();
    await screen.findByTestId('history-empty');
    await user.type(screen.getByLabelText(k.form.startDate), '2026-10-08');
    await user.type(screen.getByLabelText(k.form.endDate), '2027-10-09');
    await user.type(screen.getByRole('textbox'), '1,005');
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const summary = await screen.findByTestId('request-problems');
    expect(
      within(summary)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual([k.problems.policy, k.problems.startDate, k.problems.endDate, k.problems.amount]);
    await waitFor(() => {
      expect(summary).toHaveFocus();
    });
    expect(screen.getByLabelText(k.form.startDate)).toHaveAttribute('aria-invalid', 'true');
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
    await expectAccessible(container);
  });

  it.each([
    [
      'policy unavailable',
      { status: 409, body: problem('LEAVE_POLICY_NOT_REQUESTABLE', 409) },
      () => c.errors.LEAVE_POLICY_NOT_REQUESTABLE,
    ],
    [
      'not eligible (employment)',
      {
        status: 409,
        body: problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: 'EMPLOYMENT_PERIOD' }),
      },
      () => k.notEligible.EMPLOYMENT_PERIOD,
    ],
    [
      'not eligible (service)',
      {
        status: 409,
        body: problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: 'MINIMUM_SERVICE' }),
      },
      () => k.notEligible.MINIMUM_SERVICE,
    ],
    [
      'not eligible (unknown reason)',
      { status: 409, body: problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: '<b>x</b>' }) },
      () => c.errors.LEAVE_REQUEST_NOT_ELIGIBLE,
    ],
    [
      'overlap',
      { status: 409, body: problem('LEAVE_REQUEST_OVERLAP', 409) },
      () => c.errors.LEAVE_REQUEST_OVERLAP,
    ],
    [
      'idempotency conflict',
      { status: 409, body: problem('IDEMPOTENCY_KEY_REUSED', 409) },
      () => c.errors.IDEMPOTENCY_KEY_REUSED,
    ],
    [
      'rate limit',
      { status: 429, body: problem('RATE_LIMITED', 429), headers: { 'Retry-After': '20' } },
      () => c.errors.RATE_LIMITED,
    ],
    [
      'link required',
      { status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) },
      () => c.errors.EMPLOYEE_LINK_REQUIRED,
    ],
    ['forbidden', { status: 403, body: problem('ACCESS_DENIED', 403) }, () => k.unauthorized],
  ])('shows the %s state with its stable message', async (_name, reply, message) => {
    const user = userEvent.setup();
    standard(() => reply);
    await show();
    await screen.findByTestId('history-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const alert = await screen.findByTestId('request-error');
    expect(alert).toHaveTextContent(message());
    expect(alert).toHaveTextContent('corr-myleave-1234');
    expect(alert).not.toHaveTextContent('<b>');
    if (reply.status === 429) {
      expect(alert).toHaveTextContent(c.employees.retryAfter_other.replace('{{count}}', '20'));
    }
    expect(screen.getByLabelText(k.form.policy)).toHaveValue(ANNUAL.id);
  });

  it('marks only the allow-listed fields the server names', async () => {
    const user = userEvent.setup();
    standard(() => ({
      status: 400,
      body: problem('VALIDATION_FAILED', 400, {
        fields: [
          { field: 'startDate', constraint: 'RANGE' },
          { field: 'amount', constraint: 'FORMAT' },
          { field: 'employeeId', constraint: 'FORMAT' },
        ],
      }),
    }));
    await show();
    await screen.findByTestId('history-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const alert = await screen.findByTestId('request-error');
    expect(
      within(alert)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([k.problems.startDate, k.problems.amount]);
    expect(screen.getByLabelText(k.form.startDate)).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows the link-required state for the lists', async () => {
    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await show();
    expect(await screen.findByTestId('policies-error')).toHaveTextContent(
      c.errors.EMPLOYEE_LINK_REQUIRED,
    );
    expect(await screen.findByTestId('history-error')).toHaveTextContent(
      c.errors.EMPLOYEE_LINK_REQUIRED,
    );
  });

  it('requests a history page once while it loads, however often Show more is activated (R86-1)', async () => {
    const user = userEvent.setup();
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests = stubApi(async (r) => {
      if (r.path.startsWith('/me/leave-policies')) {
        return { status: 200, body: policyPage([ANNUAL]) };
      }
      if (r.path.includes('cursor=h-1')) {
        await held;
        return {
          status: 200,
          body: historyPage([request('ffffffff-0000-4000-8000-00000000000f')]),
        };
      }
      return {
        status: 200,
        body: historyPage([request('eeeeeeee-0000-4000-8000-00000000000e')], 'h-1'),
      };
    });
    await show();
    await screen.findAllByTestId('my-request');
    const button = screen.getByRole('button', { name: k.history.more });
    await user.click(button);
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent(k.history.loadingMore);
    await user.click(button);
    button.click();
    expect(requests.filter((r) => r.path.includes('cursor=h-1'))).toHaveLength(1);
    release();
    await waitFor(() => {
      expect(screen.getAllByTestId('my-request')).toHaveLength(2);
    });
    expect(requests.filter((r) => r.path.includes('cursor=h-1'))).toHaveLength(1);
    expect(screen.queryByRole('button', { name: k.history.more })).toBeNull();
  });

  it('is unavailable to a tenant administrator before any request', async () => {
    const requests = stubApi(() => ({ status: 200, body: historyPage([]) }));
    await renderWithSession(
      <Routes>
        <Route
          path="/me/leave"
          element={
            <RequireRole
              requiredRole="employee"
              deniedKey="myLeave.unauthorized"
              signInKey="myLeave.signInRequired"
            >
              <MyLeavePage />
            </RequireRole>
          }
        />
      </Routes>,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/me/leave',
    );
    expect(await screen.findByText(k.unauthorized)).toBeInTheDocument();
    expect(requests).toHaveLength(0);
  });
});

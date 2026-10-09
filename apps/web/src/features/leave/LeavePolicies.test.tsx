import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { LeavePoliciesPage } from './LeavePoliciesPage';

const ANNUAL = {
  id: 'aaaaaaaa-0000-4000-8000-00000000000a',
  code: 'ANNUAL',
  versionNumber: 1,
  names: { en: 'Annual leave', fr: 'Congé annuel' },
  unit: 'DAYS',
  balanceMode: 'TRACKED',
  annualEntitlement: 18.5,
  minimumServiceDays: 90,
  approvalRoute: 'MANAGER',
  payrollEffect: 'PAID',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  status: 'ACTIVE',
  createdAt: '2026-01-01T08:00:00Z',
};

const SICK = {
  ...ANNUAL,
  id: 'bbbbbbbb-0000-4000-8000-00000000000b',
  code: 'SICK',
  names: { en: 'Sick leave', fr: 'Congé maladie — Été' },
  unit: 'HOURS',
  balanceMode: 'UNTRACKED',
  annualEntitlement: null,
  minimumServiceDays: 0,
  approvalRoute: 'TENANT_ADMIN',
  payrollEffect: 'UNPAID',
  effectiveFrom: '2026-11-01',
  effectiveTo: '2026-12-31',
  status: 'PLANNED',
};

const page = (items: unknown[], overrides: Record<string, unknown> = {}) => ({
  items,
  nextCursor: null,
  asOf: '2026-10-07',
  timezone: 'Africa/Kinshasa',
  ...overrides,
});

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-leave-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

function stubApi(route: (request: Recorded, index: number) => Reply | undefined) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      const body = request.method === 'GET' ? '' : await request.clone().text();
      const path = url.pathname.replace(/^.*\/api\/v1/u, '') + url.search;
      const recorded = {
        method: request.method,
        path,
        body,
        key: request.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = route(recorded, requests.length - 1) ?? {
        status: 404,
        body: problem('NOT_FOUND', 404),
      };
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

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['fr', 'en'] as const)('MVP-040A leave policies (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.leavePolicies;
  const other = locale === 'fr' ? 'en' : 'fr';
  const show = () =>
    renderWithSession(
      <LeavePoliciesPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/leave-policies',
    );

  async function fill(
    user: ReturnType<typeof userEvent.setup>,
    values: Partial<Record<string, string>> = {},
  ) {
    const v = {
      code: 'annual',
      nameEn: 'Annual leave',
      nameFr: 'Congé annuel',
      unit: 'DAYS',
      balanceMode: 'TRACKED',
      annualEntitlement: locale === 'fr' ? '18,5' : '18.5',
      minimumServiceDays: '90',
      approvalRoute: 'MANAGER',
      payrollEffect: 'PAID',
      effectiveFrom: '2026-01-01',
      ...values,
    };
    await user.type(screen.getByLabelText(k.form.code), v.code);
    await user.type(screen.getByLabelText(k.form.nameEn), v.nameEn);
    await user.type(screen.getByLabelText(k.form.nameFr), v.nameFr);
    await user.selectOptions(screen.getByLabelText(k.form.unit), v.unit);
    await user.selectOptions(screen.getByLabelText(k.form.balanceMode), v.balanceMode);
    if (v.balanceMode === 'TRACKED') {
      await user.type(screen.getByLabelText(k.form.annualEntitlement), v.annualEntitlement);
    }
    await user.type(screen.getByLabelText(k.form.minimumServiceDays), v.minimumServiceDays);
    await user.selectOptions(screen.getByLabelText(k.form.approvalRoute), v.approvalRoute);
    await user.selectOptions(screen.getByLabelText(k.form.payrollEffect), v.payrollEffect);
    await user.type(screen.getByLabelText(k.form.effectiveFrom), v.effectiveFrom);
  }

  it('lists policies with the current-language name first, server statuses and the scope note', async () => {
    stubApi(({ method }) =>
      method === 'GET' ? { status: 200, body: page([ANNUAL, SICK]) } : undefined,
    );
    const { container } = await show();
    const table = await screen.findByTestId('policies');
    const rows = within(table).getAllByTestId('policy-row');
    expect(rows).toHaveLength(2);
    const header = within(rows[0]!).getByRole('rowheader');
    expect(header.firstElementChild).toHaveTextContent(ANNUAL.names[locale]);
    expect(header.firstElementChild).toHaveAttribute('lang', locale);
    expect(header.lastElementChild).toHaveTextContent(ANNUAL.names[other]);
    expect(rows[1]).toHaveTextContent(SICK.names[locale]);
    expect(within(rows[0]!).getByTestId('policy-status')).toHaveTextContent(k.status.ACTIVE);
    expect(within(rows[1]!).getByTestId('policy-status')).toHaveTextContent(k.status.PLANNED);
    expect(rows[0]).toHaveTextContent(
      k.entitlement.DAYS.replace(
        '{{amount}}',
        new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(18.5),
      ),
    );
    expect(rows[1]).toHaveTextContent(k.untracked);
    expect(rows[1]).toHaveTextContent(k.minimumServiceNone);
    expect(rows[0]).toHaveTextContent(k.values.approvalRoute.MANAGER);
    expect(rows[1]).toHaveTextContent(k.values.payrollEffect.UNPAID);
    const asOf = new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(
      new Date('2026-10-07T00:00:00Z'),
    );
    expect(screen.getByTestId('as-of')).toHaveTextContent(
      k.asOf.replace('{{date}}', asOf).replace('{{timezone}}', 'Africa/Kinshasa'),
    );
    expect(screen.getByTestId('leave-scope')).toHaveTextContent(k.notEnabled);
    expect(screen.getByTestId('leave-scope')).toHaveTextContent(k.legal);
    // No identifier is shown.
    expect(container.textContent).not.toContain(ANNUAL.id);
    await expectAccessible(container);
  });

  it('shows a loading status, then the empty state', async () => {
    let resolveFirst: (reply: Reply) => void = () => undefined;
    const first = new Promise<Reply>((resolve) => {
      resolveFirst = resolve;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        const reply = await first;
        return new Response(JSON.stringify(reply.body), {
          status: reply.status,
          headers: { 'Content-Type': 'application/json' },
        });
      }),
    );
    const { container } = await show();
    expect(screen.getByText(k.loading)).toHaveAttribute('role', 'status');
    resolveFirst({ status: 200, body: page([]) });
    expect(await screen.findByTestId('policies-empty')).toHaveTextContent(k.empty);
    await expectAccessible(container);
  });

  it('pages with the server cursor and appends the next page', async () => {
    const user = userEvent.setup();
    const requests = stubApi(({ path }) =>
      path.includes('cursor=next-1')
        ? { status: 200, body: page([SICK]) }
        : { status: 200, body: page([ANNUAL], { nextCursor: 'next-1' }) },
    );
    await show();
    await screen.findByTestId('policies');
    await user.click(screen.getByRole('button', { name: k.more }));
    await waitFor(() => {
      expect(screen.getAllByTestId('policy-row')).toHaveLength(2);
    });
    expect(screen.queryByRole('button', { name: k.more })).toBeNull();
    expect(requests.map((r) => r.path)).toEqual([
      '/leave-policies',
      '/leave-policies?cursor=next-1',
    ]);
  });

  it('creates a policy with one key per command, then clears the form and refreshes the list', async () => {
    const user = userEvent.setup();
    let created = false;
    const requests = stubApi(({ method }) => {
      if (method === 'POST') {
        created = true;
        return {
          status: 201,
          body: { policy: ANNUAL, asOf: '2026-10-07', timezone: 'Africa/Kinshasa' },
        };
      }
      return { status: 200, body: page(created ? [ANNUAL] : []) };
    });
    await show();
    await screen.findByTestId('policies-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    await waitFor(() => {
      expect(screen.getAllByTestId('policy-row')).toHaveLength(1);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      code: 'ANNUAL',
      names: { en: 'Annual leave', fr: 'Congé annuel' },
      unit: 'DAYS',
      balanceMode: 'TRACKED',
      annualEntitlement: 18.5,
      minimumServiceDays: 90,
      approvalRoute: 'MANAGER',
      payrollEffect: 'PAID',
      effectiveFrom: '2026-01-01',
    });
    expect(posts[0]!.key).toMatch(/^web-[0-9a-f-]{36}$/u);
    expect(screen.getByLabelText(k.form.code)).toHaveValue('');
    expect(screen.getByLabelText(k.form.nameFr)).toHaveValue('');
    expect(screen.getByLabelText(k.form.balanceMode)).toHaveValue('');
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      k.created.replace('{{name}}', ANNUAL.names[locale]).replace('{{code}}', 'ANNUAL'),
    );
  });

  it('retries a failed attempt with the same key and uses a new key once the command changes', async () => {
    const user = userEvent.setup();
    let posts = 0;
    const requests = stubApi(({ method }) => {
      if (method !== 'POST') return { status: 200, body: page([]) };
      posts += 1;
      return posts === 1
        ? { status: 0 }
        : posts === 2
          ? { status: 500, body: problem('INTERNAL_ERROR', 500) }
          : { status: 201, body: { policy: SICK, asOf: '2026-10-07', timezone: 'UTC' } };
    });
    await show();
    await screen.findByTestId('policies-empty');
    await fill(user, { balanceMode: 'UNTRACKED', unit: 'HOURS' });
    const submit = screen.getByRole('button', { name: k.form.submit });
    await user.click(submit);
    const network = await screen.findByTestId('create-error');
    expect(network).toHaveTextContent(c.errors.network);
    expect(network).toHaveFocus();
    await user.click(submit);
    expect(await screen.findByTestId('create-error')).toHaveTextContent(c.errors.generic);
    await user.clear(screen.getByLabelText(k.form.minimumServiceDays));
    await user.type(screen.getByLabelText(k.form.minimumServiceDays), '30');
    await user.click(submit);
    await waitFor(() => {
      expect(screen.queryByTestId('create-error')).toBeNull();
    });
    const keys = requests.filter((r) => r.method === 'POST').map((r) => r.key);
    expect(keys).toHaveLength(3);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
    const bodies = requests
      .filter((r) => r.method === 'POST')
      .map((r) => JSON.parse(r.body) as Record<string, unknown>);
    expect(bodies[0]).not.toHaveProperty('annualEntitlement');
  });

  it('validates in the browser, naming each field, and sends nothing', async () => {
    const user = userEvent.setup();
    const requests = stubApi(() => ({ status: 200, body: page([]) }));
    const { container } = await show();
    await screen.findByTestId('policies-empty');
    await fill(user, {
      code: 'a',
      nameEn: 'x',
      annualEntitlement: '1.005',
      minimumServiceDays: '3651',
    });
    await user.type(screen.getByLabelText(k.form.effectiveTo), '2025-12-31');
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const summary = await screen.findByTestId('create-problems');
    expect(
      within(summary)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual([
      k.problems.code,
      k.problems.nameEn,
      k.problems.annualEntitlement,
      k.problems.minimumServiceDays,
      k.problems.effectiveTo,
    ]);
    expect(screen.getByLabelText(k.form.code)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(k.form.nameFr)).not.toHaveAttribute('aria-invalid');
    await waitFor(() => {
      expect(summary).toHaveFocus();
    });
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
    await expectAccessible(container);
  });

  it.each([
    [
      'conflict',
      { status: 409, body: problem('LEAVE_POLICY_CODE_EXISTS', 409) },
      c.errors.LEAVE_POLICY_CODE_EXISTS,
    ],
    [
      'idempotency conflict',
      { status: 409, body: problem('IDEMPOTENCY_KEY_REUSED', 409) },
      c.errors.IDEMPOTENCY_KEY_REUSED,
    ],
    [
      'rate limit',
      { status: 429, body: problem('RATE_LIMITED', 429), headers: { 'Retry-After': '30' } },
      c.errors.RATE_LIMITED,
    ],
    ['forbidden', { status: 403, body: problem('MFA_REQUIRED', 403) }, k.unauthorized],
  ])('shows the %s state with the stable message and reference', async (_name, reply, message) => {
    const user = userEvent.setup();
    stubApi(({ method }) => (method === 'POST' ? reply : { status: 200, body: page([]) }));
    await show();
    await screen.findByTestId('policies-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const alert = await screen.findByTestId('create-error');
    expect(alert).toHaveTextContent(message);
    expect(alert).toHaveTextContent('corr-leave-1234');
    if (reply.status === 429) {
      expect(alert).toHaveTextContent(c.employees.retryAfter_other.replace('{{count}}', '30'));
    }
    // The form is kept so the administrator can correct it.
    expect(screen.getByLabelText(k.form.code)).toHaveValue('ANNUAL');
  });

  it('marks the fields the server names and never shows submitted values from a refusal', async () => {
    const user = userEvent.setup();
    stubApi(({ method }) =>
      method === 'POST'
        ? {
            status: 400,
            body: problem('VALIDATION_FAILED', 400, {
              fields: [
                { field: 'names.fr', constraint: 'FORMAT' },
                { field: 'annualEntitlement', constraint: 'RANGE' },
                { field: 'injected <b>value</b>', constraint: 'FORMAT' },
              ],
            }),
          }
        : { status: 200, body: page([]) },
    );
    await show();
    await screen.findByTestId('policies-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.submit }));
    const alert = await screen.findByTestId('create-error');
    expect(
      within(alert)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([k.problems.nameFr, k.problems.annualEntitlement]);
    expect(alert).not.toHaveTextContent('injected');
    expect(screen.getByLabelText(k.form.nameFr)).toHaveAttribute('aria-invalid', 'true');
  });

  it('shows a general list error with a retry, and the forbidden state without one', async () => {
    const user = userEvent.setup();
    let calls = 0;
    stubApi(() => {
      calls += 1;
      return calls === 1
        ? { status: 503, body: problem('INTERNAL_ERROR', 503) }
        : { status: 200, body: page([ANNUAL]) };
    });
    await show();
    expect(await screen.findByTestId('list-error')).toHaveTextContent(c.errors.generic);
    await user.click(screen.getByRole('button', { name: k.retry }));
    expect(await screen.findByTestId('policies')).toBeInTheDocument();

    vi.unstubAllGlobals();
    stubApi(() => ({ status: 403, body: problem('ACCESS_DENIED', 403) }));
    await show();
    const denied = await screen.findAllByTestId('list-error');
    expect(denied.at(-1)).toHaveTextContent(k.unauthorized);
  });

  it('is unavailable to an employee before any request', async () => {
    const requests = stubApi(() => ({ status: 200, body: page([]) }));
    await renderWithSession(
      <Routes>
        <Route
          path="/admin/leave-policies"
          element={
            <RequireRole
              requiredRole="tenant-admin"
              deniedKey="leavePolicies.unauthorized"
              signInKey="leavePolicies.signInRequired"
            >
              <LeavePoliciesPage />
            </RequireRole>
          }
        />
      </Routes>,
      sessionWithRoles(['employee']),
      locale,
      '/admin/leave-policies',
    );
    expect(await screen.findByText(k.unauthorized)).toBeInTheDocument();
    expect(requests).toHaveLength(0);
  });
});

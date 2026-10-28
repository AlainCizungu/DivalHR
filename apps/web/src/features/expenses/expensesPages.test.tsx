import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { AdminExpensesPage } from './AdminExpensesPage';
import { ExpenseApprovalsPage } from './ExpenseApprovalsPage';
import { MyExpensesPage } from './MyExpensesPage';

const ID = '11111111-0000-4000-8000-000000000001';
const EMPLOYEE = {
  id: '22222222-0000-4000-8000-000000000002',
  employeeNumber: 'E-0042',
  givenNames: 'Ana',
  familyName: 'Mukendi',
};

const expense = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: ID,
  employee: EMPLOYEE,
  state: 'DRAFT',
  cycle: 1,
  revision: 1,
  version: 1,
  businessDate: '2026-10-20',
  currency: 'USD',
  amount: '12.50',
  amountMinorUnits: 1250,
  category: 'TRANSPORT',
  merchant: 'Taxi <Gombe>',
  description: 'Course aller-retour vers le client',
  receipt: null,
  createdAt: '2026-10-20T08:00:00Z',
  submittedAt: null,
  decidedAt: null,
  lastActionAt: '2026-10-20T08:00:00Z',
  editable: true,
  ...overrides,
});
const detail = (overrides: Record<string, unknown> = {}, history: unknown[] = []) => ({
  expense: expense(overrides),
  revisions: [
    {
      revision: 1,
      businessDate: '2026-10-20',
      currency: 'USD',
      amount: '12.50',
      amountMinorUnits: 1250,
      category: 'TRANSPORT',
      merchant: 'Taxi <Gombe>',
      description: 'Course aller-retour vers le client',
      receipt: null,
      createdAt: '2026-10-20T08:00:00Z',
      submittedAt: null,
    },
  ],
  history: [
    {
      sequence: 1,
      action: 'CREATED',
      occurredAt: '2026-10-20T08:00:00Z',
      actorRole: 'employee',
      fromState: null,
      toState: 'DRAFT',
      revision: 1,
      receiptPresent: false,
      returnReason: null,
    },
    ...history,
  ],
});
const page = (items: unknown[]) => ({ items, nextCursor: null, asOf: '2026-10-26T10:00:00Z' });
const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-exp-9999',
});

type Recorded = { method: string; path: string; body: string; key: string | null };
type Reply = { status: number; body?: unknown };

function stub(handler: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: incoming.method === 'GET' ? '' : await incoming.clone().text(),
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = handler(recorded);
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

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('MVP-081 employee expenses (%s)', (locale) => {
  const k = resources[locale].common.expenses;
  const show = () =>
    renderWithSession(<MyExpensesPage />, sessionWithRoles(['employee']), locale, '/me/expenses');

  it('lists expenses with the currency code, the fixed notice and unchanged text', async () => {
    stub(() => ({
      status: 200,
      body: page([
        expense(),
        expense({
          id: 'b',
          currency: 'CDF',
          amount: '125000',
          state: 'APPROVED',
          merchant: 'Marché',
        }),
      ]),
    }));
    const { container } = await show();
    const rows = await screen.findAllByTestId('my-expense');
    expect(rows).toHaveLength(2);
    expect(within(rows[0] as HTMLElement).getByTestId('expense-amount')).toHaveTextContent('USD');
    expect(within(rows[1] as HTMLElement).getByTestId('expense-amount')).toHaveTextContent('CDF');
    expect(rows[0]).toHaveTextContent('Taxi <Gombe>');
    expect(screen.getByTestId('approval-notice')).toHaveTextContent(k.notice);
    await expectAccessible(container);
  });

  it('refuses an invalid form without calling the server, then creates with the exact body', async () => {
    const requests = stub((r) => {
      if (r.method === 'POST') {
        return {
          status: 201,
          body: {
            id: ID,
            state: 'DRAFT',
            cycle: 1,
            revision: 1,
            version: 1,
            currency: 'USD',
            amount: '12.50',
            amountMinorUnits: 1250,
            category: 'TRANSPORT',
            receiptPresent: false,
            occurredAt: 'x',
          },
        };
      }
      return r.path.startsWith(`/me/expenses/${ID}`)
        ? { status: 200, body: detail() }
        : { status: 200, body: page([]) };
    });
    const user = userEvent.setup();
    const { container } = await show();
    await user.click(await screen.findByTestId('new-expense'));
    await user.click(screen.getByTestId('save-expense'));
    expect(screen.getByTestId('form-problems')).toBeVisible();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await expectAccessible(container);

    await user.type(screen.getByLabelText(new RegExp(`^${k.fields.amount}`, 'u')), '12,5');
    await user.selectOptions(screen.getByLabelText(k.fields.category), 'TRANSPORT');
    await user.type(screen.getByLabelText(k.fields.merchant), 'Taxi Gombe');
    await user.type(
      screen.getByLabelText(k.fields.description),
      'Course aller-retour vers le client',
    );
    await user.click(screen.getByTestId('save-expense'));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe('/me/expenses');
    expect(post?.key).toMatch(/^web-/u);
    const body = JSON.parse(post?.body ?? '{}') as Record<string, unknown>;
    expect(body).toMatchObject({
      currency: 'USD',
      amount: '12.50',
      category: 'TRANSPORT',
      merchant: 'Taxi Gombe',
    });
    expect(typeof body.amount).toBe('string');
    expect(await screen.findByTestId('expense-detail')).toBeVisible();
  });

  it('shows the manager’s reason on a returned expense and lets the employee change it', async () => {
    stub(() => ({
      status: 200,
      body: detail({ state: 'RETURNED', version: 4, revision: 1 }, [
        {
          sequence: 4,
          action: 'RETURNED',
          occurredAt: '2026-10-22T08:00:00Z',
          actorRole: 'manager',
          fromState: 'SUBMITTED',
          toState: 'RETURNED',
          revision: 1,
          receiptPresent: false,
          returnReason: 'Joindre le reçu <svp>',
        },
      ]),
    }));
    // The list and the detail share one stub; the list reads `items`.
    const user = userEvent.setup();
    stub((r) =>
      r.path === '/me/expenses?limit=100'
        ? { status: 200, body: page([expense({ state: 'RETURNED', version: 4 })]) }
        : {
            status: 200,
            body: detail({ state: 'RETURNED', version: 4 }, [
              {
                sequence: 4,
                action: 'RETURNED',
                occurredAt: '2026-10-22T08:00:00Z',
                actorRole: 'manager',
                fromState: 'SUBMITTED',
                toState: 'RETURNED',
                revision: 1,
                receiptPresent: false,
                returnReason: 'Joindre le reçu <svp>',
              },
            ]),
          },
    );
    await show();
    await user.click(await screen.findByRole('button', { name: /Taxi/u }));
    expect(await screen.findByTestId('returned-note')).toHaveTextContent('Joindre le reçu <svp>');
    expect(screen.getByRole('button', { name: k.me.edit })).toBeVisible();
  });

  it('asks for confirmation before withdrawing, then sends the version and a stable key', async () => {
    const requests = stub((r) => {
      if (r.method === 'POST') {
        return { status: 200, body: { id: ID, state: 'WITHDRAWN', version: 3 } };
      }
      return r.path === '/me/expenses?limit=100'
        ? {
            status: 200,
            body: page([expense({ state: 'SUBMITTED', editable: false, version: 2 })]),
          }
        : { status: 200, body: detail({ state: 'SUBMITTED', editable: false, version: 2 }) };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: /Taxi/u }));
    await user.click(await screen.findByTestId('withdraw-expense'));
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.click(screen.getByTestId('confirm-withdraw'));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/me/expenses/${ID}/withdraw`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 2 });
    expect(post?.key).toMatch(/^web-/u);
  });

  it('shows the stable route message without naming anyone', async () => {
    stub((r) => {
      if (r.method === 'POST')
        return { status: 409, body: problem('EXPENSE_APPROVAL_ROUTE_UNAVAILABLE', 409) };
      return r.path === '/me/expenses?limit=100'
        ? { status: 200, body: page([expense()]) }
        : { status: 200, body: detail() };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: /Taxi/u }));
    await user.click(await screen.findByTestId('submit-expense'));
    const alert = await screen.findByTestId('expense-error');
    expect(alert).toHaveAttribute('data-kind', 'route');
    expect(alert).toHaveTextContent(
      resources[locale].common.errors.EXPENSE_APPROVAL_ROUTE_UNAVAILABLE,
    );
  });

  it('says so when the employee link is missing', async () => {
    stub(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await show();
    expect(await screen.findByTestId('expenses-error')).toHaveAttribute(
      'data-kind',
      'linkRequired',
    );
  });
});

describe.each(['en', 'fr'] as const)('MVP-081 manager approvals (%s)', (locale) => {
  const k = resources[locale].common.expenses;
  const show = () =>
    renderWithSession(
      <ExpenseApprovalsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/expense-approvals',
    );
  const queued = () =>
    expense({
      state: 'SUBMITTED',
      editable: false,
      version: 2,
      submittedAt: '2026-10-21T08:00:00Z',
    });

  it('lists the queue with employee, currency and no totals', async () => {
    stub(() => ({
      status: 200,
      body: page([queued(), { ...queued(), id: 'b', currency: 'CDF', amount: '50000' }]),
    }));
    const { container } = await show();
    const items = await screen.findAllByTestId('approval-item');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Ana Mukendi');
    expect(items[1]).toHaveTextContent('CDF');
    expect(screen.queryByText(/total/iu)).toBeNull();
    await expectAccessible(container);
  });

  it('approves only after confirmation, with the expected version', async () => {
    const requests = stub((r) => {
      if (r.method === 'POST')
        return { status: 200, body: { id: ID, state: 'APPROVED', version: 3 } };
      return r.path.startsWith('/me/expense-approvals?')
        ? { status: 200, body: page([queued()]) }
        : { status: 200, body: detail({ state: 'SUBMITTED', editable: false, version: 2 }) };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: /Taxi/u }));
    await user.click(await screen.findByTestId('approve-expense'));
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    expect(screen.getByTestId('approve-confirm')).toHaveTextContent(k.notice);
    await user.click(screen.getByTestId('confirm-approve'));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/me/expense-approvals/${ID}/approve`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 2 });
  });

  it('refuses a short reason locally and sends a valid one', async () => {
    const requests = stub((r) => {
      if (r.method === 'POST')
        return { status: 200, body: { id: ID, state: 'RETURNED', version: 3 } };
      return r.path.startsWith('/me/expense-approvals?')
        ? { status: 200, body: page([queued()]) }
        : { status: 200, body: detail({ state: 'SUBMITTED', editable: false, version: 2 }) };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: /Taxi/u }));
    await user.click(await screen.findByTestId('return-expense'));
    const form = screen.getByTestId('return-form');
    await user.type(within(form).getByLabelText(k.approvals.reason), 'court');
    await user.click(screen.getByTestId('confirm-return'));
    expect(within(form).getByText(k.problems.reason)).toBeVisible();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.clear(within(form).getByLabelText(k.approvals.reason));
    await user.type(within(form).getByLabelText(k.approvals.reason), 'Merci de joindre le reçu');
    await user.click(screen.getByTestId('confirm-return'));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      expectedVersion: 2,
      reason: 'Merci de joindre le reçu',
    });
  });

  it('shows an empty queue', async () => {
    stub(() => ({ status: 200, body: page([]) }));
    await show();
    expect(await screen.findByTestId('approvals-empty')).toHaveTextContent(k.approvals.empty);
  });
});

describe.each(['en', 'fr'] as const)('MVP-081 administrator view (%s)', (locale) => {
  it('observes only: no button changes an expense', async () => {
    stub((r) =>
      r.path.startsWith('/expenses?')
        ? { status: 200, body: page([expense({ state: 'APPROVED', editable: false, version: 3 })]) }
        : { status: 200, body: detail({ state: 'APPROVED', editable: false, version: 3 }) },
    );
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <AdminExpensesPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/expenses',
    );
    await user.click(await screen.findByRole('button', { name: /Taxi/u }));
    const detailView = await screen.findByTestId('admin-expense-detail');
    const names = within(detailView)
      .getAllByRole('button')
      .map((b) => b.textContent);
    expect(names).toHaveLength(1);
    expect(names[0]).toBe(resources[locale].common.expenses.back);
    expect(screen.getByTestId('approval-notice')).toBeVisible();
    await expectAccessible(container);
  });
});

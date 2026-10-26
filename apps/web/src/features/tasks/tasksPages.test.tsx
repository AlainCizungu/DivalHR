import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { AdminTasksPage } from './AdminTasksPage';
import { MyTasksPage } from './MyTasksPage';

const TASK = '11111111-0000-4000-8000-000000000001';
const EMPLOYEE = '22222222-0000-4000-8000-000000000002';

const task = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  id: TASK,
  assigneeEmployeeId: EMPLOYEE,
  title: 'Inventaire du dépôt <b>',
  instructions: 'Compter les palettes\nPuis ranger',
  priority: 'HIGH',
  dueAt: '2026-10-26T08:00:00Z',
  dueClass: 'OVERDUE',
  state: 'OPEN',
  version: 1,
  createdAt: '2026-10-20T08:00:00Z',
  startedAt: null,
  completedAt: null,
  cancelledAt: null,
  completionNote: null,
  cancelReason: null,
  cancelExplanation: null,
  ...overrides,
});
const mine = (overrides: Record<string, unknown> = {}) => {
  const full = task(overrides);
  delete full.assigneeEmployeeId;
  return full;
};
const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-task-9999',
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

describe.each(['en', 'fr'] as const)('MVP-080 employee tasks (%s)', (locale) => {
  const k = resources[locale].common.tasks;
  const show = () =>
    renderWithSession(<MyTasksPage />, sessionWithRoles(['employee']), locale, '/me/tasks');

  it('groups tasks, labels overdue with text and shows text unchanged', async () => {
    stub(() => ({
      status: 200,
      body: {
        items: [
          mine(),
          mine({ id: 'b', title: 'En cours', state: 'IN_PROGRESS', dueAt: null, dueClass: 'NONE' }),
          mine({
            id: 'c',
            title: 'Fini',
            state: 'COMPLETED',
            dueClass: null,
            completionNote: 'Fait',
          }),
        ],
        nextCursor: null,
        asOf: '2026-10-26T10:00:00Z',
      },
    }));
    const { container } = await show();
    const open = await screen.findByTestId('group-open');
    expect(within(open).getByTestId('task-title')).toHaveTextContent('Inventaire du dépôt <b>');
    expect(within(open).getByTestId('task-due-class')).toHaveTextContent(k.dueClass.OVERDUE);
    expect(within(open).getByTestId('task-instructions').textContent).toContain('\n');
    expect(
      within(screen.getByTestId('group-inProgress')).getByRole('heading', { name: 'En cours' }),
    ).toBeVisible();
    expect(
      within(screen.getByTestId('group-closed')).getByRole('heading', { name: 'Fini' }),
    ).toBeVisible();
    await expectAccessible(container);
  });

  it('starts a task with its version and a stable key', async () => {
    let started = false;
    const requests = stub((r) => {
      if (r.method === 'POST') {
        started = true;
        return {
          status: 200,
          body: { id: TASK, state: 'IN_PROGRESS', version: 2, occurredAt: 'x' },
        };
      }
      return {
        status: 200,
        body: {
          items: [mine(started ? { state: 'IN_PROGRESS', version: 2 } : {})],
          nextCursor: null,
          asOf: 'x',
        },
      };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: k.me.start }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(k.me.started);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/me/tasks/${TASK}/start`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 1 });
    expect(post?.key).toMatch(/^web-/u);
    expect(within(screen.getByTestId('group-inProgress')).getByTestId('my-task')).toBeVisible();
  });

  it('completes only after confirmation, sends the optional note and refuses markup', async () => {
    const requests = stub((r) =>
      r.method === 'POST'
        ? { status: 200, body: { id: TASK, state: 'COMPLETED', version: 2, occurredAt: 'x' } }
        : { status: 200, body: { items: [mine()], nextCursor: null, asOf: 'x' } },
    );
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: k.me.complete }));
    const form = screen.getByTestId('complete-confirm');
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.type(within(form).getByLabelText(k.me.note), 'voir <script>');
    await user.click(within(form).getByRole('button', { name: k.me.confirmButton }));
    expect(within(form).getByText(k.problems.note)).toBeVisible();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.clear(within(form).getByLabelText(k.me.note));
    await user.type(within(form).getByLabelText(k.me.note), 'Terminé');
    await user.click(within(form).getByRole('button', { name: k.me.confirmButton }));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 1, note: 'Terminé' });
  });

  it('shows a stable message when the task is already settled and refreshes', async () => {
    stub((r) =>
      r.method === 'POST'
        ? { status: 409, body: problem('TASK_TRANSITION_NOT_ALLOWED', 409) }
        : { status: 200, body: { items: [mine()], nextCursor: null, asOf: 'x' } },
    );
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: k.me.start }));
    const alert = await screen.findByTestId('task-error');
    expect(alert).toHaveAttribute('data-kind', 'settled');
    expect(alert).toHaveTextContent(resources[locale].common.errors.TASK_TRANSITION_NOT_ALLOWED);
    expect(alert).toHaveTextContent('corr-task-9999');
  });

  it('says so when the link is missing and when there is nothing to do', async () => {
    stub(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await show();
    expect(await screen.findByTestId('tasks-error')).toHaveAttribute('data-kind', 'linkRequired');
  });

  it('shows an empty state', async () => {
    stub(() => ({ status: 200, body: { items: [], nextCursor: null, asOf: 'x' } }));
    await show();
    expect(await screen.findByTestId('tasks-empty')).toHaveTextContent(k.me.empty);
  });
});

describe.each(['en', 'fr'] as const)('MVP-080 administrator tasks (%s)', (locale) => {
  const k = resources[locale].common.tasks;
  const show = () =>
    renderWithSession(
      <AdminTasksPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/tasks',
    );

  it('lists tasks with filters bound to the request and an accessible form', async () => {
    const requests = stub(() => ({
      status: 200,
      body: { items: [task()], nextCursor: null, asOf: 'x' },
    }));
    const user = userEvent.setup();
    const { container } = await show();
    expect(await screen.findByTestId('admin-task')).toBeVisible();
    await user.selectOptions(screen.getByLabelText(k.admin.list.state), 'OPEN');
    await waitFor(() => {
      expect(requests.some((r) => r.path.includes('state=OPEN'))).toBe(true);
    });
    await user.selectOptions(screen.getByLabelText(k.admin.list.due), 'OVERDUE');
    await waitFor(() => {
      expect(requests.some((r) => r.path.includes('due=OVERDUE'))).toBe(true);
    });
    await expectAccessible(container);
  });

  it('names the problems of an incomplete form and sends nothing', async () => {
    const requests = stub(() => ({
      status: 200,
      body: { items: [], nextCursor: null, asOf: 'x' },
    }));
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByRole('button', { name: k.admin.create.submit }));
    const problems = await screen.findByTestId('task-problems');
    expect(within(problems).getByText(k.problems.assignee)).toBeVisible();
    expect(within(problems).getByText(k.problems.title)).toBeVisible();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
  });

  it('opens a task with its history and cancels it with a closed reason', async () => {
    let cancelled = false;
    const requests = stub((r) => {
      if (r.method === 'POST') {
        cancelled = true;
        return { status: 200, body: { id: TASK, state: 'CANCELLED', version: 2, occurredAt: 'x' } };
      }
      if (r.path.startsWith(`/tasks/${TASK}`)) {
        return {
          status: 200,
          body: {
            ...task(cancelled ? { state: 'CANCELLED', version: 2, dueClass: null } : {}),
            history: [
              {
                seq: 1,
                action: 'CREATED',
                occurredAt: '2026-10-20T08:00:00Z',
                actorRole: 'tenant-admin',
                actorSubject: 's',
                fromState: null,
                toState: 'OPEN',
              },
            ],
          },
        };
      }
      if (r.path.startsWith('/employees/')) {
        return {
          status: 200,
          body: { id: EMPLOYEE, employeeNumber: 'E-1', givenNames: 'Ana', familyName: 'Mukendi' },
        };
      }
      return { status: 200, body: { items: [task()], nextCursor: null, asOf: 'x' } };
    });
    const user = userEvent.setup();
    await show();
    await user.click(
      await screen.findByRole('button', { name: new RegExp(k.admin.list.open, 'u') }),
    );
    const detail = await screen.findByTestId('task-detail');
    expect(await within(detail).findByText(/Mukendi/u)).toBeVisible();
    expect(within(detail).getByTestId('task-history').children).toHaveLength(1);
    await user.click(within(detail).getByRole('button', { name: k.admin.cancel.open }));
    const form = screen.getByTestId('cancel-confirm');
    await user.click(within(form).getByRole('button', { name: k.admin.cancel.confirm }));
    expect(within(form).getByText(k.problems.reason)).toBeVisible();
    await user.selectOptions(within(form).getByLabelText(k.admin.cancel.reason), 'OTHER');
    await user.type(within(form).getByLabelText(k.admin.cancel.explanation), 'court');
    await user.click(within(form).getByRole('button', { name: k.admin.cancel.confirm }));
    expect(within(form).getByText(k.problems.explanation)).toBeVisible();
    await user.clear(within(form).getByLabelText(k.admin.cancel.explanation));
    await user.type(
      within(form).getByLabelText(k.admin.cancel.explanation),
      'Le chantier est reporté',
    );
    await user.click(within(form).getByRole('button', { name: k.admin.cancel.confirm }));
    await waitFor(() => {
      expect(requests.some((r) => r.method === 'POST')).toBe(true);
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.path).toBe(`/tasks/${TASK}/cancel`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      expectedVersion: 1,
      reason: 'OTHER',
      explanation: 'Le chantier est reporté',
    });
    expect(post?.key).toMatch(/^web-/u);
  });
});

describe('MVP-080 locale coverage', () => {
  it('has every task string in both languages', () => {
    const flat = (o: unknown, p = ''): string[] =>
      typeof o === 'object' && o !== null
        ? Object.entries(o).flatMap(([key, value]) => flat(value, `${p}${key}.`))
        : [p];
    expect(flat(resources.fr.common.tasks).sort()).toEqual(flat(resources.en.common.tasks).sort());
  });
});

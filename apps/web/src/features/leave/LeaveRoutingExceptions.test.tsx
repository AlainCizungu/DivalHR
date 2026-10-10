import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { LeaveRoutingExceptionsPage } from './LeaveApprovalInbox';
import { approvalFailureOf } from './leaveApprovals';

const FIRST = '11111111-0000-4000-8000-000000000001';
const SECOND = '22222222-0000-4000-8000-000000000002';

const exception = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  submittedAt: '2026-10-09T10:00:00Z',
  employee: {
    id: `e-${id}`,
    employeeNumber: 'E-0042',
    givenNames: 'Bénédicte',
    familyName: '<b>Mbuyi</b>',
  },
  policyId: 'aaaaaaaa-0000-4000-8000-00000000000a',
  policyVersionId: 'aaaaaaaa-0000-4000-8000-0000000000a1',
  policyCode: 'ANNUAL',
  policyNames: { en: 'Annual leave', fr: 'Congé annuel' },
  unit: 'DAYS',
  amount: 2.5,
  startDate: '2026-10-20',
  endDate: '2026-10-22',
  approvalRoute: 'MANAGER',
  state: 'PENDING',
  exceptionReason: 'NO_QUALIFYING_MANAGER',
  ...overrides,
});

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-exception-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

function stubApi(items: unknown[], post: (request: Recorded) => Reply | Promise<Reply>) {
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
      const reply: Reply =
        incoming.method === 'GET' && path.startsWith('/leave-routing-exceptions')
          ? { status: 200, body: { items, nextCursor: null } }
          : await post(recorded);
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

describe('MVP-041E routing-exception failures', () => {
  it('maps the terminal and gone codes of an override to the items that leave the queue', () => {
    const response = (status: number) => new Response(null, { status });
    expect(
      approvalFailureOf(response(404), problem('LEAVE_REQUEST_NOT_FOUND', 404), 'exception')
        .messageKey,
    ).toBe('leaveApprovals.exception.gone');
    expect(
      approvalFailureOf(response(404), problem('LEAVE_REQUEST_NOT_FOUND', 404), 'admin').messageKey,
    ).toBe('errors.LEAVE_REQUEST_NOT_FOUND');
    expect(
      approvalFailureOf(response(409), problem('LEAVE_REQUEST_ALREADY_AMENDED', 409), 'exception')
        .kind,
    ).toBe('alreadyAmended');
    expect(
      approvalFailureOf(response(409), problem('LEAVE_REQUEST_ALREADY_CANCELLED', 409), 'exception')
        .kind,
    ).toBe('alreadyCancelled');
    expect(approvalFailureOf(response(403), problem('MFA_REQUIRED', 403), 'exception').kind).toBe(
      'mfaRequired',
    );
  });
});

describe.each(['fr', 'en'] as const)('MVP-041E leave routing exceptions (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.leaveApprovals;
  const show = () =>
    renderWithSession(
      <LeaveRoutingExceptionsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/leave-routing-exceptions',
    );

  async function reachConfirmation(
    user: ReturnType<typeof userEvent.setup>,
    decision: 'approve' | 'reject',
  ) {
    const [first] = await screen.findAllByTestId('approval-item');
    await user.click(
      within(first!).getByRole('button', {
        name: decision === 'approve' ? /^(Approuver|Approve)/u : /^(Rejeter|Reject)/u,
      }),
    );
    const dialog = screen.getByTestId('decision-dialog');
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Motif secret confirmé.');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    return dialog;
  }

  it('lists exceptions as plain text with a conspicuous override notice', async () => {
    stubApi([exception(FIRST), exception(SECOND)], () => ({ status: 500 }));
    const { container } = await show();
    expect(
      await screen.findByRole('heading', { level: 1, name: k.exception.title }),
    ).toBeInTheDocument();
    expect(await screen.findAllByTestId('approval-item')).toHaveLength(2);
    expect(screen.getByTestId('override-notice')).toHaveTextContent(k.exception.override);
    expect(screen.getAllByTestId('exception-reason')[0]).toHaveTextContent(k.exception.reason);
    expect(screen.getByTestId('approval-items')).toHaveTextContent('<b>Mbuyi</b>');
    expect(screen.getByTestId('approval-items').querySelector('b')).toBeNull();
    await expectAccessible(container);
  });

  it('shows the empty queue', async () => {
    stubApi([], () => ({ status: 500 }));
    await show();
    expect(await screen.findByTestId('inbox-empty')).toHaveTextContent(k.exception.empty);
  });

  it('decides an exception as an explicit override with one stable key', async () => {
    const requests = stubApi([exception(FIRST), exception(SECOND)], () => ({
      status: 200,
      body: {
        requestId: FIRST,
        decisionId: 'dddddddd-0000-4000-8000-00000000000d',
        state: 'APPROVED',
        decidedAt: '2026-10-09T11:00:00Z',
      },
    }));
    const user = userEvent.setup();
    const { container } = await show();
    const dialog = await reachConfirmation(user, 'approve');
    expect(within(dialog).getByTestId('dialog-override-notice')).toHaveTextContent(
      k.exception.override,
    );
    expect(within(dialog).getByTestId('decision-confirm')).toHaveTextContent(
      locale === 'fr' ? 'décision administrative exceptionnelle' : 'administrator override',
    );
    const confirm = within(dialog).getByRole('button', {
      name: k.exception.confirmButton.APPROVED,
    });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await expectAccessible(container);
    await user.click(confirm);
    await waitFor(() => {
      expect(screen.getAllByTestId('approval-item')).toHaveLength(1);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.path).toBe(`/leave-routing-exceptions/${FIRST}/decision`);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      decision: 'APPROVED',
      reasonLocale: locale,
      reason: 'Motif secret confirmé.',
    });
    expect(posts[0]!.key).toMatch(/^web-/u);
    expect(window.localStorage.length).toBe(0);
    expect(window.location.href).not.toContain('secret');
  });

  it('removes and announces an exception that now has a manager', async () => {
    stubApi([exception(FIRST), exception(SECOND)], () => ({
      status: 404,
      body: problem('LEAVE_REQUEST_NOT_FOUND', 404),
    }));
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'reject');
    await user.click(
      within(dialog).getByRole('button', { name: k.exception.confirmButton.REJECTED }),
    );
    const alert = await within(dialog).findByTestId('decision-error');
    expect(alert).toHaveTextContent(k.exception.gone);
    expect(alert).not.toHaveTextContent('secret');
    expect(screen.getByTestId('announcer')).toHaveTextContent(k.exception.gone);
    expect(
      within(dialog).getByRole('button', { name: k.exception.confirmButton.REJECTED }),
    ).toBeDisabled();
    await user.click(within(dialog).getAllByRole('button', { name: k.form.cancel }).at(-1)!);
    await waitFor(() => {
      expect(screen.getAllByTestId('approval-item')).toHaveLength(1);
    });
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: k.inbox.title })).toHaveFocus();
    });
  });

  it.each([
    ['LEAVE_REQUEST_ALREADY_AMENDED', 'alreadyAmended'],
    ['LEAVE_REQUEST_ALREADY_CANCELLED', 'alreadyCancelled'],
    ['LEAVE_REQUEST_ALREADY_DECIDED', 'alreadyDecided'],
  ] as const)('drops a request that became terminal (%s)', async (code, kind) => {
    stubApi([exception(FIRST)], () => ({ status: 409, body: problem(code, 409) }));
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'approve');
    await user.click(
      within(dialog).getByRole('button', { name: k.exception.confirmButton.APPROVED }),
    );
    const alert = await within(dialog).findByTestId('decision-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent(c.errors[code]);
  });
});

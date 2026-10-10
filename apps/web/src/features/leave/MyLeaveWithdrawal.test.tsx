import { resources } from '@divalhr/localization';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { amendmentFailureOf } from './myLeaveAmendment';
import { cancellationFailureOf } from './myLeaveCancellation';
import {
  withdrawable,
  withdrawalBodyOf,
  withdrawalFailureOf,
  withdrawalProblemsOf,
} from './myLeaveWithdrawal';
import { MyLeavePage } from './MyLeavePage';

const APPROVED = 'cccccccc-0000-4000-8000-00000000000c';
const TODAY = 'dddddddd-0000-4000-8000-00000000000d';
const PENDING = 'eeeeeeee-0000-4000-8000-00000000000e';
const AS_OF = '2026-10-09';
const NAMES = { en: 'Annual leave', fr: 'Congé annuel' };
const APPROVAL = {
  id: 'ffffffff-0000-4000-8000-00000000000f',
  outcome: 'APPROVED',
  reasonLocale: 'fr',
  reason: 'Accordé, bon repos.',
  decidedAt: '2026-10-09T11:00:00Z',
};

const request = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  policyId: 'aaaaaaaa-0000-4000-8000-00000000000a',
  policyVersionId: 'aaaaaaaa-0000-4000-8000-0000000000a1',
  policyCode: 'ANNUAL',
  policyNames: NAMES,
  unit: 'DAYS',
  amount: 2.5,
  startDate: '2026-10-20',
  endDate: '2026-10-22',
  state: 'APPROVED',
  submittedAt: '2026-10-08T10:00:00Z',
  decision: APPROVAL,
  cancellation: null,
  amendment: null,
  amendedFromRequestId: null,
  withdrawal: null,
  ...overrides,
});

const approved = () => request(APPROVED);
const startsToday = () => request(TODAY, { startDate: AS_OF, endDate: AS_OF });
const pending = () => request(PENDING, { state: 'PENDING', decision: null });
const withdrawnWith = (reason: string, reasonLocale: 'en' | 'fr') =>
  request(APPROVED, {
    state: 'WITHDRAWN',
    withdrawal: {
      id: '99999999-0000-4000-8000-000000000009',
      reasonLocale,
      reason,
      withdrawnAt: '2026-10-09T12:00:00Z',
    },
  });

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-withdraw-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

/** The page's API: no policy, the history the test sets (as of AS_OF), the withdrawals it answers. */
function stubApi(history: () => unknown[], post: (request: Recorded) => Reply | Promise<Reply>) {
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
      let reply: Reply;
      if (incoming.method === 'GET' && path.startsWith('/me/leave-policies')) {
        reply = {
          status: 200,
          body: { items: [], nextCursor: null, asOf: AS_OF, timezone: 'Africa/Kinshasa' },
        };
      } else if (incoming.method === 'GET' && path.startsWith('/me/leave-requests')) {
        reply = { status: 200, body: { items: history(), nextCursor: null, asOf: AS_OF } };
      } else {
        reply = await post(recorded);
      }
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

const receipt = {
  withdrawalId: '99999999-0000-4000-8000-000000000009',
  requestId: APPROVED,
  state: 'WITHDRAWN',
  withdrawnAt: '2026-10-09T12:00:00Z',
};

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MVP-041F withdrawal rules', () => {
  it('uses the shared reason grammar and normalizes like the server', () => {
    expect([...withdrawalProblemsOf({ reasonLocale: 'fr', reason: '' })]).toEqual(['reason']);
    expect([...withdrawalProblemsOf({ reasonLocale: 'fr', reason: 'a\u200Bb' })]).toEqual([
      'reason',
    ]);
    expect(withdrawalBodyOf({ reasonLocale: 'fr', reason: '\u3000 Cafe\u0301 \u00A0' })).toEqual({
      reasonLocale: 'fr',
      reason: 'Café',
    });
  });

  it('offers the withdrawal only for approved leave starting after the business date', () => {
    expect(withdrawable(approved() as never, AS_OF)).toBe(true);
    expect(withdrawable(startsToday() as never, AS_OF)).toBe(false);
    expect(withdrawable(request(TODAY, { startDate: '2026-10-08' }) as never, AS_OF)).toBe(false);
    expect(withdrawable(pending() as never, AS_OF)).toBe(false);
    expect(withdrawable(withdrawnWith('x', 'fr') as never, AS_OF)).toBe(false);
    expect(withdrawable(approved() as never, null)).toBe(false);
  });

  it('maps every material refusal to a stable kind; params outside the allow-list are dropped', () => {
    const response = (status: number, headers: Record<string, string> = {}) =>
      new Response(null, { status, headers });
    for (const [code, kind] of [
      ['LEAVE_REQUEST_ALREADY_WITHDRAWN', 'alreadyWithdrawn'],
      ['LEAVE_REQUEST_NOT_APPROVED', 'notApproved'],
      ['LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED', 'windowClosed'],
      ['LEAVE_REQUEST_ALREADY_CANCELLED', 'alreadyCancelled'],
      ['LEAVE_REQUEST_ALREADY_AMENDED', 'alreadyAmended'],
      ['LEAVE_REQUEST_ALREADY_DECIDED', 'alreadyDecided'],
      ['LEAVE_REQUEST_NOT_FOUND', 'unavailable'],
      ['IDEMPOTENCY_KEY_REUSED', 'conflict'],
    ] as const) {
      expect(withdrawalFailureOf(response(409), problem(code, 409)).kind).toBe(kind);
    }
    expect(
      withdrawalFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'reason', constraint: 'FORMAT' },
            { field: 'state', constraint: 'UNKNOWN_PROPERTY' },
          ],
        }),
      ).fields,
    ).toEqual(['reason']);
    expect(withdrawalFailureOf(response(500), undefined).messageKey).toBe('errors.generic');
    // A withdrawn request seen from a stale cancellation or amendment dialog.
    expect(
      cancellationFailureOf(response(409), problem('LEAVE_REQUEST_ALREADY_WITHDRAWN', 409)).kind,
    ).toBe('alreadyWithdrawn');
    expect(
      amendmentFailureOf(response(409), problem('LEAVE_REQUEST_ALREADY_WITHDRAWN', 409)).kind,
    ).toBe('alreadyWithdrawn');
  });
});

describe.each(['fr', 'en'] as const)('MVP-041F withdraw my approved leave (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.myLeave;
  const other = locale === 'fr' ? 'en' : 'fr';
  const show = () =>
    renderWithSession(<MyLeavePage />, sessionWithRoles(['employee']), locale, '/me/leave');

  async function reachConfirmation(user: ReturnType<typeof userEvent.setup>, reason: string) {
    await user.click(await screen.findByTestId('withdraw-request'));
    const dialog = screen.getByTestId('withdraw-dialog');
    await user.type(within(dialog).getByLabelText(k.withdraw.reason), reason);
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.review }));
    return dialog;
  }

  it('offers the withdrawal only for approved leave that has not started', async () => {
    stubApi(
      () => [approved(), startsToday(), pending(), withdrawnWith('Plus besoin.', 'fr')],
      () => ({ status: 500 }),
    );
    const { container } = await show();
    const rows = await screen.findAllByTestId('my-request');
    expect(rows).toHaveLength(4);
    expect(screen.getAllByTestId('withdraw-request')).toHaveLength(1);
    expect(within(rows[0]!).getByTestId('withdraw-request')).toHaveTextContent(k.withdraw.action);
    expect(within(rows[1]!).queryByTestId('withdraw-request')).toBeNull();
    expect(within(rows[2]!).queryByTestId('withdraw-request')).toBeNull();
    expect(within(rows[3]!).getByTestId('request-state')).toHaveTextContent(k.state.WITHDRAWN);
    await expectAccessible(container);
  });

  it('withdraws after an explicit confirmation, shows approval and withdrawal together', async () => {
    let withdrawn = false;
    const reason = '  <i>Mes</i> projets ont changé  ';
    const requests = stubApi(
      () => [withdrawn ? withdrawnWith('<i>Mes</i> projets ont changé', other) : approved()],
      () => {
        withdrawn = true;
        return { status: 201, body: receipt };
      },
    );
    const user = userEvent.setup();
    const { container } = await show();
    await user.click(await screen.findByTestId('withdraw-request'));
    const dialog = screen.getByTestId('withdraw-dialog');
    expect(dialog).toHaveAttribute('open');
    // The approved period, amount, policy and current approval are shown before anything else.
    const summary = within(dialog).getByTestId('withdraw-summary');
    expect(summary).toHaveTextContent(NAMES[locale]);
    expect(summary).toHaveTextContent('ANNUAL');
    expect(within(dialog).getByTestId('withdraw-approval')).toHaveTextContent(k.state.APPROVED);
    const textarea = within(dialog).getByLabelText(k.withdraw.reason);
    await waitFor(() => {
      expect(textarea).toHaveFocus();
    });
    expect(within(dialog).getByLabelText(k.withdraw.reasonLocale)).toHaveValue(locale);
    await expectAccessible(container);

    await user.click(within(dialog).getByRole('button', { name: k.withdraw.review }));
    const problems = within(dialog).getByTestId('withdraw-problems');
    await waitFor(() => {
      expect(problems).toHaveFocus();
    });
    expect(problems).toHaveTextContent(k.withdraw.problems.reason);

    await user.type(textarea, reason);
    await user.selectOptions(within(dialog).getByLabelText(k.withdraw.reasonLocale), other);
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.review }));
    const quoted = within(dialog).getByTestId('withdraw-reason');
    expect(quoted.textContent).toBe('<i>Mes</i> projets ont changé');
    expect(quoted).toHaveAttribute('lang', other);
    expect(quoted.querySelector('i')).toBeNull();
    const confirm = within(dialog).getByRole('button', { name: k.withdraw.confirmButton });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await expectAccessible(container);
    await user.click(confirm);

    await waitFor(() => {
      expect(screen.getByTestId('request-state')).toHaveTextContent(k.state.WITHDRAWN);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.path).toBe(`/me/leave-requests/${APPROVED}/withdrawal`);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      reasonLocale: other,
      reason: '<i>Mes</i> projets ont changé',
    });
    expect(posts[0]!.key).toMatch(/^web-/u);
    expect(dialog).not.toHaveAttribute('open');
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      locale === 'fr' ? 'a été retiré' : 'was withdrawn',
    );
    // The approval and the withdrawal, each reason as written in its own language.
    const cell = screen.getByTestId('request-decision');
    expect(cell.querySelector('[lang="fr"]')?.textContent).toBe('Accordé, bon repos.');
    const shown = within(cell).getByTestId('request-withdrawal').querySelector(`[lang="${other}"]`);
    expect(shown?.textContent).toBe('<i>Mes</i> projets ont changé');
    expect(cell.querySelector('i')).toBeNull();
    expect(cell).toHaveTextContent(locale === 'fr' ? 'Retirée le' : 'Withdrawn on');
    expect(screen.queryByTestId('withdraw-request')).toBeNull();
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: k.history.title })).toHaveFocus();
    });
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(window.location.href).not.toContain('projets');
  });

  it('keeps one key for a retry, a new key after an edit, and one request in flight', async () => {
    const replies: Reply[] = [{ status: 0 }, { status: 201, body: receipt }];
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests = stubApi(
      () => [approved()],
      async () => {
        const reply = replies.shift() ?? { status: 500 };
        if (reply.status === 201) await gate;
        return reply;
      },
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.confirmButton }));
    expect(await within(dialog).findByTestId('withdraw-error')).toHaveTextContent(c.errors.network);
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.confirmButton }));
    // One request in flight; every other consequential action is disabled meanwhile.
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.sending }));
    await act(async () => {
      release();
      await gate;
    });
    await waitFor(() => {
      expect(dialog).not.toHaveAttribute('open');
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[1]!.key).toBe(posts[0]!.key);

    replies.push({ status: 500, body: problem('INTERNAL_ERROR', 500) });
    const again = await reachConfirmation(user, 'Autre motif.');
    await user.click(within(again).getByRole('button', { name: k.withdraw.confirmButton }));
    await within(again).findByTestId('withdraw-error');
    const last = requests.filter((r) => r.method === 'POST').at(-1)!;
    expect(last.key).not.toBe(posts[0]!.key);
  });

  it('shares the one-action guard with cancellation and amendment', async () => {
    stubApi(
      () => [approved(), pending()],
      () => ({ status: 500 }),
    );
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('withdraw-request'));
    expect(screen.getByTestId('cancel-request')).toBeDisabled();
    expect(screen.getByTestId('amend-request')).toBeDisabled();
    await user.click(
      within(screen.getByTestId('withdraw-dialog')).getByRole('button', {
        name: k.withdraw.keep,
      }),
    );
    await user.click(screen.getByTestId('cancel-request'));
    expect(screen.getByTestId('withdraw-request')).toBeDisabled();
  });

  it.each([
    ['LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED', 'windowClosed'],
    ['LEAVE_REQUEST_ALREADY_WITHDRAWN', 'alreadyWithdrawn'],
    ['LEAVE_REQUEST_NOT_APPROVED', 'notApproved'],
  ] as const)('refreshes the item when it is settled (%s)', async (code, kind) => {
    let settled = false;
    stubApi(
      () => [
        settled
          ? kind === 'alreadyWithdrawn'
            ? withdrawnWith('Plus besoin.', 'fr')
            : kind === 'notApproved'
              ? { ...pending(), id: APPROVED }
              : { ...approved(), startDate: AS_OF }
          : approved(),
      ],
      () => {
        settled = true;
        return { status: 409, body: problem(code, 409) };
      },
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.confirmButton }));
    const alert = await within(dialog).findByTestId('withdraw-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent(k.withdraw[kind]);
    await waitFor(() => {
      expect(alert).toHaveFocus();
    });
    expect(within(dialog).getByRole('button', { name: k.withdraw.confirmButton })).toBeDisabled();
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.keep }));
    await waitFor(() => {
      expect(screen.queryByTestId('withdraw-request')).toBeNull();
    });
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: k.history.title })).toHaveFocus();
    });
  });

  it.each([
    [{ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }, 'linkRequired'],
    [
      { status: 429, body: problem('RATE_LIMITED', 429), headers: { 'Retry-After': '12' } },
      'rateLimited',
    ],
    [{ status: 409, body: problem('IDEMPOTENCY_KEY_REUSED', 409) }, 'conflict'],
    [{ status: 404, body: problem('LEAVE_REQUEST_NOT_FOUND', 404) }, 'unavailable'],
    [{ status: 500, body: problem('INTERNAL_ERROR', 500) }, 'general'],
  ] as const)('shows a refused withdrawal (%#)', async (reply, kind) => {
    stubApi(
      () => [approved()],
      () => reply,
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.confirmButton }));
    const alert = await within(dialog).findByTestId('withdraw-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent('corr-withdraw-1234');
    if (kind === 'rateLimited') expect(alert).toHaveTextContent('12');
    expect(alert).not.toHaveTextContent('Plus besoin');
  });

  it('returns to the form with the server-named fields', async () => {
    stubApi(
      () => [approved()],
      () => ({
        status: 400,
        body: problem('VALIDATION_FAILED', 400, {
          fields: [{ field: 'reason', constraint: 'FORMAT' }],
        }),
      }),
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.confirmButton }));
    const alert = await within(dialog).findByTestId('withdraw-error');
    expect(alert).toHaveTextContent(k.withdraw.problems.reason);
    expect(within(dialog).getByLabelText(k.withdraw.reason)).toHaveAttribute(
      'aria-invalid',
      'true',
    );
  });

  it('closes on « keep » and returns focus to the opening button, sending nothing', async () => {
    const requests = stubApi(
      () => [approved()],
      () => ({ status: 500 }),
    );
    const user = userEvent.setup();
    await show();
    const opener = await screen.findByTestId('withdraw-request');
    expect(opener).toHaveAccessibleName(
      expect.stringContaining(
        locale === 'fr'
          ? 'Retirer le congé approuvé Congé annuel'
          : 'Withdraw the approved leave Annual leave',
      ),
    );
    await user.click(opener);
    const dialog = screen.getByTestId('withdraw-dialog');
    await user.click(within(dialog).getByRole('button', { name: k.withdraw.keep }));
    expect(dialog).not.toHaveAttribute('open');
    await waitFor(() => {
      expect(opener).toHaveFocus();
    });
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });

  it('shows a withdrawn request, both reasons unchanged, never an actor', async () => {
    stubApi(
      () => [withdrawnWith('Family plans moved.', 'en')],
      () => ({ status: 500 }),
    );
    const { container } = await show();
    const cell = await screen.findByTestId('request-decision');
    expect(cell.querySelector('[lang="en"]')?.textContent).toBe('Family plans moved.');
    expect(cell.querySelector('[lang="fr"]')?.textContent).toBe('Accordé, bon repos.');
    expect(screen.getByTestId('my-requests')).not.toHaveTextContent('subject');
    await expectAccessible(container);
  });
});

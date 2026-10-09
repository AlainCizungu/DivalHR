import { resources } from '@divalhr/localization';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import {
  cancellationBodyOf,
  cancellationFailureOf,
  cancellationProblemsOf,
} from './myLeaveCancellation';
import { MyLeavePage } from './MyLeavePage';

const PENDING = 'cccccccc-0000-4000-8000-00000000000c';
const DECIDED = 'dddddddd-0000-4000-8000-00000000000d';
const CANCELLED = 'eeeeeeee-0000-4000-8000-00000000000e';
const NAMES = { en: 'Annual leave', fr: 'Congé annuel' };

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
  state: 'PENDING',
  submittedAt: '2026-10-09T10:00:00Z',
  decision: null,
  cancellation: null,
  ...overrides,
});

const decided = request(DECIDED, {
  state: 'APPROVED',
  startDate: '2026-11-02',
  endDate: '2026-11-02',
  decision: {
    id: 'ffffffff-0000-4000-8000-00000000000f',
    outcome: 'APPROVED',
    reasonLocale: 'fr',
    reason: 'Accordé.',
    decidedAt: '2026-10-09T11:00:00Z',
  },
});

const cancelledWith = (reason: string, reasonLocale: 'en' | 'fr') =>
  request(CANCELLED, {
    state: 'CANCELLED',
    cancellation: {
      id: '99999999-0000-4000-8000-000000000009',
      reasonLocale,
      reason,
      cancelledAt: '2026-10-09T12:00:00Z',
    },
  });

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-cancel-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

/** The page's API: no policy, the history the test sets, the cancellations it answers. */
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
          body: { items: [], nextCursor: null, asOf: '2026-10-09', timezone: 'Africa/Kinshasa' },
        };
      } else if (incoming.method === 'GET' && path.startsWith('/me/leave-requests')) {
        reply = { status: 200, body: { items: history(), nextCursor: null } };
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
  requestId: PENDING,
  cancellationId: '99999999-0000-4000-8000-000000000009',
  state: 'CANCELLED',
  cancelledAt: '2026-10-09T12:00:00Z',
};

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MVP-041C cancellation form rules', () => {
  it('uses the shared reason grammar and normalizes like the server', () => {
    expect([...cancellationProblemsOf({ reasonLocale: 'fr', reason: '' })]).toEqual(['reason']);
    expect([...cancellationProblemsOf({ reasonLocale: 'fr', reason: 'a\u200Bb' })]).toEqual([
      'reason',
    ]);
    expect(cancellationProblemsOf({ reasonLocale: 'en', reason: '😀😀' }).size).toBe(0);
    expect(
      cancellationProblemsOf({ reasonLocale: 'de' as 'en', reason: 'Fine reason' }).has(
        'reasonLocale',
      ),
    ).toBe(true);
    expect(cancellationBodyOf({ reasonLocale: 'fr', reason: '\u3000 Cafe\u0301 \u00A0' })).toEqual({
      reasonLocale: 'fr',
      reason: 'Café',
    });
  });

  it('keeps only allow-listed details of a problem', () => {
    const response = (status: number, headers: Record<string, string> = {}) =>
      new Response(null, { status, headers });
    expect(
      cancellationFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'reason', constraint: 'FORMAT' },
            { field: 'employeeId', constraint: 'UNKNOWN_PROPERTY' },
          ],
        }),
      ).fields,
    ).toEqual(['reason']);
    expect(
      cancellationFailureOf(response(429, { 'Retry-After': '9' }), problem('RATE_LIMITED', 429))
        .retryAfter,
    ).toBe(9);
    expect(
      cancellationFailureOf(response(409), problem('LEAVE_REQUEST_ALREADY_CANCELLED', 409)).kind,
    ).toBe('alreadyCancelled');
    expect(cancellationFailureOf(response(500), undefined).messageKey).toBe('errors.generic');
  });
});

describe.each(['fr', 'en'] as const)('MVP-041C cancel my leave request (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.myLeave;
  const other = locale === 'fr' ? 'en' : 'fr';
  const show = () =>
    renderWithSession(<MyLeavePage />, sessionWithRoles(['employee']), locale, '/me/leave');

  /** Opens the dialog of the pending request and types a reason. */
  async function open(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByTestId('cancel-request'));
    const dialog = screen.getByTestId('cancel-dialog');
    return dialog;
  }

  async function reachConfirmation(user: ReturnType<typeof userEvent.setup>, reason: string) {
    const dialog = await open(user);
    await user.type(within(dialog).getByLabelText(k.cancel.reason), reason);
    await user.click(within(dialog).getByRole('button', { name: k.cancel.review }));
    return dialog;
  }

  it('offers cancellation only for pending requests', async () => {
    stubApi(
      () => [request(PENDING), decided, cancelledWith('Plus besoin.', 'fr')],
      () => ({ status: 500 }),
    );
    const { container } = await show();
    const rows = await screen.findAllByTestId('my-request');
    expect(rows).toHaveLength(3);
    expect(screen.getAllByTestId('cancel-request')).toHaveLength(1);
    expect(within(rows[0]!).getByTestId('cancel-request')).toHaveTextContent(k.cancel.action);
    expect(within(rows[1]!).queryByTestId('cancel-request')).toBeNull();
    expect(within(rows[2]!).queryByTestId('cancel-request')).toBeNull();
    expect(within(rows[2]!).getByTestId('request-state')).toHaveTextContent(k.state.CANCELLED);
    await expectAccessible(container);
  });

  it('cancels after an explicit confirmation, refreshes the item and announces it', async () => {
    let cancelled = false;
    const reason = '  <i>Mes</i> dates ont changé  ';
    const requests = stubApi(
      () => [
        cancelled
          ? { ...cancelledWith('<i>Mes</i> dates ont changé', other), id: PENDING }
          : request(PENDING),
      ],
      () => {
        cancelled = true;
        return { status: 200, body: receipt };
      },
    );
    const user = userEvent.setup();
    const { container } = await show();
    const opener = await screen.findByTestId('cancel-request');
    await user.click(opener);
    const dialog = screen.getByTestId('cancel-dialog');
    expect(dialog).toHaveAttribute('open');
    const textarea = within(dialog).getByLabelText(k.cancel.reason);
    await waitFor(() => {
      expect(textarea).toHaveFocus();
    });
    // The reason's language defaults to the interface language and is always sent.
    expect(within(dialog).getByLabelText(k.cancel.reasonLocale)).toHaveValue(locale);
    await expectAccessible(container);

    // Nothing typed: the summary takes the focus and names the field.
    await user.click(within(dialog).getByRole('button', { name: k.cancel.review }));
    const problems = within(dialog).getByTestId('cancel-problems');
    await waitFor(() => {
      expect(problems).toHaveFocus();
    });
    expect(problems).toHaveTextContent(k.cancel.problems.reason);
    expect(textarea).toHaveAttribute('aria-invalid', 'true');

    await user.type(textarea, reason);
    await user.selectOptions(within(dialog).getByLabelText(k.cancel.reasonLocale), other);
    await user.click(within(dialog).getByRole('button', { name: k.cancel.review }));
    // The exact normalized reason, as text, in its own language.
    const quoted = within(dialog).getByTestId('cancel-reason');
    expect(quoted).toHaveTextContent('<i>Mes</i> dates ont changé');
    expect(quoted.textContent).toBe('<i>Mes</i> dates ont changé');
    expect(quoted).toHaveAttribute('lang', other);
    expect(quoted.querySelector('i')).toBeNull();
    const confirm = within(dialog).getByRole('button', { name: k.cancel.confirmButton });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await expectAccessible(container);
    await user.click(confirm);

    await waitFor(() => {
      expect(screen.getByTestId('request-state')).toHaveTextContent(k.state.CANCELLED);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.path).toBe(`/me/leave-requests/${PENDING}/cancellation`);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      reasonLocale: other,
      reason: '<i>Mes</i> dates ont changé',
    });
    expect(posts[0]!.key).toMatch(/^web-/u);
    expect(dialog).not.toHaveAttribute('open');
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      locale === 'fr' ? 'a été annulée' : 'was cancelled',
    );
    // The cancelled item shows the reason as written, as text, in its language.
    const shown = screen.getByTestId('request-decision').querySelector(`[lang="${other}"]`);
    expect(shown).toHaveTextContent('<i>Mes</i> dates ont changé');
    expect(screen.getByTestId('request-decision').querySelector('i')).toBeNull();
    expect(screen.queryByTestId('cancel-request')).toBeNull();
    // The button is gone: focus goes to the history heading.
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: k.history.title })).toHaveFocus();
    });
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(window.location.href).not.toContain('dates');
  });

  it('keeps one key for a retry, a new key after an edit, and one request in flight', async () => {
    const replies: Reply[] = [{ status: 0 }, { status: 200, body: receipt }];
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests = stubApi(
      () => [request(PENDING)],
      async () => {
        const reply = replies.shift() ?? { status: 500 };
        if (reply.status === 200) await gate;
        return reply;
      },
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.cancel.confirmButton }));
    expect(await within(dialog).findByTestId('cancel-error')).toHaveTextContent(c.errors.network);
    // The same command keeps its key.
    const confirm = within(dialog).getByRole('button', { name: k.cancel.confirmButton });
    await user.click(confirm);
    // One request in flight: a second activation sends nothing.
    await user.click(within(dialog).getByRole('button', { name: k.cancel.sending }));
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

    // A new opening with an edited reason is a new command: a new key.
    replies.push({ status: 500, body: problem('INTERNAL_ERROR', 500) });
    const again = await reachConfirmation(user, 'Autre motif.');
    await user.click(within(again).getByRole('button', { name: k.cancel.confirmButton }));
    await within(again).findByTestId('cancel-error');
    const last = requests.filter((r) => r.method === 'POST').at(-1)!;
    expect(last.key).not.toBe(posts[0]!.key);
  });

  it.each([
    ['LEAVE_REQUEST_ALREADY_CANCELLED', 'alreadyCancelled'],
    ['LEAVE_REQUEST_ALREADY_DECIDED', 'alreadyDecided'],
  ] as const)('refreshes the list when the request is settled (%s)', async (code, kind) => {
    let settled = false;
    stubApi(
      () => [
        settled
          ? kind === 'alreadyDecided'
            ? { ...decided, id: PENDING }
            : { ...cancelledWith('Plus besoin.', 'fr'), id: PENDING }
          : request(PENDING),
      ],
      () => {
        settled = true;
        return { status: 409, body: problem(code, 409) };
      },
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.cancel.confirmButton }));
    const alert = await within(dialog).findByTestId('cancel-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent(k.cancel[kind]);
    await waitFor(() => {
      expect(alert).toHaveFocus();
    });
    expect(within(dialog).getByRole('button', { name: k.cancel.confirmButton })).toBeDisabled();
    await waitFor(() => {
      expect(screen.getByTestId('request-state')).toHaveTextContent(
        kind === 'alreadyDecided' ? k.state.APPROVED : k.state.CANCELLED,
      );
    });
    await user.click(within(dialog).getByRole('button', { name: k.cancel.keep }));
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
  ] as const)('shows a refused cancellation (%#)', async (reply, kind) => {
    stubApi(
      () => [request(PENDING)],
      () => reply,
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Plus besoin.');
    await user.click(within(dialog).getByRole('button', { name: k.cancel.confirmButton }));
    const alert = await within(dialog).findByTestId('cancel-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent('corr-cancel-1234');
    if (kind === 'rateLimited') expect(alert).toHaveTextContent('12');
    expect(alert).not.toHaveTextContent('Plus besoin');
  });

  it('returns to the form with the server-named fields', async () => {
    stubApi(
      () => [request(PENDING)],
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
    await user.click(within(dialog).getByRole('button', { name: k.cancel.confirmButton }));
    const alert = await within(dialog).findByTestId('cancel-error');
    expect(alert).toHaveTextContent(k.cancel.problems.reason);
    expect(within(dialog).getByLabelText(k.cancel.reason)).toHaveAttribute('aria-invalid', 'true');
  });

  it('closes on « keep » and returns focus to the opening button, sending nothing', async () => {
    const requests = stubApi(
      () => [request(PENDING)],
      () => ({ status: 500 }),
    );
    const user = userEvent.setup();
    await show();
    const opener = await screen.findByTestId('cancel-request');
    expect(opener).toHaveAccessibleName(
      expect.stringContaining(
        locale === 'fr' ? 'Annuler la demande Congé annuel' : 'Cancel the request Annual leave',
      ),
    );
    const dialog = await open(user);
    await user.click(within(dialog).getByRole('button', { name: k.cancel.keep }));
    expect(dialog).not.toHaveAttribute('open');
    await waitFor(() => {
      expect(opener).toHaveFocus();
    });
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });

  it('shows a cancelled request and its reason unchanged in either interface language', async () => {
    stubApi(
      () => [cancelledWith('Family plans moved.', 'en')],
      () => ({ status: 500 }),
    );
    await show();
    const cell = await screen.findByTestId('request-decision');
    const shown = cell.querySelector('[lang="en"]');
    expect(shown?.textContent).toBe('Family plans moved.');
    expect(cell).toHaveTextContent(locale === 'fr' ? 'Annulée le' : 'Cancelled on');
    expect(screen.getByTestId('my-requests')).not.toHaveTextContent('subject');
  });
});

import { resources } from '@divalhr/localization';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { amendmentBodyOf, amendmentFailureOf, amendmentProblemsOf } from './myLeaveAmendment';
import { MyLeavePage } from './MyLeavePage';

const PENDING = 'cccccccc-0000-4000-8000-00000000000c';
const REPLACEMENT = 'bbbbbbbb-0000-4000-8000-00000000000b';
const DECIDED = 'dddddddd-0000-4000-8000-00000000000d';
const ANNUAL = 'aaaaaaaa-0000-4000-8000-00000000000a';
const FAMILY = 'aaaaaaaa-0000-4000-8000-0000000000fa';
const NAMES = { en: 'Annual leave', fr: 'Congé annuel' };
const FAMILY_NAMES = { en: 'Family event', fr: 'Événement familial' };

const policy = (id: string, code: string, names: { en: string; fr: string }) => ({
  id,
  versionId: `${id.slice(0, 34)}v1`,
  code,
  versionNumber: 1,
  names,
  unit: 'DAYS',
  balanceMode: 'UNTRACKED',
  annualEntitlement: null,
  minimumServiceDays: 0,
  approvalRoute: 'MANAGER',
  payrollEffect: 'PAID',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  status: 'ACTIVE',
});

const POLICIES = [policy(ANNUAL, 'ANNUAL', NAMES), policy(FAMILY, 'FAMILY', FAMILY_NAMES)];

const request = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  policyId: ANNUAL,
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
  amendment: null,
  amendedFromRequestId: null,
  ...overrides,
});

const amendedWith = (reason: string, reasonLocale: 'en' | 'fr') =>
  request(PENDING, {
    state: 'AMENDED',
    amendment: {
      id: '99999999-0000-4000-8000-000000000009',
      replacementRequestId: REPLACEMENT,
      reasonLocale,
      reason,
      amendedAt: '2026-10-09T12:00:00Z',
    },
  });

const replacement = request(REPLACEMENT, {
  policyId: FAMILY,
  policyCode: 'FAMILY',
  policyNames: FAMILY_NAMES,
  amount: 3,
  startDate: '2026-10-21',
  endDate: '2026-10-23',
  submittedAt: '2026-10-09T12:00:00Z',
  amendedFromRequestId: PENDING,
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

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-amend-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

/** The page's API: two policies, the history the test sets, the amendments it answers. */
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
          body: {
            items: POLICIES,
            nextCursor: null,
            asOf: '2026-10-09',
            timezone: 'Africa/Kinshasa',
          },
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
  amendmentId: '99999999-0000-4000-8000-000000000009',
  originalRequestId: PENDING,
  replacementRequestId: REPLACEMENT,
  originalState: 'AMENDED',
  replacementState: 'PENDING',
  amendedAt: '2026-10-09T12:00:00Z',
};

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('MVP-041D amendment form rules', () => {
  const valid = {
    policyId: ANNUAL,
    startDate: '2026-10-20',
    endDate: '2026-10-21',
    amount: '2',
    reasonLocale: 'fr' as const,
    reason: 'Nouvelles dates.',
  };

  it('applies the submission rules and the shared reason grammar', () => {
    expect(amendmentProblemsOf(valid, '2026-10-09').size).toBe(0);
    expect([
      ...amendmentProblemsOf(
        { ...valid, startDate: '2026-10-01', reason: 'a\u200Bb' },
        '2026-10-09',
      ),
    ]).toEqual(['startDate', 'reason']);
    expect([...amendmentProblemsOf({ ...valid, endDate: '2026-10-19' }, null)]).toEqual([
      'endDate',
    ]);
    expect([...amendmentProblemsOf({ ...valid, amount: '1.234', policyId: '' }, null)]).toEqual([
      'policy',
      'amount',
    ]);
    expect(
      amendmentBodyOf({ ...valid, amount: '2,5', reason: '\u3000 Cafe\u0301 \u00A0' }),
    ).toEqual({
      policyId: ANNUAL,
      startDate: '2026-10-20',
      endDate: '2026-10-21',
      amount: 2.5,
      reasonLocale: 'fr',
      reason: 'Café',
    });
  });

  it('keeps only allow-listed details of a problem', () => {
    const response = (status: number, headers: Record<string, string> = {}) =>
      new Response(null, { status, headers });
    expect(
      amendmentFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'policyId', constraint: 'FORMAT' },
            { field: 'reason', constraint: 'FORMAT' },
            { field: 'employeeId', constraint: 'UNKNOWN_PROPERTY' },
          ],
        }),
      ).fields,
    ).toEqual(['policy', 'reason']);
    expect(
      amendmentFailureOf(
        response(409),
        problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: 'MINIMUM_SERVICE' }),
      ).messageKey,
    ).toBe('myLeave.notEligible.MINIMUM_SERVICE');
    expect(
      amendmentFailureOf(
        response(409),
        problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: '<script>' }),
      ).messageKey,
    ).toBe('errors.LEAVE_REQUEST_NOT_ELIGIBLE');
    expect(
      amendmentFailureOf(response(409), problem('LEAVE_REQUEST_ALREADY_AMENDED', 409)).kind,
    ).toBe('alreadyAmended');
    expect(amendmentFailureOf(response(500), undefined).messageKey).toBe('errors.generic');
  });
});

describe.each(['fr', 'en'] as const)('MVP-041D amend my leave request (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.myLeave;
  const other = locale === 'fr' ? 'en' : 'fr';
  const show = () =>
    renderWithSession(<MyLeavePage />, sessionWithRoles(['employee']), locale, '/me/leave');

  async function open(user: ReturnType<typeof userEvent.setup>) {
    const [first] = await screen.findAllByTestId('amend-request');
    await user.click(first!);
    return screen.getByTestId('amend-dialog');
  }

  async function reachConfirmation(user: ReturnType<typeof userEvent.setup>, reason: string) {
    const dialog = await open(user);
    await user.type(within(dialog).getByLabelText(k.amend.reason), reason);
    await user.click(within(dialog).getByRole('button', { name: k.amend.review }));
    return dialog;
  }

  it('offers amendment and cancellation only for pending requests', async () => {
    stubApi(
      () => [request(PENDING), decided, amendedWith('Plus tard.', 'fr')],
      () => ({ status: 500 }),
    );
    const { container } = await show();
    const rows = await screen.findAllByTestId('my-request');
    expect(rows).toHaveLength(3);
    expect(screen.getAllByTestId('amend-request')).toHaveLength(1);
    expect(within(rows[0]!).getByTestId('amend-request')).toHaveTextContent(k.amend.action);
    expect(within(rows[0]!).getByTestId('cancel-request')).toBeInTheDocument();
    expect(within(rows[2]!).getByTestId('request-state')).toHaveTextContent(k.state.AMENDED);
    expect(within(rows[2]!).queryByTestId('amend-request')).toBeNull();
    await expectAccessible(container);
  });

  it('replaces after a confirmation of old and new values and shows both linked entries', async () => {
    let amended = false;
    const reason = '  <b>Mes</b> dates ont changé  ';
    const requests = stubApi(
      () =>
        amended
          ? [replacement, { ...amendedWith('<b>Mes</b> dates ont changé', other) }]
          : [request(PENDING)],
      () => {
        amended = true;
        return { status: 201, body: receipt };
      },
    );
    const user = userEvent.setup();
    const { container } = await show();
    const dialog = await open(user);
    expect(dialog).toHaveAttribute('open');
    // The form starts from the current values; the reason's language from the interface.
    const select = within(dialog).getByLabelText(k.form.policy);
    await waitFor(() => {
      expect(select).toHaveFocus();
    });
    expect(select).toHaveValue(ANNUAL);
    expect(within(dialog).getByLabelText(k.form.startDate)).toHaveValue('2026-10-20');
    expect(within(dialog).getByLabelText(k.amend.reasonLocale)).toHaveValue(locale);
    await expectAccessible(container);

    // Nothing typed as the reason: the summary takes the focus and names the field.
    await user.click(within(dialog).getByRole('button', { name: k.amend.review }));
    const problems = within(dialog).getByTestId('amend-problems');
    await waitFor(() => {
      expect(problems).toHaveFocus();
    });
    expect(problems).toHaveTextContent(k.amend.problems.reason);

    await user.selectOptions(select, FAMILY);
    const start = within(dialog).getByLabelText(k.form.startDate);
    await user.clear(start);
    await user.type(start, '2026-10-21');
    const end = within(dialog).getByLabelText(k.form.endDate);
    await user.clear(end);
    await user.type(end, '2026-10-23');
    const amount = within(dialog).getByLabelText(k.form.amount.DAYS);
    await user.clear(amount);
    await user.type(amount, '3');
    await user.type(within(dialog).getByLabelText(k.amend.reason), reason);
    await user.selectOptions(within(dialog).getByLabelText(k.amend.reasonLocale), other);
    await user.click(within(dialog).getByRole('button', { name: k.amend.review }));

    // Old and new values, and the exact normalized reason as text in its own language.
    const comparison = within(dialog).getByTestId('amend-comparison');
    expect(comparison).toHaveTextContent(NAMES[locale]);
    expect(within(dialog).getByTestId('amend-after-policy')).toHaveTextContent(
      FAMILY_NAMES[locale],
    );
    expect(within(dialog).getByTestId('amend-after-amount')).toHaveTextContent('3');
    const quoted = within(dialog).getByTestId('amend-reason');
    expect(quoted.textContent).toBe('<b>Mes</b> dates ont changé');
    expect(quoted).toHaveAttribute('lang', other);
    expect(quoted.querySelector('b')).toBeNull();
    const confirm = within(dialog).getByRole('button', { name: k.amend.confirmButton });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await expectAccessible(container);
    await user.click(confirm);

    await waitFor(() => {
      expect(screen.getAllByTestId('my-request')).toHaveLength(2);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.path).toBe(`/me/leave-requests/${PENDING}/amendment`);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      policyId: FAMILY,
      startDate: '2026-10-21',
      endDate: '2026-10-23',
      amount: 3,
      reasonLocale: other,
      reason: '<b>Mes</b> dates ont changé',
    });
    expect(posts[0]!.key).toMatch(/^web-/u);
    expect(dialog).not.toHaveAttribute('open');
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      locale === 'fr' ? 'a été modifiée' : 'was amended',
    );
    // Both entries, linked both ways; the reason as written, as text.
    const [newer, older] = screen.getAllByTestId('my-request');
    expect(within(newer!).getByTestId('request-state')).toHaveTextContent(k.state.PENDING);
    expect(within(newer!).getByTestId('request-replaces')).toHaveTextContent(
      locale === 'fr' ? 'Remplace la demande du' : 'Replaces the request for',
    );
    expect(within(older!).getByTestId('request-state')).toHaveTextContent(k.state.AMENDED);
    expect(within(older!).getByTestId('request-replaced-by')).toHaveTextContent(
      locale === 'fr' ? 'Remplacée par la demande du' : 'Replaced by the request for',
    );
    const shown = within(older!).getByTestId('request-decision').querySelector(`[lang="${other}"]`);
    expect(shown?.textContent).toBe('<b>Mes</b> dates ont changé');
    expect(within(older!).getByTestId('request-decision').querySelector('b')).toBeNull();
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: k.history.title })).toHaveFocus();
    });
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(window.location.href).not.toContain('dates');
  });

  it('keeps one key for a retry, a new key after an edit, and one action in flight', async () => {
    const replies: Reply[] = [{ status: 0 }, { status: 201, body: receipt }];
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests = stubApi(
      () => [
        request(PENDING),
        request(DECIDED, { startDate: '2026-12-01', endDate: '2026-12-01' }),
      ],
      async () => {
        const reply = replies.shift() ?? { status: 500 };
        if (reply.status === 201) await gate;
        return reply;
      },
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Nouvelles dates.');
    // While the amendment dialog is open, no other consequential action can start.
    for (const button of [
      ...screen.getAllByTestId('amend-request'),
      ...screen.getAllByTestId('cancel-request'),
    ]) {
      expect(button).toBeDisabled();
    }
    await user.click(within(dialog).getByRole('button', { name: k.amend.confirmButton }));
    expect(await within(dialog).findByTestId('amend-error')).toHaveTextContent(c.errors.network);
    await user.click(within(dialog).getByRole('button', { name: k.amend.confirmButton }));
    await user.click(within(dialog).getByRole('button', { name: k.amend.sending }));
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
    await user.click(within(again).getByRole('button', { name: k.amend.confirmButton }));
    await within(again).findByTestId('amend-error');
    const last = requests.filter((r) => r.method === 'POST').at(-1)!;
    expect(last.key).not.toBe(posts[0]!.key);
  });

  it.each([
    ['LEAVE_REQUEST_ALREADY_AMENDED', 'alreadyAmended'],
    ['LEAVE_REQUEST_ALREADY_CANCELLED', 'alreadyCancelled'],
    ['LEAVE_REQUEST_ALREADY_DECIDED', 'alreadyDecided'],
  ] as const)('refreshes the list when the request is settled (%s)', async (code, kind) => {
    let settled = false;
    stubApi(
      () => [settled ? { ...decided, id: PENDING } : request(PENDING)],
      () => {
        settled = true;
        return { status: 409, body: problem(code, 409) };
      },
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Nouvelles dates.');
    await user.click(within(dialog).getByRole('button', { name: k.amend.confirmButton }));
    const alert = await within(dialog).findByTestId('amend-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent(k.amend[kind]);
    await waitFor(() => {
      expect(alert).toHaveFocus();
    });
    expect(within(dialog).getByRole('button', { name: k.amend.confirmButton })).toBeDisabled();
    await waitFor(() => {
      expect(screen.getByTestId('request-state')).toHaveTextContent(k.state.APPROVED);
    });
    await user.click(within(dialog).getByRole('button', { name: k.amend.keep }));
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
    [{ status: 409, body: problem('LEAVE_REQUEST_OVERLAP', 409) }, 'overlap'],
    [{ status: 409, body: problem('LEAVE_POLICY_NOT_REQUESTABLE', 409) }, 'policyUnavailable'],
    [
      {
        status: 409,
        body: problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: 'EMPLOYMENT_PERIOD' }),
      },
      'notEligible',
    ],
    [{ status: 404, body: problem('LEAVE_REQUEST_NOT_FOUND', 404) }, 'unavailable'],
    [{ status: 500, body: problem('INTERNAL_ERROR', 500) }, 'general'],
  ] as const)('shows a refused amendment without echoing it (%#)', async (reply, kind) => {
    stubApi(
      () => [request(PENDING)],
      () => reply,
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Nouvelles dates secrètes.');
    await user.click(within(dialog).getByRole('button', { name: k.amend.confirmButton }));
    const alert = await within(dialog).findByTestId('amend-error');
    expect(alert).toHaveAttribute('data-kind', kind);
    expect(alert).toHaveTextContent('corr-amend-1234');
    if (kind === 'rateLimited') expect(alert).toHaveTextContent('12');
    expect(alert).not.toHaveTextContent('secrètes');
    expect(alert).not.toHaveTextContent('2026-10-20');
  });

  it('returns to the form with the server-named fields', async () => {
    stubApi(
      () => [request(PENDING)],
      () => ({
        status: 400,
        body: problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'startDate', constraint: 'RANGE' },
            { field: 'reason', constraint: 'FORMAT' },
          ],
        }),
      }),
    );
    const user = userEvent.setup();
    await show();
    const dialog = await reachConfirmation(user, 'Nouvelles dates.');
    await user.click(within(dialog).getByRole('button', { name: k.amend.confirmButton }));
    const alert = await within(dialog).findByTestId('amend-error');
    expect(alert).toHaveTextContent(k.amend.problems.startDate);
    expect(alert).toHaveTextContent(k.amend.problems.reason);
    expect(within(dialog).getByLabelText(k.form.startDate)).toHaveAttribute('aria-invalid', 'true');
  });

  it('closes on « keep » and returns focus to the opening button, sending nothing', async () => {
    const requests = stubApi(
      () => [request(PENDING)],
      () => ({ status: 500 }),
    );
    const user = userEvent.setup();
    await show();
    const opener = await screen.findByTestId('amend-request');
    expect(opener).toHaveAccessibleName(
      expect.stringContaining(
        locale === 'fr' ? 'Modifier la demande Congé annuel' : 'Amend the request Annual leave',
      ),
    );
    const dialog = await open(user);
    await user.click(within(dialog).getByRole('button', { name: k.amend.keep }));
    expect(dialog).not.toHaveAttribute('open');
    await waitFor(() => {
      expect(opener).toHaveFocus();
    });
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });

  it('shows an amendment chain and an override decision without any actor', async () => {
    stubApi(
      () => [
        {
          ...replacement,
          state: 'REJECTED',
          decision: {
            id: 'ffffffff-0000-4000-8000-00000000000f',
            outcome: 'REJECTED',
            reasonLocale: 'fr',
            reason: 'Aucun responsable admissible.',
            decidedAt: '2026-10-09T11:00:00Z',
          },
        },
        amendedWith('Family plans moved.', 'en'),
      ],
      () => ({ status: 500 }),
    );
    await show();
    const rows = await screen.findAllByTestId('my-request');
    const cell = within(rows[1]!).getByTestId('request-decision');
    expect(cell.querySelector('[lang="en"]')?.textContent).toBe('Family plans moved.');
    expect(cell).toHaveTextContent(locale === 'fr' ? 'Modifiée le' : 'Amended on');
    expect(within(rows[0]!).getByTestId('request-state')).toHaveTextContent(k.state.REJECTED);
    const table = screen.getByTestId('my-requests');
    expect(table).not.toHaveTextContent('OVERRIDE');
    expect(table).not.toHaveTextContent('subject');
  });
});

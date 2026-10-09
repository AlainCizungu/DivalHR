import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { LeaveApprovalsPage, MyLeaveApprovalsPage } from './LeaveApprovalInbox';
import { MyLeavePage } from './MyLeavePage';
import {
  approvalFailureOf,
  codePoints,
  decisionBodyOf,
  decisionProblemsOf,
  normalizeReason,
  forbiddenCodePoint,
} from './leaveApprovals';

const item = (id: string, overrides: Record<string, unknown> = {}) => ({
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
  ...overrides,
});

const FIRST = '11111111-0000-4000-8000-000000000001';
const SECOND = '22222222-0000-4000-8000-000000000002';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-approval-1234',
});

const receipt = (requestId: string, state = 'APPROVED') => ({
  requestId,
  decisionId: 'dddddddd-0000-4000-8000-00000000000d',
  state,
  decidedAt: '2026-10-09T11:00:00Z',
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

/** An inbox with the given items; decisions answered by {@code post}. */
function inbox(
  base: string,
  items: unknown[],
  post?: (request: Recorded) => Reply | Promise<Reply>,
  nextCursor: string | null = null,
) {
  return stubApi((r) => {
    if (r.method === 'GET' && r.path.startsWith(base)) {
      return { status: 200, body: { items, nextCursor } };
    }
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

describe('MVP-041B reason grammar and failures', () => {
  it('normalizes and counts reasons like the server', () => {
    expect(normalizeReason('  Cafe\u0301  ')).toBe('Café');
    expect(codePoints('😀😀')).toBe(2);
    const ok = { decision: 'APPROVED' as const, reasonLocale: 'fr' as const, reason: ' Accordé ' };
    expect(decisionProblemsOf(ok).size).toBe(0);
    expect(decisionBodyOf(ok)).toEqual({
      decision: 'APPROVED',
      reasonLocale: 'fr',
      reason: 'Accordé',
    });
    for (const reason of ['', ' x ', 'a\nb', 'a\u0007b', 'a\u200bb', 'é'.repeat(501)]) {
      expect([...decisionProblemsOf({ ...ok, reason })], JSON.stringify(reason)).toEqual([
        'reason',
      ]);
    }
    expect(decisionProblemsOf({ ...ok, reason: '😀'.repeat(500) }).size).toBe(0);
  });

  it('applies grammar version 1 like LeaveReasonGrammar and people.leave_reason_valid', () => {
    const ok = { decision: 'REJECTED' as const, reasonLocale: 'en' as const, reason: '' };
    // Zero-width, joiner, bidi override, soft hyphen, tag, private use (BMP and plane 15),
    // noncharacters, an unpaired surrogate and C1 control.
    for (const reason of [
      'a\u200Bb',
      'a\u200Db',
      'a\u202Eb',
      'a\u00ADb',
      'a\u{E0041}b',
      'a\uE000b',
      'a\u{F0000}b',
      'a\uFFFEb',
      'a\u{10FFFF}b',
      'a\uD800b',
      'a\u0085b',
    ]) {
      expect([...decisionProblemsOf({ ...ok, reason })], JSON.stringify(reason)).toEqual([
        'reason',
      ]);
    }
    // Supplementary characters count once and are accepted; unassigned U+0378 is accepted.
    for (const reason of ['😀😀', '\u{20BB7}x', 'a\u0378b']) {
      expect(decisionProblemsOf({ ...ok, reason }).size, JSON.stringify(reason)).toBe(0);
    }
    expect(normalizeReason('\u3000\u2028 ok \u00A0\u0009')).toBe('ok');
    expect(normalizeReason('\u200B ok')).toBe('\u200B ok');
    expect(forbiddenCodePoint(0xfffe)).toBe(true);
    expect(forbiddenCodePoint(0xfffd)).toBe(false);
  });

  it('keeps only allow-listed details of a problem', () => {
    const response = (status: number, headers: Record<string, string> = {}) =>
      new Response(null, { status, headers });
    expect(
      approvalFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'reason', constraint: 'LENGTH' },
            { field: 'secret', constraint: 'FORMAT' },
          ],
        }),
        'manager',
      ).fields,
    ).toEqual(['reason']);
    expect(
      approvalFailureOf(
        response(409),
        problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: '<i>x</i>' }),
        'admin',
      ).messageKey,
    ).toBe('errors.LEAVE_REQUEST_NOT_ELIGIBLE');
    expect(approvalFailureOf(response(403), problem('MFA_REQUIRED', 403), 'admin').kind).toBe(
      'mfaRequired',
    );
    expect(
      approvalFailureOf(
        response(429, { 'Retry-After': '7' }),
        problem('RATE_LIMITED', 429),
        'admin',
      ).retryAfter,
    ).toBe(7);
  });
});

describe.each(['fr', 'en'] as const)('MVP-041B leave approvals (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.leaveApprovals;
  const other = locale === 'fr' ? 'en' : 'fr';
  const showManager = () =>
    renderWithSession(
      <MyLeaveApprovalsPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/leave/approvals',
    );
  const showAdmin = () =>
    renderWithSession(
      <LeaveApprovalsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/leave-approvals',
    );
  const confirmLabel = (decision: 'APPROVED' | 'REJECTED') => k.dialog.confirmButton[decision];

  /** Opens the dialog of the first item, types a reason and reaches the confirmation step. */
  async function reachConfirmation(
    user: ReturnType<typeof userEvent.setup>,
    decision: 'APPROVED' | 'REJECTED',
    reason: string,
  ) {
    const first = (await screen.findAllByTestId('approval-item'))[0] as HTMLElement;
    await user.click(within(first).getAllByRole('button')[decision === 'APPROVED' ? 0 : 1]!);
    const dialog = screen.getByTestId('decision-dialog');
    await user.type(within(dialog).getByLabelText(k.form.reason), reason);
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    return dialog;
  }

  it('lists the manager inbox as plain text, current language first, with locale amounts', async () => {
    inbox('/me/leave-approvals', [item(FIRST)]);
    const { container } = await showManager();
    const card = await screen.findByTestId('approval-item');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(k.manager.title);
    // A name is text, never markup.
    expect(within(card).getByRole('heading', { level: 3 })).toHaveTextContent(
      'Bénédicte <b>Mbuyi</b>',
    );
    expect(card.querySelector('b')).toBeNull();
    expect(card.querySelector(`[lang="${locale}"]`)).toHaveTextContent(
      locale === 'fr' ? 'Congé annuel' : 'Annual leave',
    );
    expect(card.querySelector(`[lang="${other}"]`)).toHaveTextContent(
      other === 'fr' ? 'Congé annuel' : 'Annual leave',
    );
    expect(within(card).getByTestId('approval-amount')).toHaveTextContent(
      locale === 'fr' ? '2,5 jours' : '2.5 days',
    );
    expect(screen.getByTestId('approvals-scope')).toHaveTextContent(k.manager.routing);
    await expectAccessible(container);
  });

  it('shows the empty inboxes', async () => {
    inbox('/me/leave-approvals', []);
    await showManager();
    expect(await screen.findByTestId('inbox-empty')).toHaveTextContent(k.manager.empty);
  });

  it('approves after an explicit confirmation, then removes the item and announces it', async () => {
    const requests = inbox('/me/leave-approvals', [item(FIRST), item(SECOND)], () => ({
      status: 200,
      body: receipt(FIRST),
    }));
    const user = userEvent.setup();
    const { container } = await showManager();
    const first = (await screen.findAllByTestId('approval-item'))[0] as HTMLElement;
    await user.click(
      within(first).getByRole('button', {
        name: k.actions.approveFor.replace('{{name}}', 'Bénédicte <b>Mbuyi</b>'),
      }),
    );
    const dialog = screen.getByTestId('decision-dialog');
    expect(dialog).toHaveAttribute('open');
    const reason = within(dialog).getByLabelText(k.form.reason);
    await waitFor(() => {
      expect(reason).toHaveFocus();
    });
    // The reason's language defaults to the interface language and is sent explicitly.
    expect(within(dialog).getByLabelText(k.form.reasonLocale)).toHaveValue(locale);
    await expectAccessible(container);

    // Nothing typed: the summary takes the focus and names the field.
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    const problems = within(dialog).getByTestId('decision-problems');
    await waitFor(() => {
      expect(problems).toHaveFocus();
    });
    expect(problems).toHaveTextContent(k.problems.reason);
    expect(reason).toHaveAttribute('aria-invalid', 'true');
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    await user.type(reason, '  <i>Équipe</i> au complet  ');
    await user.selectOptions(within(dialog).getByLabelText(k.form.reasonLocale), other);
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    const confirmation = within(dialog).getByTestId('decision-confirm');
    // The reason is shown exactly, as text, in its own language.
    const quoted = within(confirmation).getByTestId('decision-reason');
    expect(quoted).toHaveTextContent('<i>Équipe</i> au complet');
    expect(quoted).toHaveAttribute('lang', other);
    expect(quoted.querySelector('i')).toBeNull();
    const confirm = within(dialog).getByRole('button', { name: confirmLabel('APPROVED') });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await user.click(confirm);

    await waitFor(() => {
      expect(screen.getAllByTestId('approval-item')).toHaveLength(1);
    });
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(1);
    expect(posts[0]!.path).toBe(`/me/leave-approvals/${FIRST}/decision`);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      decision: 'APPROVED',
      reasonLocale: other,
      reason: '<i>Équipe</i> au complet',
    });
    expect(posts[0]!.key).toMatch(/^web-/u);
    expect(dialog).not.toHaveAttribute('open');
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      locale === 'fr' ? 'a été approuvée' : 'was approved',
    );
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('keeps the key for the same decision and makes a new one after any edit', async () => {
    let calls = 0;
    const requests = inbox('/me/leave-approvals', [item(FIRST)], () => {
      calls += 1;
      return calls < 3 ? { status: 0 } : { status: 200, body: receipt(FIRST, 'REJECTED') };
    });
    const user = userEvent.setup();
    await showManager();
    const first = await screen.findByTestId('approval-item');
    await user.click(within(first).getAllByRole('button')[1]!);
    const dialog = screen.getByTestId('decision-dialog');
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Période de clôture');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    const confirm = () => within(dialog).getByRole('button', { name: confirmLabel('REJECTED') });
    await user.click(confirm());
    expect(await within(dialog).findByTestId('decision-error')).toHaveTextContent(c.errors.network);
    await user.click(confirm());
    await within(dialog).findByTestId('decision-error');
    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'POST')).toHaveLength(2);
    });
    // Edit the reason: a new decision, a new key.
    await user.click(within(dialog).getByRole('button', { name: k.dialog.back }));
    await user.type(within(dialog).getByLabelText(k.form.reason), '.');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    await user.click(confirm());
    await waitFor(() => {
      expect(screen.queryByTestId('approval-item')).toBeNull();
    });
    const keys = requests.filter((r) => r.method === 'POST').map((r) => r.key);
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it('sends one decision at a time and disables every decision control meanwhile', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests = inbox('/me/leave-approvals', [item(FIRST), item(SECOND)], async () => {
      await held;
      return { status: 200, body: receipt(FIRST) };
    });
    const user = userEvent.setup();
    await showManager();
    const dialog = await reachConfirmation(user, 'APPROVED', 'Accordé');
    const confirm = within(dialog).getByRole('button', { name: confirmLabel('APPROVED') });
    await user.click(confirm);
    const sending = within(dialog).getByRole('button', { name: k.dialog.sending });
    expect(sending).toBeDisabled();
    expect(sending).toHaveAttribute('aria-busy', 'true');
    sending.click();
    for (const button of within(screen.getByTestId('approval-items')).getAllByRole('button')) {
      expect(button).toBeDisabled();
    }
    release();
    await waitFor(() => {
      expect(screen.getAllByTestId('approval-item')).toHaveLength(1);
    });
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    // Released: the remaining item can be decided.
    for (const button of within(screen.getByTestId('approval-items')).getAllByRole('button')) {
      expect(button).toBeEnabled();
    }
  });

  it.each<[string, Reply, string, boolean]>([
    [
      'already decided',
      { status: 409, body: problem('LEAVE_REQUEST_ALREADY_DECIDED', 409) },
      'errors.LEAVE_REQUEST_ALREADY_DECIDED',
      true,
    ],
    [
      'no longer available',
      { status: 404, body: problem('LEAVE_REQUEST_NOT_FOUND', 404) },
      'errors.LEAVE_REQUEST_NOT_FOUND',
      true,
    ],
    [
      'not eligible',
      {
        status: 409,
        body: problem('LEAVE_REQUEST_NOT_ELIGIBLE', 409, { reason: 'EMPLOYMENT_PERIOD' }),
      },
      'leaveApprovals.notEligible.EMPLOYMENT_PERIOD',
      false,
    ],
    [
      'idempotency conflict',
      { status: 409, body: problem('IDEMPOTENCY_KEY_REUSED', 409) },
      'errors.IDEMPOTENCY_KEY_REUSED',
      false,
    ],
    [
      'link required',
      { status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) },
      'errors.EMPLOYEE_LINK_REQUIRED',
      false,
    ],
    [
      'forbidden',
      { status: 403, body: problem('ACCESS_DENIED', 403) },
      'leaveApprovals.manager.unauthorized',
      false,
    ],
    [
      'rate limited',
      { status: 429, body: problem('RATE_LIMITED', 429), headers: { 'Retry-After': '9' } },
      'errors.RATE_LIMITED',
      false,
    ],
    ['general', { status: 500, body: problem('INTERNAL_ERROR', 500) }, 'errors.generic', false],
  ])('explains a refused decision: %s', async (_name, reply, messageKey, gone) => {
    inbox('/me/leave-approvals', [item(FIRST)], () => reply);
    const user = userEvent.setup();
    await showManager();
    const dialog = await reachConfirmation(user, 'APPROVED', 'Accordé');
    await user.click(within(dialog).getByRole('button', { name: confirmLabel('APPROVED') }));
    const alert = await within(dialog).findByTestId('decision-error');
    const text = messageKey
      .split('.')
      .reduce<unknown>((node, part) => (node as Record<string, unknown>)[part], c) as string;
    expect(alert).toHaveTextContent(text);
    await waitFor(() => {
      expect(alert).toHaveFocus();
    });
    if (reply.headers?.['Retry-After']) expect(alert).toHaveTextContent('9');
    expect(alert).toHaveTextContent('corr-approval-1234');
    await user.click(within(dialog).getAllByRole('button', { name: k.form.cancel })[0]!);
    if (gone) {
      await waitFor(() => {
        expect(screen.queryByTestId('approval-item')).toBeNull();
      });
    } else {
      expect(screen.getByTestId('approval-item')).toBeInTheDocument();
    }
  });

  it('maps server field problems back to the form', async () => {
    inbox('/me/leave-approvals', [item(FIRST)], () => ({
      status: 400,
      body: problem('VALIDATION_FAILED', 400, {
        fields: [{ field: 'reason', constraint: 'LENGTH' }],
      }),
    }));
    const user = userEvent.setup();
    await showManager();
    const dialog = await reachConfirmation(user, 'REJECTED', 'Non');
    await user.click(within(dialog).getByRole('button', { name: confirmLabel('REJECTED') }));
    await within(dialog).findByTestId('decision-error');
    expect(within(dialog).getByLabelText(k.form.reason)).toHaveAttribute('aria-invalid', 'true');
  });

  it('requests each inbox page once while it loads (R86-1)', async () => {
    let release: () => void = () => undefined;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    const requests = stubApi(async (r) => {
      if (r.path.includes('cursor=next')) {
        await held;
        return { status: 200, body: { items: [item(SECOND)], nextCursor: null } };
      }
      return { status: 200, body: { items: [item(FIRST)], nextCursor: 'next' } };
    });
    const user = userEvent.setup();
    await showManager();
    const more = await screen.findByRole('button', { name: k.inbox.more });
    await user.click(more);
    const loading = screen.getByRole('button', { name: k.inbox.loadingMore });
    expect(loading).toBeDisabled();
    loading.click();
    release();
    await waitFor(() => {
      expect(screen.getAllByTestId('approval-item')).toHaveLength(2);
    });
    expect(requests.filter((r) => r.path.includes('cursor=next'))).toHaveLength(1);
    expect(screen.queryByRole('button', { name: k.inbox.more })).toBeNull();
  });

  it('uses the administrators inbox and explains MFA and access refusals', async () => {
    const requests = inbox(
      '/leave-approvals',
      [item(FIRST, { approvalRoute: 'TENANT_ADMIN' })],
      () => ({
        status: 200,
        body: receipt(FIRST, 'REJECTED'),
      }),
    );
    const user = userEvent.setup();
    const { container } = await showAdmin();
    expect(await screen.findByTestId('approval-item')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(k.admin.title);
    const dialog = await reachConfirmation(user, 'REJECTED', 'Not this week');
    await expectAccessible(container);
    await user.click(within(dialog).getByRole('button', { name: confirmLabel('REJECTED') }));
    await waitFor(() => {
      expect(screen.getByTestId('inbox-empty')).toHaveTextContent(k.admin.empty);
    });
    expect(requests.find((r) => r.method === 'POST')!.path).toBe(
      `/leave-approvals/${FIRST}/decision`,
    );
  });

  it('shows a refused administrators inbox', async () => {
    stubApi(() => ({ status: 403, body: problem('ACCESS_DENIED', 403) }));
    await showAdmin();
    expect(await screen.findByTestId('inbox-error')).toHaveTextContent(k.admin.unauthorized);
  });

  it('shows the link-required manager inbox', async () => {
    stubApi(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    await showManager();
    expect(await screen.findByTestId('inbox-error')).toHaveTextContent(
      c.errors.EMPLOYEE_LINK_REQUIRED,
    );
  });
});

describe.each(['fr', 'en'] as const)('MVP-041B decided requests on My leave (%s)', (locale) => {
  const c = resources[locale].common;
  it('shows terminal states and the reason as written, in its own language', async () => {
    const base = {
      policyId: 'aaaaaaaa-0000-4000-8000-00000000000a',
      policyVersionId: 'aaaaaaaa-0000-4000-8000-0000000000a1',
      policyCode: 'ANNUAL',
      policyNames: { en: 'Annual leave', fr: 'Congé annuel' },
      unit: 'DAYS',
      amount: 2.5,
      startDate: '2026-10-20',
      endDate: '2026-10-22',
      submittedAt: '2026-10-09T10:00:00Z',
    };
    stubApi((r) => {
      if (r.path.startsWith('/me/leave-policies')) {
        return {
          status: 200,
          body: { items: [], nextCursor: null, asOf: '2026-10-09', timezone: 'Africa/Kinshasa' },
        };
      }
      if (r.path.startsWith('/me/leave-requests')) {
        return {
          status: 200,
          body: {
            items: [
              {
                ...base,
                id: FIRST,
                state: 'APPROVED',
                decision: {
                  id: 'd1',
                  outcome: 'APPROVED',
                  reasonLocale: 'fr',
                  reason: 'Couverture <b>assurée</b>',
                  decidedAt: '2026-10-09T11:00:00Z',
                },
              },
              {
                ...base,
                id: SECOND,
                state: 'REJECTED',
                decision: {
                  id: 'd2',
                  outcome: 'REJECTED',
                  reasonLocale: 'en',
                  reason: 'Peak season',
                  decidedAt: '2026-10-09T12:00:00Z',
                },
              },
              { ...base, id: 'p', state: 'PENDING', decision: null },
            ],
            nextCursor: null,
          },
        };
      }
      return undefined;
    });
    await renderWithSession(<MyLeavePage />, sessionWithRoles(['employee']), locale, '/me/leave');
    const rows = await screen.findAllByTestId('my-request');
    expect(within(rows[0]!).getByTestId('request-state')).toHaveTextContent(
      c.myLeave.state.APPROVED,
    );
    expect(within(rows[1]!).getByTestId('request-state')).toHaveTextContent(
      c.myLeave.state.REJECTED,
    );
    expect(within(rows[2]!).getByTestId('request-state')).toHaveTextContent(
      c.myLeave.state.PENDING,
    );
    const approved = within(rows[0]!).getByTestId('request-decision');
    // Not translated, not interpreted: the text and its own language.
    expect(approved.querySelector('[lang="fr"]')).toHaveTextContent('Couverture <b>assurée</b>');
    expect(approved.querySelector('b')).toBeNull();
    expect(
      within(rows[1]!).getByTestId('request-decision').querySelector('[lang="en"]'),
    ).toHaveTextContent('Peak season');
    expect(within(rows[2]!).getByTestId('request-decision')).toHaveTextContent(
      c.myLeave.history.awaiting,
    );
  });
});

import type { EmployeeSummary } from '@divalhr/api-client';
import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MyLeaveApprovalsPage } from './LeaveApprovalInbox';
import { LeaveApprovalSubstitutesPage } from './LeaveApprovalSubstitutesPage';
import {
  emptySubstituteForm,
  revokeProblemsOf,
  substituteBodyOf,
  substituteFailureOf,
  substituteProblemsOf,
  substituteToneOf,
  validDate,
  type SubstituteForm,
} from './leaveSubstitutes';

const MANAGER = {
  id: 'aaaaaaaa-0000-4000-8000-0000000000a1',
  employeeNumber: 'M-0001',
  givenNames: 'Josué',
  familyName: 'Kabeya',
};
const SUBSTITUTE = {
  id: 'bbbbbbbb-0000-4000-8000-0000000000b1',
  employeeNumber: 'S-0002',
  givenNames: 'Grâce',
  familyName: '<i>Lukusa</i>',
};
const ACTIVE = '11111111-0000-4000-8000-000000000001';
const REVOKED = '22222222-0000-4000-8000-000000000002';

const summary = (employee: typeof MANAGER): EmployeeSummary => ({
  ...employee,
  employmentStatus: 'CURRENT',
});

const appointment = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  manager: MANAGER,
  substitute: SUBSTITUTE,
  effectiveFrom: '2026-10-10',
  effectiveTo: '2026-10-30',
  state: 'ACTIVE',
  reasonLocale: 'fr',
  reason: '<b>Congé</b> du responsable',
  createdAt: '2026-10-09T10:00:00Z',
  revocation: null,
  ...overrides,
});

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-substitute-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string; key: string | null };

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const body = incoming.method === 'GET' ? '' : await incoming.clone().text();
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body,
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = await route(recorded);
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

/** The directory search and the list; every other call answered by {@code write}. */
function adminApi(
  items: () => unknown[],
  write: (request: Recorded) => Reply | Promise<Reply> = () => ({ status: 500 }),
) {
  return stubApi((request) => {
    if (request.method === 'GET' && request.path.startsWith('/leave-approval-substitutions')) {
      return { status: 200, body: { items: items(), nextCursor: null, asOf: '2026-10-10' } };
    }
    if (request.method === 'POST' && request.path === '/employees/search') {
      const query = (JSON.parse(request.body) as { query: string }).query;
      return {
        status: 200,
        body: {
          items: query.startsWith('Kab') ? [summary(MANAGER)] : [summary(SUBSTITUTE)],
          nextCursor: null,
        },
      };
    }
    return write(request);
  });
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

describe('MVP-041G substitute rules in the browser', () => {
  it('checks dates strictly and inclusively', () => {
    expect(validDate('2026-02-28')).toBe(true);
    expect(validDate('2026-02-30')).toBe(false);
    expect(validDate('1899-12-31')).toBe(false);
    expect(validDate('3000-01-01')).toBe(false);
    expect(validDate('2026-2-3')).toBe(false);
  });

  it('names every problem of the form, never a value', () => {
    const empty = emptySubstituteForm('fr');
    expect([...substituteProblemsOf(empty)]).toEqual([
      'manager',
      'substitute',
      'effectiveFrom',
      'effectiveTo',
      'reason',
    ]);
    const same: SubstituteForm = {
      ...empty,
      manager: summary(MANAGER),
      substitute: summary(MANAGER),
      effectiveFrom: '2026-10-12',
      effectiveTo: '2026-10-11',
      reason: 'Intérim.',
    };
    expect([...substituteProblemsOf(same)]).toEqual(['substitute', 'effectiveTo']);
    expect([...revokeProblemsOf({ reasonLocale: 'en', reason: 'x\u0007y' })]).toEqual(['reason']);
  });

  it('sends IDs, dates and the normalized reason only', () => {
    const form: SubstituteForm = {
      ...emptySubstituteForm('en'),
      manager: summary(MANAGER),
      substitute: summary(SUBSTITUTE),
      effectiveFrom: '2026-10-12',
      effectiveTo: '2026-10-12',
      reason: '  Café closed  ',
    };
    expect(substituteBodyOf(form)).toEqual({
      managerEmployeeId: MANAGER.id,
      substituteEmployeeId: SUBSTITUTE.id,
      effectiveFrom: '2026-10-12',
      effectiveTo: '2026-10-12',
      reasonLocale: 'en',
      reason: 'Café closed',
    });
  });

  it('maps every refusal to an allow-listed message', () => {
    const response = (status: number) => new Response(null, { status });
    for (const code of [
      'LEAVE_APPROVAL_SUBSTITUTION_SAME_EMPLOYEE',
      'LEAVE_APPROVAL_SUBSTITUTION_EMPLOYMENT_PERIOD',
      'LEAVE_APPROVAL_SUBSTITUTION_NO_REPORTING_LINE',
      'LEAVE_APPROVAL_SUBSTITUTION_OVERLAP',
      'EMPLOYEE_NOT_FOUND',
      'IDEMPOTENCY_KEY_REUSED',
    ]) {
      const failure = substituteFailureOf(response(409), problem(code, 409, { value: 'secret' }));
      expect(failure).toMatchObject({ kind: 'refused', messageKey: `errors.${code}` });
      expect(JSON.stringify(failure)).not.toContain('secret');
    }
    for (const code of [
      'LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND',
      'LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED',
    ]) {
      expect(substituteFailureOf(response(409), problem(code, 409)).kind).toBe('gone');
    }
    expect(substituteFailureOf(response(403), problem('MFA_REQUIRED', 403)).kind).toBe(
      'mfaRequired',
    );
    expect(substituteFailureOf(response(403), problem('ACCESS_DENIED', 403)).kind).toBe(
      'forbidden',
    );
    expect(
      substituteFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'effectiveTo', constraint: 'RANGE' },
            { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
            { field: 'substituteEmployeeId', constraint: 'FORMAT' },
          ],
        }),
      ).fields,
    ).toEqual(['substitute', 'effectiveTo']);
    expect(substituteToneOf('ACTIVE')).toBe('success');
    expect(substituteToneOf('REVOKED')).toBe('warning');
  });
});

describe.each(['fr', 'en'] as const)('MVP-041G leave approval substitutes (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.leaveSubstitutes;
  const show = () =>
    renderWithSession(
      <LeaveApprovalSubstitutesPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/leave-approval-substitutes',
    );

  async function pick(user: ReturnType<typeof userEvent.setup>, testId: string, query: string) {
    const picker = screen.getByTestId(testId);
    await user.type(within(picker).getByLabelText(k.picker.find), query);
    await user.click(within(picker).getByRole('button', { name: k.picker.search }));
    await user.click(await within(picker).findByRole('button', { name: /\(/u }));
  }

  async function fill(user: ReturnType<typeof userEvent.setup>) {
    await pick(user, 'manager-picker', 'Kab');
    await pick(user, 'substitute-picker', 'Luk');
    await user.type(screen.getByLabelText(k.form.effectiveFrom), '2026-10-12');
    await user.type(screen.getByLabelText(k.form.effectiveTo), '2026-10-20');
    await user.type(screen.getByLabelText(k.form.reason), 'Motif secret du remplacement.');
  }

  it('lists appointments with their derived states, as plain text', async () => {
    adminApi(() => [
      appointment(ACTIVE),
      appointment(REVOKED, {
        state: 'REVOKED',
        revocation: {
          revokedAt: '2026-10-09T12:00:00Z',
          reasonLocale: 'en',
          reason: 'Back early',
        },
      }),
    ]);
    const { container } = await show();
    expect(await screen.findByRole('heading', { level: 1, name: k.title })).toBeInTheDocument();
    const rows = await screen.findAllByTestId('substitute-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByTestId('substitute-state')).toHaveTextContent(k.states.ACTIVE);
    expect(within(rows[1]!).getByTestId('substitute-state')).toHaveTextContent(k.states.REVOKED);
    expect(within(rows[1]!).getByTestId('substitute-revocation')).toHaveTextContent('Back early');
    // A revoked appointment has no revoke action; the active one does.
    expect(within(rows[0]!).getByRole('button', { name: /…$/u })).toBeInTheDocument();
    expect(within(rows[1]!).queryByRole('button')).toBeNull();
    expect(screen.getByTestId('substitutes')).toHaveTextContent('<i>Lukusa</i>');
    expect(screen.getByTestId('substitutes').querySelector('i, b')).toBeNull();
    expect(screen.getByTestId('substitute-rules')).toHaveTextContent(k.rules.primary);
    await expectAccessible(container);
  });

  it('shows the empty list and refuses without MFA, with no retry', async () => {
    adminApi(() => []);
    await show();
    expect(await screen.findByTestId('substitutes-empty')).toHaveTextContent(k.list.empty);
    vi.unstubAllGlobals();
    stubApi(() => ({ status: 403, body: problem('MFA_REQUIRED', 403) }));
    await show();
    const alerts = await screen.findAllByTestId('list-error');
    expect(alerts.at(-1)).toHaveAttribute('data-kind', 'mfaRequired');
    expect(screen.queryByRole('button', { name: k.list.retry })).toBeNull();
  });

  it('names the problems of an incomplete appointment and focuses them', async () => {
    const requests = adminApi(() => []);
    const user = userEvent.setup();
    await show();
    await screen.findByTestId('substitutes-empty');
    await user.click(screen.getByRole('button', { name: k.form.review }));
    const box = await screen.findByTestId('create-problems');
    await waitFor(() => {
      expect(box).toHaveFocus();
    });
    expect(box).toHaveTextContent(k.problems.manager);
    expect(box).toHaveTextContent(k.problems.reason);
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });

  it('appoints after a confirmation, with one stable key and one request in flight', async () => {
    let calls = 0;
    let listed: unknown[] = [];
    let release: (() => void) | undefined;
    const requests = adminApi(
      () => listed,
      async (request) => {
        if (request.path !== '/leave-approval-substitutions') return { status: 500 };
        calls += 1;
        if (calls === 1) return { status: 503, body: problem('INTERNAL_ERROR', 503) };
        await new Promise<void>((resolve) => {
          release = resolve;
        });
        listed = [appointment(ACTIVE)];
        return {
          status: 201,
          body: { substitutionId: ACTIVE, createdAt: '2026-10-10T09:00:00Z' },
        };
      },
    );
    const user = userEvent.setup();
    const { container } = await show();
    await screen.findByTestId('substitutes-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.review }));
    const confirmation = await screen.findByTestId('create-confirm');
    expect(confirmation).toHaveTextContent('Kabeya');
    expect(confirmation).toHaveTextContent('<i>Lukusa</i>');
    expect(within(confirmation).getByTestId('create-reason')).toHaveAttribute('lang', locale);
    const confirm = within(confirmation).getByRole('button', { name: k.form.confirmButton });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await expectAccessible(container);
    await user.click(confirm);
    expect(await screen.findByTestId('create-error')).not.toHaveTextContent('secret');
    // The retry of the same appointment reuses the key; a double activation sends once.
    const retry = screen.getByRole('button', { name: k.form.confirmButton });
    await user.click(retry);
    await user.click(retry);
    await waitFor(() => {
      expect(release).toBeDefined();
    });
    release?.();
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent('Kabeya');
    });
    expect(await screen.findAllByTestId('substitute-row')).toHaveLength(1);
    const posts = requests.filter(
      (r) => r.method === 'POST' && r.path === '/leave-approval-substitutions',
    );
    expect(posts).toHaveLength(2);
    expect(posts[0]!.key).toMatch(/^web-/u);
    expect(posts[1]!.key).toBe(posts[0]!.key);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      managerEmployeeId: MANAGER.id,
      substituteEmployeeId: SUBSTITUTE.id,
      effectiveFrom: '2026-10-12',
      effectiveTo: '2026-10-20',
      reasonLocale: locale,
      reason: 'Motif secret du remplacement.',
    });
    // The search terms went in request bodies only; nothing reaches the URL or storage.
    for (const request of requests) expect(request.path).not.toMatch(/Kab|Luk|secret/u);
    expect(window.location.href).not.toMatch(/Kab|Luk|secret/u);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('uses a new key once the appointment is edited, and shows refusals without echo', async () => {
    const requests = adminApi(
      () => [],
      () => ({ status: 409, body: problem('LEAVE_APPROVAL_SUBSTITUTION_OVERLAP', 409) }),
    );
    const user = userEvent.setup();
    await show();
    await screen.findByTestId('substitutes-empty');
    await fill(user);
    await user.click(screen.getByRole('button', { name: k.form.review }));
    await user.click(await screen.findByRole('button', { name: k.form.confirmButton }));
    const alert = await screen.findByTestId('create-error');
    expect(alert).toHaveTextContent(c.errors.LEAVE_APPROVAL_SUBSTITUTION_OVERLAP);
    expect(alert).not.toHaveTextContent('secret');
    await user.click(screen.getByRole('button', { name: k.form.back }));
    const to = screen.getByLabelText(k.form.effectiveTo);
    await user.clear(to);
    await user.type(to, '2026-10-21');
    await user.click(screen.getByRole('button', { name: k.form.review }));
    await user.click(await screen.findByRole('button', { name: k.form.confirmButton }));
    await screen.findByTestId('create-error');
    const posts = requests.filter(
      (r) => r.method === 'POST' && r.path === '/leave-approval-substitutions',
    );
    expect(posts).toHaveLength(2);
    expect(posts[1]!.key).not.toBe(posts[0]!.key);
  });

  it('revokes in a dialog after a confirmation and refreshes the list', async () => {
    let listed = [appointment(ACTIVE)];
    const requests = adminApi(
      () => listed,
      () => {
        listed = [
          appointment(ACTIVE, {
            state: 'REVOKED',
            revocation: { revokedAt: '2026-10-10T10:00:00Z', reasonLocale: 'en', reason: 'Back' },
          }),
        ];
        return {
          status: 201,
          body: {
            substitutionId: ACTIVE,
            revocationId: 'cccccccc-0000-4000-8000-00000000000c',
            state: 'REVOKED',
            revokedAt: '2026-10-10T10:00:00Z',
          },
        };
      },
    );
    const user = userEvent.setup();
    const { container } = await show();
    const [row] = await screen.findAllByTestId('substitute-row');
    await user.click(within(row!).getByRole('button', { name: /…$/u }));
    const dialog = screen.getByTestId('revoke-dialog');
    expect(within(dialog).getByTestId('revoke-summary')).toHaveTextContent('Kabeya');
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Responsable revenu.');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    const confirm = within(dialog).getByRole('button', { name: k.revoke.confirmButton });
    await waitFor(() => {
      expect(confirm).toHaveFocus();
    });
    await expectAccessible(container);
    await user.click(confirm);
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent('Kabeya');
    });
    await waitFor(() => {
      expect(screen.getByTestId('substitute-state')).toHaveTextContent(k.states.REVOKED);
    });
    const posts = requests.filter((r) => r.method === 'POST' && r.path.endsWith('/revocation'));
    expect(posts).toHaveLength(1);
    expect(posts[0]!.path).toBe(`/leave-approval-substitutions/${ACTIVE}/revocation`);
    expect(JSON.parse(posts[0]!.body)).toEqual({
      reasonLocale: locale,
      reason: 'Responsable revenu.',
    });
  });

  it('settles an appointment already revoked elsewhere', async () => {
    adminApi(
      () => [appointment(ACTIVE)],
      () => ({ status: 409, body: problem('LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED', 409) }),
    );
    const user = userEvent.setup();
    await show();
    const [row] = await screen.findAllByTestId('substitute-row');
    await user.click(within(row!).getByRole('button', { name: /…$/u }));
    const dialog = screen.getByTestId('revoke-dialog');
    await user.type(within(dialog).getByLabelText(k.form.reason), 'Responsable revenu.');
    await user.click(within(dialog).getByRole('button', { name: k.form.review }));
    await user.click(within(dialog).getByRole('button', { name: k.revoke.confirmButton }));
    const alert = await within(dialog).findByTestId('revoke-error');
    expect(alert).toHaveTextContent(k.revoke.gone);
    expect(screen.getByTestId('announcer')).toHaveTextContent(k.revoke.gone);
    expect(within(dialog).getByRole('button', { name: k.revoke.confirmButton })).toBeDisabled();
    await user.click(within(dialog).getAllByRole('button', { name: k.revoke.cancel }).at(-1)!);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: k.list.title })).toHaveFocus();
    });
  });
});

describe.each(['fr', 'en'] as const)(
  'MVP-041G substitute work in the manager inbox (%s)',
  (locale) => {
    const c = resources[locale].common;
    const k = c.leaveApprovals;
    const item = (id: string, capacity: 'PRIMARY' | 'SUBSTITUTE') => ({
      id,
      submittedAt: '2026-10-09T10:00:00Z',
      employee: {
        id: `e-${id}`,
        employeeNumber: 'E-0042',
        givenNames: 'Bénédicte',
        familyName: 'Mbuyi',
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
      approvalCapacity: capacity,
    });

    it('marks delegated work and confirms the decision as a substitute', async () => {
      const requests = stubApi((request) =>
        request.method === 'GET'
          ? {
              status: 200,
              body: {
                items: [item(ACTIVE, 'SUBSTITUTE'), item(REVOKED, 'PRIMARY')],
                nextCursor: null,
              },
            }
          : {
              status: 200,
              body: {
                requestId: ACTIVE,
                decisionId: 'dddddddd-0000-4000-8000-00000000000d',
                state: 'APPROVED',
                decidedAt: '2026-10-10T11:00:00Z',
              },
            },
      );
      const user = userEvent.setup();
      const { container } = await renderWithSession(
        <MyLeaveApprovalsPage />,
        sessionWithRoles(['employee']),
        locale,
        '/me/leave/approvals',
      );
      const items = await screen.findAllByTestId('approval-item');
      expect(within(items[0]!).getByTestId('substitute-capacity')).toHaveTextContent(
        k.substitute.badge,
      );
      expect(within(items[1]!).queryByTestId('substitute-capacity')).toBeNull();
      await expectAccessible(container);
      await user.click(within(items[0]!).getByRole('button', { name: /^(Approuver|Approve)/u }));
      const dialog = screen.getByTestId('decision-dialog');
      expect(within(dialog).getByTestId('dialog-substitute-notice')).toHaveTextContent(
        k.substitute.notice,
      );
      await user.type(within(dialog).getByLabelText(k.form.reason), 'Validé en remplacement.');
      await user.click(within(dialog).getByRole('button', { name: k.form.review }));
      expect(within(dialog).getByTestId('decision-confirm')).toHaveTextContent(
        k.substitute.confirm.APPROVED.replace('{{name}}', 'Bénédicte Mbuyi'),
      );
      await user.click(
        within(dialog).getByRole('button', { name: k.substitute.confirmButton.APPROVED }),
      );
      await waitFor(() => {
        expect(screen.getAllByTestId('approval-item')).toHaveLength(1);
      });
      const posts = requests.filter((r) => r.method === 'POST');
      expect(posts).toHaveLength(1);
      expect(posts[0]!.path).toBe(`/me/leave-approvals/${ACTIVE}/decision`);
      // The capacity is the server's to decide: the body never carries it.
      expect(JSON.parse(posts[0]!.body)).toEqual({
        decision: 'APPROVED',
        reasonLocale: locale,
        reason: 'Validé en remplacement.',
      });
    });
  },
);

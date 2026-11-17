import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MySalaryAdvancePage } from './MySalaryAdvancePage';
import { SalaryAdvanceHandoffsPage } from './SalaryAdvanceHandoffsPage';
import {
  handoffLive,
  handoffReconcilable,
  handoffWithdrawable,
  withdrawable,
} from './salaryAdvance';

const REQUEST_ID = '44444444-0000-4000-8000-000000000001';
const HANDOFF_ID = '77777777-0000-4000-8000-000000000001';
const DIGEST = 'b'.repeat(64);
const EXPIRES = '2099-01-01T10:15:00Z';
const NOTICE = 'DIVALHR_NOT_LENDER';
const HANDOFF_NOTICE = 'DIVALHR_HANDOFF_SYNTHETIC';
const money = (amount: string) => ({ currency: 'CDF', amount });

const request = (overrides: Record<string, unknown> = {}) => ({
  requestId: REQUEST_ID,
  status: 'EMPLOYER_CONFIRMED',
  version: 2,
  currency: 'CDF',
  requested: money('5000'),
  submittedAt: '2026-10-29T08:00:00Z',
  employeeId: null,
  employeeNumber: null,
  notice: NOTICE,
  ...overrides,
});
const handoff = (overrides: Record<string, unknown> = {}) => ({
  handoffId: HANDOFF_ID,
  requestId: REQUEST_ID,
  state: 'QUEUED',
  reviewState: null,
  version: 1,
  currency: 'CDF',
  requested: money('5000'),
  createdAt: '2026-10-30T08:00:00Z',
  employeeId: null,
  employeeNumber: 'E-0001',
  notice: NOTICE,
  handoffNotice: HANDOFF_NOTICE,
  ...overrides,
});
const preview = (overrides: Record<string, unknown> = {}) => ({
  requestId: REQUEST_ID,
  requestVersion: 2,
  currency: 'CDF',
  requested: money('5000'),
  ceiling: money('240000'),
  partnerCode: 'SYNTHETIC-TEST',
  consent: {
    version: 'SA-HANDOFF-1',
    contentSha256: DIGEST,
    purpose: 'SUBMIT_CONFIRMED_REQUEST_FOR_PARTNER_REVIEW',
    recipient: 'SYNTHETIC-TEST',
    shared: ['REQUEST_ID', 'REQUESTED_AMOUNT'],
    notShared: ['HEALTH', 'PASSWORD'],
    promise: 'NO_APPROVAL_OR_PAYOUT_PROMISED',
    withdrawal: 'BEFORE_DURABLE_PARTNER_RECEIPT',
    retention: 'NO_PRODUCTION_RETENTION_SCHEDULE_CLAIMED',
    lender: 'DIVALHR_IS_NOT_THE_LENDER',
  },
  blockers: [],
  previewDigest: DIGEST,
  expiresAt: EXPIRES,
  notice: NOTICE,
  handoffNotice: HANDOFF_NOTICE,
  ...overrides,
});
const view = (overrides: Record<string, unknown> = {}) => ({
  ...handoff(),
  reviewReason: null,
  ceiling: money('240000'),
  partnerCode: 'SYNTHETIC-TEST',
  receivedAt: null,
  lastOutcomeCode: 'UNKNOWN_TIMEOUT',
  attemptCount: 1,
  consent: {
    version: 'SA-HANDOFF-1',
    language: 'en',
    consentedAt: '2026-10-30T08:00:00Z',
  },
  events: [
    {
      sequence: 1,
      fromState: null,
      toState: 'AUTHORIZED',
      actorRole: 'EMPLOYEE',
      occurredAt: '2026-10-30T08:00:00Z',
      outcomeCode: null,
    },
  ],
  reviewEvents: [],
  attempts: [{ attempt: 1, startedAt: '2026-10-30T08:01:00Z', outcomeCode: 'UNKNOWN_TIMEOUT' }],
  applicationId: '88888888-0000-4000-8000-000000000001',
  ...overrides,
});
const eligibility = { asOf: '2026-10-29', notice: NOTICE, items: [] };

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

const storedKeys = () =>
  [...Object.keys(localStorage), ...Object.keys(sessionStorage)].filter(
    (key) => key !== 'divalhr.locale',
  );

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('handoff helpers', () => {
  it('withdraws only a queued handoff and reconciles only an uncertain or failed one', () => {
    expect(handoffWithdrawable('QUEUED')).toBe(true);
    for (const state of [
      'AUTHORIZED',
      'SENDING',
      'RECEIVED',
      'DELIVERY_UNKNOWN',
      'FAILED',
    ] as const) {
      expect(handoffWithdrawable(state)).toBe(false);
    }
    expect(handoffReconcilable('DELIVERY_UNKNOWN')).toBe(true);
    expect(handoffReconcilable('FAILED')).toBe(true);
    expect(handoffReconcilable('RECEIVED')).toBe(false);
    expect(handoffLive('WITHDRAWN')).toBe(false);
    expect(handoffLive('RECEIVED')).toBe(true);
  });

  it('removes the request withdrawal once a handoff exists', () => {
    expect(withdrawable('EMPLOYER_CONFIRMED')).toBe(true);
    expect(withdrawable('EMPLOYER_CONFIRMED', true)).toBe(false);
  });
});

describe.each(['en', 'fr'] as const)('employee handoff (%s)', (locale) => {
  const k = resources[locale].common.salaryAdvance.handoff;
  const show = () =>
    renderWithSession(
      <MySalaryAdvancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/salary-advance',
    );

  const candidateStub = () =>
    stub((call) => {
      if (call.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility };
      }
      if (call.path.startsWith('/me/salary-advance-handoffs')) {
        return { status: 200, body: { items: [], nextCursor: null } };
      }
      if (call.method === 'GET') {
        return { status: 200, body: { items: [request()], nextCursor: null } };
      }
      if (call.path.endsWith('/handoff/preview')) return { status: 200, body: preview() };
      return { status: 201, body: view({ state: 'QUEUED' }) };
    });

  it('shows both permanent notices and keeps both affirmations unchecked until the employee acts', async () => {
    const requests = candidateStub();
    const user = userEvent.setup();
    const { container } = await show();
    expect(await screen.findByTestId('handoff-notice')).toHaveTextContent(k.notice);
    await user.click(await screen.findByTestId('handoff-preview'));
    expect(await screen.findByTestId('handoff-disclosure')).toHaveAttribute(
      'data-version',
      'SA-HANDOFF-1',
    );
    const affirm = screen.getByTestId('handoff-affirm');
    const understand = screen.getByTestId('handoff-understand');
    const authorize = screen.getByTestId('handoff-authorize');
    expect(affirm).not.toBeChecked();
    expect(understand).not.toBeChecked();
    expect(authorize).toBeDisabled();
    await user.click(affirm);
    expect(authorize).toBeDisabled();
    await user.click(understand);
    expect(authorize).toBeEnabled();
    expect(requests.some((call) => call.method === 'POST' && !call.path.endsWith('/preview'))).toBe(
      false,
    );
    await expectAccessible(container);
  });

  it('authorizes with the previewed version, digest, consent and an idempotency key', async () => {
    const requests = candidateStub();
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('handoff-preview'));
    await user.click(await screen.findByTestId('handoff-affirm'));
    await user.click(screen.getByTestId('handoff-understand'));
    await user.click(screen.getByTestId('handoff-authorize'));
    await waitFor(() => {
      expect(
        requests.some((call) => call.method === 'POST' && call.path.endsWith('/handoff')),
      ).toBe(true);
    });
    const post = requests.find((call) => call.method === 'POST' && call.path.endsWith('/handoff'));
    expect(post?.path).toBe(`/me/salary-advance-requests/${REQUEST_ID}/handoff`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      expectedRequestVersion: 2,
      previewDigest: DIGEST,
      expiresAt: EXPIRES,
      consent: { version: 'SA-HANDOFF-1', language: locale, affirmed: true },
      understandsSyntheticTest: true,
    });
    expect(post?.key).toMatch(/^web-/u);
    expect(storedKeys()).toEqual([]);
  });

  it('shows the delivery state apart from the partner review and withdraws only while queued', async () => {
    const requests = stub((call) => {
      if (call.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility };
      }
      if (call.path.startsWith('/me/salary-advance-handoffs')) {
        return { status: 200, body: { items: [handoff({ version: 1 })], nextCursor: null } };
      }
      if (call.method === 'GET') {
        return { status: 200, body: { items: [request()], nextCursor: null } };
      }
      return { status: 200, body: view({ state: 'WITHDRAWN', version: 2 }) };
    });
    const user = userEvent.setup();
    await show();
    const row = await screen.findByTestId('handoff-row');
    expect(within(row).getByTestId('handoff-state')).toHaveAttribute('data-state', 'QUEUED');
    // The 120A request withdrawal is gone while the handoff stands.
    expect(screen.queryByTestId('withdraw')).toBeNull();
    await user.click(within(row).getByTestId('handoff-withdraw'));
    expect(requests.some((call) => call.method === 'POST')).toBe(false);
    await user.click(screen.getByTestId('handoff-withdraw-confirm'));
    await waitFor(() => {
      expect(requests.some((call) => call.method === 'POST')).toBe(true);
    });
    const post = requests.find((call) => call.method === 'POST');
    expect(post?.path).toBe(`/me/salary-advance-requests/${REQUEST_ID}/handoff/withdraw`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 1, confirmed: true });
  });

  it.each(['RECEIVED', 'DELIVERY_UNKNOWN', 'FAILED', 'SENDING'] as const)(
    'offers no withdrawal once the handoff is %s',
    async (state) => {
      stub((call) => {
        if (call.path.startsWith('/me/salary-advance-eligibility')) {
          return { status: 200, body: eligibility };
        }
        if (call.path.startsWith('/me/salary-advance-handoffs')) {
          return {
            status: 200,
            body: {
              items: [
                handoff({ state, reviewState: state === 'RECEIVED' ? 'UNDER_REVIEW' : null }),
              ],
              nextCursor: null,
            },
          };
        }
        return { status: 200, body: { items: [request()], nextCursor: null } };
      });
      await show();
      const row = await screen.findByTestId('handoff-row');
      expect(within(row).queryByTestId('handoff-withdraw')).toBeNull();
      expect(screen.queryByTestId('withdraw')).toBeNull();
      expect(within(row).getByText(k.stateHelp[state])).toBeVisible();
    },
  );

  it('shows the stable message of a stale preview and never the server text', async () => {
    stub((call) => {
      if (call.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility };
      }
      if (call.path.startsWith('/me/salary-advance-handoffs')) {
        return { status: 200, body: { items: [], nextCursor: null } };
      }
      if (call.method === 'GET') {
        return { status: 200, body: { items: [request()], nextCursor: null } };
      }
      if (call.path.endsWith('/handoff/preview')) return { status: 200, body: preview() };
      return {
        status: 409,
        body: {
          type: 'about:blank',
          title: 'SERVER-TEXT-MUST-NOT-SHOW',
          status: 409,
          code: 'SALARY_ADVANCE_HANDOFF_PREVIEW_STALE',
          params: {},
          correlationId: 'corr-ho-9999',
        },
      };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('handoff-preview'));
    await user.click(await screen.findByTestId('handoff-affirm'));
    await user.click(screen.getByTestId('handoff-understand'));
    await user.click(screen.getByTestId('handoff-authorize'));
    const alert = await screen.findByTestId('handoff-error');
    expect(alert).toHaveTextContent(
      resources[locale].common.errors.SALARY_ADVANCE_HANDOFF_PREVIEW_STALE,
    );
    expect(alert).not.toHaveTextContent('SERVER-TEXT-MUST-NOT-SHOW');
  });
});

describe('administrator handoffs (en)', () => {
  const show = () =>
    renderWithSession(
      <SalaryAdvanceHandoffsPage />,
      sessionWithRoles(['tenant-admin']),
      'en',
      '/admin/salary-advance-handoffs',
    );
  const unknown = handoff({ state: 'DELIVERY_UNKNOWN', version: 4 });

  it('lists handoffs and reconciles an uncertain delivery with the expected version', async () => {
    const requests = stub((call) => {
      if (call.method === 'POST') {
        return { status: 200, body: view({ state: 'RECEIVED', version: 5 }) };
      }
      if (call.path.startsWith('/salary-advance-handoffs/')) {
        return { status: 200, body: view({ state: 'DELIVERY_UNKNOWN', version: 4 }) };
      }
      return { status: 200, body: { items: [unknown], nextCursor: null } };
    });
    const user = userEvent.setup();
    const { container } = await show();
    expect(await screen.findByTestId('handoff-notice')).toBeVisible();
    await user.click(await screen.findByTestId('handoff-open'));
    expect(await screen.findByTestId('handoff-application-id')).toHaveTextContent(
      '88888888-0000-4000-8000-000000000001',
    );
    await expectAccessible(container);
    await user.click(await screen.findByTestId('handoff-reconcile-open'));
    expect(requests.some((call) => call.method === 'POST')).toBe(false);
    await user.click(screen.getByTestId('handoff-reconcile'));
    await waitFor(() => {
      expect(requests.some((call) => call.method === 'POST')).toBe(true);
    });
    const post = requests.find((call) => call.method === 'POST');
    expect(post?.path).toBe(`/salary-advance-handoffs/${HANDOFF_ID}/reconcile`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 4, confirmed: true });
    expect(post?.key).toMatch(/^web-/u);
    expect(storedKeys()).toEqual([]);
  });

  it('offers no reconcile for a received handoff and shows the closed history only', async () => {
    stub((call) => {
      if (call.path.startsWith('/salary-advance-handoffs/')) {
        return {
          status: 200,
          body: view({ state: 'RECEIVED', reviewState: 'UNDER_REVIEW', version: 6 }),
        };
      }
      return {
        status: 200,
        body: {
          items: [handoff({ state: 'RECEIVED', reviewState: 'UNDER_REVIEW' })],
          nextCursor: null,
        },
      };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('handoff-open'));
    await screen.findByTestId('handoff-events');
    expect(screen.queryByTestId('handoff-reconcile-open')).toBeNull();
  });
});

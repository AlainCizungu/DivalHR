import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MySalaryAdvancePage } from './MySalaryAdvancePage';
import { SalaryAdvancePoliciesPage } from './SalaryAdvancePoliciesPage';
import { SalaryAdvanceRequestsPage } from './SalaryAdvanceRequestsPage';
import { salaryAdvanceFailureOf, wholeNumber } from './salaryAdvance';

const REQUEST_ID = '44444444-0000-4000-8000-000000000001';
const POLICY_ID = '55555555-0000-4000-8000-000000000001';
const ENTITY_ID = '66666666-0000-4000-8000-000000000001';
const DIGEST = 'a'.repeat(64);
const EXPIRES = '2099-01-01T10:15:00Z';
const NOTICE = 'DIVALHR_NOT_LENDER';
const money = (amount: string) => ({ currency: 'CDF', amount });

const consent = {
  version: 'SA-CONSENT-1',
  contentSha256: DIGEST,
  purpose: 'EVALUATION_BY_FUTURE_APPROVED_PARTNER',
  shared: ['REQUEST_ID', 'REQUESTED_AMOUNT'],
  notShared: ['HEALTH', 'PASSWORD'],
  externalSharing: 'NONE_IN_THIS_STEP',
  withdrawal: 'BEFORE_EXTERNAL_HANDOFF',
  retention: 'NO_DELETION_SCHEDULE_YET',
};
const entry = (overrides: Record<string, unknown> = {}) => ({
  currency: 'CDF',
  status: 'ELIGIBLE',
  reasons: [],
  policy: null,
  referencePeriod: '2026-09',
  ceiling: money('240000'),
  minRequest: money('1000'),
  openRequests: 0,
  waitingUntil: null,
  consent,
  ...overrides,
});
const eligibility = (items: unknown[]) => ({ asOf: '2026-10-29', notice: NOTICE, items });
const summary = (overrides: Record<string, unknown> = {}) => ({
  requestId: REQUEST_ID,
  status: 'SUBMITTED',
  version: 1,
  currency: 'CDF',
  requested: money('5000'),
  submittedAt: '2026-10-29T08:00:00Z',
  employeeId: null,
  employeeNumber: null,
  notice: NOTICE,
  ...overrides,
});
const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-sa-9999',
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
      // MVP-120B: the page also lists the employee's handoffs; these 120A tests have none.
      const reply =
        incoming.method === 'GET' && recorded.path.startsWith('/me/salary-advance-handoffs')
          ? { status: 200, body: { items: [], nextCursor: null } }
          : handler(recorded);
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

/** The app keeps only its language choice; nothing of this feature is ever stored. */
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

describe('salary-advance helpers', () => {
  it('accepts whole numbers inside the bounds only', () => {
    expect(wholeNumber('30', 1, 50)).toBe(30);
    expect(wholeNumber('0', 0, 60)).toBe(0);
    expect(wholeNumber('51', 1, 50)).toBeNull();
    expect(wholeNumber('3.5', 0, 60)).toBeNull();
    expect(wholeNumber('-1', 0, 60)).toBeNull();
    expect(wholeNumber('', 0, 60)).toBeNull();
  });

  it('maps only the contract’s codes to stable message keys', () => {
    const of = (code: string, status: number) =>
      salaryAdvanceFailureOf(new Response(null, { status }), problem(code, status));
    expect(of('SALARY_ADVANCE_VERSION_CONFLICT', 409).kind).toBe('stale');
    expect(of('SALARY_ADVANCE_POLICY_PREVIEW_STALE', 409).messageKey).toBe(
      'errors.SALARY_ADVANCE_POLICY_PREVIEW_STALE',
    );
    expect(of('SALARY_ADVANCE_REQUEST_NOT_FOUND', 404).kind).toBe('notFound');
    expect(of('SALARY_ADVANCE_NOT_ELIGIBLE', 422).kind).toBe('domain');
    expect(of('SOMETHING_ELSE', 500).messageKey).toBe('errors.generic');
    expect(of('MFA_REQUIRED', 403).messageKey).toBe('errors.MFA_REQUIRED');
  });
});

describe.each(['en', 'fr'] as const)('employee page /me/salary-advance (%s)', (locale) => {
  const k = resources[locale].common.salaryAdvance;
  const show = () =>
    renderWithSession(
      <MySalaryAdvancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/salary-advance',
    );

  it('always shows the permanent notice and the reasons when not eligible', async () => {
    const requests = stub((request) =>
      request.path.startsWith('/me/salary-advance-eligibility')
        ? {
            status: 200,
            body: eligibility([
              entry({
                status: 'NOT_ELIGIBLE',
                reasons: ['TENURE_TOO_SHORT', 'WAITING_PERIOD'],
                ceiling: null,
                consent: null,
              }),
            ]),
          }
        : { status: 200, body: { items: [], nextCursor: null } },
    );
    await show();
    expect(await screen.findByTestId('salary-advance-notice')).toHaveTextContent(k.notice);
    const reasons = await screen.findByTestId('eligibility-reasons');
    expect(
      within(reasons)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([k.reason.TENURE_TOO_SHORT, k.reason.WAITING_PERIOD]);
    expect(screen.queryByTestId('request-amount')).toBeNull();
    expect(await screen.findByTestId('requests-empty')).toBeVisible();
    expect(requests.every((request) => request.method === 'GET')).toBe(true);
    expect(storedKeys()).toEqual([]);
    await expectAccessible(document.body);
  });

  it('previews, requires the affirmation, then submits with an idempotency key and no amount in the URL', async () => {
    const requests = stub((request) => {
      if (request.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility([entry()]) };
      }
      if (request.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      if (request.path === '/me/salary-advance-requests/preview') {
        return {
          status: 200,
          body: {
            eligibility: entry(),
            blockers: [],
            previewDigest: DIGEST,
            expiresAt: EXPIRES,
            notice: NOTICE,
          },
        };
      }
      return { status: 201, body: summary() };
    });
    const user = userEvent.setup();
    await show();
    await user.type(await screen.findByTestId('request-amount'), '5000');
    await user.click(screen.getByTestId('request-preview'));
    const document_ = await screen.findByTestId('consent-document');
    expect(document_).toHaveAttribute('data-version', 'SA-CONSENT-1');
    expect(within(document_).getByText(k.consent.sharedItem.REQUESTED_AMOUNT)).toBeVisible();
    expect(within(document_).getByText(k.consent.notSharedItem.HEALTH)).toBeVisible();
    expect(screen.getByTestId('request-submit')).toBeDisabled();
    await user.click(screen.getByTestId('request-consent'));
    await user.click(screen.getByTestId('request-submit'));
    expect(await screen.findByTestId('request-submitted')).toBeVisible();
    const submit = requests.find(
      (request) => request.method === 'POST' && request.path === '/me/salary-advance-requests',
    );
    expect(submit?.key).toMatch(/^web-/u);
    expect(JSON.parse(submit?.body ?? '{}')).toEqual({
      currency: 'CDF',
      amount: money('5000'),
      previewDigest: DIGEST,
      expiresAt: EXPIRES,
      consent: { version: 'SA-CONSENT-1', language: locale, affirmed: true },
    });
    expect(requests.every((request) => !request.path.includes('5000'))).toBe(true);
    expect(storedKeys()).toEqual([]);
  });

  it('shows the blockers of a refused preview and offers no submission', async () => {
    stub((request) => {
      if (request.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility([entry()]) };
      }
      if (request.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      return {
        status: 200,
        body: {
          eligibility: entry(),
          blockers: ['AMOUNT_OUT_OF_RANGE'],
          previewDigest: null,
          expiresAt: null,
          notice: NOTICE,
        },
      };
    });
    const user = userEvent.setup();
    await show();
    await user.type(await screen.findByTestId('request-amount'), '999999');
    await user.click(screen.getByTestId('request-preview'));
    expect(await screen.findByTestId('request-blockers')).toHaveTextContent(
      k.reason.AMOUNT_OUT_OF_RANGE,
    );
    expect(screen.queryByTestId('request-submit')).toBeNull();
  });

  it('rejects an invalid amount in the browser without a request', async () => {
    const requests = stub((request) =>
      request.path.startsWith('/me/salary-advance-eligibility')
        ? { status: 200, body: eligibility([entry()]) }
        : { status: 200, body: { items: [], nextCursor: null } },
    );
    const user = userEvent.setup();
    await show();
    await user.type(await screen.findByTestId('request-amount'), '12.5');
    await user.click(screen.getByTestId('request-preview'));
    expect(await screen.findByRole('alert')).toHaveTextContent(k.me.amountInvalid);
    expect(requests.some((request) => request.method === 'POST')).toBe(false);
  });

  it('withdraws in two steps with the expected version', async () => {
    const requests = stub((request) => {
      if (request.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility([]) };
      }
      if (request.method === 'GET') {
        return { status: 200, body: { items: [summary({ version: 3 })], nextCursor: null } };
      }
      return { status: 200, body: summary({ status: 'WITHDRAWN', version: 4 }) };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('withdraw'));
    expect(requests.some((request) => request.method === 'POST')).toBe(false);
    await user.click(screen.getByTestId('withdraw-confirm'));
    await waitFor(() => {
      expect(requests.some((request) => request.method === 'POST')).toBe(true);
    });
    const post = requests.find((request) => request.method === 'POST');
    expect(post?.path).toBe(`/me/salary-advance-requests/${REQUEST_ID}/withdraw`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 3, confirmed: true });
    expect(post?.key).toMatch(/^web-/u);
  });

  it('shows the stable message of a refused submission and never the server text', async () => {
    stub((request) => {
      if (request.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: eligibility([entry()]) };
      }
      if (request.method === 'GET') return { status: 200, body: { items: [], nextCursor: null } };
      if (request.path.endsWith('/preview')) {
        return {
          status: 200,
          body: {
            eligibility: entry(),
            blockers: [],
            previewDigest: DIGEST,
            expiresAt: EXPIRES,
            notice: NOTICE,
          },
        };
      }
      return { status: 409, body: problem('SALARY_ADVANCE_POLICY_PREVIEW_STALE', 409) };
    });
    const user = userEvent.setup();
    await show();
    await user.type(await screen.findByTestId('request-amount'), '5000');
    await user.click(screen.getByTestId('request-preview'));
    await user.click(await screen.findByTestId('request-consent'));
    await user.click(screen.getByTestId('request-submit'));
    const alert = await screen.findByTestId('salary-advance-error');
    expect(alert).toHaveAttribute('data-code', 'SALARY_ADVANCE_POLICY_PREVIEW_STALE');
    expect(alert).toHaveTextContent(
      resources[locale].common.errors.SALARY_ADVANCE_POLICY_PREVIEW_STALE,
    );
    expect(alert).toHaveTextContent('corr-sa-9999');
  });
});

describe('administrator policy page', () => {
  const policyEntry = (overrides: Record<string, unknown> = {}) => ({
    policyId: POLICY_ID,
    sequence: 1,
    currency: 'CDF',
    code: 'SAP-TEST',
    effectiveFrom: '2099-01-01',
    effectiveTo: null,
    enabled: true,
    minTenureMonths: 3,
    maxPercent: 30,
    absoluteMax: null,
    minRequest: money('1000'),
    maxOpenRequests: 1,
    waitingDays: 30,
    consentVersion: 'SA-CONSENT-1',
    partnerCode: 'SYNTHETIC-TEST',
    status: 'SCHEDULED',
    createdAt: '2026-10-29T08:00:00Z',
    cancelledAt: null,
    ...overrides,
  });
  const view = (items: unknown[]) => ({
    legalEntityId: ENTITY_ID,
    today: '2026-10-29',
    notice: NOTICE,
    versions: [{ currency: 'CDF', version: items.length }],
    items,
  });

  it('previews, then commits only the previewed policy with the notice visible', async () => {
    const requests = stub((request) => {
      if (request.path.startsWith('/legal-entities?')) {
        return {
          status: 200,
          body: { data: [{ id: ENTITY_ID, code: 'LE-1', name: 'Entité' }], nextCursor: null },
        };
      }
      if (request.method === 'GET') return { status: 200, body: view([]) };
      if (request.path.endsWith('/preview')) {
        const command = JSON.parse(request.body) as Record<string, unknown>;
        return {
          status: 200,
          body: {
            policy: command,
            before: [],
            after: [policyEntry()],
            blockers: [],
            version: 0,
            previewDigest: DIGEST,
            expiresAt: EXPIRES,
            notice: NOTICE,
          },
        };
      }
      return {
        status: 201,
        body: {
          policyId: POLICY_ID,
          sequence: 1,
          currency: 'CDF',
          effectiveFrom: '2099-01-01',
          version: 1,
          notice: NOTICE,
        },
      };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <SalaryAdvancePoliciesPage />,
      sessionWithRoles(['tenant-admin']),
      'en',
      '/admin/salary-advance-policies',
    );
    expect(await screen.findByTestId('salary-advance-notice')).toHaveTextContent(
      resources.en.common.salaryAdvance.notice,
    );
    await screen.findByTestId('policy-empty');
    await user.type(screen.getByTestId('policy-code'), 'SAP-TEST');
    await user.type(screen.getByTestId('policy-effectiveFrom'), '2099-01-01');
    await user.type(screen.getByTestId('policy-minRequest'), '1000');
    await user.click(screen.getByTestId('policy-preview'));
    await user.click(await screen.findByTestId('policy-commit'));
    expect(await screen.findByTestId('policy-committed')).toBeVisible();
    const commit = requests.find(
      (request) => request.method === 'POST' && !request.path.endsWith('/preview'),
    );
    const body = JSON.parse(commit?.body ?? '{}') as Record<string, unknown>;
    expect(commit?.key).toMatch(/^web-/u);
    expect(body).toMatchObject({
      expectedVersion: 0,
      previewDigest: DIGEST,
      expiresAt: EXPIRES,
      confirmed: true,
    });
    expect(body.policy).toMatchObject({ partnerCode: 'SYNTHETIC-TEST', code: 'SAP-TEST' });
  });

  it('flags invalid fields in the browser without a request', async () => {
    const requests = stub((request) =>
      request.path.startsWith('/legal-entities?')
        ? {
            status: 200,
            body: { data: [{ id: ENTITY_ID, code: 'LE-1', name: 'Entité' }], nextCursor: null },
          }
        : { status: 200, body: view([]) },
    );
    const user = userEvent.setup();
    await renderWithSession(
      <SalaryAdvancePoliciesPage />,
      sessionWithRoles(['tenant-admin']),
      'fr',
      '/admin/salary-advance-policies',
    );
    await screen.findByTestId('policy-empty');
    await user.type(screen.getByTestId('policy-code'), 'x');
    await user.click(screen.getByTestId('policy-preview'));
    expect((await screen.findAllByRole('alert')).length).toBeGreaterThan(0);
    expect(requests.some((request) => request.method === 'POST')).toBe(false);
  });

  it('cancels a scheduled version in two steps with the sequence as the expected version', async () => {
    const requests = stub((request) => {
      if (request.path.startsWith('/legal-entities?')) {
        return {
          status: 200,
          body: { data: [{ id: ENTITY_ID, code: 'LE-1', name: 'Entité' }], nextCursor: null },
        };
      }
      if (request.method === 'GET') return { status: 200, body: view([policyEntry()]) };
      return { status: 200, body: policyEntry({ status: 'CANCELLED' }) };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <SalaryAdvancePoliciesPage />,
      sessionWithRoles(['tenant-admin']),
      'en',
      '/admin/salary-advance-policies',
    );
    await user.click(await screen.findByTestId('policy-cancel'));
    expect(requests.some((request) => request.method === 'POST')).toBe(false);
    await user.click(screen.getByTestId('policy-cancel-confirm'));
    await waitFor(() => {
      expect(requests.some((request) => request.method === 'POST')).toBe(true);
    });
    const post = requests.find((request) => request.method === 'POST');
    expect(post?.path).toBe(
      `/legal-entities/${ENTITY_ID}/salary-advance-policies/${POLICY_ID}/cancel`,
    );
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 1, confirmed: true });
  });
});

describe('administrator request page', () => {
  const view = (overrides: Record<string, unknown> = {}) => ({
    requestId: REQUEST_ID,
    status: 'SUBMITTED',
    version: 2,
    currency: 'CDF',
    requested: money('5000'),
    ceiling: money('240000'),
    referencePeriod: '2026-09',
    policySequence: 1,
    partnerCode: 'SYNTHETIC-TEST',
    tenureMonths: 7,
    submittedAt: '2026-10-29T08:00:00Z',
    consent: {
      version: 'SA-CONSENT-1',
      language: 'fr',
      consentedAt: '2026-10-29T08:00:00Z',
      disclosureSha256: DIGEST,
    },
    lifecycle: [
      {
        sequence: 1,
        fromStatus: null,
        toStatus: 'SUBMITTED',
        actorRole: 'EMPLOYEE',
        occurredAt: '2026-10-29T08:00:00Z',
        reasonCode: null,
      },
    ],
    refusalReason: null,
    internalNote: null,
    employeeId: null,
    employeeNumber: 'E-100',
    notice: NOTICE,
    ...overrides,
  });
  const handler = (request: Recorded): Reply => {
    if (request.method === 'GET' && request.path.startsWith('/salary-advance-requests?')) {
      return {
        status: 200,
        body: { items: [summary({ employeeNumber: 'E-100' })], nextCursor: null },
      };
    }
    if (request.method === 'GET') return { status: 200, body: view() };
    return { status: 200, body: view({ status: 'EMPLOYER_CONFIRMED', version: 3 }) };
  };
  const show = () =>
    renderWithSession(
      <SalaryAdvanceRequestsPage />,
      sessionWithRoles(['tenant-admin']),
      'en',
      '/admin/salary-advance-requests',
    );

  it('confirms participation with the expected version and the not-credit-approval statement', async () => {
    const requests = stub(handler);
    const user = userEvent.setup();
    await show();
    expect(await screen.findByTestId('salary-advance-notice')).toBeVisible();
    await user.click(await screen.findByTestId('request-open'));
    await user.click(await screen.findByTestId('request-confirm-open'));
    expect(screen.getByTestId('request-confirm-panel')).toHaveTextContent(
      resources.en.common.salaryAdvance.requests.confirmStatement,
    );
    await user.click(screen.getByTestId('request-confirm'));
    await waitFor(() => {
      expect(requests.some((request) => request.method === 'POST')).toBe(true);
    });
    const post = requests.find((request) => request.method === 'POST');
    expect(post?.path).toBe(`/salary-advance-requests/${REQUEST_ID}/confirm-employer`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({ expectedVersion: 2, confirmed: true });
    expect(post?.key).toMatch(/^web-/u);
  });

  it('requires a closed reason to refuse and sends the optional note', async () => {
    const requests = stub(handler);
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('request-open'));
    await user.click(await screen.findByTestId('request-refuse-open'));
    await user.click(screen.getByTestId('request-refuse'));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      resources.en.common.salaryAdvance.requests.reasonRequired,
    );
    expect(requests.some((request) => request.method === 'POST')).toBe(false);
    await user.selectOptions(screen.getByTestId('request-refuse-reason'), 'EMPLOYMENT_ENDING');
    await user.type(screen.getByTestId('request-refuse-note'), 'Note synthétique');
    await user.click(screen.getByTestId('request-refuse'));
    await waitFor(() => {
      expect(requests.some((request) => request.method === 'POST')).toBe(true);
    });
    const post = requests.find((request) => request.method === 'POST');
    expect(post?.path).toBe(`/salary-advance-requests/${REQUEST_ID}/refuse-employer`);
    expect(JSON.parse(post?.body ?? '{}')).toEqual({
      expectedVersion: 2,
      reasonCode: 'EMPLOYMENT_ENDING',
      note: 'Note synthétique',
      confirmed: true,
    });
    expect(requests.every((request) => !request.path.includes('Note'))).toBe(true);
  });

  it('offers no decision once the request is no longer submitted', async () => {
    stub((request) =>
      request.method === 'GET' && !request.path.startsWith('/salary-advance-requests?')
        ? { status: 200, body: view({ status: 'WITHDRAWN' }) }
        : handler(request),
    );
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('request-open'));
    await screen.findByTestId('request-lifecycle');
    expect(screen.queryByTestId('request-confirm-open')).toBeNull();
    expect(screen.queryByTestId('request-refuse-open')).toBeNull();
    await expectAccessible(document.body);
  });
});

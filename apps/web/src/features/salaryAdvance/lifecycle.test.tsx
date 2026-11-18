import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { MySalaryAdvancePage } from './MySalaryAdvancePage';
import { SalaryAdvanceHandoffsPage } from './SalaryAdvanceHandoffsPage';
import { REVIEW_STATES } from './salaryAdvance';

const REQUEST_ID = '44444444-0000-4000-8000-000000000001';
const HANDOFF_ID = '77777777-0000-4000-8000-000000000001';
const PREVIEW_ID = '99999999-0000-4000-8000-000000000001';
const DIGEST = 'c'.repeat(64);
const NOTICE = 'DIVALHR_NOT_LENDER';
const money = (amount: string) => ({ currency: 'CDF', amount });

const handoff = (overrides: Record<string, unknown> = {}) => ({
  handoffId: HANDOFF_ID,
  requestId: REQUEST_ID,
  state: 'RECEIVED',
  reviewState: 'UNDER_REVIEW',
  version: 3,
  currency: 'CDF',
  requested: money('5000'),
  createdAt: '2026-10-30T08:00:00Z',
  employeeId: null,
  employeeNumber: 'E-0001',
  notice: NOTICE,
  handoffNotice: 'DIVALHR_HANDOFF_SYNTHETIC',
  ...overrides,
});
const reviewEvent = (sequence: number, toState: string) => ({
  sequence,
  fromState: sequence === 1 ? 'RECEIVED' : 'UNDER_REVIEW',
  toState,
  reasonCode: null,
  occurredAt: `2026-10-30T09:0${String(sequence)}:00Z`,
});
const view = (overrides: Record<string, unknown> = {}) => ({
  ...handoff(),
  reviewReason: null,
  ceiling: money('240000'),
  partnerCode: 'SYNTHETIC-TEST',
  receivedAt: '2026-10-30T08:02:00Z',
  lastOutcomeCode: 'RECEIVED',
  attemptCount: 1,
  consent: { version: 'SA-HANDOFF-1', language: 'en', consentedAt: '2026-10-30T08:00:00Z' },
  events: [],
  reviewEvents: [reviewEvent(1, 'UNDER_REVIEW'), reviewEvent(2, 'CANCELLED_BY_PARTNER')],
  attempts: [],
  applicationId: '88888888-0000-4000-8000-000000000001',
  ...overrides,
});

type Recorded = { method: string; path: string; body: string };
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
  vi.restoreAllMocks();
});

describe('lifecycle catalogue and wording', () => {
  it('has exactly the five review statuses and no approval, funding or payment status', () => {
    expect([...REVIEW_STATES]).toEqual([
      'RECEIVED',
      'UNDER_REVIEW',
      'MORE_INFORMATION_REQUIRED',
      'DECLINED',
      'CANCELLED_BY_PARTNER',
    ]);
    for (const status of REVIEW_STATES) {
      expect(status).not.toMatch(/APPROV|FUND|PAID|DISBURS/u);
    }
  });

  it.each(['en', 'fr'] as const)('labels every status and keeps parity in %s', (locale) => {
    const common = resources[locale].common.salaryAdvance.handoff;
    for (const status of REVIEW_STATES) {
      expect(common.review[status].length).toBeGreaterThan(0);
      expect(common.lifecycle.help[status].length).toBeGreaterThan(0);
    }
    expect(Object.keys(common.lifecycle.help).sort()).toEqual([...REVIEW_STATES].sort());
    expect(common.review.CANCELLED_BY_PARTNER).toBe(
      locale === 'en' ? 'Review cancelled by the partner' : 'Examen annulé par le partenaire',
    );
    expect(common.review.MORE_INFORMATION_REQUIRED).toBe(
      locale === 'en' ? 'More information required' : 'Informations supplémentaires requises',
    );
  });

  it('keeps both locales structurally identical for the retention and lifecycle copy', () => {
    const keys = (node: unknown, prefix = ''): string[] =>
      node !== null && typeof node === 'object'
        ? Object.entries(node).flatMap(([key, child]) => keys(child, `${prefix}${key}.`))
        : [prefix];
    const en = resources.en.common.salaryAdvance.handoff;
    const fr = resources.fr.common.salaryAdvance.handoff;
    expect(keys(fr.lifecycle).sort()).toEqual(keys(en.lifecycle).sort());
    expect(keys(fr.retention).sort()).toEqual(keys(en.retention).sort());
    for (const code of [
      'SALARY_ADVANCE_RETENTION_POLICY_MISSING',
      'SALARY_ADVANCE_RETENTION_PERIOD_INVALID',
      'SALARY_ADVANCE_PURGE_PREVIEW_NOT_FOUND',
      'SALARY_ADVANCE_PURGE_PREVIEW_STALE',
      'SALARY_ADVANCE_PURGE_ALREADY_COMPLETED',
    ] as const) {
      expect(resources.en.common.errors[code].length).toBeGreaterThan(0);
      expect(resources.fr.common.errors[code].length).toBeGreaterThan(0);
    }
  });

  it('never claims an approval or a payout in any lifecycle or retention string', () => {
    for (const locale of ['en', 'fr'] as const) {
      const text = JSON.stringify(resources[locale].common.salaryAdvance.handoff.lifecycle);
      expect(text).not.toMatch(/\bapproved\b|\bfunded\b|\bdisbursed\b|\bapprouvé|\bversé\b/iu);
    }
  });
});

describe.each(['en', 'fr'] as const)('employee review timeline (%s)', (locale) => {
  const k = resources[locale].common.salaryAdvance.handoff;
  it('shows the status, the no-approval statement and an ordered history on request', async () => {
    stub((call) => {
      if (call.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: { asOf: '2026-10-29', notice: NOTICE, items: [] } };
      }
      if (call.path.startsWith('/me/salary-advance-handoffs')) {
        return {
          status: 200,
          body: {
            items: [handoff({ reviewState: 'CANCELLED_BY_PARTNER' })],
            nextCursor: null,
          },
        };
      }
      if (call.path.endsWith('/handoff')) return { status: 200, body: view() };
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <MySalaryAdvancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/salary-advance',
    );
    const timeline = await screen.findByTestId('review-timeline');
    expect(within(timeline).getByTestId('review-help')).toHaveTextContent(
      k.lifecycle.help.CANCELLED_BY_PARTNER,
    );
    expect(within(timeline).getByTestId('review-no-approval')).toHaveTextContent(
      k.lifecycle.noApproval,
    );
    await user.click(within(timeline).getByTestId('review-timeline-toggle'));
    const events = await screen.findByTestId('review-events');
    const items = within(events).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveAttribute('data-status', 'UNDER_REVIEW');
    expect(items[1]).toHaveAttribute('data-status', 'CANCELLED_BY_PARTNER');
    await expectAccessible(container);
  });

  it('shows the stable message and no server text when the history cannot be read', async () => {
    stub((call) => {
      if (call.path.startsWith('/me/salary-advance-eligibility')) {
        return { status: 200, body: { asOf: '2026-10-29', notice: NOTICE, items: [] } };
      }
      if (call.path.startsWith('/me/salary-advance-handoffs')) {
        return { status: 200, body: { items: [handoff()], nextCursor: null } };
      }
      if (call.path.endsWith('/handoff')) {
        return {
          status: 500,
          body: { type: 'about:blank', title: 'SERVER-TEXT-MUST-NOT-SHOW', status: 500 },
        };
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <MySalaryAdvancePage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/salary-advance',
    );
    await user.click(await screen.findByTestId('review-timeline-toggle'));
    const alert = await screen.findByTestId('review-timeline-error');
    expect(alert).not.toHaveTextContent('SERVER-TEXT-MUST-NOT-SHOW');
  });
});

describe('administrator lifecycle and retention (en)', () => {
  const show = () =>
    renderWithSession(
      <SalaryAdvanceHandoffsPage />,
      sessionWithRoles(['tenant-admin']),
      'en',
      '/admin/salary-advance-handoffs',
    );
  const policy = (overrides: Record<string, unknown> = {}) => ({
    configured: false,
    retentionDays: null,
    version: null,
    effectiveFrom: null,
    ...overrides,
  });
  const purgePreview = (overrides: Record<string, unknown> = {}) => ({
    previewId: PREVIEW_ID,
    handoffCount: 1,
    attemptCount: 2,
    digest: DIGEST,
    expiresAt: '2099-01-01T10:15:00Z',
    ...overrides,
  });

  it('filters by the closed review catalogue', async () => {
    const requests = stub(() => ({
      status: 200,
      body: { items: [handoff({ reviewState: 'DECLINED' })], nextCursor: null },
    }));
    const user = userEvent.setup();
    await show();
    const filter = await screen.findByTestId('handoffs-review-filter');
    await user.selectOptions(filter, 'DECLINED');
    await waitFor(() => {
      expect(requests.some((call) => call.path.includes('reviewState=DECLINED'))).toBe(true);
    });
  });

  it('saves a bounded period, rejects one outside the bounds without calling the server', async () => {
    let current = policy();
    const requests = stub((call) => {
      if (call.path === '/salary-advance-retention-policy' && call.method === 'PUT') {
        const { retentionDays } = JSON.parse(call.body) as { retentionDays: number };
        current = policy({ configured: true, retentionDays, version: 1 });
        return { status: 200, body: current };
      }
      if (call.path === '/salary-advance-retention-policy') return { status: 200, body: current };
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    const { container } = await show();
    expect(await screen.findByTestId('retention-current')).toHaveTextContent(
      resources.en.common.salaryAdvance.handoff.retention.disabled,
    );
    expect(screen.getByTestId('purge-preview')).toBeDisabled();
    await user.type(screen.getByTestId('retention-days'), '29');
    await user.click(screen.getByTestId('retention-save'));
    expect(await screen.findByTestId('retention-error')).toHaveTextContent(
      resources.en.common.errors.SALARY_ADVANCE_RETENTION_PERIOD_INVALID,
    );
    expect(requests.some((call) => call.method === 'PUT')).toBe(false);
    await user.clear(screen.getByTestId('retention-days'));
    await user.type(screen.getByTestId('retention-days'), '30');
    await user.click(screen.getByTestId('retention-save'));
    expect(await screen.findByTestId('retention-saved')).toBeVisible();
    const put = requests.find((call) => call.method === 'PUT');
    expect(JSON.parse(put?.body ?? '{}')).toEqual({ retentionDays: 30 });
    await waitFor(() => {
      expect(screen.getByTestId('purge-preview')).toBeEnabled();
    });
    await expectAccessible(container);
  });

  it('previews counts, requires one explicit confirmation and confirms that exact preview', async () => {
    const requests = stub((call) => {
      if (call.path === '/salary-advance-retention-policy') {
        return {
          status: 200,
          body: policy({ configured: true, retentionDays: 30, version: 1 }),
        };
      }
      if (call.path === '/salary-advance-evidence-purge/preview') {
        return { status: 200, body: purgePreview() };
      }
      if (call.path === `/salary-advance-evidence-purge/${PREVIEW_ID}/confirm`) {
        return {
          status: 200,
          body: {
            handoffCount: 1,
            attemptCount: 2,
            replayed: false,
            completedAt: '2026-12-01T00:00:00Z',
          },
        };
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('purge-preview'));
    expect(await screen.findByTestId('purge-preview-result')).toBeVisible();
    // Nothing is deleted by previewing or by opening the confirmation.
    await user.click(screen.getByTestId('purge-open'));
    expect(requests.some((call) => call.path.endsWith('/confirm'))).toBe(false);
    await user.click(await screen.findByTestId('purge-confirm'));
    expect(await screen.findByTestId('purge-done')).toBeVisible();
    const confirm = requests.find((call) => call.path.endsWith('/confirm'));
    expect(JSON.parse(confirm?.body ?? '{}')).toEqual({ previewDigest: DIGEST, confirmed: true });
    expect(screen.queryByTestId('purge-confirm')).toBeNull();
  });

  it('says plainly that nothing is eligible and offers no deletion', async () => {
    stub((call) => {
      if (call.path === '/salary-advance-retention-policy') {
        return { status: 200, body: policy({ configured: true, retentionDays: 30, version: 1 }) };
      }
      if (call.path === '/salary-advance-evidence-purge/preview') {
        return { status: 200, body: purgePreview({ attemptCount: 0, handoffCount: 0 }) };
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('purge-preview'));
    expect(await screen.findByTestId('purge-none')).toBeVisible();
    expect(screen.queryByTestId('purge-open')).toBeNull();
  });

  it('shows the stable stale message and drops the preview when the purge preview is stale', async () => {
    stub((call) => {
      if (call.path === '/salary-advance-retention-policy') {
        return { status: 200, body: policy({ configured: true, retentionDays: 30, version: 1 }) };
      }
      if (call.path === '/salary-advance-evidence-purge/preview') {
        return { status: 200, body: purgePreview() };
      }
      if (call.path.endsWith('/confirm')) {
        return {
          status: 409,
          body: {
            type: 'about:blank',
            title: 'SERVER-TEXT-MUST-NOT-SHOW',
            status: 409,
            code: 'SALARY_ADVANCE_PURGE_PREVIEW_STALE',
          },
        };
      }
      return { status: 200, body: { items: [], nextCursor: null } };
    });
    const user = userEvent.setup();
    await show();
    await user.click(await screen.findByTestId('purge-preview'));
    await user.click(await screen.findByTestId('purge-open'));
    await user.click(await screen.findByTestId('purge-confirm'));
    const alert = await screen.findByTestId('retention-error');
    expect(alert).toHaveTextContent(resources.en.common.errors.SALARY_ADVANCE_PURGE_PREVIEW_STALE);
    expect(alert).not.toHaveTextContent('SERVER-TEXT-MUST-NOT-SHOW');
    expect(screen.queryByTestId('purge-confirm')).toBeNull();
    expect(screen.queryByTestId('purge-done')).toBeNull();
  });
});

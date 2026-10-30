import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes, useLocation } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import {
  ENTITY_ID,
  RUN_ID,
  RUN_ID_2,
  blockedLine,
  noPolicyView,
  policyPreview,
  policyView,
  problem,
  receipt,
  runDetail,
  runLine,
  runPreview,
  summary,
} from './fixtures';
import { PayrollRunDetailPage } from './PayrollRunDetailPage';
import { PayrollRunsPage } from './PayrollRunsPage';

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

const BASE = `/legal-entities/${ENTITY_ID}`;
const entities = { data: [{ id: ENTITY_ID, code: 'LE-1', name: 'Entité Test' }] };

/** The shared happy-path routes; a test overrides what it needs first. */
function routes(
  overrides: (request: Recorded) => Reply | undefined = () => undefined,
  policy: unknown = policyView(),
) {
  return (request: Recorded): Reply => {
    const own = overrides(request);
    if (own) return own;
    const { method, path } = request;
    if (path.startsWith('/legal-entities?')) return { status: 200, body: entities };
    if (method === 'GET' && path.startsWith(`${BASE}/payroll-policy`)) {
      return { status: 200, body: policy };
    }
    if (method === 'GET' && path.startsWith(`${BASE}/payroll-runs`)) {
      return { status: 200, body: { items: [summary()], nextCursor: null } };
    }
    return { status: 500 };
  };
}

function Where() {
  const location = useLocation();
  return <p data-testid="where">{`${location.pathname}${location.search}`}</p>;
}

async function show(locale: 'en' | 'fr') {
  return renderWithSession(
    <>
      <Where />
      <Routes>
        <Route path="/admin/payroll-runs" element={<PayrollRunsPage />} />
        <Route
          path="/admin/payroll-runs/:legalEntityId/:runId"
          element={<p data-testid="detail-reached">detail</p>}
        />
      </Routes>
    </>,
    sessionWithRoles(['tenant-admin']),
    locale,
    '/admin/payroll-runs',
  );
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

/** Every key and value of a browser store, for proving that no amount is kept in it. */
function stored(store: Storage): string {
  const values: string[] = [];
  for (let index = 0; index < store.length; index++) {
    const key = store.key(index) ?? '';
    values.push(key, store.getItem(key) ?? '');
  }
  return values.join('\n');
}

const flat = (text: string | null) => (text ?? '').replace(/\s/gu, ' ');

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

describe.each(['en', 'fr'] as const)('MVP-094A payroll runs page (%s)', (locale) => {
  const k = resources[locale].common.payrollRuns;

  it('shows the exact labels, the policy, the advisory and the run list', async () => {
    stub(routes());
    const { container } = await show(locale);
    expect(await screen.findByTestId('payroll-policy-current')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(k.title);
    expect(screen.getByTestId('payroll-policy-advisory')).toHaveTextContent(k.policy.advisory);
    expect(screen.getByTestId('payroll-policy-timeline')).toBeInTheDocument();
    const row = await screen.findByTestId('payroll-run-row');
    // Required labels, exactly as the decision writes them.
    expect(k.draftLabel).toBe(
      locale === 'en' ? 'Draft—not statutory payroll' : 'Brouillon — pas une paie statutaire',
    );
    expect(k.contractualGross).toBe(locale === 'en' ? 'Contractual gross' : 'Brut contractuel');
    expect(within(row).getByTestId('payroll-run-status')).toHaveTextContent(k.draftLabel);
    expect(screen.getByTestId('payroll-list')).toHaveTextContent(k.contractualGross);
    expect(screen.getAllByTestId('payroll-draft-label')[0]).toHaveTextContent(k.draftLabel);
    expect(flat(row.textContent)).toMatch(locale === 'en' ? /USD\s1,875\.00/u : /1\s875,00\sUSD/u);
    expect(within(row).getByRole('link')).toHaveAttribute(
      'href',
      `/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}`,
    );
    expect(container.textContent).not.toMatch(/\bpayroll\.[a-z]|payrollRuns\./u);
    await expectAccessible(container);
  });

  it('adds a policy: preview, blockers shown without a confirm, then an idempotent commit', async () => {
    const user = userEvent.setup();
    let version = 1;
    const requests = stub(
      routes((request) => {
        if (request.path.endsWith('/payroll-policy/preview')) {
          const blocked = request.body.includes('"CALENDAR_DAYS"');
          return {
            status: 200,
            body: blocked
              ? policyPreview({ blockers: ['NO_CHANGE'], previewDigest: null, expiresAt: null })
              : policyPreview(),
          };
        }
        if (request.method === 'POST' && request.path.endsWith('/payroll-policy')) {
          version = 2;
          return {
            status: 201,
            body: { policyId: 'x', sequence: 2, effectiveFrom: '2026-11-01', version },
          };
        }
        return undefined;
      }),
    );
    const { container } = await show(locale);
    await user.click(await screen.findByTestId('payroll-policy-open'));
    const form = screen.getByTestId('payroll-policy-form');
    expect(form.querySelector('h3')).toHaveFocus();
    await user.click(within(form).getByRole('button', { name: k.policy.preview }));
    const blockers = await screen.findByTestId('payroll-policy-blockers');
    expect(blockers).toHaveTextContent(k.policy.blockers.NO_CHANGE);
    expect(screen.queryByTestId('payroll-policy-confirm')).toBeNull();

    await user.selectOptions(within(form).getByLabelText(k.policy.proration), 'FIXED_30_DAYS');
    await user.selectOptions(within(form).getByLabelText(k.policy.payDay), '25');
    await user.click(within(form).getByRole('button', { name: k.policy.preview }));
    const confirm = await screen.findByTestId('payroll-policy-confirm');
    expect(screen.getByRole('heading', { level: 4, name: k.preview.title })).toHaveFocus();
    await expectAccessible(container);
    await user.click(confirm);
    await waitFor(() => {
      expect(screen.getByTestId('payroll-runs-announcer')).toHaveTextContent(k.policy.announce);
    });
    const commit = requests.find((r) => r.method === 'POST' && r.path.endsWith('/payroll-policy'));
    expect(commit?.key).toMatch(/^web-/u);
    expect(JSON.parse(commit?.body ?? '{}')).toMatchObject({
      expectedVersion: 1,
      confirmed: true,
      payDay: { kind: 'DAY', day: 25 },
      prorationMethod: 'FIXED_30_DAYS',
    });
    expect(version).toBe(2);
  });

  it('previews a run with blockers: the create button is disabled and says why', async () => {
    const user = userEvent.setup();
    const requests = stub(
      routes((request) =>
        request.path.endsWith('/payroll-runs/preview')
          ? {
              status: 200,
              body: runPreview({
                blockedEmployeeCount: 1,
                employeeCount: 2,
                previewDigest: null,
                expiresAt: null,
                lines: [blockedLine()],
              }),
            }
          : undefined,
      ),
    );
    await show(locale);
    await screen.findByTestId('payroll-policy-current');
    // The default month is the last closed month (September 2026 on the business date).
    expect(screen.getByTestId<HTMLSelectElement>('payroll-create-period').value).toBe('2026-09');
    expect(screen.getByTestId<HTMLSelectElement>('payroll-create-period').options).toHaveLength(24);
    await user.click(screen.getByTestId('payroll-create-preview'));
    const confirm = await screen.findByTestId('payroll-create-confirm');
    expect(confirm).toBeDisabled();
    expect(screen.getByTestId('payroll-create-disabled-why')).toHaveTextContent(
      k.create.whyBlocked,
    );
    const line = screen.getByTestId('payroll-line');
    expect(line).toHaveAttribute('data-blocked', 'true');
    expect(line).toHaveTextContent(k.employeeBlockers.COMPENSATION_MISSING);
    expect(screen.getByTestId('payroll-preview-blocked-note')).toBeInTheDocument();
    expect(requests.some((r) => r.method === 'POST' && r.path.endsWith('/payroll-runs'))).toBe(
      false,
    );
  });

  it('explains an identical draft and offers no creation', async () => {
    const user = userEvent.setup();
    stub(
      routes((request) =>
        request.path.endsWith('/payroll-runs/preview')
          ? {
              status: 200,
              body: runPreview({
                identicalToCurrent: true,
                existingRun: summary(),
                previewDigest: null,
                expiresAt: null,
              }),
            }
          : undefined,
      ),
    );
    await show(locale);
    await screen.findByTestId('payroll-policy-current');
    await user.click(screen.getByTestId('payroll-create-preview'));
    expect(await screen.findByTestId('payroll-preview-identical')).toHaveTextContent(
      k.preview.identical,
    );
    expect(screen.getByTestId('payroll-create-confirm')).toBeDisabled();
    expect(screen.getByTestId('payroll-create-disabled-why')).toHaveTextContent(
      k.create.whyIdentical,
    );
  });

  it('creates the run with an Idempotency-Key, reuses it on retry and navigates without amounts', async () => {
    const user = userEvent.setup();
    let attempts = 0;
    const requests = stub(
      routes((request) => {
        if (request.path.endsWith('/payroll-runs/preview')) {
          return { status: 200, body: runPreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/payroll-runs')) {
          attempts += 1;
          return attempts === 1
            ? { status: 429, body: problem('RATE_LIMITED', 429) }
            : { status: 201, body: receipt() };
        }
        return undefined;
      }),
    );
    const { container } = await show(locale);
    await screen.findByTestId('payroll-policy-current');
    await user.click(screen.getByTestId('payroll-create-preview'));
    const total = await screen.findByTestId('payroll-preview-total');
    expect(flat(total.textContent)).toMatch(
      locale === 'en' ? /USD\s1,875\.00/u : /1\s875,00\sUSD/u,
    );
    expect(screen.getByTestId('payroll-preview-lines')).toBeInTheDocument();
    expect(screen.getAllByTestId('payroll-line')).toHaveLength(2);
    expect(
      screen.getByRole('heading', {
        level: 3,
        name: new RegExp(k.preview.heading.split('{{')[0] ?? '', 'u'),
      }),
    ).toHaveFocus();
    await expectAccessible(container);
    await user.click(screen.getByTestId('payroll-create-confirm'));
    const failure = await screen.findByTestId('payroll-create-failure');
    expect(failure).toHaveTextContent(resources[locale].common.errors.RATE_LIMITED);
    expect(failure).toHaveFocus();
    await user.click(screen.getByTestId('payroll-create-confirm'));
    expect(await screen.findByTestId('detail-reached')).toBeInTheDocument();
    const posts = requests.filter((r) => r.method === 'POST' && r.path.endsWith('/payroll-runs'));
    expect(posts).toHaveLength(2);
    expect(posts[0]?.key).toMatch(/^web-/u);
    expect(posts[1]?.key).toBe(posts[0]?.key);
    expect(posts[1]?.body).toBe(posts[0]?.body);
    expect(JSON.parse(posts[0]?.body ?? '{}')).toMatchObject({
      period: '2026-09',
      currency: 'CDF',
      expectedVersion: 0,
      confirmed: true,
    });
    // The destination carries identifiers only: no amount, in the URL or in browser storage.
    const where = screen.getByTestId('where').textContent;
    expect(where).toBe(`/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}`);
    expect(where).not.toMatch(/1250|1875|625/u);
    expect(stored(window.localStorage)).not.toMatch(/1250|1875|625|E-000/u);
    expect(stored(window.sessionStorage)).not.toMatch(/1250|1875|625|E-000/u);
  });

  it('a stale preview clears the preview and the next attempt uses a new key', async () => {
    const user = userEvent.setup();
    let creates = 0;
    const requests = stub(
      routes((request) => {
        if (request.path.endsWith('/payroll-runs/preview')) {
          return { status: 200, body: runPreview() };
        }
        if (request.method === 'POST' && request.path.endsWith('/payroll-runs')) {
          creates += 1;
          return creates === 1
            ? { status: 409, body: problem('PAYROLL_RUN_PREVIEW_STALE', 409) }
            : { status: 201, body: receipt() };
        }
        return undefined;
      }),
    );
    await show(locale);
    await screen.findByTestId('payroll-policy-current');
    await user.click(screen.getByTestId('payroll-create-preview'));
    await user.click(await screen.findByTestId('payroll-create-confirm'));
    const failure = await screen.findByTestId('payroll-create-failure');
    expect(failure).toHaveAttribute('data-code', 'PAYROLL_RUN_PREVIEW_STALE');
    expect(failure).toHaveTextContent(resources[locale].common.errors.PAYROLL_RUN_PREVIEW_STALE);
    expect(screen.queryByTestId('payroll-create-confirm')).toBeNull();
    await user.click(screen.getByTestId('payroll-create-preview'));
    await user.click(await screen.findByTestId('payroll-create-confirm'));
    expect(await screen.findByTestId('detail-reached')).toBeInTheDocument();
    const posts = requests.filter((r) => r.method === 'POST' && r.path.endsWith('/payroll-runs'));
    expect(posts[1]?.key).not.toBe(posts[0]?.key);
  });

  it('loads more runs with the keyset cursor', async () => {
    const user = userEvent.setup();
    const requests = stub(
      routes((request) => {
        if (request.method === 'GET' && request.path.startsWith(`${BASE}/payroll-runs`)) {
          return request.path.includes('cursor=')
            ? {
                status: 200,
                body: {
                  items: [summary({ runId: RUN_ID_2, period: '2026-08' })],
                  nextCursor: null,
                },
              }
            : { status: 200, body: { items: [summary()], nextCursor: 'opaque-cursor' } };
        }
        return undefined;
      }),
    );
    await show(locale);
    await user.click(await screen.findByTestId('payroll-list-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('payroll-run-row')).toHaveLength(2);
    });
    expect(screen.queryByTestId('payroll-list-more')).toBeNull();
    expect(requests.some((r) => r.path.includes('cursor=opaque-cursor'))).toBe(true);
  });

  it('asks to add a policy first when none exists', async () => {
    stub(routes(() => undefined, noPolicyView()));
    await show(locale);
    expect(await screen.findByTestId('payroll-policy-none')).toHaveTextContent(k.policy.none);
  });
});

describe.each(['en', 'fr'] as const)('MVP-094A payroll run detail (%s)', (locale) => {
  const k = resources[locale].common.payrollRuns;
  const showDetail = () =>
    renderWithSession(
      <Routes>
        <Route
          path="/admin/payroll-runs/:legalEntityId/:runId"
          element={<PayrollRunDetailPage />}
        />
      </Routes>,
      sessionWithRoles(['tenant-admin']),
      locale,
      `/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}`,
    );

  it('shows the frozen policy, totals, ranges and source references, and loads more lines', async () => {
    const user = userEvent.setup();
    const requests = stub((request) =>
      request.path.includes('cursor=')
        ? {
            status: 200,
            body: runDetail({
              lines: [runLine({ employeeId: 'x2', employeeNumber: 'E-0002' })],
              nextCursor: null,
            }),
          }
        : { status: 200, body: runDetail({ nextCursor: 'opaque-lines' }) },
    );
    const { container } = await showDetail();
    expect(await screen.findByTestId('payroll-summary')).toBeInTheDocument();
    expect(requests[0]?.path).toContain('limit=50');
    expect(screen.getByTestId('payroll-summary')).toHaveTextContent(k.draftLabel);
    expect(screen.getByTestId('payroll-summary')).toHaveTextContent(k.contractualGross);
    expect(screen.getByTestId('payroll-lines-draft-label')).toHaveTextContent(k.draftLabel);
    expect(screen.getByRole('heading', { level: 2, name: k.detail.summary })).toHaveFocus();
    expect(screen.getByTestId('payroll-detail-policy')).toHaveTextContent(k.policy.advisory);
    expect(screen.getAllByTestId('payroll-source').map((s) => s.getAttribute('data-kind'))).toEqual(
      ['EMPLOYMENT', 'PLACEMENT', 'COMPENSATION'],
    );
    expect(screen.getAllByTestId('payroll-range')).toHaveLength(1);
    await expectAccessible(container);
    await user.click(screen.getByTestId('payroll-detail-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('payroll-line')).toHaveLength(2);
    });
    expect(requests.some((r) => r.path.includes('cursor=opaque-lines'))).toBe(true);
    expect(screen.queryByTestId('payroll-detail-more')).toBeNull();
  });

  it('shows a safe, complete message for an unknown run', async () => {
    stub(() => ({ status: 404, body: problem('PAYROLL_RUN_NOT_FOUND', 404) }));
    await showDetail();
    const alert = await screen.findByTestId('payroll-detail-error');
    expect(alert).toHaveAttribute('data-code', 'PAYROLL_RUN_NOT_FOUND');
    expect(alert).toHaveTextContent(resources[locale].common.errors.PAYROLL_RUN_NOT_FOUND);
  });
});

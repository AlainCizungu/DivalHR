import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { PayrollPreparationPage } from './PayrollPreparationPage';
import { attachmentName, explanationProblemOf, payrollFailureOf } from './payrollInputs';

const LEGAL = '22222222-0000-4000-8000-000000000002';
const RUN1 = '99999999-0000-4000-8000-000000000009';
const RUN2 = '99999999-0000-4000-8000-00000000000a';
const LOCK = '55555555-0000-4000-8000-000000000005';
const EXPORT = '88888888-0000-4000-8000-000000000008';
const DIGEST = 'b'.repeat(64);
const EVIDENCE = 'd'.repeat(64);
const START = '2026-03-02';
const END = '2026-03-15';

const en = resources.en.common;
const fr = resources.fr.common;

const NOTICE_EN =
  'Worked-time inputs only. Monthly salary is not calculated from these minutes. This export does not calculate or authorize pay, tax, deductions or payment.';
const NOTICE_FR =
  'Données de temps travaillé uniquement. Le salaire mensuel n’est pas calculé à partir de ces minutes. Cet export ne calcule ni n’autorise le salaire, les impôts, les retenues ou le paiement.';

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-pay-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
  key: string | null;
};

function stubApi(route: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((incoming: Request) => {
      const url = new URL(incoming.url);
      return (incoming.method === 'GET' ? Promise.resolve('') : incoming.text()).then((text) => {
        const recorded: Recorded = {
          method: incoming.method,
          path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
          body: text ? (JSON.parse(text) as Record<string, unknown>) : null,
          key: incoming.headers.get('Idempotency-Key'),
        };
        requests.push(recorded);
        const reply = route(recorded);
        return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
            ...reply.headers,
          },
        });
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
  window.localStorage.clear();
  window.sessionStorage.clear();
});

const scope = {
  legalEntityId: LEGAL,
  legalEntityCode: 'DRC',
  legalEntityName: 'Dival RDC',
  periodStart: START,
  periodEnd: END,
  businessTimezone: 'Africa/Kinshasa',
};
const counts = { timesheets: 6, employees: 3, rows: 8, workedMinutes: 3795 };

interface Chain {
  v2: boolean;
  locked: boolean;
  exported: boolean;
}

const runOf = (version: 1 | 2, chain: Chain) => {
  const id = version === 1 ? RUN1 : RUN2;
  const latest = version === (chain.v2 ? 2 : 1);
  const locked = latest && chain.locked;
  return {
    runId: id,
    version,
    status: 'GENERATED',
    schemaVersion: 1,
    scope,
    weekCount: 2,
    counts,
    excluded: [
      { code: 'ROW_OUTSIDE_PERIOD', count: 0 },
      { code: 'TIMESHEET_WITHOUT_TIME', count: 0 },
    ],
    evidenceDigest: EVIDENCE,
    generatedAt: version === 1 ? '2026-03-16T11:05:00Z' : '2026-03-17T11:05:00Z',
    lifecycle: {
      state: locked ? 'LOCKED' : 'OPEN',
      latest,
      supersedesRunId: version === 2 ? RUN1 : null,
      reasonCode: version === 2 ? 'OTHER' : null,
      lockActionId: locked ? LOCK : null,
      lockedAt: locked ? '2026-03-18T09:00:00Z' : null,
      exportId: locked && chain.exported ? EXPORT : null,
    },
  };
};

const exportView = {
  exportId: EXPORT,
  schemaVersion: 1,
  runId: RUN2,
  runVersion: 2,
  scope,
  lockActionId: LOCK,
  lockVersion: 1,
  employees: 3,
  rows: 8,
  workedMinutes: 3795,
  workedMinuteBand: 'FROM_3000_TO_5000',
  csvSha256: 'f'.repeat(64),
  createdAt: '2026-03-18T09:05:00Z',
};

const supersedePreview = (chain: Chain, ready: boolean) => ({
  current: runOf(1, chain),
  weeks: [],
  eligible: counts,
  blockers: ready ? [] : ['NO_CHANGED_EVIDENCE'],
  ready,
  previewDigest: DIGEST,
  expiresAt: '2026-03-18T09:30:00Z',
});

const lockPreview = (chain: Chain, blockers: string[]) => ({
  run: runOf(chain.v2 ? 2 : 1, chain),
  weeks: [],
  counts,
  workedMinuteBand: 'FROM_3000_TO_5000',
  supersedesRunId: chain.v2 ? RUN1 : null,
  supersedesVersion: chain.v2 ? 1 : null,
  blockers,
  ready: blockers.length === 0,
  previewDigest: DIGEST,
  expiresAt: '2026-03-18T09:30:00Z',
});

describe('MVP-092 browser rules', () => {
  it('maps the lifecycle refusals to stable codes and keeps the explanation rule small', () => {
    const response = (status: number) => new Response(null, { status });
    for (const [code, kind] of [
      ['PAYROLL_INPUT_NOT_LATEST', 'notLatest'],
      ['PAYROLL_INPUT_RUN_LOCKED', 'locked'],
      ['PAYROLL_INPUT_NOT_LOCKED', 'notLocked'],
      ['PAYROLL_INPUT_EXPORT_TOO_LARGE', 'tooLarge'],
    ] as const) {
      expect(payrollFailureOf(response(409), problem(code, 409))).toMatchObject({
        kind,
        messageKey: `errors.${code}`,
      });
      expect(en.errors[code]).toBeTruthy();
      expect(fr.errors[code]).toBeTruthy();
    }
    expect(explanationProblemOf('UPSTREAM_CORRECTION', '')).toBeNull();
    expect(explanationProblemOf('OTHER', '  ')).toBe('required');
    expect(explanationProblemOf('OTHER', 'x'.repeat(201))).toBe('length');
    expect(explanationProblemOf('OTHER', 'Scope fixed')).toBeNull();
    expect(attachmentName('attachment; filename="divalhr-worked-time-inputs-a-v2.csv"', 'x')).toBe(
      'divalhr-worked-time-inputs-a-v2.csv',
    );
    expect(attachmentName('attachment; filename="../etc/passwd"', 'safe.csv')).toBe('safe.csv');
    expect(attachmentName(null, 'safe.csv')).toBe('safe.csv');
  });

  it('keeps the fixed notices word for word in both languages', () => {
    expect(en.payrollPreparation.salaryNotice).toBe(NOTICE_EN);
    expect(fr.payrollPreparation.salaryNotice).toBe(NOTICE_FR);
  });
});

describe.each([
  ['en', en, NOTICE_EN],
  ['fr', fr, NOTICE_FR],
] as const)('MVP-092 payroll-input versions, lock and export (%s)', (locale, c, notice) => {
  const k = c.payrollPreparation;

  it('supersedes after changed evidence, locks, exports and downloads with the notice everywhere', async () => {
    const chain: Chain = { v2: false, locked: false, exported: false };
    let supersedeChecks = 0;
    let lockChecks = 0;
    const requests = stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
      }
      if (request.path.startsWith('/payroll-inputs?')) {
        return {
          status: 200,
          body: {
            items: chain.v2 ? [runOf(2, chain), runOf(1, chain)] : [runOf(1, chain)],
            nextCursor: null,
          },
        };
      }
      if (request.path.startsWith('/payroll-input-exports?')) {
        return {
          status: 200,
          body: { items: chain.exported ? [exportView] : [], nextCursor: null },
        };
      }
      if (request.path === `/payroll-inputs/${RUN1}/versions`) {
        return {
          status: 200,
          body: {
            items: [
              { run: runOf(1, chain), explanation: null },
              ...(chain.v2 ? [{ run: runOf(2, chain), explanation: 'Site scope fixed' }] : []),
            ],
          },
        };
      }
      if (request.path === `/payroll-inputs/${RUN2}/versions`) {
        return {
          status: 200,
          body: {
            items: [
              { run: runOf(1, chain), explanation: null },
              { run: runOf(2, chain), explanation: 'Site scope fixed' },
            ],
          },
        };
      }
      if (request.path === `/payroll-inputs/${RUN1}`) {
        return {
          status: 200,
          body: { run: runOf(1, chain), weeks: [], employees: [] },
        };
      }
      if (request.path === `/payroll-inputs/${RUN2}`) {
        return {
          status: 200,
          body: { run: runOf(2, chain), weeks: [], employees: [] },
        };
      }
      if (request.path === `/payroll-inputs/${RUN1}/supersede-preview`) {
        supersedeChecks += 1;
        return { status: 200, body: supersedePreview(chain, supersedeChecks > 1) };
      }
      if (request.path === `/payroll-inputs/${RUN1}/supersede`) {
        chain.v2 = true;
        return {
          status: 200,
          body: { outcome: 'SUPERSEDED', run: runOf(2, chain), supersedesRunId: RUN1 },
        };
      }
      if (request.path === `/payroll-inputs/${RUN2}/lock-preview`) {
        lockChecks += 1;
        return {
          status: 200,
          body: lockPreview(chain, lockChecks === 1 ? ['UPSTREAM_EVIDENCE_CHANGED'] : []),
        };
      }
      if (request.path === `/payroll-inputs/${RUN2}/lock`) {
        chain.locked = true;
        return { status: 200, body: { outcome: 'LOCKED', run: runOf(2, chain) } };
      }
      if (request.path === '/payroll-input-exports' && request.method === 'POST') {
        chain.exported = true;
        return { status: 200, body: { outcome: 'CREATED', export: exportView } };
      }
      if (request.path === `/payroll-input-exports/${EXPORT}/csv`) {
        return {
          status: 200,
          body: 'employee_id',
          headers: { 'Content-Type': 'text/csv; charset=utf-8' },
        };
      }
      if (request.path === `/payroll-input-exports/${EXPORT}/manifest`) {
        return { status: 200, body: { schemaVersion: 1 } };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const saved: string[] = [];
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }),
    );
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      saved.push(this.download);
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );

    // History: version and OPEN text state, with the notice; exports area with its notice.
    const history = await screen.findByTestId('payroll-prep-history');
    await waitFor(() => {
      expect(within(history).getAllByTestId('payroll-prep-item')).toHaveLength(1);
    });
    expect(within(history).getByTestId('payroll-prep-history-notice')).toHaveTextContent(notice);
    expect(within(history).getByText(k.lifecycle.state.OPEN)).toBeVisible();
    expect(await screen.findByTestId('payroll-prep-exports-empty')).toHaveTextContent(
      k.exports.empty,
    );
    expect(screen.getByTestId('payroll-prep-exports-notice')).toHaveTextContent(notice);

    await user.click(within(history).getByTestId('payroll-prep-item-open'));
    const detail = await screen.findByTestId('payroll-prep-detail');
    expect(within(detail).getByTestId('payroll-prep-detail-notice')).toHaveTextContent(notice);
    const panel = await within(detail).findByTestId('payroll-prep-lifecycle');
    expect(panel).toHaveAttribute('data-state', 'OPEN');
    await waitFor(() => {
      expect(within(panel).getAllByTestId('payroll-prep-version')).toHaveLength(1);
    });
    await expectAccessible(container);

    // Nothing changed upstream: the server says so and no replacement can be confirmed.
    await user.click(within(panel).getByTestId('payroll-prep-supersede-check'));
    const none = await within(panel).findByTestId('payroll-prep-supersede-blocked');
    expect(none).toHaveTextContent(k.lifecycle.supersede.noChange);
    expect(
      within(none).getByTestId('payroll-prep-supersede-blocker-NO_CHANGED_EVIDENCE'),
    ).toBeVisible();
    expect(within(panel).queryByTestId('payroll-prep-supersede')).toBeNull();

    // Changed evidence: the action appears; OTHER needs an explanation.
    await user.click(within(panel).getByTestId('payroll-prep-supersede-check'));
    const ready = await within(panel).findByTestId('payroll-prep-supersede-preview');
    await waitFor(() => {
      expect(ready).toHaveAttribute('data-ready', 'true');
    });
    expect(within(ready).getByTestId('payroll-prep-supersede-notice')).toHaveTextContent(notice);
    await user.selectOptions(within(ready).getByTestId('payroll-prep-supersede-reason'), 'OTHER');
    await user.click(within(ready).getByTestId('payroll-prep-supersede-confirm'));
    await user.click(within(ready).getByTestId('payroll-prep-supersede'));
    expect(await within(ready).findByText(k.lifecycle.supersede.problem.required)).toBeVisible();
    expect(requests.some((r) => r.path.endsWith('/supersede') && r.method === 'POST')).toBe(false);
    await expectAccessible(container);
    await user.type(
      within(ready).getByTestId('payroll-prep-supersede-explanation'),
      'Site scope fixed',
    );
    await user.click(within(ready).getByTestId('payroll-prep-supersede'));

    // Version 2 is shown; version 1 stays in the chain, no longer latest.
    await waitFor(() => {
      expect(screen.getByTestId('payroll-prep-detail-version')).toHaveTextContent('2');
    });
    const sent = requests.find((r) => r.path === `/payroll-inputs/${RUN1}/supersede`);
    expect(sent?.body).toEqual({
      reasonCode: 'OTHER',
      explanation: 'Site scope fixed',
      previewDigest: DIGEST,
      confirm: true,
    });
    expect(sent?.key).toMatch(/^web-[0-9a-f-]{36}$/u);
    const v2 = await screen.findByTestId('payroll-prep-lifecycle');
    await waitFor(() => {
      expect(within(v2).getAllByTestId('payroll-prep-version')).toHaveLength(2);
    });
    expect(within(v2).getAllByTestId('payroll-prep-version')[0]).toHaveAttribute(
      'data-latest',
      'false',
    );
    expect(within(v2).getByTestId('payroll-prep-explanation')).toHaveTextContent(
      'Site scope fixed',
    );
    expect(screen.getByTestId('payroll-prep-announcer')).toHaveTextContent(
      k.lifecycle.supersede.done.replace('{{version}}', '2'),
    );

    // Lock: blocked first (the server's blocker), then ready; confirmation is required.
    await user.click(within(v2).getByTestId('payroll-prep-lock-check'));
    const blocked = await within(v2).findByTestId('payroll-prep-lock-blocked');
    expect(
      within(blocked).getByTestId('payroll-prep-lock-blocker-UPSTREAM_EVIDENCE_CHANGED'),
    ).toHaveTextContent(k.lifecycle.blocker.UPSTREAM_EVIDENCE_CHANGED);
    expect(within(v2).queryByTestId('payroll-prep-lock')).toBeNull();
    await user.click(within(v2).getByTestId('payroll-prep-lock-check'));
    const lockView = await within(v2).findByTestId('payroll-prep-lock-preview');
    await waitFor(() => {
      expect(lockView).toHaveAttribute('data-ready', 'true');
    });
    expect(within(lockView).getByTestId('payroll-prep-lock-notice')).toHaveTextContent(notice);
    expect(within(lockView).getByTestId('payroll-prep-lock-rows')).toHaveTextContent('8');
    const lockButton = within(lockView).getByTestId('payroll-prep-lock');
    expect(lockButton).toBeDisabled();
    await expectAccessible(container);
    await user.click(within(lockView).getByTestId('payroll-prep-lock-confirm'));
    await user.click(lockButton);
    const locked = await screen.findByText(k.lifecycle.state.LOCKED, {
      selector: '[data-testid="payroll-prep-lifecycle-state"] *',
    });
    expect(locked).toBeVisible();
    expect(requests.find((r) => r.path === `/payroll-inputs/${RUN2}/lock`)?.body).toEqual({
      previewDigest: DIGEST,
      confirm: true,
    });

    // Export of the locked version, then the downloads.
    const lockedPanel = await screen.findByTestId('payroll-prep-lifecycle');
    await waitFor(() => {
      expect(lockedPanel).toHaveAttribute('data-state', 'LOCKED');
    });
    expect(within(lockedPanel).getByTestId('payroll-prep-export-notice')).toHaveTextContent(notice);
    expect(within(lockedPanel).queryByTestId('payroll-prep-lock-check')).toBeNull();
    expect(within(lockedPanel).queryByTestId('payroll-prep-supersede-check')).toBeNull();
    await user.click(within(lockedPanel).getByTestId('payroll-prep-export-confirm'));
    await user.click(within(lockedPanel).getByTestId('payroll-prep-export-create'));
    const exports = screen.getByTestId('payroll-prep-exports');
    const row = await within(exports).findByTestId('payroll-prep-export');
    expect(
      requests.find((r) => r.path === '/payroll-input-exports' && r.method === 'POST')?.body,
    ).toEqual({ runId: RUN2, confirm: true });
    expect(within(row).getByTestId('payroll-prep-export-sha')).toHaveTextContent('f'.repeat(64));
    expect(within(row).getByTestId('payroll-prep-export-rows')).toHaveTextContent('8');
    await expectAccessible(container);
    await user.click(within(row).getByTestId('payroll-prep-export-csv'));
    await waitFor(() => {
      expect(saved).toHaveLength(1);
    });
    await user.click(within(row).getByTestId('payroll-prep-export-manifest'));
    await waitFor(() => {
      expect(saved).toHaveLength(2);
    });
    expect(saved[0]).toBe(`divalhr-worked-time-inputs-${LEGAL}-${START}-${END}-v2.csv`);
    expect(saved[1]).toBe(`divalhr-worked-time-inputs-${LEGAL}-${START}-${END}-v2.manifest.json`);
    expect(screen.getByTestId('payroll-prep-announcer')).toHaveTextContent(
      k.exports.manifestDownloaded,
    );
    // Nothing of the runs, the digests or the export in browser storage or the URL.
    const stored = [window.localStorage, window.sessionStorage]
      .flatMap((storage) => Object.entries(storage))
      .join('\n');
    expect(stored).not.toMatch(new RegExp(`${RUN1}|${RUN2}|${EXPORT}|${DIGEST}|${EVIDENCE}`, 'u'));
    expect(window.location.search + window.location.hash).toBe('');
  });

  it('shows an older version as not the latest, with the server blocker and a way to the latest', async () => {
    const chain: Chain = { v2: true, locked: false, exported: false };
    stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
      }
      if (request.path.startsWith('/payroll-inputs?')) {
        return {
          status: 200,
          body: { items: [runOf(2, chain), runOf(1, chain)], nextCursor: null },
        };
      }
      if (request.path.startsWith('/payroll-input-exports?')) {
        return { status: 200, body: { items: [], nextCursor: null } };
      }
      if (request.path === `/payroll-inputs/${RUN1}`) {
        return { status: 200, body: { run: runOf(1, chain), weeks: [], employees: [] } };
      }
      if (request.path === `/payroll-inputs/${RUN1}/versions`) {
        return {
          status: 200,
          body: {
            items: [
              { run: runOf(1, chain), explanation: null },
              { run: runOf(2, chain), explanation: null },
            ],
          },
        };
      }
      if (request.path === `/payroll-inputs/${RUN1}/lock-preview`) {
        return {
          status: 200,
          body: { ...lockPreview(chain, ['NOT_LATEST_VERSION']), run: runOf(1, chain) },
        };
      }
      if (request.path === `/payroll-inputs/${RUN1}/lock`) {
        return { status: 409, body: problem('PAYROLL_INPUT_NOT_LATEST', 409) };
      }
      if (request.path === `/payroll-inputs/${RUN2}`) {
        return { status: 200, body: { run: runOf(2, chain), weeks: [], employees: [] } };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );
    const items = await screen.findAllByTestId('payroll-prep-item-open');
    await waitFor(() => {
      expect(items).toHaveLength(2);
    });
    const older = items[1];
    expect(older).toBeDefined();
    if (older) await user.click(older);
    const panel = await screen.findByTestId('payroll-prep-lifecycle');
    expect(panel).toHaveAttribute('data-latest', 'false');
    expect(within(panel).getByText(k.lifecycle.notLatest)).toBeVisible();
    await user.click(within(panel).getByTestId('payroll-prep-lock-check'));
    const blocked = await within(panel).findByTestId('payroll-prep-lock-blocked');
    expect(
      within(blocked).getByTestId('payroll-prep-lock-blocker-NOT_LATEST_VERSION'),
    ).toHaveTextContent(k.lifecycle.blocker.NOT_LATEST_VERSION);
    await expectAccessible(container);
    const latest = await within(panel).findByTestId('payroll-prep-open-latest');
    await user.click(latest);
    await waitFor(() => {
      expect(screen.getByTestId('payroll-prep-detail-version')).toHaveTextContent('2');
    });
    expect(screen.getByTestId('payroll-prep-lifecycle')).toHaveAttribute('data-latest', 'true');
  });

  it('drops a stale lock preview with a stable message and never confirms on its own', async () => {
    const chain: Chain = { v2: false, locked: false, exported: false };
    const requests = stubApi((request) => {
      if (request.path.startsWith('/legal-entities')) {
        return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
      }
      if (request.path.startsWith('/payroll-inputs?')) {
        return { status: 200, body: { items: [runOf(1, chain)], nextCursor: null } };
      }
      if (request.path.startsWith('/payroll-input-exports?')) {
        return { status: 200, body: { items: [], nextCursor: null } };
      }
      if (request.path === `/payroll-inputs/${RUN1}`) {
        return { status: 200, body: { run: runOf(1, chain), weeks: [], employees: [] } };
      }
      if (request.path === `/payroll-inputs/${RUN1}/versions`) {
        return { status: 200, body: { items: [{ run: runOf(1, chain), explanation: null }] } };
      }
      if (request.path === `/payroll-inputs/${RUN1}/lock-preview`) {
        return { status: 200, body: lockPreview(chain, []) };
      }
      if (request.path === `/payroll-inputs/${RUN1}/lock`) {
        return { status: 409, body: problem('PAYROLL_INPUT_PREVIEW_STALE', 409) };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <PayrollPreparationPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/payroll-preparation',
    );
    await user.click(await screen.findByTestId('payroll-prep-item-open'));
    const panel = await screen.findByTestId('payroll-prep-lifecycle');
    await user.click(within(panel).getByTestId('payroll-prep-lock-check'));
    await user.click(await within(panel).findByTestId('payroll-prep-lock-confirm'));
    await user.click(within(panel).getByTestId('payroll-prep-lock'));
    const error = await within(panel).findByTestId('payroll-prep-lifecycle-error');
    expect(error).toHaveTextContent(c.errors.PAYROLL_INPUT_PREVIEW_STALE);
    expect(within(panel).queryByTestId('payroll-prep-lock-preview')).toBeNull();
    expect(requests.filter((r) => r.path.endsWith('/lock') && r.method === 'POST')).toHaveLength(1);
  });
});

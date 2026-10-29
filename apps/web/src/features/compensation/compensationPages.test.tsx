import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { EmployeeCompensationSection } from './EmployeeCompensationSection';
import { MyCompensationPage } from './MyCompensationPage';

const EMPLOYEE_ID = '22222222-0000-4000-8000-000000000002';
const CHANGE_A = '33333333-0000-4000-8000-00000000000a';
const CHANGE_B = '33333333-0000-4000-8000-00000000000b';
const DIGEST_E = 'e'.repeat(64);
const DIGEST_P = 'a'.repeat(64);
const DIGEST_C = 'c'.repeat(64);
const EXPIRES = '2099-01-01T10:15:00Z';

const interval = (overrides: Record<string, unknown> = {}) => ({
  originChangeId: CHANGE_A,
  money: { currency: 'USD', amount: '1250.00' },
  frequency: 'MONTHLY',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  cancellable: false,
  ...overrides,
});
const change = (overrides: Record<string, unknown> = {}) => ({
  changeId: CHANGE_A,
  sequence: 1,
  action: 'ESTABLISHED',
  money: { currency: 'USD', amount: '1250.00' },
  effectiveFrom: '2026-01-01',
  reasonCode: null,
  explanation: null,
  targetChangeId: null,
  retroactive: false,
  recordedAt: '2026-01-02T08:00:00Z',
  ...overrides,
});
const record = (overrides: Record<string, unknown> = {}) => ({
  version: 1,
  businessDate: '2026-10-29',
  current: interval(),
  scheduled: [],
  changes: [change()],
  nextCursor: null,
  ...overrides,
});
const mine = (overrides: Record<string, unknown> = {}) => {
  const own: Record<string, unknown> = record(overrides);
  delete own.version;
  return own;
};
const emptyRecord = () => record({ version: 0, current: null, changes: [] });
const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-comp-9999',
});
const preview = (overrides: Record<string, unknown> = {}) => ({
  action: 'ESTABLISHED',
  money: { currency: 'USD', amount: '1250.00' },
  effectiveFrom: '2026-10-29',
  before: [],
  after: [interval({ effectiveFrom: '2026-10-29' })],
  warnings: [],
  blockers: [],
  requiresReason: false,
  requiresAcknowledgement: false,
  version: 0,
  employmentDigest: DIGEST_E,
  previewDigest: DIGEST_P,
  expiresAt: EXPIRES,
  ...overrides,
});
const receipt = (action = 'ESTABLISHED') => ({
  changeId: CHANGE_A,
  sequence: 1,
  action,
  effectiveFrom: '2026-10-29',
  version: 1,
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

/** The text with every kind of space turned into a plain one, for comparing formatted money. */
const flat = (text: string | null) => (text ?? '').replace(/\s/gu, ' ');

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('MVP-093A employee page /me/compensation (%s)', (locale) => {
  const k = resources[locale].common.compensation;
  const show = () =>
    renderWithSession(
      <MyCompensationPage />,
      sessionWithRoles(['employee']),
      locale,
      '/me/compensation',
    );

  it('shows the own value with its currency code, the history, the fixed notice and no control', async () => {
    const requests = stub(() => ({
      status: 200,
      body: mine({
        current: interval({ money: { currency: 'CDF', amount: '135000' } }),
        scheduled: [
          interval({
            originChangeId: CHANGE_B,
            money: { currency: 'CDF', amount: '150000' },
            effectiveFrom: '2026-12-01',
            cancellable: true,
          }),
        ],
        changes: [
          change({
            changeId: CHANGE_B,
            sequence: 2,
            action: 'CORRECTED',
            reasonCode: 'DATA_ENTRY_ERROR',
            retroactive: true,
            // The employee operation never returns this; even if it did, nothing renders it.
            explanation: 'Administrator-only wording that must not appear',
            money: { currency: 'CDF', amount: '135000' },
          }),
          change({ money: { currency: 'CDF', amount: '130000' } }),
        ],
      }),
    }));
    const { container } = await show();
    const current = await screen.findByTestId('current-interval');
    expect(flat(current.textContent)).toContain('CDF');
    expect(within(current).getByTestId('money')).toHaveAttribute('data-currency', 'CDF');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(k.me.title);
    expect(screen.getByTestId('my-compensation-notice')).toHaveTextContent(k.me.notice);
    expect(screen.getAllByTestId('scheduled-interval')).toHaveLength(1);
    const rows = screen.getAllByTestId('compensation-change');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent(k.reason.DATA_ENTRY_ERROR);
    expect(rows[0]).toHaveTextContent(k.history.retroactive);
    expect(container).not.toHaveTextContent('Administrator-only wording');
    expect(screen.queryByTestId('change-explanation')).toBeNull();
    // No editing control at all, and nothing that could name another employee.
    expect(screen.queryAllByRole('button')).toHaveLength(0);
    expect(container.querySelector('input, select, textarea, form')).toBeNull();
    // No wide table: the history reflows as a list.
    expect(container.querySelector('table, .table-wrapper, [role="region"]')).toBeNull();
    expect(requests.map((r) => r.path)).toEqual(['/me/compensation']);
    await expectAccessible(container);
  });

  it('shows an empty state when nothing is recorded', async () => {
    stub(() => ({ status: 200, body: mine({ current: null, changes: [] }) }));
    const { container } = await show();
    expect(await screen.findByTestId('compensation-empty')).toHaveTextContent(k.me.empty);
    expect(screen.getByTestId('compensation-history-empty')).toBeVisible();
    await expectAccessible(container);
  });

  it('shows a translated alert for an unlinked account, and loads more history by cursor', async () => {
    stub(() => ({ status: 403, body: problem('EMPLOYEE_LINK_REQUIRED', 403) }));
    const first = await show();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(resources[locale].common.errors.EMPLOYEE_LINK_REQUIRED);
    expect(alert).toHaveTextContent('corr-comp-9999');
    await expectAccessible(first.container);
    first.unmount();

    const requests = stub((r) =>
      r.path.includes('cursor=')
        ? { status: 200, body: mine({ changes: [change({ changeId: CHANGE_B, sequence: 1 })] }) }
        : { status: 200, body: mine({ changes: [change({ sequence: 2 })], nextCursor: 'opaque' }) },
    );
    const user = userEvent.setup();
    const { container } = await show();
    await screen.findAllByTestId('compensation-change');
    await user.click(screen.getByTestId('compensation-more'));
    await waitFor(() => {
      expect(screen.getAllByTestId('compensation-change')).toHaveLength(2);
    });
    expect(screen.queryByTestId('compensation-more')).toBeNull();
    expect(requests.map((r) => r.path)).toEqual([
      '/me/compensation',
      '/me/compensation?cursor=opaque',
    ]);
    await expectAccessible(container);
  });

  it('keeps nothing in browser storage', async () => {
    const set = vi.spyOn(Storage.prototype, 'setItem');
    stub(() => ({ status: 200, body: mine() }));
    await show();
    await screen.findByTestId('current-interval');
    expect(set).not.toHaveBeenCalled();
  });
});

describe.each(['en', 'fr'] as const)('MVP-093A administrator section (%s)', (locale) => {
  const k = resources[locale].common.compensation;
  const show = () =>
    renderWithSession(
      <EmployeeCompensationSection employeeId={EMPLOYEE_ID} revision={0} onChanged={() => {}} />,
      sessionWithRoles(['tenant-admin']),
      locale,
      `/admin/people/${EMPLOYEE_ID}`,
    );

  it('shows the notices, the explanation to the administrator and cancel only where cancellable', async () => {
    stub(() => ({
      status: 200,
      body: record({
        version: 4,
        scheduled: [
          interval({
            originChangeId: CHANGE_B,
            money: { currency: 'USD', amount: '1500.00' },
            effectiveFrom: '2027-01-01',
            cancellable: true,
          }),
          interval({
            originChangeId: '33333333-0000-4000-8000-00000000000c',
            money: { currency: 'USD', amount: '1600.00' },
            effectiveFrom: '2027-06-01',
            cancellable: false,
          }),
        ],
        changes: [
          change({
            changeId: CHANGE_B,
            sequence: 2,
            action: 'CORRECTED',
            reasonCode: 'WRONG_CURRENCY',
            explanation: 'Montant saisi en USD au lieu de CDF',
            retroactive: true,
          }),
        ],
      }),
    }));
    const { container } = await show();
    await screen.findByTestId('current-interval');
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(k.title);
    expect(screen.getByTestId('compensation-notice')).toHaveTextContent(k.notice);
    expect(screen.getByTestId('compensation-privacy')).toHaveTextContent(k.privacy);
    expect(screen.getByTestId('change-explanation')).toHaveTextContent(
      'Montant saisi en USD au lieu de CDF',
    );
    expect(screen.getAllByTestId('scheduled-interval')).toHaveLength(2);
    expect(screen.getAllByTestId('compensation-cancel')).toHaveLength(1);
    expect(screen.getAllByTestId('compensation-change')).toHaveLength(1);
    expect(container.querySelector('table, .table-wrapper')).toBeNull();
    await expectAccessible(container);
  });

  it('previews, then commits exactly what the preview returned, with a fresh key', async () => {
    let committed = false;
    const requests = stub((r) => {
      if (r.method === 'GET') {
        return { status: 200, body: committed ? record() : emptyRecord() };
      }
      if (r.path.endsWith('/preview')) return { status: 200, body: preview() };
      committed = true;
      return { status: 201, body: receipt() };
    });
    const user = userEvent.setup();
    const { container } = await show();
    await user.click(await screen.findByTestId('compensation-open-establish'));
    const form = screen.getByTestId('compensation-form');
    expect(form).toHaveAttribute('data-mode', 'ESTABLISHED');
    expect(screen.getByRole('heading', { name: k.form.title.ESTABLISHED })).toHaveFocus();
    const amount = within(form).getByLabelText(k.form.amount);
    expect(amount).toHaveAttribute('inputmode', 'decimal');
    expect(amount).toHaveAttribute('type', 'text');

    // An invalid amount never reaches the server.
    await user.click(within(form).getByRole('button', { name: k.form.preview }));
    expect(screen.getByTestId('compensation-problems')).toHaveFocus();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await expectAccessible(container);

    await user.type(amount, '1250,5');
    await user.click(within(form).getByRole('button', { name: k.form.preview }));
    const panel = await screen.findByTestId('compensation-preview');
    expect(within(panel).getByRole('heading', { level: 4, name: k.preview.title })).toHaveFocus();
    expect(screen.getByTestId('preview-after')).toBeVisible();
    await expectAccessible(container);

    const previewRequest = requests.find((r) => r.path.endsWith('/preview'));
    expect(previewRequest?.path).toBe(`/employees/${EMPLOYEE_ID}/compensation/preview`);
    expect(JSON.parse(previewRequest?.body ?? '{}')).toEqual({
      action: 'ESTABLISHED',
      effectiveFrom: '2026-10-29',
      currency: 'USD',
      amount: '1250.50',
    });
    expect(requests.some((r) => r.path.endsWith('/commit'))).toBe(false);

    await user.click(screen.getByTestId('compensation-confirm'));
    await waitFor(() => {
      expect(requests.some((r) => r.path.endsWith('/commit'))).toBe(true);
    });
    const commit = requests.find((r) => r.path.endsWith('/commit'));
    expect(commit?.key).toMatch(/^web-/u);
    expect(JSON.parse(commit?.body ?? '{}')).toEqual({
      action: 'ESTABLISHED',
      effectiveFrom: '2026-10-29',
      currency: 'USD',
      amount: '1250.50',
      targetChangeId: null,
      reasonCode: null,
      explanation: null,
      expectedVersion: 0,
      employmentDigest: DIGEST_E,
      previewDigest: DIGEST_P,
      expiresAt: EXPIRES,
      acknowledgeRetroactive: false,
      confirmed: true,
    });
    // The form is gone, the record is reloaded and the section heading has focus.
    await waitFor(() => {
      expect(screen.queryByTestId('compensation-form')).toBeNull();
    });
    await screen.findByTestId('current-interval');
    expect(screen.getByRole('heading', { level: 2, name: k.title })).toHaveFocus();
    await expectAccessible(container);
  });

  it('refuses to confirm a blocked preview and shows its translated blockers', async () => {
    const requests = stub((r) => {
      if (r.method === 'GET') return { status: 200, body: record() };
      return {
        status: 200,
        body: preview({
          action: 'CHANGED',
          blockers: ['NO_CHANGE', 'LIMIT_REACHED'],
          previewDigest: null,
          expiresAt: null,
        }),
      };
    });
    const user = userEvent.setup();
    const { container } = await show();
    await user.click(await screen.findByTestId('compensation-open-change'));
    await user.type(screen.getByLabelText(k.form.amount), '1250');
    await user.click(screen.getByRole('button', { name: k.form.preview }));
    const blockers = await screen.findByTestId('preview-blockers');
    expect(blockers).toHaveTextContent(k.blockers.NO_CHANGE);
    expect(blockers).toHaveTextContent(k.blockers.LIMIT_REACHED);
    expect(screen.queryByTestId('compensation-confirm')).toBeNull();
    expect(requests.some((r) => r.path.endsWith('/commit'))).toBe(false);
    await expectAccessible(container);
  });

  it('on a stale preview or version conflict shows the message and asks for a new preview', async () => {
    for (const code of ['COMPENSATION_PREVIEW_STALE', 'COMPENSATION_VERSION_CONFLICT']) {
      const requests = stub((r) => {
        if (r.method === 'GET') return { status: 200, body: record() };
        if (r.path.endsWith('/preview'))
          return { status: 200, body: preview({ action: 'CHANGED' }) };
        return { status: 409, body: problem(code, 409) };
      });
      const user = userEvent.setup();
      const { container, unmount } = await show();
      await user.click(await screen.findByTestId('compensation-open-change'));
      await user.type(screen.getByLabelText(k.form.amount), '1500');
      await user.click(screen.getByRole('button', { name: k.form.preview }));
      await user.click(await screen.findByTestId('compensation-confirm'));
      const alert = await screen.findByTestId('compensation-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(
        resources[locale].common.errors[code as 'COMPENSATION_PREVIEW_STALE'],
      );
      // The stale preview is dropped: nothing can be confirmed until it is previewed again.
      expect(screen.queryByTestId('compensation-preview')).toBeNull();
      expect(screen.queryByTestId('compensation-confirm')).toBeNull();
      // The record was reloaded.
      await waitFor(() => {
        expect(requests.filter((r) => r.method === 'GET').length).toBeGreaterThanOrEqual(2);
      });
      await expectAccessible(container);
      unmount();
      vi.unstubAllGlobals();
    }
  });

  it('corrects with a reason, an explanation and the retroactive acknowledgement', async () => {
    const requests = stub((r) => {
      if (r.method === 'GET') return { status: 200, body: record({ version: 3 }) };
      if (r.path.endsWith('/preview')) {
        return {
          status: 200,
          body: preview({
            action: 'CORRECTED',
            effectiveFrom: '2026-10-01',
            money: { currency: 'CDF', amount: '135000' },
            warnings: ['RETROACTIVE'],
            requiresReason: true,
            requiresAcknowledgement: true,
            version: 3,
          }),
        };
      }
      return { status: 201, body: receipt('CORRECTED') };
    });
    const user = userEvent.setup();
    const { container } = await show();
    await user.click(await screen.findByTestId('compensation-correct'));
    const form = screen.getByTestId('compensation-form');
    expect(form).toHaveAttribute('data-mode', 'CORRECTED');
    // Prefilled from the corrected interval.
    expect(within(form).getByLabelText(k.form.amount)).toHaveValue('1250.00');
    await user.click(within(form).getByRole('button', { name: k.form.preview }));
    const summary = screen.getByTestId('compensation-problems');
    expect(summary).toHaveTextContent(k.form.problems.reasonCode);
    expect(summary).toHaveTextContent(k.form.problems.explanation);
    await expectAccessible(container);

    await user.selectOptions(within(form).getByLabelText(k.form.currency), 'CDF');
    const amount = within(form).getByLabelText(k.form.amount);
    await user.clear(amount);
    await user.type(amount, '135000');
    await user.selectOptions(within(form).getByLabelText(k.form.reasonCode), 'WRONG_CURRENCY');
    await user.type(within(form).getByLabelText(k.form.explanation), 'Saisi en USD par erreur');
    await user.click(within(form).getByRole('button', { name: k.form.preview }));
    await screen.findByTestId('compensation-preview');
    expect(screen.getByTestId('preview-warnings')).toHaveTextContent(k.warnings.RETROACTIVE);

    // Not confirmed without the explicit acknowledgement.
    await user.click(screen.getByTestId('compensation-confirm'));
    expect(screen.getByTestId('compensation-problems')).toHaveTextContent(
      k.form.problems.acknowledge,
    );
    expect(requests.some((r) => r.path.endsWith('/commit'))).toBe(false);
    await user.click(screen.getByRole('checkbox', { name: k.preview.acknowledge }));
    await expectAccessible(container);
    await user.click(screen.getByTestId('compensation-confirm'));
    await waitFor(() => {
      expect(requests.some((r) => r.path.endsWith('/commit'))).toBe(true);
    });
    const commit = requests.find((r) => r.path.endsWith('/commit'));
    expect(JSON.parse(commit?.body ?? '{}')).toMatchObject({
      action: 'CORRECTED',
      currency: 'CDF',
      amount: '135000',
      targetChangeId: CHANGE_A,
      reasonCode: 'WRONG_CURRENCY',
      explanation: 'Saisi en USD par erreur',
      expectedVersion: 3,
      previewDigest: DIGEST_P,
      acknowledgeRetroactive: true,
      confirmed: true,
    });
  });

  it('cancels a scheduled change: preview first, then the preview digests, by keyboard', async () => {
    const scheduled = interval({
      originChangeId: CHANGE_B,
      money: { currency: 'USD', amount: '1500.00' },
      effectiveFrom: '2027-01-01',
      cancellable: true,
    });
    const requests = stub((r) => {
      if (r.method === 'GET')
        return { status: 200, body: record({ version: 2, scheduled: [scheduled] }) };
      if (r.path.endsWith('/cancel-preview')) {
        return {
          status: 200,
          body: {
            changeId: CHANGE_B,
            before: [scheduled],
            after: [interval()],
            warnings: [],
            blockers: [],
            version: 2,
            employmentDigest: DIGEST_E,
            cancellationDigest: DIGEST_C,
            expiresAt: EXPIRES,
          },
        };
      }
      return { status: 201, body: receipt('SCHEDULED_CHANGE_CANCELLED') };
    });
    const user = userEvent.setup();
    const { container } = await show();
    const trigger = await screen.findByTestId('compensation-cancel');
    trigger.focus();
    await user.keyboard('{Enter}');
    const panel = await screen.findByTestId('compensation-cancel-panel');
    expect(requests.map((r) => r.path)).toContain(
      `/employees/${EMPLOYEE_ID}/compensation/${CHANGE_B}/cancel-preview`,
    );
    expect(requests.some((r) => r.path.endsWith(`${CHANGE_B}/cancel`))).toBe(false);
    const confirm = await within(panel).findByTestId('compensation-cancel-confirm');
    expect(within(panel).getByTestId('preview-before')).toBeVisible();
    await expectAccessible(container);
    confirm.focus();
    await user.keyboard(' ');
    await waitFor(() => {
      expect(requests.some((r) => r.path.endsWith(`${CHANGE_B}/cancel`))).toBe(true);
    });
    const cancel = requests.find((r) => r.path.endsWith(`${CHANGE_B}/cancel`));
    expect(cancel?.key).toMatch(/^web-/u);
    expect(JSON.parse(cancel?.body ?? '{}')).toEqual({
      expectedVersion: 2,
      employmentDigest: DIGEST_E,
      cancellationDigest: DIGEST_C,
      expiresAt: EXPIRES,
      confirmed: true,
    });
  });

  it('returns focus to the control that opened a panel when it is closed', async () => {
    stub(() => ({ status: 200, body: record() }));
    const user = userEvent.setup();
    await show();
    const open = await screen.findByTestId('compensation-correct');
    await user.tab();
    open.focus();
    await user.keyboard('{Enter}');
    await screen.findByTestId('compensation-form');
    await user.click(screen.getByRole('button', { name: k.form.close }));
    expect(screen.queryByTestId('compensation-form')).toBeNull();
    expect(open).toHaveFocus();
  });

  it('shows an empty record, a failed load with a retry and writes nothing to browser storage', async () => {
    const set = vi.spyOn(Storage.prototype, 'setItem');
    stub(() => ({ status: 200, body: emptyRecord() }));
    const empty = await show();
    expect(await screen.findByTestId('compensation-none')).toHaveTextContent(k.current.empty);
    expect(screen.getByTestId('compensation-open-establish')).toBeVisible();
    await expectAccessible(empty.container);
    empty.unmount();

    stub(() => ({ status: 404, body: problem('COMPENSATION_NOT_FOUND', 404) }));
    const failed = await show();
    expect(await screen.findByTestId('compensation-load-error')).toHaveTextContent(
      resources[locale].common.errors.COMPENSATION_NOT_FOUND,
    );
    await expectAccessible(failed.container);
    expect(set).not.toHaveBeenCalled();
  });
});

import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { ACCOUNT_FIELDS, FORBIDDEN_WORDING } from './accounting';
import { AccountingMappingsPage } from './AccountingMappingsPage';
import { CODES, DIGEST, ENTITY_ID, EXPIRES, mapping, mappingPreview, problem } from './fixtures';

const BASE = `/legal-entities/${ENTITY_ID}/accounting-mappings`;
const entities = { data: [{ id: ENTITY_ID, code: 'LE1', name: 'Entity One' }] };

async function show(locale: 'en' | 'fr', path = '/admin/accounting-mappings') {
  return renderWithSession(
    <Routes>
      <Route path="/admin/accounting-mappings" element={<AccountingMappingsPage />} />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    path,
  );
}

const isList = (r: Recorded) => r.method === 'GET' && r.path.startsWith(BASE);
const isPreview = (r: Recorded) => r.method === 'POST' && r.path === `${BASE}/preview`;
const isCommit = (r: Recorded) => r.method === 'POST' && r.path === BASE;

/** Routes shared by the tests: entities, one CDF history, an empty USD history. */
function routes(extra?: (r: Recorded) => Reply | undefined) {
  return (r: Recorded): Reply => {
    const own = extra?.(r);
    if (own) return own;
    if (r.path.startsWith('/legal-entities?')) return { status: 200, body: entities };
    if (isList(r)) {
      return r.path.includes('currency=USD')
        ? { status: 200, body: { items: [] } }
        : {
            status: 200,
            body: {
              items: [
                mapping({
                  id: 'b'.repeat(8) + '-0000-4000-8000-000000000002',
                  version: 2,
                  status: 'SCHEDULED',
                  effectiveFrom: '2099-01-01',
                }),
                mapping({ version: 1, status: 'ACTIVE' }),
                mapping({
                  id: 'c'.repeat(8) + '-0000-4000-8000-000000000003',
                  version: 0,
                  status: 'SUPERSEDED',
                }),
              ],
            },
          };
    }
    return { status: 500 };
  };
}

async function fill(
  user: ReturnType<typeof userEvent.setup>,
  codes: Record<string, string> = CODES,
) {
  await user.type(await screen.findByTestId('accounting-mapping-effective'), '2026-09-01');
  for (const field of ACCOUNT_FIELDS) {
    await user.type(screen.getByTestId(`accounting-account-${field}`), codes[field] ?? '');
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('accounting mappings page (%s)', (locale) => {
  const c = resources[locale].common;
  const a = c.accounting;

  it('shows the fixed notice, a version list per currency and derived status chips', async () => {
    stub(routes());
    const { container } = await show(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(a.mappings.title);
    expect(screen.getByTestId('accounting-notice')).toHaveTextContent(a.notice);
    const lists = await screen.findAllByTestId('accounting-mapping-list');
    expect(lists.map((list) => list.getAttribute('data-currency'))).toEqual(['CDF', 'USD']);
    const rows = await within(lists[0] as HTMLElement).findAllByTestId('accounting-mapping-row');
    expect(rows.map((row) => row.getAttribute('data-status'))).toEqual([
      'SCHEDULED',
      'ACTIVE',
      'SUPERSEDED',
    ]);
    expect(rows[0]).toHaveTextContent(a.mappings.status.SCHEDULED);
    expect(rows[1]).toHaveTextContent(a.mappings.status.ACTIVE);
    expect(rows[2]).toHaveTextContent(a.mappings.status.SUPERSEDED);
    expect(rows[1]).toHaveTextContent(CODES.netSalaryPayable);
    expect(
      await within(lists[1] as HTMLElement).findByTestId('accounting-mapping-empty'),
    ).toHaveTextContent(a.mappings.list.empty);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('shows the loading and the error states of a list', async () => {
    stub(
      routes((r) =>
        isList(r) && r.path.includes('currency=USD')
          ? { status: 403, body: problem('ACCESS_DENIED', 403) }
          : undefined,
      ),
    );
    await show(locale);
    expect(await screen.findByTestId('accounting-mapping-list-failure')).toHaveTextContent(
      c.errors.ACCESS_DENIED,
    );
  });

  it('hints at the code format without blocking, trims, and reviews exactly what was typed', async () => {
    const requests = stub(
      routes((r) => (isPreview(r) ? { status: 200, body: mappingPreview() } : undefined)),
    );
    const user = userEvent.setup();
    await show(locale);
    await screen.findAllByTestId('accounting-mapping-row');
    expect(screen.getByTestId('accounting-code-hint')).toHaveTextContent(a.mappings.form.codeHint);
    expect(screen.getByTestId('accounting-mapping-preview-button')).toBeDisabled();
    const first = await screen.findByTestId('accounting-account-salaryExpense');
    await user.type(first, 'lower');
    expect(first).toHaveAttribute('aria-invalid', 'true');
    expect(first).toHaveAccessibleDescription(a.mappings.form.codeHint);
    await user.clear(first);
    await user.type(first, '  SYN-6411  ');
    expect(first).not.toHaveAttribute('aria-invalid');
    await user.type(screen.getByTestId('accounting-mapping-effective'), '2026-09-01');
    for (const field of ACCOUNT_FIELDS.slice(1)) {
      await user.type(screen.getByTestId(`accounting-account-${field}`), CODES[field]);
    }
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    await screen.findByTestId('accounting-mapping-preview');
    const sent = requests.find(isPreview);
    expect(JSON.parse(sent?.body ?? '{}')).toEqual({
      currency: 'CDF',
      effectiveFrom: '2026-09-01',
      accounts: CODES,
    });
    expect(screen.getByTestId('accounting-mapping-next-version')).toHaveTextContent('2');
  });

  it('lists the blockers of a blocked review and cannot be confirmed', async () => {
    stub(
      routes((r) =>
        isPreview(r)
          ? {
              status: 200,
              body: mappingPreview({
                status: 'BLOCKED',
                blockers: ['OVERLAP', 'LIMIT_REACHED'],
                previewDigest: null,
                expiresAt: null,
              }),
            }
          : undefined,
      ),
    );
    const user = userEvent.setup();
    await show(locale);
    await fill(user);
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    const blockers = await screen.findByTestId('accounting-mapping-blockers');
    expect(blockers).toHaveTextContent(a.mappings.blockers.OVERLAP);
    expect(blockers).toHaveTextContent(a.mappings.blockers.LIMIT_REACHED);
    expect(screen.getByTestId('accounting-mapping-preview-status')).toHaveTextContent(
      a.previewStatus.BLOCKED,
    );
    expect(screen.getByTestId('accounting-mapping-understand')).toBeDisabled();
    expect(screen.getByTestId('accounting-mapping-commit')).toBeDisabled();
    expect(screen.getByTestId('accounting-mapping-why')).toHaveTextContent(
      a.mappings.form.whyBlocked,
    );
  });

  it('needs the confirmation, sends a fresh idempotency key and exactly the preview, then reloads', async () => {
    let created = false;
    const requests = stub(
      routes((r) => {
        if (isPreview(r)) return { status: 200, body: mappingPreview() };
        if (isCommit(r)) {
          created = true;
          return {
            status: 201,
            body: {
              mappingId: 'a'.repeat(8) + '-0000-4000-8000-000000000001',
              legalEntityId: ENTITY_ID,
              currency: 'CDF',
              version: 2,
              effectiveFrom: '2026-09-01',
              createdAt: '2026-10-19T10:00:00Z',
            },
          };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    const { container } = await show(locale);
    await fill(user);
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    await screen.findByTestId('accounting-mapping-preview');
    expect(screen.getByTestId('accounting-mapping-commit')).toBeDisabled();
    expect(screen.getByTestId('accounting-mapping-why')).toHaveTextContent(
      a.mappings.form.whyConfirm,
    );
    await user.click(screen.getByTestId('accounting-mapping-understand'));
    expect(screen.getByTestId('accounting-mapping-commit')).toBeEnabled();
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
    await user.click(screen.getByTestId('accounting-mapping-commit'));
    await waitFor(() => {
      expect(screen.getByTestId('accounting-mappings-announcer')).toHaveTextContent(
        a.mappings.form.announceCreated,
      );
    });
    expect(created).toBe(true);
    const commit = requests.find(isCommit);
    expect(commit?.key).toMatch(/^web-/u);
    expect(JSON.parse(commit?.body ?? '{}')).toEqual({
      currency: 'CDF',
      effectiveFrom: '2026-09-01',
      accounts: CODES,
      expectedVersion: 2,
      previewDigest: DIGEST,
      expiresAt: EXPIRES,
      understandExportOnly: true,
    });
    expect(screen.queryByTestId('accounting-mapping-preview')).toBeNull();
    // The codes left the form and the page only shows them in the (re-read) history.
    expect(screen.getByTestId('accounting-account-salaryExpense')).toHaveValue('');
    expect(requests.filter(isList).length).toBeGreaterThan(2);
  });

  it('reuses the idempotency key when the same commit is retried', async () => {
    let attempts = 0;
    const requests = stub(
      routes((r) => {
        if (isPreview(r)) return { status: 200, body: mappingPreview() };
        if (isCommit(r)) {
          attempts += 1;
          return attempts === 1
            ? { status: 503, body: problem('INTERNAL_ERROR', 503) }
            : { status: 201, body: {} };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    await show(locale);
    await fill(user);
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    await user.click(await screen.findByTestId('accounting-mapping-understand'));
    await user.click(screen.getByTestId('accounting-mapping-commit'));
    expect(await screen.findByTestId('accounting-mapping-failure')).toHaveTextContent(
      c.errors.INTERNAL_ERROR,
    );
    await user.click(screen.getByTestId('accounting-mapping-commit'));
    await waitFor(() => {
      expect(attempts).toBe(2);
    });
    const keys = requests.filter(isCommit).map((r) => r.key);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it.each([
    ['ACCOUNTING_MAPPING_INVALID', 422, 'domain'],
    ['ACCOUNTING_MAPPING_OVERLAP', 409, 'domain'],
    ['ACCOUNTING_MAPPING_LIMIT_REACHED', 409, 'domain'],
    ['ACCOUNTING_MAPPING_PREVIEW_STALE', 409, 'stale'],
  ] as const)('renders %s as a finished translated message', async (code, status, kind) => {
    stub(
      routes((r) => {
        if (isPreview(r)) return { status: 200, body: mappingPreview() };
        if (isCommit(r)) {
          return { status, body: { ...problem(code, status), detail: 'SERVER TEXT 6411' } };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    const { container } = await show(locale);
    await fill(user);
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    await user.click(await screen.findByTestId('accounting-mapping-understand'));
    await user.click(screen.getByTestId('accounting-mapping-commit'));
    const failure = await screen.findByTestId('accounting-mapping-failure');
    expect(failure).toHaveAttribute('data-kind', kind);
    expect(failure).toHaveAttribute('data-code', code);
    expect(failure).toHaveTextContent(c.errors[code]);
    expect(container).not.toHaveTextContent('SERVER TEXT');
    if (kind === 'stale') {
      // A stale review is discarded: nothing can be confirmed on old evidence.
      expect(screen.queryByTestId('accounting-mapping-preview')).toBeNull();
      expect(screen.getByTestId('accounting-mapping-stale')).toHaveTextContent(
        a.mappings.form.staleRefresh,
      );
    }
  });

  it('preselects the legal entity of the link and keeps codes out of every URL', async () => {
    const requests = stub(
      routes((r) => {
        if (r.path.startsWith('/legal-entities?')) {
          return {
            status: 200,
            body: {
              data: [
                { id: ENTITY_ID, code: 'LE1', name: 'Entity One' },
                { id: '33333333-3333-4333-8333-333333333333', code: 'LE2', name: 'Entity Two' },
              ],
            },
          };
        }
        if (isPreview(r)) return { status: 200, body: mappingPreview() };
        return undefined;
      }),
    );
    const user = userEvent.setup();
    await show(locale, `/admin/accounting-mappings?entity=${ENTITY_ID}`);
    expect(await screen.findByTestId('accounting-entity')).toHaveValue(ENTITY_ID);
    await fill(user);
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    await screen.findByTestId('accounting-mapping-preview');
    for (const request of requests) {
      for (const code of Object.values(CODES)) expect(request.path).not.toContain(code);
    }
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });

  it('uses no wording about posting, settlement or payment outside the fixed notice', async () => {
    stub(routes((r) => (isPreview(r) ? { status: 200, body: mappingPreview() } : undefined)));
    const user = userEvent.setup();
    const { container } = await show(locale);
    await fill(user);
    await user.click(screen.getByTestId('accounting-mapping-preview-button'));
    await screen.findByTestId('accounting-mapping-preview');
    const text = container.textContent.replace(a.notice, '');
    expect(text).not.toMatch(FORBIDDEN_WORDING);
    expect(container.querySelectorAll('[data-testid="accounting-notice"]')).toHaveLength(1);
  });

  it('lets long labels wrap inside labelled, focusable scroll regions', async () => {
    stub(routes());
    const { container } = await show(locale);
    await screen.findAllByTestId('accounting-mapping-row');
    const regions = container.querySelectorAll('.table-wrapper');
    expect(regions.length).toBeGreaterThan(0);
    for (const region of regions) {
      expect(region).toHaveAttribute('role', 'region');
      expect(region).toHaveAttribute('tabindex', '0');
      expect(region).toHaveAccessibleName();
    }
  });
});

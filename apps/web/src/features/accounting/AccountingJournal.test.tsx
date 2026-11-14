import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { stub, type Recorded, type Reply } from '../payslips/stub';
import { FORBIDDEN_WORDING, JOURNAL_BLOCKERS } from './accounting';
import { AccountingJournalDetailPage } from './AccountingJournalDetailPage';
import { AccountingJournalPanel } from './AccountingJournalPanel';
import {
  CODES,
  DIGEST,
  ENTITY_ID,
  EXPIRES,
  JOURNAL_ID,
  journalPreview,
  journalView,
  PACKAGE_ID,
  problem,
} from './fixtures';

const PKG = `/legal-entities/${ENTITY_ID}/payroll-packages/${PACKAGE_ID}/accounting-journals`;
const JOURNAL = `/legal-entities/${ENTITY_ID}/accounting-journals/${JOURNAL_ID}`;
const summary = (overrides: Record<string, unknown> = {}) => ({
  id: JOURNAL_ID,
  legalEntityId: ENTITY_ID,
  packageId: PACKAGE_ID,
  version: 1,
  period: '2026-09',
  currency: 'CDF',
  lineCount: 3,
  generatedAt: '2026-10-19T10:00:00Z',
  sourceVoided: false,
  ...overrides,
});
const isHistory = (r: Recorded) => r.method === 'GET' && r.path === PKG;
const isPreview = (r: Recorded) => r.method === 'POST' && r.path === `${PKG}/preview`;
const isGenerate = (r: Recorded) => r.method === 'POST' && r.path === PKG;

/** The amounts the page must show, formatted exactly like the repository's money formatter. */
const money = (locale: string, minor: number, currency: 'CDF' | 'USD' = 'CDF') =>
  new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    currencyDisplay: 'code',
    minimumFractionDigits: currency === 'USD' ? 2 : 0,
    maximumFractionDigits: currency === 'USD' ? 2 : 0,
  }).format(currency === 'USD' ? minor / 100 : minor);

async function showPanel(
  locale: 'en' | 'fr',
  packageStatus: 'APPROVED' | 'VOIDED' = 'APPROVED',
  onChanged: (message: string) => void = vi.fn(),
) {
  return renderWithSession(
    <Routes>
      <Route
        path="/"
        element={
          <AccountingJournalPanel
            legalEntityId={ENTITY_ID}
            packageId={PACKAGE_ID}
            packageStatus={packageStatus}
            revision={0}
            onChanged={onChanged}
          />
        }
      />
      <Route
        path="/admin/accounting-journals/:legalEntityId/:journalId"
        element={<AccountingJournalDetailPage />}
      />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
  );
}

async function showDetail(locale: 'en' | 'fr') {
  return renderWithSession(
    <Routes>
      <Route
        path="/admin/accounting-journals/:legalEntityId/:journalId"
        element={<AccountingJournalDetailPage />}
      />
    </Routes>,
    sessionWithRoles(['tenant-admin']),
    locale,
    `/admin/accounting-journals/${ENTITY_ID}/${JOURNAL_ID}`,
  );
}

function routes(extra?: (r: Recorded) => Reply | undefined) {
  return (r: Recorded): Reply => {
    const own = extra?.(r);
    if (own) return own;
    if (isHistory(r)) return { status: 200, body: { items: [] } };
    if (r.path === JOURNAL) return { status: 200, body: journalView() };
    return { status: 500 };
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('accounting journal panel (%s)', (locale) => {
  const c = resources[locale].common;
  const a = c.accounting;

  it('shows the notice, an empty history and a review button', async () => {
    stub(routes());
    const { container } = await showPanel(locale);
    expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent(a.journal.title);
    expect(screen.getByTestId('accounting-notice')).toHaveTextContent(a.notice);
    expect(await screen.findByTestId('accounting-journals-empty')).toHaveTextContent(
      a.journal.history.empty,
    );
    expect(screen.getByTestId('accounting-journal-preview-button')).toBeEnabled();
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('lists the history and marks a journal whose source package was voided', async () => {
    stub(
      routes((r) =>
        isHistory(r)
          ? {
              status: 200,
              body: {
                items: [
                  summary({
                    id: '77777777-7777-4777-8777-777777777777',
                    version: 2,
                    sourceVoided: true,
                  }),
                  summary(),
                ],
              },
            }
          : undefined,
      ),
    );
    const { container } = await showPanel(locale, 'VOIDED');
    const items = await screen.findAllByTestId('accounting-journal-item');
    expect(items.map((item) => item.getAttribute('data-source-voided'))).toEqual(['true', 'false']);
    expect(
      within(items[0] as HTMLElement).getByTestId('accounting-source-voided'),
    ).toHaveTextContent(a.journal.sourceVoided);
    expect(within(items[1] as HTMLElement).queryByTestId('accounting-source-voided')).toBeNull();
    expect(items[1]).toHaveTextContent(a.journal.history.item.replace('{{version}}', '1'));
    // A voided package can be read but not reviewed again.
    expect(screen.queryByTestId('accounting-journal-preview-button')).toBeNull();
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('previews the exact lines, the totals, the line count and the mapping version', async () => {
    const requests = stub(
      routes((r) => (isPreview(r) ? { status: 200, body: journalPreview() } : undefined)),
    );
    const user = userEvent.setup();
    const { container } = await showPanel(locale);
    await user.click(await screen.findByTestId('accounting-journal-preview-button'));
    const preview = await screen.findByTestId('accounting-journal-preview');
    expect(screen.getByTestId('accounting-journal-preview-status')).toHaveTextContent(
      a.previewStatus.READY,
    );
    expect(screen.getByTestId('accounting-journal-line-count')).toHaveTextContent('3');
    expect(screen.getByTestId('accounting-journal-source-entries')).toHaveTextContent('5');
    expect(screen.getByTestId('accounting-journal-mapping')).toHaveTextContent(
      a.journal.mappingValue.replace('{{version}}', '2').split('{{date}}')[0] as string,
    );
    expect(screen.getByTestId('accounting-journal-debits').textContent).toBe(
      money(locale, 1000000),
    );
    expect(screen.getByTestId('accounting-journal-credits').textContent).toBe(
      money(locale, 1000000),
    );
    const rows = within(screen.getByTestId('accounting-journal-preview-lines')).getAllByTestId(
      'accounting-line',
    );
    expect(rows.map((row) => row.textContent)).toEqual([
      `1SYN-4211${a.direction.CREDIT}${money(locale, 900000)}`,
      `2SYN-6411${a.direction.DEBIT}${money(locale, 1000000)}`,
      `3SYN.4311${a.direction.CREDIT}${money(locale, 100000)}`,
    ]);
    // The preview request carries no amount, no code and no body at all.
    const sent = requests.find(isPreview);
    expect(sent?.path).not.toContain('SYN');
    expect(sent?.body).toBe('');
    expect(preview).toBeVisible();
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('formats a USD journal with two decimals', async () => {
    stub(
      routes((r) =>
        isPreview(r)
          ? {
              status: 200,
              body: journalPreview({
                currency: 'USD',
                totalDebitsMinor: 1234567,
                totalCreditsMinor: 1234567,
              }),
            }
          : undefined,
      ),
    );
    const user = userEvent.setup();
    await showPanel(locale);
    await user.click(await screen.findByTestId('accounting-journal-preview-button'));
    expect((await screen.findByTestId('accounting-journal-debits')).textContent).toBe(
      money(locale, 1234567, 'USD'),
    );
  });

  it.each(JOURNAL_BLOCKERS)('shows the %s blocker and cannot generate', async (blocker) => {
    stub(
      routes((r) =>
        isPreview(r)
          ? {
              status: 200,
              body: journalPreview({
                status: 'BLOCKED',
                blockers: [blocker],
                mapping: blocker === 'MAPPING_NOT_FOUND' ? null : journalPreview().mapping,
                lines: [],
                lineCount: 0,
                totalDebitsMinor: 0,
                totalCreditsMinor: 0,
                previewDigest: null,
                expiresAt: null,
              }),
            }
          : undefined,
      ),
    );
    const user = userEvent.setup();
    await showPanel(locale);
    await user.click(await screen.findByTestId('accounting-journal-preview-button'));
    const blockers = await screen.findByTestId('accounting-journal-blockers');
    expect(blockers).toHaveTextContent(a.journal.blockers[blocker]);
    if (blocker === 'MAPPING_NOT_FOUND') {
      expect(screen.getByTestId('accounting-mappings-link')).toHaveAttribute(
        'href',
        `/admin/accounting-mappings?entity=${ENTITY_ID}`,
      );
      expect(screen.getByTestId('accounting-journal-mapping')).toHaveTextContent(
        a.journal.noMapping,
      );
    } else {
      expect(screen.queryByTestId('accounting-mappings-link')).toBeNull();
    }
    expect(screen.getByTestId('accounting-journal-understand')).toBeDisabled();
    expect(screen.getByTestId('accounting-journal-generate')).toBeDisabled();
    expect(screen.getByTestId('accounting-journal-why')).toHaveTextContent(a.journal.whyBlocked);
  });

  it('needs the confirmation, sends exactly the preview with a fresh key and opens the generated journal', async () => {
    const onChanged = vi.fn();
    const requests = stub(
      routes((r) => {
        if (isPreview(r)) return { status: 200, body: journalPreview() };
        if (isGenerate(r)) {
          return {
            status: 201,
            body: {
              journalId: JOURNAL_ID,
              legalEntityId: ENTITY_ID,
              packageId: PACKAGE_ID,
              currency: 'CDF',
              version: 1,
              lineCount: 3,
              generatedAt: '2026-10-19T10:00:00Z',
            },
          };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    await showPanel(locale, 'APPROVED', onChanged);
    await user.click(await screen.findByTestId('accounting-journal-preview-button'));
    await screen.findByTestId('accounting-journal-preview');
    expect(screen.getByTestId('accounting-journal-generate')).toBeDisabled();
    expect(screen.getByTestId('accounting-journal-why')).toHaveTextContent(a.journal.whyConfirm);
    await user.click(screen.getByTestId('accounting-journal-understand'));
    expect(screen.getByTestId('accounting-journal-generate')).toBeEnabled();
    await user.click(screen.getByTestId('accounting-journal-generate'));
    expect(await screen.findByTestId('accounting-journal-detail')).toBeVisible();
    expect(onChanged).toHaveBeenCalledWith(a.journal.announceGenerated);
    const generate = requests.find(isGenerate);
    expect(generate?.key).toMatch(/^web-/u);
    expect(JSON.parse(generate?.body ?? '{}')).toEqual({
      expectedPackageVersion: 1,
      expectedMappingVersion: 2,
      previewDigest: DIGEST,
      expiresAt: EXPIRES,
      understandExportOnly: true,
    });
    expect(await screen.findByTestId('accounting-detail-line-count')).toHaveTextContent('3');
  });

  it.each([
    ['ACCOUNTING_JOURNAL_PREVIEW_STALE', 409, 'stale'],
    ['ACCOUNTING_JOURNAL_ALREADY_EXISTS', 409, 'stale'],
    ['ACCOUNTING_JOURNAL_SOURCE_VOIDED', 409, 'stale'],
    ['ACCOUNTING_JOURNAL_SOURCE_NOT_APPROVED', 409, 'stale'],
    ['ACCOUNTING_MAPPING_NOT_FOUND', 409, 'domain'],
    ['ACCOUNTING_JOURNAL_UNBALANCED', 409, 'domain'],
    ['ACCOUNTING_JOURNAL_TOO_LARGE', 422, 'domain'],
  ] as const)('renders %s as a finished translated message', async (code, status, kind) => {
    stub(
      routes((r) => {
        if (isPreview(r)) return { status: 200, body: journalPreview() };
        if (isGenerate(r)) {
          return { status, body: { ...problem(code, status), detail: 'SERVER TEXT 1000000' } };
        }
        return undefined;
      }),
    );
    const user = userEvent.setup();
    const { container } = await showPanel(locale);
    await user.click(await screen.findByTestId('accounting-journal-preview-button'));
    await user.click(await screen.findByTestId('accounting-journal-understand'));
    await user.click(screen.getByTestId('accounting-journal-generate'));
    const failure = await screen.findByTestId('accounting-journal-failure');
    expect(failure).toHaveAttribute('data-kind', kind);
    expect(failure).toHaveTextContent(c.errors[code]);
    expect(container).not.toHaveTextContent('SERVER TEXT');
    if (kind === 'stale') {
      expect(screen.queryByTestId('accounting-journal-preview')).toBeNull();
      expect(screen.getByTestId('accounting-journal-stale')).toHaveTextContent(
        a.journal.staleRefresh,
      );
    }
  });

  it('uses no wording about posting, settlement or payment outside the fixed notice', async () => {
    stub(
      routes((r) => {
        if (isPreview(r)) return { status: 200, body: journalPreview() };
        if (isHistory(r))
          return { status: 200, body: { items: [summary({ sourceVoided: true })] } };
        return undefined;
      }),
    );
    const user = userEvent.setup();
    const { container } = await showPanel(locale);
    await user.click(await screen.findByTestId('accounting-journal-preview-button'));
    await screen.findByTestId('accounting-journal-preview');
    const text = container.textContent.replace(a.notice, '');
    expect(text).not.toMatch(FORBIDDEN_WORDING);
    expect(
      Array.from(container.querySelectorAll('button')).map((button) => button.textContent),
    ).toEqual([a.journal.preview, a.journal.generate]);
  });
});

describe.each(['en', 'fr'] as const)('accounting journal detail (%s)', (locale) => {
  const c = resources[locale].common;
  const a = c.accounting;

  /** Serves the two files as bytes with the server's headers; everything else as JSON. */
  function withFiles(handler: (r: Recorded) => Reply, disposition: boolean) {
    const requests = stub(handler);
    const json = globalThis.fetch;
    const csv = '﻿journal_id,line_number\r\nx,1\r\n';
    const calls: Request[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn(async (incoming: Request) => {
        const path = new URL(incoming.url).pathname;
        if (path.endsWith('/csv') || path.endsWith('/manifest')) {
          calls.push(incoming);
          const isCsv = path.endsWith('/csv');
          return new Response(isCsv ? csv : '{"schemaVersion":1}', {
            status: 200,
            headers: {
              'Content-Type': isCsv ? 'text/csv' : 'application/json',
              'Cache-Control': 'private, no-store',
              ...(disposition
                ? {
                    'Content-Disposition': `attachment; filename="accounting-journal-LE1-2026-09-CDF-v1.${isCsv ? 'csv' : 'manifest.json'}"`,
                  }
                : {}),
            },
          });
        }
        return json(incoming);
      }),
    );
    return { requests, calls, csv };
  }

  async function downloadBoth(disposition: boolean) {
    const files = withFiles(routes(), disposition);
    const blobs: Blob[] = [];
    Object.assign(URL, {
      createObjectURL: (blob: Blob) => {
        blobs.push(blob);
        return 'blob:synthetic';
      },
      revokeObjectURL: vi.fn(),
    });
    const names: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      names.push(this.download);
    });
    const user = userEvent.setup();
    await showDetail(locale);
    await user.click(await screen.findByTestId('accounting-download-csv'));
    await waitFor(() => {
      expect(names).toHaveLength(1);
    });
    await user.click(screen.getByTestId('accounting-download-manifest'));
    await waitFor(() => {
      expect(names).toHaveLength(2);
    });
    return { ...files, blobs, names };
  }

  it('shows the immutable header, the exact lines, the notice and the link back to history', async () => {
    stub(routes());
    const { container } = await showDetail(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(a.detail.title);
    expect(screen.getByTestId('accounting-notice')).toHaveTextContent(a.notice);
    expect(await screen.findByTestId('accounting-detail-line-count')).toHaveTextContent('3');
    expect(screen.getByTestId('accounting-detail-debits').textContent).toBe(money(locale, 1000000));
    expect(screen.getByTestId('accounting-detail-credits').textContent).toBe(
      money(locale, 1000000),
    );
    expect(screen.getByTestId('accounting-detail-sha')).toHaveTextContent('e'.repeat(64));
    expect(screen.getAllByTestId('accounting-line')).toHaveLength(3);
    expect(screen.queryByTestId('accounting-source-voided')).toBeNull();
    expect(screen.getByTestId('accounting-history-link')).toHaveAttribute(
      'href',
      `/admin/payroll-packages/${ENTITY_ID}/${PACKAGE_ID}`,
    );
    const text = container.textContent.replace(a.notice, '');
    expect(text).not.toMatch(FORBIDDEN_WORDING);
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('marks a journal whose source package was voided and keeps it downloadable', async () => {
    stub(
      routes((r) =>
        r.path === JOURNAL ? { status: 200, body: journalView({ sourceVoided: true }) } : undefined,
      ),
    );
    const { container } = await showDetail(locale);
    expect(await screen.findByTestId('accounting-source-voided')).toHaveTextContent(
      a.journal.sourceVoided,
    );
    expect(screen.getByTestId('accounting-voided-note')).toHaveTextContent(
      a.journal.sourceVoidedHelp,
    );
    expect(screen.getByTestId('accounting-download-csv')).toBeEnabled();
    expect(screen.getByTestId('accounting-download-manifest')).toBeEnabled();
    await waitFor(async () => {
      const result = await axe.run(container);
      expect(result.violations.map((v) => v.id)).toEqual([]);
    });
  });

  it('downloads both files through the authenticated client, never cached, under the generated name', async () => {
    const { calls, blobs, names, csv } = await downloadBoth(true);
    expect(names).toEqual([
      'accounting-journal-LE1-2026-09-CDF-v1.csv',
      'accounting-journal-LE1-2026-09-CDF-v1.manifest.json',
    ]);
    expect(
      calls.map((request) => new URL(request.url).pathname.replace(/^.*\/api\/v1/u, '')),
    ).toEqual([`${JOURNAL}/csv`, `${JOURNAL}/manifest`]);
    for (const request of calls) {
      expect(request.cache).toBe('no-store');
      expect(request.url).not.toMatch(/SYN|1000000|\?/u);
    }
    // The bytes, not decoded text: the UTF-8 BOM and the CRLF line ends survive the blob.
    expect(
      Array.from(new Uint8Array((await blobs[0]?.arrayBuffer()) ?? new ArrayBuffer(0))),
    ).toEqual(Array.from(new TextEncoder().encode(csv)));
    expect(await blobs[1]?.text()).toBe('{"schemaVersion":1}');
    expect(screen.getByTestId('accounting-download-done')).toHaveTextContent(a.detail.downloaded);
  });

  it('falls back to the identical generated name when the header is not readable', async () => {
    const { names } = await downloadBoth(false);
    expect(names).toEqual([
      'accounting-journal-LE1-2026-09-CDF-v1.csv',
      'accounting-journal-LE1-2026-09-CDF-v1.manifest.json',
    ]);
  });

  it('maps a refused download and a missing journal to finished messages', async () => {
    stub(
      routes((r) =>
        r.path.endsWith('/csv')
          ? { status: 404, body: problem('ACCOUNTING_JOURNAL_NOT_FOUND', 404) }
          : undefined,
      ),
    );
    const user = userEvent.setup();
    await showDetail(locale);
    await user.click(await screen.findByTestId('accounting-download-csv'));
    expect(await screen.findByTestId('accounting-download-failure')).toHaveTextContent(
      c.errors.ACCOUNTING_JOURNAL_NOT_FOUND,
    );
  });

  it('shows a translated alert and no summary for a journal that does not exist', async () => {
    stub(() => ({ status: 404, body: problem('ACCOUNTING_JOURNAL_NOT_FOUND', 404) }));
    await showDetail(locale);
    const alert = await screen.findByTestId('accounting-detail-failure');
    expect(alert).toHaveAttribute('data-kind', 'notFound');
    expect(alert).toHaveTextContent(c.errors.ACCOUNTING_JOURNAL_NOT_FOUND);
    expect(screen.queryByTestId('accounting-journal-summary')).toBeNull();
    expect(screen.getByTestId('accounting-notice')).toHaveTextContent(a.notice);
  });

  it('never puts an amount or an account code into storage or a URL', async () => {
    const requests = stub(routes());
    await showDetail(locale);
    await screen.findByTestId('accounting-detail-line-count');
    for (const request of requests) {
      for (const code of Object.values(CODES)) expect(request.path).not.toContain(code);
      expect(request.path).not.toMatch(/(?:^|\D)(?:1000000|900000|100000)(?:\D|$)/u);
    }
    expect(window.localStorage.length + window.sessionStorage.length).toBe(0);
  });
});

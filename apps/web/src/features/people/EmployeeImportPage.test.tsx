import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../layout/AppShell';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { EmployeeImportPage, MAX_FILE_BYTES } from './EmployeeImportPage';

const IMPORT_ID = '33333333-3333-4333-8333-333333333333';
const DIGEST = 'a'.repeat(64);

const summary = (overrides: Record<string, unknown> = {}) => ({
  id: IMPORT_ID,
  status: 'VALIDATED',
  createdAt: '2026-10-01T08:00:00Z',
  expiresAt: '2026-10-01T10:00:00Z',
  totalRows: 3,
  validRows: 2,
  invalidRows: 1,
  createdCount: null,
  notImportedCount: null,
  delimiter: 'SEMICOLON',
  headerLanguage: 'fr',
  previewDigest: DIGEST,
  requiresAcknowledgement: true,
  errorCounts: [{ code: 'ROW_EMPLOYEE_NUMBER_EXISTS', count: 1 }],
  ...overrides,
});

const validRow = (n: number) => ({
  rowNumber: n,
  status: 'VALID',
  errors: [],
  values: {
    employeeNumber: `E-${String(n).padStart(3, '0')}`,
    givenNames: 'Élodie',
    familyName: 'Mukendi',
    startDate: '2026-03-01',
    legalEntityCode: 'KIN',
    siteCode: 'LSH',
    departmentCode: 'FIN',
    costCenterCode: null,
    teamCode: null,
  },
});

const invalidRow = (n: number) => ({
  rowNumber: n,
  status: 'INVALID',
  errors: [{ column: 'employee_number', code: 'ROW_EMPLOYEE_NUMBER_EXISTS' }],
  values: null,
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> } | 'network';
type Route = (request: Request, url: URL) => Reply | undefined;

function stubApi(route: Route = () => undefined) {
  const requests: Request[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const url = new URL(request.url);
      const path = url.pathname;
      const reply =
        route(request, url) ??
        (path.endsWith('/employee-imports/template')
          ? { status: 200, body: 'Matricule;Prénoms\r\n', headers: { 'Content-Type': 'text/csv' } }
          : path.endsWith('/employee-imports') && request.method === 'POST'
            ? { status: 201, body: summary() }
            : path.endsWith('/rows')
              ? { status: 200, body: { items: [validRow(1), invalidRow(2)], nextCursor: null } }
              : path.endsWith('/commit')
                ? {
                    status: 200,
                    body: summary({ status: 'COMMITTED', createdCount: 2, notImportedCount: 1 }),
                  }
                : path.endsWith('/discard')
                  ? { status: 200, body: summary({ status: 'DISCARDED' }) }
                  : path.endsWith(`/employee-imports/${IMPORT_ID}`)
                    ? { status: 200, body: summary() }
                    : { status: 404 });
      if (reply === 'network') return Promise.reject(new TypeError('Failed to fetch'));
      const body =
        reply.body === undefined
          ? null
          : typeof reply.body === 'string'
            ? reply.body
            : JSON.stringify(reply.body);
      return Promise.resolve(
        new Response(body, {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
            'Cache-Control': 'private, no-store',
            ...(reply.headers ?? {}),
          },
        }),
      );
    }),
  );
  return requests;
}

const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params,
  correlationId: 'corr-import-1234',
});

async function renderPage(locale: 'fr' | 'en', roles = ['tenant-admin'] as const) {
  return renderWithSession(
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="employeeImport.unauthorized"
      signInKey="employeeImport.signInRequired"
    >
      <EmployeeImportPage />
    </RequireRole>,
    sessionWithRoles([...roles]),
    locale,
  );
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

const CSV = 'Matricule;Prénoms;Nom de famille\r\nE-001;Élodie;Mukendi\r\n';
const csvFile = (content: string | Uint8Array<ArrayBuffer> = CSV, name = 'employes.csv') =>
  new File([content], name, { type: 'text/csv' });

async function uploadFile(
  user: ReturnType<typeof userEvent.setup>,
  locale: 'fr' | 'en',
  file = csvFile(),
) {
  const u = resources[locale].common.employeeImport.upload;
  await user.upload(screen.getByLabelText(u.file), file);
  await user.click(screen.getByRole('button', { name: u.submit }));
}

let setItem: { mock: { calls: unknown[][] } };
beforeEach(() => {
  setItem = vi.spyOn(Storage.prototype, 'setItem');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['fr', 'en'] as const)('employee import (%s)', (locale) => {
  const c = resources[locale].common;
  const e = c.employeeImport;
  const plural = (value: object, key: string, count: number) => {
    const text = (value as Record<string, unknown>)[`${key}_${count === 1 ? 'one' : 'other'}`];
    return (typeof text === 'string' ? text : '').replace('{{count}}', String(count));
  };

  it('downloads the template in either language with a no-store request', async () => {
    const requests = stubApi();
    const createObjectURL = vi.fn(() => 'blob:template');
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const { container } = await renderPage(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(e.title);
    const format = screen.getByTestId('format-table');
    expect(
      within(format)
        .getAllByRole('rowheader')
        .map((h) => h.textContent),
    ).toEqual(Object.values(e.columns));
    expect(within(format).getAllByText(e.format.yes)).toHaveLength(6);
    expect(within(format).getAllByText(e.format.no)).toHaveLength(3);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: e.template.en }));
    await waitFor(() => {
      expect(click).toHaveBeenCalledTimes(1);
    });
    const template = requests.find((q) => q.url.includes('/template'));
    expect(new URL(template?.url ?? '').searchParams.get('lang')).toBe('en');
    expect(template?.cache).toBe('no-store');
    expect(createObjectURL).toHaveBeenCalledTimes(1);
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:template');
    await expectAccessible(container);
  });

  it('checks a file, sends its exact bytes and previews valid and invalid rows', async () => {
    const requests = stubApi();
    const before = window.location.href;
    const { container } = await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    const preview = await screen.findByTestId('import-preview');
    await waitFor(() => expect(within(preview).getByRole('heading', { level: 2 })).toHaveFocus());
    expect(screen.getByTestId('preview-counts')).toHaveTextContent(
      e.preview.counts
        .replace('{{total}}', '3')
        .replace('{{valid}}', '2')
        .replace('{{invalid}}', '1'),
    );
    expect(screen.getByTestId('error-counts')).toHaveTextContent(
      `${e.rowErrors.ROW_EMPLOYEE_NUMBER_EXISTS} (1)`,
    );
    const table = await screen.findByTestId('rows-table');
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual([
      e.table.row,
      e.table.status,
      e.table.employeeNumber,
      e.table.name,
      e.table.startDate,
      e.table.units,
      e.table.problems,
    ]);
    const rows = screen.getAllByTestId('import-row');
    expect(rows[0]).toHaveTextContent('E-001');
    expect(rows[0]).toHaveTextContent('Élodie Mukendi');
    expect(rows[0]).toHaveTextContent('KIN › LSH › FIN');
    expect(rows[0]).toHaveTextContent(e.table.valid);
    expect(rows[1]).toHaveTextContent(e.table.invalid);
    expect(rows[1]).toHaveTextContent(
      e.rowError
        .replace('{{column}}', e.columns.employee_number)
        .replace('{{message}}', e.rowErrors.ROW_EMPLOYEE_NUMBER_EXISTS),
    );

    const upload = requests.find((q) => q.method === 'POST' && q.url.endsWith('/employee-imports'));
    expect(upload?.headers.get('Content-Type')).toBe('text/csv');
    expect(upload?.headers.get('Idempotency-Key')).toMatch(/^web-/u);
    const sent = await upload?.clone().arrayBuffer();
    expect(Array.from(new Uint8Array(sent ?? new ArrayBuffer(0)))).toEqual(
      Array.from(new TextEncoder().encode(CSV)),
    );
    for (const request of requests) {
      expect(request.cache).toBe('no-store');
      expect(request.url).not.toMatch(/Mukendi|Élodie|E-001/u);
    }
    expect(window.location.href).toBe(before);
    expect(setItem.mock.calls.map((call) => call[0])).toEqual(
      setItem.mock.calls.length === 0 ? [] : ['divalhr.locale'],
    );
    expect(JSON.stringify(setItem.mock.calls)).not.toMatch(/Mukendi|E-001|3333/u);
    await expectAccessible(container);
  });

  it('filters the rows and loads more with focus on the first new row', async () => {
    const requests = stubApi((_request, url) => {
      if (!url.pathname.endsWith('/rows')) return undefined;
      if (url.searchParams.get('status') === 'invalid') {
        return { status: 200, body: { items: [invalidRow(2)], nextCursor: null } };
      }
      return url.searchParams.has('cursor')
        ? { status: 200, body: { items: [validRow(3)], nextCursor: null } }
        : { status: 200, body: { items: [validRow(1)], nextCursor: 'opaque-cursor' } };
    });
    await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    await screen.findByTestId('rows-table');
    await user.click(screen.getByRole('button', { name: e.preview.loadMore }));
    await waitFor(() => {
      expect(screen.getAllByTestId('import-row')).toHaveLength(2);
    });
    await waitFor(() => expect(screen.getAllByTestId('import-row')[1]).toHaveFocus());
    expect(screen.getByTestId('announcer')).toHaveTextContent(plural(e.announce, 'rows', 1));
    await user.click(screen.getByRole('radio', { name: e.preview.invalid }));
    await waitFor(() => {
      expect(screen.getAllByTestId('import-row')).toHaveLength(1);
    });
    expect(screen.getAllByTestId('import-row')[0]).toHaveTextContent(e.table.invalid);
    const last = new URL(requests.filter((q) => q.url.includes('/rows')).at(-1)?.url ?? '');
    expect(last.searchParams.get('status')).toBe('invalid');
    expect(last.searchParams.has('cursor')).toBe(false);
  });

  it('requires the acknowledgement and a confirmation, then commits once', async () => {
    let committedImport = false;
    const requests = stubApi((_request, url) => {
      if (url.pathname.endsWith('/commit')) committedImport = true;
      if (committedImport && url.pathname.endsWith('/rows')) {
        expect(url.searchParams.get('status')).toBe('invalid');
        return {
          status: 200,
          body: { items: [{ ...invalidRow(2), status: 'NOT_IMPORTED' }], nextCursor: null },
        };
      }
      return undefined;
    });
    const { container } = await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    await screen.findByTestId('rows-table');
    const submit = screen.getByRole('button', { name: plural(e.commit, 'submit', 2) });
    await user.click(submit);
    expect(screen.getByRole('alert')).toHaveTextContent(e.commit.acknowledgeRequired);
    expect(screen.queryByTestId('commit-confirm')).toBeNull();
    await user.click(screen.getByRole('checkbox', { name: plural(e.commit, 'acknowledge', 1) }));
    await user.click(submit);
    const confirm = await screen.findByTestId('commit-confirm');
    await waitFor(() =>
      expect(within(confirm).getByText(plural(e.commit, 'confirm', 2))).toHaveFocus(),
    );
    await expectAccessible(container);
    expect(requests.some((q) => q.url.endsWith('/commit'))).toBe(false);
    await user.click(screen.getByRole('button', { name: e.commit.confirmYes }));
    const result = await screen.findByTestId('import-result');
    await waitFor(() => expect(within(result).getByRole('heading', { level: 2 })).toHaveFocus());
    expect(result).toHaveTextContent(e.result.committedTitle);
    expect(screen.getByTestId('result-created')).toHaveTextContent(plural(e.result, 'created', 2));
    expect(screen.getByTestId('result-not-imported')).toHaveTextContent(
      plural(e.result, 'notImported', 1),
    );
    const commits = requests.filter((q) => q.url.endsWith('/commit'));
    expect(commits).toHaveLength(1);
    expect(await commits[0]?.clone().json()).toEqual({
      previewDigest: DIGEST,
      validRows: 2,
      acknowledgeInvalidRows: true,
    });
    expect(commits[0]?.headers.get('Idempotency-Key')).toMatch(/^web-/u);
    expect(screen.queryByTestId('rows-table')).toBeNull();
    const notImported = await screen.findByTestId('not-imported-table');
    expect(within(notImported).getByText(e.result.notImportedCaption)).toBeInTheDocument();
    expect(within(notImported).getByTestId('import-row')).toHaveTextContent(e.table.notImported);
    expect(within(notImported).getByTestId('import-row')).toHaveTextContent(
      e.rowErrors.ROW_EMPLOYEE_NUMBER_EXISTS,
    );
    await expectAccessible(container);
  });

  it('retries a lost commit with the same idempotency key', async () => {
    let lost = true;
    const requests = stubApi((_request, url) => {
      if (url.pathname.endsWith('/commit') && lost) {
        lost = false;
        return 'network';
      }
      return undefined;
    });
    await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale, csvFile());
    await screen.findByTestId('rows-table');
    await user.click(screen.getByRole('checkbox', { name: plural(e.commit, 'acknowledge', 1) }));
    await user.click(screen.getByRole('button', { name: plural(e.commit, 'submit', 2) }));
    await user.click(await screen.findByRole('button', { name: e.commit.confirmYes }));
    const error = await screen.findByTestId('commit-error');
    expect(error).toHaveTextContent(c.errors.network);
    await waitFor(() => expect(error).toHaveFocus());
    await user.click(screen.getByRole('button', { name: e.commit.retry }));
    await user.click(await screen.findByRole('button', { name: e.commit.confirmYes }));
    await screen.findByTestId('import-result');
    const keys = requests
      .filter((q) => q.url.endsWith('/commit'))
      .map((q) => q.headers.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('explains a file-level problem with its reason and column, and focuses it', async () => {
    let rejected = true;
    const requests = stubApi((request, url) => {
      if (request.method === 'POST' && url.pathname.endsWith('/employee-imports') && rejected) {
        rejected = false;
        return {
          status: 400,
          body: problem('IMPORT_FILE_INVALID', 400, { reason: 'COLUMN_UNKNOWN', column: 3 }),
        };
      }
      return undefined;
    });
    const { container } = await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    const error = await screen.findByTestId('upload-error');
    expect(error).toHaveTextContent(c.errors.IMPORT_FILE_INVALID);
    expect(error).toHaveTextContent(e.fileReasons.COLUMN_UNKNOWN);
    expect(error).toHaveTextContent(e.upload.column.replace('{{column}}', '3'));
    expect(error).toHaveTextContent('corr-import-1234');
    await waitFor(() => expect(error).toHaveFocus());
    await expectAccessible(container);
    // A corrected file is a new attempt with a new key.
    await uploadFile(user, locale, csvFile(`${CSV}E-002;Ana;Kabila\r\n`));
    await screen.findByTestId('import-preview');
    const keys = requests
      .filter((q) => q.method === 'POST' && q.url.endsWith('/employee-imports'))
      .map((q) => q.headers.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it('names a missing column by its label', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/employee-imports')
        ? {
            status: 400,
            body: problem('IMPORT_FILE_INVALID', 400, {
              reason: 'COLUMN_MISSING',
              column: 'start_date',
            }),
          }
        : undefined,
    );
    await renderPage(locale);
    await uploadFile(userEvent.setup(), locale);
    const error = await screen.findByTestId('upload-error');
    expect(error).toHaveTextContent(e.fileReasons.COLUMN_MISSING);
    expect(error).toHaveTextContent(e.upload.column.replace('{{column}}', e.columns.start_date));
  });

  it('refuses a missing or oversized file before sending anything', async () => {
    const requests = stubApi();
    await renderPage(locale);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: e.upload.submit }));
    expect(screen.getByRole('alert')).toHaveTextContent(e.upload.required);
    const field = screen.getByLabelText(e.upload.file);
    expect(field).toHaveFocus();
    expect(field).toHaveAccessibleDescription(`${e.upload.help} ${e.upload.required}`);
    const big = csvFile(new Uint8Array(MAX_FILE_BYTES + 1));
    await uploadFile(user, locale, big);
    expect(screen.getByRole('alert')).toHaveTextContent(e.upload.tooLarge);
    expect(requests).toHaveLength(0);
  });

  it('shows a rate limit with its delay and keeps the key for the retry', async () => {
    let limited = true;
    const requests = stubApi((request, url) => {
      if (request.method === 'POST' && url.pathname.endsWith('/employee-imports') && limited) {
        limited = false;
        return {
          status: 429,
          body: problem('RATE_LIMITED', 429),
          headers: { 'Retry-After': '42' },
        };
      }
      return undefined;
    });
    await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    const error = await screen.findByTestId('upload-error');
    expect(error).toHaveTextContent(c.errors.RATE_LIMITED);
    expect(error).toHaveTextContent(plural(e, 'retryAfter', 42));
    await user.click(screen.getByRole('button', { name: e.upload.submit }));
    await screen.findByTestId('import-preview');
    const keys = requests
      .filter((q) => q.method === 'POST' && q.url.endsWith('/employee-imports'))
      .map((q) => q.headers.get('Idempotency-Key'));
    expect(keys).toEqual([keys[0], keys[0]]);
  });

  it('stops after a stale commit and offers a new import', async () => {
    stubApi((_request, url) =>
      url.pathname.endsWith('/commit')
        ? { status: 409, body: problem('IMPORT_STALE', 409) }
        : undefined,
    );
    await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    await screen.findByTestId('rows-table');
    await user.click(screen.getByRole('checkbox', { name: plural(e.commit, 'acknowledge', 1) }));
    await user.click(screen.getByRole('button', { name: plural(e.commit, 'submit', 2) }));
    await user.click(await screen.findByRole('button', { name: e.commit.confirmYes }));
    expect(await screen.findByTestId('commit-error')).toHaveTextContent(c.errors.IMPORT_STALE);
    expect(screen.getByRole('button', { name: plural(e.commit, 'submit', 2) })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: e.result.again }));
    expect(screen.queryByTestId('import-preview')).toBeNull();
    await waitFor(() => expect(screen.getByLabelText(e.upload.file)).toHaveFocus());
  });

  it('cancels an import', async () => {
    const requests = stubApi();
    await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    await screen.findByTestId('rows-table');
    await user.click(screen.getByRole('button', { name: e.commit.discard }));
    const result = await screen.findByTestId('import-result');
    expect(result).toHaveTextContent(e.result.discardedTitle);
    expect(result).toHaveTextContent(e.result.discarded);
    expect(requests.filter((q) => q.url.endsWith('/discard'))).toHaveLength(1);
    expect(requests.some((q) => q.url.endsWith('/commit'))).toBe(false);
  });

  it('shows the chosen file name and size before checking it', async () => {
    stubApi();
    await renderPage(locale);
    const user = userEvent.setup();
    await user.upload(screen.getByLabelText(e.upload.file), csvFile('x'.repeat(2048)));
    const selected = screen.getByTestId('selected-file');
    expect(selected).toHaveTextContent('employes.csv');
    expect(selected).toHaveTextContent(/2\s?(kB|ko|KB)/u);
  });

  it('explains that an expired check must be uploaded again', async () => {
    stubApi((_request, url) =>
      url.pathname.endsWith('/commit')
        ? { status: 409, body: problem('IMPORT_NOT_COMMITTABLE', 409, { status: 'EXPIRED' }) }
        : undefined,
    );
    await renderPage(locale);
    const user = userEvent.setup();
    await uploadFile(user, locale);
    await screen.findByTestId('rows-table');
    await user.click(screen.getByRole('checkbox', { name: plural(e.commit, 'acknowledge', 1) }));
    await user.click(screen.getByRole('button', { name: plural(e.commit, 'submit', 2) }));
    await user.click(await screen.findByRole('button', { name: e.commit.confirmYes }));
    expect(await screen.findByTestId('commit-error')).toHaveTextContent(e.expired);
    expect(screen.getByRole('button', { name: e.result.again })).toBeInTheDocument();
  });

  it('explains that a file without valid rows cannot be imported', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/employee-imports')
        ? { status: 201, body: summary({ validRows: 0, invalidRows: 3 }) }
        : undefined,
    );
    await renderPage(locale);
    await uploadFile(userEvent.setup(), locale);
    expect(await screen.findByTestId('nothing-to-commit')).toHaveTextContent(e.commit.nothing);
    expect(screen.queryByRole('button', { name: /^Import|^Importer/u })).toBeNull();
  });
});

describe('employee import authorization and navigation', () => {
  it('is hidden from employees and loads nothing', async () => {
    const requests = stubApi();
    await renderPage('fr', ['employee'] as unknown as readonly ['tenant-admin']);
    expect(screen.getByText(resources.fr.common.employeeImport.unauthorized)).toBeInTheDocument();
    expect(requests).toHaveLength(0);
  });

  it('shows the navigation entry only to tenant administrators', async () => {
    stubApi();
    const { unmount } = await renderWithSession(
      <AppShell environment="test">
        <p>x</p>
      </AppShell>,
      sessionWithRoles(['tenant-admin']),
      'fr',
    );
    expect(screen.getByRole('link', { name: 'Importer des employés' })).toHaveAttribute(
      'href',
      '/admin/people/import',
    );
    unmount();
    await renderWithSession(
      <AppShell environment="test">
        <p>x</p>
      </AppShell>,
      sessionWithRoles(['employee']),
      'fr',
    );
    expect(screen.queryByRole('link', { name: 'Importer des employés' })).toBeNull();
  });
});

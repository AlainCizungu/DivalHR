import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { TimeExportsPage } from './TimeExportsPage';
import { timesheetFailureOf } from './timesheets';

const LEGAL = '22222222-0000-4000-8000-000000000002';
const SITE = '33333333-0000-4000-8000-000000000003';
const GENERATION = '44444444-0000-4000-8000-000000000004';
const EXPORT = '99999999-0000-4000-8000-000000000009';
const OLDER = '88888888-0000-4000-8000-000000000008';
const DIGEST = 'b'.repeat(64);
const CSV_DIGEST = 'c'.repeat(64);
const MONDAY = '2099-11-02';

const en = resources.en.common;
const fr = resources.fr.common;

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-tx-1234',
});

type Reply = { status: number; body?: unknown };
type Recorded = {
  method: string;
  path: string;
  body: Record<string, unknown> | null;
  key: string | null;
};

function stubApi(route: (request: Recorded) => Reply | Promise<Reply>) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const text = incoming.method === 'GET' ? '' : await incoming.text();
      const recorded: Recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: text ? (JSON.parse(text) as Record<string, unknown>) : null,
        key: incoming.headers.get('Idempotency-Key'),
      };
      requests.push(recorded);
      const reply = await route(recorded);
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

function stored(): string {
  const values: string[] = [];
  for (const storage of [window.localStorage, window.sessionStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key) values.push(`${key}=${storage.getItem(key) ?? ''}`);
    }
  }
  return values.join('\n');
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

const scope = {
  legalEntityId: LEGAL,
  legalEntityCode: 'DRC',
  legalEntityName: 'Dival RDC',
  siteId: SITE,
  siteCode: 'KIN',
  siteName: 'Kinshasa Gombe',
  timezone: 'Africa/Kinshasa',
  weekStart: MONDAY,
  weekEnd: '2099-11-09',
  lastDay: '2099-11-08',
  periodStartAt: '2099-11-01T23:00:00Z',
  periodEndAt: '2099-11-08T23:00:00Z',
};

const counts = { timesheets: 2, employees: 2, rows: 3, workedMinutes: 1422 };

const preview = (overrides: Record<string, unknown> = {}) => ({
  scope,
  generationId: GENERATION,
  generationVersion: 1,
  eligible: counts,
  excluded: { draft: 1, submitted: 1, reopened: 1 },
  previewDigest: DIGEST,
  expiresAt: '2099-11-08T11:30:00Z',
  existingExportId: null,
  ...overrides,
});

const exported = (id = EXPORT, createdAt = '2099-11-08T11:05:00Z') => ({
  exportId: id,
  status: 'READY',
  schemaVersion: 1,
  scope,
  generationId: GENERATION,
  generationVersion: 1,
  counts,
  csvSha256: CSV_DIGEST,
  createdAt,
});

const units = (request: Recorded): Reply | null => {
  if (request.path.startsWith('/legal-entities')) {
    return { status: 200, body: { data: [{ id: LEGAL, code: 'DRC', name: 'Dival RDC' }] } };
  }
  if (request.path.startsWith('/sites')) {
    return { status: 200, body: { data: [{ id: SITE, code: 'KIN', name: 'Kinshasa Gombe' }] } };
  }
  return null;
};

async function chooseScope(user: ReturnType<typeof userEvent.setup>) {
  const legal = screen.getByTestId('time-exports-legal-entity');
  await waitFor(() => {
    expect(within(legal).getAllByRole('option')).toHaveLength(2);
  });
  await user.selectOptions(legal, LEGAL);
  const site = screen.getByTestId('time-exports-site');
  await waitFor(() => {
    expect(within(site).getAllByRole('option')).toHaveLength(2);
  });
  await user.selectOptions(site, SITE);
  const week = screen.getByTestId('time-exports-week');
  await user.clear(week);
  await user.type(week, MONDAY);
}

describe('time export failures', () => {
  it('maps the export codes and the administrators-only refusal', () => {
    const response = (status: number) => new Response(null, { status });
    expect(
      timesheetFailureOf('exports', response(409), problem('TIMESHEET_EXPORT_EMPTY', 409)),
    ).toMatchObject({ kind: 'notAllowed', messageKey: 'errors.TIMESHEET_EXPORT_EMPTY' });
    expect(
      timesheetFailureOf('exports', response(409), problem('TIMESHEET_EXPORT_PREVIEW_STALE', 409)),
    ).toMatchObject({ kind: 'stale', messageKey: 'errors.TIMESHEET_EXPORT_PREVIEW_STALE' });
    expect(
      timesheetFailureOf('exports', response(403), problem('ACCESS_DENIED', 403)),
    ).toMatchObject({ kind: 'forbidden', messageKey: 'timeExports.unauthorized' });
    expect(en.timeExports.notice).toBe('Approved time only. This export does not calculate pay.');
    expect(fr.timeExports.notice).toBe(
      'Temps approuvé uniquement. Cet export ne calcule pas la paie.',
    );
    expect(en.timeExports.title).toBe('Time exports');
    expect(fr.timeExports.title).toBe('Exports de temps');
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('MVP-070E time exports (%s)', (locale, c) => {
  const k = c.timeExports;

  it('previews, creates after confirming, opens the export and downloads its CSV and manifest', async () => {
    const downloads: string[] = [];
    vi.stubGlobal(
      'URL',
      Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: () => undefined }),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      downloads.push(this.download);
    });
    let created = false;
    const requests = stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/timesheet-exports?')) {
        return {
          status: 200,
          body: { items: created ? [exported()] : [], nextCursor: null },
        };
      }
      if (request.path === '/timesheet-exports/preview') return { status: 200, body: preview() };
      if (request.path === '/timesheet-exports' && request.method === 'POST') {
        created = true;
        return { status: 200, body: { outcome: 'CREATED', export: exported() } };
      }
      if (request.path === `/timesheet-exports/${EXPORT}/csv`) return { status: 200, body: 'x' };
      if (request.path === `/timesheet-exports/${EXPORT}/manifest`) {
        return { status: 200, body: { status: 'READY' } };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimeExportsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/time-exports',
    );
    expect(screen.getByRole('heading', { level: 1, name: k.title })).toBeVisible();
    expect(screen.getByTestId('time-exports-notice')).toHaveTextContent(k.notice);
    expect(await screen.findByTestId('time-exports-history-empty')).toHaveTextContent(
      k.history.empty,
    );
    await expectAccessible(container);

    // A missing legal entity is refused before any request.
    await user.click(screen.getByTestId('time-exports-preview-button'));
    expect(await screen.findByText(k.form.legalEntityRequired)).toBeVisible();
    expect(requests.filter((r) => r.path.startsWith('/timesheet-exports/'))).toHaveLength(0);

    await chooseScope(user);
    await user.click(screen.getByTestId('time-exports-preview-button'));
    const shown = await screen.findByTestId('time-exports-preview');
    expect(requests.find((r) => r.path === '/timesheet-exports/preview')?.body).toEqual({
      legalEntityId: LEGAL,
      siteId: SITE,
      weekStart: MONDAY,
    });
    expect(within(shown).getByTestId('time-exports-eligible-timesheets')).toHaveTextContent('2');
    expect(within(shown).getByTestId('time-exports-eligible-rows')).toHaveTextContent('3');
    expect(within(shown).getByTestId('time-exports-eligible-worked')).toHaveTextContent(
      '23 h 42 min',
    );
    expect(within(shown).getByTestId('time-exports-excluded-reopened')).toHaveTextContent('1');
    expect(within(shown).getByTestId('time-exports-scope')).toHaveTextContent('Kinshasa Gombe');
    expect(within(shown).getByText(k.preview.excludedNote)).toBeVisible();
    // The heading takes the focus for keyboard and screen-reader users.
    await waitFor(() => {
      expect(within(shown).getByRole('heading', { level: 2, name: k.preview.title })).toHaveFocus();
    });
    const create = within(shown).getByRole('button', { name: k.preview.create });
    expect(create).toBeDisabled();
    await expectAccessible(container);
    await user.click(within(shown).getByLabelText(k.preview.confirmCheck));
    expect(create).toBeEnabled();
    await user.click(create);

    const detail = await screen.findByTestId('time-exports-detail');
    const command = requests.find((r) => r.path === '/timesheet-exports' && r.method === 'POST');
    expect(command?.body).toEqual({
      legalEntityId: LEGAL,
      siteId: SITE,
      weekStart: MONDAY,
      previewDigest: DIGEST,
      confirm: true,
    });
    expect(command?.key).toMatch(/^web-/u);
    expect(screen.getByTestId('time-exports-announcer')).toHaveTextContent(k.created);
    expect(within(detail).getByTestId('time-exports-detail-id')).toHaveTextContent(EXPORT);
    expect(within(detail).getByTestId('time-exports-detail-digest')).toHaveTextContent(CSV_DIGEST);
    expect(await screen.findAllByTestId('time-exports-item')).toHaveLength(1);
    await expectAccessible(container);

    await user.click(within(detail).getByRole('button', { name: k.detail.csv }));
    await waitFor(() => {
      expect(downloads).toEqual([`divalhr-time-export-${MONDAY}-${EXPORT}.csv`]);
    });
    await user.click(within(detail).getByRole('button', { name: k.detail.manifest }));
    await waitFor(() => {
      expect(downloads).toHaveLength(2);
    });
    expect(downloads[1]).toBe(`divalhr-time-export-${MONDAY}-${EXPORT}.manifest.json`);
    expect(screen.getByTestId('time-exports-announcer')).toHaveTextContent(
      k.detail.manifestDownloaded,
    );
    click.mockRestore();
    expect(stored()).not.toMatch(/99999999|Kinshasa|bbbb|cccc/u);
  });

  it('shows an empty week, a stale preview and the history with more pages', async () => {
    let stale = false;
    let empty = true;
    stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/timesheet-exports?') && request.path.includes('cursor=')) {
        return {
          status: 200,
          body: { items: [exported(OLDER, '2099-11-08T10:00:00Z')], nextCursor: null },
        };
      }
      if (request.path.startsWith('/timesheet-exports?')) {
        return { status: 200, body: { items: [exported()], nextCursor: 'next-1' } };
      }
      if (request.path === '/timesheet-exports/preview') {
        return {
          status: 200,
          body: empty
            ? preview({
                eligible: { timesheets: 0, employees: 0, rows: 0, workedMinutes: 0 },
                excluded: { draft: 2, submitted: 0, reopened: 1 },
              })
            : preview({ existingExportId: EXPORT }),
        };
      }
      if (request.path === '/timesheet-exports' && request.method === 'POST') {
        return stale
          ? { status: 409, body: problem('TIMESHEET_EXPORT_PREVIEW_STALE', 409) }
          : { status: 200, body: { outcome: 'EXISTING', export: exported() } };
      }
      if (request.path === `/timesheet-exports/${OLDER}`) {
        return { status: 200, body: exported(OLDER, '2099-11-08T10:00:00Z') };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    const { container } = await renderWithSession(
      <TimeExportsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/time-exports',
    );
    expect(await screen.findAllByTestId('time-exports-item')).toHaveLength(1);
    await user.click(screen.getByRole('button', { name: k.history.more }));
    await waitFor(() => {
      expect(screen.getAllByTestId('time-exports-item')).toHaveLength(2);
    });
    expect(screen.queryByRole('button', { name: k.history.more })).toBeNull();
    const rows = screen.getAllByTestId('time-exports-item');
    await user.click(within(rows[1]!).getByTestId('time-exports-item-open'));
    expect(await screen.findByTestId('time-exports-detail-id')).toHaveTextContent(OLDER);
    await expectAccessible(container);

    // An empty week: only the exclusions, no creation.
    await chooseScope(user);
    await user.click(screen.getByTestId('time-exports-preview-button'));
    const shown = await screen.findByTestId('time-exports-preview');
    expect(within(shown).getByTestId('time-exports-empty')).toHaveTextContent(k.preview.empty);
    expect(within(shown).getByTestId('time-exports-excluded-draft')).toHaveTextContent('2');
    expect(within(shown).queryByRole('button', { name: k.preview.create })).toBeNull();
    await expectAccessible(container);

    // A stale preview is refused and dropped; preview again.
    empty = false;
    stale = true;
    await user.click(screen.getByTestId('time-exports-preview-button'));
    const again = await screen.findByTestId('time-exports-existing');
    expect(again).toHaveTextContent(k.preview.existing);
    await user.click(screen.getByLabelText(k.preview.confirmCheck));
    await user.click(screen.getByRole('button', { name: k.preview.create }));
    expect(await screen.findByTestId('time-exports-error')).toHaveTextContent(
      c.errors.TIMESHEET_EXPORT_PREVIEW_STALE,
    );
    expect(screen.queryByTestId('time-exports-preview')).toBeNull();

    // The existing export of identical evidence is opened.
    stale = false;
    await user.click(screen.getByTestId('time-exports-preview-button'));
    await user.click(await screen.findByLabelText(k.preview.confirmCheck));
    await user.click(screen.getByRole('button', { name: k.preview.create }));
    await waitFor(() => {
      expect(screen.getByTestId('time-exports-announcer')).toHaveTextContent(k.existing);
    });
    expect(screen.getByTestId('time-exports-detail-id')).toHaveTextContent(EXPORT);
    await expectAccessible(container);
  });

  it('explains a week that is not generated and a refused administrator', async () => {
    let forbidden = false;
    stubApi((request) => {
      const unit = units(request);
      if (unit) return unit;
      if (request.path.startsWith('/timesheet-exports?')) {
        return { status: 200, body: { items: [], nextCursor: null } };
      }
      if (request.path === '/timesheet-exports/preview') {
        return forbidden
          ? { status: 403, body: problem('ACCESS_DENIED', 403) }
          : { status: 404, body: problem('TIMESHEET_TARGET_NOT_FOUND', 404) };
      }
      return { status: 404, body: problem('NOT_FOUND', 404) };
    });
    const user = userEvent.setup();
    await renderWithSession(
      <TimeExportsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      '/admin/time-exports',
    );
    await chooseScope(user);
    await user.click(screen.getByTestId('time-exports-preview-button'));
    expect(await screen.findByTestId('time-exports-error')).toHaveTextContent(
      k.preview.notGenerated,
    );
    forbidden = true;
    await user.click(screen.getByTestId('time-exports-preview-button'));
    await waitFor(() => {
      expect(screen.getByTestId('time-exports-error')).toHaveTextContent(k.unauthorized);
    });
    expect(screen.getByTestId('time-exports-error')).toHaveTextContent('corr-tx-1234');
  });
});

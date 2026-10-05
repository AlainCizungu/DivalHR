import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../shell/AppShell';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { AccessReviewPage } from './AccessReviewPage';

const LE = '11111111-1111-4111-8111-111111111111';
const SITE = '22222222-2222-4222-8222-222222222222';

const entry = (n: number, overrides: Record<string, unknown> = {}) => ({
  membershipId: `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`,
  email: `membre${n}@exemple.cd`,
  role: n % 2 === 0 ? 'tenant-admin' : 'employee',
  grantedAt: '2026-09-30T10:00:00Z',
  accessState: 'ACTIVE',
  directScope: { type: 'TENANT' },
  effectiveScope: { type: 'TENANT', coversAllLegalEntitiesAndSites: true },
  matchedUnit: null,
  ...overrides,
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
      const reply =
        route(request, url) ??
        (url.pathname.endsWith('/access-review/summary')
          ? { status: 200, body: { byRole: { 'tenant-admin': 1, employee: 2 } } }
          : url.pathname.endsWith('/access-review/entries')
            ? {
                status: 200,
                body: {
                  data: [entry(0, { email: null }), entry(1), entry(3, { accessState: 'REVOKED' })],
                },
              }
            : url.pathname.endsWith('/legal-entities')
              ? {
                  status: 200,
                  body: {
                    data: [
                      {
                        id: LE,
                        code: 'KIN',
                        name: 'Société Minière du Katanga',
                        countryCode: 'CD',
                        effectiveFrom: '2026-01-01',
                        effectiveTo: null,
                        createdAt: '2026-01-01T00:00:00Z',
                      },
                    ],
                  },
                }
              : url.pathname.endsWith('/sites')
                ? {
                    status: 200,
                    body: {
                      data: [
                        {
                          id: SITE,
                          legalEntityId: LE,
                          regionId: null,
                          code: 'LSH',
                          name: 'Lubumbashi',
                          effectiveFrom: '2026-01-01',
                          effectiveTo: null,
                          createdAt: '2026-01-01T00:00:00Z',
                        },
                      ],
                    },
                  }
                : { status: 404 });
      if (reply === 'network') return Promise.reject(new TypeError('Failed to fetch'));
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
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

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-review-1234',
});

async function renderPage(locale: 'fr' | 'en', roles = ['tenant-admin'] as const) {
  return renderWithSession(
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="accessReview.unauthorized"
      signInKey="accessReview.signInRequired"
    >
      <AccessReviewPage />
    </RequireRole>,
    sessionWithRoles([...roles]),
    locale,
  );
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

let setItem: { mock: { calls: unknown[][] } };
beforeEach(() => {
  setItem = vi.spyOn(Storage.prototype, 'setItem');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['fr', 'en'] as const)('access review (%s)', (locale) => {
  const c = resources[locale].common;
  const r = c.accessReview;

  it('shows the summary, the table and the notes, with no-store requests', async () => {
    const requests = stubApi();
    const before = window.location.href;
    const { container } = await renderPage(locale);
    const table = await screen.findByTestId('review-table');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(r.title);
    expect(within(table).getByText(r.table.caption)).toBeInTheDocument();
    expect(
      within(table)
        .getAllByRole('columnheader')
        .map((h) => h.textContent),
    ).toEqual([r.table.address, r.table.role, r.table.scope, r.table.grantedAt, r.table.access]);
    const rows = screen.getAllByTestId('review-row');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(r.addressNotRecorded);
    expect(rows[0]).toHaveTextContent(c.users.form.roles['tenant-admin']);
    expect(rows[1]).toHaveTextContent('membre1@exemple.cd');
    // MVP-022: a membership whose access a separation revoked is labelled, never shown as active.
    expect(within(rows[1] as HTMLElement).getByTestId('review-access')).toHaveTextContent(
      r.accessState.ACTIVE,
    );
    expect(within(rows[2] as HTMLElement).getByTestId('review-access')).toHaveTextContent(
      r.accessState.REVOKED,
    );
    expect(rows[1]).toHaveTextContent(r.scope.entire);
    expect(screen.getByTestId('summary-tenant-admin')).toHaveTextContent(
      r.summary.count
        .replace('{{role}}', c.users.form.roles['tenant-admin'])
        .replace('{{count}}', '1'),
    );
    expect(screen.getByTestId('time-zone-note')).toHaveTextContent(r.timeZoneNote);
    expect(screen.getByText(r.pendingNote, { exact: false })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: r.pendingLink })).toHaveAttribute(
      'href',
      '/admin/users',
    );
    await waitFor(() =>
      expect(screen.getByTestId('announcer')).toHaveTextContent(
        r.announce.results_other.replace('{{count}}', '3'),
      ),
    );
    for (const request of requests) expect(request.cache).toBe('no-store');
    const list = requests.find((req) => req.url.includes('/access-review/entries'));
    expect(new URL(list?.url ?? '').search).toBe('');
    expect(window.location.href).toBe(before);
    // Only the existing language preference is stored; no review data (A6).
    expect(setItem.mock.calls.map((call) => call[0])).toEqual(
      setItem.mock.calls.length === 0 ? [] : ['divalhr.locale'],
    );
    expect(JSON.stringify(setItem.mock.calls)).not.toMatch(/membre|@|access|review/u);
    await expectAccessible(container);
  });

  it('filters by legal entity, then site, and labels access as inherited', async () => {
    const requests = stubApi((_request, url) => {
      if (url.pathname.endsWith('/access-review/entries') && url.searchParams.has('siteId')) {
        return {
          status: 200,
          body: {
            data: [
              entry(1, {
                matchedUnit: { type: 'SITE', id: SITE, inheritance: 'INHERITED_FROM_TENANT' },
              }),
            ],
          },
        };
      }
      if (
        url.pathname.endsWith('/access-review/entries') &&
        url.searchParams.has('legalEntityId')
      ) {
        return {
          status: 200,
          body: {
            data: [
              entry(1, {
                matchedUnit: { type: 'LEGAL_ENTITY', id: LE, inheritance: 'INHERITED_FROM_TENANT' },
              }),
            ],
          },
        };
      }
      return undefined;
    });
    const { container } = await renderPage(locale);
    await screen.findByTestId('review-table');
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText(r.filters.legalEntity), LE);
    await user.selectOptions(screen.getByLabelText(r.filters.role), 'employee');
    await user.click(screen.getByRole('button', { name: r.filters.apply }));
    await waitFor(() =>
      expect(screen.getAllByTestId('review-row')[0]).toHaveTextContent(
        r.scope.inheritedLegalEntity,
      ),
    );
    let last = new URL(requests.filter((q) => q.url.includes('/entries')).at(-1)?.url ?? '');
    expect(last.searchParams.get('legalEntityId')).toBe(LE);
    expect(last.searchParams.get('role')).toBe('employee');
    expect(last.searchParams.has('siteId')).toBe(false);

    await user.selectOptions(await screen.findByLabelText(r.filters.site), SITE);
    await user.click(screen.getByRole('button', { name: r.filters.apply }));
    await waitFor(() =>
      expect(screen.getAllByTestId('review-row')[0]).toHaveTextContent(r.scope.inheritedSite),
    );
    last = new URL(requests.filter((q) => q.url.includes('/entries')).at(-1)?.url ?? '');
    expect(last.searchParams.get('siteId')).toBe(SITE);
    expect(last.searchParams.has('legalEntityId')).toBe(false);
    await expectAccessible(container);
  });

  it('looks up one exact address by POST and never puts it in a URL', async () => {
    const requests = stubApi((_request, url) => {
      if (url.pathname.endsWith('/access-review/lookup')) {
        return { status: 200, body: { data: [entry(5)] } };
      }
      return undefined;
    });
    await renderPage(locale);
    await screen.findByTestId('review-table');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: r.lookup.submit }));
    expect(screen.getByRole('alert')).toHaveTextContent(r.validation.email.REQUIRED);
    expect(requests.some((q) => q.url.includes('/lookup'))).toBe(false);
    const field = screen.getByLabelText(r.lookup.email);
    expect(field).toHaveAccessibleDescription(`${r.lookup.help} ${r.validation.email.REQUIRED}`);
    await user.type(field, '  membre5@exemple.cd ');
    await user.click(screen.getByRole('button', { name: r.lookup.submit }));
    await waitFor(() => {
      expect(screen.getAllByTestId('review-row')).toHaveLength(1);
    });
    const lookup = requests.find((q) => q.url.includes('/lookup'));
    expect(lookup?.method).toBe('POST');
    expect(lookup?.cache).toBe('no-store');
    expect(new URL(lookup?.url ?? '').search).toBe('');
    expect(await lookup?.clone().json()).toEqual({ email: 'membre5@exemple.cd' });
    for (const request of requests) expect(request.url).not.toContain('membre5');
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      r.announce.lookup_one.replace('{{count}}', '1'),
    );
    await user.click(screen.getByRole('button', { name: r.lookup.back }));
    await waitFor(() => {
      expect(screen.getAllByTestId('review-row')).toHaveLength(3);
    });
  });

  it('shows an empty lookup without echoing the address', async () => {
    stubApi((_request, url) =>
      url.pathname.endsWith('/access-review/lookup')
        ? { status: 200, body: { data: [] } }
        : undefined,
    );
    await renderPage(locale);
    await screen.findByTestId('review-table');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(r.lookup.email), 'personne@exemple.cd');
    await user.click(screen.getByRole('button', { name: r.lookup.submit }));
    expect(await screen.findByTestId('review-empty')).toHaveTextContent(r.lookupEmpty);
    expect(screen.getByTestId('announcer')).toHaveTextContent(r.announce.none);
  });

  it('loads more and moves focus to the first new row', async () => {
    stubApi((_request, url) => {
      if (url.pathname.endsWith('/access-review/entries')) {
        return url.searchParams.has('cursor')
          ? { status: 200, body: { data: [entry(7), entry(9)] } }
          : { status: 200, body: { data: [entry(1)], nextCursor: 'opaque-cursor' } };
      }
      return undefined;
    });
    await renderPage(locale);
    await screen.findByTestId('review-table');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: r.loadMore }));
    await waitFor(() => {
      expect(screen.getAllByTestId('review-row')).toHaveLength(3);
    });
    await waitFor(() => expect(screen.getAllByTestId('review-row')[1]).toHaveFocus());
    expect(screen.queryByRole('button', { name: r.loadMore })).toBeNull();
  });

  it('explains a rate limit with its delay and reference, and offers a retry', async () => {
    let limited = true;
    stubApi((_request, url) => {
      if (url.pathname.endsWith('/access-review/entries') && limited) {
        return {
          status: 429,
          body: problem('RATE_LIMITED', 429),
          headers: { 'Retry-After': '42' },
        };
      }
      return undefined;
    });
    const { container } = await renderPage(locale);
    const error = await screen.findByTestId('review-error');
    expect(error).toHaveTextContent(c.errors.RATE_LIMITED);
    expect(error).toHaveTextContent(r.retryAfter_other.replace('{{count}}', '42'));
    expect(error).toHaveTextContent('corr-review-1234');
    await expectAccessible(container);
    limited = false;
    await userEvent.setup().click(screen.getByRole('button', { name: r.retry }));
    await screen.findByTestId('review-table');
  });

  it('shows a safe message when the review cannot be completed', async () => {
    stubApi((_request, url) =>
      url.pathname.endsWith('/access-review/entries')
        ? { status: 500, body: problem('INTERNAL_ERROR', 500) }
        : undefined,
    );
    await renderPage(locale);
    expect(await screen.findByTestId('review-error')).toHaveTextContent(c.errors.INTERNAL_ERROR);
    expect(screen.queryByTestId('review-table')).toBeNull();
  });
});

describe('access review authorization and navigation', () => {
  it('is hidden from employees and loads nothing', async () => {
    const requests = stubApi();
    await renderPage('fr', ['employee'] as unknown as readonly ['tenant-admin']);
    expect(screen.getByText(resources.fr.common.accessReview.unauthorized)).toBeInTheDocument();
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
    expect(screen.getByRole('link', { name: 'Revue des accès' })).toHaveAttribute(
      'href',
      '/admin/access',
    );
    unmount();
    await renderWithSession(
      <AppShell environment="test">
        <p>x</p>
      </AppShell>,
      sessionWithRoles(['employee']),
      'fr',
    );
    expect(screen.queryByRole('link', { name: 'Revue des accès' })).toBeNull();
  });
});

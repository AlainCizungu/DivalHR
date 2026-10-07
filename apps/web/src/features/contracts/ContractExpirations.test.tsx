import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { ContractExpirationsPage } from './ContractExpirationsPage';
import { EmployeeContractsSection } from './EmployeeContractsSection';

const CONTRACT_A = 'aaaaaaaa-0000-4000-8000-00000000000a';
const CONTRACT_B = 'bbbbbbbb-0000-4000-8000-00000000000b';
const CONTRACT_C = 'cccccccc-0000-4000-8000-00000000000c';
const EMPLOYEE_A = '11111111-0000-4000-8000-000000000001';
const EMPLOYEE_B = '22222222-0000-4000-8000-000000000002';
const EMPLOYEE_C = '33333333-0000-4000-8000-000000000003';
const LE = '44444444-0000-4000-8000-000000000004';
const SITE = '55555555-0000-4000-8000-000000000005';
const DEPT = '66666666-0000-4000-8000-000000000006';
const COST = '77777777-0000-4000-8000-000000000007';

const counts = { expired: 1, next30Days: 1, days31To60: 0, days61To90: 1, total: 3 };

const items = [
  {
    contractId: CONTRACT_A,
    employeeId: EMPLOYEE_A,
    employeeNumber: 'E-0042',
    givenNames: 'Bénédicte',
    familyName: 'Mbuyi',
    unit: { id: DEPT, kind: 'DEPARTMENT', code: 'FIN', name: 'Finances' },
    endDate: '2026-10-03',
    category: 'EXPIRED',
    daysUntilEnd: -4,
  },
  {
    contractId: CONTRACT_B,
    employeeId: EMPLOYEE_B,
    employeeNumber: 'E-0007',
    givenNames: 'Jean-Paul',
    familyName: 'Kalala',
    unit: null,
    endDate: '2026-10-07',
    category: 'NEXT_30_DAYS',
    daysUntilEnd: 0,
  },
];

const later = {
  contractId: CONTRACT_C,
  employeeId: EMPLOYEE_C,
  employeeNumber: 'E-0100',
  givenNames: 'Zébulon',
  familyName: 'Mutombo',
  unit: { id: COST, kind: 'COST_CENTER', code: 'CC1', name: 'Logistique' },
  endDate: '2026-12-21',
  category: 'DAYS_61_TO_90',
  daysUntilEnd: 75,
};

const page = (overrides: Record<string, unknown> = {}) => ({
  asOf: '2026-10-07',
  timezone: 'Africa/Kinshasa',
  counts,
  items,
  nextCursor: null,
  ...overrides,
});

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-expiry-1234',
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> };
type Recorded = { method: string; path: string; body: string };

function stubApi(route: (path: string, body: Record<string, unknown>) => Reply | undefined) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (request: Request) => {
      const url = new URL(request.url);
      const body = request.method === 'GET' ? '' : await request.clone().text();
      const path = url.pathname.replace(/^.*\/api\/v1/u, '');
      requests.push({ method: request.method, path, body });
      const parsed = body ? (JSON.parse(body) as Record<string, unknown>) : {};
      const units: Record<string, unknown> = {
        '/legal-entities': { data: [{ id: LE, code: 'KIN', name: 'Société Kinshasa' }] },
        '/sites': { data: [{ id: SITE, code: 'GOM', name: 'Site Goma' }] },
        '/departments': { data: [{ id: DEPT, code: 'FIN', name: 'Finances' }] },
        '/cost-centers': { data: [{ id: COST, code: 'CC1', name: 'Logistique' }] },
      };
      const reply = route(path, parsed) ??
        (units[path] ? { status: 200, body: units[path] } : undefined) ?? {
          status: 404,
          body: problem('NOT_FOUND', 404),
        };
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
          ...reply.headers,
        },
      });
    }),
  );
  return requests;
}

const searches = (requests: Recorded[]) =>
  requests
    .filter((r) => r.path === '/contract-expirations/search')
    .map((r) => JSON.parse(r.body) as Record<string, unknown>);

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['fr', 'en'] as const)('MVP-031A contract expirations (%s)', (locale) => {
  const c = resources[locale].common;
  const k = c.contractExpirations;
  const show = (path = '/admin/contract-expirations') =>
    renderWithSession(
      <ContractExpirationsPage />,
      sessionWithRoles(['tenant-admin']),
      locale,
      path,
    );

  it('shows the server business date, the counts and one row per contract, never the contract ID', async () => {
    const requests = stubApi((path) =>
      path === '/contract-expirations/search' ? { status: 200, body: page() } : undefined,
    );
    const { container } = await show();
    const table = await screen.findByTestId('expirations-table');
    const asOf = new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(
      new Date('2026-10-07T00:00:00Z'),
    );
    expect(screen.getByTestId('as-of')).toHaveTextContent(
      k.asOf.replace('{{date}}', asOf).replace('{{timezone}}', 'Africa/Kinshasa'),
    );
    expect(screen.getByTestId('category-EXPIRED')).toHaveTextContent(
      k.categoryToggle.replace('{{label}}', k.category.EXPIRED).replace('{{count}}', '1'),
    );
    expect(screen.getByTestId('category-DAYS_31_TO_60')).toHaveAttribute('aria-pressed', 'true');
    const rows = within(table).getAllByTestId('expiration-row');
    expect(rows).toHaveLength(2);
    expect(within(rows[0]!).getByRole('link')).toHaveAttribute(
      'href',
      `/admin/people/${EMPLOYEE_A}#contracts`,
    );
    expect(rows[0]).toHaveTextContent('Bénédicte');
    expect(rows[0]).toHaveTextContent('E-0042');
    expect(rows[0]).toHaveTextContent('Finances (FIN)');
    expect(rows[0]).toHaveTextContent(k.days.overdue_other.replace('{{count}}', '4'));
    expect(rows[1]).toHaveTextContent(k.noUnit);
    expect(rows[1]).toHaveTextContent(k.days.today);
    expect(container.textContent).not.toContain(CONTRACT_A);
    expect(container.textContent).not.toContain(CONTRACT_B);
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      k.found_other.replace('{{count}}', '2'),
    );
    // The first page sends no filter: every category, no query, no unit, no cursor.
    expect(searches(requests)).toEqual([
      { query: null, unitId: null, categories: null, cursor: null, limit: 25 },
    ]);
    await expectAccessible(container);
  });

  it('narrows by category, keeps the counts and never leaves no category selected', async () => {
    const user = userEvent.setup();
    const requests = stubApi((path, body) => {
      if (path !== '/contract-expirations/search') return undefined;
      const categories = body.categories as string[] | null;
      return {
        status: 200,
        body: page({
          items: categories ? items.filter((i) => categories.includes(i.category)) : items,
        }),
      };
    });
    await show();
    await screen.findByTestId('expirations-table');
    for (const category of ['NEXT_30_DAYS', 'DAYS_31_TO_60', 'DAYS_61_TO_90']) {
      await user.click(screen.getByTestId(`category-${category}`));
    }
    await waitFor(() => {
      expect(screen.getAllByTestId('expiration-row')).toHaveLength(1);
    });
    expect(screen.getByTestId('category-NEXT_30_DAYS')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('category-NEXT_30_DAYS')).toHaveTextContent(
      k.categoryToggle.replace('{{label}}', k.category.NEXT_30_DAYS).replace('{{count}}', '1'),
    );
    const before = searches(requests).length;
    await user.click(screen.getByTestId('category-EXPIRED'));
    expect(screen.getByTestId('category-EXPIRED')).toHaveAttribute('aria-pressed', 'true');
    expect(searches(requests)).toHaveLength(before);
    expect(searches(requests).at(-1)).toMatchObject({ categories: ['EXPIRED'] });
  });

  it('filters by search text and by a department or cost center from the hierarchy', async () => {
    const user = userEvent.setup();
    const requests = stubApi((path, body) =>
      path === '/contract-expirations/search'
        ? {
            status: 200,
            body:
              body.unitId === COST
                ? page({ items: [later], counts: { ...counts, total: 1 } })
                : page(),
          }
        : undefined,
    );
    const { container } = await show();
    await screen.findByTestId('expirations-table');
    const form = screen.getByTestId('expiration-filters');
    await user.type(within(form).getByLabelText(k.filters.query), 'z');
    await user.click(within(form).getByRole('button', { name: k.filters.apply }));
    const query = within(form).getByLabelText(k.filters.query);
    expect(query).toHaveAttribute('aria-invalid', 'true');
    expect(query).toHaveFocus();
    expect(within(form).getByRole('alert')).toHaveTextContent(k.filters.queryLength);
    expect(searches(requests)).toHaveLength(1);

    await user.clear(query);
    await user.type(query, '  Zébulon  ');
    const unit = within(form).getByTestId('unit-filter');
    expect(within(unit).getByLabelText(k.filters.site)).toBeDisabled();
    await user.selectOptions(await within(unit).findByLabelText(k.filters.legalEntity), [LE]);
    await waitFor(() => {
      expect(within(unit).getByLabelText(k.filters.site)).toBeEnabled();
    });
    await within(unit).findByRole('option', { name: 'Site Goma (GOM)' });
    await user.selectOptions(within(unit).getByLabelText(k.filters.site), [SITE]);
    const department = within(unit).getByLabelText(k.filters.department);
    await within(department).findByRole('option', { name: 'Logistique (CC1)' });
    expect(within(department).getByRole('group', { name: k.filters.departments })).toBeTruthy();
    expect(within(department).getByRole('group', { name: k.filters.costCenters })).toBeTruthy();
    await user.selectOptions(department, [COST]);
    await user.click(within(form).getByRole('button', { name: k.filters.apply }));
    await waitFor(() => {
      expect(screen.getAllByTestId('expiration-row')).toHaveLength(1);
    });
    expect(searches(requests).at(-1)).toEqual({
      query: 'Zébulon',
      unitId: COST,
      categories: null,
      cursor: null,
      limit: 25,
    });
    expect(screen.getByTestId('expiration-row')).toHaveTextContent('Logistique (CC1)');
    expect(window.location.search).toBe('');
    await expectAccessible(container);

    await user.click(within(form).getByRole('button', { name: k.filters.reset }));
    await waitFor(() => {
      expect(screen.getAllByTestId('expiration-row')).toHaveLength(2);
    });
    expect(within(form).getByLabelText(k.filters.query)).toHaveValue('');
    expect(searches(requests).at(-1)).toMatchObject({ query: null, unitId: null });
  });

  it('shows more with the cursor and moves focus to the first new row', async () => {
    const user = userEvent.setup();
    const requests = stubApi((path, body) => {
      if (path !== '/contract-expirations/search') return undefined;
      return body.cursor === 'next-1'
        ? { status: 200, body: page({ items: [later] }) }
        : { status: 200, body: page({ nextCursor: 'next-1' }) };
    });
    await show();
    await screen.findByTestId('expirations-table');
    await user.click(screen.getByRole('button', { name: k.more }));
    await waitFor(() => {
      expect(screen.getAllByTestId('expiration-row')).toHaveLength(3);
    });
    await waitFor(() => {
      expect(
        screen.getByRole('link', {
          name: c.employees.fullName
            .replace('{{given}}', 'Zébulon')
            .replace('{{family}}', 'Mutombo'),
        }),
      ).toHaveFocus();
    });
    expect(searches(requests).at(-1)).toMatchObject({ cursor: 'next-1' });
    expect(screen.queryByRole('button', { name: k.more })).toBeNull();
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      k.found_other.replace('{{count}}', '3'),
    );
  });

  it('distinguishes an empty queue from an empty filtered result', async () => {
    const user = userEvent.setup();
    stubApi((path) =>
      path === '/contract-expirations/search'
        ? {
            status: 200,
            body: page({
              items: [],
              counts: { expired: 0, next30Days: 0, days31To60: 0, days61To90: 0, total: 0 },
            }),
          }
        : undefined,
    );
    const { container } = await show();
    expect(await screen.findByTestId('expirations-empty')).toHaveTextContent(k.empty);
    await user.click(screen.getByTestId('category-EXPIRED'));
    await waitFor(() => {
      expect(screen.getByTestId('expirations-empty')).toHaveTextContent(k.emptyFiltered);
    });
    await expectAccessible(container);
  });

  it('reports a rate limit with its delay and retries', async () => {
    const user = userEvent.setup();
    let calls = 0;
    stubApi((path) => {
      if (path !== '/contract-expirations/search') return undefined;
      calls += 1;
      return calls === 1
        ? { status: 429, body: problem('RATE_LIMITED', 429), headers: { 'Retry-After': '30' } }
        : { status: 200, body: page() };
    });
    const { container } = await show();
    const alert = await screen.findByTestId('expirations-error');
    expect(alert).toHaveTextContent(c.errors.RATE_LIMITED);
    expect(alert).toHaveTextContent(c.employees.retryAfter_other.replace('{{count}}', '30'));
    expect(alert).toHaveTextContent('corr-expiry-1234');
    await expectAccessible(container);
    await user.click(screen.getByRole('button', { name: k.retry }));
    expect(await screen.findByTestId('expirations-table')).toBeTruthy();
  });

  it('explains a refusal from the server', async () => {
    stubApi((path) =>
      path === '/contract-expirations/search'
        ? { status: 403, body: problem('ACCESS_DENIED', 403) }
        : undefined,
    );
    await show();
    expect(await screen.findByTestId('expirations-error')).toHaveTextContent(k.unauthorized);
  });

  it('is refused to an employee before any request', async () => {
    const requests = stubApi(() => undefined);
    await renderWithSession(
      <Routes>
        <Route
          path="/admin/contract-expirations"
          element={
            <RequireRole
              requiredRole="tenant-admin"
              deniedKey="contractExpirations.unauthorized"
              signInKey="contractExpirations.signInRequired"
            >
              <ContractExpirationsPage />
            </RequireRole>
          }
        />
      </Routes>,
      sessionWithRoles(['employee']),
      locale,
      '/admin/contract-expirations',
    );
    expect(await screen.findByText(k.unauthorized)).toBeTruthy();
    expect(requests).toEqual([]);
  });

  it('moves focus to the contracts section when the record opens from the queue', async () => {
    stubApi((path) =>
      path === `/employees/${EMPLOYEE_A}/contracts`
        ? { status: 200, body: { items: [], nextCursor: null } }
        : path === '/contract-templates'
          ? { status: 200, body: { items: [], nextCursor: null } }
          : undefined,
    );
    await renderWithSession(
      <EmployeeContractsSection
        employeeId={EMPLOYEE_A}
        businessDate="2026-10-07"
        revision={0}
        onChanged={vi.fn()}
      />,
      sessionWithRoles(['tenant-admin']),
      locale,
      `/admin/people/${EMPLOYEE_A}#contracts`,
    );
    const section = await screen.findByTestId('contracts');
    expect(section).toHaveAttribute('id', 'contracts');
    await waitFor(() => {
      expect(within(section).getByRole('heading', { name: c.contracts.issue.title })).toHaveFocus();
    });
  });
});

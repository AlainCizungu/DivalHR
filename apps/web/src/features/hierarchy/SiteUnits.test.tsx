import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { HierarchyPage } from './HierarchyPage';
import { RequireRole } from './RequireRole';

const LE = {
  id: '11111111-1111-4111-8111-111111111111',
  code: 'KIN-01',
  name: 'Société Minière de Kinshasa',
  countryCode: 'CD',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  createdAt: '2026-09-29T10:15:00Z',
};
const LE2 = {
  ...LE,
  id: '22222222-2222-4222-8222-222222222222',
  code: 'LUB-01',
  name: 'Brasseries',
};
const SITE = {
  id: '33333333-3333-4333-8333-333333333333',
  legalEntityId: LE.id,
  code: 'GOMBE',
  name: 'Siège de la Gombe',
  timezone: 'Africa/Kinshasa',
  effectiveFrom: '2026-02-01',
  effectiveTo: '2026-12-31',
  createdAt: '2026-09-29T10:20:00Z',
};
const DEPT = {
  id: '44444444-4444-4444-8444-444444444444',
  siteId: SITE.id,
  code: 'RH',
  name: 'Ressources humaines et relations sociales',
  effectiveFrom: '2026-02-01',
  effectiveTo: '2026-12-31',
  createdAt: '2026-09-29T10:25:00Z',
};
const DEPT2 = {
  ...DEPT,
  id: '55555555-5555-4555-8555-555555555555',
  code: 'SEC',
  name: 'Sécurité',
};
const CC = {
  ...DEPT,
  id: '66666666-6666-4666-8666-666666666666',
  code: 'CC-01',
  name: 'Centre de coût Opérations',
};

type Reply = { status: number; body?: unknown } | 'network';
type Route = (request: Request, url: URL) => Reply | undefined;

function stubApi(route: Route) {
  const requests: Request[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const url = new URL(request.url);
      let reply = route(request, url);
      if (!reply) {
        if (url.pathname.endsWith('/legal-entities'))
          reply = { status: 200, body: { data: [LE, LE2] } };
        else if (url.pathname.endsWith('/sites')) reply = { status: 200, body: { data: [SITE] } };
        else reply = { status: 200, body: { data: [] } };
      }
      if (reply === 'network') return Promise.reject(new TypeError('Failed to fetch'));
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
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
  correlationId: 'corr-12345678',
});

async function renderPage(locale: 'fr' | 'en' = 'en') {
  return renderWithSession(
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="hierarchy.unauthorized"
      signInKey="hierarchy.signInRequired"
    >
      <HierarchyPage />
    </RequireRole>,
    sessionWithRoles(['tenant-admin']),
    locale,
  );
}

function fill(template: string, values: Record<string, string>) {
  return Object.entries(values).reduce((text, [k, v]) => text.replace(`{{${k}}}`, v), template);
}

async function openSite(user: ReturnType<typeof userEvent.setup>, locale: 'en' | 'fr' = 'en') {
  const s = resources[locale].common.hierarchy;
  await user.click(
    await screen.findByRole('button', {
      name: fill(s.legalEntities.select, { name: LE.name, code: LE.code }),
    }),
  );
  const select = await screen.findByRole('button', {
    name: fill(s.sites.select, { name: SITE.name, code: SITE.code }),
  });
  await user.click(select);
  return select;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('departments and cost centers (%s)', (locale) => {
  const s = resources[locale].common.hierarchy;

  it('selects a site by name and code and shows both empty lists accessibly', async () => {
    stubApi(() => undefined);
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    const select = await openSite(user, locale);
    expect(select).toHaveTextContent(s.sites.selectShort);
    expect(select).toHaveAttribute('aria-pressed', 'true');
    const heading = await screen.findByRole('heading', {
      level: 2,
      name: fill(s.siteUnits.title, { name: SITE.name, code: SITE.code }),
    });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(await screen.findByTestId('department-list-empty')).toHaveTextContent(
      s.departments.empty,
    );
    expect(await screen.findByTestId('costCenter-list-empty')).toHaveTextContent(
      s.costCenters.empty,
    );
    const form = screen.getByTestId('department-form');
    expect(within(form).getByLabelText(s.fields.departmentName.label)).toBeInTheDocument();
    // Dates default to the site's period.
    expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveValue(
      SITE.effectiveFrom,
    );
    expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveValue(SITE.effectiveTo);
    expect(container.querySelectorAll('[lang]')).toHaveLength(0);
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

describe('departments and cost centers behaviour', () => {
  const s = resources.en.common.hierarchy;
  const errors = resources.en.common.errors;

  it('focus moves to the section heading without trapping the keyboard', async () => {
    stubApi(() => undefined);
    const user = userEvent.setup();
    await renderPage();
    await openSite(user);
    const heading = await screen.findByTestId('site-units-section');
    const h2 = within(heading).getByRole('heading', { level: 2 });
    await waitFor(() => {
      expect(h2).toHaveFocus();
    });
    expect(h2).toHaveAttribute('tabindex', '-1');
    await user.tab();
    expect(h2).not.toHaveFocus();
  });

  it('lists departments with the site filter and loads more with focus on the first new item', async () => {
    const requests = stubApi((_, url) => {
      if (!url.pathname.endsWith('/departments')) return undefined;
      return url.searchParams.get('cursor') === 'opaque.cursor'
        ? { status: 200, body: { data: [DEPT2] } }
        : { status: 200, body: { data: [DEPT], nextCursor: 'opaque.cursor' } };
    });
    const user = userEvent.setup();
    await renderPage();
    await openSite(user);
    const list = await screen.findByTestId('department-list');
    expect(list).toHaveTextContent(DEPT.name);
    await user.click(screen.getByRole('button', { name: s.departments.loadMore }));
    await waitFor(() => {
      expect(screen.getByText(DEPT2.name).closest('[tabindex]')).toHaveFocus();
    });
    const departmentCalls = requests.filter((r) =>
      new URL(r.url).pathname.endsWith('/departments'),
    );
    expect(new URL(departmentCalls[0]!.url).searchParams.get('siteId')).toBe(SITE.id);
    expect(new URL(departmentCalls[1]!.url).searchParams.get('cursor')).toBe('opaque.cursor');
  });

  it('creates a department with a normalized payload and announces it once', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/departments')
        ? { status: 201, body: DEPT }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage();
    await openSite(user);
    const form = await screen.findByTestId('department-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), '  rh ');
    await user.type(within(form).getByLabelText(s.fields.departmentName.label), ` ${DEPT.name} `);
    await user.click(within(form).getByRole('button', { name: s.departmentForm.submit }));
    const message = fill(s.departments.created, { name: DEPT.name, code: DEPT.code });
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(message);
    });
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getAllByText(message)).toHaveLength(1);
    expect(screen.getByTestId('department-list')).toHaveTextContent(DEPT.name);
    const post = requests.find((r) => r.method === 'POST')!;
    expect(new URL(post.url).pathname.endsWith('/departments')).toBe(true);
    expect(post.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/);
    expect(await post.json()).toEqual({
      siteId: SITE.id,
      code: 'RH',
      name: DEPT.name,
      effectiveFrom: SITE.effectiveFrom,
      effectiveTo: SITE.effectiveTo,
    });
  });

  it('maps cost-center errors to fields, reuses the key after a network failure and renews it after an edit', async () => {
    let posts = 0;
    const requests = stubApi((request, url) => {
      if (request.method !== 'POST' || !url.pathname.endsWith('/cost-centers')) return undefined;
      posts++;
      if (posts === 1) return 'network';
      if (posts === 2)
        return { status: 409, body: problem('DUPLICATE_COST_CENTER_CODE', 409, { field: 'code' }) };
      if (posts === 3)
        return {
          status: 400,
          body: problem('COST_CENTER_PERIOD_OUTSIDE_SITE', 400, { field: 'effectiveTo' }),
        };
      return { status: 201, body: CC };
    });
    const user = userEvent.setup();
    await renderPage();
    await openSite(user);
    const form = await screen.findByTestId('costCenter-form');
    const submit = within(form).getByRole('button', { name: s.costCenterForm.submit });
    await user.type(within(form).getByLabelText(s.fields.code.label), 'CC-01');
    await user.type(within(form).getByLabelText(s.fields.costCenterName.label), CC.name);
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByTestId('form-error')).toHaveTextContent(errors.network);
    });
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.code.label)).toHaveAccessibleDescription(
        `${s.fields.code.help} ${s.validation.code.DUPLICATE_CODE}`,
      );
    });
    expect(within(form).getByTestId('form-summary')).toHaveFocus();
    expect(within(form).queryAllByRole('alert')).toHaveLength(0);

    await user.clear(within(form).getByLabelText(s.fields.code.label));
    await user.type(within(form).getByLabelText(s.fields.code.label), 'CC-02');
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveAccessibleDescription(
        `${s.fields.effectiveTo.help} ${s.validation.effectiveTo.OUTSIDE_SITE}`,
      );
    });
    const keys = requests
      .filter((r) => r.method === 'POST')
      .map((r) => r.headers.get('Idempotency-Key'));
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
  });

  it('shows a missing site as a form error and validates on the client first', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/departments')
        ? { status: 404, body: problem('SITE_NOT_FOUND', 404, { field: 'siteId' }) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage();
    await openSite(user);
    const form = await screen.findByTestId('department-form');
    await user.click(within(form).getByRole('button', { name: s.departmentForm.submit }));
    const summary = within(form).getByTestId('form-summary');
    await waitFor(() => {
      expect(summary).toHaveFocus();
    });
    expect(within(summary).getByRole('link', { name: s.validation.code.REQUIRED })).toBeVisible();
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    await user.type(within(form).getByLabelText(s.fields.code.label), 'OPS');
    await user.type(within(form).getByLabelText(s.fields.departmentName.label), 'Opérations');
    await user.click(within(form).getByRole('button', { name: s.departmentForm.submit }));
    await waitFor(() => {
      expect(within(form).getByTestId('form-error')).toHaveTextContent(errors.SITE_NOT_FOUND);
    });
  });

  it('selecting another legal entity clears the selected site', async () => {
    stubApi(() => undefined);
    const user = userEvent.setup();
    await renderPage();
    await openSite(user);
    expect(await screen.findByTestId('site-units-section')).toBeInTheDocument();
    await user.click(
      screen.getByRole('button', {
        name: fill(s.legalEntities.select, { name: LE2.name, code: LE2.code }),
      }),
    );
    await waitFor(() => {
      expect(screen.queryByTestId('site-units-section')).toBeNull();
    });
  });

  it('keeps the French locale through selection and submission', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/cost-centers')
        ? { status: 201, body: CC }
        : undefined,
    );
    const fr = resources.fr.common.hierarchy;
    const user = userEvent.setup();
    await renderPage('fr');
    await openSite(user, 'fr');
    const form = await screen.findByTestId('costCenter-form');
    await user.type(within(form).getByLabelText(fr.fields.code.label), 'CC-01');
    await user.type(within(form).getByLabelText(fr.fields.costCenterName.label), CC.name);
    await user.click(within(form).getByRole('button', { name: fr.costCenterForm.submit }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(
        fill(fr.costCenters.created, { name: CC.name, code: CC.code }),
      );
    });
    expect(document.documentElement.lang).toBe('fr');
  });
});

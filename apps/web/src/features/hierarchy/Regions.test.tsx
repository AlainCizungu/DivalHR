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
  code: 'KAT-01',
  name: 'Société Minière du Grand Katanga et du Haut-Lomami',
  countryCode: 'CD',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  createdAt: '2026-09-29T10:15:00Z',
};
const REGION = {
  id: '22222222-2222-4222-8222-222222222222',
  legalEntityId: LE.id,
  code: 'NORD',
  name: 'Région Grand Katanga et Haut-Lomami',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  createdAt: '2026-09-29T10:16:00Z',
};
const REGION2 = {
  ...REGION,
  id: '33333333-3333-4333-8333-333333333333',
  code: 'SUD',
  name: 'Région Sud',
};
const UNASSIGNED = {
  id: '44444444-4444-4444-8444-444444444444',
  legalEntityId: LE.id,
  regionId: null,
  code: 'GOMBE',
  name: 'Siège de la Gombe',
  timezone: 'Africa/Kinshasa',
  effectiveFrom: '2026-02-01',
  effectiveTo: '2026-12-31',
  createdAt: '2026-09-29T10:20:00Z',
};
const ASSIGNED = {
  ...UNASSIGNED,
  id: '55555555-5555-4555-8555-555555555555',
  regionId: REGION.id,
  code: 'KOLWEZI',
  name: 'Site de Kolwezi',
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
        if (url.pathname.endsWith('/legal-entities')) reply = { status: 200, body: { data: [LE] } };
        else if (url.pathname.endsWith('/regions'))
          reply = { status: 200, body: { data: [REGION] } };
        else if (url.pathname.endsWith('/sites'))
          reply = { status: 200, body: { data: [UNASSIGNED, ASSIGNED] } };
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

async function openLegalEntity(user: ReturnType<typeof userEvent.setup>, locale: 'en' | 'fr') {
  const s = resources[locale].common.hierarchy;
  await user.click(
    await screen.findByRole('button', {
      name: fill(s.legalEntities.select, { name: LE.name, code: LE.code }),
    }),
  );
}

function siteRow(site: { name: string }) {
  const item = screen.getByText(site.name).closest('li');
  if (!item) throw new Error('site row not found');
  return item;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('regions and site assignment (%s)', (locale) => {
  const s = resources[locale].common.hierarchy;

  it('shows regions first, keeps every site reachable, and is accessible', async () => {
    stubApi(() => undefined);
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await openLegalEntity(user, locale);
    const heading = await screen.findByRole('heading', {
      level: 2,
      name: fill(s.regions.title, { name: LE.name, code: LE.code }),
    });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(heading).toHaveAttribute('tabindex', '-1');
    expect(await screen.findByTestId('region-list')).toHaveTextContent(REGION.name);
    // Both sites are listed, with their region status.
    await waitFor(() => {
      expect(within(siteRow(ASSIGNED)).getByTestId('site-region')).toHaveTextContent(
        fill(s.sites.region, { name: REGION.name, code: REGION.code }),
      );
    });
    expect(within(siteRow(UNASSIGNED)).getByTestId('site-region')).toHaveTextContent(
      s.sites.noRegion,
    );
    // Only the site without a region offers an assignment, named by site name and code.
    expect(
      screen.getByRole('button', {
        name: fill(s.assignment.open, { name: UNASSIGNED.name, code: UNASSIGNED.code }),
      }),
    ).toHaveAttribute('aria-expanded', 'false');
    expect(
      screen.queryByRole('button', {
        name: fill(s.assignment.open, { name: ASSIGNED.name, code: ASSIGNED.code }),
      }),
    ).toBeNull();
    // The site form offers the optional region, defaulting to none.
    const siteForm = screen.getByTestId('site-form');
    const regionSelect = within(siteForm).getByLabelText(s.fields.region.label);
    expect(regionSelect).toHaveValue('');
    expect(within(regionSelect).getByRole('option', { name: s.fields.region.none })).toBeVisible();
    expect(
      within(regionSelect).getByRole('option', {
        name: fill(s.regions.option, { name: REGION.name, code: REGION.code }),
      }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('status')).toHaveLength(1);
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  it('assigns a region with focus, one announcement and a stable locale', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'PUT' && url.pathname.endsWith(`/sites/${UNASSIGNED.id}/region`)
        ? { status: 200, body: { ...UNASSIGNED, regionId: REGION.id } }
        : undefined,
    );
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await openLegalEntity(user, locale);
    const open = await screen.findByRole('button', {
      name: fill(s.assignment.open, { name: UNASSIGNED.name, code: UNASSIGNED.code }),
    });
    await waitFor(() => {
      expect(within(siteRow(ASSIGNED)).getByTestId('site-region')).toHaveTextContent(REGION.code);
    });
    await user.click(open);
    expect(open).toHaveAttribute('aria-expanded', 'true');
    const form = screen.getByTestId('assign-region-form');
    expect(open).toHaveAttribute('aria-controls', form.id);
    const select = within(form).getByLabelText(s.fields.assignRegion.label);
    await waitFor(() => {
      expect(select).toHaveFocus();
    });
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);

    await user.selectOptions(select, REGION.id);
    await user.click(within(form).getByRole('button', { name: s.assignment.submit }));
    const message = fill(s.assignment.done, {
      site: UNASSIGNED.name,
      siteCode: UNASSIGNED.code,
      region: REGION.name,
      regionCode: REGION.code,
    });
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(message);
    });
    const status = within(siteRow(UNASSIGNED)).getByTestId('site-region');
    await waitFor(() => {
      expect(status).toHaveFocus();
    });
    expect(status).toHaveTextContent(
      fill(s.sites.region, { name: REGION.name, code: REGION.code }),
    );
    expect(screen.queryByTestId('assign-region-form')).toBeNull();
    expect(
      screen.queryByRole('button', {
        name: fill(s.assignment.open, { name: UNASSIGNED.name, code: UNASSIGNED.code }),
      }),
    ).toBeNull();
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getAllByText(message)).toHaveLength(1);

    const put = requests.find((r) => r.method === 'PUT')!;
    expect(put.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/);
    expect(await put.json()).toEqual({ regionId: REGION.id });
    expect(document.documentElement.lang).toBe(locale);
  });
});

describe('regions and site assignment behaviour', () => {
  const s = resources.en.common.hierarchy;
  const errors = resources.en.common.errors;

  it('lists regions with the legal-entity filter and loads more with focus on the first new one', async () => {
    const requests = stubApi((_, url) => {
      if (!url.pathname.endsWith('/regions')) return undefined;
      if (url.searchParams.get('limit') === '200')
        return { status: 200, body: { data: [REGION, REGION2] } };
      return url.searchParams.get('cursor') === 'opaque.region'
        ? { status: 200, body: { data: [REGION2] } }
        : { status: 200, body: { data: [REGION], nextCursor: 'opaque.region' } };
    });
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    const list = await screen.findByTestId('region-list');
    expect(list).toHaveTextContent(REGION.name);
    await user.click(screen.getByRole('button', { name: s.regions.loadMore }));
    await waitFor(() => {
      expect(within(list).getByText(REGION2.name).closest('[tabindex]')).toHaveFocus();
    });
    const regionCalls = requests.filter((r) => new URL(r.url).pathname.endsWith('/regions'));
    for (const call of regionCalls) {
      expect(new URL(call.url).searchParams.get('legalEntityId')).toBe(LE.id);
    }
    expect(
      regionCalls.some((call) => new URL(call.url).searchParams.get('cursor') === 'opaque.region'),
    ).toBe(true);
    // The options loader asked for the largest page.
    expect(regionCalls.some((call) => new URL(call.url).searchParams.get('limit') === '200')).toBe(
      true,
    );
  });

  it('creates a region, focuses it, announces it once and offers it to sites', async () => {
    const requests = stubApi((request, url) => {
      if (!url.pathname.endsWith('/regions')) return undefined;
      if (request.method === 'POST') return { status: 201, body: REGION2 };
      return { status: 200, body: { data: [REGION] } };
    });
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    const form = await screen.findByTestId('region-form');
    // Dates default to the legal entity's period.
    expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveValue(LE.effectiveFrom);
    await user.type(within(form).getByLabelText(s.fields.code.label), ' sud ');
    await user.type(within(form).getByLabelText(s.fields.regionName.label), ` ${REGION2.name} `);
    await user.click(within(form).getByRole('button', { name: s.regionForm.submit }));
    const message = fill(s.regions.created, { name: REGION2.name, code: REGION2.code });
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(message);
    });
    const row = within(screen.getByTestId('region-list'))
      .getByText(REGION2.name)
      .closest('[tabindex]');
    await waitFor(() => {
      expect(row).toHaveFocus();
    });
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getAllByText(message)).toHaveLength(1);
    const post = requests.find((r) => r.method === 'POST')!;
    expect(await post.json()).toEqual({
      legalEntityId: LE.id,
      code: 'SUD',
      name: REGION2.name,
      effectiveFrom: LE.effectiveFrom,
      effectiveTo: null,
    });
    const siteForm = screen.getByTestId('site-form');
    expect(
      within(within(siteForm).getByLabelText(s.fields.region.label)).getByRole('option', {
        name: fill(s.regions.option, { name: REGION2.name, code: REGION2.code }),
      }),
    ).toBeInTheDocument();
  });

  it('maps region errors to fields and focuses the summary', async () => {
    let posts = 0;
    stubApi((request, url) => {
      if (!(request.method === 'POST' && url.pathname.endsWith('/regions'))) return undefined;
      posts++;
      return posts === 1
        ? { status: 409, body: problem('DUPLICATE_REGION_CODE', 409, { field: 'code' }) }
        : {
            status: 400,
            body: problem('REGION_PERIOD_OUTSIDE_LEGAL_ENTITY', 400, { field: 'effectiveTo' }),
          };
    });
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    const form = await screen.findByTestId('region-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), 'NORD');
    await user.type(within(form).getByLabelText(s.fields.regionName.label), 'Doublon');
    await user.click(within(form).getByRole('button', { name: s.regionForm.submit }));
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.code.label)).toHaveAccessibleDescription(
        `${s.fields.code.help} ${s.validation.code.DUPLICATE_CODE}`,
      );
    });
    await waitFor(() => {
      expect(within(form).getByTestId('form-summary')).toHaveFocus();
    });
    await user.clear(within(form).getByLabelText(s.fields.code.label));
    await user.type(within(form).getByLabelText(s.fields.code.label), 'EST');
    await user.click(within(form).getByRole('button', { name: s.regionForm.submit }));
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveAccessibleDescription(
        `${s.fields.effectiveTo.help} ${s.validation.effectiveTo.OUTSIDE_LEGAL_ENTITY}`,
      );
    });
  });

  it('cancels, validates on the client and maps assignment errors with key reuse', async () => {
    let puts = 0;
    const requests = stubApi((request, url) => {
      if (request.method !== 'PUT' || !url.pathname.endsWith('/region')) return undefined;
      puts++;
      if (puts === 1) return 'network';
      if (puts === 2)
        return {
          status: 400,
          body: problem('SITE_PERIOD_OUTSIDE_REGION', 400, { field: 'regionId' }),
        };
      return {
        status: 400,
        body: problem('SITE_REGION_LEGAL_ENTITY_MISMATCH', 400, { field: 'regionId' }),
      };
    });
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    const open = await screen.findByRole('button', {
      name: fill(s.assignment.open, { name: UNASSIGNED.name, code: UNASSIGNED.code }),
    });
    await user.click(open);
    await user.click(screen.getByRole('button', { name: s.assignment.cancel }));
    expect(screen.queryByTestId('assign-region-form')).toBeNull();
    await waitFor(() => {
      expect(open).toHaveFocus();
    });
    expect(open).toHaveAttribute('aria-expanded', 'false');

    await user.click(open);
    const form = screen.getByTestId('assign-region-form');
    const submit = within(form).getByRole('button', { name: s.assignment.submit });
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByTestId('form-summary')).toHaveFocus();
    });
    expect(
      within(within(form).getByTestId('form-summary')).getByRole('link', {
        name: s.validation.regionId.REQUIRED,
      }),
    ).toBeVisible();
    expect(requests.filter((r) => r.method === 'PUT')).toHaveLength(0);

    const select = within(form).getByLabelText(s.fields.assignRegion.label);
    await user.selectOptions(select, REGION.id);
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByTestId('form-error')).toHaveTextContent(errors.network);
    });
    await user.click(submit);
    await waitFor(() => {
      expect(select).toHaveAccessibleDescription(s.validation.regionId.OUTSIDE_REGION);
    });
    await waitFor(() => {
      expect(within(form).getByTestId('form-summary')).toHaveFocus();
    });
    await user.click(submit);
    await waitFor(() => {
      expect(select).toHaveAccessibleDescription(s.validation.regionId.MISMATCH);
    });
    const keys = requests
      .filter((r) => r.method === 'PUT')
      .map((r) => r.headers.get('Idempotency-Key'));
    // Same site and region: the key is reused after the uncertain network failure.
    expect(new Set(keys).size).toBe(1);
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(within(siteRow(UNASSIGNED)).getByTestId('site-region')).toHaveTextContent(
      s.sites.noRegion,
    );
  });

  it('offers a reload when the site already has another region', async () => {
    let assigned = false;
    const requests = stubApi((request, url) => {
      if (request.method === 'PUT' && url.pathname.endsWith('/region')) {
        assigned = true;
        return { status: 409, body: problem('SITE_REGION_ALREADY_ASSIGNED', 409) };
      }
      if (request.method === 'GET' && url.pathname.endsWith('/sites') && assigned)
        return {
          status: 200,
          body: { data: [{ ...UNASSIGNED, regionId: REGION2.id }, ASSIGNED] },
        };
      if (url.pathname.endsWith('/regions'))
        return { status: 200, body: { data: [REGION, REGION2] } };
      return undefined;
    });
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    await user.click(
      await screen.findByRole('button', {
        name: fill(s.assignment.open, { name: UNASSIGNED.name, code: UNASSIGNED.code }),
      }),
    );
    const form = screen.getByTestId('assign-region-form');
    await user.selectOptions(within(form).getByLabelText(s.fields.assignRegion.label), REGION.id);
    await user.click(within(form).getByRole('button', { name: s.assignment.submit }));
    await waitFor(() => {
      expect(within(form).getByTestId('form-error')).toHaveTextContent(
        errors.SITE_REGION_ALREADY_ASSIGNED,
      );
    });
    const sitesBefore = requests.filter((r) => new URL(r.url).pathname.endsWith('/sites')).length;
    await user.click(within(form).getByRole('button', { name: s.assignment.reload }));
    await waitFor(() => {
      expect(within(siteRow(UNASSIGNED)).getByTestId('site-region')).toHaveTextContent(
        fill(s.sites.region, { name: REGION2.name, code: REGION2.code }),
      );
    });
    expect(requests.filter((r) => new URL(r.url).pathname.endsWith('/sites')).length).toBe(
      sitesBefore + 1,
    );
  });

  it('creates a site with or without a region and keeps the pre-region payload', async () => {
    const created = { ...UNASSIGNED, id: '66666666-6666-4666-8666-666666666666', code: 'LIK' };
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/sites')
        ? { status: 201, body: created }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    const form = await screen.findByTestId('site-form');
    const submit = within(form).getByRole('button', { name: s.siteForm.submit });
    await user.type(within(form).getByLabelText(s.fields.code.label), 'LIK');
    await user.type(within(form).getByLabelText(s.fields.siteName.label), 'Likasi');
    await user.click(submit);
    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'POST')).toHaveLength(1);
    });
    expect(await requests.filter((r) => r.method === 'POST')[0]!.json()).not.toHaveProperty(
      'regionId',
    );
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.code.label)).toHaveValue('');
    });
    await user.type(within(form).getByLabelText(s.fields.code.label), 'LUB');
    await user.type(within(form).getByLabelText(s.fields.siteName.label), 'Lubumbashi');
    await user.selectOptions(within(form).getByLabelText(s.fields.region.label), REGION.id);
    await user.click(submit);
    await waitFor(() => {
      expect(requests.filter((r) => r.method === 'POST')).toHaveLength(2);
    });
    expect(await requests.filter((r) => r.method === 'POST')[1]!.json()).toMatchObject({
      legalEntityId: LE.id,
      regionId: REGION.id,
      code: 'LUB',
    });
  });

  it('maps site-creation region errors to the region field', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/sites')
        ? { status: 404, body: problem('REGION_NOT_FOUND', 404, { field: 'regionId' }) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage();
    await openLegalEntity(user, 'en');
    const form = await screen.findByTestId('site-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), 'LUB');
    await user.type(within(form).getByLabelText(s.fields.siteName.label), 'Lubumbashi');
    await user.selectOptions(within(form).getByLabelText(s.fields.region.label), REGION.id);
    await user.click(within(form).getByRole('button', { name: s.siteForm.submit }));
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.region.label)).toHaveAccessibleDescription(
        `${s.fields.region.help} ${s.validation.regionId.NOT_FOUND}`,
      );
    });
    await waitFor(() => {
      expect(within(form).getByTestId('form-summary')).toHaveFocus();
    });
  });
});

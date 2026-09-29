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
const SITE = {
  id: '33333333-3333-4333-8333-333333333333',
  legalEntityId: LE.id,
  code: 'GOMBE',
  name: 'Siège de la Gombe',
  timezone: 'Africa/Kinshasa',
  effectiveFrom: '2026-02-01',
  effectiveTo: null,
  createdAt: '2026-09-29T10:20:00Z',
};
const DEPT = {
  id: '44444444-4444-4444-8444-444444444444',
  siteId: SITE.id,
  code: 'RH',
  name: 'Ressources humaines et relations sociales',
  effectiveFrom: '2026-03-01',
  effectiveTo: '2026-12-31',
  createdAt: '2026-09-29T10:25:00Z',
};
const CC = {
  ...DEPT,
  id: '66666666-6666-4666-8666-666666666666',
  code: 'CC-01',
  name: 'Centre de coût Opérations',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
};
const TEAM = {
  id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  siteId: SITE.id,
  departmentId: DEPT.id,
  costCenterId: null,
  code: 'PAIE',
  name: 'Équipe paie et avantages',
  effectiveFrom: '2026-03-01',
  effectiveTo: '2026-12-31',
  createdAt: '2026-09-29T10:30:00Z',
};
const TEAM2 = {
  ...TEAM,
  id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
  code: 'RECRUT',
  name: 'Recrutement',
};
const CC_TEAM = {
  ...TEAM,
  id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  departmentId: null,
  costCenterId: CC.id,
  code: 'MAINT',
  name: 'Maintenance',
  effectiveFrom: '2026-04-01',
  effectiveTo: null,
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
        else if (url.pathname.endsWith('/sites')) reply = { status: 200, body: { data: [SITE] } };
        else if (url.pathname.endsWith('/departments'))
          reply = { status: 200, body: { data: [DEPT] } };
        else if (url.pathname.endsWith('/cost-centers'))
          reply = { status: 200, body: { data: [CC] } };
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

const isTeams = (url: URL) => url.pathname.endsWith('/teams');
const teamGets = (requests: Request[]) =>
  requests.filter((r) => r.method === 'GET' && isTeams(new URL(r.url))).map((r) => new URL(r.url));

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
  return Object.entries(values).reduce((text, [k, v]) => text.replaceAll(`{{${k}}}`, v), template);
}

async function openSite(user: ReturnType<typeof userEvent.setup>, locale: 'en' | 'fr') {
  const s = resources[locale].common.hierarchy;
  await user.click(
    await screen.findByRole('button', {
      name: fill(s.legalEntities.select, { name: LE.name, code: LE.code }),
    }),
  );
  await user.click(
    await screen.findByRole('button', {
      name: fill(s.sites.select, { name: SITE.name, code: SITE.code }),
    }),
  );
}

async function selectParent(
  user: ReturnType<typeof userEvent.setup>,
  kind: 'department' | 'costCenter',
  locale: 'en' | 'fr' = 'en',
) {
  const s = resources[locale].common.hierarchy;
  const unit = kind === 'department' ? DEPT : CC;
  const button = await screen.findByRole('button', {
    name: fill(s.teams[kind].select, { name: unit.name, code: unit.code }),
  });
  await user.click(button);
  return button;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('teams (%s)', (locale) => {
  const s = resources[locale].common.hierarchy;

  it('keeps site focus, then selects a department by type, name and code and focuses the Teams heading', async () => {
    const requests = stubApi(() => undefined);
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await openSite(user, locale);
    // Site selection keeps its focus target; no team list is loaded before a parent is chosen.
    const unitsHeading = within(await screen.findByTestId('site-units-section')).getByRole(
      'heading',
      { level: 2 },
    );
    await waitFor(() => {
      expect(unitsHeading).toHaveFocus();
    });
    const section = screen.getByTestId('team-section');
    expect(within(section).getByRole('heading', { level: 3 })).toHaveTextContent(s.teams.title);
    expect(within(section).getByTestId('team-none')).toHaveTextContent(s.teams.none);
    expect(teamGets(requests)).toHaveLength(0);

    const button = await selectParent(user, 'department', locale);
    expect(button).toHaveTextContent(s.teams.selectShort);
    expect(button).toHaveAttribute('aria-pressed', 'true');
    const heading = within(section).getByRole('heading', {
      level: 3,
      name: fill(s.teams.department.title, { name: DEPT.name, code: DEPT.code }),
    });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(await within(section).findByTestId('team-list-empty')).toHaveTextContent(
      s.teams.department.empty,
    );
    const form = within(section).getByTestId('team-form');
    expect(
      within(form).getByRole('heading', {
        name: fill(s.teams.department.formTitle, { name: DEPT.name, code: DEPT.code }),
      }),
    ).toBeVisible();
    expect(within(form).getByText(s.teams.department.periodHint)).toBeVisible();
    expect(within(form).getByLabelText(s.fields.teamName.label)).toBeInTheDocument();
    // Dates default to the parent's period.
    expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveValue(
      DEPT.effectiveFrom,
    );
    expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveValue(DEPT.effectiveTo);
    expect(container.querySelectorAll('[lang]')).toHaveLength(0);
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  it('switches to a cost center with a fresh list, form and heading', async () => {
    const requests = stubApi((request, url) => {
      if (!isTeams(url) || request.method !== 'GET') return undefined;
      return url.searchParams.get('costCenterId')
        ? { status: 200, body: { data: [CC_TEAM] } }
        : { status: 200, body: { data: [TEAM] } };
    });
    const user = userEvent.setup();
    await renderPage(locale);
    await openSite(user, locale);
    const department = await selectParent(user, 'department', locale);
    const section = screen.getByTestId('team-section');
    expect(await within(section).findByText(TEAM.name)).toBeVisible();

    const costCenter = await selectParent(user, 'costCenter', locale);
    expect(costCenter).toHaveAttribute('aria-pressed', 'true');
    expect(department).toHaveAttribute('aria-pressed', 'false');
    const heading = within(section).getByRole('heading', {
      level: 3,
      name: fill(s.teams.costCenter.title, { name: CC.name, code: CC.code }),
    });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(await within(section).findByText(CC_TEAM.name)).toBeVisible();
    expect(within(section).queryByText(TEAM.name)).toBeNull();
    const form = within(section).getByTestId('team-form');
    expect(within(form).getByText(s.teams.costCenter.periodHint)).toBeVisible();
    expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveValue(CC.effectiveFrom);
    expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveValue('');

    const [first, second] = teamGets(requests);
    expect(first!.searchParams.get('departmentId')).toBe(DEPT.id);
    expect(first!.searchParams.has('costCenterId')).toBe(false);
    expect(second!.searchParams.get('costCenterId')).toBe(CC.id);
    expect(second!.searchParams.has('departmentId')).toBe(false);
  });
});

describe('teams behaviour', () => {
  const s = resources.en.common.hierarchy;
  const errors = resources.en.common.errors;

  it('loads more with the same parent filter and focuses the first new team', async () => {
    const requests = stubApi((_, url) => {
      if (!isTeams(url)) return undefined;
      return url.searchParams.get('cursor') === 'opaque.cursor'
        ? { status: 200, body: { data: [TEAM2] } }
        : { status: 200, body: { data: [TEAM], nextCursor: 'opaque.cursor' } };
    });
    const user = userEvent.setup();
    await renderPage();
    await openSite(user, 'en');
    await selectParent(user, 'department');
    const list = await screen.findByTestId('team-list');
    await user.click(screen.getByRole('button', { name: s.teams.loadMore }));
    await waitFor(() => {
      expect(within(list).getByText(TEAM2.name).closest('[tabindex]')).toHaveFocus();
    });
    const [, next] = teamGets(requests);
    expect(next!.searchParams.get('departmentId')).toBe(DEPT.id);
    expect(next!.searchParams.get('cursor')).toBe('opaque.cursor');
  });

  it('creates a team with exactly one parent, focuses its row and announces it once', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && isTeams(url) ? { status: 201, body: CC_TEAM } : undefined,
    );
    const user = userEvent.setup();
    await renderPage();
    await openSite(user, 'en');
    await selectParent(user, 'costCenter');
    const form = await screen.findByTestId('team-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), ' maint ');
    await user.type(within(form).getByLabelText(s.fields.teamName.label), ` ${CC_TEAM.name} `);
    const submit = within(form).getByRole('button', { name: s.teamForm.submit });
    await user.click(submit);
    const message = fill(s.teams.created, { name: CC_TEAM.name, code: CC_TEAM.code });
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(message);
    });
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getAllByText(message)).toHaveLength(1);
    const row = within(screen.getByTestId('team-list'))
      .getByText(CC_TEAM.name)
      .closest('[tabindex]');
    await waitFor(() => {
      expect(row).toHaveFocus();
    });
    expect(submit).not.toHaveFocus();
    expect(within(form).getByLabelText(s.fields.code.label)).toHaveValue('');
    const post = requests.find((r) => r.method === 'POST')!;
    expect(post.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/);
    // Exactly one parent property; no site and no other parent are sent.
    expect(await post.json()).toEqual({
      costCenterId: CC.id,
      code: 'MAINT',
      name: CC_TEAM.name,
      effectiveFrom: CC.effectiveFrom,
      effectiveTo: null,
    });
  });

  it('keeps server order without duplicates when a created team sorts after the cursor', async () => {
    const ADM = { ...TEAM, id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd', code: 'ADM', name: 'Admin' };
    const TR = { ...TEAM, id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', code: 'TR', name: 'Transit' };
    stubApi((request, url) => {
      if (!isTeams(url)) return undefined;
      if (request.method === 'POST') return { status: 201, body: TR };
      return url.searchParams.get('cursor') === 'opaque.cursor'
        ? { status: 200, body: { data: [TEAM2, TR] } }
        : { status: 200, body: { data: [ADM, TEAM], nextCursor: 'opaque.cursor' } };
    });
    const user = userEvent.setup();
    await renderPage();
    await openSite(user, 'en');
    await selectParent(user, 'department');
    const list = await screen.findByTestId('team-list');
    const codes = () =>
      within(list)
        .getAllByRole('listitem')
        .map((item) => item.querySelector('.badge')?.textContent);
    const form = screen.getByTestId('team-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), TR.code);
    await user.type(within(form).getByLabelText(s.fields.teamName.label), TR.name);
    await user.click(within(form).getByRole('button', { name: s.teamForm.submit }));
    await waitFor(() => {
      expect(codes()).toEqual(['ADM', 'PAIE', 'TR']);
    });
    await user.click(screen.getByRole('button', { name: s.teams.loadMore }));
    await waitFor(() => {
      expect(codes()).toEqual(['ADM', 'PAIE', 'RECRUT', 'TR']);
    });
    expect(within(list).getAllByText(TR.name)).toHaveLength(1);
    await waitFor(() => {
      expect(within(list).getByText(TEAM2.name).closest('[tabindex]')).toHaveFocus();
    });
  });

  it('maps team errors to fields, reuses the key after a network failure and renews it after an edit', async () => {
    let posts = 0;
    const requests = stubApi((request, url) => {
      if (request.method !== 'POST' || !isTeams(url)) return undefined;
      posts++;
      if (posts === 1) return 'network';
      if (posts === 2)
        return { status: 409, body: problem('DUPLICATE_TEAM_CODE', 409, { field: 'code' }) };
      if (posts === 3)
        return {
          status: 400,
          body: problem('TEAM_PERIOD_OUTSIDE_DEPARTMENT', 400, { field: 'effectiveTo' }),
        };
      return {
        status: 400,
        body: problem('TEAM_PERIOD_OUTSIDE_COST_CENTER', 400, { field: 'effectiveFrom' }),
      };
    });
    const user = userEvent.setup();
    await renderPage();
    await openSite(user, 'en');
    await selectParent(user, 'department');
    const form = await screen.findByTestId('team-form');
    const submit = within(form).getByRole('button', { name: s.teamForm.submit });
    await user.type(within(form).getByLabelText(s.fields.code.label), 'PAIE');
    await user.type(within(form).getByLabelText(s.fields.teamName.label), TEAM.name);
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
    await user.type(within(form).getByLabelText(s.fields.code.label), 'PAIE-2');
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveAccessibleDescription(
        `${s.fields.effectiveTo.help} ${s.validation.effectiveTo.OUTSIDE_DEPARTMENT}`,
      );
    });
    await user.clear(within(form).getByLabelText(s.fields.code.label));
    await user.type(within(form).getByLabelText(s.fields.code.label), 'PAIE-3');
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveAccessibleDescription(
        `${s.fields.effectiveFrom.help} ${s.validation.effectiveFrom.OUTSIDE_COST_CENTER}`,
      );
    });
    const keys = requests
      .filter((r) => r.method === 'POST')
      .map((r) => r.headers.get('Idempotency-Key'));
    expect(keys[0]).toBe(keys[1]);
    expect(keys[2]).not.toBe(keys[1]);
    expect(keys[3]).not.toBe(keys[2]);
  });

  it.each(['DEPARTMENT_NOT_FOUND', 'TEAM_PARENT_REQUIRED', 'TEAM_PARENT_AMBIGUOUS'] as const)(
    'shows %s as a form error after client validation passes',
    async (code) => {
      const requests = stubApi((request, url) =>
        request.method === 'POST' && isTeams(url)
          ? { status: code === 'DEPARTMENT_NOT_FOUND' ? 404 : 400, body: problem(code, 400) }
          : undefined,
      );
      const user = userEvent.setup();
      await renderPage();
      await openSite(user, 'en');
      await selectParent(user, 'department');
      const form = await screen.findByTestId('team-form');
      const submit = within(form).getByRole('button', { name: s.teamForm.submit });
      await user.click(submit);
      const summary = within(form).getByTestId('form-summary');
      await waitFor(() => {
        expect(summary).toHaveFocus();
      });
      expect(within(summary).getByRole('link', { name: s.validation.code.REQUIRED })).toBeVisible();
      expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

      await user.type(within(form).getByLabelText(s.fields.code.label), 'OPS');
      await user.type(within(form).getByLabelText(s.fields.teamName.label), 'Opérations');
      await user.click(submit);
      await waitFor(() => {
        expect(within(form).getByTestId('form-error')).toHaveTextContent(errors[code]);
      });
      await waitFor(() => {
        expect(summary).toHaveFocus();
      });
    },
  );

  it('shows a list failure with a retry that keeps the parent filter', async () => {
    let gets = 0;
    const requests = stubApi((request, url) => {
      if (!isTeams(url) || request.method !== 'GET') return undefined;
      gets++;
      return gets === 1
        ? { status: 404, body: problem('COST_CENTER_NOT_FOUND', 404) }
        : { status: 200, body: { data: [CC_TEAM] } };
    });
    const user = userEvent.setup();
    await renderPage();
    await openSite(user, 'en');
    await selectParent(user, 'costCenter');
    const failure = await screen.findByTestId('team-list-error');
    expect(failure).toHaveTextContent(errors.COST_CENTER_NOT_FOUND);
    await user.click(within(failure).getByRole('button', { name: s.retry }));
    expect(await screen.findByText(CC_TEAM.name)).toBeVisible();
    for (const url of teamGets(requests)) {
      expect(url.searchParams.get('costCenterId')).toBe(CC.id);
      expect(url.searchParams.has('departmentId')).toBe(false);
    }
  });

  it('keeps the French locale through selection and submission', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && isTeams(url) ? { status: 201, body: TEAM } : undefined,
    );
    const fr = resources.fr.common.hierarchy;
    const user = userEvent.setup();
    await renderPage('fr');
    await openSite(user, 'fr');
    await selectParent(user, 'department', 'fr');
    const form = await screen.findByTestId('team-form');
    await user.type(within(form).getByLabelText(fr.fields.code.label), TEAM.code);
    await user.type(within(form).getByLabelText(fr.fields.teamName.label), TEAM.name);
    await user.click(within(form).getByRole('button', { name: fr.teamForm.submit }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(
        fill(fr.teams.created, { name: TEAM.name, code: TEAM.code }),
      );
    });
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(document.documentElement.lang).toBe('fr');
  });
});

import { resources } from '@divalhr/localization';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../layout/AppShell';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { HierarchyPage } from './HierarchyPage';
import { RequireRole } from './RequireRole';

const LE_A = {
  id: '11111111-1111-4111-8111-111111111111',
  code: 'KIN-01',
  name: 'Société Minière de Kinshasa',
  countryCode: 'CD',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  createdAt: '2026-09-29T10:15:00Z',
};
const LE_B = {
  ...LE_A,
  id: '22222222-2222-4222-8222-222222222222',
  code: 'LUB-01',
  name: 'Brasseries du Katanga',
  effectiveTo: '2026-12-31',
};
const SITE = {
  id: '33333333-3333-4333-8333-333333333333',
  legalEntityId: LE_A.id,
  code: 'GOMBE',
  name: 'Siège de la Gombe',
  timezone: 'Africa/Kinshasa',
  effectiveFrom: '2026-02-01',
  effectiveTo: null,
  createdAt: '2026-09-29T10:20:00Z',
};

type Reply = { status: number; body?: unknown } | 'network';
type Route = (request: Request, url: URL) => Reply | undefined;

function stubApi(route: Route) {
  const requests: Request[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const reply = route(request, new URL(request.url)) ?? { status: 500 };
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

async function expectNoAxeViolations(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('hierarchy page (%s)', (locale) => {
  const s = resources[locale].common.hierarchy;

  it('lists legal entities and exposes name and code on each selection control', async () => {
    stubApi((_, url) =>
      url.pathname.endsWith('/legal-entities')
        ? { status: 200, body: { data: [LE_A, LE_B] } }
        : undefined,
    );
    const { container } = await renderPage(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(s.title);
    const list = await screen.findByTestId('legal-entity-list');
    const buttons = within(list).getAllByRole('button');
    expect(buttons[0]).toHaveAccessibleName(
      s.legalEntities.select.replace('{{name}}', LE_A.name).replace('{{code}}', LE_A.code),
    );
    // The accessible name contains the visible label (WCAG 2.5.3).
    expect(buttons[0]).toHaveTextContent(s.legalEntities.selectShort);
    expect(buttons[0]).toHaveAttribute('aria-pressed', 'false');
    expect(container.querySelectorAll('[lang]')).toHaveLength(0);
    await expectNoAxeViolations(container);
  });

  it('shows an empty state and every form label in the selected language', async () => {
    stubApi(() => ({ status: 200, body: { data: [] } }));
    const { container } = await renderPage(locale);
    expect(await screen.findByTestId('legal-entity-list-empty')).toHaveTextContent(
      s.legalEntities.empty,
    );
    const form = screen.getByTestId('legal-entity-form');
    expect(within(form).getByLabelText(s.fields.code.label)).toHaveAccessibleDescription(
      s.fields.code.help,
    );
    expect(within(form).getByLabelText(s.fields.legalName.label)).toBeInTheDocument();
    expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveAttribute(
      'type',
      'date',
    );
    await expectNoAxeViolations(container);
  });
});

describe('hierarchy page behaviour', () => {
  const s = resources.en.common.hierarchy;

  it('moves focus to the sites heading on selection without trapping the keyboard', async () => {
    stubApi((_, url) =>
      url.pathname.endsWith('/legal-entities')
        ? { status: 200, body: { data: [LE_A] } }
        : { status: 200, body: { data: [SITE] } },
    );
    const user = userEvent.setup();
    await renderPage();
    const select = await screen.findByRole('button', {
      name: `Show sites for ${LE_A.name} (${LE_A.code})`,
    });
    await user.click(select);
    const heading = await screen.findByRole('heading', {
      level: 2,
      name: `Sites of ${LE_A.name} (${LE_A.code})`,
    });
    await waitFor(() => {
      expect(heading).toHaveFocus();
    });
    expect(heading).toHaveAttribute('tabindex', '-1');
    expect(select).toHaveAttribute('aria-pressed', 'true');
    await user.tab();
    expect(heading).not.toHaveFocus();
    await user.tab({ shift: true });
    await user.tab({ shift: true });
    expect(heading).not.toHaveFocus();
    expect(await screen.findByText(SITE.name)).toBeInTheDocument();
  });

  it('loads more with the server cursor and focuses the first new item', async () => {
    const requests = stubApi((_, url) =>
      url.searchParams.get('cursor') === 'opaque.cursor'
        ? { status: 200, body: { data: [LE_B] } }
        : { status: 200, body: { data: [LE_A], nextCursor: 'opaque.cursor' } },
    );
    const user = userEvent.setup();
    await renderPage();
    await user.click(await screen.findByRole('button', { name: s.legalEntities.loadMore }));
    const next = await screen.findByRole('button', {
      name: `Show sites for ${LE_B.name} (${LE_B.code})`,
    });
    await waitFor(() => {
      expect(next).toHaveFocus();
    });
    expect(screen.queryByRole('button', { name: s.legalEntities.loadMore })).toBeNull();
    expect(new URL(requests[1]!.url).searchParams.get('cursor')).toBe('opaque.cursor');
  });

  it('creates a legal entity with a normalized payload and announces it exactly once', async () => {
    const requests = stubApi((request) =>
      request.method === 'POST'
        ? {
            status: 201,
            body: { ...LE_A, code: 'NEW-01', id: '44444444-4444-4444-8444-444444444444' },
          }
        : { status: 200, body: { data: [] } },
    );
    const user = userEvent.setup();
    await renderPage();
    const form = await screen.findByTestId('legal-entity-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), '  new-01 ');
    await user.type(within(form).getByLabelText(s.fields.legalName.label), LE_A.name);
    await user.type(within(form).getByLabelText(s.fields.effectiveFrom.label), '2026-01-01');
    await user.click(within(form).getByRole('button', { name: s.legalEntityForm.submit }));

    const message = `Legal entity ${LE_A.name} (NEW-01) created.`;
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(message);
    });
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getAllByText(message)).toHaveLength(1);
    const post = requests.find((r) => r.method === 'POST')!;
    expect(post.headers.get('Idempotency-Key')).toMatch(/^web-[0-9a-f-]{36}$/);
    expect(await post.json()).toEqual({
      code: 'NEW-01',
      name: LE_A.name,
      countryCode: 'CD',
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
    });
    expect(screen.getByTestId('legal-entity-list')).toHaveTextContent('NEW-01');
  });

  it('maps duplicate codes to the field, focuses the summary and reuses the key on retry', async () => {
    let posts = 0;
    const requests = stubApi((request) => {
      if (request.method !== 'POST') return { status: 200, body: { data: [] } };
      posts++;
      return posts === 1
        ? 'network'
        : { status: 409, body: problem('DUPLICATE_LEGAL_ENTITY_CODE', 409, { field: 'code' }) };
    });
    const user = userEvent.setup();
    await renderPage();
    const form = await screen.findByTestId('legal-entity-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), 'DUP-01');
    await user.type(within(form).getByLabelText(s.fields.legalName.label), 'Doublon SARL');
    await user.type(within(form).getByLabelText(s.fields.effectiveFrom.label), '2026-01-01');
    const submit = within(form).getByRole('button', { name: s.legalEntityForm.submit });
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByTestId('form-error')).toBeInTheDocument();
    });
    await user.click(submit);
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.code.label)).toHaveAccessibleDescription(
        `${s.fields.code.help} ${s.validation.code.DUPLICATE_CODE}`,
      );
    });
    expect(within(form).getByTestId('form-summary')).toHaveFocus();
    // The summary is not a live region: focus announces it once.
    expect(within(form).getByTestId('form-summary')).not.toHaveAttribute('role');
    expect(within(form).queryAllByRole('alert')).toHaveLength(0);
    const keys = requests
      .filter((r) => r.method === 'POST')
      .map((r) => r.headers.get('Idempotency-Key'));
    expect(keys[0]).toBe(keys[1]);
  });

  it('validates on the client before calling the API', async () => {
    const requests = stubApi(() => ({ status: 200, body: { data: [] } }));
    const user = userEvent.setup();
    await renderPage();
    const form = await screen.findByTestId('legal-entity-form');
    await user.type(within(form).getByLabelText(s.fields.code.label), 'A B');
    await user.type(within(form).getByLabelText(s.fields.effectiveFrom.label), '2026-05-01');
    await user.type(within(form).getByLabelText(s.fields.effectiveTo.label), '2026-04-30');
    await user.click(within(form).getByRole('button', { name: s.legalEntityForm.submit }));
    const summary = within(form).getByTestId('form-summary');
    await waitFor(() => {
      expect(summary).toHaveFocus();
    });
    expect(within(summary).getByRole('link', { name: s.validation.code.FORMAT })).toBeVisible();
    expect(within(summary).getByRole('link', { name: s.validation.name.REQUIRED })).toBeVisible();
    expect(
      within(summary).getByRole('link', { name: s.validation.effectiveTo.BEFORE_START }),
    ).toBeVisible();
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
  });

  it('creates sites within the selected legal entity and maps containment errors', async () => {
    let sitePosts = 0;
    const requests = stubApi((request, url) => {
      if (url.pathname.endsWith('/legal-entities')) return { status: 200, body: { data: [LE_B] } };
      if (request.method === 'GET') return { status: 200, body: { data: [] } };
      sitePosts++;
      return sitePosts === 1
        ? {
            status: 400,
            body: problem('SITE_PERIOD_OUTSIDE_LEGAL_ENTITY', 400, { field: 'effectiveTo' }),
          }
        : { status: 201, body: { ...SITE, legalEntityId: LE_B.id } };
    });
    const user = userEvent.setup();
    await renderPage();
    await user.click(
      await screen.findByRole('button', { name: `Show sites for ${LE_B.name} (${LE_B.code})` }),
    );
    const form = await screen.findByTestId('site-form');
    const zone = within(form).getByLabelText(s.fields.timezone.label);
    expect(
      within(zone)
        .getAllByRole('option')
        .map((o) => o.getAttribute('value')),
    ).toEqual(['Africa/Kinshasa', 'Africa/Lubumbashi']);
    // Dates default to the parent's period.
    expect(within(form).getByLabelText(s.fields.effectiveFrom.label)).toHaveValue('2026-01-01');
    expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveValue('2026-12-31');

    await user.type(within(form).getByLabelText(s.fields.code.label), 'gombe');
    await user.type(within(form).getByLabelText(s.fields.siteName.label), SITE.name);
    await user.click(within(form).getByRole('button', { name: s.siteForm.submit }));
    await waitFor(() => {
      expect(within(form).getByLabelText(s.fields.effectiveTo.label)).toHaveAccessibleDescription(
        `${s.fields.effectiveTo.help} ${s.validation.effectiveTo.OUTSIDE_PARENT}`,
      );
    });
    await user.click(within(form).getByRole('button', { name: s.siteForm.submit }));
    await waitFor(() => {
      expect(screen.getByTestId('announcer')).toHaveTextContent(
        `Site ${SITE.name} (${SITE.code}) created.`,
      );
    });
    const sitePost = requests.filter((r) => r.method === 'POST' && r.url.endsWith('/sites'));
    const body = (await sitePost[0]!.clone().json()) as Record<string, unknown>;
    expect(body.legalEntityId).toBe(LE_B.id);
    expect(body.code).toBe('GOMBE');
  });

  it('shows list failures with a retry and never exposes the cursor', async () => {
    let calls = 0;
    stubApi(() => {
      calls++;
      return calls === 1
        ? { status: 400, body: problem('CURSOR_INVALID', 400) }
        : { status: 200, body: { data: [LE_A] } };
    });
    const user = userEvent.setup();
    await renderPage();
    const error = await screen.findByTestId('legal-entity-list-error');
    expect(error).toHaveTextContent(resources.en.common.errors.CURSOR_INVALID);
    await user.click(within(error).getByRole('button', { name: s.retry }));
    expect(await screen.findByText(LE_A.name)).toBeInTheDocument();
  });

  it('shows the navigation entry only to tenant administrators', async () => {
    stubApi(() => ({ status: 200, body: { data: [] } }));
    const nav = resources.en.common.nav.hierarchy;
    const { unmount } = await renderWithSession(
      <AppShell environment="test">
        <p>content</p>
      </AppShell>,
      sessionWithRoles(['tenant-admin']),
    );
    expect(screen.getByRole('link', { name: nav })).toHaveAttribute('href', '/admin/hierarchy');
    unmount();
    await renderWithSession(
      <AppShell environment="test">
        <p>content</p>
      </AppShell>,
      sessionWithRoles(['employee', 'platform-admin']),
    );
    expect(screen.queryByRole('link', { name: nav })).toBeNull();
  });

  it('refuses the page to other roles without calling the API', async () => {
    const requests = stubApi(() => ({ status: 200, body: { data: [] } }));
    for (const roles of [['employee'], ['platform-admin']] as const) {
      const { unmount } = await renderWithSession(
        <RequireRole
          requiredRole="tenant-admin"
          deniedKey="hierarchy.unauthorized"
          signInKey="hierarchy.signInRequired"
        >
          <HierarchyPage />
        </RequireRole>,
        sessionWithRoles([...roles]),
      );
      expect(screen.getByTestId('not-authorized')).toHaveTextContent(s.unauthorized);
      unmount();
    }
    await act(() => Promise.resolve());
    expect(requests).toHaveLength(0);
  });
});

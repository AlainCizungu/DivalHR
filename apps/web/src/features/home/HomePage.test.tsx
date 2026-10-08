import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { SessionState } from '../../app/SessionProvider';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { HomePage } from './HomePage';

const en = resources.en.common;
const fr = resources.fr.common;

const renderHome = (session: SessionState, locale: 'en' | 'fr' = 'en') =>
  renderWithSession(<HomePage />, session, locale);

const cardLinks = () =>
  screen
    .getAllByRole('listitem')
    .map((item) => within(item).queryByRole('link'))
    .filter((link): link is HTMLElement => link !== null)
    .map((link) => [link.textContent, link.getAttribute('href')]);

/**
 * The fabricated-data guard (D10): homes render no figures, except inside the one approved live
 * card, "Contracts needing attention" (MVP-031A, C3b), whose figures come from the server.
 */
function expectNoFigures(container: HTMLElement) {
  const copy = container.cloneNode(true) as HTMLElement;
  copy.querySelector('[data-testid="home-contract-attention"]')?.remove();
  expect(copy.textContent).not.toMatch(/\d/);
}

const summaryCounts = { expired: 2, next30Days: 5, days31To60: 1, days61To90: 0, total: 8 };
let summaryReply: { status: number; body: unknown } = {
  status: 200,
  body: { asOf: '2026-10-07', timezone: 'Africa/Kinshasa', counts: summaryCounts },
};
let summaryRequests = 0;

beforeEach(() => {
  summaryRequests = 0;
  summaryReply = {
    status: 200,
    body: { asOf: '2026-10-07', timezone: 'Africa/Kinshasa', counts: summaryCounts },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      const path = new URL(request.url).pathname.replace(/^.*\/api\/v1/u, '');
      if (path === '/contract-expirations/summary') summaryRequests += 1;
      const reply =
        path === '/contract-expirations/summary'
          ? summaryReply
          : { status: 404, body: { code: 'NOT_FOUND' } };
      return Promise.resolve(
        new Response(JSON.stringify(reply.body), {
          status: reply.status,
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    }),
  );
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('role homes', () => {
  it('gives an organization administrator quick actions, modules and the roadmap', async () => {
    const { container } = await renderHome(sessionWithRoles(['tenant-admin']));
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.home.tenant.title);
    const quick = screen.getByTestId('quick-actions');
    expect(
      within(quick)
        .getAllByRole('link')
        .map((link) => [link.textContent, link.getAttribute('href')]),
    ).toEqual([
      [en.home.tenant.actions.importEmployees, '/admin/people/import'],
      [en.home.tenant.actions.inviteUser, '/admin/users'],
      [en.home.tenant.actions.createTemplate, '/admin/contract-templates'],
      [en.home.tenant.actions.reviewAccess, '/admin/access'],
    ]);
    for (const testId of [
      'home-card-employees',
      'home-card-hierarchy',
      'home-card-users',
      'home-card-access-review',
      'home-card-contract-templates',
      'home-card-employee-import',
    ]) {
      expect(within(screen.getByTestId(testId)).getAllByRole('link')).toHaveLength(1);
    }
    expect(within(screen.getByTestId('roadmap')).getAllByRole('listitem')).toHaveLength(6);
    expect(screen.queryByTestId('home-my-contracts')).toBeNull();
    await screen.findByTestId('attention-count');
    expectNoFigures(container);
  });

  it('gives an employee My contracts and their own roadmap', async () => {
    const { container } = await renderHome(sessionWithRoles(['employee']), 'fr');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(fr.home.employee.title);
    expect(
      within(screen.getByTestId('home-my-contracts')).getByRole('link', {
        name: fr.home.employee.contractsAction,
      }),
    ).toHaveAttribute('href', '/me/contracts');
    const roadmap = screen.getByTestId('roadmap');
    const items = within(roadmap).getAllByRole('listitem');
    expect(items).toHaveLength(4);
    for (const item of items) expect(item).toHaveTextContent(fr.roadmap.badge);
    expect(roadmap).toHaveTextContent(fr.roadmap.items.payslips.title);
    expect(screen.queryByTestId('quick-actions')).toBeNull();
    expect(screen.queryByTestId('home-contract-attention')).toBeNull();
    expect(summaryRequests).toBe(0);
    expectNoFigures(container);
  });

  it('gives a platform administrator organization set-up and the system status shortcut', async () => {
    const { container } = await renderHome({
      kind: 'ready',
      session: { tenantId: null, roles: ['platform-admin'] },
    });
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.home.platform.title);
    expect(cardLinks()).toEqual([
      [en.home.platform.createOrganization.title, '/admin/organizations/new'],
      [en.home.platform.firstAdmin.title, '/admin/organizations/first-admin'],
      [en.nav.status, '/status'],
    ]);
    expect(screen.queryByTestId('roadmap')).toBeNull();
    expect(screen.queryByTestId('home-contract-attention')).toBeNull();
    expect(summaryRequests).toBe(0);
    expectNoFigures(container);
  });

  it('combines the sections of a user with several roles under one heading (D13)', async () => {
    await renderHome(sessionWithRoles(['employee', 'tenant-admin']));
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(en.home.tenant.title);
    expect(screen.getByTestId('quick-actions')).toBeInTheDocument();
    expect(screen.getByTestId('home-my-contracts')).toBeInTheDocument();
    expect(screen.getAllByTestId('roadmap')).toHaveLength(1);
  });

  it('renders "Coming later" modules as text, never as controls (D7)', async () => {
    for (const roles of [['tenant-admin'], ['employee']] as const) {
      const { unmount } = await renderHome(sessionWithRoles([...roles]));
      const roadmap = screen.getByTestId('roadmap');
      expect(
        roadmap.querySelectorAll(
          'a, button, input, select, textarea, [role], [tabindex], [aria-disabled]',
        ),
      ).toHaveLength(0);
      unmount();
    }
  });
});

describe.each(['en', 'fr'] as const)('Contracts needing attention (%s)', (locale) => {
  const a = resources[locale].common.home.tenant.attention;

  it('shows the server count and the expired part, with a link to the queue', async () => {
    await renderHome(sessionWithRoles(['tenant-admin']), locale);
    const card = screen.getByTestId('home-contract-attention');
    expect(within(card).getByRole('heading', { name: a.title })).toBeInTheDocument();
    expect(await within(card).findByTestId('attention-count')).toHaveTextContent(
      `${a.count_other.replace('{{count}}', '8')} ${a.expired_other.replace('{{count}}', '2')}`,
    );
    expect(within(card).getByRole('link', { name: a.open })).toHaveAttribute(
      'href',
      '/admin/contract-expirations',
    );
    expect(summaryRequests).toBe(1);
  });

  it('says when nothing needs attention', async () => {
    summaryReply = {
      status: 200,
      body: {
        asOf: '2026-10-07',
        timezone: 'Africa/Kinshasa',
        counts: { expired: 0, next30Days: 0, days31To60: 0, days61To90: 0, total: 0 },
      },
    };
    await renderHome(sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findByTestId('attention-count')).toHaveTextContent(a.none);
  });

  it('keeps the link when the count cannot be loaded', async () => {
    summaryReply = { status: 429, body: { code: 'RATE_LIMITED' } };
    const { container } = await renderHome(sessionWithRoles(['tenant-admin']), locale);
    expect(await screen.findByTestId('attention-failed')).toHaveTextContent(a.failed);
    expect(screen.getByRole('link', { name: a.open })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\d/);
  });

  it('announces loading before the count arrives', async () => {
    await renderHome(sessionWithRoles(['tenant-admin']), locale);
    expect(screen.getByTestId('home-contract-attention')).toHaveTextContent(a.loading);
    await waitFor(() => {
      expect(screen.getByTestId('attention-count')).toBeInTheDocument();
    });
  });
});

describe('session states on Home', () => {
  it('shows anonymous visitors the landing page (UI-002)', async () => {
    await renderHome({ kind: 'anonymous' });
    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      en.landing.hero.line1,
    );
    expect(screen.getByRole('button', { name: en.landing.hero.signIn })).toBeInTheDocument();
  });

  it('announces loading', async () => {
    await renderHome({ kind: 'loading' });
    expect(screen.getByRole('status')).toHaveTextContent(en.session.loading);
  });

  it('explains a failed session with a retry and sign-out', async () => {
    await renderHome({ kind: 'error', code: 'ACCESS_DENIED' });
    expect(screen.getByRole('alert')).toHaveTextContent(en.errors.ACCESS_DENIED);
    expect(screen.getByRole('button', { name: en.home.retry })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: en.auth.signOut })).toBeInTheDocument();
  });

  it('tells a signed-in user without a role that they have no access yet', async () => {
    await renderHome(sessionWithRoles([]), 'fr');
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(fr.home.noAccess.title);
    expect(screen.queryByRole('link')).toBeNull();
  });
});

describe.each(['en', 'fr'] as const)('accessibility (%s)', (locale) => {
  it.each([['tenant-admin'], ['employee'], ['platform-admin']] as const)(
    'the %s home has no detectable violations',
    async (role) => {
      const user = userEvent.setup();
      const { container } = await renderHome(sessionWithRoles([role]), locale);
      await user.tab();
      const result = await axe.run(container, {
        rules: { 'color-contrast': { enabled: false } },
      });
      expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
    },
  );
});

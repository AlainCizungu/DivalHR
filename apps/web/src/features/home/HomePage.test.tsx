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
let requestedPaths: string[] = [];

beforeEach(() => {
  summaryRequests = 0;
  requestedPaths = [];
  summaryReply = {
    status: 200,
    body: { asOf: '2026-10-07', timezone: 'Africa/Kinshasa', counts: summaryCounts },
  };
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      const path = new URL(request.url).pathname.replace(/^.*\/api\/v1/u, '');
      requestedPaths.push(path);
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

const TENANT_DESTINATIONS = {
  people: [
    ['employees', '/admin/people'],
    ['leavePolicies', '/admin/leave-policies'],
    ['leaveApprovals', '/admin/leave-approvals'],
    ['workSchedules', '/admin/work-schedules'],
  ],
  governance: [
    ['hierarchy', '/admin/hierarchy'],
    ['contractTemplates', '/admin/contract-templates'],
    ['users', '/admin/users'],
    ['accessReview', '/admin/access'],
  ],
} as const;

const groupLinks = (testId: string) =>
  within(screen.getByTestId(testId))
    .getAllByRole('link')
    .map((link) => [link.textContent, link.getAttribute('href')]);

describe.each(['en', 'fr'] as const)('role homes (%s)', (locale) => {
  const c = resources[locale].common;

  it('gives an organization administrator quick actions, two groups and the roadmap', async () => {
    const { container } = await renderHome(sessionWithRoles(['tenant-admin']), locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(c.home.tenant.title);
    const quick = screen.getByTestId('quick-actions');
    expect(
      within(quick)
        .getAllByRole('link')
        .map((link) => [link.textContent, link.getAttribute('href')]),
    ).toEqual([
      [c.home.tenant.actions.importEmployees, '/admin/people/import'],
      [c.home.tenant.actions.reviewLeave, '/admin/leave-approvals'],
      [c.home.tenant.actions.openSchedules, '/admin/work-schedules'],
      [c.home.tenant.actions.inviteUser, '/admin/users'],
    ]);
    for (const group of ['people', 'governance'] as const) {
      const section = screen.getByTestId(`home-group-${group}`);
      expect(within(section).getByRole('heading', { level: 2 })).toHaveTextContent(
        c.home.tenant.groups[group].title,
      );
      expect(groupLinks(`home-group-${group}`)).toEqual(
        TENANT_DESTINATIONS[group].map(([id, to]) => [c.home.tenant.cards[id].title, to]),
      );
    }
    // The live card stays; shipped capabilities left the roadmap (UI3-4).
    expect(screen.getByTestId('home-contract-attention')).toBeInTheDocument();
    const roadmap = screen.getByTestId('roadmap');
    expect(within(roadmap).getAllByRole('listitem')).toHaveLength(3);
    for (const title of [
      c.roadmap.items.attendance.title,
      c.roadmap.items.payrollInputs.title,
      c.roadmap.items.expenses.title,
    ]) {
      expect(roadmap).toHaveTextContent(title);
    }
    // No employee destination for an administrator who is not an employee (UI3-6).
    expect(screen.queryByTestId('home-my-work')).toBeNull();
    expect(screen.queryByTestId('home-leave-approvals')).toBeNull();
    expect(container.querySelector('a[href^="/me/"]')).toBeNull();
    await screen.findByTestId('attention-count');
    // UI3-7: the only request is the approved contract summary.
    expect(requestedPaths).toEqual(['/contract-expirations/summary']);
    expectNoFigures(container);
  });

  it('gives an employee My work, the approvals card and their own roadmap', async () => {
    const { container } = await renderHome(sessionWithRoles(['employee']), locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(c.home.employee.title);
    const work = screen.getByTestId('home-my-work');
    expect(within(work).getByRole('heading', { level: 2 })).toHaveTextContent(
      c.home.employee.myWork,
    );
    expect(groupLinks('home-my-work')).toEqual([
      [c.nav.myLeave, '/me/leave'],
      [c.nav.mySchedule, '/me/schedule'],
      [c.nav.myAttendance, '/me/attendance'],
      [c.nav.myContracts, '/me/contracts'],
    ]);
    const approvals = screen.getByTestId('home-leave-approvals');
    expect(within(approvals).getByRole('heading', { level: 2 })).toHaveTextContent(
      c.home.employee.approvals.title,
    );
    expect(approvals).toHaveTextContent(c.home.employee.approvals.description);
    expect(
      within(approvals).getByRole('link', { name: c.home.employee.approvals.action }),
    ).toHaveAttribute('href', '/me/leave/approvals');
    // MVP-051: the shift-substitution card, worded conditionally and loading nothing.
    const substitutions = screen.getByTestId('home-shift-substitutions');
    expect(within(substitutions).getByRole('heading', { level: 2 })).toHaveTextContent(
      c.home.employee.substitutions.title,
    );
    expect(substitutions).toHaveTextContent(c.home.employee.substitutions.description);
    expect(
      within(substitutions).getByRole('link', { name: c.home.employee.substitutions.action }),
    ).toHaveAttribute('href', '/me/schedule/substitutions');
    const roadmap = screen.getByTestId('roadmap');
    const items = within(roadmap).getAllByRole('listitem');
    expect(items).toHaveLength(3);
    for (const item of items) expect(item).toHaveTextContent(c.roadmap.badge);
    expect(roadmap).toHaveTextContent(c.roadmap.items.payslips.title);
    expect(roadmap).not.toHaveTextContent(c.nav.myLeave);
    // No administrator destination for an employee (UI3-6), and no data is loaded (UI3-7).
    expect(screen.queryByTestId('quick-actions')).toBeNull();
    expect(screen.queryByTestId('home-contract-attention')).toBeNull();
    expect(container.querySelector('a[href^="/admin/"]')).toBeNull();
    expect(requestedPaths).toEqual([]);
    expectNoFigures(container);
  });

  it('combines both sections for a user with both roles, without duplicate names (UI3-6)', async () => {
    const { container } = await renderHome(sessionWithRoles(['employee', 'tenant-admin']), locale);
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(c.home.tenant.title);
    expect(screen.getByTestId('quick-actions')).toBeInTheDocument();
    expect(screen.getByTestId('home-group-people')).toBeInTheDocument();
    expect(screen.getByTestId('home-my-work')).toBeInTheDocument();
    expect(screen.getByTestId('home-leave-approvals')).toBeInTheDocument();
    expect(screen.getAllByTestId('roadmap')).toHaveLength(1);
    const headings = screen.getAllByRole('heading').map((heading) => heading.textContent);
    expect(new Set(headings).size).toBe(headings.length);
    const regions = [...container.querySelectorAll('section[aria-labelledby]')].map(
      (region) =>
        document.getElementById(region.getAttribute('aria-labelledby') ?? '')?.textContent,
    );
    expect(new Set(regions).size).toBe(regions.length);
  });
});

describe('role homes', () => {
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
    expect(screen.queryByTestId('home-my-work')).toBeNull();
    expect(summaryRequests).toBe(0);
    expectNoFigures(container);
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
  it.each([
    ['tenant-admin'],
    ['employee'],
    ['platform-admin'],
    ['employee', 'tenant-admin'],
  ] as const)('the %s home has no detectable violations', async (...roles) => {
    const user = userEvent.setup();
    const { container } = await renderHome(sessionWithRoles([...roles]), locale);
    await user.tab();
    const result = await axe.run(container, {
      rules: { 'color-contrast': { enabled: false } },
    });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });
});

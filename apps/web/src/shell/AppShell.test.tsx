import { resources } from '@divalhr/localization';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { Route, Routes, useLocation } from 'react-router';
import { describe, expect, it } from 'vitest';
import type { SessionState } from '../app/SessionProvider';
import type { Environment } from '../config/runtime';
import { renderWithSession, sessionWithRoles } from '../test/renderWithSession';
import { AppShell } from './AppShell';

const en = resources.en.common;
const fr = resources.fr.common;

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>;
}

async function renderShell(
  session: SessionState,
  {
    path = '/',
    locale = 'en',
    environment = 'development',
  }: { path?: string; locale?: 'en' | 'fr'; environment?: Environment } = {},
) {
  return renderWithSession(
    <AppShell environment={environment}>
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </AppShell>,
    session,
    locale,
    path,
  );
}

const primaryNav = (strings = en) => screen.getByRole('navigation', { name: strings.nav.primary });
/** Visible link labels (the rail tooltip repeats the label and is aria-hidden). */
const labelOf = (link: HTMLElement) => link.querySelector('.nav-link__label')?.textContent;
const navLinkNames = (strings = en) =>
  within(primaryNav(strings)).getAllByRole('link').map(labelOf);

async function expectNoAxeViolations(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

describe('signed-in frame', () => {
  it('has the landmarks, the skip link and one primary navigation', async () => {
    await renderShell(sessionWithRoles(['tenant-admin']));
    expect(screen.getByRole('link', { name: en.nav.skipToContent })).toHaveAttribute(
      'href',
      '#main-content',
    );
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveAttribute('id', 'main-content');
    expect(screen.getAllByRole('navigation', { name: en.nav.primary })).toHaveLength(1);
  });

  it.each([
    [
      'tenant-admin',
      ['tenant-admin'] as const,
      [
        en.nav.home,
        en.nav.employees,
        en.nav.employeeImport,
        en.nav.leavePolicies,
        en.nav.leaveApprovals,
        en.nav.leaveRoutingExceptions,
        en.nav.hierarchy,
        en.nav.contractTemplates,
        en.nav.contractExpirations,
        en.nav.users,
        en.nav.accessReview,
      ],
    ],
    [
      'employee',
      ['employee'] as const,
      [en.nav.home, en.nav.myContracts, en.nav.myLeave, en.nav.myLeaveApprovals],
    ],
    [
      'platform-admin',
      ['platform-admin'] as const,
      [en.nav.home, en.nav.platformAdmin, en.nav.firstAdmin],
    ],
  ])('shows a %s exactly their destinations', async (_name, roles, expected) => {
    await renderShell(sessionWithRoles([...roles]));
    expect(navLinkNames()).toEqual(expected);
  });

  it('never shows System status in the primary navigation', async () => {
    await renderShell(sessionWithRoles(['employee', 'platform-admin', 'tenant-admin']));
    expect(within(primaryNav()).queryByRole('link', { name: en.nav.status })).toBeNull();
  });

  it('shows Home only and a status message while the session loads', async () => {
    await renderShell({ kind: 'loading' }, { path: '/admin/people' });
    expect(navLinkNames()).toEqual([en.nav.home]);
    expect(screen.getByRole('button', { name: new RegExp(en.shell.accountMenu) })).toBeDisabled();
    expect(screen.queryByTestId('workspace-context')).toBeNull();
  });

  it('shows Home only when the session failed to load', async () => {
    await renderShell({ kind: 'error', code: 'generic' });
    expect(navLinkNames()).toEqual([en.nav.home]);
  });

  it.each([
    ['/admin/people/3b0d2f4e-0000-4000-8000-000000000001', en.nav.employees],
    ['/admin/people/import', en.nav.employeeImport],
    ['/admin/contract-templates/3b0d2f4e-0000-4000-8000-000000000002', en.nav.contractTemplates],
    ['/admin/contract-expirations', en.nav.contractExpirations],
    ['/admin/leave-policies', en.nav.leavePolicies],
  ])('marks the current page for %s', async (path, expected) => {
    await renderShell(sessionWithRoles(['tenant-admin']), { path });
    const current = within(primaryNav())
      .getAllByRole('link')
      .filter((link) => link.getAttribute('aria-current') === 'page');
    expect(current.map(labelOf)).toEqual([expected]);
  });

  it('shows breadcrumbs from the route registry, with group labels as plain text', async () => {
    await renderShell(sessionWithRoles(['tenant-admin']), {
      path: '/admin/people/3b0d2f4e-0000-4000-8000-000000000001',
    });
    const crumbs = screen.getByRole('navigation', { name: en.shell.breadcrumb });
    expect(
      within(crumbs)
        .getAllByRole('listitem')
        .map((item) => item.textContent),
    ).toEqual([en.nav.home, en.nav.groups.people, en.nav.employees, en.shell.crumb.employeeRecord]);
    expect(
      within(crumbs)
        .getAllByRole('link')
        .map((link) => link.getAttribute('href')),
    ).toEqual(['/', '/admin/people']);
    expect(within(crumbs).getByText(en.shell.crumb.employeeRecord)).toHaveAttribute(
      'aria-current',
      'page',
    );
  });

  it('shows no breadcrumbs on Home', async () => {
    await renderShell(sessionWithRoles(['employee']));
    expect(screen.queryByRole('navigation', { name: en.shell.breadcrumb })).toBeNull();
  });

  it('names the workspace without inventing an organization name (D1)', async () => {
    await renderShell(sessionWithRoles(['tenant-admin']));
    expect(screen.getByTestId('workspace-context')).toHaveTextContent(
      en.shell.workspaceOrganization,
    );
    await act(() => Promise.resolve());
  });
});

describe('environment badge (UI1-2)', () => {
  it.each([
    ['development', 'en', en.shell.environment.development],
    ['test', 'en', en.shell.environment.test],
    ['staging', 'en', en.shell.environment.staging],
    ['development', 'fr', fr.shell.environment.development],
    ['test', 'fr', fr.shell.environment.test],
    ['staging', 'fr', fr.shell.environment.staging],
  ] as const)('labels %s in %s', async (environment, locale, label) => {
    await renderShell(sessionWithRoles(['employee']), { environment, locale });
    const badge = screen.getByTestId('environment-badge');
    expect(badge).toHaveTextContent(label);
    expect(within(badge).queryByRole('button')).toBeNull();
  });

  it('shows nothing in production, signed in or not', async () => {
    const { unmount } = await renderShell(sessionWithRoles(['employee']), {
      environment: 'production',
    });
    expect(screen.queryByTestId('environment-badge')).toBeNull();
    unmount();
    await renderShell({ kind: 'anonymous' }, { environment: 'production' });
    expect(screen.queryByTestId('environment-badge')).toBeNull();
  });

  it('never says the data is test data', async () => {
    for (const strings of [en, fr]) {
      expect(JSON.stringify(strings)).not.toMatch(/test data only|données de test uniquement/i);
    }
    await act(() => Promise.resolve());
  });
});

describe('sidebar collapse (D8)', () => {
  it('collapses to a rail that keeps every accessible name, and remembers only that', async () => {
    const user = userEvent.setup();
    await renderShell(sessionWithRoles(['tenant-admin']));
    const toggle = screen.getByRole('button', { name: en.shell.collapseNavigation });
    expect(toggle).toHaveAttribute('aria-expanded', 'true');
    await user.click(toggle);
    const expand = screen.getByRole('button', { name: en.shell.expandNavigation });
    expect(expand).toHaveAttribute('aria-expanded', 'false');
    expect(expand).toHaveFocus();
    expect(navLinkNames()).toContain(en.nav.accessReview);
    expect(Object.keys(window.localStorage)).toEqual(['divalhr.sidebar']);
    expect(window.localStorage.getItem('divalhr.sidebar')).toBe('collapsed');
    await user.click(expand);
    expect(window.localStorage.getItem('divalhr.sidebar')).toBe('expanded');
  });

  it('starts collapsed when the preference says so', async () => {
    window.localStorage.setItem('divalhr.sidebar', 'collapsed');
    await renderShell(sessionWithRoles(['employee']));
    expect(screen.getByRole('button', { name: en.shell.expandNavigation })).toBeInTheDocument();
  });
});

describe('mobile navigation drawer', () => {
  it('opens with focus on the first link and returns focus to the menu button on close', async () => {
    const user = userEvent.setup();
    await renderShell(sessionWithRoles(['employee']));
    const menuButton = screen.getByRole('button', { name: en.shell.openNavigation });
    expect(menuButton).toHaveAttribute('aria-expanded', 'false');
    await user.click(menuButton);
    const dialog = screen.getByRole('dialog', { name: en.nav.primary });
    expect(menuButton).toHaveAttribute('aria-expanded', 'true');
    expect(within(dialog).getByRole('link', { name: en.nav.home })).toHaveFocus();
    await user.click(within(dialog).getByRole('button', { name: en.shell.closeNavigation }));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(menuButton).toHaveFocus();
  });

  it('closes when a link is followed', async () => {
    const user = userEvent.setup();
    await renderShell(sessionWithRoles(['employee']));
    await user.click(screen.getByRole('button', { name: en.shell.openNavigation }));
    const dialog = screen.getByRole('dialog', { name: en.nav.primary });
    await user.click(within(dialog).getByRole('link', { name: en.nav.myContracts }));
    expect(screen.getByTestId('where')).toHaveTextContent('/me/contracts');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByRole('button', { name: en.shell.openNavigation })).toHaveFocus();
  });
});

describe('account menu', () => {
  it('shows the roles and organization ID, closes on Escape and restores focus', async () => {
    const user = userEvent.setup();
    await renderShell(sessionWithRoles(['employee', 'tenant-admin']));
    const button = screen.getByRole('button', { name: new RegExp(en.shell.accountMenu) });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('session-roles')).toHaveTextContent(
      `${en.session.role.employee}, ${en.session.role['tenant-admin']}`,
    );
    expect(screen.getByTestId('session-tenant')).toHaveTextContent(
      '00000000-0000-4000-8000-00000000000a',
    );
    expect(screen.getByRole('link', { name: en.nav.status })).toHaveAttribute('href', '/status');
    await user.keyboard('{Escape}');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
  });

  it('says "No organization" for a platform administrator without a tenant', async () => {
    await renderShell({ kind: 'ready', session: { tenantId: null, roles: ['platform-admin'] } });
    expect(screen.getByTestId('session-tenant')).toHaveTextContent(en.session.noTenant);
    expect(screen.getByTestId('workspace-context')).toHaveTextContent(en.shell.workspacePlatform);
  });

  it('switches the theme from the menu', async () => {
    const user = userEvent.setup();
    await renderShell(sessionWithRoles(['employee']));
    await user.click(screen.getByRole('button', { name: new RegExp(en.shell.accountMenu) }));
    await user.click(screen.getByRole('button', { name: en.theme.dark }));
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(screen.getByRole('button', { name: en.theme.dark })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});

describe('public frame', () => {
  it.each(['/invitation', '/auth/callback'])(
    'keeps %s free of the signed-in sidebar and menu even when signed in',
    async (path) => {
      await renderShell(sessionWithRoles(['tenant-admin']), { path });
      expect(screen.queryByRole('navigation', { name: en.nav.primary })).toBeNull();
      expect(screen.queryByRole('button', { name: new RegExp(en.shell.accountMenu) })).toBeNull();
      expect(screen.getByRole('main')).toBeInTheDocument();
    },
  );

  it('shows anonymous visitors the public frame with sign-in, language and theme', async () => {
    await renderShell({ kind: 'anonymous' }, { path: '/admin/people' });
    expect(screen.queryByRole('navigation', { name: en.nav.primary })).toBeNull();
    expect(screen.getByRole('button', { name: en.auth.signIn })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: en.locale.label })).toBeInTheDocument();
    expect(screen.getByLabelText(en.theme.label)).toBeInTheDocument();
  });
});

describe.each([
  ['en', en],
  ['fr', fr],
] as const)('accessibility (%s)', (locale, strings) => {
  it('has no detectable violations in the expanded, collapsed and drawer states', async () => {
    const user = userEvent.setup();
    const { container } = await renderShell(sessionWithRoles(['tenant-admin', 'employee']), {
      locale,
      path: '/admin/people/import',
    });
    await expectNoAxeViolations(container);
    await user.click(screen.getByRole('button', { name: strings.shell.collapseNavigation }));
    await expectNoAxeViolations(container);
    await user.click(screen.getByRole('button', { name: new RegExp(strings.shell.accountMenu) }));
    await expectNoAxeViolations(container);
    await user.keyboard('{Escape}');
    await user.click(screen.getByRole('button', { name: strings.shell.openNavigation }));
    // jsdom cannot model native showModal(): no top layer and no inert background, so axe-core
    // (4.14+) sees the page behind the drawer as reachable. Scan the open dialog itself here; the
    // full-page modal behaviour is covered in Chromium by e2e/shell.spec.ts.
    const dialog = screen.getByRole('dialog', { name: strings.nav.primary });
    await expectNoAxeViolations(dialog);
  });
});

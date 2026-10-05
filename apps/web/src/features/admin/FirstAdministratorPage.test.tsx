import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShell } from '../../shell/AppShell';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { CreateOrganizationPage } from './CreateOrganizationPage';
import { FirstAdministratorPage } from './FirstAdministratorPage';
import { RequirePlatformAdmin } from './RequirePlatformAdmin';

const ORG = '6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a11';
const EMAIL = 'premiere.admin@xn--socit-minire-deb2k.cd';

const RECEIPT = {
  id: '77777777-7777-4777-8777-777777777777',
  role: 'tenant-admin',
  locale: 'fr',
  status: 'PENDING',
  deliveryState: 'QUEUED',
  expiresAt: '2026-10-08T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
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
            'Cache-Control': 'private, no-store',
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

const status = (available: boolean, invitation: unknown = null) => ({
  status: 200,
  body: { available, invitation },
});

const isStatus = (request: Request, url: URL) =>
  request.method === 'GET' && url.pathname.endsWith(`/organizations/${ORG}/tenant-admin-bootstrap`);

async function renderPage(locale: 'fr' | 'en' = 'en', roles = ['platform-admin'] as const) {
  return renderWithSession(
    <RequirePlatformAdmin deniedKey="firstAdmin.unauthorized" signInKey="firstAdmin.signInRequired">
      <FirstAdministratorPage />
    </RequirePlatformAdmin>,
    sessionWithRoles([...roles]),
    locale,
  );
}

async function selectOrganization(label: string, submit: string, value = ORG) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText(label), value);
  await user.click(screen.getByRole('button', { name: submit }));
  return user;
}

async function expectNoAxeViolations(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

let setItem: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  setItem = vi.spyOn(Storage.prototype, 'setItem');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['en', 'fr'] as const)('first administrator (%s)', (locale) => {
  const c = resources[locale].common;
  const f = c.firstAdmin;

  it('validates the reference locally and never sends an invalid one', async () => {
    const requests = stubApi(() => undefined);
    const { container } = await renderPage(locale);
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(f.pageTitle);
    const input = screen.getByLabelText(f.reference.label);
    expect(input).toHaveAccessibleDescription(f.reference.help);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: f.reference.submit }));
    expect(screen.getByRole('alert')).toHaveTextContent(f.reference.REQUIRED);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveFocus();
    await user.type(input, 'not-a-reference');
    await user.click(screen.getByRole('button', { name: f.reference.submit }));
    expect(screen.getByRole('alert')).toHaveTextContent(f.reference.FORMAT);
    expect(requests).toHaveLength(0);
    await expectNoAxeViolations(container);
  });

  it('loads the status for the reference without writing it to the URL or storage', async () => {
    const requests = stubApi((request, url) => (isStatus(request, url) ? status(true) : undefined));
    const before = window.location.href;
    const { container } = await renderPage(locale);
    await selectOrganization(f.reference.label, f.reference.submit, ORG.toUpperCase());
    const panel = await screen.findByTestId('first-admin-form');
    expect(within(panel).getByRole('heading')).toHaveTextContent(f.form.title);
    expect(screen.getByText(f.form.roleNote)).toBeInTheDocument();
    expect(requests).toHaveLength(1);
    expect(requests[0]!.cache).toBe('no-store');
    expect(new URL(requests[0]!.url).search).toBe('');
    expect(window.location.href).toBe(before);
    expect(setItem).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it('invites with the email and locale only, then shows the receipt and its delivery state', async () => {
    let created = false;
    const requests = stubApi((request, url) => {
      if (isStatus(request, url)) return created ? status(false, RECEIPT) : status(true);
      if (request.method === 'POST' && url.pathname.endsWith('/tenant-admin-bootstrap')) {
        created = true;
        return { status: 201, body: RECEIPT };
      }
      return undefined;
    });
    const { container } = await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    await screen.findByTestId('first-admin-form');
    await user.type(screen.getByLabelText(c.users.form.email), EMAIL);
    await user.selectOptions(screen.getByLabelText(c.users.form.locale), 'en');
    await user.click(screen.getByRole('button', { name: f.form.submit }));

    const invitation = await screen.findByTestId('first-admin-invitation');
    expect(within(invitation).getByTestId('first-admin-delivery')).toHaveTextContent(
      c.users.delivery.QUEUED,
    );
    expect(invitation).not.toHaveTextContent(EMAIL);
    const post = requests.find((r) => r.method === 'POST')!;
    expect(post.cache).toBe('no-store');
    expect(post.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9-]{16,}$/);
    expect(await post.clone().json()).toEqual({ email: EMAIL, locale: 'en' });
    expect(setItem).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it('validates the address locally and maps a server validation error to it', async () => {
    const requests = stubApi((request, url) => {
      if (isStatus(request, url)) return status(true);
      return {
        status: 400,
        body: problem('VALIDATION_FAILED', 400, {
          fields: [{ field: 'email', constraint: 'FORMAT' }],
        }),
      };
    });
    await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    await screen.findByTestId('first-admin-form');
    await user.click(screen.getByRole('button', { name: f.form.submit }));
    expect(screen.getByTestId('first-admin-summary')).toHaveTextContent(
      c.users.validation.email.REQUIRED,
    );
    await waitFor(() => expect(screen.getByTestId('first-admin-summary')).toHaveFocus());
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    await user.type(screen.getByLabelText(c.users.form.email), 'a@b.cd');
    await user.click(screen.getByRole('button', { name: f.form.submit }));
    await waitFor(() =>
      expect(screen.getByLabelText(c.users.form.email)).toHaveAccessibleDescription(
        `${f.form.emailHelp} ${c.users.validation.email.FORMAT}`,
      ),
    );
  });

  it('reuses the idempotency key when retrying after a network failure', async () => {
    let attempts = 0;
    const requests = stubApi((request, url) => {
      if (isStatus(request, url)) return status(attempts < 2, attempts < 2 ? null : RECEIPT);
      attempts += 1;
      return attempts === 1 ? 'network' : { status: 201, body: RECEIPT };
    });
    await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    await screen.findByTestId('first-admin-form');
    await user.type(screen.getByLabelText(c.users.form.email), EMAIL);
    await user.click(screen.getByRole('button', { name: f.form.submit }));
    expect(await screen.findByTestId('first-admin-error')).toHaveTextContent(c.errors.network);
    await user.click(screen.getByRole('button', { name: f.form.submit }));
    await screen.findByTestId('first-admin-invitation');
    const posts = requests.filter((r) => r.method === 'POST');
    expect(posts).toHaveLength(2);
    expect(posts[1]!.headers.get('Idempotency-Key')).toBe(posts[0]!.headers.get('Idempotency-Key'));
  });

  it('shows the generic unavailable state and maps a lost race to its stable code', async () => {
    let calls = 0;
    stubApi((request, url) => {
      if (isStatus(request, url)) return status(calls++ === 0);
      return { status: 409, body: problem('TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE', 409) };
    });
    const { container } = await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    await screen.findByTestId('first-admin-form');
    await user.type(screen.getByLabelText(c.users.form.email), EMAIL);
    await user.click(screen.getByRole('button', { name: f.form.submit }));
    expect(await screen.findByTestId('first-admin-error')).toHaveTextContent(
      c.errors.TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE,
    );
    expect(await screen.findByTestId('first-admin-unavailable')).toHaveTextContent(f.unavailable);
    expect(screen.queryByTestId('first-admin-form')).toBeNull();
    await expectNoAxeViolations(container);
  });

  it('revokes in two steps: confirmation takes focus, cancel restores it, confirm revokes', async () => {
    let revoked = false;
    const requests = stubApi((request, url) => {
      if (isStatus(request, url)) return revoked ? status(true) : status(false, RECEIPT);
      if (url.pathname.endsWith('/revoke')) {
        revoked = true;
        return { status: 204 };
      }
      return undefined;
    });
    const { container } = await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    await screen.findByTestId('first-admin-invitation');
    const revoke = screen.getByRole('button', { name: f.actions.revoke });
    await user.click(revoke);
    const confirm = screen.getByRole('button', { name: f.actions.confirmRevokeButton });
    await waitFor(() => expect(confirm).toHaveFocus());
    expect(screen.getByRole('group')).toHaveAccessibleName(f.actions.confirmRevoke);
    await expectNoAxeViolations(container);
    await user.click(screen.getByRole('button', { name: f.actions.cancel }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: f.actions.revoke })).toHaveFocus(),
    );
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: f.actions.revoke }));
    await user.click(screen.getByRole('button', { name: f.actions.confirmRevokeButton }));
    await screen.findByTestId('first-admin-form');
    const post = requests.find((r) => r.method === 'POST')!;
    expect(new URL(post.url).pathname).toMatch(
      new RegExp(`/organizations/${ORG}/tenant-admin-bootstrap/revoke$`),
    );
  });

  it('sends a new link with its own idempotency key and shows the new delivery state', async () => {
    let resent = false;
    const requests = stubApi((request, url) => {
      if (isStatus(request, url))
        return status(
          false,
          resent ? { ...RECEIPT, deliveryState: 'SENT' } : { ...RECEIPT, deliveryState: 'FAILED' },
        );
      if (url.pathname.endsWith('/resend')) {
        resent = true;
        return { status: 200, body: { ...RECEIPT, deliveryState: 'QUEUED' } };
      }
      return undefined;
    });
    await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    expect(await screen.findByTestId('first-admin-delivery')).toHaveTextContent(
      c.users.delivery.FAILED,
    );
    await user.click(screen.getByRole('button', { name: f.actions.resend }));
    await waitFor(() =>
      expect(screen.getByTestId('first-admin-delivery')).toHaveTextContent(c.users.delivery.SENT),
    );
    const post = requests.find((r) => r.method === 'POST')!;
    expect(post.headers.get('Idempotency-Key')).toBeTruthy();
    expect(screen.getByTestId('first-admin-delivery')).toHaveAttribute('aria-live', 'polite');
  });

  it('returns an unknown or inactive organization to the reference field', async () => {
    stubApi(() => ({ status: 404, body: problem('ORGANIZATION_NOT_FOUND', 404) }));
    await renderPage(locale);
    await selectOrganization(f.reference.label, f.reference.submit);
    expect(await screen.findByRole('alert')).toHaveTextContent(f.notFound);
    expect(screen.queryByTestId('first-admin-panel')).toBeNull();
    await waitFor(() => expect(screen.getByLabelText(f.reference.label)).toHaveFocus());
  });

  it('shows the server refusal and a retry when the status cannot be read', async () => {
    let calls = 0;
    stubApi(() =>
      calls++ === 0 ? { status: 403, body: problem('ACCESS_DENIED', 403) } : status(true),
    );
    await renderPage(locale);
    const user = await selectOrganization(f.reference.label, f.reference.submit);
    expect(await screen.findByTestId('first-admin-load-error')).toHaveTextContent(f.unauthorized);
    await user.click(screen.getByRole('button', { name: c.users.retry }));
    await screen.findByTestId('first-admin-form');
  });
});

describe('first administrator authorization and entry points', () => {
  it('is hidden from tenant administrators and loads nothing', async () => {
    const requests = stubApi(() => undefined);
    await renderPage('fr', ['tenant-admin'] as unknown as readonly ['platform-admin']);
    expect(screen.getByTestId('not-authorized')).toHaveTextContent(
      resources.fr.common.firstAdmin.unauthorized,
    );
    expect(requests).toHaveLength(0);
  });

  it('shows the navigation entry only to platform administrators', async () => {
    stubApi(() => undefined);
    const { unmount } = await renderWithSession(
      <AppShell environment="test">
        <p>x</p>
      </AppShell>,
      sessionWithRoles(['platform-admin']),
      'en',
    );
    expect(screen.getByRole('link', { name: resources.en.common.nav.firstAdmin })).toHaveAttribute(
      'href',
      '/admin/organizations/first-admin',
    );
    unmount();
    await renderWithSession(
      <AppShell environment="test">
        <p>x</p>
      </AppShell>,
      sessionWithRoles(['tenant-admin']),
      'en',
    );
    expect(screen.queryByRole('link', { name: resources.en.common.nav.firstAdmin })).toBeNull();
  });

  it('offers the first-administrator panel right after an organization is created', async () => {
    const created = {
      id: ORG,
      name: 'Hôpital Général de Kinshasa',
      countryCode: 'CD',
      defaultLocale: 'fr',
      timezone: 'Africa/Kinshasa',
      currencies: ['CDF'],
      status: 'ACTIVE',
      createdAt: '2026-10-01T10:15:00Z',
    };
    const requests = stubApi((request, url) => {
      if (isStatus(request, url)) return status(true);
      if (request.method === 'POST' && url.pathname.endsWith('/organizations'))
        return { status: 201, body: created };
      return undefined;
    });
    const s = resources.fr.common;
    await renderWithSession(
      <RequirePlatformAdmin>
        <CreateOrganizationPage />
      </RequirePlatformAdmin>,
      sessionWithRoles(['platform-admin']),
      'fr',
    );
    const user = userEvent.setup();
    await user.type(screen.getByLabelText(s.createOrganization.name.label), created.name);
    await user.click(screen.getByRole('button', { name: s.createOrganization.submit }));
    const success = await screen.findByTestId('organization-created');
    await within(success).findByTestId('first-admin-form');
    // The panel's invitation language defaults to the organization's default language.
    expect(within(success).getByLabelText(s.users.form.locale)).toHaveValue('fr');
    expect(requests.some((r) => isStatus(r, new URL(r.url)))).toBe(true);
  });
});

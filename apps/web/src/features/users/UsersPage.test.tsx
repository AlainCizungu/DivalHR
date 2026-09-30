import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { AppShell } from '../../layout/AppShell';
import { RequireRole } from '../hierarchy/RequireRole';
import { UsersPage } from './UsersPage';

const PENDING = {
  id: '11111111-1111-4111-8111-111111111111',
  email: 'elodie.mukendi@xn--socit-minire-deb2k.cd',
  role: 'employee',
  locale: 'fr',
  status: 'PENDING',
  deliveryState: 'QUEUED',
  expiresAt: '2026-10-07T10:00:00Z',
  createdAt: '2026-09-30T10:00:00.000200Z',
  acceptedAt: null,
  revokedAt: null,
  resendsRemaining: 3,
};
const SENT = {
  ...PENDING,
  id: '22222222-2222-4222-8222-222222222222',
  email: 'sent@example.cd',
  deliveryState: 'SENT',
  createdAt: '2026-09-30T10:00:00.0001Z',
};
const FAILED = {
  ...PENDING,
  id: '33333333-3333-4333-8333-333333333333',
  email: 'failed@example.cd',
  role: 'tenant-admin',
  locale: 'en',
  deliveryState: 'FAILED',
  createdAt: '2026-09-29T10:00:00Z',
  resendsRemaining: 1,
};
const ACCEPTED = {
  ...PENDING,
  id: '44444444-4444-4444-8444-444444444444',
  email: 'accepted@example.cd',
  status: 'ACCEPTED',
  deliveryState: 'SENT',
  createdAt: '2026-09-28T10:00:00Z',
  acceptedAt: '2026-09-28T12:00:00Z',
};

type Reply = { status: number; body?: unknown } | 'network';
type Route = (request: Request, url: URL) => Reply | undefined;

function stubApi(route: Route = () => undefined) {
  const requests: Request[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const url = new URL(request.url);
      const reply = route(request, url) ?? {
        status: 200,
        body: { data: [PENDING, SENT, FAILED, ACCEPTED] },
      };
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

function fill(template: string, values: Record<string, string | number>) {
  return Object.entries(values).reduce(
    (text, [k, v]) => text.replaceAll(`{{${k}}}`, String(v)),
    template,
  );
}

async function renderPage(locale: 'fr' | 'en' = 'en', roles = ['tenant-admin'] as const) {
  return renderWithSession(
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="users.unauthorized"
      signInKey="users.signInRequired"
    >
      <UsersPage />
    </RequireRole>,
    sessionWithRoles([...roles]),
    locale,
  );
}

const rowOf = (email: string) =>
  screen
    .getAllByTestId('invitation-row')
    .find((li) => li.textContent.includes(email)) as HTMLElement;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe.each(['en', 'fr'] as const)('users and invitations (%s)', (locale) => {
  const s = resources[locale].common.users;
  const errors = resources[locale].common.errors;

  it('lists invitations newest first with strict delivery wording and passes axe', async () => {
    const requests = stubApi();
    const { container } = await renderPage(locale);
    const list = await screen.findByTestId('invitation-list');
    const emails = within(list)
      .getAllByTestId('invitation-row')
      .map((li) => li.querySelector('strong')?.textContent);
    expect(emails).toEqual([PENDING.email, SENT.email, FAILED.email, ACCEPTED.email]);

    const delivery = (email: string) => within(rowOf(email)).queryByTestId('invitation-delivery');
    expect(delivery(PENDING.email)).toHaveTextContent(s.delivery.QUEUED);
    expect(delivery(PENDING.email)).not.toHaveTextContent(s.delivery.SENT);
    expect(delivery(SENT.email)).toHaveTextContent(s.delivery.SENT);
    expect(delivery(FAILED.email)).toHaveTextContent(s.delivery.FAILED);
    expect(delivery(ACCEPTED.email)).toBeNull();
    expect(within(rowOf(ACCEPTED.email)).queryByRole('button')).toBeNull();
    expect(
      within(rowOf(FAILED.email)).getByText(fill(s.list.resendsRemaining_one, { count: 1 })),
    ).toBeInTheDocument();

    const get = requests.find((r) => r.method === 'GET');
    expect(get?.cache).toBe('no-store');
    expect(new URL(get?.url ?? '').searchParams.has('status')).toBe(false);

    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
  });

  it('invites a person: no tenant in the body, receipt row shows delivery pending, focus and one announcement', async () => {
    const receipt = {
      id: '55555555-5555-4555-8555-555555555555',
      role: 'tenant-admin',
      locale: 'en',
      status: 'PENDING',
      deliveryState: 'QUEUED',
      expiresAt: '2026-10-07T11:00:00Z',
      createdAt: '2026-09-30T11:00:00Z',
    };
    const requests = stubApi((request) =>
      request.method === 'POST' ? { status: 201, body: receipt } : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await screen.findByTestId('invitation-list');
    const form = screen.getByTestId('invite-form');
    // Only tenant roles are offered.
    const radios = within(form).getAllByRole('radio');
    expect(radios.map((r) => (r as HTMLInputElement).value)).toEqual(['employee', 'tenant-admin']);
    expect(within(form).getByRole('radio', { name: s.form.roles.employee })).toBeChecked();

    await user.type(within(form).getByLabelText(s.form.email), '  Nouvelle.Admin@Example.CD ');
    await user.click(within(form).getByRole('radio', { name: s.form.roles['tenant-admin'] }));
    await user.selectOptions(within(form).getByLabelText(s.form.locale), 'en');
    await user.click(within(form).getByRole('button', { name: s.form.submit }));

    const post = requests.find((r) => r.method === 'POST');
    expect(post?.url).toBe('http://core.test/api/v1/invitations');
    expect(post?.cache).toBe('no-store');
    expect(post?.headers.get('Idempotency-Key')).toMatch(/^web-/);
    expect(await post?.clone().json()).toStrictEqual({
      email: 'Nouvelle.Admin@Example.CD',
      role: 'tenant-admin',
      locale: 'en',
    });

    const created = await waitFor(() => rowOf('Nouvelle.Admin@Example.CD'));
    expect(screen.getAllByTestId('invitation-row')[0]).toBe(created);
    expect(within(created).getByTestId('invitation-delivery')).toHaveTextContent(s.delivery.QUEUED);
    await waitFor(() => {
      expect(created.querySelector('.hierarchy-item__main')).toHaveFocus();
    });
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      fill(s.announce.created, { email: 'Nouvelle.Admin@Example.CD' }),
    );
    expect(within(form).getByLabelText(s.form.email)).toHaveValue('');
  });

  it('validates locally and maps an already pending invitation to the email field', async () => {
    const requests = stubApi((request) =>
      request.method === 'POST'
        ? { status: 409, body: problem('INVITATION_ALREADY_PENDING', 409) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    const form = await screen.findByTestId('invite-form');
    await user.click(within(form).getByRole('button', { name: s.form.submit }));
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
    const summary = screen.getByTestId('form-summary');
    await waitFor(() => {
      expect(summary).toHaveFocus();
    });
    expect(summary).toHaveTextContent(s.validation.email.REQUIRED);

    const email = within(form).getByLabelText(s.form.email);
    await user.type(email, 'dup@example.cd');
    await user.click(within(form).getByRole('button', { name: s.form.submit }));
    await waitFor(() => {
      expect(summary).toHaveTextContent(s.validation.email.ALREADY_PENDING);
    });
    expect(email).toHaveAttribute('aria-invalid', 'true');
    expect(email).toHaveAccessibleDescription(
      `${s.form.emailHelp} ${s.validation.email.ALREADY_PENDING}`,
    );
  });

  it('reuses the idempotency key when retrying after a network failure', async () => {
    let calls = 0;
    const requests = stubApi((request) => {
      if (request.method !== 'POST') return undefined;
      calls += 1;
      return calls === 1
        ? 'network'
        : { status: 429, body: problem('INVITATION_RATE_LIMITED', 429) };
    });
    const user = userEvent.setup();
    await renderPage(locale);
    const form = await screen.findByTestId('invite-form');
    await user.type(within(form).getByLabelText(s.form.email), 'retry@example.cd');
    await user.click(within(form).getByRole('button', { name: s.form.submit }));
    expect(await screen.findByTestId('form-error')).toHaveTextContent(errors.network);
    await user.click(within(form).getByRole('button', { name: s.form.submit }));
    await waitFor(() => {
      expect(screen.getByTestId('form-error')).toHaveTextContent(errors.INVITATION_RATE_LIMITED);
    });
    const keys = requests
      .filter((r) => r.method === 'POST')
      .map((r) => r.headers.get('Idempotency-Key'));
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
  });

  it('revokes in two steps: confirmation takes focus, cancel restores it, confirm revokes', async () => {
    const revoked = { ...PENDING, status: 'REVOKED', revokedAt: '2026-09-30T12:00:00Z' };
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/revoke')
        ? { status: 200, body: revoked }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await screen.findByTestId('invitation-list');
    const row = rowOf(PENDING.email);
    const revoke = within(row).getByRole('button', {
      name: fill(s.actions.revokeLabel, { email: PENDING.email }),
    });
    await user.click(revoke);
    const confirmation = within(row).getByTestId('invitation-confirm');
    expect(confirmation).toHaveAccessibleName(
      fill(s.actions.confirmRevoke, { email: PENDING.email }),
    );
    const yes = within(confirmation).getByRole('button', { name: s.actions.confirmRevokeButton });
    await waitFor(() => {
      expect(yes).toHaveFocus();
    });
    await user.click(within(confirmation).getByRole('button', { name: s.actions.cancel }));
    expect(requests.filter((r) => r.method === 'POST')).toHaveLength(0);
    const again = within(row).getByRole('button', {
      name: fill(s.actions.revokeLabel, { email: PENDING.email }),
    });
    await waitFor(() => {
      expect(again).toHaveFocus();
    });

    await user.click(again);
    await user.click(within(row).getByRole('button', { name: s.actions.confirmRevokeButton }));
    await waitFor(() => {
      expect(within(rowOf(PENDING.email)).getByTestId('invitation-status')).toHaveTextContent(
        s.status.REVOKED,
      );
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.url).toBe(`http://core.test/api/v1/invitations/${PENDING.id}/revoke`);
    expect(post?.cache).toBe('no-store');
    expect(within(rowOf(PENDING.email)).queryByTestId('invitation-delivery')).toBeNull();
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      fill(s.announce.revoked, { email: PENDING.email }),
    );
  });

  it('resends a failed delivery with a new link and warns once no resend remains', async () => {
    const receipt = {
      id: FAILED.id,
      role: FAILED.role,
      locale: FAILED.locale,
      status: 'PENDING',
      deliveryState: 'QUEUED',
      expiresAt: '2026-10-08T10:00:00Z',
      createdAt: FAILED.createdAt,
    };
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/resend')
        ? { status: 200, body: receipt }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await screen.findByTestId('invitation-list');
    const row = rowOf(FAILED.email);
    await user.click(
      within(row).getByRole('button', {
        name: fill(s.actions.resendLabel, { email: FAILED.email }),
      }),
    );
    expect(within(row).getByTestId('invitation-confirm')).toHaveAccessibleName(
      fill(s.actions.confirmResend, { email: FAILED.email }),
    );
    await user.click(within(row).getByRole('button', { name: s.actions.confirmResendButton }));
    await waitFor(() => {
      expect(within(rowOf(FAILED.email)).getByTestId('invitation-delivery')).toHaveTextContent(
        s.delivery.QUEUED,
      );
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.url).toBe(`http://core.test/api/v1/invitations/${FAILED.id}/resend`);
    expect(post?.headers.get('Idempotency-Key')).toMatch(/^web-/);
    // The last resend was used: no resend button remains, revoke stays available.
    const updated = rowOf(FAILED.email);
    expect(
      within(updated).queryByRole('button', {
        name: fill(s.actions.resendLabel, { email: FAILED.email }),
      }),
    ).toBeNull();
    expect(
      within(updated).getByRole('button', {
        name: fill(s.actions.revokeLabel, { email: FAILED.email }),
      }),
    ).toBeEnabled();
    expect(screen.getByTestId('announcer')).toHaveTextContent(
      fill(s.announce.resent, { email: FAILED.email }),
    );
  });

  it('shows the resend limit error next to the row and announces it once', async () => {
    stubApi((request) =>
      request.method === 'POST'
        ? { status: 429, body: problem('INVITATION_RESEND_LIMITED', 429) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await screen.findByTestId('invitation-list');
    const row = rowOf(SENT.email);
    await user.click(
      within(row).getByRole('button', { name: fill(s.actions.resendLabel, { email: SENT.email }) }),
    );
    await user.click(within(row).getByRole('button', { name: s.actions.confirmResendButton }));
    expect(await within(row).findByTestId('invitation-error')).toHaveTextContent(
      errors.INVITATION_RESEND_LIMITED,
    );
    expect(screen.getByTestId('announcer')).toHaveTextContent(errors.INVITATION_RESEND_LIMITED);
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.queryAllByRole('alert')).toHaveLength(0);
  });

  it('filters by status with a fresh list', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'GET' && url.searchParams.get('status') === 'EXPIRED'
        ? { status: 200, body: { data: [] } }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await screen.findByTestId('invitation-list');
    await user.selectOptions(screen.getByLabelText(s.list.filter), 'EXPIRED');
    expect(await screen.findByTestId('invitation-list-empty')).toHaveTextContent(
      s.list.emptyFiltered,
    );
    const last = requests.filter((r) => r.method === 'GET').at(-1);
    expect(new URL(last?.url ?? '').searchParams.get('status')).toBe('EXPIRED');
  });
});

describe('users page authorization', () => {
  it('is hidden from employees and loads nothing', async () => {
    const requests = stubApi();
    await renderPage('fr', ['employee'] as never);
    expect(screen.getByTestId('not-authorized')).toHaveTextContent(
      resources.fr.common.users.unauthorized,
    );
    expect(requests).toHaveLength(0);
  });

  it('shows the server refusal when the API denies the list', async () => {
    stubApi(() => ({ status: 403, body: problem('ACCESS_DENIED', 403) }));
    await renderPage('en');
    expect(await screen.findByTestId('invitation-list-error')).toHaveTextContent(
      resources.en.common.users.unauthorized,
    );
  });

  it('shows the navigation entry only to tenant administrators', async () => {
    stubApi();
    for (const locale of ['en', 'fr'] as const) {
      const nav = resources[locale].common.nav.users;
      const { unmount } = await renderWithSession(
        <AppShell environment="test">
          <p>content</p>
        </AppShell>,
        sessionWithRoles(['tenant-admin']),
        locale,
      );
      expect(screen.getByRole('link', { name: nav })).toHaveAttribute('href', '/admin/users');
      unmount();
      const other = await renderWithSession(
        <AppShell environment="test">
          <p>content</p>
        </AppShell>,
        sessionWithRoles(['employee', 'platform-admin']),
        locale,
      );
      expect(screen.queryByRole('link', { name: nav })).toBeNull();
      other.unmount();
    }
  });

  it('refuses the page to a platform administrator without calling the API', async () => {
    const requests = stubApi();
    await renderPage('en', ['platform-admin'] as never);
    expect(screen.getByTestId('not-authorized')).toHaveTextContent(
      resources.en.common.users.unauthorized,
    );
    expect(requests).toHaveLength(0);
  });
});

import { resources } from '@divalhr/localization';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession } from '../../test/renderWithSession';
import { AcceptInvitationPage } from './AcceptInvitationPage';
import { readInvitationToken } from './invitationToken';

const signIn = vi.fn(() => Promise.resolve());

// The visitor is signed in to another account: the public client must still never send its token.
vi.mock('../../auth/AuthProvider', () => ({
  AuthProvider: ({ children }: { children: ReactNode }) => children,
  useAuth: () => ({
    status: 'authenticated',
    getAccessToken: () => 'access-token-must-not-leak',
    signIn,
    signOut: () => Promise.resolve(),
  }),
}));

// An obviously fake token with the real shape (43 base64url characters).
const TOKEN = 'test-only-invitation-token'.padEnd(43, '0');
const BASE_PREVIEW = { role: 'tenant-admin', locale: 'fr', expiresAt: '2026-10-07T10:00:00Z' };

type Reply = { status: number; body?: unknown } | 'network';

function stubApi(route: (url: URL) => Reply) {
  const requests: Request[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const reply = route(new URL(request.url));
      if (reply === 'network') return Promise.reject(new TypeError('Failed to fetch'));
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
            'Cache-Control': 'no-store',
          },
        }),
      );
    }),
  );
  return requests;
}

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-12345678',
});

const isInspect = (url: URL) => url.pathname.endsWith('/public/invitations/inspect');

async function openLink(locale: 'fr' | 'en', hash = `#token=${TOKEN}`) {
  window.history.replaceState(null, '', `/invitation${hash}`);
  return renderWithSession(<AcceptInvitationPage />, { kind: 'anonymous' }, locale, '/invitation');
}

function fill(template: string, values: Record<string, string>) {
  return Object.entries(values).reduce((text, [k, v]) => text.replaceAll(`{{${k}}}`, v), template);
}

beforeEach(() => {
  signIn.mockClear();
});

afterEach(() => {
  vi.unstubAllGlobals();
  window.history.replaceState(null, '', '/');
});

describe('invitation token', () => {
  it('accepts only a 43-character base64url token from the fragment', () => {
    const at = (hash: string) => ({ hash }) as Location;
    expect(readInvitationToken(at(`#token=${TOKEN}`))).toBe(TOKEN);
    expect(readInvitationToken(at(''))).toBeNull();
    expect(readInvitationToken(at('#token=short'))).toBeNull();
    expect(readInvitationToken(at(`#token=${TOKEN}=`))).toBeNull();
    expect(readInvitationToken(at(`#other=${TOKEN}`))).toBeNull();
  });
});

describe.each(['en', 'fr'] as const)('invitation page (%s)', (locale) => {
  const s = resources[locale].common.invitation;
  const errors = resources[locale].common.errors;

  // Invitations in the language under test; the page adopts the invitation's language.
  const PREVIEW = { ...BASE_PREVIEW, locale };

  it('strips the fragment, sends the token only in a no-store body without credentials, and accepts', async () => {
    const requests = stubApi((url) =>
      isInspect(url)
        ? { status: 200, body: PREVIEW }
        : { status: 200, body: { status: 'ACCEPTED' } },
    );
    const user = userEvent.setup();
    const { container } = await openLink(locale);
    await waitFor(() => {
      expect(window.location.hash).toBe('');
    });
    expect(window.location.href).not.toContain(TOKEN);

    const preview = await screen.findByTestId('invitation-preview');
    expect(preview).toHaveTextContent(fill(s.summary, { role: s.roles['tenant-admin'] }));
    // Nothing about the organization or any account is shown.
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);

    await user.click(screen.getByRole('button', { name: s.accept }));
    const done = screen.getByTestId('invitation-result');
    await waitFor(() => {
      expect(done).toHaveTextContent(s.accepted);
    });
    await waitFor(() => {
      expect(done).toHaveFocus();
    });
    expect(screen.getByTestId('announcer')).toHaveTextContent(s.accepted);

    expect(requests.map((r) => new URL(r.url).pathname)).toEqual([
      '/api/v1/public/invitations/inspect',
      '/api/v1/public/invitations/accept',
    ]);
    for (const request of requests) {
      expect(request.method).toBe('POST');
      expect(request.url).not.toContain(TOKEN);
      expect(request.headers.has('Authorization')).toBe(false);
      expect(request.cache).toBe('no-store');
      expect(await request.clone().json()).toStrictEqual({ token: TOKEN });
    }

    await user.click(screen.getByRole('button', { name: s.signIn }));
    expect(signIn).toHaveBeenCalledWith('/');
  });

  it('says the same thing for every unusable link', async () => {
    stubApi(() => ({ status: 404, body: problem('INVITATION_INVALID', 404) }));
    await openLink(locale);
    await waitFor(() => {
      expect(screen.getByTestId('invitation-result')).toHaveTextContent(s.invalid);
    });
    expect(screen.queryByTestId('invitation-preview')).toBeNull();
    expect(screen.queryByRole('button', { name: s.accept })).toBeNull();
  });

  it('never calls the API without a well-formed token', async () => {
    const requests = stubApi(() => ({ status: 500 }));
    await openLink(locale, '#token=not-a-token');
    expect(screen.getByText(s.missing)).toBeInTheDocument();
    await waitFor(() => {
      expect(window.location.hash).toBe('');
    });
    expect(requests).toHaveLength(0);
  });

  it('keeps the token in memory to retry after the identity provider was unavailable', async () => {
    let accepts = 0;
    const requests = stubApi((url) => {
      if (isInspect(url)) return { status: 200, body: PREVIEW };
      accepts += 1;
      return accepts === 1
        ? { status: 503, body: problem('IDENTITY_PROVIDER_UNAVAILABLE', 503) }
        : { status: 200, body: { status: 'ACCEPTED' } };
    });
    const user = userEvent.setup();
    await openLink(locale);
    await user.click(await screen.findByRole('button', { name: s.accept }));
    const result = screen.getByTestId('invitation-result');
    await waitFor(() => {
      expect(result).toHaveTextContent(errors.IDENTITY_PROVIDER_UNAVAILABLE);
    });
    await user.click(screen.getByRole('button', { name: s.retry }));
    await waitFor(() => {
      expect(result).toHaveTextContent(s.accepted);
    });
    const bodies = await Promise.all(requests.map((r) => r.clone().json()));
    expect(bodies).toEqual([{ token: TOKEN }, { token: TOKEN }, { token: TOKEN }]);
  });

  it('explains a refusal without revealing why', async () => {
    stubApi((url) =>
      isInspect(url)
        ? { status: 200, body: PREVIEW }
        : { status: 409, body: problem('INVITATION_CANNOT_BE_ACCEPTED', 409) },
    );
    const user = userEvent.setup();
    await openLink(locale);
    await user.click(await screen.findByRole('button', { name: s.accept }));
    await waitFor(() => {
      expect(screen.getByTestId('invitation-result')).toHaveTextContent(s.cannotAccept);
    });
    expect(screen.queryByRole('button', { name: s.retry })).toBeNull();
  });

  it('retries the inspection after a rate limit', async () => {
    let inspections = 0;
    stubApi(() => {
      inspections += 1;
      return inspections === 1
        ? { status: 429, body: problem('RATE_LIMITED', 429) }
        : { status: 200, body: PREVIEW };
    });
    const user = userEvent.setup();
    await openLink(locale);
    await user.click(await screen.findByRole('button', { name: s.retry }));
    expect(await screen.findByTestId('invitation-preview')).toBeInTheDocument();
    expect(screen.getByTestId('announcer')).toBeEmptyDOMElement();
  });
});

describe('invitation language', () => {
  it('switches to the invitation language', async () => {
    stubApi(() => ({ status: 200, body: BASE_PREVIEW }));
    await openLink('en');
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(
        resources.fr.common.invitation.title,
      );
    });
  });
});

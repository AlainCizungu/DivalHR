import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { WebhooksPage } from './WebhooksPage';

const ID = '33333333-3333-4333-8333-333333333333';
// Synthetic and built at run time (never a literal that looks like a credential).
const SECRET = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, i) => 65 + i)))
  .replace(/\+/g, '-')
  .replace(/\//g, '_')
  .replace(/=+$/, '');

const webhook = (overrides: Record<string, unknown> = {}) => ({
  id: ID,
  name: 'Flux paie',
  url: 'https://hooks.exemple.cd/in',
  eventTypes: ['leave.request.approved.v1'],
  status: 'ACTIVE',
  configVersion: 1,
  secretVersion: 1,
  consecutiveFailures: 0,
  lastSuccessAt: null,
  lastFailureAt: null,
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  ...overrides,
});

const delivery = (overrides: Record<string, unknown> = {}) => ({
  id: '44444444-4444-4444-8444-444444444444',
  webhookId: ID,
  eventId: '55555555-5555-4555-8555-555555555555',
  eventType: 'webhook.test.v1',
  state: 'RETRYING',
  attemptCount: 1,
  createdAt: '2026-10-01T10:00:00Z',
  lastAttemptAt: '2026-10-01T10:00:05Z',
  nextAttemptAt: '2026-10-01T10:01:05Z',
  lastHttpStatus: null,
  lastFailureClass: 'CONNECT_TIMEOUT',
  ...overrides,
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> } | 'network';
type Route = (request: Request, url: URL) => Reply | undefined;

function stubApi(route: Route = () => undefined) {
  const requests: Request[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn((request: Request) => {
      requests.push(request);
      const url = new URL(request.url);
      const path = url.pathname;
      const reply: Reply =
        route(request, url) ??
        (path.endsWith('/deliveries')
          ? { status: 200, body: { items: [delivery()], nextCursor: null } }
          : path.includes('/deliveries/')
            ? {
                status: 200,
                body: {
                  delivery: delivery(),
                  attempts: [
                    {
                      number: 1,
                      startedAt: '2026-10-01T10:00:05Z',
                      httpStatus: null,
                      failureClass: 'CONNECT_TIMEOUT',
                      latencyMillis: 3000,
                    },
                  ],
                },
              }
            : path.endsWith(`/webhooks/${ID}`)
              ? {
                  status: 200,
                  body: { webhook: webhook(), secret: null, previousSecretExpiresAt: null },
                }
              : path.endsWith('/webhooks')
                ? { status: 200, body: { items: [webhook()], nextCursor: null } }
                : { status: 404 });
      if (reply === 'network') return Promise.reject(new TypeError('Failed to fetch'));
      return Promise.resolve(
        new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
          status: reply.status,
          headers: {
            'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
            'Cache-Control': 'private, no-store',
            ...(reply.headers ?? {}),
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
  correlationId: 'corr-hook-1234',
});

async function renderPage(locale: 'fr' | 'en', roles = ['tenant-admin'] as const) {
  return renderWithSession(
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="webhooks.unauthorized"
      signInKey="webhooks.signInRequired"
    >
      <WebhooksPage />
    </RequireRole>,
    sessionWithRoles([...roles]),
    locale,
  );
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

let setItem: { mock: { calls: unknown[][] } };
beforeEach(() => {
  setItem = vi.spyOn(Storage.prototype, 'setItem');
});
afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe.each(['fr', 'en'] as const)('webhooks (%s)', (locale) => {
  const c = resources[locale].common;
  const w = c.webhooks;

  it('lists webhooks with no-store requests and no accessibility violation', async () => {
    const requests = stubApi();
    const { container } = await renderPage(locale);
    expect(await screen.findByRole('heading', { level: 1, name: w.title })).toBeTruthy();
    expect(await screen.findByText('Flux paie')).toBeTruthy();
    expect(screen.getByText(w.status.ACTIVE)).toBeTruthy();
    expect(requests.every((r) => r.cache === 'no-store' || r.method !== 'GET')).toBe(true);
    await expectAccessible(container);
  });

  it('is denied to employees', async () => {
    stubApi();
    await renderPage(locale, ['employee'] as never);
    expect(await screen.findByText(w.unauthorized)).toBeTruthy();
  });

  it('validates the form before sending and focuses the first problem', async () => {
    const requests = stubApi();
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.create.open }));
    await user.click(screen.getByRole('button', { name: w.form.create }));
    expect(await screen.findByText(w.errors.nameRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.urlRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.eventsRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.confirmRequired)).toBeTruthy();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.type(screen.getByLabelText(w.form.url), 'http://exemple.cd');
    await user.click(screen.getByRole('button', { name: w.form.create }));
    expect(await screen.findByText(w.errors.urlHttps)).toBeTruthy();
    await expectAccessible(container);
  });

  it('reveals the secret once, copies it, and drops it when done', async () => {
    const requests = stubApi((request) =>
      request.method === 'POST' && new URL(request.url).pathname.endsWith('/webhooks')
        ? {
            status: 201,
            body: { webhook: webhook(), secret: SECRET, previousSecretExpiresAt: null },
          }
        : undefined,
    );
    const user = userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    const { container } = await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.create.open }));
    await user.type(screen.getByLabelText(w.form.name), 'Flux paie');
    await user.type(screen.getByLabelText(w.form.url), 'https://hooks.exemple.cd/in');
    await user.click(screen.getByLabelText(new RegExp(w.events['leave.request.approved.v1'])));
    await user.click(screen.getByLabelText(w.form.confirm));
    await user.click(screen.getByRole('button', { name: w.form.create }));

    const reveal = await screen.findByTestId('secret-value');
    expect((reveal as HTMLInputElement).value).toBe(SECRET);
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.headers.get('Idempotency-Key')).toMatch(/^web-/);
    expect(await post?.clone().json()).toMatchObject({
      name: 'Flux paie',
      url: 'https://hooks.exemple.cd/in',
      confirm: true,
    });
    await expectAccessible(container);

    await user.click(screen.getByRole('button', { name: w.secret.copy }));
    expect(writeText).toHaveBeenCalledWith(SECRET);
    expect(await screen.findByText(w.secret.copied)).toBeTruthy();

    const done = screen.getByRole('button', { name: w.secret.done });
    expect((done as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByLabelText(w.secret.stored));
    await user.click(done);
    await waitFor(() => {
      expect(screen.queryByTestId('secret-value')).toBeNull();
    });
    expect(document.body.textContent).not.toContain(SECRET);
    expect(window.location.href).not.toContain(SECRET);
    expect(setItem.mock.calls.flat().join(' ')).not.toContain(SECRET);
  });

  it('shows a blocked destination as a stable, translated refusal', async () => {
    stubApi((request) =>
      request.method === 'POST'
        ? { status: 422, body: problem('WEBHOOK_DESTINATION_BLOCKED', 422) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.create.open }));
    await user.type(screen.getByLabelText(w.form.name), 'Interne');
    await user.type(screen.getByLabelText(w.form.url), 'https://interne.exemple.cd/');
    await user.click(screen.getByLabelText(new RegExp(w.events['timesheet.approved.v1'])));
    await user.click(screen.getByLabelText(w.form.confirm));
    await user.click(screen.getByRole('button', { name: w.form.create }));
    const box = await screen.findByTestId('webhook-failure');
    expect(box.textContent).toContain(c.errors.WEBHOOK_DESTINATION_BLOCKED);
    expect(box.textContent).toContain('corr-hook-1234');
    expect(screen.queryByTestId('secret-value')).toBeNull();
  });

  it('shows the delivery history with closed failure classes and attempts', async () => {
    stubApi();
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: 'Flux paie' }));
    const row = await screen.findByTestId('history-row');
    expect(within(row).getByText(w.state.RETRYING)).toBeTruthy();
    expect(within(row).getByText(w.failure.CONNECT_TIMEOUT)).toBeTruthy();
    await user.click(within(row).getByRole('button', { name: w.history.show }));
    expect(await screen.findByTestId('attempts')).toBeTruthy();
    await expectAccessible(container);
  });

  it('rotates the secret only after an explicit confirmation and reveals the new one', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/rotate-secret')
        ? {
            status: 200,
            body: {
              webhook: webhook({ secretVersion: 2 }),
              secret: SECRET,
              previousSecretExpiresAt: '2026-10-02T10:00:00Z',
            },
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: 'Flux paie' }));
    await user.click(await screen.findByRole('button', { name: w.actions.rotate }));
    const submit = screen.getByRole('button', { name: w.confirmAction.rotate.submit });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByLabelText(w.confirmAction.understand));
    await user.click(submit);
    expect(await screen.findByTestId('secret-value')).toBeTruthy();
    const post = requests.find((r) => r.method === 'POST');
    expect(await post?.clone().json()).toEqual({ configVersion: 1, confirm: true });
  });

  it('keeps nothing in the URL or browser storage', async () => {
    stubApi();
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: 'Flux paie' }));
    await screen.findByTestId('history-row');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');
    expect(setItem).not.toHaveBeenCalled();
  });

  it('maps a rate limit with its Retry-After', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/test-deliveries')
        ? {
            status: 429,
            headers: { 'Retry-After': '120' },
            body: problem('WEBHOOK_TEST_RATE_LIMITED', 429),
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: 'Flux paie' }));
    await user.click(await screen.findByRole('button', { name: w.actions.test }));
    const box = await screen.findByTestId('webhook-failure');
    expect(box.textContent).toContain(c.errors.WEBHOOK_TEST_RATE_LIMITED);
    expect(box.textContent).toMatch(/120/);
  });
});

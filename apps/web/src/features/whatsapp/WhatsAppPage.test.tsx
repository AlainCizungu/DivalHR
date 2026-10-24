import { resources } from '@divalhr/localization';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { RequireRole } from '../hierarchy/RequireRole';
import { WhatsAppPage } from './WhatsAppPage';

const RECIPIENT_ID = '66666666-6666-4666-8666-666666666666';
// Synthetic and built at run time (never a literal that looks like a credential).
const TOKEN = 'T'.repeat(12) + 'k'.repeat(12) + '0123456789';
const FULL_NUMBER = '+243810000042';

const connector = (overrides: Record<string, unknown> = {}) => ({
  id: '77777777-7777-4777-8777-777777777777',
  provider: 'WHATSAPP',
  state: 'DRAFT',
  configVersion: 1,
  credentialVersion: 1,
  wabaId: '100200300',
  phoneNumberId: '400500600',
  displayNumber: '+243999000111',
  graphApiVersion: 'v22.0',
  verifiedAt: null,
  consecutiveFailures: 0,
  lastSuccessAt: null,
  lastFailureAt: null,
  recipientCount: 0,
  templates: [
    {
      notificationType: 'CONNECTOR_TEST',
      language: 'fr',
      templateName: 'divalhr_test_fr',
      verified: false,
    },
  ],
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  ...overrides,
});

const recipient = (overrides: Record<string, unknown> = {}) => ({
  id: RECIPIENT_ID,
  label: 'Amélie Mukendi',
  numberMasked: '+2 *** ** 42',
  language: 'fr',
  notificationTypes: ['LEAVE_DECISION'],
  status: 'ACTIVE',
  consentSource: 'WRITTEN',
  consentConfirmedAt: '2026-09-30T12:00:00Z',
  version: 1,
  createdAt: '2026-10-01T10:00:00Z',
  updatedAt: '2026-10-01T10:00:00Z',
  ...overrides,
});

const delivery = (overrides: Record<string, unknown> = {}) => ({
  id: '88888888-8888-4888-8888-888888888888',
  recipientId: RECIPIENT_ID,
  recipientLabel: 'Amélie Mukendi',
  numberMasked: '+2 *** ** 42',
  notificationType: 'CONNECTOR_TEST',
  test: true,
  language: 'fr',
  state: 'RETRYING',
  attemptCount: 1,
  createdAt: '2026-10-01T10:00:00Z',
  lastAttemptAt: '2026-10-01T10:00:05Z',
  nextAttemptAt: '2026-10-01T10:01:05Z',
  acceptedAt: null,
  deliveredAt: null,
  readAt: null,
  completedAt: null,
  lastHttpStatus: null,
  failureClass: 'CONNECT_TIMEOUT',
  ...overrides,
});

type Reply = { status: number; body?: unknown; headers?: Record<string, string> } | 'network';
type Route = (request: Request, url: URL) => Reply | undefined;

const BASE = '/integrations/messaging/whatsapp';

function stubApi(
  route: Route = () => undefined,
  current: Record<string, unknown> | null = connector(),
) {
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
                      finishedAt: '2026-10-01T10:00:08Z',
                      httpStatus: null,
                      failureClass: 'CONNECT_TIMEOUT',
                      latencyMs: 3000,
                    },
                  ],
                  statusReports: [
                    {
                      status: 'DELIVERED',
                      occurredAt: '2026-10-01T10:00:20Z',
                      receivedAt: '2026-10-01T10:00:21Z',
                      errorClass: null,
                    },
                  ],
                },
              }
            : path.endsWith('/recipients')
              ? { status: 200, body: { items: [recipient()] } }
              : path.endsWith(BASE)
                ? current
                  ? { status: 200, body: current }
                  : { status: 404, body: problem('MESSAGING_CONNECTOR_NOT_FOUND', 404) }
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
  correlationId: 'corr-wa-1234',
});

async function renderPage(locale: 'fr' | 'en', roles = ['tenant-admin'] as const) {
  return renderWithSession(
    <RequireRole
      requiredRole="tenant-admin"
      deniedKey="whatsapp.unauthorized"
      signInKey="whatsapp.signInRequired"
    >
      <WhatsAppPage />
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

describe.each(['fr', 'en'] as const)('whatsapp (%s)', (locale) => {
  const c = resources[locale].common;
  const w = c.whatsapp;
  const templateLabel = (type: keyof typeof w.types, language: 'en' | 'fr') =>
    w.connect.templateName
      .replace('{{type}}', w.types[type])
      .replace('{{language}}', w.language[language]);

  it('shows the connect form when no connector exists, validates before sending', async () => {
    const requests = stubApi(() => undefined, null);
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    expect(await screen.findByRole('heading', { level: 1, name: w.title })).toBeTruthy();
    expect(await screen.findByRole('heading', { name: w.connect.title })).toBeTruthy();
    expect(screen.getByText(w.stopNote)).toBeTruthy();
    await user.click(screen.getByRole('button', { name: w.connect.create }));
    expect(await screen.findByText(w.errors.displayNumberInvalid)).toBeTruthy();
    expect(screen.getAllByText(w.errors.digitsInvalid)).toHaveLength(2);
    expect(screen.getByText(w.errors.tokenRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.templatesRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.confirmRequired)).toBeTruthy();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await expectAccessible(container);
  });

  it('connects once with a write-only token that is never echoed or stored', async () => {
    const requests = stubApi((request, url) => {
      if (request.method === 'POST' && url.pathname.endsWith(BASE)) {
        return { status: 201, body: connector() };
      }
      return undefined;
    }, null);
    const user = userEvent.setup();
    await renderPage(locale);
    await screen.findByRole('heading', { name: w.connect.title });
    await user.type(screen.getByLabelText(w.connect.wabaId), '100200300');
    await user.type(screen.getByLabelText(w.connect.phoneNumberId), '400500600');
    await user.type(screen.getByLabelText(w.connect.displayNumber), '+243999000111');
    await user.type(screen.getByLabelText(w.connect.accessToken), TOKEN);
    await user.type(
      screen.getByLabelText(templateLabel('CONNECTOR_TEST', 'fr')),
      'divalhr_test_fr',
    );
    await user.click(screen.getByLabelText(w.connect.confirm));
    await user.click(screen.getByRole('button', { name: w.connect.create }));

    expect(await screen.findByTestId('whatsapp-connector')).toBeTruthy();
    const post = requests.find((r) => r.method === 'POST');
    expect(post?.headers.get('Idempotency-Key')).toMatch(/^web-/);
    expect(await post?.clone().json()).toEqual({
      wabaId: '100200300',
      phoneNumberId: '400500600',
      displayNumber: '+243999000111',
      accessToken: TOKEN,
      templates: [
        { notificationType: 'CONNECTOR_TEST', language: 'fr', templateName: 'divalhr_test_fr' },
      ],
      confirm: true,
    });
    expect(document.body.textContent).not.toContain(TOKEN);
    expect(window.location.href).not.toContain(TOKEN);
    expect(setItem.mock.calls.flat().join(' ')).not.toContain(TOKEN);
  });

  it('shows the connector without any secret, with no-store requests, and is denied to employees', async () => {
    const requests = stubApi();
    const { container } = await renderPage(locale);
    expect(await screen.findByText('100200300')).toBeTruthy();
    expect(screen.getByTestId('connector-state').textContent).toBe(w.connectorState.DRAFT);
    expect(screen.getByText(w.connector.draftNote)).toBeTruthy();
    expect(requests.every((r) => r.cache === 'no-store' || r.method !== 'GET')).toBe(true);
    await expectAccessible(container);
  });

  it('denies employees', async () => {
    stubApi();
    await renderPage(locale, ['employee'] as never);
    expect(await screen.findByText(w.unauthorized)).toBeTruthy();
  });

  it('verifies only after an explicit confirmation and shows each check', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/verify')
        ? {
            status: 200,
            body: {
              connector: connector({ state: 'ACTIVE', verifiedAt: '2026-10-01T11:00:00Z' }),
              credential: 'VALID',
              templates: [
                { notificationType: 'CONNECTOR_TEST', language: 'fr', result: 'NOT_APPROVED' },
              ],
              verified: false,
            },
          }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.actions.verify }));
    const submit = screen.getByRole('button', { name: w.confirmAction.verify.submit });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await user.click(screen.getByLabelText(w.confirmAction.understand));
    await user.click(submit);
    const result = await screen.findByTestId('verification');
    expect(within(result).getByText(w.verification.notVerified)).toBeTruthy();
    expect(result.textContent).toContain(w.templateResult.NOT_APPROVED);
    const post = requests.find((r) => r.method === 'POST');
    expect(await post?.clone().json()).toEqual({ configVersion: 1, confirm: true });
  });

  it('rotates the token only with a token and clears it afterwards', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/rotate-token')
        ? { status: 200, body: connector({ credentialVersion: 2 }) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.actions.rotate }));
    await user.click(screen.getByLabelText(w.confirmAction.understand));
    await user.click(screen.getByRole('button', { name: w.confirmAction.rotate.submit }));
    expect(await screen.findByText(w.errors.tokenRequired)).toBeTruthy();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await user.type(screen.getByLabelText(w.rotate.token), TOKEN);
    await user.click(screen.getByRole('button', { name: w.confirmAction.rotate.submit }));
    await waitFor(() => {
      expect(screen.queryByTestId('confirm')).toBeNull();
    });
    const post = requests.find((r) => r.method === 'POST');
    expect(await post?.clone().json()).toEqual({
      configVersion: 1,
      accessToken: TOKEN,
      confirm: true,
    });
    expect(document.body.textContent).not.toContain(TOKEN);
    expect(setItem.mock.calls.flat().join(' ')).not.toContain(TOKEN);
  });

  it('shows a translated refusal with its reference', async () => {
    stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/verify')
        ? { status: 503, body: problem('MESSAGING_PROVIDER_UNAVAILABLE', 503) }
        : undefined,
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.actions.verify }));
    await user.click(screen.getByLabelText(w.confirmAction.understand));
    await user.click(screen.getByRole('button', { name: w.confirmAction.verify.submit }));
    const box = await screen.findByTestId('whatsapp-failure');
    expect(box.textContent).toContain(c.errors.MESSAGING_PROVIDER_UNAVAILABLE);
    expect(box.textContent).toContain('corr-wa-1234');
  });

  it('registers a recipient with a recorded consent and shows only the masked number', async () => {
    const requests = stubApi((request, url) =>
      request.method === 'POST' && url.pathname.endsWith('/recipients')
        ? { status: 201, body: recipient() }
        : undefined,
    );
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    await user.click(await screen.findByRole('button', { name: w.recipients.add }));
    await user.click(screen.getByRole('button', { name: w.recipients.form.create }));
    expect(await screen.findByText(w.errors.labelRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.phoneInvalid)).toBeTruthy();
    expect(screen.getByText(w.errors.typesRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.consentSourceRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.consentDateRequired)).toBeTruthy();
    expect(screen.getByText(w.errors.consentAttestRequired)).toBeTruthy();
    expect(requests.some((r) => r.method === 'POST')).toBe(false);
    await expectAccessible(container);

    await user.type(screen.getByLabelText(w.recipients.form.label), 'Amélie Mukendi');
    await user.type(screen.getByLabelText(w.recipients.form.phone), FULL_NUMBER);
    await user.click(screen.getByLabelText(w.types.LEAVE_DECISION));
    await user.selectOptions(
      screen.getByLabelText(w.recipients.form.consentSource),
      w.consentSource.WRITTEN,
    );
    await user.type(screen.getByLabelText(w.recipients.form.consentDate), '2026-09-30');
    await user.click(screen.getByLabelText(w.recipients.form.consentAttest));
    await user.click(screen.getByLabelText(w.recipients.form.confirm));
    await user.click(screen.getByRole('button', { name: w.recipients.form.create }));

    expect(await screen.findByTestId('recipients-table')).toBeTruthy();
    const post = requests.find((r) => r.method === 'POST');
    const sent = (await post?.clone().json()) as Record<string, unknown>;
    expect(sent).toMatchObject({
      label: 'Amélie Mukendi',
      phoneNumber: FULL_NUMBER,
      notificationTypes: ['LEAVE_DECISION'],
      consent: { source: 'WRITTEN', attested: true },
      confirm: true,
    });
    expect((sent.consent as { confirmedAt: string }).confirmedAt).toMatch(
      /^2026-09-30T12:00:00(\.000)?Z$/u,
    );
    expect(within(screen.getByTestId('recipients-table')).getByText('+2 *** ** 42')).toBeTruthy();
    expect(document.body.textContent).not.toContain(FULL_NUMBER);
    expect(setItem.mock.calls.flat().join(' ')).not.toContain(FULL_NUMBER);
  });

  it('sends a test message after confirmation and maps the rate limit', async () => {
    const requests = stubApi(
      (request, url) =>
        request.method === 'POST' && url.pathname.endsWith('/test-deliveries')
          ? {
              status: 429,
              headers: { 'Retry-After': '3600' },
              body: problem('MESSAGING_TEST_RATE_LIMITED', 429),
            }
          : undefined,
      connector({ state: 'ACTIVE', verifiedAt: '2026-10-01T11:00:00Z' }),
    );
    const user = userEvent.setup();
    await renderPage(locale);
    await user.click(
      await screen.findByRole('button', { name: `${w.recipients.actions.test} Amélie Mukendi` }),
    );
    await user.click(screen.getByRole('button', { name: w.recipients.confirm.testYes }));
    const box = await screen.findByTestId('whatsapp-failure');
    expect(box.textContent).toContain(c.errors.MESSAGING_TEST_RATE_LIMITED);
    expect(box.textContent).toMatch(/3600/);
    const post = requests.find((r) => r.method === 'POST');
    expect(await post?.clone().json()).toEqual({ confirm: true });
  });

  it('disables the test button until the connector is active', async () => {
    stubApi();
    await renderPage(locale);
    const test = await screen.findByRole('button', {
      name: `${w.recipients.actions.test} Amélie Mukendi`,
    });
    expect((test as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows the delivery history with closed classes and provider statuses', async () => {
    stubApi();
    const user = userEvent.setup();
    const { container } = await renderPage(locale);
    const row = await screen.findByTestId('history-row');
    expect(within(row).getByText(w.state.RETRYING)).toBeTruthy();
    expect(within(row).getByText(w.failure.CONNECT_TIMEOUT)).toBeTruthy();
    expect(within(row).getByText('+2 *** ** 42')).toBeTruthy();
    await user.click(within(row).getByRole('button', { name: w.history.show }));
    const detail = await screen.findByTestId('delivery-detail');
    expect(detail.textContent).toContain(w.callbackStatus.DELIVERED);
    await expectAccessible(container);
  });

  it('keeps nothing in the URL or browser storage', async () => {
    stubApi();
    await renderPage(locale);
    await screen.findByTestId('history-row');
    expect(window.location.search).toBe('');
    expect(window.location.hash).toBe('');
    expect(setItem).not.toHaveBeenCalled();
  });

  it('translates every messaging error code', () => {
    const codes = Object.keys(c.errors).filter((code) => code.startsWith('MESSAGING_'));
    expect(codes).toHaveLength(23);
    for (const code of codes) {
      expect((c.errors as Record<string, string>)[code]?.length).toBeGreaterThan(10);
    }
  });
});

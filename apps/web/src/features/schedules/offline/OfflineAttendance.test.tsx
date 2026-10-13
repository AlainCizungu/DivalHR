import { resources } from '@divalhr/localization';
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { useEffect, useState, type ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { renderWithSession, sessionWithRoles } from '../../../test/renderWithSession';
import { MyAttendancePage } from '../MyAttendancePage';
import { MySchedulePage } from '../MySchedulePage';
import { OfflineQueueProvider } from './OfflineAttendanceProvider';
import { formatDeviceTime } from './OfflineAttendancePanel';
import {
  captureEvent,
  memoryQueueStore,
  ownerScopeOf,
  parseEvent,
  type QueueStore,
  type QueuedEvent,
  type SendResult,
  type SyncPayload,
} from './offlineQueue';

// MVP-061B (B61B-9 items 1 to 4, 6 to 8, 10, 11, 13 to 15): the offline queue on My schedule and
// My attendance with an in-memory store (the IndexedDB store runs in the Playwright journeys).

const TENANT = '00000000-0000-4000-8000-00000000000a';
const SITE = '33333333-0000-4000-8000-000000000003';
const SCHEDULE = '55555555-0000-4000-8000-000000000005';
const SLOT = '66666666-0000-4000-8000-000000000006';
const SLOT_TWO = '77777777-0000-4000-8000-000000000007';
const RECORD = '88888888-0000-4000-8000-000000000008';
const OPEN_STORE_FAILURE = () => Promise.reject(new Error('no IndexedDB'));

const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: { slotId: SLOT },
  correlationId: 'corr-offline-1234',
});

type Reply = { status: number; body?: unknown } | 'network';
type Recorded = { method: string; path: string; body: string };

function stubApi(route: (request: Recorded) => Reply) {
  const requests: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (incoming: Request) => {
      const url = new URL(incoming.url);
      const recorded = {
        method: incoming.method,
        path: url.pathname.replace(/^.*\/api\/v1/u, '') + url.search,
        body: incoming.method === 'GET' ? '' : await incoming.clone().text(),
      };
      requests.push(recorded);
      const reply = /\/exceptions(\?|$)/u.test(recorded.path)
        ? {
            status: 200,
            body: { items: [], nextCursor: null, asOfInstant: '2099-11-02T06:00:00Z' },
          }
        : route(recorded);
      if (reply === 'network') throw new TypeError('Failed to fetch');
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: {
          'Content-Type': reply.status >= 400 ? 'application/problem+json' : 'application/json',
        },
      });
    }),
  );
  return requests;
}

const attendance = (overrides: Record<string, unknown> = {}) => ({
  status: 'NOT_RECORDED',
  attendanceRecordId: null,
  checkedInAt: null,
  checkedOutAt: null,
  checkInAvailable: true,
  checkOutAvailable: false,
  checkInRecordingMethod: null,
  checkInSupervisorReasonLocale: null,
  checkInSupervisorReason: null,
  checkOutRecordingMethod: null,
  checkOutSupervisorReasonLocale: null,
  checkOutSupervisorReason: null,
  ...overrides,
});

const shiftOf = (id: string, overrides: Record<string, unknown> = {}) => ({
  id,
  scheduleId: SCHEDULE,
  site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
  start: '2099-11-02T08:00',
  end: '2099-11-02T16:00',
  siteTimezone: 'Africa/Kinshasa',
  overnight: false,
  labels: { en: 'Cashier', fr: 'Caissière' },
  assignmentSource: 'PUBLISHED',
  attendance: attendance(),
  ...overrides,
});

const page = (items: unknown[]) => ({
  items,
  nextCursor: null,
  asOfInstant: '2099-11-02T06:00:00Z',
});
const emptyHistory = { status: 200, body: { items: [], nextCursor: null, asOfInstant: 'x' } };

const openRecord = {
  id: RECORD,
  status: 'OPEN',
  checkedInAt: '2099-11-02T06:55:00Z',
  checkedOutAt: null,
  checkOutAvailable: true,
  checkInRecordingMethod: 'SELF',
  checkInSupervisorReasonLocale: null,
  checkInSupervisorReason: null,
  checkOutRecordingMethod: null,
  checkOutSupervisorReasonLocale: null,
  checkOutSupervisorReason: null,
  shift: {
    scheduleId: SCHEDULE,
    slotId: SLOT,
    site: { id: SITE, code: 'KIN', name: 'Kinshasa Gombe' },
    siteTimezone: 'Africa/Kinshasa',
    start: '2099-11-02T08:00',
    end: '2099-11-02T16:00',
    startAt: '2099-11-02T07:00:00Z',
    endAt: '2099-11-02T15:00:00Z',
    overnight: false,
    labels: { en: 'Cashier', fr: 'Caissière' },
  },
};

function scheduleApi(extra: (request: Recorded) => Reply | undefined = () => undefined) {
  return stubApi((request) => {
    const answer = extra(request);
    if (answer) return answer;
    if (request.path.startsWith('/me/work-schedule-substitutions')) return emptyHistory;
    if (request.path.startsWith('/me/work-schedule')) {
      return { status: 200, body: page([shiftOf(SLOT), shiftOf(SLOT_TWO)]) };
    }
    if (request.path.startsWith('/me/attendance')) {
      return { status: 200, body: { items: [openRecord], nextCursor: null, asOfInstant: 'x' } };
    }
    return { status: 500, body: problem('INTERNAL_ERROR', 500) };
  });
}

/** The offline sender: answers in order, then `applied`; records every payload. */
function sender(answers: (SendResult | Promise<SendResult>)[] = []) {
  const sent: SyncPayload[] = [];
  const send = vi.fn((payload: SyncPayload) => {
    sent.push(structuredClone(payload));
    return Promise.resolve(answers.shift() ?? ({ kind: 'applied' } as const));
  });
  return { sent, send };
}

async function render(
  ui: ReactNode,
  options: {
    store?: QueueStore;
    ownerScope?: string | null;
    send?: (payload: SyncPayload) => Promise<SendResult>;
    locale?: 'en' | 'fr';
    openStore?: () => Promise<QueueStore>;
  } = {},
) {
  const store = options.store ?? memoryQueueStore();
  const openStore = options.openStore ?? (() => Promise.resolve(store));
  const send = options.send ?? sender().send;
  const tree = (scope: string | null) => (
    <OfflineQueueProvider ownerScope={scope} openStore={openStore} send={send}>
      {ui}
    </OfflineQueueProvider>
  );
  const owner =
    options.ownerScope === undefined ? await ownerScopeOf(TENANT, 'sub-ana') : options.ownerScope;
  const control: { set: (scope: string | null) => void } = { set: () => undefined };
  function Owner({ onReady }: { onReady: (set: (scope: string | null) => void) => void }) {
    const [scope, setScope] = useState<string | null>(owner);
    useEffect(() => {
      onReady(setScope);
    }, [onReady]);
    return tree(scope);
  }
  const ready = (set: (scope: string | null) => void) => {
    control.set = set;
  };
  const rendered = await renderWithSession(
    <Owner onReady={ready} />,
    sessionWithRoles(['employee']),
    options.locale ?? 'en',
  );
  return {
    ...rendered,
    store,
    owner,
    rerenderOwner: (scope: string | null) => {
      act(() => {
        control.set(scope);
      });
    },
  };
}

function stored(): string {
  const values: string[] = [];
  for (const storage of [window.localStorage, window.sessionStorage]) {
    for (let i = 0; i < storage.length; i += 1) {
      const key = storage.key(i);
      if (key) values.push(`${key}=${storage.getItem(key) ?? ''}`);
    }
  }
  return values.join('\n');
}

async function expectAccessible(container: HTMLElement) {
  const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
  expect(result.violations.map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

function setOnline(value: boolean) {
  vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(value);
}

const consoleCalls: unknown[][] = [];
beforeEach(() => {
  consoleCalls.length = 0;
  for (const level of ['log', 'info', 'warn', 'error', 'debug'] as const) {
    vi.spyOn(console, level).mockImplementation((...args: unknown[]) => {
      consoleCalls.push(args);
    });
  }
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  window.localStorage.clear();
  window.sessionStorage.clear();
});

/** Nothing queued reaches browser storage, the URL or the console. */
function expectNoLeak(values: string[]) {
  const console = JSON.stringify(consoleCalls);
  for (const value of values) {
    expect(stored()).not.toContain(value);
    expect(window.location.href).not.toContain(value);
    expect(console).not.toContain(value);
  }
}

describe('MVP-061B capture on My schedule', () => {
  it('online, the confirmation stays the MVP-060A command and nothing is queued', async () => {
    setOnline(true);
    const requests = scheduleApi((request) =>
      request.path === '/me/attendance/check-in'
        ? {
            status: 201,
            body: {
              attendanceRecordId: RECORD,
              slotId: SLOT,
              status: 'OPEN',
              checkedInAt: '2099-11-02T06:55:00Z',
              checkedOutAt: null,
            },
          }
        : undefined,
    );
    const { send } = sender();
    const user = userEvent.setup();
    const { store } = await render(<MySchedulePage />, { send });
    const [first] = (await screen.findAllByTestId('my-shift')) as [HTMLElement];
    await user.click(within(first).getByTestId('check-in'));
    const dialog = await screen.findByRole('dialog', { name: 'Check in' });
    expect(within(dialog).queryByTestId('attendance-dialog-offline')).toBeNull();
    await user.click(within(dialog).getByRole('button', { name: 'Confirm check-in' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(requests.filter((r) => r.method === 'POST').map((r) => r.path)).toEqual([
      '/me/attendance/check-in',
    ]);
    expect((store as ReturnType<typeof memoryQueueStore>).rows.size).toBe(0);
    expect(send).not.toHaveBeenCalled();
    expect(screen.getByTestId('my-schedule-offline-notice')).toHaveTextContent('');
  });

  it('offline, the confirmation saves one immutable event on this device and sends nothing', async () => {
    setOnline(false);
    const c = resources.en.common;
    const requests = scheduleApi();
    const user = userEvent.setup();
    const { store, owner, container } = await render(<MySchedulePage />);
    const [first, second] = (await screen.findAllByTestId('my-shift')) as [
      HTMLElement,
      HTMLElement,
    ];
    await user.click(within(first).getByTestId('check-in'));
    const dialog = await screen.findByRole('dialog', { name: 'Check in' });
    expect(within(dialog).getByTestId('attendance-dialog-offline')).toHaveTextContent(
      c.offlineAttendance.offlineConfirm,
    );
    // Offline the server records nothing now, so the online sentence is not shown.
    expect(dialog).not.toHaveTextContent(c.attendance.checkIn.confirm);
    await expectAccessible(container);
    const before = Date.now();
    await user.click(within(dialog).getByRole('button', { name: 'Confirm check-in' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(requests.filter((r) => r.method === 'POST')).toEqual([]);
    expect(screen.getByTestId('my-schedule-offline-notice')).toHaveTextContent(
      'Saved on this device — waiting to synchronize.',
    );
    const rows = [...(store as ReturnType<typeof memoryQueueStore>).rows.values()];
    expect(rows).toHaveLength(1);
    const event = parseEvent(rows[0]) as QueuedEvent;
    expect(event).toMatchObject({
      slotId: SLOT,
      type: 'CHECK_IN',
      ownerScope: owner,
      state: 'PENDING',
    });
    expect(Date.parse(event.deviceOccurredAt)).toBeGreaterThanOrEqual(before - 1);
    // The shift now says it is saved and offers no second check-in; the other shift still does.
    expect(within(first).queryByTestId('check-in')).toBeNull();
    expect(within(first).getByTestId('my-shift-offline-queued')).toHaveTextContent(
      'Check-in saved on this device — waiting to synchronize',
    );
    expect(within(second).getByTestId('check-in')).toBeInTheDocument();
    await expectAccessible(container);
    expectNoLeak([SLOT, event.clientEventId, event.deviceOccurredAt, owner ?? 'x']);
  });

  it('queues after a failure without an HTTP response, never after an HTTP refusal', async () => {
    setOnline(true);
    let refusal: Reply = { status: 409, body: problem('ATTENDANCE_ALREADY_CHECKED_IN', 409) };
    const requests = scheduleApi((request) => {
      if (request.path !== '/me/attendance/check-in') return undefined;
      return JSON.parse(request.body).slotId === SLOT ? 'network' : refusal;
    });
    const user = userEvent.setup();
    const { store } = await render(<MySchedulePage />);
    const rows = (store as ReturnType<typeof memoryQueueStore>).rows;
    const [first, second] = (await screen.findAllByTestId('my-shift')) as [
      HTMLElement,
      HTMLElement,
    ];

    await user.click(within(first).getByTestId('check-in'));
    await user.click(await screen.findByRole('button', { name: 'Confirm check-in' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
    expect(rows.size).toBe(1);

    for (const reply of [
      { status: 409, body: problem('ATTENDANCE_ALREADY_CHECKED_IN', 409) },
      { status: 404, body: problem('ATTENDANCE_SHIFT_NOT_FOUND', 404) },
      { status: 429, body: problem('RATE_LIMITED', 429) },
      { status: 422, body: problem('VALIDATION_FAILED', 422) },
    ]) {
      refusal = reply;
      await user.click(within(second).getByTestId('check-in'));
      await user.click(await screen.findByRole('button', { name: 'Confirm check-in' }));
      await screen.findByTestId('attendance-dialog-error');
      expect(rows.size).toBe(1);
      await user.click(screen.getByRole('button', { name: 'Cancel' }));
      await waitFor(() => {
        expect(screen.queryByRole('dialog')).toBeNull();
      });
    }
    // One network failure (queued) and four HTTP refusals (never queued).
    expect(requests.filter((r) => r.path === '/me/attendance/check-in')).toHaveLength(5);
    expect(rows.size).toBe(1);
  });

  it('a failed IndexedDB write is an error, never a queued success', async () => {
    setOnline(false);
    scheduleApi();
    const store = memoryQueueStore();
    store.addUnless = () => Promise.reject(new DOMException('quota', 'QuotaExceededError'));
    const user = userEvent.setup();
    await render(<MySchedulePage />, { store });
    const [first] = (await screen.findAllByTestId('my-shift')) as [HTMLElement];
    await user.click(within(first).getByTestId('check-in'));
    await user.click(await screen.findByRole('button', { name: 'Confirm check-in' }));
    expect(await screen.findByTestId('attendance-dialog-error')).toHaveTextContent(
      'This could not be saved on this device. Nothing was recorded.',
    );
    expect(screen.getByTestId('my-schedule-offline-notice')).toHaveTextContent('');
    expect(within(first).getByTestId('check-in')).toBeInTheDocument();
    expect(store.rows.size).toBe(0);
  });

  it('never queues a second event of the same slot and type', async () => {
    setOnline(false);
    scheduleApi();
    const user = userEvent.setup();
    const { store, owner } = await render(<MySchedulePage />);
    const [first] = (await screen.findAllByTestId('my-shift')) as [HTMLElement];
    await user.click(within(first).getByTestId('check-in'));
    const dialog = await screen.findByRole('dialog', { name: 'Check in' });
    // Saved meanwhile (another tab): the confirmation finds it and adds nothing.
    const other = captureEvent({ type: 'CHECK_IN', slotId: SLOT }, owner ?? '', new Date());
    (store as ReturnType<typeof memoryQueueStore>).rows.set(other.clientEventId, other);
    await user.click(within(dialog).getByRole('button', { name: 'Confirm check-in' }));
    expect(await within(dialog).findByTestId('attendance-dialog-duplicate')).toHaveTextContent(
      'This is already saved on this device and waiting to synchronize.',
    );
    expect(within(dialog).getByRole('button', { name: 'Confirm check-in' })).toBeDisabled();
    expect((store as ReturnType<typeof memoryQueueStore>).rows.size).toBe(1);
  });
});

describe('MVP-061B « Présence hors connexion » / "Offline attendance" on My attendance', () => {
  function seeded(owner: string) {
    const pending = captureEvent(
      { type: 'CHECK_OUT', slotId: SLOT },
      owner,
      new Date('2099-11-02T15:05:00.000Z'),
    );
    const attention: QueuedEvent = {
      ...captureEvent(
        { type: 'CHECK_IN', slotId: SLOT_TWO },
        owner,
        new Date('2099-11-02T06:50:00.000Z'),
      ),
      state: 'NEEDS_ATTENTION',
      attempts: 1,
      reason: 'OFFLINE_ATTENDANCE_CONFLICT',
    };
    const unreadable = {
      ownerScope: owner,
      schemaVersion: 7,
      createdAt: '2099-11-02T07:00:00.000Z',
    };
    return {
      pending,
      attention,
      store: memoryQueueStore([
        { key: pending.clientEventId, value: pending },
        { key: attention.clientEventId, value: attention },
        { key: 'unreadable', value: unreadable },
      ]),
    };
  }

  it('lists the owner’s saved events, synchronizes on demand and discards with confirmation', async () => {
    setOnline(true);
    const owner = await ownerScopeOf(TENANT, 'sub-ana');
    const { pending, attention, store } = seeded(owner);
    let lists = 0;
    scheduleApi((request) => {
      if (request.path.startsWith('/me/attendance') && request.method === 'GET') lists += 1;
      return undefined;
    });
    // The first (automatic) pass meets a network failure; "Sync now" then applies it.
    const { sent, send } = sender([{ kind: 'network' }, { kind: 'applied' }]);
    const user = userEvent.setup();
    const { container } = await render(<MyAttendancePage />, { store, send });
    const panel = await screen.findByTestId('offline-attendance');
    expect(within(panel).getByRole('heading', { level: 2 })).toHaveTextContent(
      'Offline attendance',
    );
    expect(within(panel).getByTestId('offline-connection')).toHaveTextContent('Connection: online');
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-status')).toHaveTextContent(
        'The connection is unavailable. Saved attendance stays on this device',
      ),
    );
    expect(within(panel).getByTestId('offline-count')).toHaveTextContent(
      '3 attendance events saved on this device',
    );
    const entries = within(panel).getAllByTestId('offline-entry');
    expect(entries.map((entry) => entry.dataset.state)).toEqual([
      'NEEDS_ATTENTION',
      'UNREADABLE',
      'PENDING',
    ]);
    const [needs, broken, waiting] = entries as [HTMLElement, HTMLElement, HTMLElement];
    expect(waiting).toHaveTextContent('Check-out');
    expect(waiting).toHaveTextContent('Pending sync');
    expect(within(waiting).getByTestId('offline-entry-time')).toHaveTextContent(
      `Time recorded on this device: ${formatDeviceTime('en', pending.deviceOccurredAt)}`,
    );
    expect(within(waiting).queryByTestId('offline-discard')).toBeNull();
    expect(needs).toHaveTextContent('Needs attention');
    expect(within(needs).getByTestId('offline-entry-reason')).toHaveTextContent(
      resources.en.common.errors.OFFLINE_ATTENDANCE_CONFLICT,
    );
    expect(broken).toHaveTextContent('This saved item cannot be read and will never be sent.');
    // The record already saved for check-out offers no second check-out.
    const record = await screen.findByTestId('my-attendance-record');
    expect(within(record).queryByTestId('check-out')).toBeNull();
    expect(within(record).getByTestId('my-attendance-offline-queued')).toBeInTheDocument();
    expect(panel.textContent).not.toMatch(
      new RegExp(`${SLOT}|${SLOT_TWO}|${pending.clientEventId}`, 'u'),
    );
    await expectAccessible(container);

    const listsBefore = lists;
    await user.click(within(panel).getByTestId('offline-sync-now'));
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-status')).toHaveTextContent(
        '1 saved attendance event was synchronized.',
      ),
    );
    expect(sent).toEqual([
      expect.objectContaining({ clientEventId: pending.clientEventId }),
      expect.objectContaining({ clientEventId: pending.clientEventId }),
    ]);
    expect(sent[0]).toEqual(sent[1]);
    await waitFor(() => {
      expect(lists).toBeGreaterThan(listsBefore);
    });
    expect(store.rows.has(pending.clientEventId)).toBe(false);
    expect(within(panel).queryByTestId('offline-sync-now')).toBeNull();

    await user.click(
      within(panel).getByRole('button', {
        name: `Discard the Check-in with the time recorded on this device ${formatDeviceTime('en', attention.deviceOccurredAt)}`,
      }),
    );
    const dialog = await screen.findByRole('dialog', { name: 'Discard this saved attendance?' });
    await waitFor(() =>
      expect(within(dialog).getByTestId('offline-discard-confirm')).toHaveFocus(),
    );
    await expectAccessible(container);
    await user.click(within(dialog).getByTestId('offline-discard-confirm'));
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-status')).toHaveTextContent(
        'The saved attendance was discarded.',
      ),
    );
    expect(store.rows.has(attention.clientEventId)).toBe(false);
    await user.click(
      within(panel).getByRole('button', { name: 'Discard the saved item that cannot be read' }),
    );
    await user.click(await screen.findByTestId('offline-discard-confirm'));
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-count')).toHaveTextContent(
        'Nothing is waiting on this device.',
      ),
    );
    expect(store.rows.size).toBe(0);
  });

  it('shows the states, reasons and actions in French', async () => {
    setOnline(true);
    const c = resources.fr.common;
    const owner = await ownerScopeOf(TENANT, 'sub-ana');
    const { store } = seeded(owner);
    scheduleApi();
    const { send } = sender([{ kind: 'refused', status: 401, code: 'AUTHENTICATION_REQUIRED' }]);
    const { container } = await render(<MyAttendancePage />, { store, send, locale: 'fr' });
    const panel = await screen.findByTestId('offline-attendance');
    expect(within(panel).getByRole('heading', { level: 2 })).toHaveTextContent(
      'Présence hors connexion',
    );
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-status')).toHaveTextContent(
        c.offlineAttendance.stopped.auth,
      ),
    );
    expect(within(panel).getByTestId('offline-connection')).toHaveTextContent(
      'Connexion : en ligne',
    );
    expect(within(panel).getByTestId('offline-count')).toHaveTextContent(
      '3 pointages enregistrés sur cet appareil',
    );
    expect(panel).toHaveTextContent('En attente de synchronisation');
    expect(panel).toHaveTextContent('Action requise');
    expect(panel).toHaveTextContent('Heure enregistrée sur cet appareil');
    expect(panel).toHaveTextContent(c.errors.OFFLINE_ATTENDANCE_CONFLICT);
    expect(within(panel).getByTestId('offline-sync-now')).toHaveTextContent(
      'Synchroniser maintenant',
    );
    expect(within(panel).getAllByTestId('offline-discard')[0]).toHaveTextContent('Supprimer');
    await expectAccessible(container);
  });

  it('shows the offline status in words and no "Sync now" while offline', async () => {
    setOnline(false);
    const owner = await ownerScopeOf(TENANT, 'sub-ana');
    const { store } = seeded(owner);
    scheduleApi();
    const { send } = sender();
    await render(<MyAttendancePage />, { store, send });
    const panel = await screen.findByTestId('offline-attendance');
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-count')).toHaveTextContent('3 attendance events'),
    );
    expect(within(panel).getByTestId('offline-connection')).toHaveTextContent(
      'Connection: offline',
    );
    expect(within(panel).queryByTestId('offline-sync-now')).toBeNull();
    expect(send).not.toHaveBeenCalled();
  });

  it('says when this browser cannot save attendance offline', async () => {
    setOnline(true);
    scheduleApi();
    await render(<MyAttendancePage />, { openStore: OPEN_STORE_FAILURE });
    expect(await screen.findByTestId('offline-unavailable')).toHaveTextContent(
      'Offline saving is not available in this browser.',
    );
  });
});

describe('MVP-061B owners, sign-out and foreground passes', () => {
  it('another account never sees, counts or synchronizes the rows; the owner finds them again', async () => {
    setOnline(true);
    const ana = await ownerScopeOf(TENANT, 'sub-ana');
    const ben = await ownerScopeOf(TENANT, 'sub-ben');
    const anas = captureEvent({ type: 'CHECK_IN', slotId: SLOT }, ana, new Date());
    const store = memoryQueueStore([{ key: anas.clientEventId, value: anas }]);
    scheduleApi();
    const { sent, send } = sender([{ kind: 'network' }]);
    const { rerenderOwner } = await render(<MyAttendancePage />, { store, send, ownerScope: ben });
    const panel = await screen.findByTestId('offline-attendance');
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-count')).toHaveTextContent(
        'Nothing is waiting on this device.',
      ),
    );
    expect(within(panel).queryAllByTestId('offline-entry')).toHaveLength(0);
    expect(sent).toEqual([]);
    // Signed out: nothing is shown and the row stays.
    rerenderOwner(null);
    await waitFor(() => {
      expect(within(panel).queryByTestId('offline-count')).toBeNull();
    });
    expect(store.rows.has(anas.clientEventId)).toBe(true);
    // Ana signs in again: her row is back (and one pass runs for her).
    rerenderOwner(ana);
    await waitFor(() =>
      expect(within(panel).getByTestId('offline-count')).toHaveTextContent(
        '1 attendance event saved on this device',
      ),
    );
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    expect(sent[0]?.clientEventId).toBe(anas.clientEventId);
  });

  it('runs one pass at a time per tab, oldest first', async () => {
    setOnline(true);
    const owner = await ownerScopeOf(TENANT, 'sub-ana');
    const older = captureEvent(
      { type: 'CHECK_IN', slotId: SLOT },
      owner,
      new Date('2099-11-02T06:00:00Z'),
    );
    const newer = captureEvent(
      { type: 'CHECK_IN', slotId: SLOT_TWO },
      owner,
      new Date('2099-11-02T07:00:00Z'),
    );
    const store = memoryQueueStore([
      { key: newer.clientEventId, value: newer },
      { key: older.clientEventId, value: older },
    ]);
    scheduleApi();
    let release: (value: SendResult) => void = () => undefined;
    const gate = new Promise<SendResult>((resolve) => {
      release = resolve;
    });
    const { sent, send } = sender([gate]);
    await render(<MyAttendancePage />, { store, send });
    await waitFor(() => {
      expect(sent).toHaveLength(1);
    });
    // While the first request is outstanding, more triggers start no second pass.
    act(() => {
      window.dispatchEvent(new Event('online'));
      window.dispatchEvent(new Event('online'));
    });
    await screen.findByTestId('offline-sync-now');
    expect(screen.getByTestId('offline-sync-now')).toBeDisabled();
    expect(sent).toHaveLength(1);
    release({ kind: 'applied' });
    await waitFor(() => {
      expect(store.rows.size).toBe(0);
    });
    expect(sent.map((payload) => payload.clientEventId)).toEqual([
      older.clientEventId,
      newer.clientEventId,
    ]);
  });
});

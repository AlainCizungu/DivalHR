import { describe, expect, it } from 'vitest';
import {
  MAX_ATTEMPTS,
  QUEUE_LIFETIME_MS,
  captureEvent,
  deviceTimeOf,
  enqueue,
  memoryQueueStore,
  openQueue,
  ownerScopeOf,
  parseEvent,
  patchOwned,
  payloadOf,
  removeOwned,
  synchronize,
  type QueuedEvent,
  type SendResult,
  type SyncPayload,
} from './offlineQueue';

// MVP-061B (B61B-9 items 4, 5, 6, 7, 8, 9, 10, 11, 12): the queue and the synchronization pass,
// independent of IndexedDB (the browser store is covered by the Playwright journeys).

const TENANT = '00000000-0000-4000-8000-00000000000a';
const SUBJECT = 'kc-subject-ana-0001';
const SLOT_A = '66666666-0000-4000-8000-000000000006';
const SLOT_B = '77777777-0000-4000-8000-000000000007';
const NOW = new Date('2099-11-02T07:15:30.123Z');

/** A Date whose zone offset is fixed (minutes east of UTC), whatever the test machine's zone. */
function at(instant: string, offsetMinutes: number): Date {
  const shifted = new Date(Date.parse(instant) + offsetMinutes * 60_000);
  const date = new Date(instant);
  date.getTimezoneOffset = () => -offsetMinutes;
  date.getFullYear = () => shifted.getUTCFullYear();
  date.getMonth = () => shifted.getUTCMonth();
  date.getDate = () => shifted.getUTCDate();
  date.getHours = () => shifted.getUTCHours();
  date.getMinutes = () => shifted.getUTCMinutes();
  date.getSeconds = () => shifted.getUTCSeconds();
  date.getMilliseconds = () => shifted.getUTCMilliseconds();
  return date;
}

async function owners() {
  return {
    ana: await ownerScopeOf(TENANT, SUBJECT),
    ben: await ownerScopeOf(TENANT, 'kc-subject-ben-0002'),
  };
}

function event(owner: string, overrides: Partial<QueuedEvent> = {}): QueuedEvent {
  return {
    ...captureEvent({ type: 'CHECK_IN', slotId: SLOT_A }, owner, at(NOW.toISOString(), 60)),
    ...overrides,
  };
}

function sender(answers: SendResult[]) {
  const sent: SyncPayload[] = [];
  return {
    sent,
    send: (payload: SyncPayload) => {
      sent.push(structuredClone(payload));
      return Promise.resolve<SendResult>(answers.shift() ?? { kind: 'applied' });
    },
  };
}

describe('MVP-061B capture', () => {
  it('writes the device time with its explicit offset to the millisecond, once', () => {
    expect(deviceTimeOf(at('2099-11-02T07:15:30.123Z', 60))).toEqual({
      deviceOccurredAt: '2099-11-02T08:15:30.123+01:00',
      deviceOffsetMinutes: 60,
    });
    expect(deviceTimeOf(at('2099-11-02T07:15:30.000Z', -270)).deviceOccurredAt).toBe(
      '2099-11-02T02:45:30.000-04:30',
    );
    // UTC is +00:00, never -00:00.
    expect(deviceTimeOf(at('2099-11-02T07:15:30.000Z', 0)).deviceOccurredAt).toBe(
      '2099-11-02T07:15:30.000+00:00',
    );
    expect(deviceTimeOf(at('2099-11-02T07:15:30.000Z', 840)).deviceOffsetMinutes).toBe(840);
    expect(() => deviceTimeOf(at('2099-11-02T07:15:30.000Z', 841))).toThrow(RangeError);
  });

  it('keeps only the MVP-061A fields and bounded metadata: no identity, credential or label', async () => {
    const { ana } = await owners();
    const captured = captureEvent(
      { type: 'CHECK_OUT', slotId: SLOT_A.toUpperCase() },
      ana,
      at(NOW.toISOString(), 60),
    );
    expect(Object.keys(captured).sort()).toEqual([
      'attempts',
      'clientEventId',
      'createdAt',
      'deviceOccurredAt',
      'deviceOffsetMinutes',
      'ownerScope',
      'reason',
      'schemaVersion',
      'slotId',
      'state',
      'type',
    ]);
    expect(captured).toMatchObject({
      schemaVersion: 1,
      slotId: SLOT_A,
      type: 'CHECK_OUT',
      deviceOccurredAt: '2099-11-02T08:15:30.123+01:00',
      deviceOffsetMinutes: 60,
      createdAt: NOW.toISOString(),
      state: 'PENDING',
      attempts: 0,
      reason: null,
    });
    expect(captured.clientEventId).toMatch(/^[0-9a-f-]{36}$/u);
    const serialized = JSON.stringify(captured);
    for (const forbidden of [TENANT, SUBJECT, 'Bearer', 'token', 'Cashier', 'Caissière']) {
      expect(serialized).not.toContain(forbidden);
    }
    expect(parseEvent(captured)).toEqual(captured);
  });

  it('derives a domain-separated owner scope that is not the tenant or the subject', async () => {
    const { ana, ben } = await owners();
    expect(ana).toMatch(/^[0-9a-f]{64}$/u);
    expect(ana).toBe(await ownerScopeOf(TENANT, SUBJECT));
    expect(ben).not.toBe(ana);
    expect(await ownerScopeOf('00000000-0000-4000-8000-00000000000b', SUBJECT)).not.toBe(ana);
    // The separator makes tenant + subject unambiguous.
    expect(await ownerScopeOf(`${TENANT}\nsubject=x`, 'y')).not.toBe(
      await ownerScopeOf(TENANT, 'x\nsubject=y'),
    );
  });

  it('never guesses an unsupported or malformed row', async () => {
    const { ana } = await owners();
    const good = event(ana);
    expect(parseEvent({ ...good, schemaVersion: 2 })).toBeNull();
    expect(parseEvent({ ...good, extra: 'x' })).toBeNull();
    expect(parseEvent({ ...good, deviceOffsetMinutes: 0 })).toBeNull();
    expect(parseEvent({ ...good, deviceOccurredAt: '2099-11-02T07:15:30.123-00:00' })).toBeNull();
    expect(parseEvent({ ...good, type: 'BREAK' })).toBeNull();
    expect(parseEvent({ ...good, reason: 'Some free text' })).toBeNull();
    expect(parseEvent({ ...good, attempts: MAX_ATTEMPTS + 1 })).toBeNull();
    expect(parseEvent('text')).toBeNull();
  });
});

describe('MVP-061B queue', () => {
  it('keeps one pending event per owner, slot and type', async () => {
    const { ana, ben } = await owners();
    const store = memoryQueueStore();
    expect(await enqueue(store, event(ana))).toBe('added');
    expect(await enqueue(store, event(ana))).toBe('duplicate');
    expect(await enqueue(store, event(ana, { type: 'CHECK_OUT' }))).toBe('added');
    expect(await enqueue(store, event(ana, { slotId: SLOT_B }))).toBe('added');
    expect(await enqueue(store, event(ben))).toBe('added');
    expect(store.rows.size).toBe(4);
  });

  it('expires the owner’s rows after 7 days when opened and returns unreadable rows unguessed', async () => {
    const { ana, ben } = await owners();
    const old = event(ana, {
      createdAt: new Date(NOW.getTime() - QUEUE_LIFETIME_MS - 1).toISOString(),
    });
    const edge = event(ana, {
      slotId: SLOT_B,
      createdAt: new Date(NOW.getTime() - QUEUE_LIFETIME_MS).toISOString(),
    });
    const theirs = event(ben, {
      createdAt: new Date(NOW.getTime() - QUEUE_LIFETIME_MS - 1).toISOString(),
    });
    const store = memoryQueueStore([
      { key: old.clientEventId, value: old },
      { key: edge.clientEventId, value: edge },
      { key: theirs.clientEventId, value: theirs },
      { key: 'bad', value: { ownerScope: ana, schemaVersion: 9, createdAt: NOW.toISOString() } },
    ]);
    const opened = await openQueue(store, ana, NOW);
    expect(opened.map((entry) => entry.key)).toEqual([edge.clientEventId, 'bad']);
    expect(opened[1]?.event).toBeNull();
    // Another owner's expired row is left for that owner to open.
    expect(store.rows.has(theirs.clientEventId)).toBe(true);
    expect(store.rows.has(old.clientEventId)).toBe(false);
  });

  it('isolates owners: another owner cannot list, patch, discard or synchronize', async () => {
    const { ana, ben } = await owners();
    const anas = event(ana);
    const store = memoryQueueStore([{ key: anas.clientEventId, value: anas }]);
    expect(await openQueue(store, ben, NOW)).toEqual([]);
    await patchOwned(store, ben, anas.clientEventId, {
      state: 'NEEDS_ATTENTION',
      attempts: 1,
      reason: null,
    });
    await removeOwned(store, ben, anas.clientEventId);
    const { sent, send } = sender([]);
    expect(await synchronize(store, ben, NOW, send)).toEqual({
      applied: 0,
      attention: 0,
      stopped: null,
    });
    expect(sent).toEqual([]);
    expect(store.rows.get(anas.clientEventId)).toEqual(anas);
    // The original owner, signed in again, still has it.
    expect((await openQueue(store, ana, NOW)).map((entry) => entry.event)).toEqual([anas]);
  });
});

describe('MVP-061B synchronization pass', () => {
  async function three() {
    const { ana } = await owners();
    const first = event(ana, { createdAt: '2099-11-02T07:00:00.000Z' });
    const second = event(ana, {
      slotId: SLOT_B,
      createdAt: '2099-11-02T07:05:00.000Z',
    });
    const third = event(ana, {
      type: 'CHECK_OUT',
      createdAt: '2099-11-02T07:10:00.000Z',
    });
    const store = memoryQueueStore(
      [third, first, second].map((value) => ({ key: value.clientEventId, value })),
    );
    return { ana, store, first, second, third };
  }

  it('sends oldest first, one at a time, and deletes applied and already-applied events', async () => {
    const { ana, store, first, second, third } = await three();
    const { sent, send } = sender([{ kind: 'applied' }, { kind: 'applied' }, { kind: 'applied' }]);
    expect(await synchronize(store, ana, NOW, send)).toEqual({
      applied: 3,
      attention: 0,
      stopped: null,
    });
    expect(sent).toEqual([payloadOf(first), payloadOf(second), payloadOf(third)]);
    expect(Object.keys(sent[0] ?? {}).sort()).toEqual([
      'clientEventId',
      'deviceOccurredAt',
      'deviceOffsetMinutes',
      'slotId',
      'type',
    ]);
    expect(store.rows.size).toBe(0);
  });

  it.each([
    [{ kind: 'network' } as const, 'network'],
    [{ kind: 'refused', status: 401, code: 'AUTHENTICATION_REQUIRED' } as const, 'auth'],
    [{ kind: 'refused', status: 403, code: 'EMPLOYEE_LINK_REQUIRED' } as const, 'forbidden'],
    [{ kind: 'refused', status: 429, code: 'RATE_LIMITED' } as const, 'rateLimited'],
    [{ kind: 'refused', status: 500, code: 'INTERNAL_ERROR' } as const, 'server'],
    [{ kind: 'refused', status: 503, code: undefined } as const, 'server'],
  ])('keeps the row pending and stops on %o', async (answer, stopped) => {
    const { ana, store, first } = await three();
    const { sent, send } = sender([answer]);
    expect(await synchronize(store, ana, NOW, send)).toEqual({ applied: 0, attention: 0, stopped });
    expect(sent).toHaveLength(1);
    const kept = parseEvent(store.rows.get(first.clientEventId));
    expect(kept).toEqual({ ...first, attempts: 1 });
    expect(store.rows.size).toBe(3);
  });

  it('retries with the same client event ID, device time and offset', async () => {
    const { ana, store, first } = await three();
    const { sent, send } = sender([
      { kind: 'network' },
      { kind: 'refused', status: 503, code: undefined },
      { kind: 'applied' },
    ]);
    await synchronize(store, ana, NOW, send);
    await synchronize(store, ana, NOW, send);
    await synchronize(store, ana, NOW, send);
    expect(sent.slice(0, 3)).toEqual([payloadOf(first), payloadOf(first), payloadOf(first)]);
    expect(store.rows.has(first.clientEventId)).toBe(false);
  });

  it.each([
    [400, 'VALIDATION_FAILED', 'VALIDATION_FAILED'],
    [404, 'ATTENDANCE_SHIFT_NOT_FOUND', 'ATTENDANCE_SHIFT_NOT_FOUND'],
    [409, 'OFFLINE_ATTENDANCE_CONFLICT', 'OFFLINE_ATTENDANCE_CONFLICT'],
    [409, 'IDEMPOTENCY_KEY_REUSED', 'IDEMPOTENCY_KEY_REUSED'],
    [422, 'OFFLINE_ATTENDANCE_OFFSET_MISMATCH', 'OFFLINE_ATTENDANCE_OFFSET_MISMATCH'],
    [409, 'SOMETHING_NEW', 'GENERIC'],
  ])('marks a permanent %i %s as needing attention and goes on', async (status, code, reason) => {
    const { ana, store, first, second } = await three();
    const { sent, send } = sender([{ kind: 'refused', status, code }]);
    expect(await synchronize(store, ana, NOW, send)).toEqual({
      applied: 2,
      attention: 1,
      stopped: null,
    });
    expect(sent).toHaveLength(3);
    expect(parseEvent(store.rows.get(first.clientEventId))).toEqual({
      ...first,
      state: 'NEEDS_ATTENTION',
      attempts: 1,
      reason,
    });
    expect(store.rows.has(second.clientEventId)).toBe(false);
    // A row needing attention is never sent again; the employee discards it.
    const again = sender([]);
    await synchronize(store, ana, NOW, again.send);
    expect(again.sent).toEqual([]);
    await removeOwned(store, ana, first.clientEventId);
    expect(store.rows.size).toBe(0);
  });

  it('never submits an unreadable row', async () => {
    const { ana } = await owners();
    const store = memoryQueueStore([
      { key: 'bad', value: { ownerScope: ana, schemaVersion: 2, createdAt: NOW.toISOString() } },
    ]);
    const { sent, send } = sender([]);
    await synchronize(store, ana, NOW, send);
    expect(sent).toEqual([]);
    expect(store.rows.has('bad')).toBe(true);
  });
});

/**
 * MVP-061B (Issue #124, B61B-2 to B61B-5): the browser-held queue of attendance events captured
 * while offline, and the one foreground synchronization pass that sends them through MVP-061A.
 *
 * A row holds only what MVP-061A needs plus bounded attempt metadata: the schema version, the
 * client event ID, the slot, the type, the claimed device time with its explicit offset and the
 * offset in minutes, the creation instant, the owner scope, the state, an attempt count and an
 * allow-listed refusal code. Never a token, cookie, code, PKCE material, tenant, subject, employee
 * identity, name, number, shift or site label, reason, response body or manager identity.
 *
 * The owner scope is a SHA-256 of a domain-separated string of the verified session's tenant and
 * subject. It separates the people who use one browser; it is not encryption, and the rows are
 * not encrypted by DivalHR.
 *
 * Everything here is storage-agnostic: {@link QueueStore} is IndexedDB in the browser
 * (indexedDbStore.ts) and an in-memory map in tests.
 */

/** The only schema this code writes or submits. */
export const QUEUE_SCHEMA_VERSION = 1;

/** Rows older than this are deleted when the queue is next opened (no background timer). */
export const QUEUE_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000;

/** The attempt counter stops here (bounded metadata). */
export const MAX_ATTEMPTS = 99;

/** The device UTC offset bound accepted by MVP-061A, in minutes either side of UTC. */
export const MAX_OFFSET_MINUTES = 840;

export type OfflineType = 'CHECK_IN' | 'CHECK_OUT';
export type QueueState = 'PENDING' | 'NEEDS_ATTENTION';

/**
 * Stable refusal codes kept on a row that needs attention; anything else is kept as `GENERIC`.
 * Each has a neutral EN/FR message (never a response body).
 */
export const ATTENTION_REASONS = [
  'OFFLINE_ATTENDANCE_TOO_OLD',
  'OFFLINE_ATTENDANCE_FUTURE_TIME',
  'OFFLINE_ATTENDANCE_OFFSET_MISMATCH',
  'OFFLINE_ATTENDANCE_CONFLICT',
  'ATTENDANCE_SHIFT_NOT_FOUND',
  'ATTENDANCE_RECORD_NOT_FOUND',
  'ATTENDANCE_CHECK_IN_TOO_EARLY',
  'ATTENDANCE_CHECK_IN_WINDOW_CLOSED',
  'ATTENDANCE_CHECK_OUT_WINDOW_CLOSED',
  'ATTENDANCE_STATE_CONFLICT',
  'IDEMPOTENCY_KEY_REUSED',
  'VALIDATION_FAILED',
  'GENERIC',
] as const;
export type AttentionReason = (typeof ATTENTION_REASONS)[number];

/** One queued event (schema version 1). */
export interface QueuedEvent {
  schemaVersion: 1;
  clientEventId: string;
  slotId: string;
  type: OfflineType;
  deviceOccurredAt: string;
  deviceOffsetMinutes: number;
  createdAt: string;
  ownerScope: string;
  state: QueueState;
  attempts: number;
  reason: AttentionReason | null;
}

/** A stored row of the owner as read back: the parsed event, or `null` when it cannot be read. */
export interface QueueEntry {
  key: string;
  event: QueuedEvent | null;
  /** The creation instant of a row that cannot be read, when even that is readable. */
  createdAt: string | null;
}

/** The only fields a stored row may change after it was written. */
export type QueuePatch = Pick<QueuedEvent, 'state' | 'attempts' | 'reason'>;

/**
 * Where the queue lives: a mechanism only. Every ownership and uniqueness decision is made by the
 * functions of this module (owner isolation, one event per slot and type, immutable fields), so it
 * holds for every store.
 */
export interface QueueStore {
  /** Every stored row, of any owner, in no particular order. */
  all(): Promise<{ key: string; value: unknown }[]>;
  /** In one atomic step: reads every value and adds `value` under `key` unless `refuse` says so. */
  addUnless(key: string, value: object, refuse: (values: unknown[]) => boolean): Promise<boolean>;
  /** In one atomic step: replaces the row with `change(value)` when that returns an object. */
  update(key: string, change: (value: unknown) => object | null): Promise<void>;
  /** In one atomic step: deletes the row when `allowed(value)`. */
  delete(key: string, allowed: (value: unknown) => boolean): Promise<void>;
}

/** Whether a stored value belongs to the owner scope. */
export function ownedBy(ownerScope: string): (value: unknown) => boolean {
  return (value) =>
    typeof value === 'object' &&
    value !== null &&
    (value as { ownerScope?: unknown }).ownerScope === ownerScope;
}

/** Adds the event unless its owner already has a readable row of the same slot and type. */
export async function enqueue(
  store: QueueStore,
  event: QueuedEvent,
): Promise<'added' | 'duplicate'> {
  const added = await store.addUnless(event.clientEventId, event, (values) =>
    values.some((value) => {
      const other = parseEvent(value);
      return (
        other?.ownerScope === event.ownerScope &&
        other.slotId === event.slotId &&
        other.type === event.type
      );
    }),
  );
  return added ? 'added' : 'duplicate';
}

/** Changes only the state metadata of one of the owner's rows. */
export function patchOwned(
  store: QueueStore,
  ownerScope: string,
  key: string,
  patch: QueuePatch,
): Promise<void> {
  const owned = ownedBy(ownerScope);
  return store.update(key, (value) =>
    owned(value)
      ? { ...(value as object), state: patch.state, attempts: patch.attempts, reason: patch.reason }
      : null,
  );
}

/** Deletes one of the owner's rows; another owner's row is never touched. */
export function removeOwned(store: QueueStore, ownerScope: string, key: string): Promise<void> {
  return store.delete(key, ownedBy(ownerScope));
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/u;
const SCOPE = /^[0-9a-f]{64}$/u;
const DEVICE_TIME = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}(Z|([+-])(\d{2}):(\d{2}))$/u;
const FIELDS = [
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
].join(',');

/**
 * The owner scope of a verified session: SHA-256 (Web Crypto) of a domain-separated,
 * length-prefixed string of the tenant and the subject, as lower-case hex. Never stored or shown in clear.
 */
export async function ownerScopeOf(tenantId: string, subject: string): Promise<string> {
  // Length-prefixed, so no tenant and subject pair can collide with another.
  const text =
    `DIVALHR-OFFLINE-ATTENDANCE-OWNER\nversion=1\ntenant=${tenantId.length}:${tenantId}` +
    `\nsubject=${subject.length}:${subject}`;
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

const two = (value: number) => String(value).padStart(2, '0');

/**
 * The device's local time with its explicit offset, to the millisecond (RFC 3339, the shape
 * MVP-061A accepts): `2026-11-02T08:15:30.123+01:00`. UTC is `+00:00`, never `-00:00`.
 */
export function deviceTimeOf(now: Date): { deviceOccurredAt: string; deviceOffsetMinutes: number } {
  const offset = -now.getTimezoneOffset();
  if (!Number.isInteger(offset) || Math.abs(offset) > MAX_OFFSET_MINUTES) {
    throw new RangeError('device offset out of range');
  }
  const sign = offset < 0 ? '-' : '+';
  const magnitude = Math.abs(offset);
  const local =
    `${String(now.getFullYear()).padStart(4, '0')}-${two(now.getMonth() + 1)}-${two(now.getDate())}` +
    `T${two(now.getHours())}:${two(now.getMinutes())}:${two(now.getSeconds())}` +
    `.${String(now.getMilliseconds()).padStart(3, '0')}`;
  return {
    deviceOccurredAt: `${local}${sign}${two(Math.floor(magnitude / 60))}:${two(magnitude % 60)}`,
    deviceOffsetMinutes: offset,
  };
}

/**
 * A new event, captured once at the employee's confirmation: the client event ID, the device
 * instant and offset are fixed here and never recalculated.
 */
export function captureEvent(
  input: { type: OfflineType; slotId: string },
  ownerScope: string,
  now: Date,
  clientEventId: string = crypto.randomUUID(),
): QueuedEvent {
  return {
    schemaVersion: QUEUE_SCHEMA_VERSION,
    clientEventId,
    slotId: input.slotId.toLowerCase(),
    type: input.type,
    ...deviceTimeOf(now),
    createdAt: now.toISOString(),
    ownerScope,
    state: 'PENDING',
    attempts: 0,
    reason: null,
  };
}

/** A stored value as an event, or `null` for anything not exactly schema version 1. */
export function parseEvent(value: unknown): QueuedEvent | null {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null;
  const row = value as Record<string, unknown>;
  if (Object.keys(row).sort().join(',') !== FIELDS) return null;
  const time =
    typeof row.deviceOccurredAt === 'string' ? DEVICE_TIME.exec(row.deviceOccurredAt) : null;
  const offset = row.deviceOffsetMinutes;
  if (
    row.schemaVersion !== QUEUE_SCHEMA_VERSION ||
    typeof row.clientEventId !== 'string' ||
    !UUID.test(row.clientEventId) ||
    typeof row.slotId !== 'string' ||
    !UUID.test(row.slotId) ||
    (row.type !== 'CHECK_IN' && row.type !== 'CHECK_OUT') ||
    time === null ||
    typeof offset !== 'number' ||
    !Number.isInteger(offset) ||
    Math.abs(offset) > MAX_OFFSET_MINUTES ||
    typeof row.createdAt !== 'string' ||
    Number.isNaN(Date.parse(row.createdAt)) ||
    typeof row.ownerScope !== 'string' ||
    !SCOPE.test(row.ownerScope) ||
    (row.state !== 'PENDING' && row.state !== 'NEEDS_ATTENTION') ||
    typeof row.attempts !== 'number' ||
    !Number.isInteger(row.attempts) ||
    row.attempts < 0 ||
    row.attempts > MAX_ATTEMPTS ||
    !(row.reason === null || (ATTENTION_REASONS as readonly unknown[]).includes(row.reason))
  ) {
    return null;
  }
  // The explicit offset must be the recorded offset (MVP-061A refuses anything else).
  const written =
    time[1] === 'Z' ? 0 : (time[2] === '-' ? -1 : 1) * (Number(time[3]) * 60 + Number(time[4]));
  if (written !== offset || (time[2] === '-' && written === 0)) return null;
  return row as unknown as QueuedEvent;
}

/** The creation instant of any stored value, when it is readable. */
function createdAtOf(value: unknown): string | null {
  if (typeof value !== 'object' || value === null) return null;
  const created = (value as { createdAt?: unknown }).createdAt;
  return typeof created === 'string' && !Number.isNaN(Date.parse(created)) ? created : null;
}

/** Oldest first, then by client event ID (a stable order). */
function oldestFirst(a: QueueEntry, b: QueueEntry): number {
  const at = (a.createdAt ?? '').localeCompare(b.createdAt ?? '');
  return at !== 0 ? at : a.key.localeCompare(b.key);
}

/**
 * Opens the owner's queue: deletes the owner's rows created more than 7 days ago, then returns
 * the rest oldest first. Unreadable rows are returned with `event: null`, never guessed.
 */
export async function openQueue(
  store: QueueStore,
  ownerScope: string,
  now: Date,
): Promise<QueueEntry[]> {
  const entries: QueueEntry[] = [];
  const owned = ownedBy(ownerScope);
  for (const { key, value } of await store.all()) {
    // Another owner's rows are never read, counted, shown, expired or sent.
    if (!owned(value)) continue;
    const created = createdAtOf(value);
    if (created !== null && now.getTime() - Date.parse(created) > QUEUE_LIFETIME_MS) {
      await removeOwned(store, ownerScope, key);
      continue;
    }
    entries.push({ key, event: parseEvent(value), createdAt: created });
  }
  return entries.sort(oldestFirst);
}

/** What one request through MVP-061A answered. */
export type SendResult =
  | { kind: 'applied' }
  | { kind: 'network' }
  | { kind: 'refused'; status: number; code: string | undefined };

/** Why a pass stopped before the end of the queue. */
export type StopReason = 'network' | 'auth' | 'forbidden' | 'rateLimited' | 'server';

/** The outcome of one pass. */
export interface PassResult {
  applied: number;
  attention: number;
  stopped: StopReason | null;
}

/** HTTP statuses that are a stable, permanent refusal of one event (never retried). */
const PERMANENT = new Set([400, 404, 409, 422]);

function reasonOf(code: string | undefined): AttentionReason {
  return code !== undefined && (ATTENTION_REASONS as readonly string[]).includes(code)
    ? (code as AttentionReason)
    : 'GENERIC';
}

/** The MVP-061A request body. */
export interface SyncPayload {
  clientEventId: string;
  slotId: string;
  type: OfflineType;
  deviceOccurredAt: string;
  deviceOffsetMinutes: number;
}

/** The request body of a queued event: exactly the MVP-061A fields, never the metadata. */
export function payloadOf(event: QueuedEvent): SyncPayload {
  return {
    clientEventId: event.clientEventId,
    slotId: event.slotId,
    type: event.type,
    deviceOccurredAt: event.deviceOccurredAt,
    deviceOffsetMinutes: event.deviceOffsetMinutes,
  };
}

/**
 * One foreground synchronization pass over the owner's pending rows, oldest first and one at a
 * time (B61B-4). Applied and already-applied events are deleted. A network failure, 401, 403,
 * 429 or any other non-permanent answer keeps the row pending and stops the pass. A permanent
 * 400, 404, 409 or 422 keeps the row as "needs attention" with an allow-listed code and goes on.
 */
export async function synchronize(
  store: QueueStore,
  ownerScope: string,
  now: Date,
  send: (payload: SyncPayload) => Promise<SendResult>,
): Promise<PassResult> {
  const result: PassResult = { applied: 0, attention: 0, stopped: null };
  for (const entry of await openQueue(store, ownerScope, now)) {
    const event = entry.event;
    if (event?.state !== 'PENDING') continue;
    const answer = await send(payloadOf(event));
    if (answer.kind === 'applied') {
      await removeOwned(store, ownerScope, entry.key);
      result.applied++;
      continue;
    }
    const attempts = Math.min(event.attempts + 1, MAX_ATTEMPTS);
    if (answer.kind === 'refused' && PERMANENT.has(answer.status)) {
      await patchOwned(store, ownerScope, entry.key, {
        state: 'NEEDS_ATTENTION',
        attempts,
        reason: reasonOf(answer.code),
      });
      result.attention++;
      continue;
    }
    await patchOwned(store, ownerScope, entry.key, { state: 'PENDING', attempts, reason: null });
    result.stopped =
      answer.kind === 'network'
        ? 'network'
        : answer.status === 401
          ? 'auth'
          : answer.status === 403
            ? 'forbidden'
            : answer.status === 429
              ? 'rateLimited'
              : 'server';
    break;
  }
  return result;
}

/** An in-memory store (tests; never used by the application). */
export function memoryQueueStore(initial: { key: string; value: unknown }[] = []): QueueStore & {
  rows: Map<string, unknown>;
} {
  const rows = new Map<string, unknown>(initial.map(({ key, value }) => [key, value]));
  return {
    rows,
    all: () =>
      Promise.resolve(
        [...rows.entries()].map(([key, value]) => ({ key, value: structuredClone(value) })),
      ),
    addUnless: (key, value, refuse) => {
      if (rows.has(key) || refuse([...rows.values()].map((v) => structuredClone(v)))) {
        return Promise.resolve(false);
      }
      rows.set(key, structuredClone(value));
      return Promise.resolve(true);
    },
    update: (key, change) => {
      if (rows.has(key)) {
        const next = change(structuredClone(rows.get(key)));
        if (next) rows.set(key, next);
      }
      return Promise.resolve();
    },
    delete: (key, allowed) => {
      if (rows.has(key) && allowed(rows.get(key))) rows.delete(key);
      return Promise.resolve();
    },
  };
}

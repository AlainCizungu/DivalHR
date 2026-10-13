import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useApi } from '../../../app/ApiProvider';
import { useSession } from '../../../app/SessionProvider';
import { useAuth } from '../../../auth/AuthProvider';
import { openIndexedDbStore } from './indexedDbStore';
import {
  captureEvent,
  enqueue,
  openQueue,
  ownerScopeOf,
  removeOwned,
  synchronize,
  type OfflineType,
  type PassResult,
  type QueueEntry,
  type QueueStore,
  type QueuedEvent,
  type SendResult,
  type SyncPayload,
} from './offlineQueue';

/** What the last foreground action told the employee (keys and counts only, never data). */
export type OfflineNotice =
  { kind: 'pass'; result: PassResult } | { kind: 'discarded' } | { kind: 'queued' };

export interface OfflineAttendance {
  /** The queue is usable: a signed-in employee's verified owner scope and writable IndexedDB. */
  available: boolean;
  /** IndexedDB could not be opened in this browser. */
  unsupported: boolean;
  online: boolean;
  /** The current owner's rows, oldest first (never another owner's). */
  entries: QueueEntry[];
  syncing: boolean;
  notice: OfflineNotice | null;
  /** Increases whenever a pass applied at least one event (pages reload their data). */
  appliedVersion: number;
  /** Captures an event once (client event ID, device instant and offset fixed now). */
  prepare: (input: { type: OfflineType; slotId: string }) => QueuedEvent | null;
  /** Stores a prepared event; `failed` is never shown as queued. */
  capture: (event: QueuedEvent) => Promise<'queued' | 'duplicate' | 'failed'>;
  /** Whether the owner already has a queued row of this slot and type. */
  queuedFor: (slotId: string, type: OfflineType) => boolean;
  syncNow: () => void;
  discard: (key: string) => Promise<void>;
}

const OfflineAttendanceContext = createContext<OfflineAttendance | null>(null);

/** The offline queue of the signed-in employee, or `null` outside a supported session. */
export function useOfflineAttendance(): OfflineAttendance | null {
  return useContext(OfflineAttendanceContext);
}

function isOnline(): boolean {
  return typeof navigator === 'undefined' ? true : navigator.onLine;
}

/**
 * The queue for one owner scope (MVP-061B, B61B-3 and B61B-4). Synchronization runs only in the
 * foreground: once when the owner's queue is ready while online, on the browser's `online`
 * event, and on "Sync now". One pass at a time per tab; several tabs are safe through the
 * server's idempotency. No timer, scheduler, Background Sync or notification.
 */
export function OfflineQueueProvider({
  ownerScope,
  openStore,
  send,
  now = () => new Date(),
  children,
}: {
  ownerScope: string | null;
  openStore: () => Promise<QueueStore>;
  send: (payload: SyncPayload) => Promise<SendResult>;
  now?: () => Date;
  children: ReactNode;
}) {
  const [store, setStore] = useState<QueueStore | null>(null);
  const [unsupported, setUnsupported] = useState(false);
  const [online, setOnline] = useState(isOnline);
  const [entriesOf, setEntriesOf] = useState<{ owner: string; entries: QueueEntry[] } | null>(null);
  // Rows belong to the owner they were read for: another owner never sees them.
  const entries = useMemo(
    () => (entriesOf && entriesOf.owner === ownerScope ? entriesOf.entries : []),
    [entriesOf, ownerScope],
  );
  const [syncing, setSyncing] = useState(false);
  const [noticeOf, setNoticeOf] = useState<{
    owner: string | null;
    notice: OfflineNotice;
  } | null>(null);
  // A notice belongs to the owner it was given to: another owner never sees it.
  const notice = noticeOf && noticeOf.owner === ownerScope ? noticeOf.notice : null;
  const setNotice = useCallback(
    (next: OfflineNotice) => {
      setNoticeOf({ owner: ownerScope, notice: next });
    },
    [ownerScope],
  );
  const [appliedVersion, setAppliedVersion] = useState(0);
  const running = useRef(false);
  const startedFor = useRef<string | null>(null);
  const sendRef = useRef(send);
  const nowRef = useRef(now);
  useEffect(() => {
    sendRef.current = send;
    nowRef.current = now;
  });

  // The store is opened once per provider.
  const openStoreRef = useRef(openStore);
  useEffect(() => {
    let active = true;
    openStoreRef.current().then(
      (opened) => {
        if (active) setStore(opened);
      },
      () => {
        if (active) setUnsupported(true);
      },
    );
    return () => {
      active = false;
    };
  }, []);

  const refresh = useCallback(async () => {
    if (!store || !ownerScope) return;
    const next = await openQueue(store, ownerScope, nowRef.current()).catch((): QueueEntry[] => []);
    setEntriesOf({ owner: ownerScope, entries: next });
  }, [store, ownerScope]);

  const pass = useCallback(async () => {
    if (!store || !ownerScope || running.current) return;
    if (!isOnline()) {
      setNotice({ kind: 'pass', result: { applied: 0, attention: 0, stopped: 'network' } });
      return;
    }
    running.current = true;
    setSyncing(true);
    try {
      const result = await synchronize(store, ownerScope, nowRef.current(), (payload) =>
        sendRef.current(payload),
      );
      if (result.applied > 0) setAppliedVersion((value) => value + 1);
      if (result.applied > 0 || result.attention > 0 || result.stopped) {
        setNotice({ kind: 'pass', result });
      }
    } catch {
      setNotice({ kind: 'pass', result: { applied: 0, attention: 0, stopped: 'server' } });
    } finally {
      running.current = false;
      setSyncing(false);
      await refresh();
    }
  }, [store, ownerScope, refresh, setNotice]);

  // The owner's queue opens (and expires old rows); then one pass when online.
  useEffect(() => {
    void refresh().then(() => {
      if (ownerScope && store && startedFor.current !== ownerScope && isOnline()) {
        startedFor.current = ownerScope;
        void pass();
      }
    });
  }, [ownerScope, store, refresh, pass]);

  useEffect(() => {
    const up = () => {
      setOnline(true);
      void pass();
    };
    const down = () => {
      setOnline(false);
    };
    window.addEventListener('online', up);
    window.addEventListener('offline', down);
    return () => {
      window.removeEventListener('online', up);
      window.removeEventListener('offline', down);
    };
  }, [pass]);

  const prepare = useCallback(
    (input: { type: OfflineType; slotId: string }) => {
      if (!ownerScope) return null;
      try {
        return captureEvent(input, ownerScope, nowRef.current());
      } catch {
        return null;
      }
    },
    [ownerScope],
  );

  const capture = useCallback(
    async (event: QueuedEvent) => {
      if (!store || !ownerScope || event.ownerScope !== ownerScope) return 'failed' as const;
      try {
        const outcome = await enqueue(store, event);
        await refresh();
        if (outcome === 'duplicate') return 'duplicate' as const;
        setNotice({ kind: 'queued' });
        return 'queued' as const;
      } catch {
        return 'failed' as const;
      }
    },
    [store, ownerScope, refresh, setNotice],
  );

  const discard = useCallback(
    async (key: string) => {
      if (!store || !ownerScope) return;
      await removeOwned(store, ownerScope, key);
      setNotice({ kind: 'discarded' });
      await refresh();
    },
    [store, ownerScope, refresh, setNotice],
  );

  const queuedFor = useCallback(
    (slotId: string, type: OfflineType) =>
      entries.some(
        (entry) => entry.event?.slotId === slotId.toLowerCase() && entry.event.type === type,
      ),
    [entries],
  );

  const value = useMemo<OfflineAttendance>(
    () => ({
      available: store !== null && ownerScope !== null,
      unsupported,
      online,
      entries,
      syncing,
      notice,
      appliedVersion,
      prepare,
      capture,
      queuedFor,
      syncNow: () => {
        void pass();
      },
      discard,
    }),
    [
      store,
      ownerScope,
      unsupported,
      online,
      entries,
      syncing,
      notice,
      appliedVersion,
      prepare,
      capture,
      queuedFor,
      pass,
      discard,
    ],
  );
  return <OfflineAttendanceContext value={value}>{children}</OfflineAttendanceContext>;
}

/**
 * The application's offline queue (MVP-061B): present for a signed-in employee whose verified
 * session has a tenant; the owner scope is derived from that tenant and the verified subject.
 * Synchronization uses the generated MVP-061A client.
 */
export function OfflineAttendanceProvider({ children }: { children: ReactNode }) {
  const session = useSession();
  const { getSubject } = useAuth();
  const { core } = useApi();
  const [derived, setDerived] = useState<{ source: string; scope: string } | null>(null);
  const tenantId =
    session.kind === 'ready' && session.session.roles.includes('employee')
      ? session.session.tenantId
      : null;
  const subject = tenantId ? getSubject() : undefined;
  const source = tenantId && subject ? `${tenantId}\n${subject}` : null;
  // Only the scope of the current verified session is ever used.
  const ownerScope = derived && derived.source === source ? derived.scope : null;

  useEffect(() => {
    if (!tenantId || !subject || source === null) return;
    let active = true;
    ownerScopeOf(tenantId, subject).then(
      (scope) => {
        if (active) setDerived({ source, scope });
      },
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [tenantId, subject, source]);

  const send = useCallback(
    async (payload: SyncPayload): Promise<SendResult> => {
      try {
        const { data, error, response } = await core.POST('/me/attendance/offline-events:sync', {
          body: payload,
        });
        if (data) return { kind: 'applied' };
        const code = (error as { code?: unknown } | undefined)?.code;
        return {
          kind: 'refused',
          status: response.status,
          code: typeof code === 'string' ? code : undefined,
        };
      } catch {
        return { kind: 'network' };
      }
    },
    [core],
  );

  const openStore = useCallback(() => openIndexedDbStore(), []);

  return (
    <OfflineQueueProvider ownerScope={ownerScope} openStore={openStore} send={send}>
      {children}
    </OfflineQueueProvider>
  );
}

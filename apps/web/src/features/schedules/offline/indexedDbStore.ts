import type { QueueStore } from './offlineQueue';

/**
 * MVP-061B (B61B-2): the attendance queue in IndexedDB, the only place queued attendance is kept
 * (never Cache Storage, localStorage, sessionStorage, a URL or a log). One object store keyed by
 * the client event ID. This module is the mechanism only: ownership, uniqueness and immutability
 * are decided by offlineQueue.ts and applied here inside one transaction. Plain IndexedDB, no
 * dependency. The data is not encrypted by DivalHR.
 */

export const QUEUE_DATABASE = 'divalhr-attendance-queue';
const STORE = 'events';

function request<T>(call: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    call.onsuccess = () => {
      resolve(call.result);
    };
    call.onerror = () => {
      reject(call.error ?? new Error('IndexedDB request failed'));
    };
  });
}

function done(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => {
      resolve();
    };
    transaction.onerror = () => {
      reject(transaction.error ?? new Error('IndexedDB transaction failed'));
    };
    transaction.onabort = () => {
      reject(transaction.error ?? new Error('IndexedDB transaction aborted'));
    };
  });
}

/** Opens (and on first use creates) the queue database; rejects when IndexedDB is unusable. */
export async function openIndexedDbStore(factory?: IDBFactory): Promise<QueueStore> {
  const idb = factory ?? (typeof indexedDB === 'undefined' ? undefined : indexedDB);
  if (!idb) throw new Error('IndexedDB is not available');
  const opening = idb.open(QUEUE_DATABASE, 1);
  opening.onupgradeneeded = () => {
    opening.result.createObjectStore(STORE, { keyPath: 'clientEventId' });
  };
  const db = await request(opening);

  const all = async () => {
    const store = db.transaction(STORE, 'readonly').objectStore(STORE);
    const [keys, values] = await Promise.all([
      request(store.getAllKeys()),
      request(store.getAll()),
    ]);
    return keys.map((key, position) => ({
      key: key as string,
      value: values[position] as unknown,
    }));
  };

  const addUnless = (_key: string, value: object, refuse: (values: unknown[]) => boolean) =>
    new Promise<boolean>((resolve, reject) => {
      const transaction = db.transaction(STORE, 'readwrite');
      const store = transaction.objectStore(STORE);
      let added = false;
      const existing = store.getAll();
      existing.onsuccess = () => {
        if (!refuse(existing.result)) {
          store.add(value);
          added = true;
        }
      };
      done(transaction).then(() => {
        resolve(added);
      }, reject);
    });

  /** Reads one row and changes it in the same transaction. */
  const withRow = (key: string, act: (store: IDBObjectStore, value: unknown) => void) =>
    new Promise<void>((resolve, reject) => {
      const transaction = db.transaction(STORE, 'readwrite');
      const store = transaction.objectStore(STORE);
      const current = store.get(key);
      current.onsuccess = () => {
        if (current.result !== undefined) act(store, current.result);
      };
      done(transaction).then(resolve, reject);
    });

  const update = (key: string, change: (value: unknown) => object | null) =>
    withRow(key, (store, value) => {
      const next = change(value);
      if (next) store.put(next);
    });

  const remove = (key: string, allowed: (value: unknown) => boolean) =>
    withRow(key, (store, value) => {
      if (allowed(value)) store.delete(key);
    });

  return { all, addUnless, update, delete: remove };
}

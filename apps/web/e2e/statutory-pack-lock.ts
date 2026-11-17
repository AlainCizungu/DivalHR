import { randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

/**
 * R207-3: the lock that keeps the statutory-pack specs from running into each other.
 *
 * `synthetic-verified-rule-pack.sql` retires every ACTIVE or SCHEDULED Congo pack, whatever its
 * code, so a spec that creates a synthetic verified pack while MVP-097A holds its own SCHEDULED
 * pack retires it ("Retirée" where the French journey expects "Planifiée"). The two workers of the
 * `features` project can run those specs at the same time, and the retry of a CI run hides it.
 *
 * Whoever creates a verified pack (`createSyntheticVerifiedPack`, so MVP-094B and every other spec
 * that uses it) takes this lock around the SQL, and the two statutory specs (MVP-094B and
 * MVP-097A) hold it for their whole journeys, so a pack another spec still reads is never retired
 * underneath it. It is a directory created atomically in the temporary directory, as
 * `subjectLock` in support.ts; a holder that died frees it. It is re-entrant within one process,
 * and it waits for nothing but the holder: no retry, no worker change and no fixed delay.
 */
export const STATUTORY_PACK_LOCK = join(tmpdir(), 'divalhr-e2e-statutory-pack.lock');

/** Test hooks; the specs use the defaults. */
export interface StatutoryPackLockOptions {
  /** The lock directory. */
  dir?: string;
  /** How long to wait for a live holder. */
  timeoutMs?: number;
  /** The interval between two looks at a live holder. */
  pollMs?: number;
  /** How long a lock directory without an owner record is left alone (the holder is writing it). */
  graceMs?: number;
}

interface Owner {
  pid: number;
  token: string;
}

interface Entry {
  count: number;
  token: string;
}

// The locks this process holds, by directory (the lock is re-entrant within a process).
const held = new Map<string, Entry>();

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

function alive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    // EPERM: the process exists but is not ours.
    return (error as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function owner(dir: string): Owner | null {
  try {
    const parsed = JSON.parse(readFileSync(join(dir, 'owner'), 'utf8')) as Partial<Owner>;
    return Number.isInteger(parsed.pid) && typeof parsed.token === 'string'
      ? { pid: parsed.pid as number, token: parsed.token }
      : null;
  } catch {
    return null;
  }
}

/** The owner record that has gone stale, or `undefined` while the holder may still be alive. */
function stale(dir: string, graceMs: number): { token: string | null } | undefined {
  const record = owner(dir);
  if (record) return alive(record.pid) ? undefined : { token: record.token };
  try {
    // No record: the holder is between creating the directory and writing it.
    return Date.now() - statSync(dir).mtimeMs > graceMs ? { token: null } : undefined;
  } catch {
    return undefined; // gone already
  }
}

function reclaim(dir: string, observed: string | null): void {
  const aside = `${dir}.reclaim-${randomUUID()}`;
  try {
    renameSync(dir, aside);
  } catch {
    return; // someone else reclaimed or took it first
  }
  const moved = owner(aside);
  if (observed !== null && moved !== null && moved.token !== observed) {
    // A new holder took the lock between the look and the rename: give it back.
    try {
      renameSync(aside, dir);
      return;
    } catch {
      // The directory is taken again; the moved one is released below.
    }
  }
  rmSync(aside, { recursive: true, force: true });
}

function releaser(dir: string, entry: Entry): () => void {
  let released = false;
  return () => {
    if (released) return;
    released = true;
    entry.count -= 1;
    if (entry.count > 0) return;
    held.delete(dir);
    // Only the holder's own directory is removed: a reclaimed lock now belongs to someone else.
    if (owner(dir)?.token === entry.token) rmSync(dir, { recursive: true, force: true });
  };
}

/**
 * Takes the lock, waiting for a live holder, and returns its release. Re-entrant: a process that
 * already holds it takes it again at once, and it is freed when every release has been called.
 */
export async function acquireStatutoryPackLock(
  options: StatutoryPackLockOptions = {},
): Promise<() => void> {
  const dir = options.dir ?? STATUTORY_PACK_LOCK;
  const timeoutMs = options.timeoutMs ?? 10 * 60_000;
  const pollMs = options.pollMs ?? 200;
  const graceMs = options.graceMs ?? 5_000;
  const mine = held.get(dir);
  if (mine) {
    mine.count += 1;
    return releaser(dir, mine);
  }
  const token = randomUUID();
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      mkdirSync(dir);
      writeFileSync(join(dir, 'owner'), JSON.stringify({ pid: process.pid, token }));
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;
    }
    const gone = stale(dir, graceMs);
    if (gone) {
      reclaim(dir, gone.token);
      continue;
    }
    if (Date.now() > deadline) {
      throw new Error(`the statutory pack lock was not released within ${timeoutMs} ms`);
    }
    await sleep(pollMs);
  }
  const entry = { count: 1, token };
  held.set(dir, entry);
  return releaser(dir, entry);
}

/** Runs `action` while holding the lock. */
export async function withStatutoryPackLock<T>(
  action: () => T | Promise<T>,
  options: StatutoryPackLockOptions = {},
): Promise<T> {
  const release = await acquireStatutoryPackLock(options);
  try {
    return await action();
  } finally {
    release();
  }
}

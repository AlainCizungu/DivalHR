import type { AttendanceReceipt, Problem } from '@divalhr/api-client';

/**
 * MVP-060A: what the employee confirms. A check-in names the slot; a check-out the record. The
 * instant is never sent: the server records its own.
 */
export type AttendanceAction =
  { kind: 'checkIn'; slotId: string } | { kind: 'checkOut'; recordId: string };

/** The shift shown in the confirmation (display only). */
export interface AttendanceShiftFacts {
  start: string;
  end: string;
  labels: { en: string; fr: string };
  siteName: string | null;
  siteTimezone: string;
  checkedInAt?: string | null;
}

/** A refused or failed attendance call, reduced to stable codes. */
export interface AttendanceFailure {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'gone'
    | 'notYet'
    | 'closed'
    | 'already'
    | 'openElsewhere'
    | 'conflict'
    | 'general';
  messageKey: string;
  correlationId?: string;
  retryAfter?: number;
}

export const ATTENDANCE_NETWORK_FAILURE: AttendanceFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Kinds after which the page's data is stale: it is refreshed from the server. */
export const SETTLED_ATTENDANCE_KINDS: ReadonlySet<AttendanceFailure['kind']> = new Set([
  'gone',
  'notYet',
  'closed',
  'already',
  'openElsewhere',
  'conflict',
]);

const KINDS: Record<string, AttendanceFailure['kind']> = {
  ATTENDANCE_SHIFT_NOT_FOUND: 'gone',
  ATTENDANCE_RECORD_NOT_FOUND: 'gone',
  ATTENDANCE_CHECK_IN_TOO_EARLY: 'notYet',
  ATTENDANCE_CHECK_IN_WINDOW_CLOSED: 'closed',
  ATTENDANCE_CHECK_OUT_WINDOW_CLOSED: 'closed',
  ATTENDANCE_ALREADY_CHECKED_IN: 'already',
  ATTENDANCE_ALREADY_CHECKED_OUT: 'already',
  ATTENDANCE_OPEN_RECORD_EXISTS: 'openElsewhere',
  ATTENDANCE_STATE_CONFLICT: 'conflict',
};

/** Maps a Problem response; no param is ever shown. */
export function attendanceFailureOf(response: Response, error: unknown): AttendanceFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'attendance.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code && Object.hasOwn(KINDS, code)) {
    return {
      ...base,
      kind: KINDS[code] as AttendanceFailure['kind'],
      messageKey: `errors.${code}`,
    };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED' || code === 'CURSOR_INVALID') {
    return { ...base, kind: 'general', messageKey: `errors.${code}` };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/**
 * An instant in the site's own time zone, with the date (display only; the instant is the
 * server's).
 */
export function formatInstant(language: 'en' | 'fr', instant: string, zone: string): string {
  try {
    return new Intl.DateTimeFormat(language, {
      timeZone: zone,
      dateStyle: 'medium',
      timeStyle: 'short',
    }).format(new Date(instant));
  } catch {
    return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(instant),
    );
  }
}

/** The announcement after a recorded command. */
export function receiptMessageKey(receipt: AttendanceReceipt): string {
  return receipt.status === 'COMPLETED' ? 'attendance.checkOut.done' : 'attendance.checkIn.done';
}

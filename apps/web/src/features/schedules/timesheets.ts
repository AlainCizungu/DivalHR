import type {
  Problem,
  TimesheetPeriodUnlockReason,
  TimesheetRowState,
  TimesheetStatus,
  TimesheetSubmissionBlocker,
} from '@divalhr/api-client';
import type { StatusTone } from '../../ui/primitives';

/**
 * MVP-070A: weekly draft timesheets. The browser only chooses a site and a Monday and shows the
 * server's previews, drafts and rows: it never derives a row's state, the worked minutes, the week's
 * boundaries or whether a week may be generated. Nothing is kept in browser storage or put in a URL.
 */

export const ROW_STATES: readonly TimesheetRowState[] = [
  'READY',
  'MISSING_ATTENDANCE',
  'OPEN_ATTENDANCE',
  'SOURCE_CHANGED',
];

export const STATE_TONES: Record<TimesheetRowState, StatusTone> = {
  READY: 'success',
  MISSING_ATTENDANCE: 'warning',
  OPEN_ATTENDANCE: 'info',
  SOURCE_CHANGED: 'warning',
};

/**
 * MVP-070B/C and MVP-070D: the timesheet's state; the text carries the meaning, the tone only
 * repeats it.
 */
export const STATUS_TONES: Record<TimesheetStatus, StatusTone> = {
  DRAFT: 'neutral',
  SUBMITTED: 'info',
  APPROVED: 'success',
  REOPENED: 'warning',
};

/** MVP-070B/C: what keeps a draft from being submitted, in the server's fixed order. */
export const BLOCKERS: readonly TimesheetSubmissionBlocker[] = [
  'MISSING_ATTENDANCE',
  'OPEN_ATTENDANCE',
  'SOURCE_CHANGED',
  'NO_READY_ROWS',
  'APPROVAL_ROUTE_UNAVAILABLE',
  'REFRESH_REQUIRED',
];

/** MVP-071: the closed unlock reasons, in the server's fixed order. */
export const UNLOCK_REASONS: readonly TimesheetPeriodUnlockReason[] = [
  'ATTENDANCE_CORRECTION_REQUIRED',
  'TIMESHEET_EVIDENCE_ERROR',
  'ORGANIZATION_REQUEST',
  'OTHER',
];

/**
 * MVP-070B/C: the return reason's bounds (the server decides; this only guides). MVP-071's unlock
 * explanation follows the same grammar.
 */
export const REASON_MIN = 10;
export const REASON_MAX = 500;

export type ReasonProblem = 'required' | 'length' | 'format';

/**
 * Problems of a return reason the browser can already tell (trimmed length, a link or markup, a
 * control or invisible format character); the server checks everything again.
 */
export function reasonProblemOf(reason: string): ReasonProblem | null {
  const trimmed = reason.trim();
  if (!trimmed) return 'required';
  let length = 0;
  for (let i = 0; i < trimmed.length; i += 1) {
    if ((trimmed.codePointAt(i) ?? 0) > 0xffff) i += 1;
    length += 1;
  }
  if (length < REASON_MIN || length > REASON_MAX) return 'length';
  if (/[<>]/u.test(trimmed) || /(https?:\/\/|ftp:\/\/|www\.|mailto:)/iu.test(trimmed)) {
    return 'format';
  }
  if (/[\p{Cc}\p{Cf}]/u.test(trimmed)) return 'format';
  return null;
}

function two(value: number): string {
  return String(value).padStart(2, '0');
}

/** The Monday of the week containing a calendar date ({@code YYYY-MM-DD}). */
export function mondayOf(date: string): string {
  const day = new Date(`${date}T00:00:00Z`);
  const shift = (day.getUTCDay() + 6) % 7;
  day.setUTCDate(day.getUTCDate() - shift);
  return `${String(day.getUTCFullYear())}-${two(day.getUTCMonth() + 1)}-${two(day.getUTCDate())}`;
}

/** The browser's current Monday: only a default for the picker; the server decides. */
export function currentMonday(now: Date = new Date()): string {
  return mondayOf(`${String(now.getFullYear())}-${two(now.getMonth() + 1)}-${two(now.getDate())}`);
}

export interface WeekForm {
  siteId: string;
  weekStart: string;
}

export const WEEK_FIELDS = ['siteId', 'weekStart'] as const;
export type WeekField = (typeof WEEK_FIELDS)[number];
export type WeekProblem = 'siteRequired' | 'weekRequired' | 'notMonday' | 'range';

/** Problems the browser can already tell; the server checks everything again. */
export function weekProblemsOf(form: WeekForm): Map<WeekField, WeekProblem> {
  const problems = new Map<WeekField, WeekProblem>();
  if (!form.siteId) problems.set('siteId', 'siteRequired');
  if (!form.weekStart) problems.set('weekStart', 'weekRequired');
  else if (!/^\d{4}-\d{2}-\d{2}$/u.test(form.weekStart)) problems.set('weekStart', 'notMonday');
  else if (mondayOf(form.weekStart) !== form.weekStart) problems.set('weekStart', 'notMonday');
  return problems;
}

/** Whole minutes as hours and minutes, never rounded. */
export function durationParts(minutes: number): { hours: number; minutes: number } {
  return { hours: Math.floor(minutes / 60), minutes: minutes % 60 };
}

/** A local calendar date in the site's zone, for a row's instant. */
export function formatShiftDate(language: 'en' | 'fr', instant: string, zone: string): string {
  return new Intl.DateTimeFormat(language, {
    timeZone: zone,
    weekday: 'short',
    day: 'numeric',
    month: 'short',
  }).format(new Date(instant));
}

/** A local time of day in the site's zone. */
export function formatShiftTime(language: 'en' | 'fr', instant: string, zone: string): string {
  return new Intl.DateTimeFormat(language, {
    timeZone: zone,
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(instant));
}

/** A refused or failed request, reduced to stable codes and allow-listed fields. */
export interface TimesheetFailure {
  kind:
    | 'validation'
    | 'notFound'
    | 'notAllowed'
    | 'limit'
    | 'stale'
    | 'locked'
    | 'route'
    | 'decided'
    | 'forbidden'
    | 'linkRequired'
    | 'rateLimited'
    | 'idempotency'
    | 'general';
  messageKey: string;
  fields?: WeekField[];
  /** MVP-070B/C: the return reason was refused (MVP-071: the unlock reason or explanation). */
  reason?: boolean;
  correlationId?: string;
}

export const TIMESHEET_NETWORK_FAILURE: TimesheetFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

const CODES: Record<string, TimesheetFailure['kind']> = {
  TIMESHEET_TARGET_NOT_FOUND: 'notFound',
  TIMESHEET_GENERATION_NOT_ALLOWED: 'notAllowed',
  TIMESHEET_GENERATION_LIMIT_EXCEEDED: 'limit',
  TIMESHEET_REFRESH_PREVIEW_STALE: 'stale',
  TIMESHEET_SUBMISSION_NOT_ALLOWED: 'notAllowed',
  TIMESHEET_APPROVAL_ROUTE_UNAVAILABLE: 'route',
  TIMESHEET_DECISION_STALE: 'stale',
  TIMESHEET_DECISION_NOT_ALLOWED: 'decided',
  TIMESHEET_EXPORT_EMPTY: 'notAllowed',
  TIMESHEET_EXPORT_PREVIEW_STALE: 'stale',
  TIMESHEET_PERIOD_NOT_READY: 'notAllowed',
  TIMESHEET_PERIOD_LOCKED: 'locked',
  TIMESHEET_PERIOD_NOT_LOCKED: 'notAllowed',
  TIMESHEET_PERIOD_PREVIEW_STALE: 'stale',
  IDEMPOTENCY_KEY_REUSED: 'idempotency',
};

/** Maps a Problem response; no param outside the field allow-list is ever shown. */
export function timesheetFailureOf(
  view: 'admin' | 'self' | 'manager' | 'exports',
  response: Response,
  error: unknown,
): TimesheetFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code ?? '';
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const base = { correlationId: problem?.correlationId };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (response.status === 403) {
    return {
      ...base,
      kind: 'forbidden',
      messageKey:
        view === 'admin'
          ? 'timesheets.unauthorized'
          : view === 'exports'
            ? 'timeExports.unauthorized'
            : view === 'manager'
              ? 'timeApprovals.unauthorized'
              : 'myTimesheets.unauthorized',
    };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  const kind = CODES[code];
  if (kind) return { ...base, kind, messageKey: `errors.${code}` };
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<WeekField>();
    let reason = false;
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && (WEEK_FIELDS as readonly string[]).includes(name)) {
          fields.add(name as WeekField);
        }
        if (name === 'reason' || name === 'reasonCode' || name === 'explanation') reason = true;
      }
    }
    if (view === 'manager') {
      return { ...base, kind: 'validation', messageKey: 'timeApprovals.validation', reason };
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'timesheets.validation',
      fields: WEEK_FIELDS.filter((field) => fields.has(field)),
      reason,
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

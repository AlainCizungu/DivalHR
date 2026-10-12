import type {
  Problem,
  SupervisorCheckInRequest,
  SupervisorCheckOutRequest,
  TeamAttendanceAction,
} from '@divalhr/api-client';
import { normalizeReason } from '../leave/leaveApprovals';
import { reasonProblemsOf, type ReasonForm } from './substitutions';

/**
 * MVP-060B: what the manager confirms for a direct report. A check-in names the slot; a check-out
 * the report's record. The instant is never sent: the server records its own.
 */
export type TeamAttendanceCommand =
  | { kind: 'checkIn'; slotId: string; reasonLocale: 'en' | 'fr'; reason: string }
  | { kind: 'checkOut'; recordId: string; reasonLocale: 'en' | 'fr'; reason: string };

/** The reason fields a manager fills in; the order problems are listed in. */
export const TEAM_FIELDS = ['reasonLocale', 'reason'] as const;
export type TeamField = (typeof TEAM_FIELDS)[number];

/** A refused or failed team-attendance call, reduced to stable codes and allow-listed fields. */
export interface TeamAttendanceFailure {
  kind:
    | 'linkRequired'
    | 'notManager'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'gone'
    | 'notYet'
    | 'closed'
    | 'already'
    | 'openElsewhere'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: TeamField[];
  correlationId?: string;
  retryAfter?: number;
}

export const TEAM_NETWORK_FAILURE: TeamAttendanceFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Kinds after which the queue is stale: it is refreshed from the server. */
export const SETTLED_TEAM_KINDS: ReadonlySet<TeamAttendanceFailure['kind']> = new Set([
  'gone',
  'notYet',
  'closed',
  'already',
  'openElsewhere',
  'conflict',
]);

const KINDS: Record<string, TeamAttendanceFailure['kind']> = {
  TEAM_ATTENDANCE_ACTION_NOT_FOUND: 'gone',
  ATTENDANCE_CHECK_IN_TOO_EARLY: 'notYet',
  ATTENDANCE_CHECK_IN_WINDOW_CLOSED: 'closed',
  ATTENDANCE_CHECK_OUT_WINDOW_CLOSED: 'closed',
  ATTENDANCE_ALREADY_CHECKED_IN: 'already',
  ATTENDANCE_ALREADY_CHECKED_OUT: 'already',
  ATTENDANCE_OPEN_RECORD_EXISTS: 'openElsewhere',
  ATTENDANCE_STATE_CONFLICT: 'conflict',
};

const SERVER_FIELDS: Record<string, TeamField> = {
  reasonLocale: 'reasonLocale',
  reason: 'reason',
};

/** Maps a Problem response; no param outside the field allow-list is ever shown. */
export function teamFailureOf(response: Response, error: unknown): TeamAttendanceFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (code === 'TEAM_ATTENDANCE_MANAGER_REQUIRED') {
    return { ...base, kind: 'notManager', messageKey: 'teamAttendance.notManager' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'teamAttendance.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code && Object.hasOwn(KINDS, code)) {
    return {
      ...base,
      kind: KINDS[code] as TeamAttendanceFailure['kind'],
      messageKey: `errors.${code}`,
    };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<TeamField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as TeamField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: TEAM_FIELDS.filter((field) => fields.has(field)),
    };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED' || code === 'CURSOR_INVALID') {
    return { ...base, kind: 'general', messageKey: `errors.${code}` };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** What the browser can already tell is wrong with the reason; the server checks it again. */
export function teamProblemsOf(form: ReasonForm): Set<TeamField> {
  const problems = reasonProblemsOf(form);
  return new Set(TEAM_FIELDS.filter((field) => problems.has(field)));
}

/** The command for a queue row and a reason (normalized as the server stores it). */
export function commandOf(action: TeamAttendanceAction, form: ReasonForm): TeamAttendanceCommand {
  const reason = normalizeReason(form.reason);
  return action.requiredAction === 'CHECK_OUT' && action.attendanceRecordId
    ? {
        kind: 'checkOut',
        recordId: action.attendanceRecordId,
        reasonLocale: form.reasonLocale,
        reason,
      }
    : { kind: 'checkIn', slotId: action.slotId, reasonLocale: form.reasonLocale, reason };
}

export function checkInBodyOf(
  command: Extract<TeamAttendanceCommand, { kind: 'checkIn' }>,
): SupervisorCheckInRequest {
  return { slotId: command.slotId, reasonLocale: command.reasonLocale, reason: command.reason };
}

export function checkOutBodyOf(command: TeamAttendanceCommand): SupervisorCheckOutRequest {
  return { reasonLocale: command.reasonLocale, reason: command.reason };
}

/** The dialog and message family of a queue row's required action. */
export function kindOf(
  action: Pick<TeamAttendanceAction, 'requiredAction'>,
): 'checkIn' | 'checkOut' {
  return action.requiredAction === 'CHECK_OUT' ? 'checkOut' : 'checkIn';
}

/** "Given Family (number)" as plain text, never markup. */
export function employeeName(action: TeamAttendanceAction): string {
  const person = action.employee;
  return `${person.givenNames} ${person.familyName} (${person.employeeNumber})`;
}

/**
 * How the employee sees one of their events: their own (nothing to add) or recorded by a
 * supervisor, with the reason exactly as submitted and its language. Never who recorded it.
 */
export interface AssistedEvent {
  reasonLocale: 'en' | 'fr';
  reason: string;
}

export function assistedOf(
  method: 'SELF' | 'SUPERVISOR' | null | undefined,
  locale: 'en' | 'fr' | null | undefined,
  reason: string | null | undefined,
): AssistedEvent | null {
  return method === 'SUPERVISOR' && reason && locale ? { reasonLocale: locale, reason } : null;
}

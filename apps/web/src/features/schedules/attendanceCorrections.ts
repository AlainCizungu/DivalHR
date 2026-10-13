import type {
  AttendanceCorrectionPreview,
  AttendanceCorrectionReason,
  Problem,
} from '@divalhr/api-client';
import { codePoints } from '../leave/leaveApprovals';
import type { ExceptionRow } from './attendanceExceptions';

/**
 * MVP-062: a tenant administrator's correction of factual attendance evidence. The browser only
 * collects the proposal and shows the server's preview: it never computes an exception, a window or
 * a version. Times are entered as site-local date-times and sent as RFC 3339 instants carrying the
 * site's own UTC offset at that moment (never the browser's), so nothing is silently converted.
 */

/** The closed reason codes, in the order of the selector. */
export const CORRECTION_REASONS: readonly AttendanceCorrectionReason[] = [
  'DEVICE_OR_CONNECTIVITY',
  'SUPERVISOR_OR_EMPLOYEE_REPORT',
  'DATA_ENTRY_ERROR',
  'SYSTEM_ERROR',
  'OTHER',
];

export const EXPLANATION_MIN = 10;
export const EXPLANATION_MAX = 500;

/** What a correction targets: a record (REPLACE_TIMES) or a slot's missing record. */
export type CorrectionTarget =
  | { kind: 'record'; recordId: string; row: ExceptionRow }
  | { kind: 'missing'; slotId: string; employeeId: string; row: ExceptionRow };

/** The target of an exception card: a recorded exception corrects its record. */
export function targetOf(row: ExceptionRow): CorrectionTarget | null {
  if (row.attendanceRecordId) return { kind: 'record', recordId: row.attendanceRecordId, row };
  if (row.type === 'MISSING_CHECK_IN' && row.employee) {
    return { kind: 'missing', slotId: row.slotId, employeeId: row.employee.id, row };
  }
  return null;
}

/**
 * One time field: the site-local value the administrator sees, and the exact server instant it
 * was prefilled from (kept while the value is untouched, so an unchanged time is sent exactly).
 */
export interface TimeField {
  local: string;
  original: string | null;
}

export interface CorrectionForm {
  checkIn: TimeField;
  hasCheckOut: boolean;
  checkOut: TimeField;
  reasonCode: '' | AttendanceCorrectionReason;
  explanation: string;
}

export const CORRECTION_FIELDS = [
  'checkedInAt',
  'checkedOutAt',
  'reasonCode',
  'explanation',
] as const;
export type CorrectionField = (typeof CORRECTION_FIELDS)[number];

/** The problem of a field the browser can already tell; the server checks everything again. */
export type CorrectionProblem = 'required' | 'format' | 'order' | 'length';

const LOCAL = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/u;

/** The UTC offset of the zone at an instant, in minutes. */
export function offsetMinutes(zone: string, epochMs: number): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(epochMs));
  const value = (type: string) => Number(parts.find((part) => part.type === type)?.value);
  const asUtc = Date.UTC(
    value('year'),
    value('month') - 1,
    value('day'),
    value('hour'),
    value('minute'),
    value('second'),
  );
  return Math.round((asUtc - Math.floor(epochMs / 1000) * 1000) / 60000);
}

function two(value: number): string {
  return String(value).padStart(2, '0');
}

/**
 * A site-local date-time as an RFC 3339 instant with the site's offset at that moment, or null
 * when it is malformed or does not exist there (a daylight-saving gap).
 */
export function zonedInstant(local: string, zone: string): string | null {
  const match = LOCAL.exec(local);
  if (!match) return null;
  const [year, month, day, hour, minute] = match.slice(1, 6).map(Number) as [
    number,
    number,
    number,
    number,
    number,
  ];
  const second = Number(match[6] ?? '0');
  const asUtc = Date.UTC(year, month - 1, day, hour, minute, second);
  if (Number.isNaN(asUtc)) return null;
  let guess = asUtc;
  for (let i = 0; i < 3; i++) guess = asUtc - offsetMinutes(zone, guess) * 60000;
  const offset = offsetMinutes(zone, guess);
  if (guess + offset * 60000 !== asUtc) return null;
  const sign = offset < 0 ? '-' : '+';
  const abs = Math.abs(offset);
  return (
    `${String(year)}-${two(month)}-${two(day)}T${two(hour)}:${two(minute)}:${two(second)}` +
    `${sign}${two(Math.floor(abs / 60))}:${two(abs % 60)}`
  );
}

/**
 * An instant as the site-local value of a date-time input, in the input's own normalized form:
 * seconds only when they are not zero (fractions of a second are not shown).
 */
export function zonedLocal(instant: string, zone: string): string {
  const epoch = new Date(instant).getTime();
  const local = new Date(epoch + offsetMinutes(zone, epoch) * 60000);
  const seconds = local.getUTCSeconds();
  return (
    `${String(local.getUTCFullYear())}-${two(local.getUTCMonth() + 1)}-${two(local.getUTCDate())}` +
    `T${two(local.getUTCHours())}:${two(local.getUTCMinutes())}` +
    (seconds === 0 ? '' : `:${two(seconds)}`)
  );
}

export function timeField(instant: string | null, zone: string): TimeField {
  return instant
    ? { local: zonedLocal(instant, zone), original: instant }
    : { local: '', original: null };
}

/** The value sent for a field: the exact original while untouched, else the site-local entry. */
export function instantOf(field: TimeField, zone: string): string | null {
  if (field.original && zonedLocal(field.original, zone) === field.local) return field.original;
  return zonedInstant(field.local, zone);
}

/** The explanation as the server stores it: surrounding whitespace removed. */
export function explanationOf(text: string): string {
  return text.trim();
}

function explanationProblem(text: string): CorrectionProblem | null {
  const trimmed = explanationOf(text);
  if (!trimmed) return 'required';
  const length = codePoints(trimmed);
  if (length < EXPLANATION_MIN || length > EXPLANATION_MAX) return 'length';
  // eslint-disable-next-line no-control-regex
  if (/[\u0000-\u001f\u007f-\u009f<>]|\p{Cf}/u.test(trimmed)) return 'format';
  if (/(https?:\/\/|ftp:\/\/|www\.|mailto:)/iu.test(trimmed)) return 'format';
  return null;
}

/** Every problem the browser can tell, in field order. */
export function correctionProblemsOf(
  form: CorrectionForm,
  zone: string,
): Map<CorrectionField, CorrectionProblem> {
  const problems = new Map<CorrectionField, CorrectionProblem>();
  const checkIn = form.checkIn.local ? instantOf(form.checkIn, zone) : null;
  if (!form.checkIn.local) problems.set('checkedInAt', 'required');
  else if (!checkIn) problems.set('checkedInAt', 'format');
  if (form.hasCheckOut) {
    const checkOut = form.checkOut.local ? instantOf(form.checkOut, zone) : null;
    if (!form.checkOut.local) problems.set('checkedOutAt', 'required');
    else if (!checkOut) problems.set('checkedOutAt', 'format');
    else if (checkIn && new Date(checkOut).getTime() <= new Date(checkIn).getTime()) {
      problems.set('checkedOutAt', 'order');
    }
  }
  if (!form.reasonCode) problems.set('reasonCode', 'required');
  const explanation = explanationProblem(form.explanation);
  if (explanation) problems.set('explanation', explanation);
  return problems;
}

/** The proposal's body: the same values for the preview and the commit. */
export function proposalOf(form: CorrectionForm, zone: string) {
  return {
    checkedInAt: instantOf(form.checkIn, zone) ?? '',
    checkedOutAt: form.hasCheckOut ? instantOf(form.checkOut, zone) : null,
    reasonCode: form.reasonCode as AttendanceCorrectionReason,
    explanation: explanationOf(form.explanation),
  };
}

/** The commit's body: the identical proposal, the previewed version and digest, the confirmation. */
export function commitOf(
  proposal: ReturnType<typeof proposalOf>,
  preview: AttendanceCorrectionPreview,
) {
  return {
    ...proposal,
    targetVersion: preview.targetVersion,
    previewDigest: preview.previewDigest,
    confirm: true as const,
  };
}

/** A refused or failed request, reduced to stable codes and allow-listed fields. */
export interface CorrectionFailure {
  kind:
    | 'validation'
    | 'notFound'
    | 'notAllowed'
    | 'stale'
    | 'forbidden'
    | 'rateLimited'
    | 'idempotency'
    | 'general';
  messageKey: string;
  fields?: CorrectionField[];
  correlationId?: string;
  retryAfter?: number;
}

export const CORRECTION_NETWORK_FAILURE: CorrectionFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

const CODES: Record<string, CorrectionFailure['kind']> = {
  ATTENDANCE_CORRECTION_TARGET_NOT_FOUND: 'notFound',
  ATTENDANCE_CORRECTION_NOT_ALLOWED: 'notAllowed',
  ATTENDANCE_CORRECTION_PREVIEW_STALE: 'stale',
  IDEMPOTENCY_KEY_REUSED: 'idempotency',
};

/** Maps a Problem response; no param outside the field allow-list is ever shown. */
export function correctionFailureOf(response: Response, error: unknown): CorrectionFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code ?? '';
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'attendanceCorrection.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  const kind = CODES[code];
  if (kind) return { ...base, kind, messageKey: `errors.${code}` };
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<CorrectionField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && (CORRECTION_FIELDS as readonly string[]).includes(name)) {
          fields.add(name as CorrectionField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'attendanceCorrection.validation',
      fields: CORRECTION_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

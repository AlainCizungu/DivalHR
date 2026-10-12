import type {
  CreateWorkScheduleSubstitutionRequest,
  DecideWorkScheduleSubstitution,
  MyWorkScheduleSubstitution,
  MyWorkShift,
  Problem,
  WorkScheduleSubstitutionPerson,
} from '@divalhr/api-client';
import {
  codePoints,
  normalizeReason,
  reasonAcceptable,
  type ReasonLocale,
} from '../leave/leaveApprovals';

/** MVP-051: the reason form fields (request and decision), in the order problems are listed. */
export const SUBSTITUTION_FIELDS = ['substitute', 'reasonLocale', 'reason'] as const;
export type SubstitutionField = (typeof SUBSTITUTION_FIELDS)[number];

export type SubstitutionOutcome = DecideWorkScheduleSubstitution['decision'];

/** A reason typed by the employee or the manager. */
export interface ReasonForm {
  reasonLocale: ReasonLocale;
  reason: string;
}

/** The manager's decision form; the outcome comes from the button that opened it. */
export interface SubstitutionDecisionForm extends ReasonForm {
  decision: SubstitutionOutcome;
  substitute: WorkScheduleSubstitutionPerson | null;
}

/** What the browser can already tell is wrong with a reason; the server checks it again. */
export function reasonProblemsOf(form: ReasonForm): Set<SubstitutionField> {
  const problems = new Set<SubstitutionField>();
  if (!(['en', 'fr'] as readonly string[]).includes(form.reasonLocale)) {
    problems.add('reasonLocale');
  }
  if (!reasonAcceptable(form.reason)) problems.add('reason');
  return problems;
}

/** A decision also needs a chosen substitute when it approves. */
export function decisionProblemsOf(form: SubstitutionDecisionForm): Set<SubstitutionField> {
  const problems = reasonProblemsOf(form);
  if (form.decision === 'APPROVED' && !form.substitute) problems.add('substitute');
  return new Set(SUBSTITUTION_FIELDS.filter((field) => problems.has(field)));
}

export function requestBodyOf(
  slotId: string,
  form: ReasonForm,
): CreateWorkScheduleSubstitutionRequest {
  return { slotId, reasonLocale: form.reasonLocale, reason: normalizeReason(form.reason) };
}

/** A rejection names no substitute; an approval always names one. */
export function decisionBodyOf(form: SubstitutionDecisionForm): DecideWorkScheduleSubstitution {
  return form.decision === 'APPROVED'
    ? {
        decision: 'APPROVED',
        substituteEmployeeId: form.substitute?.id ?? null,
        reasonLocale: form.reasonLocale,
        reason: normalizeReason(form.reason),
      }
    : {
        decision: 'REJECTED',
        reasonLocale: form.reasonLocale,
        reason: normalizeReason(form.reason),
      };
}

/** The candidate search text: NFC, trimmed, at least two code points (the server's rule). */
export function candidateQueryOf(text: string): string | null {
  const query = text.normalize('NFC').trim();
  const length = codePoints(query);
  return length >= 2 && length <= 100 ? query : null;
}

/** "Given Family (number)" as plain text, never markup. */
export function personName(person: {
  givenNames: string;
  familyName: string;
  employeeNumber: string;
}): string {
  return `${person.givenNames} ${person.familyName} (${person.employeeNumber})`;
}

/**
 * The current local date-time at a site, `YYYY-MM-DDTHH:MM`, from the browser clock in the site's
 * IANA time zone (display only: the server decides with its own instant).
 */
export function localNow(zone: string, now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(now);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((entry) => entry.type === type)?.value ?? '00';
  return `${part('year')}-${part('month')}-${part('day')}T${part('hour')}:${part('minute')}`;
}

/**
 * Whether the employee may ask for a substitute on this shift from the page: published to them,
 * not yet started at the site, and without a pending request already listed. The server decides.
 */
export function requestable(
  shift: MyWorkShift,
  pendingSlots: ReadonlySet<string>,
  now: Date = new Date(),
): boolean {
  if (shift.assignmentSource !== 'PUBLISHED' || pendingSlots.has(shift.id)) return false;
  try {
    return shift.start > localNow(shift.siteTimezone, now);
  } catch {
    return true;
  }
}

/** The slots that already have a pending request in the loaded history. */
export function pendingSlotsOf(items: readonly MyWorkScheduleSubstitution[]): Set<string> {
  return new Set(items.filter((item) => item.state === 'PENDING').map((item) => item.shift.slotId));
}

const NOT_AVAILABLE = new Set(['NOT_OPERATIONAL', 'SUBSTITUTED', 'EMPLOYMENT', 'NO_MANAGER']);

const SERVER_FIELDS: Record<string, SubstitutionField> = {
  reasonLocale: 'reasonLocale',
  reason: 'reason',
  substituteEmployeeId: 'substitute',
};

/** A refused or failed substitution call, reduced to stable codes and allow-listed details. */
export interface SubstitutionFailure {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'gone'
    | 'pending'
    | 'decided'
    | 'closed'
    | 'unavailable'
    | 'notEligible'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: SubstitutionField[];
  correlationId?: string;
  retryAfter?: number;
}

export const SUBSTITUTION_NETWORK_FAILURE: SubstitutionFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Kinds after which the request or shift cannot be acted on any more here. */
export const SETTLED_SUBSTITUTION_KINDS: ReadonlySet<SubstitutionFailure['kind']> = new Set([
  'gone',
  'pending',
  'decided',
  'closed',
  'unavailable',
]);

/** Maps a Problem response; params outside the allow-lists are dropped, never shown. */
export function substitutionFailureOf(response: Response, error: unknown): SubstitutionFailure {
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
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'scheduleSubstitutions.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  switch (code) {
    case 'WORK_SCHEDULE_SHIFT_NOT_FOUND':
    case 'WORK_SCHEDULE_SUBSTITUTION_NOT_FOUND':
      return { ...base, kind: 'gone', messageKey: `errors.${code}` };
    case 'WORK_SCHEDULE_SUBSTITUTION_ALREADY_PENDING':
      return { ...base, kind: 'pending', messageKey: `errors.${code}` };
    case 'WORK_SCHEDULE_SUBSTITUTION_ALREADY_DECIDED':
      return { ...base, kind: 'decided', messageKey: `errors.${code}` };
    case 'WORK_SCHEDULE_SUBSTITUTION_WINDOW_CLOSED':
      return { ...base, kind: 'closed', messageKey: `errors.${code}` };
    case 'WORK_SCHEDULE_SUBSTITUTION_NOT_AVAILABLE': {
      const reason =
        typeof params.reason === 'string' && NOT_AVAILABLE.has(params.reason)
          ? params.reason
          : null;
      return {
        ...base,
        kind: 'unavailable',
        messageKey: reason ? `scheduleSubstitutions.notAvailable.${reason}` : `errors.${code}`,
      };
    }
    case 'WORK_SCHEDULE_SUBSTITUTE_NOT_ELIGIBLE':
      return { ...base, kind: 'notEligible', messageKey: `errors.${code}` };
    case 'WORK_SCHEDULE_SUBSTITUTE_CONFLICT':
      return { ...base, kind: 'conflict', messageKey: `errors.${code}` };
    case 'IDEMPOTENCY_KEY_REUSED':
      return { ...base, kind: 'general', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
    case 'CURSOR_INVALID':
      return { ...base, kind: 'general', messageKey: 'errors.CURSOR_INVALID' };
    case 'VALIDATION_FAILED': {
      const raw = params.fields;
      const fields = new Set<SubstitutionField>();
      if (Array.isArray(raw)) {
        for (const entry of raw) {
          const name = (entry as { field?: unknown }).field;
          if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
            fields.add(SERVER_FIELDS[name] as SubstitutionField);
          }
        }
      }
      return {
        ...base,
        kind: 'validation',
        messageKey: 'errors.VALIDATION_FAILED',
        fields: SUBSTITUTION_FIELDS.filter((field) => fields.has(field)),
      };
    }
    default:
      return { ...base, kind: 'general', messageKey: 'errors.generic' };
  }
}

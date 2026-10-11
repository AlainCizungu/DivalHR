import type {
  CreateLeaveApprovalSubstitution,
  EmployeeSummary,
  LeaveApprovalSubstitutionState,
  Problem,
  RevokeLeaveApprovalSubstitution,
} from '@divalhr/api-client';
import type { StatusTone } from '../../ui/primitives';
import { normalizeReason, reasonAcceptable, type ReasonLocale } from './leaveApprovals';

/** MVP-041G: the appointment form fields, in the order problems are listed. */
export const SUBSTITUTE_FIELDS = [
  'manager',
  'substitute',
  'effectiveFrom',
  'effectiveTo',
  'reasonLocale',
  'reason',
] as const;
export type SubstituteField = (typeof SUBSTITUTE_FIELDS)[number];

/** The revocation form fields. */
export const REVOKE_FIELDS = ['reasonLocale', 'reason'] as const;
export type RevokeField = (typeof REVOKE_FIELDS)[number];

/** What the administrator chose and typed. Employees come from the directory search only. */
export interface SubstituteForm {
  manager: EmployeeSummary | null;
  substitute: EmployeeSummary | null;
  effectiveFrom: string;
  effectiveTo: string;
  reasonLocale: ReasonLocale;
  reason: string;
}

export interface RevokeForm {
  reasonLocale: ReasonLocale;
  reason: string;
}

export function emptySubstituteForm(language: ReasonLocale): SubstituteForm {
  return {
    manager: null,
    substitute: null,
    effectiveFrom: '',
    effectiveTo: '',
    reasonLocale: language,
    reason: '',
  };
}

const ISO_DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;

/** A calendar date the server accepts: strict ISO, 1900-01-01 to 2999-12-31. */
export function validDate(text: string): boolean {
  if (!ISO_DATE.test(text)) return false;
  const [year, month, day] = text.split('-').map(Number) as [number, number, number];
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day &&
    text >= '1900-01-01' &&
    text <= '2999-12-31'
  );
}

/**
 * What the browser can already tell is wrong with the appointment; the server checks everything
 * again, and the employment, reporting-line and overlap rules only there.
 */
export function substituteProblemsOf(form: SubstituteForm): Set<SubstituteField> {
  const problems = new Set<SubstituteField>();
  if (!form.manager) problems.add('manager');
  if (!form.substitute || form.substitute.id === form.manager?.id) problems.add('substitute');
  const from = validDate(form.effectiveFrom);
  if (!from) problems.add('effectiveFrom');
  if (!validDate(form.effectiveTo) || (from && form.effectiveTo < form.effectiveFrom)) {
    problems.add('effectiveTo');
  }
  if (!(['en', 'fr'] as readonly string[]).includes(form.reasonLocale)) {
    problems.add('reasonLocale');
  }
  if (!reasonAcceptable(form.reason)) problems.add('reason');
  return problems;
}

/** The request body of a valid appointment form: IDs, dates, the normalized reason. */
export function substituteBodyOf(form: SubstituteForm): CreateLeaveApprovalSubstitution {
  return {
    managerEmployeeId: form.manager?.id ?? '',
    substituteEmployeeId: form.substitute?.id ?? '',
    effectiveFrom: form.effectiveFrom,
    effectiveTo: form.effectiveTo,
    reasonLocale: form.reasonLocale,
    reason: normalizeReason(form.reason),
  };
}

export function revokeProblemsOf(form: RevokeForm): Set<RevokeField> {
  const problems = new Set<RevokeField>();
  if (!(['en', 'fr'] as readonly string[]).includes(form.reasonLocale)) {
    problems.add('reasonLocale');
  }
  if (!reasonAcceptable(form.reason)) problems.add('reason');
  return problems;
}

export function revokeBodyOf(form: RevokeForm): RevokeLeaveApprovalSubstitution {
  return { reasonLocale: form.reasonLocale, reason: normalizeReason(form.reason) };
}

/** Server field names of VALIDATION_FAILED mapped to form fields (anything else is ignored). */
const SERVER_FIELDS: Record<string, SubstituteField> = {
  managerEmployeeId: 'manager',
  substituteEmployeeId: 'substitute',
  effectiveFrom: 'effectiveFrom',
  effectiveTo: 'effectiveTo',
  reasonLocale: 'reasonLocale',
  reason: 'reason',
};

/** The stable refusals of an appointment or a revocation, shown with their own message. */
const REFUSALS = new Set([
  'LEAVE_APPROVAL_SUBSTITUTION_SAME_EMPLOYEE',
  'LEAVE_APPROVAL_SUBSTITUTION_EMPLOYMENT_PERIOD',
  'LEAVE_APPROVAL_SUBSTITUTION_NO_REPORTING_LINE',
  'LEAVE_APPROVAL_SUBSTITUTION_OVERLAP',
  'EMPLOYEE_NOT_FOUND',
  'IDEMPOTENCY_KEY_REUSED',
]);

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export interface SubstituteFailure {
  kind: 'mfaRequired' | 'forbidden' | 'rateLimited' | 'validation' | 'refused' | 'gone' | 'general';
  messageKey: string;
  fields?: SubstituteField[];
  correlationId?: string;
  retryAfter?: number;
}

export const SUBSTITUTE_NETWORK_FAILURE: SubstituteFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; params outside the allow-list are dropped, never shown. */
export function substituteFailureOf(response: Response, error: unknown): SubstituteFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'MFA_REQUIRED') {
    return { ...base, kind: 'mfaRequired', messageKey: 'errors.MFA_REQUIRED' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'leaveSubstitutes.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (
    code === 'LEAVE_APPROVAL_SUBSTITUTION_NOT_FOUND' ||
    code === 'LEAVE_APPROVAL_SUBSTITUTION_ALREADY_REVOKED'
  ) {
    return { ...base, kind: 'gone', messageKey: 'leaveSubstitutes.revoke.gone' };
  }
  if (code && REFUSALS.has(code)) {
    return { ...base, kind: 'refused', messageKey: `errors.${code}` };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = (problem?.params as { fields?: unknown } | undefined)?.fields;
    const fields = new Set<SubstituteField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as SubstituteField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: SUBSTITUTE_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return {
    ...base,
    kind: 'general',
    messageKey: code === 'CURSOR_INVALID' ? 'errors.CURSOR_INVALID' : 'errors.generic',
  };
}

/** The colour that reinforces a state; the text always carries the meaning. */
export function substituteToneOf(state: LeaveApprovalSubstitutionState): StatusTone {
  switch (state) {
    case 'ACTIVE':
      return 'success';
    case 'SCHEDULED':
      return 'info';
    case 'REVOKED':
      return 'warning';
    default:
      return 'neutral';
  }
}

/** Whether an appointment can still be revoked (the server decides again). */
export function revocable(state: LeaveApprovalSubstitutionState): boolean {
  return state !== 'REVOKED';
}

import type { AmendMyLeaveRequest, MyLeaveRequest, Problem } from '@divalhr/api-client';
import { normalizeReason, reasonAcceptable, type ReasonLocale } from './leaveApprovals';
import { requestBodyOf, requestProblemsOf, type LeaveRequestForm } from './myLeave';

/**
 * MVP-041D: the amendment form fields (the submission fields, then the reason), in the order
 * problems are listed.
 */
export const AMENDMENT_FIELDS = [
  'policy',
  'startDate',
  'endDate',
  'amount',
  'reasonLocale',
  'reason',
] as const;
export type AmendmentField = (typeof AMENDMENT_FIELDS)[number];

/** What the employee typed: the replacement's fields and the reason. */
export interface AmendmentForm extends LeaveRequestForm {
  reasonLocale: ReasonLocale;
  reason: string;
}

/** The form opened on a pending request: its current values, an empty reason. */
export function amendmentFormOf(
  request: MyLeaveRequest,
  reasonLocale: ReasonLocale,
): AmendmentForm {
  return {
    policyId: request.policyId,
    startDate: request.startDate,
    endDate: request.endDate,
    amount: String(request.amount),
    reasonLocale,
    reason: '',
  };
}

/**
 * What the browser can already tell is wrong: the submission rules of a new request and the
 * decision-reason grammar version 1; the server checks everything again.
 */
export function amendmentProblemsOf(form: AmendmentForm, asOf: string | null): Set<AmendmentField> {
  const problems = new Set<AmendmentField>(requestProblemsOf(form, asOf));
  if (!(['en', 'fr'] as readonly string[]).includes(form.reasonLocale)) {
    problems.add('reasonLocale');
  }
  if (!reasonAcceptable(form.reason)) problems.add('reason');
  return new Set(AMENDMENT_FIELDS.filter((field) => problems.has(field)));
}

/** The request body of a valid form: the submission body, the locale and the normalized reason. */
export function amendmentBodyOf(form: AmendmentForm): AmendMyLeaveRequest {
  return {
    ...requestBodyOf(form),
    reasonLocale: form.reasonLocale,
    reason: normalizeReason(form.reason),
  };
}

const SERVER_FIELDS: Record<string, AmendmentField> = {
  policyId: 'policy',
  startDate: 'startDate',
  endDate: 'endDate',
  amount: 'amount',
  reasonLocale: 'reasonLocale',
  reason: 'reason',
};

const NOT_ELIGIBLE = new Set(['EMPLOYMENT_PERIOD', 'MINIMUM_SERVICE']);

/** A refused or failed amendment, reduced to stable codes and allow-listed details. */
export interface AmendmentFailure {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'unavailable'
    | 'policyUnavailable'
    | 'notEligible'
    | 'overlap'
    | 'alreadyAmended'
    | 'alreadyCancelled'
    | 'alreadyDecided'
    | 'alreadyWithdrawn'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: AmendmentField[];
  correlationId?: string;
  retryAfter?: number;
}

export const AMENDMENT_NETWORK_FAILURE: AmendmentFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/**
 * Kinds after which the request can no longer be amended here: the list is refreshed from the
 * server and the confirmation is disabled.
 */
export const AMENDMENT_SETTLED_KINDS: ReadonlySet<AmendmentFailure['kind']> = new Set([
  'unavailable',
  'alreadyAmended',
  'alreadyCancelled',
  'alreadyDecided',
  'alreadyWithdrawn',
]);

/** Maps a Problem response; params outside the allow-lists are dropped, never shown. */
export function amendmentFailureOf(response: Response, error: unknown): AmendmentFailure {
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
    return { ...base, kind: 'forbidden', messageKey: 'myLeave.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'LEAVE_REQUEST_NOT_FOUND') {
    return { ...base, kind: 'unavailable', messageKey: 'errors.LEAVE_REQUEST_NOT_FOUND' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_AMENDED') {
    return { ...base, kind: 'alreadyAmended', messageKey: 'myLeave.amend.alreadyAmended' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_CANCELLED') {
    return { ...base, kind: 'alreadyCancelled', messageKey: 'myLeave.amend.alreadyCancelled' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_WITHDRAWN') {
    return { ...base, kind: 'alreadyWithdrawn', messageKey: 'myLeave.amend.alreadyWithdrawn' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_DECIDED') {
    return { ...base, kind: 'alreadyDecided', messageKey: 'myLeave.amend.alreadyDecided' };
  }
  if (code === 'LEAVE_POLICY_NOT_REQUESTABLE') {
    return {
      ...base,
      kind: 'policyUnavailable',
      messageKey: 'errors.LEAVE_POLICY_NOT_REQUESTABLE',
    };
  }
  if (code === 'LEAVE_REQUEST_NOT_ELIGIBLE') {
    const reason = typeof params.reason === 'string' ? params.reason : '';
    return {
      ...base,
      kind: 'notEligible',
      messageKey: NOT_ELIGIBLE.has(reason)
        ? `myLeave.notEligible.${reason}`
        : 'errors.LEAVE_REQUEST_NOT_ELIGIBLE',
    };
  }
  if (code === 'LEAVE_REQUEST_OVERLAP') {
    return { ...base, kind: 'overlap', messageKey: 'myLeave.amend.overlap' };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<AmendmentField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as AmendmentField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: AMENDMENT_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

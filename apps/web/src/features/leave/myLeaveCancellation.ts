import type { CancelMyLeaveRequest, Problem } from '@divalhr/api-client';
import { normalizeReason, reasonAcceptable, type ReasonLocale } from './leaveApprovals';

/** MVP-041C: the cancellation form fields, in the order problems are listed. */
export const CANCELLATION_FIELDS = ['reasonLocale', 'reason'] as const;
export type CancellationField = (typeof CANCELLATION_FIELDS)[number];

/** What the employee typed. */
export interface CancellationForm {
  reasonLocale: ReasonLocale;
  reason: string;
}

/**
 * What the browser can already tell is wrong with the form (the same decision-reason grammar
 * version 1 as decisions); the server checks everything again.
 */
export function cancellationProblemsOf(form: CancellationForm): Set<CancellationField> {
  const problems = new Set<CancellationField>();
  if (!(['en', 'fr'] as readonly string[]).includes(form.reasonLocale)) {
    problems.add('reasonLocale');
  }
  if (!reasonAcceptable(form.reason)) problems.add('reason');
  return problems;
}

/** The request body of a valid form: the normalized reason, the locale always sent. */
export function cancellationBodyOf(form: CancellationForm): CancelMyLeaveRequest {
  return { reasonLocale: form.reasonLocale, reason: normalizeReason(form.reason) };
}

const SERVER_FIELDS: Record<string, CancellationField> = {
  reasonLocale: 'reasonLocale',
  reason: 'reason',
};

/** A refused or failed cancellation, reduced to stable codes and allow-listed details. */
export interface CancellationFailure {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'unavailable'
    | 'alreadyCancelled'
    | 'alreadyAmended'
    | 'alreadyDecided'
    | 'alreadyWithdrawn'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: CancellationField[];
  correlationId?: string;
  retryAfter?: number;
}

export const CANCELLATION_NETWORK_FAILURE: CancellationFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/**
 * Kinds after which the request can no longer be cancelled here: the list is refreshed from the
 * server and the confirmation is disabled.
 */
export const SETTLED_KINDS: ReadonlySet<CancellationFailure['kind']> = new Set([
  'unavailable',
  'alreadyCancelled',
  'alreadyAmended',
  'alreadyDecided',
  'alreadyWithdrawn',
]);

/** Maps a Problem response; params outside the allow-lists are dropped, never shown. */
export function cancellationFailureOf(response: Response, error: unknown): CancellationFailure {
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
  if (code === 'LEAVE_REQUEST_ALREADY_CANCELLED') {
    return { ...base, kind: 'alreadyCancelled', messageKey: 'myLeave.cancel.alreadyCancelled' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_AMENDED') {
    return { ...base, kind: 'alreadyAmended', messageKey: 'myLeave.cancel.alreadyAmended' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_WITHDRAWN') {
    return { ...base, kind: 'alreadyWithdrawn', messageKey: 'myLeave.cancel.alreadyWithdrawn' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_DECIDED') {
    return { ...base, kind: 'alreadyDecided', messageKey: 'myLeave.cancel.alreadyDecided' };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<CancellationField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as CancellationField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: CANCELLATION_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

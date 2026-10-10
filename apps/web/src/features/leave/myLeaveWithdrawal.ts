import type { MyLeaveRequest, Problem, WithdrawMyApprovedLeave } from '@divalhr/api-client';
import {
  cancellationBodyOf,
  cancellationProblemsOf,
  type CancellationField,
  type CancellationForm,
} from './myLeaveCancellation';

/**
 * MVP-041F: the withdrawal form is a reason and its language, validated exactly as a cancellation
 * (the decision-reason grammar version 1); the server checks everything again.
 */
export const WITHDRAWAL_FIELDS = ['reasonLocale', 'reason'] as const;
export type WithdrawalField = CancellationField;
export type WithdrawalForm = CancellationForm;

export const withdrawalProblemsOf: (form: WithdrawalForm) => Set<WithdrawalField> =
  cancellationProblemsOf;

/** The request body of a valid form: the normalized reason, the locale always sent. */
export function withdrawalBodyOf(form: WithdrawalForm): WithdrawMyApprovedLeave {
  return cancellationBodyOf(form);
}

/**
 * Whether the page offers the withdrawal: approved leave whose first day is after the server-owned
 * business date of the list (`asOf`). The server re-checks it under the request's row lock.
 */
export function withdrawable(request: MyLeaveRequest, asOf: string | null): boolean {
  return (
    request.state === 'APPROVED' &&
    request.withdrawal === null &&
    asOf !== null &&
    request.startDate > asOf
  );
}

const SERVER_FIELDS: Record<string, WithdrawalField> = {
  reasonLocale: 'reasonLocale',
  reason: 'reason',
};

/** A refused or failed withdrawal, reduced to stable codes and allow-listed details. */
export interface WithdrawalFailure {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'unavailable'
    | 'alreadyWithdrawn'
    | 'notApproved'
    | 'windowClosed'
    | 'alreadyCancelled'
    | 'alreadyAmended'
    | 'alreadyDecided'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: WithdrawalField[];
  correlationId?: string;
  retryAfter?: number;
}

export const WITHDRAWAL_NETWORK_FAILURE: WithdrawalFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/**
 * Kinds after which the leave can no longer be withdrawn here: the list is refreshed from the
 * server and the confirmation is disabled.
 */
export const SETTLED_WITHDRAWAL_KINDS: ReadonlySet<WithdrawalFailure['kind']> = new Set([
  'unavailable',
  'alreadyWithdrawn',
  'notApproved',
  'windowClosed',
  'alreadyCancelled',
  'alreadyAmended',
  'alreadyDecided',
]);

const STATE_CONFLICTS: Record<string, [WithdrawalFailure['kind'], string]> = {
  LEAVE_REQUEST_ALREADY_WITHDRAWN: ['alreadyWithdrawn', 'myLeave.withdraw.alreadyWithdrawn'],
  LEAVE_REQUEST_NOT_APPROVED: ['notApproved', 'myLeave.withdraw.notApproved'],
  LEAVE_REQUEST_WITHDRAWAL_WINDOW_CLOSED: ['windowClosed', 'myLeave.withdraw.windowClosed'],
  LEAVE_REQUEST_ALREADY_CANCELLED: ['alreadyCancelled', 'myLeave.withdraw.alreadyCancelled'],
  LEAVE_REQUEST_ALREADY_AMENDED: ['alreadyAmended', 'myLeave.withdraw.alreadyAmended'],
  LEAVE_REQUEST_ALREADY_DECIDED: ['alreadyDecided', 'myLeave.withdraw.alreadyDecided'],
};

/** Maps a Problem response; params outside the allow-lists are dropped, never shown. */
export function withdrawalFailureOf(response: Response, error: unknown): WithdrawalFailure {
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
  if (code !== undefined && Object.hasOwn(STATE_CONFLICTS, code)) {
    const [kind, messageKey] = STATE_CONFLICTS[code] as [WithdrawalFailure['kind'], string];
    return { ...base, kind, messageKey };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<WithdrawalField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as WithdrawalField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: WITHDRAWAL_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

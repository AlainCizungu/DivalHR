import type { DecideLeaveRequest, Problem } from '@divalhr/api-client';

/** MVP-041B: which inbox a page shows. */
export type ApprovalScope = 'manager' | 'admin';

export type DecisionOutcome = DecideLeaveRequest['decision'];
export type ReasonLocale = DecideLeaveRequest['reasonLocale'];

/** The decision form fields, in the order problems are listed. */
export const DECISION_FIELDS = ['reasonLocale', 'reason'] as const;
export type DecisionField = (typeof DECISION_FIELDS)[number];

/** What the approver typed. The outcome comes from the button that opened the form. */
export interface DecisionForm {
  decision: DecisionOutcome;
  reasonLocale: ReasonLocale;
  reason: string;
}

export const REASON_MIN = 2;
export const REASON_MAX = 500;

/**
 * Characters the server refuses in a reason (control, format, line and paragraph separators,
 * private-use and unassigned code points), as in the V20 grammar.
 */
const UNSAFE = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Co}\p{Cn}]/u;

/** The reason as the server will store it: NFC-normalized and trimmed. */
export function normalizeReason(text: string): string {
  return text.normalize('NFC').trim();
}

/** Unicode code points of a text (a supplementary character counts once, as on the server). */
export function codePoints(text: string): number {
  return Array.from(text).length;
}

/**
 * What the browser can already tell is wrong with the form; the server checks everything again.
 *
 * @param form what was typed
 * @returns the problem fields
 */
export function decisionProblemsOf(form: DecisionForm): Set<DecisionField> {
  const problems = new Set<DecisionField>();
  // The locale comes from a closed select, but the server checks it again.
  if (!(['en', 'fr'] as readonly string[]).includes(form.reasonLocale))
    problems.add('reasonLocale');
  const reason = normalizeReason(form.reason);
  const length = codePoints(reason);
  if (length < REASON_MIN || length > REASON_MAX || UNSAFE.test(reason)) problems.add('reason');
  return problems;
}

/** The request body of a valid form: the normalized reason, the locale sent explicitly. */
export function decisionBodyOf(form: DecisionForm): DecideLeaveRequest {
  return {
    decision: form.decision,
    reasonLocale: form.reasonLocale,
    reason: normalizeReason(form.reason),
  };
}

const SERVER_FIELDS: Record<string, DecisionField> = {
  reasonLocale: 'reasonLocale',
  reason: 'reason',
};

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export interface ApprovalFailure {
  kind:
    | 'linkRequired'
    | 'mfaRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'unavailable'
    | 'alreadyDecided'
    | 'notEligible'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: DecisionField[];
  correlationId?: string;
  retryAfter?: number;
}

export const APPROVAL_NETWORK_FAILURE: ApprovalFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/**
 * Kinds after which the request can no longer be decided here: the item leaves the inbox.
 */
export const GONE_KINDS: ReadonlySet<ApprovalFailure['kind']> = new Set([
  'unavailable',
  'alreadyDecided',
]);

/** Maps a Problem response; params outside the allow-lists are dropped, never shown. */
export function approvalFailureOf(
  response: Response,
  error: unknown,
  scope: ApprovalScope,
): ApprovalFailure {
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
  if (code === 'MFA_REQUIRED') {
    return { ...base, kind: 'mfaRequired', messageKey: 'errors.MFA_REQUIRED' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: `leaveApprovals.${scope}.unauthorized` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'LEAVE_REQUEST_NOT_FOUND') {
    return { ...base, kind: 'unavailable', messageKey: 'errors.LEAVE_REQUEST_NOT_FOUND' };
  }
  if (code === 'LEAVE_REQUEST_ALREADY_DECIDED') {
    return { ...base, kind: 'alreadyDecided', messageKey: 'errors.LEAVE_REQUEST_ALREADY_DECIDED' };
  }
  if (code === 'LEAVE_REQUEST_NOT_ELIGIBLE') {
    return {
      ...base,
      kind: 'notEligible',
      messageKey:
        params.reason === 'EMPLOYMENT_PERIOD'
          ? 'leaveApprovals.notEligible.EMPLOYMENT_PERIOD'
          : 'errors.LEAVE_REQUEST_NOT_ELIGIBLE',
    };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<DecisionField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as DecisionField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: DECISION_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return {
    ...base,
    kind: 'general',
    messageKey: code === 'CURSOR_INVALID' ? 'errors.CURSOR_INVALID' : 'errors.generic',
  };
}

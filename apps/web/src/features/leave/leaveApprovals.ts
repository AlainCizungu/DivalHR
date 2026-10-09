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
 * The decision-reason grammar, version 1 (R90-2): the same explicit code point lists as the Core
 * (`LeaveReasonGrammar`) and PostgreSQL (`people.leave_reason_valid`). FORBIDDEN: C0/C1 controls,
 * Unicode 15.0 format characters, line/paragraph separators, private use, noncharacters and
 * surrogates. TRIM: removed from both ends before the checks.
 */
type Range = readonly [number, number];

const TRIM: readonly Range[] = [
  [0x0009, 0x000d],
  [0x0020, 0x0020],
  [0x0085, 0x0085],
  [0x00a0, 0x00a0],
  [0x1680, 0x1680],
  [0x2000, 0x200a],
  [0x2028, 0x2029],
  [0x202f, 0x202f],
  [0x205f, 0x205f],
  [0x3000, 0x3000],
];

const FORBIDDEN: readonly Range[] = [
  // C0 and C1 controls.
  [0x0000, 0x001f],
  [0x007f, 0x009f],
  // Format characters (Cf) of Unicode 15.0.
  [0x00ad, 0x00ad],
  [0x0600, 0x0605],
  [0x061c, 0x061c],
  [0x06dd, 0x06dd],
  [0x070f, 0x070f],
  [0x0890, 0x0891],
  [0x08e2, 0x08e2],
  [0x180e, 0x180e],
  [0x200b, 0x200f],
  [0x202a, 0x202e],
  [0x2060, 0x2064],
  [0x2066, 0x206f],
  [0xfeff, 0xfeff],
  [0xfff9, 0xfffb],
  [0x110bd, 0x110bd],
  [0x110cd, 0x110cd],
  [0x13430, 0x1343f],
  [0x1bca0, 0x1bca3],
  [0x1d173, 0x1d17a],
  [0xe0001, 0xe0001],
  [0xe0020, 0xe007f],
  // Line and paragraph separators.
  [0x2028, 0x2029],
  // Surrogates (an unpaired one in a JavaScript string).
  [0xd800, 0xdfff],
  // Private use.
  [0xe000, 0xf8ff],
  [0xf0000, 0xffffd],
  [0x100000, 0x10fffd],
  // Noncharacters: U+FDD0–U+FDEF and the last two code points of every plane.
  [0xfdd0, 0xfdef],
  ...Array.from({ length: 17 }, (_, plane): Range => [
    plane * 0x10000 + 0xfffe,
    plane * 0x10000 + 0xffff,
  ]),
];

function inRanges(ranges: readonly Range[], codePoint: number): boolean {
  return ranges.some(([low, high]) => codePoint >= low && codePoint <= high);
}

/** Whether a code point is forbidden anywhere in a reason (grammar version 1). */
export function forbiddenCodePoint(codePoint: number): boolean {
  return inRanges(FORBIDDEN, codePoint);
}

/** The code point of one character from `Array.from` (never empty). */
function codePointOf(c: string): number {
  return c.codePointAt(0) ?? 0;
}

/** The reason as the server will store it: NFC-normalized, the trim set removed from both ends. */
export function normalizeReason(text: string): string {
  const chars = Array.from(text.normalize('NFC'));
  const trimmed = (c: string | undefined) => c !== undefined && inRanges(TRIM, codePointOf(c));
  let start = 0;
  let end = chars.length;
  while (start < end && trimmed(chars[start])) start += 1;
  while (end > start && trimmed(chars[end - 1])) end -= 1;
  return chars.slice(start, end).join('');
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
  if (
    length < REASON_MIN ||
    length > REASON_MAX ||
    Array.from(reason).some((c) => forbiddenCodePoint(codePointOf(c)))
  )
    problems.add('reason');
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

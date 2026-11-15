import type { Problem } from '@divalhr/api-client';
import type { PayrollFailure } from '../payrollRuns/payrollRuns';

/**
 * MVP-097A (Issue #200, ADR 0040): governance of statutory payroll packs. The browser shows what the
 * server returned and sends only what the contract lets it choose (a command, the expected version,
 * a confirmation, a bounded reason code, a future date). It never evaluates a rule, never shows a
 * rate, formula or reference value, and never keeps evidence in a URL, in browser storage, in a log
 * or in the console. Technical validation is not legal approval, and no wording here says
 * otherwise.
 */

export const PACK_STATES = [
  'DRAFT',
  'VALIDATED',
  'REVIEWED',
  'SCHEDULED',
  'ACTIVE',
  'RETIRED',
] as const;
export type PackState = (typeof PACK_STATES)[number];

export const COMMANDS = [
  'validate',
  'submit-review',
  'approve',
  'reject',
  'schedule',
  'activate',
  'retire',
] as const;
export type PackCommand = (typeof COMMANDS)[number];

/** The reason codes of a rejection and of a retirement (closed lists, ADR 0040 section 2). */
export const REJECT_REASONS = [
  'SOURCE_MISMATCH',
  'VALUE_ERROR',
  'EVIDENCE_INSUFFICIENT',
  'SCOPE_ERROR',
  'OTHER',
] as const;
export const RETIRE_REASONS = ['SUPERSEDED', 'WITHDRAWN', 'LEGAL_CHANGE', 'ERROR'] as const;
export const NOTE_MAX = 500;

/** The commands the lifecycle offers in each state; the server still decides every one. */
export function commandsFor(state: PackState, submitted: boolean): PackCommand[] {
  switch (state) {
    case 'DRAFT':
      return ['validate'];
    case 'VALIDATED':
      return submitted ? ['approve', 'reject'] : ['submit-review'];
    case 'REVIEWED':
      return ['schedule', 'activate', 'retire'];
    case 'SCHEDULED':
      return ['activate', 'retire'];
    case 'ACTIVE':
      return ['retire'];
    default:
      return [];
  }
}

/** Approval, rejection, scheduling, activation and retirement need an explicit confirmation. */
export function needsConfirmation(command: PackCommand): boolean {
  return command !== 'validate' && command !== 'submit-review';
}

export const PACK_CODES = [
  'STATUTORY_PACK_NOT_FOUND',
  'STATUTORY_PACK_VERSION_CONFLICT',
  'STATUTORY_PACK_TRANSITION_NOT_ALLOWED',
  'STATUTORY_PACK_VALIDATION_FAILED',
  'STATUTORY_PACK_EVIDENCE_INCOMPLETE',
  'STATUTORY_PACK_DIGEST_MISMATCH',
  'STATUTORY_PACK_SELF_APPROVAL_FORBIDDEN',
  'STATUTORY_PACK_REVIEW_REQUIRED',
  'STATUTORY_PACK_EFFECTIVE_PERIOD_CONFLICT',
  'STATUTORY_PACK_NO_ACTIVE_VERSION',
  'STATUTORY_PACK_ALREADY_ACTIVE',
] as const;
const PACK_CODE_SET: ReadonlySet<string> = new Set(PACK_CODES);
const PLATFORM_CODE_SET: ReadonlySet<string> = new Set([
  'RATE_LIMITED',
  'IDEMPOTENCY_KEY_REUSED',
  'LEGAL_ENTITY_NOT_FOUND',
  'CURSOR_INVALID',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'INTERNAL_ERROR',
]);
const STALE_CODES: ReadonlySet<string> = new Set([
  'STATUTORY_PACK_VERSION_CONFLICT',
  'STATUTORY_PACK_TRANSITION_NOT_ALLOWED',
  'STATUTORY_PACK_ALREADY_ACTIVE',
  'IDEMPOTENCY_KEY_REUSED',
]);
const NOT_FOUND_CODES: ReadonlySet<string> = new Set([
  'STATUTORY_PACK_NOT_FOUND',
  'LEGAL_ENTITY_NOT_FOUND',
]);

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function packFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && (PACK_CODE_SET.has(code) || PLATFORM_CODE_SET.has(code))) {
    if (code === 'RATE_LIMITED') {
      return { ...base, code, kind: 'rateLimited', messageKey: `errors.${code}` };
    }
    const kind = STALE_CODES.has(code)
      ? 'stale'
      : NOT_FOUND_CODES.has(code)
        ? 'notFound'
        : code === 'MFA_REQUIRED' || code === 'ACCESS_DENIED' || response.status === 403
          ? 'forbidden'
          : PACK_CODE_SET.has(code)
            ? 'domain'
            : 'general';
    return { ...base, code, kind, messageKey: `errors.${code}` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, code, kind: 'validation', messageKey: 'statutoryPacks.errors.validation' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'statutoryPacks.errors.forbidden' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** The tone of the lifecycle badge; the badge always carries the state as text as well. */
export const STATE_TONES = {
  DRAFT: 'warning',
  VALIDATED: 'info',
  REVIEWED: 'info',
  SCHEDULED: 'info',
  ACTIVE: 'success',
  RETIRED: 'neutral',
} as const;

/** The path of one version page: the code and the integer version only, never evidence. */
export function versionPath(packCode: string, version: number): string {
  return `/admin/statutory-packs/${encodeURIComponent(packCode)}/${version}`;
}

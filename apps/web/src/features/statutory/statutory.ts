import type {
  Problem,
  StatutoryRuleBracket,
  StatutoryRuleComponent,
  StatutorySource,
} from '@divalhr/api-client';
import type { PayrollFailure } from '../payrollRuns/payrollRuns';

/**
 * MVP-094B (Issue #179, ADR 0034): a statutory preview of a frozen payroll run. The browser only
 * chooses what the contract lets it choose (a month, a profile, an acknowledgement) and shows what
 * the server returned: it never calculates a rate, a base, a deduction, a total or a net amount.
 * Amounts, profiles and flags are restricted data: never in a URL, in browser storage, in a log or
 * in the console. The wording is "statutory preview", never a claim of legal compliance.
 */

/** The eleven stable Problem Details codes of this feature (D94B-10, ADR 0034 section 11). */
export const STATUTORY_CODES = [
  'STATUTORY_RULE_PACK_NOT_FOUND',
  'STATUTORY_RULE_PACK_NOT_VERIFIED',
  'STATUTORY_RULE_PACK_NOT_APPLICABLE',
  'STATUTORY_PROFILE_MISSING',
  'STATUTORY_PROFILE_UNSUPPORTED',
  'STATUTORY_CALCULATION_BLOCKED',
  'STATUTORY_PREVIEW_STALE',
  'STATUTORY_CALCULATION_NO_CHANGE',
  'STATUTORY_VERSION_CONFLICT',
  'STATUTORY_LIMIT_REACHED',
  'STATUTORY_CALCULATION_NOT_FOUND',
] as const;
const STATUTORY_CODE_SET: ReadonlySet<string> = new Set(STATUTORY_CODES);

/** Platform and neighbouring codes this feature can meet, each with a finished message. */
export const PLATFORM_CODES = [
  'RATE_LIMITED',
  'IDEMPOTENCY_KEY_REUSED',
  'LEGAL_ENTITY_NOT_FOUND',
  'PAYROLL_RUN_NOT_FOUND',
  'COMPENSATION_NOT_FOUND',
  'CURSOR_INVALID',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'INTERNAL_ERROR',
] as const;
const NOT_FOUND_CODES: ReadonlySet<string> = new Set([
  'LEGAL_ENTITY_NOT_FOUND',
  'PAYROLL_RUN_NOT_FOUND',
  'COMPENSATION_NOT_FOUND',
]);
const PLATFORM_CODE_SET: ReadonlySet<string> = new Set(PLATFORM_CODES);

export const RUN_BLOCKERS = [
  'SOURCE_RUN_NOT_CURRENT',
  'CURRENCY_NOT_SUPPORTED',
  'RULE_PACK_MISSING',
  'RULE_PACK_NOT_VERIFIED',
] as const;
export const LINE_BLOCKERS = [
  'PROFILE_MISSING',
  'PROFILE_UNSUPPORTED',
  'PROFILE_CHANGES_IN_MONTH',
  'UNMODELED_EARNINGS',
  'DEDUCTIONS_EXCEED_GROSS',
] as const;
/** The blockers an administrator resolves on the employee's statutory profile page. */
export const PROFILE_FIXABLE: ReadonlySet<string> = new Set([
  'PROFILE_MISSING',
  'PROFILE_UNSUPPORTED',
  'PROFILE_CHANGES_IN_MONTH',
]);
export const PROFILE_BLOCKERS = [
  'EFFECTIVE_DATE_OUT_OF_RANGE',
  'NO_CHANGE',
  'LIMIT_REACHED',
] as const;
export const CLASSIFICATIONS = ['STANDARD_RESIDENT', 'UNSUPPORTED'] as const;
export const EVIDENCE_CODES = [
  'EMPLOYMENT_CONTRACT',
  'PAYROLL_FILE_REVIEW',
  'EMPLOYER_DECLARATION',
  'PROFESSIONAL_REVIEW',
] as const;
export const PACK_STATUSES = ['DRAFT', 'VERIFIED', 'RETIRED'] as const;

/** Page sizes: rule packs (maximum), calculation versions and frozen lines. */
export const PACK_LIMIT = 100;
export const VERSION_LIMIT = 25;
export const LINES_LIMIT = 50;

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function statutoryFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && STATUTORY_CODE_SET.has(code)) {
    const kind =
      code === 'STATUTORY_PREVIEW_STALE' || code === 'STATUTORY_VERSION_CONFLICT'
        ? 'stale'
        : code === 'STATUTORY_RULE_PACK_NOT_FOUND' || code === 'STATUTORY_CALCULATION_NOT_FOUND'
          ? 'notFound'
          : 'domain';
    return { ...base, code, kind, messageKey: `errors.${code}` };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, code, kind: 'stale', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code && NOT_FOUND_CODES.has(code)) {
    return { ...base, code, kind: 'notFound', messageKey: `errors.${code}` };
  }
  if (code === 'RATE_LIMITED' || response.status === 429) {
    return { ...base, code, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, code, kind: 'validation', messageKey: 'statutory.validation' };
  }
  if (code === 'MFA_REQUIRED') {
    return { ...base, code, kind: 'forbidden', messageKey: 'errors.MFA_REQUIRED' };
  }
  if (code && PLATFORM_CODE_SET.has(code)) {
    return {
      ...base,
      code,
      kind: response.status === 403 ? 'forbidden' : 'general',
      messageKey: `errors.${code}`,
    };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'statutory.forbidden' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** A rate in basis points for display (1 bp = 0.01 %), formatted by the locale. */
export function formatBasisPoints(language: string, basisPoints: number): string {
  return new Intl.NumberFormat(language, {
    style: 'percent',
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(basisPoints / 10_000);
}

/** The official source a component cites, from the pack's own sources. */
export function sourceFor(
  sources: readonly StatutorySource[],
  key: string,
): StatutorySource | undefined {
  return sources.find((source) => source.key === key);
}

/** The pack rule behind a calculated component, by its id. */
export function ruleFor(
  rules: readonly StatutoryRuleComponent[],
  componentId: string,
): StatutoryRuleComponent | undefined {
  return rules.find((rule) => rule.id === componentId);
}

/** Bracket upper bounds are decimal strings of whole francs, or null for the open bracket. */
export function bracketBounds(brackets: readonly StatutoryRuleBracket[]): {
  from: string;
  to: string | null;
  rateBp: number;
}[] {
  let from = '0';
  return brackets.map((bracket) => {
    const row = { from, to: bracket.upToAnnual, rateBp: bracket.rateBp };
    from = bracket.upToAnnual ?? from;
    return row;
  });
}

export const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;
export const ISO_MONTH = /^\d{4}-(0[1-9]|1[0-2])$/u;

/** `YYYY-MM-DD` to `YYYY-MM-01` for a month, used only to label a month. */
export function monthOf(date: string): string {
  return date.slice(0, 7);
}

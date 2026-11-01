import type {
  PayrollApprovalBlocker,
  PayrollApprovalWarning,
  PayrollVoidBlocker,
  PayrollVoidReasonCode,
  Problem,
} from '@divalhr/api-client';
import type { PayrollFailure } from '../payrollRuns/payrollRuns';

/**
 * MVP-094C (Issue #181, ADR 0035): approved payroll packages and bilingual payslips. The browser
 * shows what the server returned and sends only what the contract lets it choose (three
 * confirmations, a controlled void reason, an acknowledgement): it never calculates a rate, a
 * deduction, a total or a net amount. Amounts, digests, void explanations and payslips are
 * restricted data: never in a URL, in browser storage, in a log or in the console. An approved
 * payroll is not a payment and a payslip is not proof of payment.
 */

/** The twelve stable Problem Details codes of this feature (D94C-11, ADR 0035 section 15). */
export const PACKAGE_CODES = [
  'PAYROLL_PACKAGE_NOT_FOUND',
  'PAYROLL_PACKAGE_BLOCKED',
  'PAYROLL_APPROVAL_PREVIEW_STALE',
  'PAYROLL_APPROVAL_NO_CHANGE',
  'PAYROLL_PACKAGE_VERSION_CONFLICT',
  'PAYROLL_PACKAGE_ALREADY_APPROVED',
  'PAYROLL_PACKAGE_VOIDED',
  'PAYROLL_VOID_PREVIEW_STALE',
  'PAYROLL_VOID_NOT_ALLOWED',
  'PAYSLIP_NOT_FOUND',
  'PAYSLIP_RENDER_FAILED',
  'PAYROLL_PACKAGE_LIMIT_REACHED',
] as const;
const PACKAGE_CODE_SET: ReadonlySet<string> = new Set(PACKAGE_CODES);
const STALE_CODES: ReadonlySet<string> = new Set([
  'PAYROLL_APPROVAL_PREVIEW_STALE',
  'PAYROLL_VOID_PREVIEW_STALE',
  'PAYROLL_PACKAGE_VERSION_CONFLICT',
  'PAYROLL_PACKAGE_ALREADY_APPROVED',
  'PAYROLL_APPROVAL_NO_CHANGE',
  'PAYROLL_PACKAGE_VOIDED',
  'IDEMPOTENCY_KEY_REUSED',
]);
const NOT_FOUND_CODES: ReadonlySet<string> = new Set([
  'PAYROLL_PACKAGE_NOT_FOUND',
  'PAYSLIP_NOT_FOUND',
  'LEGAL_ENTITY_NOT_FOUND',
  'PAYROLL_RUN_NOT_FOUND',
  'STATUTORY_CALCULATION_NOT_FOUND',
]);
/** Platform and neighbouring codes this feature can meet, each with a finished message. */
const PLATFORM_CODE_SET: ReadonlySet<string> = new Set([
  'RATE_LIMITED',
  'IDEMPOTENCY_KEY_REUSED',
  'LEGAL_ENTITY_NOT_FOUND',
  'PAYROLL_RUN_NOT_FOUND',
  'STATUTORY_CALCULATION_NOT_FOUND',
  'EMPLOYEE_LINK_REQUIRED',
  'LOCALE_NOT_SUPPORTED',
  'CURSOR_INVALID',
  'EMPLOYMENT_CHANGE_TIMEOUT',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'INTERNAL_ERROR',
]);

export const APPROVAL_BLOCKERS: readonly PayrollApprovalBlocker[] = [
  'SOURCE_RUN_NOT_CURRENT',
  'CALCULATION_NOT_CURRENT',
  'CALCULATION_INCOMPLETE',
  'RULE_PACK_NOT_VERIFIED',
  'POLICY_CHANGED',
  'PROFILE_CHANGED',
  'RULE_PACK_CHANGED',
  'SOURCE_CHANGED',
  'CURRENCY_NOT_SUPPORTED',
  'NO_PAYABLE_LINES',
  'ARITHMETIC_MISMATCH',
  'ALREADY_APPROVED',
  'SOURCE_ALREADY_VOIDED',
  'SOURCE_MISSING',
  'LIMIT_REACHED',
];
export const APPROVAL_WARNINGS: readonly PayrollApprovalWarning[] = [
  'SAME_PREPARER_AND_APPROVER',
  'SYNTHETIC_RULE_PACK',
  'EXCLUDED_LINES_PRESENT',
  'ZERO_NET_LINES_PRESENT',
];
export const VOID_BLOCKERS: readonly PayrollVoidBlocker[] = ['ALREADY_VOIDED', 'PAYMENT_EXISTS'];
export const VOID_REASONS: readonly PayrollVoidReasonCode[] = [
  'SOURCE_CORRECTION',
  'PROFILE_CORRECTION',
  'RULE_PACK_CORRECTION',
  'ADMINISTRATIVE_ERROR',
  'OTHER_CONTROLLED',
];
export const PACKAGE_STATUSES = ['APPROVED', 'VOIDED'] as const;

/** The explanation is required (and only then sent) for OTHER_CONTROLLED: 10 to 500 characters. */
export const EXPLANATION_MIN = 10;
export const EXPLANATION_MAX = 500;
export const PAGE_LIMIT = 25;
export const PAYSLIP_PAGE_LIMIT = 50;
export const LINES_PAGE = 100;

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function packageFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && (PACKAGE_CODE_SET.has(code) || PLATFORM_CODE_SET.has(code))) {
    if (code === 'RATE_LIMITED') {
      return { ...base, code, kind: 'rateLimited', messageKey: `errors.${code}` };
    }
    const kind = STALE_CODES.has(code)
      ? 'stale'
      : NOT_FOUND_CODES.has(code)
        ? 'notFound'
        : code === 'MFA_REQUIRED' ||
            code === 'ACCESS_DENIED' ||
            code === 'EMPLOYEE_LINK_REQUIRED' ||
            response.status === 403
          ? 'forbidden'
          : PACKAGE_CODE_SET.has(code)
            ? 'domain'
            : 'general';
    return { ...base, code, kind, messageKey: `errors.${code}` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, code, kind: 'validation', messageKey: 'payrollPackages.validation' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'payrollPackages.forbidden' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** The wording language of a document: the interface language when supported, otherwise English. */
export function documentLocale(language: string): 'en' | 'fr' {
  return language.toLowerCase().startsWith('fr') ? 'fr' : 'en';
}

/**
 * The local name of a downloaded payslip: `payslip-YYYY-MM-<8 hex of the payslip id>.pdf`, the
 * pattern of the server (never an employee name or number).
 */
export function payslipFileName(period: string, payslipId: string): string {
  return `payslip-${period}-${payslipId.replaceAll('-', '').slice(0, 8)}.pdf`;
}

/** Whether an OTHER_CONTROLLED explanation is acceptable once trimmed. */
export function explanationValid(text: string): boolean {
  const length = text.trim().length;
  return length >= EXPLANATION_MIN && length <= EXPLANATION_MAX;
}

/** Phrases the payroll package and payslip wording must never contain (D94C, ADR 0035 section 1). */
export const FORBIDDEN_PHRASES: Record<'en' | 'fr', readonly RegExp[]> = {
  en: [
    /\bpaid\b/iu,
    /payment completed/iu,
    /\bfiled\b/iu,
    /tax[- ]compliant/iu,
    /government[- ]approved/iu,
  ],
  fr: [
    /(?<!\p{L})payée?s?(?!\p{L})/iu,
    /paiement effectué/iu,
    /(?<!\p{L})déclarée?s?(?!\p{L})/iu,
    /conforme fiscalement/iu,
    /approuvée? par l.État/iu,
  ],
};

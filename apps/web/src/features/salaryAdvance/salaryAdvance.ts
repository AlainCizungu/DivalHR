import type {
  CompensationMoney,
  Problem,
  SalaryAdvanceReason,
  SalaryAdvanceRefusalReason,
  SalaryAdvanceRequestStatus,
} from '@divalhr/api-client';
import {
  parseAmount,
  NETWORK_FAILURE,
  type CompensationFailure,
} from '../compensation/compensation';

export { NETWORK_FAILURE, parseAmount };
export type { CompensationFailure as SalaryAdvanceFailure };

/** MVP-120A: the synthetic partner is the only one that exists (D120A-1); the server checks it. */
export const PARTNER_CODE = 'SYNTHETIC-TEST' as const;
export const CONSENT_VERSION = 'SA-CONSENT-1';
export const PAGE_LIMIT = 25;

export const REASONS: readonly SalaryAdvanceReason[] = [
  'NO_ENABLED_POLICY',
  'EMPLOYMENT_NOT_ACTIVE',
  'LEGAL_ENTITY_MISMATCH',
  'TENURE_TOO_SHORT',
  'PAYROLL_REFERENCE_MISSING',
  'CURRENCY_MISMATCH',
  'OPEN_LIMIT_REACHED',
  'WAITING_PERIOD',
  'AMOUNT_OUT_OF_RANGE',
  'ROLLING_LIMIT_REACHED',
  'MULTIPLE_ACTIVE_PLACEMENTS',
];
export const REFUSAL_REASONS: readonly SalaryAdvanceRefusalReason[] = [
  'POLICY_NO_LONGER_APPLIES',
  'EMPLOYMENT_ENDING',
  'PAYROLL_REFERENCE_INVALID',
  'OPEN_REQUEST_LIMIT',
  'EMPLOYER_NOT_PARTICIPATING',
  'OTHER_POLICY_REASON',
];
export const STATUSES: readonly SalaryAdvanceRequestStatus[] = [
  'SUBMITTED',
  'EMPLOYER_CONFIRMED',
  'EMPLOYER_REFUSED',
  'WITHDRAWN',
];
export const NOTE_MAX = 500;

/** A request the employee can still withdraw before any external handoff (there is none yet). */
export const withdrawable = (status: SalaryAdvanceRequestStatus) =>
  status === 'SUBMITTED' || status === 'EMPLOYER_CONFIRMED';

/** Whole-number field: digits only, within bounds; null when not valid. */
export function wholeNumber(raw: string, min: number, max: number): number | null {
  if (!/^(0|[1-9][0-9]{0,3})$/u.test(raw.trim())) return null;
  const value = Number(raw.trim());
  return value >= min && value <= max ? value : null;
}

export function moneyOf(
  raw: string,
  currency: CompensationMoney['currency'],
): CompensationMoney | null {
  const amount = parseAmount(raw, currency);
  return amount === null ? null : { currency, amount };
}

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function salaryAdvanceFailureOf(response: Response, error: unknown): CompensationFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (
    code === 'SALARY_ADVANCE_POLICY_PREVIEW_STALE' ||
    code === 'SALARY_ADVANCE_VERSION_CONFLICT' ||
    code === 'IDEMPOTENCY_KEY_REUSED'
  ) {
    return { ...base, code, kind: 'stale', messageKey: `errors.${code}` };
  }
  if (code === 'SALARY_ADVANCE_POLICY_NOT_FOUND' || code === 'SALARY_ADVANCE_REQUEST_NOT_FOUND') {
    return { ...base, code, kind: 'notFound', messageKey: `errors.${code}` };
  }
  if (code?.startsWith('SALARY_ADVANCE_')) {
    return { ...base, code, kind: 'domain', messageKey: `errors.${code}` };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, kind: 'validation', messageKey: 'salaryAdvance.validation' };
  }
  if (response.status === 403) {
    return {
      ...base,
      kind: 'forbidden',
      messageKey: code === 'MFA_REQUIRED' ? 'errors.MFA_REQUIRED' : 'salaryAdvance.forbidden',
    };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

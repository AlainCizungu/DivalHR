import type { Problem } from '@divalhr/api-client';
import type { TFunction } from 'i18next';

/**
 * MVP-094A (Issue #177, ADR 0033): monthly payroll runs and contractual gross. The browser only
 * chooses a closed month, a currency and a policy, and shows what the server previewed or froze:
 * it never calculates a gross amount, an eligibility or a total. Amounts are restricted financial
 * data: they are never put in a URL, in browser storage, in a log or in the console.
 */

export const PAYROLL_CURRENCIES = ['CDF', 'USD'] as const;
export const PRORATION_METHODS = ['CALENDAR_DAYS', 'FIXED_30_DAYS', 'NONE'] as const;
export const PAY_DAYS: readonly string[] = [
  ...Array.from({ length: 28 }, (_, index) => String(index + 1)),
  'LAST_DAY',
];
export const EMPLOYEE_BLOCKERS = [
  'COMPENSATION_MISSING',
  'CURRENCY_MISMATCH',
  'FOREIGN_PLACEMENT',
  'OVERLAPPING_EVIDENCE',
  'MULTIPLE_MONTHLY_AMOUNTS',
  'PARTIAL_MONTH_NOT_ALLOWED',
] as const;
export const EXPLANATION_CODES = [
  'FULL_MONTH',
  'PRORATED_CALENDAR_DAYS',
  'PRORATED_FIXED_30_DAYS',
  'CAPPED_AT_FULL_MONTH',
  'STARTED_IN_MONTH',
  'ENDED_IN_MONTH',
  'MULTIPLE_PERIODS',
] as const;
export const POLICY_BLOCKERS = ['NO_CHANGE', 'LIMIT_REACHED'] as const;
export const SOURCE_KINDS = ['EMPLOYMENT', 'PLACEMENT', 'COMPENSATION'] as const;
export const RUN_STATUSES = ['DRAFT', 'SUPERSEDED'] as const;

/** The eight stable Problem Details codes of this feature (D94A-10). */
export const PAYROLL_CODES = [
  'PAYROLL_POLICY_NOT_FOUND',
  'PAYROLL_POLICY_CONFLICT',
  'PAYROLL_PERIOD_INVALID',
  'PAYROLL_RUN_NOT_FOUND',
  'PAYROLL_RUN_BLOCKED',
  'PAYROLL_RUN_PREVIEW_STALE',
  'PAYROLL_RUN_VERSION_CONFLICT',
  'PAYROLL_RUN_LIMIT_REACHED',
] as const;
const PAYROLL_CODE_SET: ReadonlySet<string> = new Set(PAYROLL_CODES);

/** Platform codes this feature can meet, each with a finished message under `errors.*`. */
export const PLATFORM_CODES = [
  'RATE_LIMITED',
  'IDEMPOTENCY_KEY_REUSED',
  'EMPLOYMENT_CHANGE_TIMEOUT',
  'LEGAL_ENTITY_NOT_FOUND',
  'CURSOR_INVALID',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'INTERNAL_ERROR',
] as const;
const PLATFORM_CODE_SET: ReadonlySet<string> = new Set(PLATFORM_CODES);

/** The run list page size (default 25) and the detail lines page size (default 50). */
export const LIST_LIMIT = 25;
export const LINES_LIMIT = 50;
/** The closed months a run may be created for (D94A-6). */
export const CLOSED_MONTHS = 24;

export interface PayrollFailure {
  kind: 'forbidden' | 'rateLimited' | 'validation' | 'notFound' | 'stale' | 'domain' | 'general';
  messageKey: string;
  code?: string;
  correlationId?: string;
  retryAfter?: number;
}

export const NETWORK_FAILURE: PayrollFailure = { kind: 'general', messageKey: 'errors.network' };

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function payrollFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && PAYROLL_CODE_SET.has(code)) {
    const kind =
      code === 'PAYROLL_RUN_PREVIEW_STALE' ||
      code === 'PAYROLL_RUN_VERSION_CONFLICT' ||
      code === 'PAYROLL_POLICY_CONFLICT'
        ? 'stale'
        : code === 'PAYROLL_RUN_NOT_FOUND' || code === 'PAYROLL_POLICY_NOT_FOUND'
          ? 'notFound'
          : 'domain';
    return { ...base, code, kind, messageKey: `errors.${code}` };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, code, kind: 'stale', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'LEGAL_ENTITY_NOT_FOUND') {
    return { ...base, code, kind: 'notFound', messageKey: 'errors.LEGAL_ENTITY_NOT_FOUND' };
  }
  if (code === 'RATE_LIMITED' || response.status === 429) {
    return { ...base, code, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, code, kind: 'validation', messageKey: 'payrollRuns.validation' };
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
    return { ...base, kind: 'forbidden', messageKey: 'payrollRuns.forbidden' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** `YYYY-MM-DD` to a UTC day number (no time zone drift). */
function dayNumber(date: string): number {
  return Math.floor(new Date(`${date}T00:00:00Z`).getTime() / 86_400_000);
}

/**
 * The closed months a run can be created for, newest first: the 24 calendar months before the
 * month of the business date (the month of the business date itself is never closed).
 */
export function closedMonths(businessDate: string): string[] {
  const year = Number(businessDate.slice(0, 4));
  const month = Number(businessDate.slice(5, 7));
  const months: string[] = [];
  for (let back = 1; back <= CLOSED_MONTHS; back++) {
    const index = year * 12 + (month - 1) - back;
    const y = Math.floor(index / 12);
    const m = (index % 12) + 1;
    months.push(`${String(y)}-${String(m).padStart(2, '0')}`);
  }
  return months;
}

/** The month name for display, e.g. "September 2026" (UTC so that no zone moves it). */
export function formatMonth(language: string, period: string): string {
  return new Intl.DateTimeFormat(language, {
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(`${period}-01T00:00:00Z`));
}

export function formatDay(language: string, date: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(`${date}T00:00:00Z`),
  );
}

/** The inclusive number of days between two dates, used only to label an interval. */
export function spanDays(from: string, to: string): number {
  return dayNumber(to) - dayNumber(from) + 1;
}

export const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;

export interface PolicyForm {
  effectiveFrom: string;
  payDay: string;
  prorationMethod: (typeof PRORATION_METHODS)[number];
}

/** The `payDay` object of the contract for a select value. */
export function payDayOf(value: string): { kind: 'DAY' | 'LAST_DAY'; day: number | null } {
  return value === 'LAST_DAY'
    ? { kind: 'LAST_DAY', day: null }
    : { kind: 'DAY', day: Number(value) };
}

/** The pay day for display. */
export function payDayText(
  t: TFunction,
  payDay: { kind: 'DAY' | 'LAST_DAY'; day: number | null },
): string {
  return payDay.kind === 'LAST_DAY'
    ? t('payrollRuns.payDay.LAST_DAY')
    : t('payrollRuns.payDay.DAY', { day: payDay.day });
}

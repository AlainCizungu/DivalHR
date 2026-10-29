import type {
  CompensationAction,
  CompensationBlocker,
  CompensationCurrency,
  CompensationMoney,
  CompensationReasonCode,
  CompensationWarning,
  Problem,
} from '@divalhr/api-client';
import type { TFunction } from 'i18next';

/** MVP-093A limits (D93-4, D93-7); the server checks everything again. */
export const EXPLANATION_MIN = 10;
export const EXPLANATION_MAX = 500;

export const CURRENCIES: readonly CompensationCurrency[] = ['CDF', 'USD'];
export const REASON_CODES: readonly CompensationReasonCode[] = [
  'DATA_ENTRY_ERROR',
  'WRONG_EFFECTIVE_DATE',
  'WRONG_CURRENCY',
  'OTHER_CORRECTION',
];
export const ACTIONS: readonly CompensationAction[] = [
  'ESTABLISHED',
  'CHANGED',
  'CORRECTED',
  'SCHEDULED_CHANGE_CANCELLED',
];
export const BLOCKERS: readonly CompensationBlocker[] = [
  'AMOUNT_OUT_OF_RANGE',
  'DATE_OUT_OF_RANGE',
  'EMPLOYMENT_CONFLICT',
  'TRANSITION_NOT_ALLOWED',
  'NO_CHANGE',
  'NOT_CANCELLABLE',
  'LIMIT_REACHED',
];
export const WARNINGS: readonly CompensationWarning[] = ['RETROACTIVE', 'LATER_CHANGE_SCHEDULED'];

/** The stable Problem Details codes of this feature (D93-9). */
export const COMPENSATION_CODES = [
  'COMPENSATION_NOT_FOUND',
  'COMPENSATION_INVALID_AMOUNT',
  'COMPENSATION_DATE_OUT_OF_RANGE',
  'COMPENSATION_EMPLOYMENT_CONFLICT',
  'COMPENSATION_PREVIEW_STALE',
  'COMPENSATION_VERSION_CONFLICT',
  'COMPENSATION_TRANSITION_NOT_ALLOWED',
  'COMPENSATION_LIMIT_REACHED',
] as const;
const CODE_SET: ReadonlySet<string> = new Set(COMPENSATION_CODES);

/**
 * The amount grammar of one currency, as a decimal string, and never through a JSON or JS number:
 * CDF is whole francs only (1 to 1,000,000,000); USD has one or two decimals (0.01 to 100,000.00)
 * and is canonicalised to two. A dot is the decimal separator; a single comma is accepted as the
 * separator too (French input) and sent as a dot. No sign, exponent, grouping separator, leading
 * zero or excess scale is accepted. Returns the canonical text, or null.
 */
export function parseAmount(raw: string, currency: CompensationCurrency): string | null {
  const text = raw.trim().replace(',', '.');
  if (currency === 'CDF') {
    if (!/^[1-9][0-9]{0,9}$/u.test(text)) return null;
    // At most ten digits: exactly representable, and compared as digits, not as money.
    if (text.length === 10 && text > '1000000000') return null;
    return text;
  }
  const match = /^(0|[1-9][0-9]{0,5})(?:\.([0-9]{1,2}))?$/u.exec(text);
  if (!match) return null;
  const whole = match[1] ?? '0';
  const fraction = (match[2] ?? '').padEnd(2, '0');
  if (whole === '0' && fraction === '00') return null;
  if (whole.length === 6 && (whole > '100000' || (whole === '100000' && fraction !== '00'))) {
    return null;
  }
  return `${whole}.${fraction}`;
}

/**
 * The amount for display: the locale's currency format with the ISO code, never a symbol, never
 * converted or combined. The canonical text has at most ten digits, so it is exact as a number.
 */
export function formatMoney(language: string, money: CompensationMoney): string {
  const digits = money.currency === 'CDF' ? 0 : 2;
  return new Intl.NumberFormat(language, {
    style: 'currency',
    currency: money.currency,
    currencyDisplay: 'code',
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Number(money.amount));
}

export function formatDate(language: string, date: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'long', timeZone: 'UTC' }).format(
    new Date(`${date}T00:00:00Z`),
  );
}

export function formatInstant(language: string, instant: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

/** The active interval's period: "from D" while open, "D1 – D2" once closed. */
export function formatPeriod(
  t: TFunction,
  language: string,
  from: string,
  to: string | null,
): string {
  return to
    ? t('compensation.period.closed', {
        from: formatDate(language, from),
        to: formatDate(language, to),
      })
    : t('compensation.period.open', { from: formatDate(language, from) });
}

export type CompensationField =
  'effectiveFrom' | 'currency' | 'amount' | 'reasonCode' | 'explanation';

export interface CompensationForm {
  effectiveFrom: string;
  currency: CompensationCurrency;
  amount: string;
  reasonCode: CompensationReasonCode | '';
  explanation: string;
}

export const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;

/** What the browser can already tell is wrong; the server owns the real rules. */
export function problemsOf(
  form: CompensationForm,
  action: 'ESTABLISHED' | 'CHANGED' | 'CORRECTED',
  textOk: (text: string) => boolean,
): Set<CompensationField> {
  const problems = new Set<CompensationField>();
  if (!ISO_DATE.test(form.effectiveFrom)) problems.add('effectiveFrom');
  if (parseAmount(form.amount, form.currency) === null) problems.add('amount');
  if (action === 'CORRECTED') {
    if (!form.reasonCode) problems.add('reasonCode');
    if (!textOk(form.explanation)) problems.add('explanation');
  }
  return problems;
}

/** A refused or failed compensation request, reduced to a stable message key. */
export interface CompensationFailure {
  kind:
    | 'forbidden'
    | 'linkRequired'
    | 'rateLimited'
    | 'validation'
    | 'notFound'
    | 'stale'
    | 'domain'
    | 'general';
  messageKey: string;
  code?: string;
  correlationId?: string;
  retryAfter?: number;
}

export const NETWORK_FAILURE: CompensationFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function compensationFailureOf(response: Response, error: unknown): CompensationFailure {
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
  if (code === 'COMPENSATION_PREVIEW_STALE' || code === 'COMPENSATION_VERSION_CONFLICT') {
    return { ...base, code, kind: 'stale', messageKey: `errors.${code}` };
  }
  if (code === 'COMPENSATION_NOT_FOUND') {
    return { ...base, code, kind: 'notFound', messageKey: `errors.${code}` };
  }
  if (code && CODE_SET.has(code)) {
    return { ...base, code, kind: 'domain', messageKey: `errors.${code}` };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, code, kind: 'stale', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, kind: 'validation', messageKey: 'compensation.validation' };
  }
  if (response.status === 403) {
    return {
      ...base,
      kind: 'forbidden',
      messageKey: code === 'MFA_REQUIRED' ? 'errors.MFA_REQUIRED' : 'compensation.forbidden',
    };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

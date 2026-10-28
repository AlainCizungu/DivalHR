import type { ExpenseCategory, ExpenseCurrency, ExpenseState, Problem } from '@divalhr/api-client';

/** MVP-081 limits (D81-2 to D81-5); the server checks everything again. */
export const MERCHANT_MAX = 120;
export const DESCRIPTION_MIN = 10;
export const DESCRIPTION_MAX = 1000;
export const REASON_MIN = 10;
export const REASON_MAX = 500;
export const FILE_NAME_MAX = 120;
export const RECEIPT_MAX_BYTES = 2 * 1024 * 1024;
export const WINDOW_DAYS = 90;

export const CURRENCIES: readonly ExpenseCurrency[] = ['CDF', 'USD'];
export const CATEGORIES: readonly ExpenseCategory[] = [
  'TRANSPORT',
  'MEALS',
  'LODGING',
  'SUPPLIES',
  'COMMUNICATION',
  'TRAINING',
  'MEDICAL_WORK_RELATED',
  'OTHER',
];
export const STATES: readonly ExpenseState[] = [
  'DRAFT',
  'SUBMITTED',
  'RETURNED',
  'APPROVED',
  'WITHDRAWN',
];
export const RECEIPT_TYPES = ['application/pdf', 'image/jpeg', 'image/png'] as const;

/** NFC and trimmed: the text the server stores and the browser sends. */
export function normalizeText(raw: string): string {
  return raw.normalize('NFC').trim();
}

/** Unicode code points, as the server counts characters. */
export function codePoints(text: string): number {
  // eslint-disable-next-line @typescript-eslint/no-misused-spread
  return [...text].length;
}

// What the browser can already tell is refused; the server owns the real grammar.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/u;
const FORBIDDEN = /[<>`\\]|https?:|www\.|:\/\/|%[0-9a-f]{2}|&#|&[a-z][a-z0-9]{1,31};/iu;

export function textAcceptable(text: string, min: number, max: number): boolean {
  const length = codePoints(text);
  return length >= min && length <= max && !FORBIDDEN.test(text) && !CONTROL.test(text);
}

/**
 * The amount grammar of one currency, as a decimal string: CDF is whole francs (1 to 100,000,000),
 * USD has at most two decimals (0.01 to 100,000.00). A comma is accepted as the decimal separator
 * of a French keyboard and sent with a dot. The two currencies are never converted.
 *
 * @returns the canonical decimal string to send, or null when it is not acceptable
 */
export function canonicalAmount(raw: string, currency: ExpenseCurrency): string | null {
  const text = raw.trim().replace(',', '.');
  if (currency === 'CDF') {
    if (!/^[0-9]{1,9}$/u.test(text)) return null;
    const value = Number(text);
    return value >= 1 && value <= 100_000_000 ? String(value) : null;
  }
  const match = /^([0-9]{1,6})(?:\.([0-9]{1,2}))?$/u.exec(text);
  if (!match) return null;
  const cents = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  if (cents < 1 || cents > 10_000_000) return null;
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

/** The amount for display: always with its currency code, never converted or combined. */
export function formatAmount(language: string, currency: ExpenseCurrency, amount: string): string {
  const digits = currency === 'CDF' ? 0 : 2;
  const number = new Intl.NumberFormat(language, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(Number(amount));
  return `${number} ${currency}`;
}

export function formatDate(language: string, date: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(`${date}T00:00:00Z`),
  );
}

export function formatInstant(language: string, instant: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

/** Today's date in the browser's zone as `YYYY-MM-DD` (the server decides with the organization's zone). */
export function isoDate(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

export function earliestDate(today: Date): string {
  const copy = new Date(today);
  copy.setDate(copy.getDate() - WINDOW_DAYS);
  return isoDate(copy);
}

export type ExpenseField =
  'businessDate' | 'currency' | 'amount' | 'category' | 'merchant' | 'description';
export const EXPENSE_FIELDS: readonly ExpenseField[] = [
  'businessDate',
  'currency',
  'amount',
  'category',
  'merchant',
  'description',
];

export interface ExpenseForm {
  businessDate: string;
  currency: ExpenseCurrency;
  amount: string;
  category: ExpenseCategory | '';
  merchant: string;
  description: string;
}

export function emptyForm(today: Date): ExpenseForm {
  return {
    businessDate: isoDate(today),
    currency: 'USD',
    amount: '',
    category: '',
    merchant: '',
    description: '',
  };
}

export function problemsOf(form: ExpenseForm, today: Date): Set<ExpenseField> {
  const problems = new Set<ExpenseField>();
  const date = form.businessDate;
  if (!/^\d{4}-\d{2}-\d{2}$/u.test(date) || date > isoDate(today) || date < earliestDate(today)) {
    problems.add('businessDate');
  }
  if (canonicalAmount(form.amount, form.currency) === null) problems.add('amount');
  if (!form.category) problems.add('category');
  if (!textAcceptable(normalizeText(form.merchant), 1, MERCHANT_MAX)) problems.add('merchant');
  if (!textAcceptable(normalizeText(form.description), DESCRIPTION_MIN, DESCRIPTION_MAX)) {
    problems.add('description');
  }
  return problems;
}

/** The request body of a valid form. */
export function bodyOf(form: ExpenseForm) {
  return {
    businessDate: form.businessDate,
    currency: form.currency,
    amount: canonicalAmount(form.amount, form.currency) ?? '',
    category: form.category as ExpenseCategory,
    merchant: normalizeText(form.merchant),
    description: normalizeText(form.description),
  };
}

export type ReceiptProblem = 'type' | 'size' | 'name' | 'empty';

/** What the browser can tell about a chosen file; the server verifies the bytes. */
export function receiptProblemOf(file: {
  size: number;
  type: string;
  name: string;
}): ReceiptProblem | null {
  if (file.size < 1) return 'empty';
  if (file.size > RECEIPT_MAX_BYTES) return 'size';
  if (!(RECEIPT_TYPES as readonly string[]).includes(file.type)) return 'type';
  const name = normalizeText(file.name);
  if (
    codePoints(name) < 1 ||
    codePoints(name) > FILE_NAME_MAX ||
    /[<>\\/:"|?*]/u.test(name) ||
    CONTROL.test(name)
  ) {
    return 'name';
  }
  return null;
}

/** A refused or failed expense command, reduced to a stable message key and allow-listed details. */
export interface ExpenseFailure {
  kind:
    | 'forbidden'
    | 'linkRequired'
    | 'rateLimited'
    | 'validation'
    | 'notFound'
    | 'route'
    | 'conflict'
    | 'settled'
    | 'limit'
    | 'receipt'
    | 'general';
  messageKey: string;
  fields?: string[];
  correlationId?: string;
  retryAfter?: number;
}

export const NETWORK_FAILURE: ExpenseFailure = { kind: 'general', messageKey: 'errors.network' };

const FIELD_NAMES = new Set<string>([...EXPENSE_FIELDS, 'reason']);

const RECEIPT_CODES = new Set([
  'EXPENSE_RECEIPT_INVALID',
  'EXPENSE_RECEIPT_TOO_LARGE',
  'EXPENSE_RECEIPT_UPLOAD_TIMEOUT',
  'EXPENSE_RECEIPT_MEDIA_UNSUPPORTED',
]);

/** Maps a Problem response; params outside the allow-list are dropped, never shown. */
export function expenseFailureOf(response: Response, error: unknown): ExpenseFailure {
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
  if (response.status === 403)
    return { ...base, kind: 'forbidden', messageKey: 'expenses.forbidden' };
  if (response.status === 429)
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  if (code === 'EXPENSE_NOT_FOUND')
    return { ...base, kind: 'notFound', messageKey: 'errors.EXPENSE_NOT_FOUND' };
  if (code === 'EXPENSE_APPROVAL_ROUTE_UNAVAILABLE') {
    return { ...base, kind: 'route', messageKey: 'errors.EXPENSE_APPROVAL_ROUTE_UNAVAILABLE' };
  }
  if (code === 'EXPENSE_TRANSITION_NOT_ALLOWED') {
    return { ...base, kind: 'settled', messageKey: 'errors.EXPENSE_TRANSITION_NOT_ALLOWED' };
  }
  if (code === 'EXPENSE_VERSION_CONFLICT') {
    return { ...base, kind: 'conflict', messageKey: 'errors.EXPENSE_VERSION_CONFLICT' };
  }
  if (code === 'EXPENSE_LIMIT_REACHED') {
    return { ...base, kind: 'limit', messageKey: 'errors.EXPENSE_LIMIT_REACHED' };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code && RECEIPT_CODES.has(code)) {
    return { ...base, kind: 'receipt', messageKey: `errors.${code}` };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw: unknown = problem?.params?.fields;
    const fields = new Set<string>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && FIELD_NAMES.has(name)) fields.add(name);
      }
    }
    return { ...base, kind: 'validation', messageKey: 'expenses.validation', fields: [...fields] };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

export function stateTone(state: ExpenseState): 'info' | 'success' | 'warning' | 'neutral' {
  if (state === 'APPROVED') return 'success';
  if (state === 'RETURNED') return 'warning';
  if (state === 'SUBMITTED') return 'info';
  return 'neutral';
}

/** The extension for a download name, from the verified media type. */
export function downloadNameOf(mediaType: string): string {
  if (mediaType === 'application/pdf') return 'receipt.pdf';
  if (mediaType === 'image/jpeg') return 'receipt.jpg';
  return 'receipt.png';
}

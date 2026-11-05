import type {
  PaymentBatchBlocker,
  PaymentBatchCancelReasonCode,
  PaymentBatchWarning,
  PaymentDestinationReasonCode,
  PaymentMethod,
  PaymentReconciliationBlocker,
  PaymentReconciliationOutcome,
  PaymentReconciliationState,
  PaymentSubmissionBlocker,
  PaymentSubmissionOutcome,
  PaymentSubmissionState,
  Problem,
} from '@divalhr/api-client';
import type { PayrollFailure } from '../payrollRuns/payrollRuns';

/**
 * MVP-095A (Issue #183, ADR 0036): payment destinations and payment batches. The browser shows what
 * the server returned (masked values only) and sends what the contract lets it choose. It never
 * calculates an amount, never keeps an account number, a phone number or a holder name after the
 * command is sent, and never stores any of them in a URL, in browser storage, in a log or in the
 * console. A prepared batch is a payment instruction register: nothing is sent and no money moves.
 */

/** The stable Problem Details codes of this feature (D95A-10, D95B-9). */
export const PAYMENT_CODES = [
  'PAYMENT_DESTINATION_NOT_FOUND',
  'PAYMENT_DESTINATION_INVALID',
  'PAYMENT_DESTINATION_VERSION_CONFLICT',
  'PAYMENT_DESTINATION_PREVIEW_STALE',
  'PAYMENT_DESTINATION_KEY_UNAVAILABLE',
  'PAYMENT_BATCH_NOT_FOUND',
  'PAYMENT_BATCH_BLOCKED',
  'PAYMENT_BATCH_PREVIEW_STALE',
  'PAYMENT_BATCH_VERSION_CONFLICT',
  'PAYMENT_BATCH_ALREADY_PREPARED',
  'PAYMENT_BATCH_CANCELLED',
  'PAYMENT_BATCH_CANCEL_NOT_ALLOWED',
  'PAYMENT_BATCH_LIMIT_REACHED',
  // MVP-095B (Issue #187, ADR 0037): provider submission.
  'PAYMENT_BATCH_ALREADY_SUBMITTED',
  'PAYMENT_SUBMISSION_NOT_FOUND',
  'PAYMENT_SUBMISSION_BLOCKED',
  'PAYMENT_SUBMISSION_PREVIEW_STALE',
  'PAYMENT_SUBMISSION_VERSION_CONFLICT',
  'PAYMENT_SUBMISSION_EXISTS',
  'PAYMENT_PROVIDER_UNAVAILABLE',
  'PAYMENT_SUBMISSION_KEY_UNAVAILABLE',
  // MVP-095C (Issue #190, ADR 0038): reconciliation of uncertain submissions.
  'PAYMENT_RECONCILIATION_NOT_FOUND',
  'PAYMENT_RECONCILIATION_NOT_ELIGIBLE',
  'PAYMENT_RECONCILIATION_EXISTS',
  'PAYMENT_RECONCILIATION_PREVIEW_STALE',
] as const;
const PAYMENT_CODE_SET: ReadonlySet<string> = new Set(PAYMENT_CODES);
const STALE_CODES: ReadonlySet<string> = new Set([
  'PAYMENT_DESTINATION_VERSION_CONFLICT',
  'PAYMENT_DESTINATION_PREVIEW_STALE',
  'PAYMENT_BATCH_PREVIEW_STALE',
  'PAYMENT_BATCH_VERSION_CONFLICT',
  'PAYMENT_BATCH_ALREADY_PREPARED',
  'PAYMENT_BATCH_CANCELLED',
  'PAYMENT_BATCH_ALREADY_SUBMITTED',
  'PAYMENT_SUBMISSION_PREVIEW_STALE',
  'PAYMENT_SUBMISSION_VERSION_CONFLICT',
  'PAYMENT_SUBMISSION_EXISTS',
  'PAYMENT_RECONCILIATION_NOT_ELIGIBLE',
  'PAYMENT_RECONCILIATION_EXISTS',
  'PAYMENT_RECONCILIATION_PREVIEW_STALE',
  'IDEMPOTENCY_KEY_REUSED',
]);
const NOT_FOUND_CODES: ReadonlySet<string> = new Set([
  'PAYMENT_DESTINATION_NOT_FOUND',
  'PAYMENT_BATCH_NOT_FOUND',
  'PAYMENT_SUBMISSION_NOT_FOUND',
  'PAYMENT_RECONCILIATION_NOT_FOUND',
  'PAYROLL_PACKAGE_NOT_FOUND',
  'LEGAL_ENTITY_NOT_FOUND',
]);
/** Platform and neighbouring codes this feature can meet, each with a finished message. */
const PLATFORM_CODE_SET: ReadonlySet<string> = new Set([
  'RATE_LIMITED',
  'IDEMPOTENCY_KEY_REUSED',
  'PAYROLL_PACKAGE_NOT_FOUND',
  'LEGAL_ENTITY_NOT_FOUND',
  'EMPLOYEE_LINK_REQUIRED',
  'CURSOR_INVALID',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'INTERNAL_ERROR',
]);

export const METHODS: readonly PaymentMethod[] = ['BANK_ACCOUNT', 'MOBILE_MONEY', 'MANUAL'];
export const DESTINATION_REASONS: readonly PaymentDestinationReasonCode[] = [
  'INITIAL_SETUP',
  'EMPLOYEE_REQUEST',
  'PROVIDER_CHANGE',
  'ACCOUNT_CORRECTION',
  'OTHER_CONTROLLED',
];
export const CANCEL_REASONS: readonly PaymentBatchCancelReasonCode[] = [
  'DESTINATION_CORRECTION',
  'PACKAGE_CORRECTION',
  'PREPARED_IN_ERROR',
  'OTHER_CONTROLLED',
];
export const BATCH_BLOCKERS: readonly PaymentBatchBlocker[] = [
  'PACKAGE_VOIDED',
  'PACKAGE_NOT_LATEST',
  'BATCH_ALREADY_PREPARED',
  'NO_PAYABLE_LINES',
  'MISSING_DESTINATION',
  'DESTINATION_INVALID',
  'INTEGRITY_FAILURE',
  'LIMIT_REACHED',
];
export const BATCH_WARNINGS: readonly PaymentBatchWarning[] = [
  'DESTINATION_CHANGED_AFTER_APPROVAL',
  'DUPLICATE_DESTINATION',
  'MANUAL_LINES_PRESENT',
  'ZERO_NET_EXCLUDED',
];

/** Controlled providers by method (codes of the server's catalogue; names are proper nouns). */
export const PROVIDERS: Record<'BANK_ACCOUNT' | 'MOBILE_MONEY', readonly Provider[]> = {
  BANK_ACCOUNT: [
    { code: 'RAWBANK', name: 'Rawbank' },
    { code: 'EQUITY_BCDC', name: 'Equity BCDC' },
    { code: 'TMB', name: 'Trust Merchant Bank' },
    { code: 'ECOBANK_CD', name: 'Ecobank RDC' },
    { code: 'STANDARD_BANK_CD', name: 'Standard Bank RDC' },
    { code: 'ACCESS_BANK_CD', name: 'Access Bank RDC' },
    { code: 'OTHER_BANK', name: '' },
  ],
  MOBILE_MONEY: [
    { code: 'MPESA', name: 'M-Pesa' },
    { code: 'AIRTEL_MONEY', name: 'Airtel Money' },
    { code: 'ORANGE_MONEY', name: 'Orange Money' },
    { code: 'AFRIMONEY', name: 'Afrimoney' },
    { code: 'OTHER_MOBILE_MONEY', name: '' },
  ],
};
export interface Provider {
  code: string;
  /** An empty name means the label comes from the translation (the "other" providers). */
  name: string;
}

export const EXPLANATION_MIN = 10;
export const EXPLANATION_MAX = 500;
export const PAGE_LIMIT = 25;
export const LINES_PAGE = 100;

const ACCOUNT = /^[A-Z0-9]{6,34}$/u;
const PHONE = /^\+[1-9][0-9]{7,14}$/u;
const BRANCH = /^[A-Za-z0-9 -]{1,20}$/u;

/** Account numbers are entered with spaces or hyphens and sent upper-case without them. */
export function normalizeAccount(raw: string): string {
  return raw.replaceAll(/[\s-]/gu, '').toUpperCase();
}

export function accountValid(raw: string): boolean {
  return ACCOUNT.test(normalizeAccount(raw));
}

export function phoneValid(raw: string): boolean {
  return PHONE.test(raw.trim());
}

export function branchValid(raw: string): boolean {
  return raw.trim() === '' || BRANCH.test(raw.trim());
}

export function holderValid(raw: string): boolean {
  const length = raw.trim().length;
  return length >= 2 && length <= 120;
}

export function explanationValid(text: string): boolean {
  const length = text.trim().length;
  return length >= EXPLANATION_MIN && length <= EXPLANATION_MAX;
}

/**
 * Formats integer minor units for display without floating point: CDF has no minor unit, USD has
 * two decimals. The browser only places the decimal point; the server owns every amount.
 */
export function minorToAmount(minor: number, currency: string): string {
  const negative = minor < 0;
  const digits = String(Math.abs(minor));
  const exponent = currency === 'USD' ? 2 : 0;
  const padded = digits.padStart(exponent + 1, '0');
  const whole = padded.slice(0, padded.length - exponent);
  const fraction = exponent === 0 ? '' : '.' + padded.slice(padded.length - exponent);
  return (negative ? '-' : '') + whole + fraction;
}

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function paymentFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && (PAYMENT_CODE_SET.has(code) || PLATFORM_CODE_SET.has(code))) {
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
          : PAYMENT_CODE_SET.has(code)
            ? 'domain'
            : 'general';
    return { ...base, code, kind, messageKey: `errors.${code}` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, code, kind: 'validation', messageKey: 'payments.validation' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'payments.forbidden' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** Phrases the payment wording must never contain (nothing was sent, nothing was paid). */
export const FORBIDDEN_PHRASES: Record<'en' | 'fr', readonly RegExp[]> = {
  en: [
    /payment sent/iu,
    /\bdisbursed\b/iu,
    /\bpaid\b/iu,
    /payment completed/iu,
    /bank[- ]verified/iu,
    /\bverified account\b/iu,
  ],
  fr: [
    /paiement envoyé/iu,
    /(?<!\p{L})décaissée?s?(?!\p{L})/iu,
    /(?<!\p{L})payée?s?(?!\p{L})/iu,
    /paiement effectué/iu,
    /vérifié par la banque/iu,
    /compte vérifié/iu,
  ],
};

/** Closed lists of provider submission (MVP-095B). The states and outcomes come from the server. */
export const SUBMISSION_STATES: readonly PaymentSubmissionState[] = [
  'QUEUED',
  'SENDING',
  'ACCEPTED_FOR_PROCESSING',
  'REJECTED',
  'DELIVERY_UNKNOWN',
  'MANUAL_INTERVENTION',
];
export const SUBMISSION_OUTCOMES: readonly PaymentSubmissionOutcome[] = [
  'ACCEPTED',
  'REJECTED_INVALID_REQUEST',
  'REJECTED_SIGNATURE',
  'REJECTED_UNSUPPORTED',
  'REJECTED_DUPLICATE_MISMATCH',
  'REJECTED_LIMIT',
  'REJECTED_OTHER',
  'TRANSIENT_CONNECT',
  'TRANSIENT_TLS',
  'TRANSIENT_RATE_LIMITED',
  'TRANSIENT_SERVER',
  'UNKNOWN_TIMEOUT',
  'UNKNOWN_CONNECTION_LOST',
  'UNKNOWN_LEASE_EXPIRED',
  'UNKNOWN_BAD_RESPONSE',
  'RETRIES_EXHAUSTED',
  'NOT_SENT_UNREADABLE',
];
export const SUBMISSION_BLOCKERS: readonly PaymentSubmissionBlocker[] = [
  'BATCH_NOT_PREPARED',
  'PACKAGE_NOT_APPROVED',
  'PACKAGE_VOIDED',
  'PACKAGE_NOT_LATEST',
  'SUBMISSION_EXISTS',
  'PROVIDER_NOT_AVAILABLE',
  'PROVIDER_UNSUPPORTED',
  'PROVIDER_KEY_UNAVAILABLE',
  'DESTINATION_UNREADABLE',
  'LIMIT_REACHED',
];
/** States that still change by themselves: the page refreshes while it shows one of them. */
export const SUBMISSION_IN_FLIGHT: ReadonlySet<PaymentSubmissionState> = new Set([
  'QUEUED',
  'SENDING',
]);
/** Page size of the attempt history. */
export const ATTEMPTS_PAGE = 25;
/** Refresh period while a submission is in flight (milliseconds). */
export const SUBMISSION_REFRESH_MS = 4000;

/** Tone of a submission state; the text always carries the meaning. */
export function submissionTone(
  state: PaymentSubmissionState,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'ACCEPTED_FOR_PROCESSING':
      return 'success';
    case 'REJECTED':
    case 'MANUAL_INTERVENTION':
      return 'danger';
    case 'DELIVERY_UNKNOWN':
      return 'warning';
    default:
      return 'info';
  }
}

/** MVP-095C (Issue #190, ADR 0038): closed lists of the delivery investigation. */
export const RECONCILIATION_STATES: readonly PaymentReconciliationState[] = [
  'OPEN',
  'INQUIRY_IN_PROGRESS',
  'CONFIRMED_ACCEPTED_FOR_PROCESSING',
  'CONFIRMED_REJECTED',
  'STILL_UNRESOLVED',
  'MANUAL_REVIEW_REQUIRED',
];
export const RECONCILIATION_OUTCOMES: readonly PaymentReconciliationOutcome[] = [
  'ACCEPTED_FOR_PROCESSING',
  'REJECTED_INVALID_REQUEST',
  'REJECTED_SIGNATURE',
  'REJECTED_UNSUPPORTED',
  'REJECTED_DUPLICATE_MISMATCH',
  'REJECTED_LIMIT',
  'REJECTED_OTHER',
  'NOT_FOUND',
  'PENDING',
  'UNKNOWN',
  'BAD_RESPONSE',
  'TRANSIENT_CONNECT',
  'TRANSIENT_TLS',
  'TRANSIENT_TIMEOUT',
  'TRANSIENT_RATE_LIMITED',
  'TRANSIENT_SERVER',
  'RETRIES_EXHAUSTED',
];
export const RECONCILIATION_BLOCKERS: readonly PaymentReconciliationBlocker[] = [
  'SUBMISSION_NOT_ELIGIBLE',
  'RECONCILIATION_EXISTS',
  'PROVIDER_NOT_AVAILABLE',
  'PROVIDER_KEY_UNAVAILABLE',
];
/** Submission states that may be investigated (the server decides; this only shows the panel). */
export const RECONCILIATION_ELIGIBLE: ReadonlySet<PaymentSubmissionState> = new Set([
  'DELIVERY_UNKNOWN',
  'MANUAL_INTERVENTION',
]);
/** Reconciliation states that still change by themselves: the panel refreshes while it shows one. */
export const RECONCILIATION_IN_FLIGHT: ReadonlySet<PaymentReconciliationState> = new Set([
  'OPEN',
  'INQUIRY_IN_PROGRESS',
]);
/** States whose guidance asks for a human follow-up with the provider. */
export const RECONCILIATION_GUIDED: ReadonlySet<PaymentReconciliationState> = new Set([
  'STILL_UNRESOLVED',
  'MANUAL_REVIEW_REQUIRED',
]);
/** Page size of the inquiry history. */
export const INQUIRIES_PAGE = 25;
/** Refresh period while an investigation is running (milliseconds). */
export const RECONCILIATION_REFRESH_MS = 4000;
/** Upper bound of automatic refreshes per panel visit (the server stops at six inquiries). */
export const RECONCILIATION_REFRESH_MAX = 150;

/** Tone of a reconciliation state; the text always carries the meaning. */
export function reconciliationTone(
  state: PaymentReconciliationState,
): 'neutral' | 'info' | 'success' | 'warning' | 'danger' {
  switch (state) {
    case 'CONFIRMED_ACCEPTED_FOR_PROCESSING':
      return 'success';
    case 'CONFIRMED_REJECTED':
    case 'MANUAL_REVIEW_REQUIRED':
      return 'danger';
    case 'STILL_UNRESOLVED':
      return 'warning';
    default:
      return 'info';
  }
}

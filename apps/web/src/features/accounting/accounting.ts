import type { Problem } from '@divalhr/api-client';
import type { PayrollFailure } from '../payrollRuns/payrollRuns';

/**
 * MVP-096 (Issue #194, ADR 0039): accounting mappings and the accounting-journal export. The
 * browser shows what the server returned and sends what the contract lets it choose. It never
 * calculates an amount, never keeps an account code or an amount after the page is left, and never
 * puts one in a URL, in browser storage, in a log or in the console. The journal is an export for
 * manual import: nothing is posted anywhere and nothing proves a payment.
 */

export type AccountingCurrency = 'CDF' | 'USD';
export type AccountingDirection = 'DEBIT' | 'CREDIT';
export type AccountingMappingStatus = 'SCHEDULED' | 'ACTIVE' | 'SUPERSEDED';

export interface AccountingAccounts {
  salaryExpense: string;
  employeeDeductionLiability: string;
  employerContributionExpense: string;
  employerContributionLiability: string;
  netSalaryPayable: string;
}
export interface AccountingMapping {
  id: string;
  legalEntityId: string;
  currency: AccountingCurrency;
  version: number;
  effectiveFrom: string;
  accounts: AccountingAccounts;
  mappingDigest: string;
  status: AccountingMappingStatus;
  createdAt: string;
}
export interface AccountingMappingPreview {
  legalEntityId: string;
  currency: AccountingCurrency;
  nextVersion: number;
  effectiveFrom: string;
  accounts: AccountingAccounts;
  previousEffectiveFrom: string | null;
  status: 'READY' | 'BLOCKED';
  blockers: AccountingMappingBlocker[];
  previewDigest: string | null;
  expiresAt: string | null;
}
export interface AccountingJournalLine {
  lineNumber: number;
  accountCode: string;
  direction: AccountingDirection;
  amountMinor: number;
  costCenterCode: string | null;
  siteCode: string | null;
}
export interface AccountingJournalMappingRef {
  id: string;
  version: number;
  effectiveFrom: string;
}
export interface AccountingJournalPreview {
  legalEntityId: string;
  packageId: string;
  packageVersion: number;
  period: string;
  currency: AccountingCurrency;
  mapping: AccountingJournalMappingRef | null;
  status: 'READY' | 'BLOCKED';
  blockers: AccountingJournalBlocker[];
  sourceEntryCount: number;
  lineCount: number;
  totalDebitsMinor: number;
  totalCreditsMinor: number;
  lines: AccountingJournalLine[];
  previewDigest: string | null;
  expiresAt: string | null;
}
export interface AccountingJournalSummary {
  id: string;
  legalEntityId: string;
  packageId: string;
  version: number;
  period: string;
  currency: AccountingCurrency;
  lineCount: number;
  generatedAt: string;
  sourceVoided: boolean;
}
export interface AccountingJournalView {
  id: string;
  legalEntityId: string;
  legalEntityCode: string;
  packageId: string;
  packageVersion: number;
  version: number;
  period: string;
  currency: AccountingCurrency;
  mapping: AccountingJournalMappingRef;
  mappingDigest: string;
  payslipCount: number;
  sourceEntryCount: number;
  lineCount: number;
  totalDebitsMinor: number;
  totalCreditsMinor: number;
  csvSha256: string;
  csvBytes: number;
  journalDigest: string;
  schemaVersion: 1;
  generatedAt: string;
  sourceVoided: boolean;
  lines: AccountingJournalLine[];
}

/** The stable Problem Details codes of this feature (ADR 0039 section 8). */
export const ACCOUNTING_CODES = [
  'ACCOUNTING_MAPPING_NOT_FOUND',
  'ACCOUNTING_MAPPING_INVALID',
  'ACCOUNTING_MAPPING_OVERLAP',
  'ACCOUNTING_MAPPING_LIMIT_REACHED',
  'ACCOUNTING_MAPPING_PREVIEW_STALE',
  'ACCOUNTING_JOURNAL_SOURCE_NOT_APPROVED',
  'ACCOUNTING_JOURNAL_SOURCE_VOIDED',
  'ACCOUNTING_JOURNAL_ALREADY_EXISTS',
  'ACCOUNTING_JOURNAL_PREVIEW_STALE',
  'ACCOUNTING_JOURNAL_UNBALANCED',
  'ACCOUNTING_JOURNAL_TOO_LARGE',
  'ACCOUNTING_JOURNAL_NOT_FOUND',
] as const;
const ACCOUNTING_CODE_SET: ReadonlySet<string> = new Set(ACCOUNTING_CODES);
const STALE_CODES: ReadonlySet<string> = new Set([
  'ACCOUNTING_MAPPING_PREVIEW_STALE',
  'ACCOUNTING_JOURNAL_PREVIEW_STALE',
  'ACCOUNTING_JOURNAL_ALREADY_EXISTS',
  'ACCOUNTING_JOURNAL_SOURCE_VOIDED',
  'ACCOUNTING_JOURNAL_SOURCE_NOT_APPROVED',
  'IDEMPOTENCY_KEY_REUSED',
]);
const NOT_FOUND_CODES: ReadonlySet<string> = new Set([
  'ACCOUNTING_JOURNAL_NOT_FOUND',
  'PAYROLL_PACKAGE_NOT_FOUND',
  'LEGAL_ENTITY_NOT_FOUND',
]);
/** Platform and neighbouring codes this feature can meet, each with a finished message. */
const PLATFORM_CODE_SET: ReadonlySet<string> = new Set([
  'RATE_LIMITED',
  'IDEMPOTENCY_KEY_REUSED',
  'PAYROLL_PACKAGE_NOT_FOUND',
  'LEGAL_ENTITY_NOT_FOUND',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'INTERNAL_ERROR',
]);

export const CURRENCIES: readonly AccountingCurrency[] = ['CDF', 'USD'];
export const ACCOUNT_FIELDS: readonly (keyof AccountingAccounts)[] = [
  'salaryExpense',
  'employeeDeductionLiability',
  'employerContributionExpense',
  'employerContributionLiability',
  'netSalaryPayable',
];
export type AccountingMappingBlocker = 'OVERLAP' | 'LIMIT_REACHED';
export type AccountingJournalBlocker =
  | 'PACKAGE_VOIDED'
  | 'PACKAGE_NOT_APPROVED'
  | 'JOURNAL_ALREADY_EXISTS'
  | 'MAPPING_NOT_FOUND'
  | 'TOO_LARGE'
  | 'UNBALANCED';
export const MAPPING_BLOCKERS: readonly AccountingMappingBlocker[] = ['OVERLAP', 'LIMIT_REACHED'];
export const JOURNAL_BLOCKERS: readonly AccountingJournalBlocker[] = [
  'PACKAGE_VOIDED',
  'PACKAGE_NOT_APPROVED',
  'JOURNAL_ALREADY_EXISTS',
  'MAPPING_NOT_FOUND',
  'TOO_LARGE',
  'UNBALANCED',
];
export const MAPPING_STATUSES: readonly AccountingMappingStatus[] = [
  'SCHEDULED',
  'ACTIVE',
  'SUPERSEDED',
];

/** The server's account code format after trimming (a hint only: the server is authoritative). */
const ACCOUNT_CODE = /^[A-Z0-9][A-Z0-9._-]{0,39}$/u;

export function accountCodeValid(raw: string): boolean {
  return ACCOUNT_CODE.test(raw.trim());
}

/** The generated local name, identical to the server's: used when the header is not readable. */
export function journalFileName(
  view: Pick<AccountingJournalView, 'legalEntityCode' | 'period' | 'currency' | 'version'>,
  file: 'csv' | 'manifest',
): string {
  return `accounting-journal-${view.legalEntityCode}-${view.period}-${view.currency}-v${String(view.version)}.${
    file === 'csv' ? 'csv' : 'manifest.json'
  }`;
}

const SAFE_FILE_NAME = /^accounting-journal-[A-Za-z0-9._-]{1,120}\.(csv|manifest\.json)$/u;

/** A name from Content-Disposition only when it has the generated shape; never anything else. */
export function safeDispositionName(header: string | null): string | null {
  const match = header ? /filename="?([^";]+)"?/u.exec(header) : null;
  const name = match?.[1] ?? '';
  return SAFE_FILE_NAME.test(name) ? name : null;
}

/** Maps a Problem response; only the contract's codes are shown, nothing the server echoes. */
export function accountingFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && (ACCOUNTING_CODE_SET.has(code) || PLATFORM_CODE_SET.has(code))) {
    if (code === 'RATE_LIMITED') {
      return { ...base, code, kind: 'rateLimited', messageKey: `errors.${code}` };
    }
    const kind = STALE_CODES.has(code)
      ? 'stale'
      : NOT_FOUND_CODES.has(code)
        ? 'notFound'
        : code === 'MFA_REQUIRED' || code === 'ACCESS_DENIED' || response.status === 403
          ? 'forbidden'
          : ACCOUNTING_CODE_SET.has(code)
            ? 'domain'
            : 'general';
    return { ...base, code, kind, messageKey: `errors.${code}` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, code, kind: 'validation', messageKey: 'accounting.validation' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'accounting.forbidden' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** Phrases the accounting wording must never contain, outside the two fixed notices. */
export const FORBIDDEN_WORDING = /post(ed|ing)|settle|paid|payé|comptabilis/iu;

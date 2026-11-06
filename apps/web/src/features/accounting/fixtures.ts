import type {
  AccountingJournalPreview,
  AccountingJournalView,
  AccountingMapping,
  AccountingMappingPreview,
} from './accounting';

export const ENTITY_ID = '22222222-2222-4222-8222-222222222222';
export const PACKAGE_ID = '44444444-4444-4444-8444-444444444444';
export const JOURNAL_ID = '99999999-9999-4999-8999-999999999999';
export const MAPPING_ID = '88888888-8888-4888-8888-888888888888';
export const DIGEST = 'c'.repeat(64);
export const EXPIRES = '2026-10-19T10:30:00Z';
/** Synthetic account codes: they must never reach a URL, storage or the console. */
export const CODES = {
  salaryExpense: 'SYN-6411',
  employeeDeductionLiability: 'SYN.4311',
  employerContributionExpense: 'SYN_6451',
  employerContributionLiability: 'SYN4312',
  netSalaryPayable: 'SYN-4211',
};

export const problem = (code: string, status: number) => ({
  type: `https://docs.divalhr.com/errors/${code.toLowerCase()}`,
  title: code,
  status,
  code,
  correlationId: 'corr-synthetic',
});

export const mapping = (overrides: Partial<AccountingMapping> = {}): AccountingMapping => ({
  id: MAPPING_ID,
  legalEntityId: ENTITY_ID,
  currency: 'CDF',
  version: 1,
  effectiveFrom: '2026-01-01',
  accounts: CODES,
  mappingDigest: 'd'.repeat(64),
  status: 'ACTIVE',
  createdAt: '2026-01-02T08:00:00Z',
  ...overrides,
});

export const mappingPreview = (
  overrides: Partial<AccountingMappingPreview> = {},
): AccountingMappingPreview => ({
  legalEntityId: ENTITY_ID,
  currency: 'CDF',
  nextVersion: 2,
  effectiveFrom: '2026-09-01',
  accounts: CODES,
  previousEffectiveFrom: '2026-01-01',
  status: 'READY',
  blockers: [],
  previewDigest: DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});

export const lines = [
  {
    lineNumber: 1,
    accountCode: 'SYN-4211',
    direction: 'CREDIT' as const,
    amountMinor: 900000,
    costCenterCode: null,
    siteCode: null,
  },
  {
    lineNumber: 2,
    accountCode: 'SYN-6411',
    direction: 'DEBIT' as const,
    amountMinor: 1000000,
    costCenterCode: null,
    siteCode: null,
  },
  {
    lineNumber: 3,
    accountCode: 'SYN.4311',
    direction: 'CREDIT' as const,
    amountMinor: 100000,
    costCenterCode: null,
    siteCode: null,
  },
];

export const journalPreview = (
  overrides: Partial<AccountingJournalPreview> = {},
): AccountingJournalPreview => ({
  legalEntityId: ENTITY_ID,
  packageId: PACKAGE_ID,
  packageVersion: 1,
  period: '2026-09',
  currency: 'CDF',
  mapping: { id: MAPPING_ID, version: 2, effectiveFrom: '2026-09-01' },
  status: 'READY',
  blockers: [],
  sourceEntryCount: 5,
  lineCount: 3,
  totalDebitsMinor: 1000000,
  totalCreditsMinor: 1000000,
  lines,
  previewDigest: DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});

export const journalView = (
  overrides: Partial<AccountingJournalView> = {},
): AccountingJournalView => ({
  id: JOURNAL_ID,
  legalEntityId: ENTITY_ID,
  legalEntityCode: 'LE1',
  packageId: PACKAGE_ID,
  packageVersion: 1,
  version: 1,
  period: '2026-09',
  currency: 'CDF',
  mapping: { id: MAPPING_ID, version: 2, effectiveFrom: '2026-09-01' },
  mappingDigest: 'd'.repeat(64),
  payslipCount: 2,
  sourceEntryCount: 5,
  lineCount: 3,
  totalDebitsMinor: 1000000,
  totalCreditsMinor: 1000000,
  csvSha256: 'e'.repeat(64),
  csvBytes: 412,
  journalDigest: 'f'.repeat(64),
  schemaVersion: 1,
  generatedAt: '2026-10-19T10:00:00Z',
  sourceVoided: false,
  lines,
  ...overrides,
});

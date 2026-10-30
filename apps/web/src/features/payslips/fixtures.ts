/** Synthetic fixtures for the approved-payroll and payslip tests (no real person, amount or rule). */
export const ENTITY_ID = '11111111-0000-4000-8000-000000000001';
export const RUN_ID = '44444444-0000-4000-8000-000000000001';
export const CALC_ID = '88888888-0000-4000-8000-000000000001';
export const PACKAGE_ID = '99999999-0000-4000-8000-000000000001';
export const PACKAGE_ID_2 = '99999999-0000-4000-8000-000000000002';
export const PACK_ID = '77777777-0000-4000-8000-000000000002';
export const EMPLOYEE_ID = '22222222-0000-4000-8000-000000000001';
export const EMPLOYEE_ID_2 = '22222222-0000-4000-8000-000000000002';
export const PAYSLIP_ID = 'aaaaaaaa-0000-4000-8000-000000000001';
export const PAYSLIP_ID_2 = 'aaaaaaaa-0000-4000-8000-000000000002';
export const DIGEST = 'a'.repeat(64);
export const PREVIEW_DIGEST = 'e'.repeat(64);
export const SNAPSHOT_DIGEST = '9'.repeat(64);
export const EXPIRES = '2099-01-01T10:15:00Z';

export const cdf = (amount: string) => ({ currency: 'CDF', amount });

export const pack = (overrides: Record<string, unknown> = {}) => ({
  packId: PACK_ID,
  code: 'SYNTHETIC-TEST-1',
  version: 1,
  synthetic: true,
  ...overrides,
});

export const totals = () => ({
  contractualGross: cdf('1500000'),
  employeeDeductions: cdf('150000'),
  employerContributions: cdf('90000'),
  approvedNet: cdf('1350000'),
  employerCost: cdf('1590000'),
});

export const approvalLine = (overrides: Record<string, unknown> = {}) => ({
  employeeId: EMPLOYEE_ID,
  employeeNumber: 'E-0001',
  excluded: false,
  contractualGross: cdf('1000000'),
  employeeDeductions: cdf('100000'),
  employerContributions: cdf('60000'),
  approvedNet: cdf('900000'),
  employerCost: cdf('1060000'),
  ...overrides,
});

export const packageSummary = (overrides: Record<string, unknown> = {}) => ({
  packageId: PACKAGE_ID,
  legalEntityId: ENTITY_ID,
  period: '2026-09',
  currency: 'CDF',
  version: 1,
  status: 'APPROVED',
  synthetic: true,
  runId: RUN_ID,
  runVersion: 2,
  calculationId: CALC_ID,
  calculationVersion: 3,
  pack: pack(),
  payslipCount: 2,
  totals: totals(),
  samePreparer: false,
  approvedAt: '2026-10-02T09:00:00Z',
  voidedAt: null,
  voidReasonCode: null,
  ...overrides,
});

export const approvalPreview = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY_ID,
  runId: RUN_ID,
  runVersion: 2,
  calculationId: CALC_ID,
  calculationVersion: 3,
  period: '2026-09',
  currency: 'CDF',
  status: 'READY',
  pack: pack(),
  policySequence: 1,
  blockers: [],
  warnings: ['SYNTHETIC_RULE_PACK'],
  samePreparer: false,
  expectedVersion: 0,
  existingPackage: null,
  payslipCount: 2,
  excludedCount: 0,
  totals: totals(),
  lines: [
    approvalLine(),
    approvalLine({
      employeeId: EMPLOYEE_ID_2,
      employeeNumber: 'E-0002',
      contractualGross: cdf('500000'),
      employeeDeductions: cdf('50000'),
      employerContributions: cdf('30000'),
      approvedNet: cdf('450000'),
      employerCost: cdf('530000'),
    }),
  ],
  sourceRunDigest: DIGEST,
  calculationDigest: 'b'.repeat(64),
  profileDigest: 'c'.repeat(64),
  rulePackDigest: 'd'.repeat(64),
  previewDigest: PREVIEW_DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});

export const approvalReceipt = (overrides: Record<string, unknown> = {}) => ({
  packageId: PACKAGE_ID,
  legalEntityId: ENTITY_ID,
  runId: RUN_ID,
  runVersion: 2,
  calculationId: CALC_ID,
  calculationVersion: 3,
  packId: PACK_ID,
  packVersion: 1,
  period: '2026-09',
  currency: 'CDF',
  version: 1,
  status: 'APPROVED',
  synthetic: true,
  payslipCount: 2,
  samePreparer: false,
  approvedAt: '2026-10-02T09:00:00Z',
  ...overrides,
});

export const packageDetail = (overrides: Record<string, unknown> = {}, pkg = {}) => ({
  package: packageSummary(pkg),
  evidence: {
    approvalDigest: DIGEST,
    sourceRunDigest: DIGEST,
    runInputDigest: DIGEST,
    calculationDigest: DIGEST,
    calculationProfileDigest: DIGEST,
    calculationRulePackDigest: DIGEST,
    rulesDigest: DIGEST,
    policySequence: 1,
    reviewedTotals: true,
    reviewedExceptions: true,
    understandsNoPayment: true,
  },
  void: null,
  ...overrides,
});

export const voidPreview = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY_ID,
  packageId: PACKAGE_ID,
  packageVersion: 1,
  period: '2026-09',
  currency: 'CDF',
  packageStatus: 'APPROVED',
  status: 'READY',
  blockers: [],
  reasonCode: 'SOURCE_CORRECTION',
  payslipCount: 2,
  approvalDigest: DIGEST,
  previewDigest: PREVIEW_DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});

export const voidReceipt = () => ({
  packageId: PACKAGE_ID,
  legalEntityId: ENTITY_ID,
  version: 1,
  period: '2026-09',
  currency: 'CDF',
  status: 'VOIDED',
  reasonCode: 'SOURCE_CORRECTION',
  payslipCount: 2,
  voidedAt: '2026-10-03T09:00:00Z',
});

export const payslipSummary = (overrides: Record<string, unknown> = {}) => ({
  payslipId: PAYSLIP_ID,
  packageId: PACKAGE_ID,
  legalEntityId: ENTITY_ID,
  period: '2026-09',
  currency: 'CDF',
  employeeId: EMPLOYEE_ID,
  employeeNumber: 'E-0001',
  status: 'APPROVED',
  synthetic: true,
  voidReasonCode: null,
  approvedAt: '2026-10-02T09:00:00Z',
  voidedAt: null,
  contractualGross: cdf('1000000'),
  employeeDeductions: cdf('100000'),
  employerContributions: cdf('60000'),
  approvedNet: cdf('900000'),
  ...overrides,
});

export const payslipPage = (items: unknown[], nextCursor: string | null = null) => ({
  items,
  nextCursor,
});

const WORDING = {
  en: {
    locale: 'en',
    title: 'Payslip',
    statusLabel: 'Approved payroll',
    notProofOfPayment: 'Not proof of payment',
    employerContributionsNote:
      'Employer contributions are shown separately and do not reduce the employee’s net.',
    rulePackStatement: 'Statutory calculation based on rule pack SYNTHETIC-TEST-1 v1',
    syntheticNotice: 'Synthetic test rule pack: for testing only.',
    voidedNotice: null,
  },
  fr: {
    locale: 'fr',
    title: 'Bulletin de paie',
    statusLabel: 'Paie approuvée',
    notProofOfPayment: 'Ne constitue pas une preuve de paiement',
    employerContributionsNote:
      'Les cotisations patronales sont indiquées séparément et ne réduisent pas le net du salarié.',
    rulePackStatement: 'Calcul statutaire fondé sur le jeu de règles SYNTHETIC-TEST-1 v1',
    syntheticNotice: 'Jeu de règles synthétique de test : pour essais uniquement.',
    voidedNotice: null,
  },
} as const;

export const payslipView = (
  locale: 'en' | 'fr',
  overrides: Record<string, unknown> = {},
  voided = false,
) => ({
  payslipId: PAYSLIP_ID,
  status: voided ? 'VOIDED' : 'APPROVED',
  synthetic: true,
  voidReasonCode: voided ? 'SOURCE_CORRECTION' : null,
  voidedAt: voided ? '2026-10-03T09:00:00Z' : null,
  snapshot: {
    schemaVersion: 1,
    packageId: PACKAGE_ID,
    legalEntityId: ENTITY_ID,
    period: '2026-09',
    currency: 'CDF',
    employeeId: EMPLOYEE_ID,
    employeeNumber: 'E-0001',
    status: 'APPROVED',
    approvedAt: '2026-10-02T09:00:00.000000Z',
    rulePack: { code: 'SYNTHETIC-TEST-1', version: 1 },
    excluded: false,
    contractualGross: cdf('1000000'),
    employeeCnss: cdf('50000'),
    irppWithholding: cdf('50000'),
    employeeDeductions: cdf('100000'),
    approvedNet: cdf('900000'),
    employerContributions: cdf('60000'),
    explanationCodes: ['RATE_APPLIED'],
    components: [
      {
        seq: 1,
        id: 'CNSS_EMPLOYEE_PENSION',
        party: 'EMPLOYEE',
        category: 'SOCIAL_CONTRIBUTION',
        base: cdf('1000000'),
        amount: cdf('50000'),
        explanationCodes: ['RATE_APPLIED'],
      },
      {
        seq: 2,
        id: 'CNSS_EMPLOYER_PENSION',
        party: 'EMPLOYER',
        category: 'SOCIAL_CONTRIBUTION',
        base: cdf('1000000'),
        amount: cdf('60000'),
        explanationCodes: ['RATE_APPLIED'],
      },
    ],
  },
  snapshotDigest: SNAPSHOT_DIGEST,
  wording: {
    ...WORDING[locale],
    voidedNotice: voided
      ? locale === 'en'
        ? 'VOIDED: this payroll was voided.'
        : 'ANNULÉ : cette paie a été annulée.'
      : null,
  },
  ...overrides,
});

export const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-pay-4242',
});

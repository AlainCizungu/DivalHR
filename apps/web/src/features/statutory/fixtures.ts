/** Synthetic fixtures for the statutory-preview tests (no real person, amount, rule or reviewer). */
export const ENTITY_ID = '11111111-0000-4000-8000-000000000001';
export const RUN_ID = '44444444-0000-4000-8000-000000000001';
export const EMPLOYEE_ID = '22222222-0000-4000-8000-000000000001';
export const EMPLOYEE_ID_2 = '22222222-0000-4000-8000-000000000002';
export const DRAFT_PACK_ID = '77777777-0000-4000-8000-000000000001';
export const VERIFIED_PACK_ID = '77777777-0000-4000-8000-000000000002';
export const CALC_ID = '88888888-0000-4000-8000-000000000001';
export const CALC_ID_2 = '88888888-0000-4000-8000-000000000002';
export const SOURCE_DIGEST = 'a'.repeat(64);
export const PROFILE_DIGEST = 'b'.repeat(64);
export const PACK_DIGEST = 'c'.repeat(64);
export const CALC_DIGEST = 'd'.repeat(64);
export const PREVIEW_DIGEST = 'e'.repeat(64);
export const SHA = 'f'.repeat(64);
export const EXPIRES = '2099-01-01T10:15:00Z';

export const cdf = (amount: string) => ({ currency: 'CDF', amount });

export const packSummary = (overrides: Record<string, unknown> = {}) => ({
  packId: DRAFT_PACK_ID,
  code: 'CD-SALARY-PREVIEW',
  version: 1,
  country: 'CD',
  currency: 'CDF',
  status: 'DRAFT',
  effectiveFrom: '2026-01-01',
  effectiveTo: '2026-12-31',
  methodVersion: 1,
  rulesDigest: PACK_DIGEST,
  sourceCount: 2,
  ...overrides,
});
export const verifiedSummary = (overrides: Record<string, unknown> = {}) =>
  packSummary({
    packId: VERIFIED_PACK_ID,
    code: 'SYNTHETIC-TEST-1',
    status: 'VERIFIED',
    effectiveTo: null,
    ...overrides,
  });
export const packPage = (items: unknown[] = [packSummary()]) => ({ items, nextCursor: null });

export const source = (overrides: Record<string, unknown> = {}) => ({
  key: 'CNSS_RATES',
  title: 'CNSS contribution rates (synthetic title)',
  url: 'https://cnss.cd/?page_id=1388',
  sha256: null,
  retrievedOn: '2026-10-29',
  ...overrides,
});
export const sources = (hashed = false) => [
  source({ sha256: hashed ? SHA : null }),
  source({
    key: 'DGI_IRPP',
    title: 'DGI IRPP overview and progressive annual bands (synthetic title)',
    url: 'https://dgi.gouv.cd/impot-sur-le-revenu-des-personnes-physiques-irpp/',
    sha256: hashed ? SHA : null,
  }),
];

export const rules = () => [
  {
    id: 'CNSS_EMPLOYEE_PENSION',
    party: 'EMPLOYEE',
    category: 'SOCIAL_CONTRIBUTION',
    order: 1,
    requires: 'CNSS_SUBJECT',
    base: 'GROSS',
    sourceKey: 'CNSS_RATES',
    rounding: 'HALF_UP',
    method: { type: 'RATE', rateBp: 500 },
  },
  {
    id: 'CNSS_EMPLOYER_PENSION',
    party: 'EMPLOYER',
    category: 'SOCIAL_CONTRIBUTION',
    order: 2,
    requires: 'CNSS_SUBJECT',
    base: 'GROSS',
    sourceKey: 'CNSS_RATES',
    rounding: 'HALF_UP',
    method: { type: 'RATE', rateBp: 500 },
  },
  {
    id: 'IRPP_SALARY_WITHHOLDING',
    party: 'EMPLOYEE',
    category: 'INCOME_TAX',
    order: 3,
    requires: 'IRPP_SUBJECT',
    base: 'GROSS',
    sourceKey: 'DGI_IRPP',
    rounding: 'HALF_UP',
    method: {
      type: 'PROGRESSIVE',
      periodsPerYear: 12,
      capBp: 3000,
      brackets: [
        { upToAnnual: '1944000', rateBp: 300 },
        { upToAnnual: '21600000', rateBp: 1500 },
        { upToAnnual: null, rateBp: 4000 },
      ],
    },
  },
];

export const packDetail = (overrides: Record<string, unknown> = {}) => ({
  pack: packSummary(),
  sources: sources(false),
  components: rules(),
  verification: null,
  ...overrides,
});
export const verifiedDetail = () =>
  packDetail({
    pack: verifiedSummary(),
    sources: sources(true),
    verification: {
      reviewerName: 'Synthetic Test Reviewer',
      reviewerOrganization: 'Test Only',
      reviewedOn: '2026-10-01',
      attested: true,
      verifiedAt: '2026-10-02T08:00:00Z',
    },
  });

export const activationView = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY_ID,
  month: '2026-09',
  version: 0,
  verificationRequired: true,
  inEffect: null,
  activations: [],
  ...overrides,
});
export const activatedView = () =>
  activationView({
    version: 1,
    verificationRequired: false,
    inEffect: {
      activationId: '99999999-0000-4000-8000-000000000001',
      sequence: 1,
      fromMonth: '2026-01',
      pack: verifiedSummary(),
    },
    activations: [
      {
        activationId: '99999999-0000-4000-8000-000000000001',
        sequence: 1,
        fromMonth: '2026-01',
        packId: VERIFIED_PACK_ID,
        packCode: 'SYNTHETIC-TEST-1',
        packVersion: 1,
        activatedAt: '2026-10-02T09:00:00Z',
      },
    ],
  });
export const activationReceipt = () => ({
  activationId: '99999999-0000-4000-8000-000000000001',
  legalEntityId: ENTITY_ID,
  packId: VERIFIED_PACK_ID,
  packCode: 'SYNTHETIC-TEST-1',
  packVersion: 1,
  fromMonth: '2026-09',
  sequence: 1,
});

export const profileEntry = (overrides: Record<string, unknown> = {}) => ({
  profileId: '33333333-0000-4000-8000-000000000001',
  sequence: 1,
  effectiveFrom: '2026-01-01',
  country: 'CD',
  classification: 'STANDARD_RESIDENT',
  cnssSubject: true,
  irppSubject: true,
  evidenceCode: 'EMPLOYMENT_CONTRACT',
  createdAt: '2026-10-01T08:00:00Z',
  ...overrides,
});
export const profileView = (overrides: Record<string, unknown> = {}) => ({
  employeeId: EMPLOYEE_ID,
  version: 1,
  businessDate: '2026-10-29',
  current: profileEntry(),
  timeline: [profileEntry()],
  ...overrides,
});
export const emptyProfileView = () => profileView({ version: 0, current: null, timeline: [] });
export const profilePreview = (overrides: Record<string, unknown> = {}) => ({
  employeeId: EMPLOYEE_ID,
  version: 0,
  effectiveFrom: '2026-09-01',
  classification: 'STANDARD_RESIDENT',
  cnssSubject: true,
  irppSubject: true,
  evidenceCode: 'EMPLOYMENT_CONTRACT',
  before: [],
  after: [profileEntry({ effectiveFrom: '2026-09-01' })],
  blockers: [],
  previewDigest: PREVIEW_DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});
export const profileReceipt = () => ({
  profileId: '33333333-0000-4000-8000-000000000001',
  employeeId: EMPLOYEE_ID,
  sequence: 1,
  effectiveFrom: '2026-09-01',
  version: 1,
});

export const totals = (overrides: Record<string, unknown> = {}) => ({
  contractualGross: cdf('1500000'),
  employeeDeductions: cdf('187500'),
  employerContributions: cdf('150000'),
  previewNet: cdf('1312500'),
  employerCost: cdf('1650000'),
  ...overrides,
});

export const rateComponent = (overrides: Record<string, unknown> = {}) => ({
  componentId: 'CNSS_EMPLOYEE_PENSION',
  party: 'EMPLOYEE',
  category: 'SOCIAL_CONTRIBUTION',
  base: cdf('1000000'),
  amount: cdf('50000'),
  explanationCodes: ['BASE_GROSS', 'RATE_APPLIED'],
  trace: {},
  ...overrides,
});
export const employerComponent = () =>
  rateComponent({
    componentId: 'CNSS_EMPLOYER_PENSION',
    party: 'EMPLOYER',
    amount: cdf('50000'),
  });
export const taxComponent = () => ({
  componentId: 'IRPP_SALARY_WITHHOLDING',
  party: 'EMPLOYEE',
  category: 'INCOME_TAX',
  base: cdf('1000000'),
  amount: cdf('137500'),
  explanationCodes: ['BASE_GROSS', 'PROGRESSIVE_APPLIED'],
  trace: {
    annualBase: '12000000',
    slices: [
      { from: '0', to: '1944000', rateBp: 300, amount: '1944000' },
      { from: '1944000', to: '12000000', rateBp: 1500, amount: '10056000' },
    ],
    numerator: '15141600000',
    capApplied: false,
  },
});

export const previewLine = (overrides: Record<string, unknown> = {}) => ({
  employeeId: EMPLOYEE_ID,
  employeeNumber: 'E-0001',
  blockers: [],
  excluded: false,
  contractualGross: cdf('1000000'),
  employeeDeductions: cdf('187500'),
  employerContributions: cdf('50000'),
  previewNet: cdf('812500'),
  employerCost: cdf('1050000'),
  components: [rateComponent(), employerComponent(), taxComponent()],
  ...overrides,
});
export const secondLine = () =>
  previewLine({
    employeeId: EMPLOYEE_ID_2,
    employeeNumber: 'E-0002',
    contractualGross: cdf('500000'),
    employeeDeductions: cdf('0'),
    employerContributions: cdf('0'),
    previewNet: cdf('500000'),
    employerCost: cdf('500000'),
    excluded: true,
    components: [],
  });
export const blockedLine = (blockers: string[] = ['PROFILE_MISSING']) =>
  previewLine({
    employeeId: EMPLOYEE_ID_2,
    employeeNumber: 'E-0002',
    blockers,
    employeeDeductions: null,
    employerContributions: null,
    previewNet: null,
    employerCost: null,
    components: [],
  });

export const calculationPreview = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY_ID,
  runId: RUN_ID,
  runVersion: 1,
  period: '2026-09',
  currency: 'CDF',
  status: 'READY',
  pack: verifiedSummary(),
  runBlockers: [],
  expectedVersion: 0,
  existingCalculation: null,
  supportedCount: 1,
  excludedCount: 1,
  blockedCount: 0,
  totals: totals(),
  lines: [previewLine(), secondLine()],
  sourceRunDigest: SOURCE_DIGEST,
  profileDigest: PROFILE_DIGEST,
  rulePackDigest: PACK_DIGEST,
  calculationDigest: CALC_DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});
export const blockedPreview = (overrides: Record<string, unknown> = {}) =>
  calculationPreview({
    status: 'BLOCKED',
    blockedCount: 1,
    supportedCount: 0,
    excludedCount: 0,
    lines: [blockedLine()],
    calculationDigest: null,
    expiresAt: null,
    ...overrides,
  });
export const draftPackPreview = () =>
  blockedPreview({
    pack: packSummary(),
    runBlockers: ['RULE_PACK_NOT_VERIFIED'],
    totals: null,
    rulePackDigest: PACK_DIGEST,
    lines: [blockedLine([])],
  });

export const calcSummary = (overrides: Record<string, unknown> = {}) => ({
  calculationId: CALC_ID,
  runId: RUN_ID,
  runVersion: 1,
  packId: VERIFIED_PACK_ID,
  packCode: 'SYNTHETIC-TEST-1',
  packVersion: 1,
  period: '2026-09',
  currency: 'CDF',
  version: 1,
  status: 'DRAFT',
  supportedCount: 1,
  excludedCount: 1,
  totals: totals(),
  createdAt: '2026-10-03T08:00:00Z',
  supersededAt: null,
  supersededById: null,
  ...overrides,
});
export const calcPage = (items: unknown[] = [calcSummary()]) => ({ items, nextCursor: null });
export const calcReceipt = () => ({
  calculationId: CALC_ID,
  runId: RUN_ID,
  legalEntityId: ENTITY_ID,
  packId: VERIFIED_PACK_ID,
  packVersion: 1,
  period: '2026-09',
  currency: 'CDF',
  version: 1,
  status: 'DRAFT',
  supersededCalculationId: null,
  supportedCount: 1,
  excludedCount: 1,
});
export const runLine = (overrides: Record<string, unknown> = {}) => ({
  ...previewLine({ blockers: undefined }),
  ...overrides,
});
export const calcDetail = (overrides: Record<string, unknown> = {}) => ({
  calculation: calcSummary(),
  pack: verifiedSummary(),
  sources: sources(true),
  lines: [runLine()],
  nextCursor: null,
  ...overrides,
});

/** A minimal payroll run detail (the statutory page reads only the run's period and currency). */
export const payrollRun = (overrides: Record<string, unknown> = {}) => ({
  run: {
    runId: RUN_ID,
    legalEntityId: ENTITY_ID,
    period: '2026-09',
    currency: 'CDF',
    version: 1,
    status: 'DRAFT',
    policySequence: 1,
    employeeCount: 2,
    excludedEmployeeCount: 0,
    total: cdf('1500000'),
    createdAt: '2026-10-01T08:00:00Z',
    supersededAt: null,
    supersededByRunId: null,
    ...overrides,
  },
  policy: {
    policyId: '55555555-0000-4000-8000-000000000001',
    sequence: 1,
    payDay: { kind: 'LAST_DAY', day: null },
    prorationMethod: 'CALENDAR_DAYS',
  },
  inputDigest: SOURCE_DIGEST,
  lines: [],
  nextCursor: null,
});

export const problem = (code: string, status: number) => ({
  type: 'about:blank',
  title: 'x',
  status,
  code,
  params: {},
  correlationId: 'corr-stat-4242',
});

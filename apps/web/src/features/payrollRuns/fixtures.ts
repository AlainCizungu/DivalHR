/** Synthetic fixtures for the payroll-run tests (no real person or amount). */
export const ENTITY_ID = '11111111-0000-4000-8000-000000000001';
export const RUN_ID = '44444444-0000-4000-8000-000000000001';
export const RUN_ID_2 = '44444444-0000-4000-8000-000000000002';
export const POLICY_ID = '55555555-0000-4000-8000-000000000001';
export const DIGEST = 'a'.repeat(64);
export const PREVIEW_DIGEST = 'b'.repeat(64);
export const EXPIRES = '2099-01-01T10:15:00Z';

export const policyEntry = (overrides: Record<string, unknown> = {}) => ({
  policyId: POLICY_ID,
  sequence: 1,
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  payDay: { kind: 'LAST_DAY', day: null },
  prorationMethod: 'CALENDAR_DAYS',
  ...overrides,
});
export const policyView = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY_ID,
  version: 1,
  businessDate: '2026-10-29',
  current: policyEntry(),
  policies: [policyEntry()],
  ...overrides,
});
export const noPolicyView = () => policyView({ version: 0, current: null, policies: [] });
export const policyPreview = (overrides: Record<string, unknown> = {}) => ({
  effectiveFrom: '2026-11-01',
  payDay: { kind: 'DAY', day: 25 },
  prorationMethod: 'FIXED_30_DAYS',
  before: [policyEntry()],
  after: [
    policyEntry({ effectiveTo: '2026-10-31' }),
    policyEntry({
      sequence: 2,
      effectiveFrom: '2026-11-01',
      payDay: { kind: 'DAY', day: 25 },
      prorationMethod: 'FIXED_30_DAYS',
    }),
  ],
  blockers: [],
  version: 1,
  previewDigest: PREVIEW_DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});

export const usd = (amount: string) => ({ currency: 'USD', amount });
export const summary = (overrides: Record<string, unknown> = {}) => ({
  runId: RUN_ID,
  legalEntityId: ENTITY_ID,
  period: '2026-09',
  currency: 'USD',
  version: 1,
  status: 'DRAFT',
  policySequence: 1,
  employeeCount: 2,
  total: usd('1875.00'),
  createdAt: '2026-10-01T08:00:00Z',
  supersededAt: null,
  supersededByRunId: null,
  ...overrides,
});
export const previewLine = (overrides: Record<string, unknown> = {}) => ({
  employeeId: '22222222-0000-4000-8000-000000000001',
  employeeNumber: 'E-0001',
  eligibleDays: 30,
  ranges: [{ from: '2026-09-01', to: '2026-09-30' }],
  denominator: null,
  fullMonth: true,
  monthly: usd('1250.00'),
  gross: usd('1250.00'),
  explanationCodes: ['FULL_MONTH'],
  blockers: [],
  ...overrides,
});
export const partialLine = () =>
  previewLine({
    employeeId: '22222222-0000-4000-8000-000000000002',
    employeeNumber: 'E-0002',
    eligibleDays: 15,
    ranges: [{ from: '2026-09-16', to: '2026-09-30' }],
    denominator: 30,
    fullMonth: false,
    gross: usd('625.00'),
    explanationCodes: ['STARTED_IN_MONTH', 'PRORATED_CALENDAR_DAYS'],
  });
export const blockedLine = () =>
  previewLine({
    employeeId: '22222222-0000-4000-8000-000000000003',
    employeeNumber: 'E-0003',
    eligibleDays: 0,
    ranges: [],
    denominator: null,
    fullMonth: false,
    monthly: null,
    gross: null,
    explanationCodes: [],
    blockers: ['COMPENSATION_MISSING'],
  });
export const runPreview = (overrides: Record<string, unknown> = {}) => ({
  legalEntityId: ENTITY_ID,
  period: '2026-09',
  currency: 'USD',
  policy: {
    policyId: POLICY_ID,
    sequence: 1,
    payDay: { kind: 'LAST_DAY', day: null },
    prorationMethod: 'CALENDAR_DAYS',
  },
  expectedVersion: 0,
  existingRun: null,
  identicalToCurrent: false,
  employeeCount: 2,
  blockedEmployeeCount: 0,
  total: usd('1875.00'),
  lines: [previewLine(), partialLine()],
  inputDigest: DIGEST,
  previewDigest: PREVIEW_DIGEST,
  expiresAt: EXPIRES,
  ...overrides,
});
export const receipt = () => ({
  runId: RUN_ID,
  legalEntityId: ENTITY_ID,
  period: '2026-09',
  currency: 'USD',
  version: 1,
  status: 'DRAFT',
  supersededRunId: null,
  employeeCount: 2,
});
export const runLine = (overrides: Record<string, unknown> = {}) => ({
  ...previewLine(),
  blockers: undefined,
  sources: [
    {
      kind: 'EMPLOYMENT',
      sourceId: '66666666-0000-4000-8000-000000000001',
      sourceVersion: 1,
      validFrom: '2026-01-01',
      validTo: null,
    },
    {
      kind: 'PLACEMENT',
      sourceId: '66666666-0000-4000-8000-000000000002',
      sourceVersion: 2,
      validFrom: '2026-01-01',
      validTo: null,
    },
    {
      kind: 'COMPENSATION',
      sourceId: '66666666-0000-4000-8000-000000000003',
      sourceVersion: 3,
      validFrom: '2026-01-01',
      validTo: null,
    },
  ],
  ...overrides,
});
export const runDetail = (overrides: Record<string, unknown> = {}) => ({
  run: summary(),
  policy: runPreview().policy,
  inputDigest: DIGEST,
  lines: [runLine()],
  nextCursor: null,
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

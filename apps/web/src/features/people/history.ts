import type {
  AssignmentKind,
  AssignmentPeriod,
  CompensationBasis,
  ContractClassification,
  EmploymentChangeReason,
  Problem,
} from '@divalhr/api-client';
import type { TFunction } from 'i18next';

/** Code points, matching the server's codePointCount rules. */
export function codePoints(text: string): number {
  // eslint-disable-next-line @typescript-eslint/no-misused-spread
  return [...text].length;
}

/** MVP-021 constants, in the contract's order. */
export const KINDS: readonly AssignmentKind[] = [
  'PLACEMENT',
  'MANAGER',
  'CONTRACT',
  'COMPENSATION',
];
export const CONTRACTS: readonly ContractClassification[] = [
  'PERMANENT',
  'FIXED_TERM',
  'APPRENTICESHIP',
  'INTERNSHIP',
  'DAILY',
];
export const COMPENSATIONS: readonly CompensationBasis[] = [
  'MONTHLY',
  'HOURLY',
  'DAILY',
  'PIECE_RATE',
];
export const CHANGE_REASONS: readonly EmploymentChangeReason[] = [
  'LATE_NOTIFICATION',
  'REORGANIZATION',
  'CONTRACT_CHANGE',
  'OTHER_BUSINESS_CHANGE',
];
export const CORRECTION_REASONS: readonly EmploymentChangeReason[] = [
  'DATA_ENTRY_ERROR',
  'IMPORT_ERROR',
  'DOCUMENT_RECEIVED',
];

/**
 * A business date (YYYY-MM-DD, a calendar date in the organization's time zone) in the user's
 * language. It is formatted as a UTC date so the calendar day never shifts with the device.
 */
export function formatDate(language: string, date: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'long', timeZone: 'UTC' }).format(
    new Date(`${date}T00:00:00Z`),
  );
}

/** A period: "from D" or "D1 – D2". */
export function formatPeriod(
  t: TFunction,
  language: string,
  from: string,
  to: string | null,
): string {
  return to
    ? t('employees.period.closed', {
        from: formatDate(language, from),
        to: formatDate(language, to),
      })
    : t('employees.period.open', { from: formatDate(language, from) });
}

/** A row's value as text: unit codes and names, the manager's name, or a localized code. */
export function valueText(
  t: TFunction,
  row: Pick<
    AssignmentPeriod,
    'placement' | 'manager' | 'contractClassification' | 'compensationBasis'
  >,
): string {
  if (row.placement) {
    return [
      row.placement.legalEntity,
      row.placement.site,
      row.placement.department,
      row.placement.costCenter,
      row.placement.team,
    ]
      .filter((unit) => unit !== null)
      .map((unit) => `${unit.name} (${unit.code})`)
      .join(' › ');
  }
  if (row.manager) {
    return t('employees.managerName', {
      given: row.manager.givenNames,
      family: row.manager.familyName,
      number: row.manager.employeeNumber,
    });
  }
  if (row.contractClassification) return t(`employees.contract.${row.contractClassification}`);
  if (row.compensationBasis) return t(`employees.compensation.${row.compensationBasis}`);
  return t('employees.none');
}

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export type HistoryFailure = {
  messageKey: string;
  code?: string;
  detailKey?: string;
  detailValues?: Record<string, string>;
  correlationId?: string;
  retryAfter?: number;
};

export const HISTORY_NETWORK_FAILURE: HistoryFailure = { messageKey: 'errors.network' };

const MANAGER_REASONS = new Set(['NOT_FOUND', 'SELF', 'NOT_EMPLOYED', 'CYCLE', 'CHAIN_TOO_DEEP']);
const PLACEMENT_REASONS = new Set(['NOT_FOUND', 'MISMATCH', 'NOT_EFFECTIVE', 'ENDS_DURING_PERIOD']);
const PLACEMENT_FIELDS = new Set([
  'legalEntityId',
  'siteId',
  'departmentId',
  'costCenterId',
  'teamId',
]);

/** Maps a Problem response; params outside the contract's allow-lists are dropped, not shown. */
export function historyFailureOf(response: Response, error: unknown): HistoryFailure {
  const problem = error as Partial<Problem> | undefined;
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const retry = Number(response.headers.get('Retry-After'));
  const failure: HistoryFailure = {
    messageKey:
      response.status === 403
        ? 'employees.unauthorized'
        : problem?.code
          ? `errors.${problem.code}`
          : 'errors.generic',
    code: problem?.code,
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  const reason = typeof params.reason === 'string' ? params.reason : '';
  const field = typeof params.field === 'string' ? params.field : '';
  if (problem?.code === 'MANAGER_INVALID' && MANAGER_REASONS.has(reason)) {
    failure.detailKey = `employees.problems.manager.${reason}`;
  } else if (
    problem?.code === 'PLACEMENT_INVALID' &&
    PLACEMENT_REASONS.has(reason) &&
    PLACEMENT_FIELDS.has(field)
  ) {
    failure.detailKey = `employees.problems.placement.${reason}`;
    failure.detailValues = { field };
  } else if (
    problem?.code === 'EMPLOYMENT_CHANGE_NO_EFFECT' &&
    (KINDS as readonly string[]).includes(field)
  ) {
    failure.detailKey = 'employees.problems.noEffect';
    failure.detailValues = { kind: field };
  }
  return failure;
}

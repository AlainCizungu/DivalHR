import type { CreateMyLeaveRequest, MyLeavePolicy, Problem } from '@divalhr/api-client';

/** MVP-041A request form fields, in the order problems are listed. */
export const REQUEST_FIELDS = ['policy', 'startDate', 'endDate', 'amount'] as const;
export type RequestField = (typeof REQUEST_FIELDS)[number];

/** What the employee typed; nothing is preselected. */
export interface LeaveRequestForm {
  policyId: string;
  startDate: string;
  endDate: string;
  amount: string;
}

export const EMPTY_REQUEST: LeaveRequestForm = {
  policyId: '',
  startDate: '',
  endDate: '',
  amount: '',
};

const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/u;
const AMOUNT = /^[0-9]{1,5}(?:[.,][0-9]{1,2})?$/u;
const DAY_MS = 86_400_000;

function validDate(value: string): boolean {
  if (!DATE.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return (
    !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().startsWith(value) &&
    value >= '1900-01-01' &&
    value <= '2999-12-31'
  );
}

/** The amount as a number with at most two decimals, or null when it is not one. */
export function parseAmount(text: string): number | null {
  const value = text.trim();
  if (!AMOUNT.test(value)) return null;
  const number = Number(value.replace(',', '.'));
  return number > 0 && number <= 10000 ? number : null;
}

/**
 * The fields the browser can already tell are wrong; the server checks everything again, including
 * the policy period, the employment and the minimum service.
 *
 * @param form what was typed
 * @param asOf the server's business date, when the catalogue has loaded
 */
export function requestProblemsOf(form: LeaveRequestForm, asOf: string | null): Set<RequestField> {
  const problems = new Set<RequestField>();
  if (!form.policyId) problems.add('policy');
  const start = validDate(form.startDate) && (asOf === null || form.startDate >= asOf);
  if (!start) problems.add('startDate');
  if (!validDate(form.endDate)) {
    problems.add('endDate');
  } else if (validDate(form.startDate)) {
    const days =
      (Date.parse(`${form.endDate}T00:00:00Z`) - Date.parse(`${form.startDate}T00:00:00Z`)) /
        DAY_MS +
      1;
    if (days < 1 || days > 366) problems.add('endDate');
  }
  if (parseAmount(form.amount) === null) problems.add('amount');
  return problems;
}

/** The request body of a valid form. */
export function requestBodyOf(form: LeaveRequestForm): CreateMyLeaveRequest {
  return {
    policyId: form.policyId,
    startDate: form.startDate,
    endDate: form.endDate,
    amount: parseAmount(form.amount) ?? 0,
  };
}

const SERVER_FIELDS: Record<string, RequestField> = {
  policyId: 'policy',
  startDate: 'startDate',
  endDate: 'endDate',
  amount: 'amount',
};

const NOT_ELIGIBLE = new Set(['EMPLOYMENT_PERIOD', 'MINIMUM_SERVICE']);

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export type MyLeaveFailure = {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'unavailable'
    | 'notEligible'
    | 'overlap'
    | 'conflict'
    | 'general';
  messageKey: string;
  fields?: RequestField[];
  correlationId?: string;
  retryAfter?: number;
};

export const MY_LEAVE_NETWORK_FAILURE: MyLeaveFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; params outside the allow-lists are dropped, never shown. */
export function myLeaveFailureOf(response: Response, error: unknown): MyLeaveFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'myLeave.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'LEAVE_POLICY_NOT_REQUESTABLE') {
    return { ...base, kind: 'unavailable', messageKey: 'errors.LEAVE_POLICY_NOT_REQUESTABLE' };
  }
  if (code === 'LEAVE_REQUEST_NOT_ELIGIBLE') {
    const reason = typeof params.reason === 'string' ? params.reason : '';
    return {
      ...base,
      kind: 'notEligible',
      messageKey: NOT_ELIGIBLE.has(reason)
        ? `myLeave.notEligible.${reason}`
        : 'errors.LEAVE_REQUEST_NOT_ELIGIBLE',
    };
  }
  if (code === 'LEAVE_REQUEST_OVERLAP') {
    return { ...base, kind: 'overlap', messageKey: 'errors.LEAVE_REQUEST_OVERLAP' };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<RequestField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as RequestField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: REQUEST_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return {
    ...base,
    kind: 'general',
    messageKey: code === 'CURSOR_INVALID' ? 'errors.CURSOR_INVALID' : 'errors.generic',
  };
}

/** A number in the interface language, with up to two decimals. */
export function formatAmount(language: string, amount: number): string {
  return new Intl.NumberFormat(language, {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(amount);
}

/** The policy chosen in the form, if it is loaded. */
export function selectedPolicy(
  policies: readonly MyLeavePolicy[],
  policyId: string,
): MyLeavePolicy | undefined {
  return policies.find((policy) => policy.id === policyId);
}

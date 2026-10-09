import type {
  CreateLeavePolicy,
  LeaveApprovalRoute,
  LeaveBalanceMode,
  LeavePayrollEffect,
  LeavePolicyStatus,
  LeaveUnit,
  Problem,
} from '@divalhr/api-client';
import type { StatusTone } from '../../ui/primitives';
import { codePoints } from '../people/history';

/** MVP-040A closed values, in the contract's order. */
export const UNITS: readonly LeaveUnit[] = ['DAYS', 'HOURS'];
export const BALANCE_MODES: readonly LeaveBalanceMode[] = ['TRACKED', 'UNTRACKED'];
export const APPROVAL_ROUTES: readonly LeaveApprovalRoute[] = ['MANAGER', 'TENANT_ADMIN'];
export const PAYROLL_EFFECTS: readonly LeavePayrollEffect[] = ['PAID', 'UNPAID'];

/** Form fields, in the order problems are listed. */
export const FIELDS = [
  'code',
  'nameEn',
  'nameFr',
  'unit',
  'balanceMode',
  'annualEntitlement',
  'minimumServiceDays',
  'approvalRoute',
  'payrollEffect',
  'effectiveFrom',
  'effectiveTo',
] as const;
export type Field = (typeof FIELDS)[number];

/** What the administrator typed; every value is text until it is validated. */
export interface LeavePolicyForm {
  code: string;
  nameEn: string;
  nameFr: string;
  unit: LeaveUnit | '';
  balanceMode: LeaveBalanceMode | '';
  annualEntitlement: string;
  minimumServiceDays: string;
  approvalRoute: LeaveApprovalRoute | '';
  payrollEffect: LeavePayrollEffect | '';
  effectiveFrom: string;
  effectiveTo: string;
}

/** No value is suggested: the organization defines every one (no statutory default). */
export const EMPTY_FORM: LeavePolicyForm = {
  code: '',
  nameEn: '',
  nameFr: '',
  unit: '',
  balanceMode: '',
  annualEntitlement: '',
  minimumServiceDays: '',
  approvalRoute: '',
  payrollEffect: '',
  effectiveFrom: '',
  effectiveTo: '',
};

const CODE = /^[A-Z0-9][A-Z0-9_-]{1,19}$/u;
const ENTITLEMENT = /^[0-9]{1,5}(?:[.,][0-9]{1,2})?$/u;
const WHOLE = /^[0-9]{1,4}$/u;
const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/u;
// Control, format, line and paragraph separator characters (the server refuses them too).
const UNSAFE = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}\p{Co}]/u;

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

function nameOk(value: string): boolean {
  const name = value.trim().normalize('NFC');
  const length = codePoints(name);
  return length >= 2 && length <= 100 && !UNSAFE.test(name);
}

/** The entitlement as a number with at most two decimals, or null when it is not one. */
export function parseEntitlement(text: string): number | null {
  const value = text.trim();
  if (!ENTITLEMENT.test(value)) return null;
  const number = Number(value.replace(',', '.'));
  return number > 0 && number <= 10000 ? number : null;
}

/** The fields the browser can already tell are wrong (the server checks everything again). */
export function problemsOf(form: LeavePolicyForm): Set<Field> {
  const problems = new Set<Field>();
  if (!CODE.test(form.code.trim().toUpperCase())) problems.add('code');
  if (!nameOk(form.nameEn)) problems.add('nameEn');
  if (!nameOk(form.nameFr)) problems.add('nameFr');
  if (!form.unit) problems.add('unit');
  if (!form.balanceMode) problems.add('balanceMode');
  if (form.balanceMode === 'TRACKED' && parseEntitlement(form.annualEntitlement) === null) {
    problems.add('annualEntitlement');
  }
  const days = form.minimumServiceDays.trim();
  if (!WHOLE.test(days) || Number(days) > 3650) problems.add('minimumServiceDays');
  if (!form.approvalRoute) problems.add('approvalRoute');
  if (!form.payrollEffect) problems.add('payrollEffect');
  if (!validDate(form.effectiveFrom)) problems.add('effectiveFrom');
  if (
    form.effectiveTo !== '' &&
    (!validDate(form.effectiveTo) ||
      (validDate(form.effectiveFrom) && form.effectiveTo < form.effectiveFrom))
  ) {
    problems.add('effectiveTo');
  }
  return problems;
}

/** The request body of a valid form. */
export function bodyOf(form: LeavePolicyForm): CreateLeavePolicy {
  const tracked = form.balanceMode === 'TRACKED';
  return {
    code: form.code.trim().toUpperCase(),
    names: { en: form.nameEn.trim(), fr: form.nameFr.trim() },
    unit: form.unit as LeaveUnit,
    balanceMode: form.balanceMode as LeaveBalanceMode,
    ...(tracked ? { annualEntitlement: parseEntitlement(form.annualEntitlement) } : {}),
    minimumServiceDays: Number(form.minimumServiceDays.trim()),
    approvalRoute: form.approvalRoute as LeaveApprovalRoute,
    payrollEffect: form.payrollEffect as LeavePayrollEffect,
    effectiveFrom: form.effectiveFrom,
    ...(form.effectiveTo ? { effectiveTo: form.effectiveTo } : {}),
  };
}

/** Server field names of VALIDATION_FAILED mapped to form fields (anything else is ignored). */
const SERVER_FIELDS: Record<string, Field> = {
  code: 'code',
  'names.en': 'nameEn',
  'names.fr': 'nameFr',
  unit: 'unit',
  balanceMode: 'balanceMode',
  annualEntitlement: 'annualEntitlement',
  minimumServiceDays: 'minimumServiceDays',
  approvalRoute: 'approvalRoute',
  payrollEffect: 'payrollEffect',
  effectiveFrom: 'effectiveFrom',
  effectiveTo: 'effectiveTo',
};

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export type LeaveFailure = {
  kind: 'forbidden' | 'conflict' | 'rateLimited' | 'validation' | 'general';
  messageKey: string;
  /** For VALIDATION_FAILED: the form fields the server named, never their values. */
  fields?: Field[];
  correlationId?: string;
  retryAfter?: number;
};

export const LEAVE_NETWORK_FAILURE: LeaveFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; params outside the allow-list are dropped, not shown. */
export function leaveFailureOf(response: Response, error: unknown): LeaveFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'leavePolicies.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'LEAVE_POLICY_CODE_EXISTS' || code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: `errors.${code}` };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = (problem?.params as { fields?: unknown } | undefined)?.fields;
    const fields = new Set<Field>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && Object.hasOwn(SERVER_FIELDS, name)) {
          fields.add(SERVER_FIELDS[name] as Field);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: FIELDS.filter((field) => fields.has(field)),
    };
  }
  return {
    ...base,
    kind: 'general',
    messageKey: code === 'CURSOR_INVALID' ? 'errors.CURSOR_INVALID' : 'errors.generic',
  };
}

/** The colour that reinforces a status; the text always carries the meaning. */
export function toneOf(status: LeavePolicyStatus): StatusTone {
  return status === 'ACTIVE' ? 'success' : status === 'PLANNED' ? 'info' : 'neutral';
}

import type {
  AttendanceException,
  AttendanceExceptionType,
  Problem,
  TeamAttendanceException,
} from '@divalhr/api-client';
import { daysBetween } from './workSchedules';

/**
 * MVP-060C: factual attendance exceptions, derived by the server at one instant. The browser only
 * filters and displays them: it never computes an exception, a duration or a policy outcome.
 */

/** Which of the three read-only views a list belongs to. */
export type ExceptionView = 'self' | 'team' | 'admin';

/** The types in their stable order (the order of the type filter). */
export const EXCEPTION_TYPES: readonly AttendanceExceptionType[] = [
  'MISSING_CHECK_IN',
  'LATE_CHECK_IN',
  'EARLY_CHECK_OUT',
  'MISSING_CHECK_OUT',
];

/** Longest explicit range the server accepts: 93 inclusive site-local dates. */
export const MAX_RANGE_DAYS = 93;

/** The default range the server applies when both dates are empty. */
export const DEFAULT_RANGE_DAYS = 31;

/** The filters a reviewer submits; empty strings are absent filters. */
export interface ExceptionFilters {
  type: '' | AttendanceExceptionType;
  from: string;
  to: string;
  siteId: string;
}

export const EMPTY_FILTERS: ExceptionFilters = { type: '', from: '', to: '', siteId: '' };

export const FILTER_FIELDS = ['type', 'from', 'to', 'siteId'] as const;

/** The two date fields, in form order. */
export const DATE_FIELDS = ['from', 'to'] as const;
export type FilterField = (typeof FILTER_FIELDS)[number];

/** The problem of a filter field the browser can already tell; the server checks it again. */
export type FilterProblem = 'required' | 'range';

/** Both dates or neither; the first not after the last; at most 93 inclusive dates. */
export function filterProblemsOf(filters: ExceptionFilters): Map<FilterField, FilterProblem> {
  const problems = new Map<FilterField, FilterProblem>();
  if (filters.from && !filters.to) problems.set('to', 'required');
  else if (filters.to && !filters.from) problems.set('from', 'required');
  else if (filters.from && filters.to) {
    const span = daysBetween(filters.from, filters.to);
    if (!Number.isFinite(span) || span < 0 || span + 1 > MAX_RANGE_DAYS)
      problems.set('to', 'range');
  }
  return problems;
}

/** A page's query parameters. */
export interface ExceptionQuery {
  siteId?: string;
  type?: AttendanceExceptionType;
  from?: string;
  to?: string;
  cursor?: string;
}

/** The query of a page: only present filters, never an identity, a tenant or a time. */
export function exceptionQueryOf(
  view: ExceptionView,
  filters: ExceptionFilters,
  cursor: string | null,
): ExceptionQuery {
  const query: ExceptionQuery = {};
  if (view === 'admin' && filters.siteId) query.siteId = filters.siteId;
  if (filters.type) query.type = filters.type;
  if (filters.from && filters.to) {
    query.from = filters.from;
    query.to = filters.to;
  }
  if (cursor) query.cursor = cursor;
  return query;
}

/** A refused or failed exception list, reduced to stable codes and allow-listed fields. */
export interface ExceptionFailure {
  kind:
    | 'linkRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'scopeNotFound'
    | 'cursor'
    | 'general';
  messageKey: string;
  fields?: FilterField[];
  correlationId?: string;
  retryAfter?: number;
}

export const EXCEPTION_NETWORK_FAILURE: ExceptionFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; no param outside the field allow-list is ever shown. */
export function exceptionFailureOf(
  view: ExceptionView,
  response: Response,
  error: unknown,
): ExceptionFailure {
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
    return {
      ...base,
      kind: 'forbidden',
      messageKey: view === 'admin' ? 'attendanceReview.unauthorized' : 'attendance.unauthorized',
    };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND') {
    return {
      ...base,
      kind: 'scopeNotFound',
      messageKey: 'errors.ATTENDANCE_EXCEPTION_SCOPE_NOT_FOUND',
    };
  }
  if (code === 'CURSOR_INVALID') {
    return { ...base, kind: 'cursor', messageKey: 'errors.CURSOR_INVALID' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<FilterField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && (FILTER_FIELDS as readonly string[]).includes(name)) {
          fields.add(name as FilterField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'errors.VALIDATION_FAILED',
      fields: FILTER_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** One row of any view: the employee is present in the manager's and the organization's views. */
export type ExceptionRow = AttendanceException & {
  employee?: TeamAttendanceException['employee'];
};

/** A stable key: the order's keys (slot, employee, type) identify one row. */
export function exceptionKey(row: ExceptionRow): string {
  return `${row.slotId}-${row.employee?.id ?? 'self'}-${row.type}`;
}

/** "Given Family (number)" as plain text, never markup. */
export function exceptionEmployeeName(row: ExceptionRow): string {
  const person = row.employee;
  return person ? `${person.givenNames} ${person.familyName} (${person.employeeNumber})` : '';
}

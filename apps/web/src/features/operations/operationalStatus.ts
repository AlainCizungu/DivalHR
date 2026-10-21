import type { Problem } from '@divalhr/api-client';

/**
 * MVP-100: today's operational status, derived by the server at one instant. The browser only
 * displays it: it never computes a count, a threshold, a date or an outcome.
 */

/** Which of the two read-only views a status belongs to. */
export type StatusView = 'admin' | 'team';

/** The filters an administrator submits; empty strings are absent filters. */
export interface StatusFilters {
  legalEntityId: string;
  siteId: string;
}

export const EMPTY_STATUS_FILTERS: StatusFilters = { legalEntityId: '', siteId: '' };

/** The query of a request: only present filters, never an identity, a tenant or a time. */
export function statusQueryOf(
  view: StatusView,
  filters: StatusFilters,
): { siteId?: string; legalEntityId?: string } {
  const query: { siteId?: string; legalEntityId?: string } = {};
  if (view === 'admin' && filters.legalEntityId) query.legalEntityId = filters.legalEntityId;
  if (view === 'admin' && filters.siteId) query.siteId = filters.siteId;
  return query;
}

/** A refused or failed status, reduced to stable codes. */
export interface StatusFailure {
  kind: 'linkRequired' | 'forbidden' | 'rateLimited' | 'scopeNotFound' | 'general';
  messageKey: string;
  correlationId?: string;
}

export const STATUS_NETWORK_FAILURE: StatusFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; no parameter is ever shown. */
export function statusFailureOf(
  view: StatusView,
  response: Response,
  error: unknown,
): StatusFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const base = { correlationId: problem?.correlationId };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (response.status === 403) {
    return {
      ...base,
      kind: 'forbidden',
      messageKey:
        view === 'admin' ? 'operationsStatus.unauthorized' : 'teamOperations.unauthorized',
    };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'OPERATIONAL_STATUS_SCOPE_NOT_FOUND') {
    return {
      ...base,
      kind: 'scopeNotFound',
      messageKey: 'errors.OPERATIONAL_STATUS_SCOPE_NOT_FOUND',
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** "Given Family (number)" as plain text, never markup. */
export function personName(person: {
  givenNames: string;
  familyName: string;
  employeeNumber: string;
}): string {
  return `${person.givenNames} ${person.familyName} (${person.employeeNumber})`;
}

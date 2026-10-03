import type { Problem } from '@divalhr/api-client';

/** A refused or failed request, reduced to stable codes and safe values. */
export type Failure = {
  messageKey: string;
  code?: string;
  correlationId?: string;
  retryAfter?: number;
  reason?: string;
  column?: string;
};

const FILE_REASONS = new Set([
  'EMPTY',
  'ENCODING',
  'DELIMITER',
  'MALFORMED',
  'LINE_TOO_LONG',
  'TOO_MANY_COLUMNS',
  'COLUMN_UNKNOWN',
  'COLUMN_DUPLICATE',
  'COLUMN_MISSING',
  'NO_ROWS',
  'TOO_MANY_ROWS',
]);

export const NETWORK_FAILURE: Failure = { messageKey: 'errors.network' };

/** Maps a Problem response to a failure; unknown reasons and columns are dropped, not shown. */
export function failureOf(response: Response, error: unknown): Failure {
  const problem = error as Partial<Problem> | undefined;
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const retry = Number(response.headers.get('Retry-After'));
  const reason = typeof params.reason === 'string' ? params.reason : undefined;
  const column = params.column;
  const expired = problem?.code === 'IMPORT_NOT_COMMITTABLE' && params.status === 'EXPIRED';
  return {
    messageKey: expired
      ? 'employeeImport.expired'
      : problem?.code
        ? `errors.${problem.code}`
        : 'errors.generic',
    code: problem?.code,
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
    reason: reason && FILE_REASONS.has(reason) ? reason : undefined,
    column:
      typeof column === 'number' || (typeof column === 'string' && /^[a-z_]{1,32}$/u.test(column))
        ? String(column)
        : undefined,
  };
}

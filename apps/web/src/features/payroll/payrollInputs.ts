import type { Problem } from '@divalhr/api-client';
import type { StatusTone } from '../../ui/primitives';

/**
 * MVP-090A: time-based payroll inputs. The browser only chooses a legal entity and two dates and
 * shows the server's preview, weeks, counts, blockers and runs: it never derives eligibility, a
 * total or whether a period may be generated. Payroll inputs only: nothing here is a pay amount.
 * Nothing is kept in browser storage or put in a URL.
 */

export const PERIOD_FIELDS = ['legalEntityId', 'periodStart', 'periodEnd'] as const;
export type PeriodField = (typeof PERIOD_FIELDS)[number];
export type PeriodProblem = 'required' | 'order' | 'length';

export interface PeriodForm {
  legalEntityId: string;
  periodStart: string;
  periodEnd: string;
}

/** The longest supported period (inclusive days), as the contract states. */
export const MAX_PERIOD_DAYS = 31;

const DATE = /^\d{4}-\d{2}-\d{2}$/u;

function dayNumber(date: string): number | null {
  if (!DATE.test(date)) return null;
  const value = new Date(`${date}T00:00:00Z`).getTime();
  return Number.isNaN(value) ? null : Math.floor(value / 86_400_000);
}

/** The problems a form has before it is sent (the server decides everything else). */
export function periodProblemsOf(form: PeriodForm): Map<PeriodField, PeriodProblem> {
  const problems = new Map<PeriodField, PeriodProblem>();
  if (!form.legalEntityId) problems.set('legalEntityId', 'required');
  const start = dayNumber(form.periodStart);
  const end = dayNumber(form.periodEnd);
  if (start === null) problems.set('periodStart', 'required');
  if (end === null) problems.set('periodEnd', 'required');
  if (start !== null && end !== null) {
    if (end < start) problems.set('periodEnd', 'order');
    else if (end - start >= MAX_PERIOD_DAYS) problems.set('periodEnd', 'length');
  }
  return problems;
}

/** Today as a calendar date in a zone (the default period end). */
export function todayIn(zone: string | undefined): string {
  try {
    return new Intl.DateTimeFormat('en-CA', {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).format(new Date());
  } catch {
    return new Date().toISOString().slice(0, 10);
  }
}

/** The first day of the month of a calendar date. */
export function firstOfMonth(date: string): string {
  return `${date.slice(0, 8)}01`;
}

export type WeekStatus = 'LOCKED' | 'NOT_GENERATED' | 'NOT_LOCKED' | 'EVIDENCE_CHANGED' | 'EMPTY';

/** The week status tones; the text carries the meaning. */
export const WEEK_TONES: Record<WeekStatus, StatusTone> = {
  LOCKED: 'success',
  NOT_GENERATED: 'warning',
  NOT_LOCKED: 'warning',
  EVIDENCE_CHANGED: 'warning',
  EMPTY: 'neutral',
};

export interface PayrollFailure {
  kind:
    | 'validation'
    | 'notFound'
    | 'notReady'
    | 'stale'
    | 'exists'
    | 'notLatest'
    | 'locked'
    | 'notLocked'
    | 'tooLarge'
    | 'forbidden'
    | 'rateLimited'
    | 'idempotency'
    | 'general';
  messageKey: string;
  fields?: PeriodField[];
  correlationId?: string;
}

export const PAYROLL_NETWORK_FAILURE: PayrollFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

const CODES: Record<string, PayrollFailure['kind']> = {
  PAYROLL_INPUT_TARGET_NOT_FOUND: 'notFound',
  PAYROLL_INPUT_NOT_READY: 'notReady',
  PAYROLL_INPUT_PREVIEW_STALE: 'stale',
  PAYROLL_INPUT_RUN_EXISTS: 'exists',
  PAYROLL_INPUT_NOT_LATEST: 'notLatest',
  PAYROLL_INPUT_RUN_LOCKED: 'locked',
  PAYROLL_INPUT_NOT_LOCKED: 'notLocked',
  PAYROLL_INPUT_EXPORT_TOO_LARGE: 'tooLarge',
  IDEMPOTENCY_KEY_REUSED: 'idempotency',
};

/** Maps a Problem response; no param outside the field allow-list is ever shown. */
export function payrollFailureOf(response: Response, error: unknown): PayrollFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code ?? '';
  const params = (problem?.params ?? {}) as Record<string, unknown>;
  const base = { correlationId: problem?.correlationId };
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'payrollPreparation.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  const kind = CODES[code];
  if (kind) return { ...base, kind, messageKey: `errors.${code}` };
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const fields = new Set<PeriodField>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string' && (PERIOD_FIELDS as readonly string[]).includes(name)) {
          fields.add(name as PeriodField);
        }
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'payrollPreparation.validation',
      fields: PERIOD_FIELDS.filter((field) => fields.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/**
 * MVP-092: the reasons a superseding version can carry. OTHER alone needs a bounded plain-text
 * explanation; the server decides everything else (no link, markup, address or long number).
 */
export const SUPERSEDE_REASONS = [
  'UPSTREAM_CORRECTION',
  'UPSTREAM_REAPPROVAL',
  'SCOPE_CORRECTION',
  'OTHER',
] as const;
export type SupersedeReason = (typeof SUPERSEDE_REASONS)[number];

/** The longest explanation (characters), as the contract states. */
export const MAX_EXPLANATION = 200;

/** The explanation problem the browser can see before sending (the server is the authority). */
export function explanationProblemOf(
  reason: SupersedeReason,
  explanation: string,
): 'required' | 'length' | null {
  if (reason !== 'OTHER') return null;
  if (explanation.trim().length === 0) return 'required';
  return explanation.length > MAX_EXPLANATION ? 'length' : null;
}

/** The lifecycle tones; the text (OPEN or LOCKED) carries the meaning. */
export const LIFECYCLE_TONES: Record<'OPEN' | 'LOCKED', StatusTone> = {
  OPEN: 'warning',
  LOCKED: 'success',
};

/**
 * A safe attachment name: the server's approved name when it matches the approved grammar,
 * otherwise the fallback built from identifiers. Nothing else is ever used as a file name.
 */
export function attachmentName(header: string | null, fallback: string): string {
  const match = /filename="([A-Za-z0-9._-]{1,200})"/u.exec(header ?? '');
  return match?.[1] ?? fallback;
}

import type { Problem } from '@divalhr/api-client';

/**
 * MVP-091: payroll-input variance review. The browser only chooses a comparison run, two review
 * thresholds and a filter and shows the server's classifications, minutes and totals: it never
 * matches employees, classifies, computes a delta or decides what is material. Worked-time inputs
 * only: nothing here is a pay amount. Nothing is kept in browser storage or put in a URL.
 */

export const VIEWS = ['ALL', 'MATERIAL', 'NEW', 'MISSING', 'CHANGED', 'UNCHANGED'] as const;
export type VarianceView = (typeof VIEWS)[number];

export const CLASSIFICATIONS = ['NEW', 'MISSING', 'CHANGED', 'UNCHANGED'] as const;
export type Classification = (typeof CLASSIFICATIONS)[number];

export const DEFAULT_ABSOLUTE = '120';
export const DEFAULT_PERCENTAGE = '20';
export const MAX_ABSOLUTE = 10_080;
export const MAX_PERCENTAGE = 100;

export interface ThresholdForm {
  absolute: string;
  percentage: string;
}
export type ThresholdField = 'absoluteMinuteThreshold' | 'percentageThreshold';

const INTEGER = /^\d{1,6}$/u;

/** The problems a threshold form has before it is sent (the server decides everything else). */
export function thresholdProblemsOf(form: ThresholdForm): Set<ThresholdField> {
  const problems = new Set<ThresholdField>();
  if (!INTEGER.test(form.absolute) || Number(form.absolute) > MAX_ABSOLUTE) {
    problems.add('absoluteMinuteThreshold');
  }
  if (!INTEGER.test(form.percentage) || Number(form.percentage) > MAX_PERCENTAGE) {
    problems.add('percentageThreshold');
  }
  return problems;
}

/** The sign shown before a delta: an explicit plus or minus, nothing for zero. */
export function signOf(delta: number): string {
  if (delta > 0) return '+';
  if (delta < 0) return '−';
  return '';
}

/** Basis points as a percentage text in a language; formatting only, never arithmetic. */
export function formatBasisPoints(language: 'en' | 'fr', basisPoints: number): string {
  const sign = signOf(basisPoints);
  const text = new Intl.NumberFormat(language, {
    style: 'percent',
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(Math.abs(basisPoints) / 10_000);
  return `${sign}${text}`;
}

export interface VarianceFailure {
  kind: 'validation' | 'notFound' | 'forbidden' | 'rateLimited' | 'cursor' | 'general';
  messageKey: string;
  fields?: ThresholdField[];
  correlationId?: string;
}

export const VARIANCE_NETWORK_FAILURE: VarianceFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

const FIELDS: readonly ThresholdField[] = ['absoluteMinuteThreshold', 'percentageThreshold'];

/** Maps a Problem response; no param outside the field allow-list is ever shown. */
export function varianceFailureOf(response: Response, error: unknown): VarianceFailure {
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
  if (code === 'PAYROLL_VARIANCE_TARGET_NOT_FOUND') {
    return { ...base, kind: 'notFound', messageKey: 'errors.PAYROLL_VARIANCE_TARGET_NOT_FOUND' };
  }
  if (code === 'CURSOR_INVALID') {
    return { ...base, kind: 'cursor', messageKey: 'payrollPreparation.variance.unavailable' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw = params.fields;
    const named = new Set<string>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        if (typeof name === 'string') named.add(name);
      }
    }
    return {
      ...base,
      kind: 'validation',
      messageKey: 'payrollPreparation.variance.thresholds.problem.server',
      fields: FIELDS.filter((field) => named.has(field)),
    };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

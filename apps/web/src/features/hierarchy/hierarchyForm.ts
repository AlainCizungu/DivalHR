import type { CreateLegalEntity, CreateSite } from '@divalhr/api-client';
import { COUNTRIES, TIMEZONES_BY_COUNTRY } from '../admin/organizationForm';

/** Stable constraint codes shared with the API's params.fields[].constraint. */
export type Constraint =
  | 'REQUIRED'
  | 'LENGTH'
  | 'FORMAT'
  | 'RANGE'
  | 'NOT_SUPPORTED'
  | 'DUPLICATE_CODE'
  | 'BEFORE_START'
  | 'OUTSIDE_PARENT';

export type LegalEntityField = 'code' | 'name' | 'countryCode' | 'effectiveFrom' | 'effectiveTo';
export type SiteField = 'code' | 'name' | 'timezone' | 'effectiveFrom' | 'effectiveTo';
export const LEGAL_ENTITY_FIELDS: LegalEntityField[] = [
  'code',
  'name',
  'countryCode',
  'effectiveFrom',
  'effectiveTo',
];
export const SITE_FIELDS: SiteField[] = [
  'code',
  'name',
  'timezone',
  'effectiveFrom',
  'effectiveTo',
];

export type Errors<F extends string> = Partial<Record<F, Constraint>>;

export interface LegalEntityValues {
  code: string;
  name: string;
  countryCode: string;
  effectiveFrom: string;
  effectiveTo: string;
}

export interface SiteValues {
  code: string;
  name: string;
  timezone: string;
  effectiveFrom: string;
  effectiveTo: string;
}

const CODE = /^[A-Z0-9_-]{2,20}$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;
const MIN_DATE = '1900-01-01';
const MAX_DATE = '2999-12-31';

export function normalizeCode(raw: string): string {
  return raw.trim().toUpperCase();
}

function checkCode(raw: string): Constraint | undefined {
  const code = normalizeCode(raw);
  if (code.length === 0) return 'REQUIRED';
  if (code.length < 2 || code.length > 20) return 'LENGTH';
  return CODE.test(code) ? undefined : 'FORMAT';
}

function checkName(raw: string): Constraint | undefined {
  const name = raw.trim();
  // eslint-disable-next-line @typescript-eslint/no-misused-spread
  const length = [...name].length;
  if (length === 0) return 'REQUIRED';
  if (CONTROL.test(name)) return 'FORMAT';
  return length < 2 || length > 160 ? 'LENGTH' : undefined;
}

function checkDate(raw: string, required: boolean): Constraint | undefined {
  if (raw === '') return required ? 'REQUIRED' : undefined;
  if (!ISO_DATE.test(raw)) return 'FORMAT';
  const parsed = new Date(`${raw}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== raw) return 'FORMAT';
  return raw < MIN_DATE || raw > MAX_DATE ? 'RANGE' : undefined;
}

function checkPeriod<F extends string>(
  errors: Errors<F>,
  values: { effectiveFrom: string; effectiveTo: string },
) {
  const from = checkDate(values.effectiveFrom, true);
  const to = checkDate(values.effectiveTo, false);
  if (from) errors['effectiveFrom' as F] = from;
  if (to) errors['effectiveTo' as F] = to;
  if (!from && !to && values.effectiveTo !== '' && values.effectiveTo < values.effectiveFrom) {
    errors['effectiveTo' as F] = 'BEFORE_START';
  }
}

/** Client-side validation mirroring the server; the server stays authoritative. */
export function validateLegalEntity(values: LegalEntityValues): Errors<LegalEntityField> {
  const errors: Errors<LegalEntityField> = {};
  const code = checkCode(values.code);
  if (code) errors.code = code;
  const name = checkName(values.name);
  if (name) errors.name = name;
  if (!values.countryCode) errors.countryCode = 'REQUIRED';
  else if (!(COUNTRIES as readonly string[]).includes(values.countryCode))
    errors.countryCode = 'NOT_SUPPORTED';
  checkPeriod(errors, values);
  return errors;
}

export function validateSite(values: SiteValues, countryCode: string): Errors<SiteField> {
  const errors: Errors<SiteField> = {};
  const code = checkCode(values.code);
  if (code) errors.code = code;
  const name = checkName(values.name);
  if (name) errors.name = name;
  const zones = (TIMEZONES_BY_COUNTRY as Record<string, readonly string[] | undefined>)[
    countryCode
  ];
  if (!values.timezone) errors.timezone = 'REQUIRED';
  else if (!zones?.includes(values.timezone)) errors.timezone = 'NOT_SUPPORTED';
  checkPeriod(errors, values);
  return errors;
}

/** Normalized payloads, matching how the server fingerprints them. */
export function toLegalEntityPayload(values: LegalEntityValues): CreateLegalEntity {
  return {
    code: normalizeCode(values.code),
    name: values.name.trim(),
    countryCode: values.countryCode as CreateLegalEntity['countryCode'],
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}

export function toSitePayload(legalEntityId: string, values: SiteValues): CreateSite {
  return {
    legalEntityId,
    code: normalizeCode(values.code),
    name: values.name.trim(),
    timezone: values.timezone as CreateSite['timezone'],
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}

interface ProblemLike {
  code?: string;
  params?: Record<string, unknown> | { [key: string]: unknown };
}

/** Maps a server Problem to field errors; returns null when it is not field-specific. */
export function fieldErrorsFromProblem<F extends string>(
  problem: ProblemLike,
  fields: readonly F[],
): Errors<F> | null {
  const code = problem.code ?? '';
  const field = typeof problem.params?.field === 'string' ? (problem.params.field as F) : null;
  const single: Record<string, Constraint> = {
    DUPLICATE_LEGAL_ENTITY_CODE: 'DUPLICATE_CODE',
    DUPLICATE_SITE_CODE: 'DUPLICATE_CODE',
    EFFECTIVE_DATE_INVALID: 'BEFORE_START',
    SITE_PERIOD_OUTSIDE_LEGAL_ENTITY: 'OUTSIDE_PARENT',
    COUNTRY_NOT_SUPPORTED: 'NOT_SUPPORTED',
    TIMEZONE_NOT_SUPPORTED: 'NOT_SUPPORTED',
  };
  if (single[code] && field && fields.includes(field)) {
    return { [field]: single[code] } as Errors<F>;
  }
  if (code !== 'VALIDATION_FAILED') return null;
  const entries = Array.isArray(problem.params?.fields) ? problem.params.fields : [];
  const errors: Errors<F> = {};
  for (const entry of entries as { field?: string; constraint?: string }[]) {
    const name = entry.field as F;
    if (fields.includes(name) && entry.constraint) {
      errors[name] = entry.constraint as Constraint;
    }
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

import type {
  AssignSiteRegion,
  CreateCostCenter,
  CreateDepartment,
  CreateLegalEntity,
  CreateRegion,
  CreateSite,
  CreateTeam,
} from '@divalhr/api-client';
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
  | 'OUTSIDE_PARENT'
  | 'OUTSIDE_SITE'
  | 'OUTSIDE_LEGAL_ENTITY'
  | 'OUTSIDE_REGION'
  | 'OUTSIDE_DEPARTMENT'
  | 'OUTSIDE_COST_CENTER'
  | 'MISMATCH'
  | 'NOT_FOUND';

export type LegalEntityField = 'code' | 'name' | 'countryCode' | 'effectiveFrom' | 'effectiveTo';
export type SiteField = 'code' | 'name' | 'timezone' | 'regionId' | 'effectiveFrom' | 'effectiveTo';
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
  'regionId',
  'effectiveFrom',
  'effectiveTo',
];
/** Regions (MVP-002 Increment 3A) share the site-unit form shape beneath a legal entity. */
export type RegionField = 'code' | 'name' | 'effectiveFrom' | 'effectiveTo';
export const REGION_FIELDS: RegionField[] = ['code', 'name', 'effectiveFrom', 'effectiveTo'];
/** The first-assignment form has a single field. */
export type AssignmentField = 'regionId';
export const ASSIGNMENT_FIELDS: AssignmentField[] = ['regionId'];
/** Departments and cost centers share one form shape. */
export type SiteUnitField = 'code' | 'name' | 'effectiveFrom' | 'effectiveTo';
export const SITE_UNIT_FIELDS: SiteUnitField[] = ['code', 'name', 'effectiveFrom', 'effectiveTo'];
export type SiteUnitKind = 'department' | 'costCenter';
/** Teams (MVP-002 Increment 3B) sit beneath exactly one department or cost center. */
export type TeamField = SiteUnitField;
export const TEAM_FIELDS: TeamField[] = SITE_UNIT_FIELDS;
export type TeamParentKind = SiteUnitKind;

export type Errors<F extends string> = Partial<Record<F, Constraint>>;

export interface LegalEntityValues {
  code: string;
  name: string;
  countryCode: string;
  effectiveFrom: string;
  effectiveTo: string;
}

export interface SiteUnitValues {
  code: string;
  name: string;
  effectiveFrom: string;
  effectiveTo: string;
}

export interface SiteValues {
  code: string;
  name: string;
  timezone: string;
  /** Optional region of the same legal entity; '' for none. */
  regionId: string;
  effectiveFrom: string;
  effectiveTo: string;
}

export type RegionValues = SiteUnitValues;
export type TeamValues = SiteUnitValues;

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

export function validateRegion(values: RegionValues): Errors<RegionField> {
  return validateSiteUnit(values);
}

export function validateTeam(values: TeamValues): Errors<TeamField> {
  return validateSiteUnit(values);
}

export function validateAssignment(regionId: string): Errors<AssignmentField> {
  return regionId === '' ? { regionId: 'REQUIRED' } : {};
}

export function validateSiteUnit(values: SiteUnitValues): Errors<SiteUnitField> {
  const errors: Errors<SiteUnitField> = {};
  const code = checkCode(values.code);
  if (code) errors.code = code;
  const name = checkName(values.name);
  if (name) errors.name = name;
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

/** Without a region the payload is exactly the pre-region one (no regionId property). */
export function toSitePayload(legalEntityId: string, values: SiteValues): CreateSite {
  return {
    legalEntityId,
    ...(values.regionId === '' ? {} : { regionId: values.regionId }),
    code: normalizeCode(values.code),
    name: values.name.trim(),
    timezone: values.timezone as CreateSite['timezone'],
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}

export function toRegionPayload(legalEntityId: string, values: RegionValues): CreateRegion {
  return {
    legalEntityId,
    code: normalizeCode(values.code),
    name: values.name.trim(),
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}

export function toAssignmentPayload(regionId: string): AssignSiteRegion {
  return { regionId };
}

export function toSiteUnitPayload(
  siteId: string,
  values: SiteUnitValues,
): CreateDepartment & CreateCostCenter {
  return {
    siteId,
    code: normalizeCode(values.code),
    name: values.name.trim(),
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
}

/**
 * A create-team body naming exactly one parent. The other parent property is omitted, which the
 * API treats like null; the union stays discriminated and assignable to the generated CreateTeam.
 */
export type TeamPayload = Omit<CreateTeam, 'departmentId' | 'costCenterId'> &
  ({ departmentId: string } | { costCenterId: string });

export function toTeamPayload(
  parentKind: TeamParentKind,
  parentId: string,
  values: TeamValues,
): TeamPayload {
  const rest = {
    code: normalizeCode(values.code),
    name: values.name.trim(),
    effectiveFrom: values.effectiveFrom,
    effectiveTo: values.effectiveTo === '' ? null : values.effectiveTo,
  };
  return parentKind === 'department'
    ? { departmentId: parentId, ...rest }
    : { costCenterId: parentId, ...rest };
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
    DUPLICATE_DEPARTMENT_CODE: 'DUPLICATE_CODE',
    DUPLICATE_COST_CENTER_CODE: 'DUPLICATE_CODE',
    DEPARTMENT_PERIOD_OUTSIDE_SITE: 'OUTSIDE_SITE',
    COST_CENTER_PERIOD_OUTSIDE_SITE: 'OUTSIDE_SITE',
    DUPLICATE_REGION_CODE: 'DUPLICATE_CODE',
    REGION_PERIOD_OUTSIDE_LEGAL_ENTITY: 'OUTSIDE_LEGAL_ENTITY',
    REGION_NOT_FOUND: 'NOT_FOUND',
    SITE_PERIOD_OUTSIDE_REGION: 'OUTSIDE_REGION',
    SITE_REGION_LEGAL_ENTITY_MISMATCH: 'MISMATCH',
    DUPLICATE_TEAM_CODE: 'DUPLICATE_CODE',
    TEAM_PERIOD_OUTSIDE_DEPARTMENT: 'OUTSIDE_DEPARTMENT',
    TEAM_PERIOD_OUTSIDE_COST_CENTER: 'OUTSIDE_COST_CENTER',
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

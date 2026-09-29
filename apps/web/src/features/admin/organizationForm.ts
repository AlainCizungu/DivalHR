import type { CreateOrganization } from '@divalhr/api-client';

/** Allow-list mirrored from docs/API-SPEC.yaml; the server re-validates everything. */
export const COUNTRIES = ['CD'] as const;
export const LOCALES = ['fr', 'en'] as const;
export const TIMEZONES_BY_COUNTRY: Record<(typeof COUNTRIES)[number], readonly string[]> = {
  CD: ['Africa/Kinshasa', 'Africa/Lubumbashi'],
};
export const CURRENCIES_BY_COUNTRY: Record<(typeof COUNTRIES)[number], readonly string[]> = {
  CD: ['CDF', 'USD'],
};

export type FieldName = 'name' | 'countryCode' | 'defaultLocale' | 'timezone' | 'currencies';
export const FIELD_ORDER: FieldName[] = [
  'name',
  'countryCode',
  'defaultLocale',
  'timezone',
  'currencies',
];

/** Stable constraint codes shared with the API's params.fields[].constraint. */
export type Constraint = 'REQUIRED' | 'LENGTH' | 'FORMAT' | 'DUPLICATE' | 'NOT_SUPPORTED';
export type FieldErrors = Partial<Record<FieldName, Constraint>>;

export interface FormValues {
  name: string;
  countryCode: string;
  defaultLocale: string;
  timezone: string;
  currencies: string[];
}

// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001f\u007f]/;

/** Client-side validation mirroring the server rules (the server stays authoritative). */
export function validate(values: FormValues): FieldErrors {
  const errors: FieldErrors = {};
  const name = values.name.trim();
  // Code points, matching the server's codePointCount rule for the 2-160 limit.
  // eslint-disable-next-line @typescript-eslint/no-misused-spread
  const length = [...name].length;
  if (length === 0) errors.name = 'REQUIRED';
  else if (CONTROL.test(name)) errors.name = 'FORMAT';
  else if (length < 2 || length > 160) errors.name = 'LENGTH';

  const country = values.countryCode as (typeof COUNTRIES)[number];
  if (!values.countryCode) errors.countryCode = 'REQUIRED';
  else if (!COUNTRIES.includes(country)) errors.countryCode = 'NOT_SUPPORTED';

  if (!values.defaultLocale) errors.defaultLocale = 'REQUIRED';
  else if (!(LOCALES as readonly string[]).includes(values.defaultLocale))
    errors.defaultLocale = 'NOT_SUPPORTED';

  const zones = TIMEZONES_BY_COUNTRY[country] as readonly string[] | undefined;
  if (!values.timezone) errors.timezone = 'REQUIRED';
  else if (zones && !zones.includes(values.timezone)) errors.timezone = 'NOT_SUPPORTED';

  const currencies = CURRENCIES_BY_COUNTRY[country] as readonly string[] | undefined;
  if (values.currencies.length === 0) errors.currencies = 'REQUIRED';
  else if (new Set(values.currencies).size !== values.currencies.length)
    errors.currencies = 'DUPLICATE';
  else if (currencies && values.currencies.some((c) => !currencies.includes(c)))
    errors.currencies = 'NOT_SUPPORTED';
  return errors;
}

/** Normalized payload: trimmed name and sorted currencies, as the server fingerprints it. */
export function toPayload(values: FormValues): CreateOrganization {
  return {
    name: values.name.trim(),
    countryCode: values.countryCode as CreateOrganization['countryCode'],
    defaultLocale: values.defaultLocale as CreateOrganization['defaultLocale'],
    timezone: values.timezone as CreateOrganization['timezone'],
    currencies: [...values.currencies].sort() as CreateOrganization['currencies'],
  };
}

const NOT_SUPPORTED_FIELD: Record<string, FieldName> = {
  COUNTRY_NOT_SUPPORTED: 'countryCode',
  LOCALE_NOT_SUPPORTED: 'defaultLocale',
  TIMEZONE_NOT_SUPPORTED: 'timezone',
  CURRENCY_NOT_SUPPORTED: 'currencies',
};

/** Maps a server Problem to field errors; returns null when it is not field-specific. */
export function fieldErrorsFromProblem(problem: {
  code?: string;
  params?: Record<string, unknown> | { [key: string]: unknown };
}): FieldErrors | null {
  const code = problem.code ?? '';
  const notSupported = NOT_SUPPORTED_FIELD[code];
  if (notSupported) return { [notSupported]: 'NOT_SUPPORTED' };
  if (code !== 'VALIDATION_FAILED') return null;
  const fields = Array.isArray(problem.params?.fields) ? problem.params.fields : [];
  const errors: FieldErrors = {};
  for (const entry of fields as { field?: string; constraint?: string }[]) {
    const field = entry.field as FieldName;
    if (FIELD_ORDER.includes(field) && entry.constraint) {
      errors[field] = entry.constraint as Constraint;
    }
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

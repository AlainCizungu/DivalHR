import type {
  PartnerAccountAction,
  PartnerAccountReasonCode,
  PartnerAccountStatus,
  PartnerBilingualText,
  PartnerProductAction,
  PartnerProductContent,
  PartnerProductStatus,
  PartnerProductType,
} from '@divalhr/api-client';

/**
 * MVP-120E (Issue #217, ADR 0044): partner accounts and the controlled product catalogue.
 * Closed vocabularies mirror the contract; the server stays the authority for every transition.
 * Nothing here reads or stores a contact, a licence reference, a checklist result or a note.
 */

export const PARTNER_STATUSES: readonly PartnerAccountStatus[] = [
  'DRAFT',
  'ACTIVE',
  'SUSPENDED',
  'CLOSED',
];
export const PARTNER_TONES = {
  DRAFT: 'info',
  ACTIVE: 'success',
  SUSPENDED: 'warning',
  CLOSED: 'neutral',
} as const;

export const PARTNER_ACTIONS: readonly PartnerAccountAction[] = [
  'ACTIVATE',
  'SUSPEND',
  'REACTIVATE',
  'CLOSE',
  'AMEND_SCOPE',
];
/** Partner actions that need an explicit confirmation, and those that take a reason. */
export const PARTNER_CONFIRMED: ReadonlySet<PartnerAccountAction> = new Set([
  'ACTIVATE',
  'REACTIVATE',
  'CLOSE',
]);
export const PARTNER_REASONED: ReadonlySet<PartnerAccountAction> = new Set(['SUSPEND', 'CLOSE']);
export const PARTNER_REASON_REQUIRED: ReadonlySet<PartnerAccountAction> = new Set(['SUSPEND']);

export const REASON_CODES: readonly PartnerAccountReasonCode[] = [
  'COMPLIANCE_REVIEW',
  'COMMERCIAL_HOLD',
  'PARTNER_REQUEST',
  'REGULATORY_RESTRICTION',
  'SECURITY_CONCERN',
  'PROGRAM_ENDED',
  'OTHER',
];
export const ACCOUNT_NOTE_MAX = 500;

export const PRODUCT_TYPES: readonly PartnerProductType[] = [
  'SALARY_ADVANCE',
  'EMPLOYEE_LOAN',
  'HEALTH_INSURANCE',
  'LIFE_INSURANCE',
  'OTHER_INSURANCE',
  'TELECOM_VOICE_DATA',
  'DEVICE_FINANCING',
  'HEALTHCARE',
  'WELLNESS',
  'TRAINING',
  'OTHER_BENEFIT',
];
/** Credit-like products need monetary and duration limits before they can be submitted. */
export const NEEDS_LIMITS: ReadonlySet<PartnerProductType> = new Set([
  'SALARY_ADVANCE',
  'EMPLOYEE_LOAN',
]);

export const PRODUCT_STATUSES: readonly PartnerProductStatus[] = [
  'DRAFT',
  'UNDER_REVIEW',
  'ACTIVE',
  'PAUSED',
  'RETIRED',
];
export const PRODUCT_TONES = {
  DRAFT: 'info',
  UNDER_REVIEW: 'info',
  ACTIVE: 'success',
  PAUSED: 'warning',
  RETIRED: 'neutral',
} as const;

export const PRODUCT_ACTIONS: readonly PartnerProductAction[] = [
  'SUBMIT',
  'RETURN',
  'PUBLISH',
  'PAUSE',
  'RESUME',
  'RETIRE',
];
export const PRODUCT_CONFIRMED: ReadonlySet<PartnerProductAction> = new Set([
  'PUBLISH',
  'RESUME',
  'RETIRE',
]);
export const PRODUCT_REASONED: ReadonlySet<PartnerProductAction> = new Set(['PAUSE', 'RETIRE']);

/** Currencies offered now (contract: CDF and USD). */
export const CURRENCIES: readonly string[] = ['CDF', 'USD'];

export const PRODUCT_CODE = /^[A-Z0-9][A-Z0-9_-]{1,39}$/u;
const AMOUNT = /^[0-9]{1,16}(\.[0-9]{1,2})?$/u;
const HTTPS = /^https:\/\/\S+$/u;

export const partnerPath = (reference: string) =>
  `/admin/partners/${encodeURIComponent(reference)}`;
export const productPath = (reference: string, productCode: string) =>
  `${partnerPath(reference)}/products/${encodeURIComponent(productCode)}`;

/** The editable form state of a product version: strings only, converted on save. */
export interface ContentForm {
  nameEn: string;
  nameFr: string;
  summaryEn: string;
  summaryFr: string;
  eligibilityEn: string;
  eligibilityFr: string;
  disclosureEn: string;
  disclosureFr: string;
  infoUrl: string;
  effectiveDate: string;
  endDate: string;
  minMonths: string;
  maxMonths: string;
  countries: string[];
  currencies: Record<string, { on: boolean; min: string; max: string }>;
}

export const emptyForm = (): ContentForm => ({
  nameEn: '',
  nameFr: '',
  summaryEn: '',
  summaryFr: '',
  eligibilityEn: '',
  eligibilityFr: '',
  disclosureEn: '',
  disclosureFr: '',
  infoUrl: '',
  effectiveDate: '',
  endDate: '',
  minMonths: '',
  maxMonths: '',
  countries: [],
  currencies: Object.fromEntries(CURRENCIES.map((c) => [c, { on: false, min: '', max: '' }])),
});

export function formFromContent(content: PartnerProductContent): ContentForm {
  const form = emptyForm();
  form.nameEn = content.name.en ?? '';
  form.nameFr = content.name.fr ?? '';
  form.summaryEn = content.summary.en ?? '';
  form.summaryFr = content.summary.fr ?? '';
  form.eligibilityEn = content.eligibility.en ?? '';
  form.eligibilityFr = content.eligibility.fr ?? '';
  form.disclosureEn = content.disclosure.en ?? '';
  form.disclosureFr = content.disclosure.fr ?? '';
  form.infoUrl = content.infoUrl ?? '';
  form.effectiveDate = content.effectiveDate ?? '';
  form.endDate = content.endDate ?? '';
  form.minMonths = content.minMonths === null ? '' : String(content.minMonths);
  form.maxMonths = content.maxMonths === null ? '' : String(content.maxMonths);
  form.countries = [...content.countries];
  for (const limit of content.currencies) {
    form.currencies[limit.currency] = {
      on: true,
      min: limit.minAmount ?? '',
      max: limit.maxAmount ?? '',
    };
  }
  return form;
}

const text = (value: string): string | null => {
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
};
const pair = (en: string, fr: string): PartnerBilingualText => ({ en: text(en), fr: text(fr) });
const whole = (value: string): number | null => (value.trim() === '' ? null : Number(value));

export function contentFromForm(form: ContentForm): PartnerProductContent {
  return {
    name: pair(form.nameEn, form.nameFr),
    summary: pair(form.summaryEn, form.summaryFr),
    eligibility: pair(form.eligibilityEn, form.eligibilityFr),
    disclosure: pair(form.disclosureEn, form.disclosureFr),
    infoUrl: text(form.infoUrl),
    effectiveDate: text(form.effectiveDate),
    endDate: text(form.endDate),
    minMonths: whole(form.minMonths),
    maxMonths: whole(form.maxMonths),
    countries: [...form.countries],
    currencies: CURRENCIES.filter((c) => form.currencies[c]?.on).map((currency) => ({
      currency,
      minAmount: text(form.currencies[currency]?.min ?? ''),
      maxAmount: text(form.currencies[currency]?.max ?? ''),
    })),
  };
}

export type ContentErrors = Partial<Record<string, string>>;

const lengthOf = (value: string, min: number, max: number): string | null => {
  const length = value.trim().length;
  if (length === 0) return null; // a language may be left empty while drafting
  return length < min || length > max ? 'LENGTH' : null;
};

/**
 * Format checks that need no server: lengths, https address, date order, amounts, month range.
 * Completeness (both languages, scope, limits for credit-like products) is checked by the server
 * when a version is submitted; a draft may be incomplete.
 */
export function validateContent(form: ContentForm): ContentErrors {
  const errors: ContentErrors = {};
  const set = (field: string, constraint: string | null) => {
    if (constraint) errors[field] = constraint;
  };
  set('nameEn', lengthOf(form.nameEn, 2, 120));
  set('nameFr', lengthOf(form.nameFr, 2, 120));
  set('summaryEn', lengthOf(form.summaryEn, 10, 300));
  set('summaryFr', lengthOf(form.summaryFr, 10, 300));
  set('eligibilityEn', lengthOf(form.eligibilityEn, 10, 500));
  set('eligibilityFr', lengthOf(form.eligibilityFr, 10, 500));
  set('disclosureEn', lengthOf(form.disclosureEn, 20, 2000));
  set('disclosureFr', lengthOf(form.disclosureFr, 20, 2000));
  const url = form.infoUrl.trim();
  if (url !== '')
    set('infoUrl', HTTPS.test(url) && url.length >= 10 && url.length <= 300 ? null : 'FORMAT');
  if (form.effectiveDate && form.endDate && form.endDate < form.effectiveDate) {
    set('endDate', 'RANGE');
  }
  const min = form.minMonths.trim();
  const max = form.maxMonths.trim();
  const month = (v: string) => /^[0-9]{1,3}$/u.test(v) && Number(v) >= 1 && Number(v) <= 600;
  if (min !== '' || max !== '') {
    if (min === '' || max === '' || !month(min) || !month(max) || Number(max) < Number(min)) {
      set('minMonths', 'RANGE');
    }
  }
  for (const currency of CURRENCIES) {
    const row = form.currencies[currency];
    if (!row?.on) continue;
    const lo = row.min.trim();
    const hi = row.max.trim();
    if (
      (lo !== '' && !AMOUNT.test(lo)) ||
      (hi !== '' && !AMOUNT.test(hi)) ||
      (lo !== '' && hi !== '' && Number(hi) < Number(lo))
    ) {
      set(`currency-${currency}`, 'FORMAT');
    }
  }
  return errors;
}

/** A server field path such as `content.name.en` mapped onto a form field id, or `body`. */
export function formFieldOf(path: string): string {
  const map: Record<string, string> = {
    'content.name.en': 'nameEn',
    'content.name.fr': 'nameFr',
    'content.summary.en': 'summaryEn',
    'content.summary.fr': 'summaryFr',
    'content.eligibility.en': 'eligibilityEn',
    'content.eligibility.fr': 'eligibilityFr',
    'content.disclosure.en': 'disclosureEn',
    'content.disclosure.fr': 'disclosureFr',
    'content.infoUrl': 'infoUrl',
    'content.effectiveDate': 'effectiveDate',
    'content.endDate': 'endDate',
    'content.minMonths': 'minMonths',
    'content.maxMonths': 'minMonths',
    'content.countries': 'countries',
    'content.currencies': 'currencies',
    productCode: 'productCode',
    productType: 'productType',
  };
  return map[path] ?? 'body';
}

const SERVER_CONSTRAINTS: ReadonlySet<string> = new Set([
  'REQUIRED',
  'LENGTH',
  'FORMAT',
  'RANGE',
  'LIMIT',
  'DUPLICATE',
  'UNKNOWN_PROPERTY',
]);

/** Field errors from a refusal's `params.fields`: names and constraint codes only. */
export function contentErrorsFromProblem(problem: unknown): ContentErrors | null {
  const fields = (problem as { params?: { fields?: unknown } } | undefined)?.params?.fields;
  if (!Array.isArray(fields)) return null;
  const errors: ContentErrors = {};
  for (const entry of fields as { field?: unknown; constraint?: unknown }[]) {
    if (typeof entry.field !== 'string' || typeof entry.constraint !== 'string') continue;
    const field = formFieldOf(entry.field);
    errors[field] ??= SERVER_CONSTRAINTS.has(entry.constraint) ? entry.constraint : 'FORMAT';
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

/** Picks the interface language's text, falling back to the other one (never empty strings). */
export function localized(text: PartnerBilingualText, language: string): string {
  const preferred = language.startsWith('fr') ? text.fr : text.en;
  return preferred ?? text.en ?? text.fr ?? '';
}

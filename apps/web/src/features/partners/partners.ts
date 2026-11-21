import type {
  PartnerApiAvailability,
  PartnerApplicationStatus,
  PartnerApplicationSubmission,
  PartnerCapability,
  PartnerCategory,
  PartnerCheckCode,
  PartnerCheckResult,
  PartnerLanguage,
  PartnerReasonCode,
  Problem,
} from '@divalhr/api-client';

/**
 * MVP-120D (Issue #215, ADR 0043): the partner application and its platform review. The browser
 * shows what the server returned and sends only what the contract lets it choose. An application
 * is a request to talk: nothing here lists a partner, creates a credential, opens an integration
 * or exposes employee data. Internal review notes never enter a URL, analytics, browser storage
 * or a log; the honeypot is never part of the form state.
 */

export const CATEGORIES: readonly PartnerCategory[] = [
  'BANK',
  'MICROFINANCE',
  'INSURANCE',
  'HEALTH_BENEFITS',
  'TELECOM',
  'MOBILE_MONEY_PAYMENT',
  'PENSION_SAVINGS',
  'OTHER_REGULATED_PROVIDER',
];

/** Regulatory fields are required when the primary category is one of these (D120D-7). */
export const REGULATED: ReadonlySet<PartnerCategory> = new Set<PartnerCategory>([
  'BANK',
  'MICROFINANCE',
  'INSURANCE',
  'TELECOM',
  'MOBILE_MONEY_PAYMENT',
  'PENSION_SAVINGS',
]);

export const CAPABILITIES: readonly PartnerCapability[] = [
  'SALARY_ADVANCE',
  'EMPLOYEE_LOANS',
  'HEALTH_INSURANCE',
  'LIFE_INSURANCE',
  'ACCIDENT_INSURANCE',
  'HEALTHCARE_BENEFITS',
  'AIRTIME_DATA_BENEFITS',
  'SMS_USSD_SERVICES',
  'MOBILE_MONEY_DISBURSEMENT',
  'PAYMENT_COLLECTION',
  'PENSION_SAVINGS',
  'OTHER_EMPLOYEE_SERVICE',
];

export const LANGUAGES: readonly PartnerLanguage[] = ['en', 'fr'];
export const API_AVAILABILITY: readonly PartnerApiAvailability[] = ['YES', 'PLANNED', 'NO'];

/** Countries offered for registration and operation (ISO 3166-1 alpha-2). */
export const COUNTRIES: readonly string[] = [
  'AO', 'BE', 'BI', 'CD', 'CG', 'CI', 'CM', 'EG', 'ET', 'FR', 'GA', 'GH', 'KE', 'MA', 'NG',
  'RW', 'SN', 'TN', 'TZ', 'UG', 'ZA', 'ZM',
]; // prettier-ignore

/** Versions recorded with the applicant's acknowledgment (ADR 0043). */
export const PRIVACY_NOTICE_VERSION = '2026-10';
export const CONSENT_VERSION = '2026-10';

export const MAX_ADDITIONAL = 3;

export interface ApplyValues {
  legalName: string;
  tradingName: string;
  countryOfRegistration: string;
  countriesOfOperation: string[];
  registrationReference: string;
  primaryCategory: PartnerCategory | '';
  additionalCategories: PartnerCategory[];
  requestedCapabilities: PartnerCapability[];
  website: string;
  contactName: string;
  contactRole: string;
  businessEmail: string;
  businessPhone: string;
  supportedLanguages: PartnerLanguage[];
  apiAvailability: PartnerApiAvailability | '';
  regulatorName: string;
  licenceReference: string;
  serviceSummary: string;
  privacyContactEmail: string;
  acknowledgment: boolean;
}

export type ApplyField = keyof ApplyValues | 'body';
export type ApplyErrors = Partial<Record<ApplyField, string>>;

export const FIELD_ORDER: readonly ApplyField[] = [
  'legalName',
  'tradingName',
  'countryOfRegistration',
  'countriesOfOperation',
  'registrationReference',
  'primaryCategory',
  'additionalCategories',
  'regulatorName',
  'licenceReference',
  'requestedCapabilities',
  'serviceSummary',
  'website',
  'apiAvailability',
  'supportedLanguages',
  'contactName',
  'contactRole',
  'businessEmail',
  'businessPhone',
  'privacyContactEmail',
  'acknowledgment',
  'body',
];

export const emptyValues = (locale: PartnerLanguage): ApplyValues => ({
  legalName: '',
  tradingName: '',
  countryOfRegistration: '',
  countriesOfOperation: [],
  registrationReference: '',
  primaryCategory: '',
  additionalCategories: [],
  requestedCapabilities: [],
  website: 'https://',
  contactName: '',
  contactRole: '',
  businessEmail: '',
  businessPhone: '',
  supportedLanguages: [locale],
  apiAvailability: '',
  regulatorName: '',
  licenceReference: '',
  serviceSummary: '',
  privacyContactEmail: '',
  acknowledgment: false,
});

export function isRegulated(category: PartnerCategory | ''): boolean {
  return category !== '' && REGULATED.has(category);
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/u;
const WEBSITE = /^https:\/\/[^\s]+$/u;
const PHONE = /^\+[1-9][0-9]{6,14}$/u;

const between = (value: string, min: number, max: number): string | null => {
  const length = value.trim().length;
  if (length === 0) return 'REQUIRED';
  return length < min || length > max ? 'LENGTH' : null;
};

/** Client-side checks mirror the contract; the server stays the authority. */
export function validate(values: ApplyValues): ApplyErrors {
  const errors: ApplyErrors = {};
  const set = (field: ApplyField, constraint: string | null) => {
    if (constraint) errors[field] = constraint;
  };
  set('legalName', between(values.legalName, 2, 200));
  if (values.tradingName.trim()) set('tradingName', between(values.tradingName, 2, 200));
  set('countryOfRegistration', values.countryOfRegistration ? null : 'REQUIRED');
  set('countriesOfOperation', values.countriesOfOperation.length > 0 ? null : 'REQUIRED');
  set('registrationReference', between(values.registrationReference, 2, 100));
  set('primaryCategory', values.primaryCategory ? null : 'REQUIRED');
  if (values.additionalCategories.length > MAX_ADDITIONAL) set('additionalCategories', 'LENGTH');
  if (isRegulated(values.primaryCategory)) {
    set('regulatorName', between(values.regulatorName, 2, 200));
    set('licenceReference', between(values.licenceReference, 2, 100));
  }
  set('requestedCapabilities', values.requestedCapabilities.length > 0 ? null : 'REQUIRED');
  set('serviceSummary', between(values.serviceSummary, 20, 1000));
  const website = values.website.trim();
  set(
    'website',
    website === '' || website === 'https://'
      ? 'REQUIRED'
      : WEBSITE.test(website) && website.length >= 10 && website.length <= 300
        ? null
        : 'FORMAT',
  );
  set('apiAvailability', values.apiAvailability ? null : 'REQUIRED');
  set('supportedLanguages', values.supportedLanguages.length > 0 ? null : 'REQUIRED');
  set('contactName', between(values.contactName, 2, 150));
  set('contactRole', between(values.contactRole, 2, 120));
  const email = (value: string) =>
    value.trim() === '' ? 'REQUIRED' : EMAIL.test(value.trim()) ? null : 'FORMAT';
  set('businessEmail', email(values.businessEmail));
  if (values.businessPhone.trim()) {
    set('businessPhone', PHONE.test(values.businessPhone.trim()) ? null : 'FORMAT');
  }
  set('privacyContactEmail', email(values.privacyContactEmail));
  set('acknowledgment', values.acknowledgment ? null : 'REQUIRED');
  return errors;
}

/**
 * The request body. The honeypot is sent empty and never read from the form: it is not part of
 * the state, so it can be neither redisplayed nor persisted.
 */
export function toSubmission(
  values: ApplyValues,
  locale: PartnerLanguage,
): PartnerApplicationSubmission {
  const regulated = isRegulated(values.primaryCategory);
  const optional = (value: string) => (value.trim() === '' ? null : value.trim());
  return {
    legalName: values.legalName.trim(),
    tradingName: optional(values.tradingName),
    countryOfRegistration: values.countryOfRegistration,
    countriesOfOperation: values.countriesOfOperation,
    registrationReference: values.registrationReference.trim(),
    primaryCategory: values.primaryCategory as PartnerCategory,
    additionalCategories: values.additionalCategories.filter(
      (category) => category !== values.primaryCategory,
    ),
    requestedCapabilities: values.requestedCapabilities,
    website: values.website.trim(),
    contactName: values.contactName.trim(),
    contactRole: values.contactRole.trim(),
    businessEmail: values.businessEmail.trim(),
    businessPhone: optional(values.businessPhone),
    supportedLanguages: values.supportedLanguages,
    apiAvailability: values.apiAvailability as PartnerApiAvailability,
    regulatorName: regulated ? optional(values.regulatorName) : null,
    licenceReference: regulated ? optional(values.licenceReference) : null,
    serviceSummary: values.serviceSummary.trim(),
    privacyContactEmail: values.privacyContactEmail.trim(),
    acknowledgment: values.acknowledgment,
    privacyNoticeVersion: PRIVACY_NOTICE_VERSION,
    consentVersion: CONSENT_VERSION,
    locale,
    faxNumber: '',
  };
}

const FIELD_SET: ReadonlySet<string> = new Set(FIELD_ORDER);
const CONSTRAINTS: ReadonlySet<string> = new Set([
  'REQUIRED',
  'LENGTH',
  'FORMAT',
  'RANGE',
  'DUPLICATE',
  'UNKNOWN_PROPERTY',
]);

/** Field errors from a PARTNER_APPLICATION_INVALID problem: names and constraint codes only. */
export function fieldErrorsFromProblem(problem: Partial<Problem> | undefined): ApplyErrors | null {
  const fields = (problem?.params as { fields?: unknown } | undefined)?.fields;
  if (!Array.isArray(fields)) return null;
  const errors: ApplyErrors = {};
  for (const entry of fields as { field?: unknown; constraint?: unknown }[]) {
    if (typeof entry.field !== 'string' || typeof entry.constraint !== 'string') continue;
    const field = FIELD_SET.has(entry.field) ? (entry.field as ApplyField) : 'body';
    errors[field] ??= CONSTRAINTS.has(entry.constraint) ? entry.constraint : 'FORMAT';
  }
  return Object.keys(errors).length > 0 ? errors : null;
}

/** Closed set of codes this feature renders; each has an EN and FR message under `errors`. */
export const PARTNER_ERROR_CODES = [
  'PARTNER_APPLICATION_INVALID',
  'PARTNER_APPLICATION_CONSENT_REQUIRED',
  'PARTNER_APPLICATION_IDEMPOTENCY_REUSED',
  'PARTNER_APPLICATION_RATE_LIMITED',
  'PARTNER_APPLICATION_NOT_FOUND',
  'PARTNER_APPLICATION_TRANSITION_INVALID',
  'PARTNER_APPLICATION_TERMINAL',
  'PARTNER_APPLICATION_CHECKLIST_INCOMPLETE',
  'PARTNER_APPLICATION_DUPLICATE',
  'CURSOR_INVALID',
  'RATE_LIMITED',
  'MFA_REQUIRED',
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'IDEMPOTENCY_KEY_REUSED',
] as const;
const CODE_SET: ReadonlySet<string> = new Set(PARTNER_ERROR_CODES);

export interface PartnerFailure {
  kind: 'invalid' | 'rateLimited' | 'forbidden' | 'notFound' | 'stale' | 'general';
  messageKey: string;
  code?: string;
  params?: Record<string, unknown>;
  retryAfter?: number;
  correlationId?: string;
}

export const NETWORK_FAILURE: PartnerFailure = { kind: 'general', messageKey: 'errors.network' };

/** Maps a Problem response; only contract codes are shown, never anything the server echoes. */
export function partnerFailureOf(response: Response, error: unknown): PartnerFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code && CODE_SET.has(code)) {
    const kind: PartnerFailure['kind'] =
      code === 'PARTNER_APPLICATION_INVALID' || code === 'PARTNER_APPLICATION_CONSENT_REQUIRED'
        ? 'invalid'
        : code.endsWith('RATE_LIMITED')
          ? 'rateLimited'
          : code === 'PARTNER_APPLICATION_NOT_FOUND'
            ? 'notFound'
            : code === 'MFA_REQUIRED' || code === 'ACCESS_DENIED' || response.status === 403
              ? 'forbidden'
              : code === 'PARTNER_APPLICATION_TRANSITION_INVALID' ||
                  code === 'PARTNER_APPLICATION_TERMINAL' ||
                  code === 'PARTNER_APPLICATION_IDEMPOTENCY_REUSED' ||
                  code === 'IDEMPOTENCY_KEY_REUSED'
                ? 'stale'
                : 'general';
    return { ...base, kind, code, messageKey: `errors.${code}` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.PARTNER_APPLICATION_RATE_LIMITED' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'errors.ACCESS_DENIED' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** Review. */
export const STATUSES: readonly PartnerApplicationStatus[] = [
  'SUBMITTED',
  'UNDER_REVIEW',
  'MORE_INFORMATION_REQUIRED',
  'APPROVED_FOR_ONBOARDING',
  'DECLINED',
  'WITHDRAWN',
];
export const STATUS_TONES = {
  SUBMITTED: 'info',
  UNDER_REVIEW: 'info',
  MORE_INFORMATION_REQUIRED: 'warning',
  APPROVED_FOR_ONBOARDING: 'success',
  DECLINED: 'neutral',
  WITHDRAWN: 'neutral',
} as const;
export const CHECK_RESULTS: readonly PartnerCheckResult[] = [
  'PENDING',
  'SATISFIED',
  'NOT_APPLICABLE',
  'CONCERN',
];
export const NOTE_MAX = 500;
export const CHECK_CODES: readonly PartnerCheckCode[] = [
  'BUSINESS_IDENTITY',
  'REGISTRATION_REFERENCE',
  'REGULATORY_INFORMATION',
  'SERVICE_SCOPE',
  'PRIVACY_SECURITY_CONTACT',
  'TECHNICAL_API_READINESS',
  'COMMERCIAL_DISCUSSION',
];

/** Closed reason lists per target status (D120D-5); approval and review start take no reason. */
export const REASONS: Partial<Record<PartnerApplicationStatus, readonly PartnerReasonCode[]>> = {
  MORE_INFORMATION_REQUIRED: [
    'CLARIFY_REGULATORY_INFORMATION',
    'CLARIFY_SERVICE_SCOPE',
    'CLARIFY_TECHNICAL_READINESS',
    'OTHER',
  ],
  DECLINED: [
    'OUT_OF_SCOPE',
    'INCOMPLETE_INFORMATION',
    'REGULATORY_CONCERN',
    'DUE_DILIGENCE_CONCERN',
    'NOT_PURSUING',
    'OTHER',
  ],
  WITHDRAWN: ['APPLICANT_REQUEST'],
};
export const CONFIRMED_TARGETS: ReadonlySet<PartnerApplicationStatus> = new Set([
  'APPROVED_FOR_ONBOARDING',
  'DECLINED',
  'WITHDRAWN',
]);
export const TERMINAL: ReadonlySet<PartnerApplicationStatus> = new Set([
  'APPROVED_FOR_ONBOARDING',
  'DECLINED',
  'WITHDRAWN',
]);

export const referencePath = (reference: string) =>
  `/admin/partner-applications/${encodeURIComponent(reference)}`;

/** HTML autocomplete tokens for the applicant's own business details. */
export const AUTOCOMPLETE = {
  organization: 'organization',
  title: 'organization-title',
  url: 'url',
  name: 'name',
  email: 'email',
  tel: 'tel',
} as const;

import type {
  PartnerReferralLanguage,
  PartnerReferralNeverShared,
  PartnerReferralPreviewRequest,
  PartnerReferralState,
} from '@divalhr/api-client';
import type { StatusTone } from '../../ui/primitives';

/**
 * MVP-120F (Issue #224, ADR 0045): the employee's consented partner referral and the platform's
 * read-only queue. A referral is an introduction only: nothing here decides, prices, activates or
 * moves money. The browser sends only what the contract lets the employee choose; the server
 * decides what is eligible and what is disclosed. No personal value ever enters a URL, browser
 * storage, analytics or a log; the form state lives in memory only.
 */

export const CONSENT_VERSION = 'PR-REFERRAL-1';
export const PHONE_PATTERN = /^\+[1-9][0-9]{6,14}$/;

export const LANGUAGES: readonly PartnerReferralLanguage[] = ['en', 'fr'];

export const STATES: readonly PartnerReferralState[] = [
  'SUBMITTED',
  'DELIVERY_PENDING',
  'DELIVERED',
  'DELIVERY_UNKNOWN',
  'DELIVERY_FAILED',
  'WITHDRAWN',
];

/** Text always carries the meaning; the tone only reinforces it. */
export const STATE_TONES: Record<PartnerReferralState, StatusTone> = {
  SUBMITTED: 'info',
  DELIVERY_PENDING: 'info',
  DELIVERED: 'success',
  DELIVERY_UNKNOWN: 'warning',
  DELIVERY_FAILED: 'warning',
  WITHDRAWN: 'neutral',
};

export const NEVER_SHARED: readonly PartnerReferralNeverShared[] = [
  'SALARY',
  'COMPENSATION',
  'PAYROLL',
  'ATTENDANCE',
  'LEAVE',
  'PERFORMANCE',
  'DOCUMENTS',
  'NATIONAL_IDENTIFIERS',
  'BANK_OR_MOBILE_MONEY_DETAILS',
  'DEDUCTIONS',
  'CREDIT_HISTORY',
  'MEDICAL_INFORMATION',
  'DEPENDANTS',
  'MANAGER',
  'SITE',
  'COST_CENTRE',
  'EMPLOYMENT_HISTORY',
];

export const productPath = (id: string) => `/me/partner-products/${encodeURIComponent(id)}`;
export const requestPath = (id: string) => `/me/partner-requests/${encodeURIComponent(id)}`;
export const platformPath = (id: string) => `/admin/partner-referrals/${encodeURIComponent(id)}`;

export interface ReferralForm {
  language: PartnerReferralLanguage;
  includePhone: boolean;
  phone: string;
  includeOrganizationName: boolean;
}

export type ReferralFormErrors = Partial<Record<'phone', 'REQUIRED' | 'FORMAT'>>;

/** Spaces and hyphens typed for readability are dropped; everything else is validated as typed. */
export function normalizePhone(raw: string): string {
  return raw.replace(/[\s-]/g, '');
}

export function validateForm(form: ReferralForm): ReferralFormErrors {
  if (!form.includePhone) return {};
  const phone = normalizePhone(form.phone);
  if (phone === '') return { phone: 'REQUIRED' };
  return PHONE_PATTERN.test(phone) ? {} : { phone: 'FORMAT' };
}

export function toPreviewBody(
  productId: string,
  form: ReferralForm,
): PartnerReferralPreviewRequest {
  return {
    productId,
    language: form.language,
    includePhone: form.includePhone,
    phone: form.includePhone ? normalizePhone(form.phone) : null,
    includeOrganizationName: form.includeOrganizationName,
  };
}

/** A different request once the inputs change: the preview and its key are discarded. */
export function formSignature(productId: string, form: ReferralForm): string {
  return JSON.stringify(toPreviewBody(productId, form));
}

export function formatInstant(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  );
}

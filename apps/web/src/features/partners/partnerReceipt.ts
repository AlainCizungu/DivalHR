import type { PartnerApplicationReceipt } from '@divalhr/api-client';

/**
 * R216-4: the only thing this story keeps in the browser. The application sends no email and has
 * no public lookup, so the safe receipt (opaque reference, submission time, status, next-step code)
 * survives a reload of the same tab session. Form fields, contact data, regulatory values,
 * consent data and the honeypot are never stored, and neither are internal review notes. Every
 * read is validated: anything unexpected is discarded rather than shown.
 */
export const RECEIPT_STORAGE_KEY = 'divalhr.partner.receipt';

const REFERENCE = /^PA-[0-9A-HJKMNP-TV-Z]{12}$/u;
const NEXT_STEPS: readonly string[] = ['DIVALHR_WILL_CONTACT_BUSINESS_EMAIL'];

const isReceipt = (value: unknown): value is PartnerApplicationReceipt => {
  if (typeof value !== 'object' || value === null) return false;
  const keys = Object.keys(value).sort();
  if (keys.join(',') !== 'nextStep,reference,status,submittedAt') return false;
  const r = value as Record<string, unknown>;
  return (
    typeof r.reference === 'string' &&
    REFERENCE.test(r.reference) &&
    typeof r.submittedAt === 'string' &&
    !Number.isNaN(Date.parse(r.submittedAt)) &&
    r.status === 'SUBMITTED' &&
    typeof r.nextStep === 'string' &&
    NEXT_STEPS.includes(r.nextStep)
  );
};

/** Keeps the four safe receipt fields, and nothing else the server might add. */
export function saveReceipt(receipt: PartnerApplicationReceipt): void {
  try {
    const safe: PartnerApplicationReceipt = {
      reference: receipt.reference,
      submittedAt: receipt.submittedAt,
      status: receipt.status,
      nextStep: receipt.nextStep,
    };
    sessionStorage.setItem(RECEIPT_STORAGE_KEY, JSON.stringify(safe));
  } catch {
    // Storage may be unavailable or full: the confirmation still shows in this render.
  }
}

/** The stored receipt of this tab session, or null when absent, unreadable or not a safe receipt. */
export function loadReceipt(): PartnerApplicationReceipt | null {
  try {
    const raw = sessionStorage.getItem(RECEIPT_STORAGE_KEY);
    if (raw === null) return null;
    const parsed: unknown = JSON.parse(raw);
    if (isReceipt(parsed)) return parsed;
    sessionStorage.removeItem(RECEIPT_STORAGE_KEY);
  } catch {
    // unreadable storage or malformed JSON
  }
  return null;
}

export function clearReceipt(): void {
  try {
    sessionStorage.removeItem(RECEIPT_STORAGE_KEY);
  } catch {
    // nothing to clear
  }
}

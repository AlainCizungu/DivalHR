import type {
  PrivacyAction,
  PrivacyDataClass,
  PrivacyHoldReason,
  PrivacyLifecycleTrigger,
  PrivacyPolicyChecklist,
  PrivacyPolicyState,
  PrivacyPurpose,
  PrivacyRequestState,
  PrivacyRequestType,
  Problem,
} from '@divalhr/api-client';
import { useCallback, useRef, useState } from 'react';
import type { StatusTone } from '../../ui/primitives';
import { NETWORK_FAILURE, type PartnerFailure } from '../partners/partners';

/**
 * LAUNCH-001 (Issue #226, ADR 0046): privacy governance, consent retention and the employee's
 * data-rights requests. The browser shows what the server returned and sends only what the
 * contract lets the operator choose. No disclosed value, request note or package ever enters a URL,
 * browser storage, analytics or a log; every form lives in memory only. Nothing here states that
 * DivalHR "complies" with or is "certified" for any law: this is a governance framework, not legal
 * advice or a certification.
 */

export const REQUEST_TYPES: readonly PrivacyRequestType[] = [
  'ACCESS',
  'ERASURE',
  'RESTRICTION',
  'OBJECTION',
];

export const REQUEST_STATES: readonly PrivacyRequestState[] = [
  'SUBMITTED',
  'UNDER_REVIEW',
  'MORE_INFORMATION_REQUIRED',
  'APPROVED',
  'PARTIALLY_APPROVED',
  'REFUSED',
  'COMPLETED',
  'CANCELLED_BY_EMPLOYEE',
];

export const OPEN_STATES: ReadonlySet<PrivacyRequestState> = new Set<PrivacyRequestState>([
  'SUBMITTED',
  'UNDER_REVIEW',
  'MORE_INFORMATION_REQUIRED',
  'APPROVED',
  'PARTIALLY_APPROVED',
]);

/** What the platform may move a request to next (the server enforces the same closed table). */
export const NEXT_STATES: Record<PrivacyRequestState, readonly PrivacyRequestState[]> = {
  SUBMITTED: ['UNDER_REVIEW'],
  UNDER_REVIEW: ['MORE_INFORMATION_REQUIRED', 'APPROVED', 'PARTIALLY_APPROVED', 'REFUSED'],
  MORE_INFORMATION_REQUIRED: ['UNDER_REVIEW'],
  APPROVED: ['COMPLETED'],
  PARTIALLY_APPROVED: ['COMPLETED'],
  REFUSED: [],
  COMPLETED: [],
  CANCELLED_BY_EMPLOYEE: [],
};

export const REASONS_REQUIRED: ReadonlySet<PrivacyRequestState> = new Set<PrivacyRequestState>([
  'PARTIALLY_APPROVED',
  'REFUSED',
]);

export const DECISION_REASONS = [
  'IDENTITY_NOT_VERIFIED',
  'LEGAL_HOLD_ACTIVE',
  'RETENTION_REQUIRED',
  'POLICY_PENDING',
  'REQUEST_OUT_OF_SCOPE',
  'NO_MATCHING_DATA',
] as const;

/** Text always carries the meaning; the tone only reinforces it. */
export const REQUEST_TONES: Record<PrivacyRequestState, StatusTone> = {
  SUBMITTED: 'info',
  UNDER_REVIEW: 'info',
  MORE_INFORMATION_REQUIRED: 'warning',
  APPROVED: 'success',
  PARTIALLY_APPROVED: 'warning',
  REFUSED: 'neutral',
  COMPLETED: 'success',
  CANCELLED_BY_EMPLOYEE: 'neutral',
};

export const POLICY_TONES: Record<PrivacyPolicyState, StatusTone> = {
  DRAFT: 'neutral',
  APPROVED: 'info',
  SCHEDULED: 'info',
  ACTIVE: 'success',
  RETIRED: 'neutral',
};

export const NEXT_POLICY_STATES: Record<PrivacyPolicyState, readonly PrivacyPolicyState[]> = {
  DRAFT: ['APPROVED', 'RETIRED'],
  APPROVED: ['SCHEDULED', 'ACTIVE', 'RETIRED'],
  SCHEDULED: ['ACTIVE', 'RETIRED'],
  ACTIVE: ['RETIRED'],
  RETIRED: [],
};

export const DATA_CLASSES: readonly { dataClass: PrivacyDataClass; purpose: PrivacyPurpose }[] = [
  { dataClass: 'PARTNER_REFERRAL_DISCLOSURE', purpose: 'CONSENTED_INTRODUCTION' },
  { dataClass: 'PARTNER_REFERRAL_CONSENT', purpose: 'CONSENT_EVIDENCE' },
  { dataClass: 'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS', purpose: 'DELIVERY_TRUTH' },
  { dataClass: 'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE', purpose: 'LIFECYCLE_EVIDENCE' },
];

export const ACTIONS: readonly PrivacyAction[] = ['HOLD', 'MINIMIZE', 'ERASE'];
export const TRIGGERS: readonly PrivacyLifecycleTrigger[] = ['REFERRAL_CREATED', 'REFERRAL_CLOSED'];

export const HOLD_REASONS: readonly PrivacyHoldReason[] = [
  'LEGAL_PROCEEDING',
  'REGULATORY_REQUEST',
  'DISPUTE',
  'INVESTIGATION',
  'OTHER_DOCUMENTED',
];

export const CHECKLIST_ITEMS = [
  'jurisdictionIdentified',
  'sourceReferenceRecorded',
  'counselReviewRecorded',
  'dataClassesCovered',
  'lifecycleTriggerDefined',
  'durationsOrHoldSet',
  'employeeWordingVersionRecorded',
  'rollbackReviewComplete',
] as const satisfies readonly (keyof PrivacyPolicyChecklist)[];

export const NOTE_MAX = 1000;
export const MESSAGE_MAX = 500;
export const REFERRAL_PATTERN = /^PR-[0-9A-HJKMNP-TV-Z]{16}$/;

export interface RuleForm {
  action: PrivacyAction;
  trigger: PrivacyLifecycleTrigger;
  days: string;
}

export function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

export function formatInstant(iso: string, locale: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  );
}

const NOT_FOUND = /_NOT_FOUND$/;
const STALE = /(_STALE|_EXPIRED|_ALREADY_USED|_IDEMPOTENCY_REUSED)$/;

/** Maps a Problem response; only contract codes are shown, never anything the server echoes. */
export function privacyFailureOf(response: Response, error: unknown): PartnerFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code?.startsWith('PRIVACY_')) {
    const kind: PartnerFailure['kind'] = code.endsWith('RATE_LIMITED')
      ? 'rateLimited'
      : NOT_FOUND.test(code)
        ? 'notFound'
        : STALE.test(code)
          ? 'stale'
          : 'general';
    return { ...base, kind, code, messageKey: `errors.${code}` };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.PRIVACY_RATE_LIMITED' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'errors.ACCESS_DENIED' };
  }
  if (response.status === 400) {
    return { ...base, kind: 'invalid', messageKey: 'privacy.invalid' };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

type Settled<T> = { data?: T; error?: unknown; response: Response };

/** One in-flight command at a time, with a finished-sentence failure and no double submit. */
export function useCommand() {
  const busy = useRef(false);
  const [working, setWorking] = useState(false);
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const run = useCallback(async <T>(call: () => Promise<Settled<T>>): Promise<T | null> => {
    if (busy.current) return null;
    busy.current = true;
    setWorking(true);
    setFailure(null);
    try {
      const { data, error, response } = await call();
      if (data !== undefined) return data;
      setFailure(privacyFailureOf(response, error));
      return null;
    } catch {
      setFailure(NETWORK_FAILURE);
      return null;
    } finally {
      busy.current = false;
      setWorking(false);
    }
  }, []);
  return {
    run,
    working,
    failure,
    clear: () => {
      setFailure(null);
    },
  };
}

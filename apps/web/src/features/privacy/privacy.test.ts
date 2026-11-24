import { describe, expect, it } from 'vitest';
import { resources } from '@divalhr/localization';
import {
  ACTIONS,
  CHECKLIST_ITEMS,
  DATA_CLASSES,
  HOLD_REASONS,
  NEXT_POLICY_STATES,
  NEXT_STATES,
  OPEN_STATES,
  REASONS_REQUIRED,
  REQUEST_STATES,
  REQUEST_TYPES,
  privacyFailureOf,
} from './privacy';

const en = resources.en.common;
const fr = resources.fr.common;

const problem = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  privacyFailureOf(new Response(null, { status, headers }), body);

describe('privacy failures', () => {
  it('maps contract codes to finished sentences and a kind', () => {
    expect(problem(404, { code: 'PRIVACY_REQUEST_NOT_FOUND' })).toMatchObject({
      kind: 'notFound',
      messageKey: 'errors.PRIVACY_REQUEST_NOT_FOUND',
    });
    expect(problem(409, { code: 'PRIVACY_PREVIEW_STALE' }).kind).toBe('stale');
    expect(problem(409, { code: 'PRIVACY_PREVIEW_ALREADY_USED' }).kind).toBe('stale');
    expect(problem(409, { code: 'PRIVACY_REQUEST_DUPLICATE_OPEN' }).kind).toBe('general');
    expect(problem(429, { code: 'PRIVACY_RATE_LIMITED' }, { 'Retry-After': '90' })).toMatchObject({
      kind: 'rateLimited',
      retryAfter: 90,
    });
  });

  it('never shows anything the server echoes for an unknown code', () => {
    const failure = problem(500, { code: 'SOMETHING_ELSE', detail: 'a@example.test' });
    expect(failure).toMatchObject({ kind: 'general', messageKey: 'errors.generic' });
    expect(JSON.stringify(failure)).not.toContain('example.test');
    expect(problem(403, {}).kind).toBe('forbidden');
    expect(problem(400, {}).messageKey).toBe('privacy.invalid');
  });
});

describe('privacy tables', () => {
  it('lets the platform move a request only along the closed table', () => {
    expect(NEXT_STATES.SUBMITTED).toEqual(['UNDER_REVIEW']);
    expect(NEXT_STATES.UNDER_REVIEW).toEqual([
      'MORE_INFORMATION_REQUIRED',
      'APPROVED',
      'PARTIALLY_APPROVED',
      'REFUSED',
    ]);
    for (const terminal of ['REFUSED', 'COMPLETED', 'CANCELLED_BY_EMPLOYEE'] as const) {
      expect(NEXT_STATES[terminal]).toEqual([]);
      expect(OPEN_STATES.has(terminal)).toBe(false);
    }
    expect(REASONS_REQUIRED).toEqual(new Set(['PARTIALLY_APPROVED', 'REFUSED']));
    expect(REQUEST_STATES).toHaveLength(8);
    expect(REQUEST_TYPES).toEqual(['ACCESS', 'ERASURE', 'RESTRICTION', 'OBJECTION']);
  });

  it('moves a policy only along the closed lifecycle', () => {
    expect(NEXT_POLICY_STATES.DRAFT).toEqual(['APPROVED', 'RETIRED']);
    expect(NEXT_POLICY_STATES.APPROVED).toEqual(['SCHEDULED', 'ACTIVE', 'RETIRED']);
    expect(NEXT_POLICY_STATES.ACTIVE).toEqual(['RETIRED']);
    expect(NEXT_POLICY_STATES.RETIRED).toEqual([]);
  });

  it('pairs each data class with its only purpose', () => {
    expect(DATA_CLASSES.map((c) => c.dataClass)).toEqual([
      'PARTNER_REFERRAL_DISCLOSURE',
      'PARTNER_REFERRAL_CONSENT',
      'PARTNER_REFERRAL_DELIVERY_DIAGNOSTICS',
      'PARTNER_REFERRAL_LIFECYCLE_EVIDENCE',
    ]);
    expect(ACTIONS).toEqual(['HOLD', 'MINIMIZE', 'ERASE']);
    expect(CHECKLIST_ITEMS).toHaveLength(8);
  });
});

describe('privacy copy', () => {
  const flat = (value: unknown, prefix = ''): Record<string, string> =>
    typeof value === 'string'
      ? { [prefix]: value }
      : Object.entries(value as Record<string, unknown>).reduce(
          (all, [key, inner]) => ({ ...all, ...flat(inner, prefix ? `${prefix}.${key}` : key) }),
          {},
        );

  it('has the same keys in English and French', () => {
    expect(Object.keys(flat(fr.privacy)).sort()).toEqual(Object.keys(flat(en.privacy)).sort());
  });

  it('covers every enum the screens print', () => {
    for (const state of REQUEST_STATES) expect(en.privacy.state).toHaveProperty(state);
    for (const type of REQUEST_TYPES) expect(en.privacy.type).toHaveProperty(type);
    for (const reason of HOLD_REASONS) expect(fr.privacy.holdReason).toHaveProperty(reason);
  });

  it('never claims compliance or certification', () => {
    const claims = /\b(compliant|certified|conforme|certifié)\b|GDPR|RGPD|DRC compliant/iu;
    for (const text of [...Object.values(flat(en.privacy)), ...Object.values(flat(fr.privacy))]) {
      expect(text).not.toMatch(claims);
    }
    expect(en.privacy.platform.framework).toContain('not legal advice or a certification');
    expect(fr.privacy.platform.framework).toContain('pas un avis juridique ni une certification');
  });
});

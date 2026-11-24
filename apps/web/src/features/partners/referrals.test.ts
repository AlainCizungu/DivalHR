import { describe, expect, it } from 'vitest';
import en from '../../../../../packages/localization/locales/en/common.json';
import fr from '../../../../../packages/localization/locales/fr/common.json';
import { partnerFailureOf } from './partners';
import {
  NEVER_SHARED,
  STATES,
  STATE_TONES,
  formSignature,
  normalizePhone,
  toPreviewBody,
  validateForm,
  type ReferralForm,
} from './referrals';

const form = (over: Partial<ReferralForm> = {}): ReferralForm => ({
  language: 'en',
  includePhone: false,
  phone: '',
  includeOrganizationName: false,
  ...over,
});

const problem = (code: string, status: number) =>
  [new Response(null, { status }), { code, status, correlationId: 'c-1' }] as const;

describe('MVP-120F referral form', () => {
  it('requires a well-formed international phone only when it is shared', () => {
    expect(validateForm(form())).toEqual({});
    expect(validateForm(form({ includePhone: true, phone: '' }))).toEqual({ phone: 'REQUIRED' });
    expect(validateForm(form({ includePhone: true, phone: '0810000000' }))).toEqual({
      phone: 'FORMAT',
    });
    expect(validateForm(form({ includePhone: true, phone: '+243 810-000-000' }))).toEqual({});
  });

  it('sends no phone unless it is shared and never anything the contract does not allow', () => {
    expect(toPreviewBody('PP-1', form({ phone: '+243810000000' }))).toEqual({
      productId: 'PP-1',
      language: 'en',
      includePhone: false,
      phone: null,
      includeOrganizationName: false,
    });
    expect(
      toPreviewBody('PP-1', form({ includePhone: true, phone: '+243 810 000 000' })).phone,
    ).toBe(normalizePhone('+243 810 000 000'));
  });

  it('a different request has a different signature, an identical one the same', () => {
    expect(formSignature('PP-1', form())).toBe(formSignature('PP-1', form()));
    expect(formSignature('PP-1', form())).not.toBe(
      formSignature('PP-1', form({ includeOrganizationName: true })),
    );
  });
});

describe('MVP-120F referral failures and texts', () => {
  it.each([
    ['PARTNER_REFERRAL_PRODUCT_UNAVAILABLE', 404, 'notFound'],
    ['PARTNER_REFERRAL_NOT_FOUND', 404, 'notFound'],
    ['PARTNER_REFERRAL_PREVIEW_EXPIRED', 409, 'stale'],
    ['PARTNER_REFERRAL_PREVIEW_STALE', 409, 'stale'],
    ['PARTNER_REFERRAL_PREVIEW_ALREADY_USED', 409, 'stale'],
    ['PARTNER_REFERRAL_NOT_WITHDRAWABLE', 409, 'stale'],
    ['PARTNER_REFERRAL_VERSION_STALE', 409, 'stale'],
    ['PARTNER_REFERRAL_IDEMPOTENCY_REUSED', 409, 'stale'],
    ['PARTNER_REFERRAL_CONSENT_REQUIRED', 422, 'invalid'],
    ['PARTNER_REFERRAL_DISCLOSURE_INVALID', 400, 'invalid'],
    ['PARTNER_REFERRAL_RATE_LIMITED', 429, 'rateLimited'],
  ])('%s is a finished sentence of kind %s', (code, status, kind) => {
    const [response, error] = problem(code, status);
    const failure = partnerFailureOf(response, error);
    expect(failure.kind).toBe(kind);
    expect(failure.messageKey).toBe(`errors.${code}`);
    expect((en.errors as Record<string, string>)[code]).toBeTruthy();
    expect((fr.errors as Record<string, string>)[code]).toBeTruthy();
  });

  it('every state, every never-shared category and every field has English and French text', () => {
    const r = (lang: typeof en) =>
      lang.referrals as unknown as Record<string, Record<string, string>>;
    for (const lang of [en, fr]) {
      for (const state of STATES) {
        expect(r(lang).state?.[state], state).toBeTruthy();
        expect(r(lang).stateHelp?.[state], state).toBeTruthy();
        expect(STATE_TONES[state]).toBeDefined();
      }
      for (const item of NEVER_SHARED) expect(r(lang).neverShared?.[item], item).toBeTruthy();
    }
    expect(NEVER_SHARED).toHaveLength(17);
  });

  it('says in both languages that nothing is decided and no money moves', () => {
    const notice = (lang: unknown) =>
      (lang as { referrals: { notice: Record<string, string> } }).referrals.notice;
    expect(notice(en).noDecision).toMatch(/no decision/i);
    expect(notice(en).noMoney).toMatch(/no money/i);
    expect(notice(fr).noDecision).toMatch(/aucune décision/i);
    expect(notice(fr).noMoney).toMatch(/aucun argent/i);
  });
});

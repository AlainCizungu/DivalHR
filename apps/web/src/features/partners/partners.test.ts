import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import problemSchema from '../../../../../packages/shared-contracts/schemas/problem.schema.json';
import {
  API_AVAILABILITY,
  CAPABILITIES,
  CATEGORIES,
  CHECK_CODES,
  CHECK_RESULTS,
  CONSENT_VERSION,
  COUNTRIES,
  MAX_OPERATION,
  PRIVACY_NOTICE_VERSION,
  PARTNER_ERROR_CODES,
  REASONS,
  REGULATED,
  STATUSES,
  countryOptions,
  emptyValues,
  fieldErrorsFromProblem,
  isCountryCode,
  isRegulated,
  partnerFailureOf,
  toSubmission,
  validate,
  type ApplyValues,
} from './partners';

const valid = (over: Partial<ApplyValues> = {}): ApplyValues => ({
  ...emptyValues('en'),
  legalName: 'Synthetic Test Bank SA',
  countryOfRegistration: 'CD',
  countriesOfOperation: ['CD'],
  registrationReference: 'SYN-0001',
  primaryCategory: 'BANK',
  requestedCapabilities: ['SALARY_ADVANCE'],
  website: 'https://example.test',
  contactName: 'Test Contact',
  contactRole: 'Head of partnerships',
  businessEmail: 'contact@example.test',
  apiAvailability: 'PLANNED',
  regulatorName: 'Synthetic Regulator',
  licenceReference: 'LIC-0001',
  serviceSummary: 'A synthetic summary that is long enough to pass.',
  privacyContactEmail: 'privacy@example.test',
  acknowledgment: true,
  ...over,
});

describe('partner application model', () => {
  it('knows the eight categories and the six regulated ones', () => {
    expect(CATEGORIES).toHaveLength(8);
    expect([...REGULATED].sort()).toEqual(
      [
        'BANK',
        'INSURANCE',
        'MICROFINANCE',
        'MOBILE_MONEY_PAYMENT',
        'PENSION_SAVINGS',
        'TELECOM',
      ].sort(),
    );
    expect(isRegulated('HEALTH_BENEFITS')).toBe(false);
    expect(isRegulated('OTHER_REGULATED_PROVIDER')).toBe(false);
    expect(isRegulated('')).toBe(false);
  });

  it('accepts a complete application and sends an empty honeypot with no regulatory fields for others', () => {
    expect(validate(valid())).toEqual({});
    const body = toSubmission(
      valid({ primaryCategory: 'HEALTH_BENEFITS', regulatorName: 'x' }),
      'en',
    );
    expect(body.faxNumber).toBe('');
    expect(body.regulatorName).toBeNull();
    expect(body.licenceReference).toBeNull();
    expect(body.businessPhone).toBeNull();
    expect(body.privacyNoticeVersion).toBe('PARTNER-PRIVACY-1');
    expect(body.consentVersion).toBe('PARTNER-APPLICATION-CONSENT-1');
  });

  it('requires the regulatory fields only when a selected category is regulated', () => {
    const missing = validate(valid({ regulatorName: '', licenceReference: '' }));
    expect(missing.regulatorName).toBe('REQUIRED');
    expect(missing.licenceReference).toBe('REQUIRED');
    expect(
      validate(valid({ primaryCategory: 'OTHER_REGULATED_PROVIDER', regulatorName: '' })),
    ).toEqual({});
  });

  it('checks the contract limits', () => {
    const errors = validate(
      valid({
        legalName: 'x',
        website: 'http://insecure.example',
        businessEmail: 'nope',
        businessPhone: '0900000000',
        serviceSummary: 'too short',
        countriesOfOperation: [],
        requestedCapabilities: [],
        acknowledgment: false,
      }),
    );
    expect(errors).toMatchObject({
      legalName: 'LENGTH',
      website: 'FORMAT',
      businessEmail: 'FORMAT',
      businessPhone: 'FORMAT',
      serviceSummary: 'LENGTH',
      countriesOfOperation: 'REQUIRED',
      requestedCapabilities: 'REQUIRED',
      acknowledgment: 'REQUIRED',
    });
  });

  it('never repeats the primary category among the additional ones', () => {
    const body = toSubmission(valid({ additionalCategories: ['BANK', 'INSURANCE'] }), 'fr');
    expect(body.additionalCategories).toEqual(['INSURANCE']);
    expect(body.locale).toBe('fr');
  });

  it('reads field errors as names and constraint codes only', () => {
    expect(
      fieldErrorsFromProblem({
        params: {
          fields: [
            { field: 'legalName', constraint: 'LENGTH' },
            { field: 'somethingElse', constraint: 'UNKNOWN_PROPERTY' },
            { field: 'website', constraint: 'secret-value' },
          ],
        },
      }),
    ).toEqual({ legalName: 'LENGTH', body: 'UNKNOWN_PROPERTY', website: 'FORMAT' });
    expect(fieldErrorsFromProblem({ params: {} })).toBeNull();
    expect(fieldErrorsFromProblem(undefined)).toBeNull();
  });

  it('maps every contract failure to a finished sentence key, never the raw code', () => {
    const response = (status: number, retry?: string) =>
      new Response(null, { status, headers: retry ? { 'Retry-After': retry } : {} });
    expect(
      partnerFailureOf(response(429, '30'), { code: 'PARTNER_APPLICATION_RATE_LIMITED' }),
    ).toMatchObject({ kind: 'rateLimited', retryAfter: 30 });
    expect(
      partnerFailureOf(response(409), { code: 'PARTNER_APPLICATION_TRANSITION_INVALID' }).kind,
    ).toBe('stale');
    expect(partnerFailureOf(response(404), { code: 'PARTNER_APPLICATION_NOT_FOUND' }).kind).toBe(
      'notFound',
    );
    expect(partnerFailureOf(response(403), { code: 'MFA_REQUIRED' }).kind).toBe('forbidden');
    expect(partnerFailureOf(response(500), { code: 'SOMETHING_UNKNOWN' }).messageKey).toBe(
      'errors.generic',
    );
  });
});

describe.each(['en', 'fr'] as const)('partner copy (%s)', (locale) => {
  const c = resources[locale].common;
  const text = (value: unknown) => typeof value === 'string' && value.trim().length > 0;

  it('has a complete error message for every code this feature renders, and the contract lists it', () => {
    const catalogue = problemSchema.properties.code.enum;
    for (const code of PARTNER_ERROR_CODES) {
      expect(text((c.errors as Record<string, unknown>)[code]), code).toBe(true);
      expect(catalogue, code).toContain(code);
    }
  });

  it('names every category, capability, status, reason, result, check and event', () => {
    const p = c.partners as Record<string, Record<string, unknown>>;
    for (const v of CATEGORIES) expect(text(p.category?.[v]), v).toBe(true);
    for (const v of CAPABILITIES) expect(text(p.capability?.[v]), v).toBe(true);
    for (const v of API_AVAILABILITY) expect(text(p.api?.[v]), v).toBe(true);
    for (const v of STATUSES) expect(text(p.status?.[v]), v).toBe(true);
    for (const v of CHECK_RESULTS) expect(text(p.result?.[v]), v).toBe(true);
    for (const v of Object.values(REASONS).flat()) expect(text(p.reason?.[v]), v).toBe(true);
    for (const v of CHECK_CODES) {
      const entry = p.check?.[v] as { title: string; help: string };
      expect(text(entry.title) && text(entry.help), v).toBe(true);
    }
    for (const v of [
      'SUBMITTED',
      'REVIEW_STARTED',
      'INFORMATION_REQUESTED',
      'CHECKLIST_UPDATED',
      'APPROVED_FOR_ONBOARDING',
      'DECLINED',
      'WITHDRAWAL_RECORDED',
    ]) {
      expect(text(p.event?.[v]), v).toBe(true);
    }
  });

  it('states the approval boundary and never claims a connected partner', () => {
    expect(c.partners.review.approvalBoundary).toMatch(
      locale === 'en'
        ? /No credentials, integration or access to employee data/u
        : /Aucun identifiant, aucune intégration et aucun accès aux données des employés/u,
    );
    expect(c.partners.apply.boundary).not.toMatch(/connected|connecté/iu);
  });
});

describe('R216-2: regulatory fields apply to every selected category', () => {
  const REGULATED_CATEGORIES = [
    'BANK',
    'MICROFINANCE',
    'INSURANCE',
    'TELECOM',
    'MOBILE_MONEY_PAYMENT',
    'PENSION_SAVINGS',
  ] as const;

  it.each(REGULATED_CATEGORIES)(
    'requires them when %s is an additional category of a nonregulated primary',
    (category) => {
      const values = valid({
        primaryCategory: 'HEALTH_BENEFITS',
        additionalCategories: [category],
        regulatorName: '',
        licenceReference: '',
      });
      expect(isRegulated('HEALTH_BENEFITS', [category])).toBe(true);
      expect(validate(values)).toMatchObject({
        regulatorName: 'REQUIRED',
        licenceReference: 'REQUIRED',
      });
      const filled = valid({
        primaryCategory: 'HEALTH_BENEFITS',
        additionalCategories: [category],
      });
      expect(validate(filled)).toEqual({});
      const body = toSubmission(filled, 'en');
      expect(body.regulatorName).toBe('Synthetic Regulator');
      expect(body.licenceReference).toBe('LIC-0001');
    },
  );

  it('does not require them for nonregulated selections only', () => {
    const values = valid({
      primaryCategory: 'HEALTH_BENEFITS',
      additionalCategories: ['OTHER_REGULATED_PROVIDER'],
      regulatorName: '',
      licenceReference: '',
    });
    expect(isRegulated('HEALTH_BENEFITS', ['OTHER_REGULATED_PROVIDER'])).toBe(false);
    expect(validate(values)).toEqual({});
    expect(toSubmission(values, 'en').regulatorName).toBeNull();
  });
});

describe('R216-1: the ISO country catalogue', () => {
  it('lists the 249 assigned codes with no OTHER value', () => {
    expect(COUNTRIES).toHaveLength(249);
    expect(new Set(COUNTRIES).size).toBe(249);
    for (const absent of ['OTHER', 'XK', 'EU', 'UN', 'ZZ', 'AN', 'UK']) {
      expect(isCountryCode(absent)).toBe(false);
    }
  });

  it('covers DRC, Francophone and Anglophone African and international countries', () => {
    for (const code of [
      'CD',
      'CG',
      'CI',
      'SN',
      'CM',
      'GA',
      'BF',
      'ML',
      'RW',
      'KE',
      'NG',
      'ZA',
      'FR',
      'US',
      'JP',
    ]) {
      expect(isCountryCode(code)).toBe(true);
    }
  });

  it('names every country in English and French with Intl.DisplayNames, never the code', () => {
    const en = countryOptions('en');
    const fr = countryOptions('fr');
    expect(en).toHaveLength(249);
    for (const option of [...en, ...fr]) expect(option.label).not.toBe(option.value);
    expect(en.find((o) => o.value === 'CD')?.label).toContain('Kinshasa');
    expect(fr.find((o) => o.value === 'CD')?.label).toContain('Kinshasa');
    expect(fr.find((o) => o.value === 'CI')?.label).toContain('Ivoire');
    expect(en.find((o) => o.value === 'SN')?.label).toBe('Senegal');
    expect(fr.find((o) => o.value === 'SN')?.label).toBe('Sénégal');
  });

  it('sorts the names for the interface language', () => {
    const labels = countryOptions('fr').map((o) => o.label);
    expect(labels).toEqual([...labels].sort(new Intl.Collator('fr').compare));
  });

  it('rejects unknown countries and too many countries of operation', () => {
    expect(validate(valid({ countryOfRegistration: 'ZZ' })).countryOfRegistration).toBe('FORMAT');
    expect(validate(valid({ countriesOfOperation: ['CD', 'OTHER'] })).countriesOfOperation).toBe(
      'FORMAT',
    );
    expect(
      validate(valid({ countriesOfOperation: COUNTRIES.slice(0, MAX_OPERATION + 1) }))
        .countriesOfOperation,
    ).toBe('LENGTH');
    expect(
      validate(valid({ countryOfRegistration: 'SN', countriesOfOperation: ['SN', 'CI'] })),
    ).toEqual({});
  });
});

describe('R216-3: consent provenance', () => {
  it('sends the stable semantic identifiers, not a date', () => {
    expect(PRIVACY_NOTICE_VERSION).toBe('PARTNER-PRIVACY-1');
    expect(CONSENT_VERSION).toBe('PARTNER-APPLICATION-CONSENT-1');
    const body = toSubmission(valid(), 'fr');
    expect(body.privacyNoticeVersion).toBe(PRIVACY_NOTICE_VERSION);
    expect(body.consentVersion).toBe(CONSENT_VERSION);
  });

  // The exact bilingual wording shown to the applicant is pinned to its identifier: editing the
  // wording changes the digest, and the test fails until the identifier (here, in Core, in the
  // contract and in ADR 0043) and the digest below are deliberately updated together.
  const digest = async (parts: string[]) => {
    const bytes = new TextEncoder().encode(JSON.stringify(parts));
    const hash = await crypto.subtle.digest('SHA-256', bytes);
    return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('');
  };
  const text = (locale: 'en' | 'fr') => resources[locale].common.partners;

  it('pins PARTNER-PRIVACY-1 to the displayed privacy notice in both languages', async () => {
    expect(PRIVACY_NOTICE_VERSION).toBe('PARTNER-PRIVACY-1');
    const parts = (['en', 'fr'] as const).flatMap((l) => [
      text(l).notice.title,
      text(l).notice.body,
    ]);
    expect(await digest(parts)).toBe(
      '3d30bb5994cf20a87c0accf4a963c5713ccc650d4532d874a09b711ec547d6d9',
    );
  });

  it('pins PARTNER-APPLICATION-CONSENT-1 to the displayed acknowledgment in both languages', async () => {
    expect(CONSENT_VERSION).toBe('PARTNER-APPLICATION-CONSENT-1');
    const parts = (['en', 'fr'] as const).flatMap((l) => [
      text(l).apply.fields.acknowledgment.label,
      text(l).apply.fields.acknowledgment.help,
    ]);
    expect(await digest(parts)).toBe(
      '3aaa5caa7a5fb0b8a374ba9a34584122a2f6323c762f2dadad07eba57f109145',
    );
  });
});

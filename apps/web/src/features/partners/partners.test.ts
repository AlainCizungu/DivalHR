import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import problemSchema from '../../../../../packages/shared-contracts/schemas/problem.schema.json';
import {
  API_AVAILABILITY,
  CAPABILITIES,
  CATEGORIES,
  CHECK_CODES,
  CHECK_RESULTS,
  PARTNER_ERROR_CODES,
  REASONS,
  REGULATED,
  STATUSES,
  emptyValues,
  fieldErrorsFromProblem,
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
    expect(body.privacyNoticeVersion).toMatch(/^[A-Za-z0-9._-]{1,40}$/u);
  });

  it('requires the regulatory fields only for regulated primary categories', () => {
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
        ? /No listing, credentials, integration or access to employee data/u
        : /Aucun référencement, aucun identifiant, aucune intégration et aucun accès aux données des employés/u,
    );
    expect(c.partners.apply.boundary).not.toMatch(/connected|connecté/iu);
  });
});

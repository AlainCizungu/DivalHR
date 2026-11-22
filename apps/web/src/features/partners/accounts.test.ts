import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import {
  CURRENCIES,
  contentErrorsFromProblem,
  contentFromForm,
  emptyForm,
  formFromContent,
  localized,
  validateContent,
} from './accounts';
import { PARTNER_ERROR_CODES, partnerFailureOf } from './partners';

const filled = () => {
  const form = emptyForm();
  form.nameEn = 'Salary advance';
  form.nameFr = 'Avance sur salaire';
  form.summaryEn = 'A short synthetic summary.';
  form.summaryFr = 'Un court résumé synthétique.';
  form.eligibilityEn = 'Open to permanent employees.';
  form.eligibilityFr = 'Ouvert aux employés permanents.';
  form.disclosureEn = 'Synthetic disclosure that is long enough.';
  form.disclosureFr = 'Mention synthétique suffisamment longue.';
  form.infoUrl = 'https://example.test/info';
  form.effectiveDate = '2026-11-01';
  form.minMonths = '1';
  form.maxMonths = '6';
  form.countries = ['CD'];
  form.currencies.CDF = { on: true, min: '10000', max: '500000.50' };
  return form;
};

describe('product content form', () => {
  it('accepts an empty draft and a complete one', () => {
    expect(validateContent(emptyForm())).toEqual({});
    expect(validateContent(filled())).toEqual({});
  });

  it('reports format problems field by field, never the value', () => {
    const form = filled();
    form.nameEn = 'x';
    form.infoUrl = 'http://example.test';
    form.endDate = '2026-10-01';
    form.minMonths = '7';
    form.currencies.CDF = { on: true, min: '1e3', max: '5' };
    expect(validateContent(form)).toEqual({
      nameEn: 'LENGTH',
      infoUrl: 'FORMAT',
      endDate: 'RANGE',
      minMonths: 'RANGE',
      'currency-CDF': 'FORMAT',
    });
  });

  it('ignores amounts of a currency that is switched off', () => {
    const form = filled();
    form.currencies.USD = { on: false, min: 'not a number', max: '' };
    expect(validateContent(form)).toEqual({});
  });

  it('round-trips the contract content and sends blanks as null', () => {
    const content = contentFromForm(filled());
    expect(content.name).toEqual({ en: 'Salary advance', fr: 'Avance sur salaire' });
    expect(content.currencies).toEqual([
      { currency: 'CDF', minAmount: '10000', maxAmount: '500000.50' },
    ]);
    expect(content.endDate).toBeNull();
    expect(formFromContent(content)).toEqual(filled());
    const blank = contentFromForm(emptyForm());
    expect(blank.name).toEqual({ en: null, fr: null });
    expect(blank.countries).toEqual([]);
    expect(blank.currencies).toEqual([]);
    expect(CURRENCIES).toEqual(['CDF', 'USD']);
  });

  it('maps server field paths to form fields and unknown paths to the body', () => {
    expect(
      contentErrorsFromProblem({
        params: {
          fields: [
            { field: 'content.name.fr', constraint: 'REQUIRED' },
            { field: 'content.maxMonths', constraint: 'RANGE' },
            { field: 'something.else', constraint: 'WEIRD' },
            { field: 5, constraint: 'REQUIRED' },
          ],
        },
      }),
    ).toEqual({ nameFr: 'REQUIRED', minMonths: 'RANGE', body: 'FORMAT' });
    expect(contentErrorsFromProblem({})).toBeNull();
    expect(contentErrorsFromProblem(undefined)).toBeNull();
  });

  it('picks the interface language and falls back to the other one', () => {
    expect(localized({ en: 'Hello', fr: 'Bonjour' }, 'fr')).toBe('Bonjour');
    expect(localized({ en: 'Hello', fr: null }, 'fr')).toBe('Hello');
    expect(localized({ en: null, fr: 'Bonjour' }, 'en-GB')).toBe('Bonjour');
    expect(localized({ en: null, fr: null }, 'en')).toBe('');
  });
});

describe('partner account failures', () => {
  const kindOf = (code: string, status = 409) =>
    partnerFailureOf(new Response(null, { status }), { code }).kind;

  it('classifies the MVP-120E codes', () => {
    expect(kindOf('PARTNER_NOT_FOUND', 404)).toBe('notFound');
    expect(kindOf('PARTNER_PRODUCT_NOT_FOUND', 404)).toBe('notFound');
    expect(kindOf('PARTNER_PRODUCT_NOT_AVAILABLE', 404)).toBe('notFound');
    expect(kindOf('PARTNER_PRODUCT_INVALID', 400)).toBe('invalid');
    expect(kindOf('PARTNER_PRODUCT_SCOPE_INVALID', 400)).toBe('invalid');
    for (const code of [
      'PARTNER_SOURCE_APPLICATION_NOT_APPROVED',
      'PARTNER_ALREADY_CREATED',
      'PARTNER_TRANSITION_INVALID',
      'PARTNER_HAS_ACTIVE_PRODUCTS',
      'PARTNER_PRODUCT_TRANSITION_INVALID',
      'PARTNER_PRODUCT_PARTNER_INACTIVE',
      'PARTNER_PRODUCT_VERSION_STALE',
    ]) {
      expect(kindOf(code)).toBe('stale');
    }
  });

  it('never shows a code outside the closed set', () => {
    const failure = partnerFailureOf(new Response(null, { status: 500 }), {
      code: 'SOMETHING_ELSE',
      detail: 'internal text',
    });
    expect(failure.messageKey).toBe('errors.generic');
  });

  it('has an English and a French message for every code', () => {
    for (const locale of ['en', 'fr'] as const) {
      const errors = resources[locale].common.errors as Record<string, string>;
      for (const code of PARTNER_ERROR_CODES) {
        expect(errors[code], `${locale} ${code}`).toBeTruthy();
      }
    }
  });
});

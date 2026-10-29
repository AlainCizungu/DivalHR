import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import {
  ACTIONS,
  BLOCKERS,
  COMPENSATION_CODES,
  compensationFailureOf,
  formatMoney,
  parseAmount,
  REASON_CODES,
  WARNINGS,
} from './compensation';

describe('MVP-093A money grammar (D93-4)', () => {
  it('accepts CDF as whole francs from 1 to 1,000,000,000 only', () => {
    expect(parseAmount('1', 'CDF')).toBe('1');
    expect(parseAmount(' 135000 ', 'CDF')).toBe('135000');
    expect(parseAmount('1000000000', 'CDF')).toBe('1000000000');
    for (const bad of [
      '',
      '0',
      '00',
      '01',
      '1000000001',
      '9999999999',
      '12345678901',
      '1.5',
      '1,5',
      '1.0',
      '-1',
      '+1',
      '1e3',
      '1 000',
      '1,000',
      '١٢٣',
      'abc',
    ]) {
      expect(parseAmount(bad, 'CDF'), bad).toBeNull();
    }
  });

  it('accepts USD from 0.01 to 100,000.00 and canonicalizes to two decimals', () => {
    expect(parseAmount('0.01', 'USD')).toBe('0.01');
    expect(parseAmount('0.1', 'USD')).toBe('0.10');
    expect(parseAmount('1250', 'USD')).toBe('1250.00');
    expect(parseAmount('1250.5', 'USD')).toBe('1250.50');
    expect(parseAmount('99999.99', 'USD')).toBe('99999.99');
    expect(parseAmount('100000', 'USD')).toBe('100000.00');
    expect(parseAmount('100000.00', 'USD')).toBe('100000.00');
    for (const bad of [
      '',
      '0',
      '0.00',
      '0.001',
      '1.234',
      '100000.01',
      '100001',
      '1000000',
      '-5',
      '+5',
      '1e2',
      '1,250.00',
      '1 250',
      '.5',
      '5.',
      '01.50',
      '1.2.3',
    ]) {
      expect(parseAmount(bad, 'USD'), bad).toBeNull();
    }
  });

  it('accepts one comma as the decimal separator and sends a dot', () => {
    expect(parseAmount('1250,5', 'USD')).toBe('1250.50');
    expect(parseAmount('0,01', 'USD')).toBe('0.01');
    expect(parseAmount('100000,00', 'USD')).toBe('100000.00');
    expect(parseAmount('1,5,5', 'USD')).toBeNull();
    expect(parseAmount('1250,5', 'CDF')).toBeNull();
  });

  it('formats from the canonical text with the ISO code, in each locale', () => {
    const nbsp = /\s/gu;
    expect(formatMoney('en', { currency: 'USD', amount: '1250.00' }).replace(nbsp, ' ')).toBe(
      'USD 1,250.00',
    );
    expect(formatMoney('en', { currency: 'CDF', amount: '135000' }).replace(nbsp, ' ')).toBe(
      'CDF 135,000',
    );
    const fr = formatMoney('fr', { currency: 'USD', amount: '1250.00' }).replace(nbsp, ' ');
    expect(fr).toBe('1 250,00 USD');
    expect(formatMoney('fr', { currency: 'CDF', amount: '1000000000' }).replace(nbsp, ' ')).toBe(
      '1 000 000 000 CDF',
    );
  });
});

describe('MVP-093A error mapping (D93-9, D93-16)', () => {
  const reply = (status: number) => new Response(null, { status });

  it('has English and French text for each of the eight stable codes', () => {
    expect(COMPENSATION_CODES).toHaveLength(8);
    for (const locale of ['en', 'fr'] as const) {
      const errors = resources[locale].common.errors as Record<string, string>;
      for (const code of COMPENSATION_CODES) {
        expect(errors[code], `${locale} ${code}`).toBeTruthy();
      }
    }
  });

  it('has text for every action, reason, warning and blocker in both languages', () => {
    for (const locale of ['en', 'fr'] as const) {
      const c = resources[locale].common.compensation as unknown as Record<
        string,
        Record<string, string>
      >;
      for (const key of ACTIONS) expect(c.action?.[key], `${locale} ${key}`).toBeTruthy();
      for (const key of REASON_CODES) expect(c.reason?.[key], `${locale} ${key}`).toBeTruthy();
      for (const key of WARNINGS) expect(c.warnings?.[key], `${locale} ${key}`).toBeTruthy();
      for (const key of BLOCKERS) expect(c.blockers?.[key], `${locale} ${key}`).toBeTruthy();
    }
  });

  it('maps each code to its own message key and the stale ones to a re-preview', () => {
    for (const code of COMPENSATION_CODES) {
      const failure = compensationFailureOf(reply(409), { code, correlationId: 'corr-1' });
      expect(failure.messageKey).toBe(`errors.${code}`);
      expect(failure.correlationId).toBe('corr-1');
      const stale =
        code === 'COMPENSATION_PREVIEW_STALE' || code === 'COMPENSATION_VERSION_CONFLICT';
      expect(failure.kind === 'stale').toBe(stale);
    }
  });

  it('never shows text the server echoes, and maps the generic statuses', () => {
    expect(
      compensationFailureOf(reply(500), { code: 'SOMETHING_ELSE', title: '1250.00 USD' })
        .messageKey,
    ).toBe('errors.generic');
    expect(compensationFailureOf(reply(403), { code: 'ACCESS_DENIED' }).kind).toBe('forbidden');
    expect(compensationFailureOf(reply(403), { code: 'EMPLOYEE_LINK_REQUIRED' }).kind).toBe(
      'linkRequired',
    );
    expect(compensationFailureOf(reply(429), {}).kind).toBe('rateLimited');
    expect(compensationFailureOf(reply(400), { code: 'VALIDATION_FAILED' }).kind).toBe(
      'validation',
    );
  });

  it('says the same thing in both languages about payroll and payment', () => {
    expect(resources.en.common.compensation.notice).toBe(
      'Recording compensation does not calculate payroll or initiate payment.',
    );
    expect(resources.fr.common.compensation.notice).toBe(
      'L’enregistrement de la rémunération ne calcule pas la paie et n’initie aucun paiement.',
    );
    expect(resources.en.common.compensation.me.notice).toBe(
      'Worked minutes do not automatically change a monthly salary, and no payment is initiated.',
    );
  });
});

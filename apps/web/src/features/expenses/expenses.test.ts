import { describe, expect, it } from 'vitest';
import {
  bodyOf,
  canonicalAmount,
  earliestDate,
  emptyForm,
  expenseFailureOf,
  formatAmount,
  problemsOf,
  receiptProblemOf,
} from './expenses';

describe('amount grammar per currency (D81-2)', () => {
  it('accepts CDF in whole francs only, 1 to 100,000,000', () => {
    expect(canonicalAmount('125000', 'CDF')).toBe('125000');
    expect(canonicalAmount(' 1 ', 'CDF')).toBe('1');
    expect(canonicalAmount('100000000', 'CDF')).toBe('100000000');
    for (const bad of ['0', '100000001', '12.5', '12,5', '1e3', '-5', '', 'abc', '١٢٣']) {
      expect(canonicalAmount(bad, 'CDF')).toBeNull();
    }
  });

  it('accepts USD with at most two decimals, 0.01 to 100,000.00, and a comma as separator', () => {
    expect(canonicalAmount('12.5', 'USD')).toBe('12.50');
    expect(canonicalAmount('12,50', 'USD')).toBe('12.50');
    expect(canonicalAmount('0.01', 'USD')).toBe('0.01');
    expect(canonicalAmount('100000', 'USD')).toBe('100000.00');
    for (const bad of ['0', '0.00', '100000.01', '1.234', '-1', '1e2', '', '1.', '.5']) {
      expect(canonicalAmount(bad, 'USD')).toBeNull();
    }
  });

  it('never converts: the same digits are validated by their own currency only', () => {
    expect(canonicalAmount('150000', 'USD')).toBeNull();
    expect(canonicalAmount('150000', 'CDF')).toBe('150000');
    expect(canonicalAmount('12.50', 'CDF')).toBeNull();
  });
});

describe('display', () => {
  it('always shows the currency code, in the interface language', () => {
    expect(formatAmount('en', 'USD', '1234.50')).toBe('1,234.50 USD');
    expect(formatAmount('fr', 'CDF', '125000')).toMatch(/^125[\s\u00a0\u202f]000 CDF$/u);
    expect(formatAmount('fr', 'USD', '12.50')).toBe('12,50 USD');
  });
});

describe('form validation', () => {
  const today = new Date(2026, 9, 26);
  const valid = {
    ...emptyForm(today),
    amount: '12.50',
    category: 'TRANSPORT' as const,
    merchant: 'Taxi Kinshasa',
    description: 'Course aller-retour vers le client',
  };

  it('accepts a valid form and builds the exact body', () => {
    expect(problemsOf(valid, today).size).toBe(0);
    expect(bodyOf(valid)).toEqual({
      businessDate: '2026-10-26',
      currency: 'USD',
      amount: '12.50',
      category: 'TRANSPORT',
      merchant: 'Taxi Kinshasa',
      description: 'Course aller-retour vers le client',
    });
  });

  it('refuses a date in the future or older than 90 days, accepts the boundary', () => {
    expect(problemsOf({ ...valid, businessDate: '2026-10-27' }, today).has('businessDate')).toBe(
      true,
    );
    expect(earliestDate(today)).toBe('2026-07-28');
    expect(problemsOf({ ...valid, businessDate: '2026-07-28' }, today).has('businessDate')).toBe(
      false,
    );
    expect(problemsOf({ ...valid, businessDate: '2026-07-27' }, today).has('businessDate')).toBe(
      true,
    );
  });

  it('refuses links, markup and short text, and counts code points', () => {
    expect(problemsOf({ ...valid, merchant: 'x <b>' }, today).has('merchant')).toBe(true);
    expect(
      problemsOf({ ...valid, description: 'voir https://exemple.org' }, today).has('description'),
    ).toBe(true);
    expect(problemsOf({ ...valid, description: 'trop bref' }, today).has('description')).toBe(true);
    expect(problemsOf({ ...valid, merchant: '😀'.repeat(120) }, today).has('merchant')).toBe(false);
    expect(problemsOf({ ...valid, merchant: '😀'.repeat(121) }, today).has('merchant')).toBe(true);
  });
});

describe('receipt pre-check', () => {
  it('rejects empty, oversized, untyped and path-like files before any upload', () => {
    expect(receiptProblemOf({ size: 0, type: 'image/png', name: 'a.png' })).toBe('empty');
    expect(receiptProblemOf({ size: 2 * 1024 * 1024 + 1, type: 'image/png', name: 'a.png' })).toBe(
      'size',
    );
    expect(receiptProblemOf({ size: 10, type: 'image/gif', name: 'a.gif' })).toBe('type');
    expect(receiptProblemOf({ size: 10, type: 'image/png', name: '../a.png' })).toBe('name');
    expect(
      receiptProblemOf({ size: 2 * 1024 * 1024, type: 'application/pdf', name: 'reçu.pdf' }),
    ).toBeNull();
  });
});

describe('failure mapping', () => {
  const failure = (status: number, code: string, params: unknown = {}) =>
    expenseFailureOf(new Response(null, { status }), { code, params, correlationId: 'c-1' });

  it('maps stable codes to message keys and never carries server text', () => {
    expect(failure(409, 'EXPENSE_APPROVAL_ROUTE_UNAVAILABLE').messageKey).toBe(
      'errors.EXPENSE_APPROVAL_ROUTE_UNAVAILABLE',
    );
    expect(failure(404, 'EXPENSE_NOT_FOUND').kind).toBe('notFound');
    expect(failure(409, 'EXPENSE_VERSION_CONFLICT').kind).toBe('conflict');
    expect(failure(409, 'EXPENSE_TRANSITION_NOT_ALLOWED').kind).toBe('settled');
    expect(failure(413, 'EXPENSE_RECEIPT_TOO_LARGE').messageKey).toBe(
      'errors.EXPENSE_RECEIPT_TOO_LARGE',
    );
    expect(failure(415, 'EXPENSE_RECEIPT_MEDIA_UNSUPPORTED').kind).toBe('receipt');
    expect(failure(403, 'ACCESS_DENIED').kind).toBe('forbidden');
  });

  it('keeps only allow-listed field names of a validation problem', () => {
    const mapped = failure(400, 'VALIDATION_FAILED', {
      fields: [
        { field: 'amount' },
        { field: 'merchant' },
        { field: '<script>' },
        { field: 'constructor' },
      ],
    });
    expect(mapped.fields).toEqual(['amount', 'merchant']);
  });
});

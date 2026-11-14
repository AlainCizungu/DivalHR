import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import {
  ACCOUNTING_CODES,
  accountCodeValid,
  accountingFailureOf,
  FORBIDDEN_WORDING,
  journalFileName,
  safeDispositionName,
} from './accounting';

const response = (status: number, headers: Record<string, string> = {}) =>
  new Response(null, { status, headers });
const problem = (code: string) => ({ code, correlationId: 'corr-1', detail: 'server text' });

describe('account code hint', () => {
  it.each(['6411', 'A', 'SYN-6411', 'SYN.4311_X', '0'.repeat(40), ' 6411 '])('accepts %s', (v) => {
    expect(accountCodeValid(v)).toBe(true);
  });
  it.each(['', '   ', '-6411', '_A', '.A', 'syn-6411', 'A B', 'A/B', '=SUM', '0'.repeat(41), 'É1'])(
    'refuses %s',
    (v) => {
      expect(accountCodeValid(v)).toBe(false);
    },
  );
});

describe('failure mapping', () => {
  it('lists exactly the twelve accounting codes of the contract', () => {
    expect(ACCOUNTING_CODES).toHaveLength(12);
    expect(new Set(ACCOUNTING_CODES).size).toBe(12);
  });

  it.each(ACCOUNTING_CODES)('maps %s to a translation key, never to server text', (code) => {
    const failure = accountingFailureOf(response(409), problem(code));
    expect(failure.messageKey).toBe(`errors.${code}`);
    expect(failure.code).toBe(code);
    expect(failure.correlationId).toBe('corr-1');
    expect(JSON.stringify(failure)).not.toContain('server text');
  });

  it.each(['en', 'fr'] as const)('has a finished %s message for every code', (locale) => {
    const errors = resources[locale].common.errors as Record<string, string>;
    for (const code of ACCOUNTING_CODES) {
      expect(errors[code], code).toBeTruthy();
    }
  });

  it('classifies stale, missing and domain failures', () => {
    expect(
      accountingFailureOf(response(409), problem('ACCOUNTING_JOURNAL_PREVIEW_STALE')).kind,
    ).toBe('stale');
    expect(
      accountingFailureOf(response(409), problem('ACCOUNTING_MAPPING_PREVIEW_STALE')).kind,
    ).toBe('stale');
    expect(accountingFailureOf(response(404), problem('ACCOUNTING_JOURNAL_NOT_FOUND')).kind).toBe(
      'notFound',
    );
    expect(accountingFailureOf(response(404), problem('PAYROLL_PACKAGE_NOT_FOUND')).kind).toBe(
      'notFound',
    );
    expect(accountingFailureOf(response(422), problem('ACCOUNTING_MAPPING_INVALID')).kind).toBe(
      'domain',
    );
    expect(accountingFailureOf(response(409), problem('ACCOUNTING_MAPPING_OVERLAP')).kind).toBe(
      'domain',
    );
    expect(accountingFailureOf(response(403), problem('MFA_REQUIRED')).kind).toBe('forbidden');
  });

  it('keeps the retry delay of a rate limit and falls back for unknown codes', () => {
    const limited = accountingFailureOf(
      response(429, { 'Retry-After': '12' }),
      problem('RATE_LIMITED'),
    );
    expect(limited).toMatchObject({ kind: 'rateLimited', retryAfter: 12 });
    expect(accountingFailureOf(response(500), problem('SOMETHING_NEW'))).toMatchObject({
      kind: 'general',
      messageKey: 'errors.generic',
    });
    expect(accountingFailureOf(response(403), undefined)).toMatchObject({
      kind: 'forbidden',
      messageKey: 'accounting.forbidden',
    });
  });
});

describe('download names', () => {
  const view = { legalEntityCode: 'LE1', period: '2026-09', currency: 'CDF', version: 1 } as const;
  it('builds the generated names exactly as the server does', () => {
    expect(journalFileName(view, 'csv')).toBe('accounting-journal-LE1-2026-09-CDF-v1.csv');
    expect(journalFileName(view, 'manifest')).toBe(
      'accounting-journal-LE1-2026-09-CDF-v1.manifest.json',
    );
  });
  it('reads the Content-Disposition name only when it has the generated shape', () => {
    expect(
      safeDispositionName('attachment; filename="accounting-journal-LE1-2026-09-CDF-v1.csv"'),
    ).toBe('accounting-journal-LE1-2026-09-CDF-v1.csv');
    expect(
      safeDispositionName('attachment; filename=accounting-journal-A-2026-09-USD-v1.manifest.json'),
    ).toBe('accounting-journal-A-2026-09-USD-v1.manifest.json');
    expect(safeDispositionName('attachment; filename="../../etc/passwd"')).toBeNull();
    expect(safeDispositionName('attachment; filename="Marie Dupont.csv"')).toBeNull();
    expect(safeDispositionName(null)).toBeNull();
  });
});

describe('accounting wording', () => {
  it.each(['en', 'fr'] as const)(
    'carries the fixed notice and no forbidden wording in %s',
    (locale) => {
      const { notice, ...rest } = resources[locale].common.accounting;
      expect(notice).toBe(
        locale === 'en'
          ? 'Accounting export only — not posted and not proof of payment.'
          : 'Export comptable uniquement — ni comptabilisé ni preuve de paiement.',
      );
      expect(JSON.stringify(rest)).not.toMatch(FORBIDDEN_WORDING);
      const errors = resources[locale].common.errors as Record<string, string>;
      for (const code of ACCOUNTING_CODES) expect(errors[code]).not.toMatch(FORBIDDEN_WORDING);
    },
  );
});

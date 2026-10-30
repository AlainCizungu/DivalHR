import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import {
  APPROVAL_BLOCKERS,
  APPROVAL_WARNINGS,
  documentLocale,
  explanationValid,
  FORBIDDEN_PHRASES,
  PACKAGE_CODES,
  packageFailureOf,
  payslipFileName,
  VOID_BLOCKERS,
  VOID_REASONS,
} from './payslips';

const response = (status: number, headers: Record<string, string> = {}) =>
  new Response(null, { status, headers });
const problem = (code: string) => ({ code, correlationId: 'corr-1' });

describe('failure mapping', () => {
  it.each([
    ['PAYROLL_PACKAGE_NOT_FOUND', 404, 'notFound'],
    ['PAYSLIP_NOT_FOUND', 404, 'notFound'],
    ['PAYROLL_PACKAGE_BLOCKED', 409, 'domain'],
    ['PAYROLL_APPROVAL_PREVIEW_STALE', 409, 'stale'],
    ['PAYROLL_APPROVAL_NO_CHANGE', 409, 'stale'],
    ['PAYROLL_PACKAGE_VERSION_CONFLICT', 409, 'stale'],
    ['PAYROLL_PACKAGE_ALREADY_APPROVED', 409, 'stale'],
    ['PAYROLL_PACKAGE_VOIDED', 409, 'stale'],
    ['PAYROLL_VOID_PREVIEW_STALE', 409, 'stale'],
    ['PAYROLL_VOID_NOT_ALLOWED', 409, 'domain'],
    ['PAYSLIP_RENDER_FAILED', 500, 'domain'],
    ['PAYROLL_PACKAGE_LIMIT_REACHED', 409, 'domain'],
    ['EMPLOYEE_LINK_REQUIRED', 403, 'forbidden'],
    ['MFA_REQUIRED', 403, 'forbidden'],
    ['IDEMPOTENCY_KEY_REUSED', 409, 'stale'],
    ['LOCALE_NOT_SUPPORTED', 400, 'general'],
  ] as const)('maps %s', (code, status, kind) => {
    const failure = packageFailureOf(response(status), problem(code));
    expect(failure.kind).toBe(kind);
    expect(failure.messageKey).toBe(`errors.${code}`);
    expect(failure.correlationId).toBe('corr-1');
  });

  it('never shows an unknown code or server text', () => {
    const failure = packageFailureOf(response(500), { code: 'SOMETHING_ELSE', title: 'secret' });
    expect(failure.messageKey).toBe('errors.generic');
    expect(JSON.stringify(failure)).not.toContain('secret');
  });

  it('reads the rate-limit wait and treats 429 as rate limited', () => {
    const failure = packageFailureOf(response(429, { 'Retry-After': '7' }), undefined);
    expect(failure).toMatchObject({ kind: 'rateLimited', retryAfter: 7 });
  });

  it('maps validation and bare 403 to feature messages', () => {
    expect(packageFailureOf(response(400), problem('VALIDATION_FAILED')).messageKey).toBe(
      'payrollPackages.validation',
    );
    expect(packageFailureOf(response(403), undefined).messageKey).toBe('payrollPackages.forbidden');
  });

  it('has an English and a French message for every code', () => {
    for (const locale of ['en', 'fr'] as const) {
      for (const code of PACKAGE_CODES) {
        expect(resources[locale].common.errors[code].length).toBeGreaterThan(10);
      }
    }
  });
});

describe('helpers', () => {
  it('builds the safe local file name from the period and 8 hex of the opaque id', () => {
    expect(payslipFileName('2026-09', 'aaaaaaaa-0000-4000-8000-000000000001')).toBe(
      'payslip-2026-09-aaaaaaaa.pdf',
    );
  });

  it('chooses the document language from the interface language', () => {
    expect(documentLocale('fr')).toBe('fr');
    expect(documentLocale('fr-CD')).toBe('fr');
    expect(documentLocale('en-US')).toBe('en');
    expect(documentLocale('sw')).toBe('en');
  });

  it('accepts an explanation of 10 to 500 trimmed characters only', () => {
    expect(explanationValid('too short')).toBe(false);
    expect(explanationValid('   ten chars! ')).toBe(true);
    expect(explanationValid(`${' '.repeat(20)}x`)).toBe(false);
    expect(explanationValid('x'.repeat(500))).toBe(true);
    expect(explanationValid('x'.repeat(501))).toBe(false);
  });
});

type Tree = { [key: string]: string | Tree };
const flatten = (tree: Tree, prefix = ''): [string, string][] =>
  Object.entries(tree).flatMap(([key, value]) =>
    typeof value === 'string' ? [[`${prefix}${key}`, value]] : flatten(value, `${prefix}${key}.`),
  );

describe.each(['en', 'fr'] as const)('payroll package and payslip wording (%s)', (locale) => {
  const common = resources[locale].common as unknown as Record<string, Tree>;
  const own = [
    ...flatten(common.payrollPackages as Tree, 'payrollPackages.'),
    ...flatten(common.payslips as Tree, 'payslips.'),
  ];

  it('never says paid, payment completed, filed, tax compliant or government approved', () => {
    expect(own.length).toBeGreaterThan(150);
    for (const [key, value] of own) {
      for (const pattern of FORBIDDEN_PHRASES[locale]) {
        expect(pattern.test(value), `${key}: ${value}`).toBe(false);
      }
    }
  });

  it('carries the mandatory product wording', () => {
    const t = common.payrollPackages as Tree;
    const expected =
      locale === 'en'
        ? ['Approved payroll', 'Not proof of payment', 'Statutory calculation based on rule pack']
        : [
            'Paie approuvée',
            'Ne constitue pas une preuve de paiement',
            'Calcul statutaire fondé sur le jeu de règles',
          ];
    expect((t.status as Tree).APPROVED).toBe(expected[0]);
    expect(t.notProofOfPayment).toBe(expected[1]);
    expect(t.rulePackStatement).toBe(`${expected[2]} {{code}} v{{version}}`);
    expect((common.payslips as Tree).view).toMatchObject({
      title: locale === 'en' ? 'Payslip' : 'Bulletin de paie',
    });
    expect(((common.payslips as Tree).my as Tree).title).toBe(
      locale === 'en' ? 'My payslips' : 'Mes bulletins de paie',
    );
  });

  it('has a message for every closed code', () => {
    const t = common.payrollPackages as Tree;
    for (const code of APPROVAL_BLOCKERS) expect((t.blockers as Tree)[code]).toBeTruthy();
    for (const code of APPROVAL_WARNINGS) expect((t.warnings as Tree)[code]).toBeTruthy();
    for (const code of VOID_BLOCKERS) expect((t.voidBlockers as Tree)[code]).toBeTruthy();
    for (const code of VOID_REASONS) expect((t.voidReason as Tree)[code]).toBeTruthy();
  });

  it('defines every literal translation key the feature uses', () => {
    const flat = new Map(
      Object.entries(common).flatMap(([namespace, tree]) =>
        typeof tree === 'string'
          ? [[namespace, tree] as const]
          : flatten(tree, `${namespace}.`).map(([key, value]) => [key, value] as const),
      ),
    );
    const sources = import.meta.glob<string>('./*.{ts,tsx}', {
      query: '?raw',
      import: 'default',
      eager: true,
    });
    const missing: string[] = [];
    for (const [file, source] of Object.entries(sources)) {
      if (/\.test\.|fixtures|stub/u.test(file)) continue;
      for (const match of source.matchAll(/\bt(?:Doc)?\(\s*'([A-Za-z0-9_.]+)'/gu)) {
        const key = match[1] as string;
        const plural = ['_one', '_other'].some((suffix) => flat.has(`${key}${suffix}`));
        if (!flat.has(key) && !plural) missing.push(key);
      }
    }
    expect(missing).toEqual([]);
  });
});

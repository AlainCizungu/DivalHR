import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import {
  accountValid,
  BATCH_BLOCKERS,
  BATCH_WARNINGS,
  CANCEL_REASONS,
  DESTINATION_REASONS,
  FORBIDDEN_PHRASES,
  SUBMISSION_BLOCKERS,
  SUBMISSION_OUTCOMES,
  SUBMISSION_STATES,
  METHODS,
  minorToAmount,
  normalizeAccount,
  PAYMENT_CODES,
  paymentFailureOf,
  phoneValid,
  PROVIDERS,
} from './payments';
import { registerFileName } from './useRegisterDownload';

const response = (status: number, headers: Record<string, string> = {}) =>
  new Response(null, { status, headers });
const problem = (code: string) => ({ code, correlationId: 'corr-1' });

describe('failure mapping', () => {
  it.each([
    ['PAYMENT_DESTINATION_NOT_FOUND', 404, 'notFound'],
    ['PAYMENT_BATCH_NOT_FOUND', 404, 'notFound'],
    ['PAYMENT_DESTINATION_INVALID', 422, 'domain'],
    ['PAYMENT_DESTINATION_VERSION_CONFLICT', 409, 'stale'],
    ['PAYMENT_DESTINATION_PREVIEW_STALE', 409, 'stale'],
    ['PAYMENT_DESTINATION_KEY_UNAVAILABLE', 503, 'domain'],
    ['PAYMENT_BATCH_BLOCKED', 409, 'domain'],
    ['PAYMENT_BATCH_PREVIEW_STALE', 409, 'stale'],
    ['PAYMENT_BATCH_VERSION_CONFLICT', 409, 'stale'],
    ['PAYMENT_BATCH_ALREADY_PREPARED', 409, 'stale'],
    ['PAYMENT_BATCH_CANCELLED', 409, 'stale'],
    ['PAYMENT_BATCH_CANCEL_NOT_ALLOWED', 409, 'domain'],
    ['PAYMENT_BATCH_LIMIT_REACHED', 409, 'domain'],
    ['EMPLOYEE_LINK_REQUIRED', 403, 'forbidden'],
    ['MFA_REQUIRED', 403, 'forbidden'],
    ['IDEMPOTENCY_KEY_REUSED', 409, 'stale'],
  ] as const)('maps %s', (code, status, kind) => {
    const failure = paymentFailureOf(response(status), problem(code));
    expect(failure.kind).toBe(kind);
    expect(failure.messageKey).toBe(`errors.${code}`);
  });

  it('never shows an unknown code or server text', () => {
    const failure = paymentFailureOf(response(500), { code: 'OTHER', title: 'secret-account' });
    expect(failure.messageKey).toBe('errors.generic');
    expect(JSON.stringify(failure)).not.toContain('secret-account');
  });

  it('maps validation, rate limiting and a bare 403', () => {
    expect(paymentFailureOf(response(400), problem('VALIDATION_FAILED')).messageKey).toBe(
      'payments.validation',
    );
    expect(paymentFailureOf(response(429, { 'Retry-After': '9' }), undefined)).toMatchObject({
      kind: 'rateLimited',
      retryAfter: 9,
    });
    expect(paymentFailureOf(response(403), undefined).messageKey).toBe('payments.forbidden');
  });
});

describe('input helpers', () => {
  it('normalizes account numbers and checks their shape', () => {
    expect(normalizeAccount(' cd12-3456 7890 ')).toBe('CD1234567890');
    expect(accountValid('cd12 3456 7890')).toBe(true);
    expect(accountValid('12')).toBe(false);
    expect(accountValid('has/slash12345')).toBe(false);
  });

  it('accepts only E.164 phone numbers', () => {
    expect(phoneValid('+243990001122')).toBe(true);
    expect(phoneValid('0990001122')).toBe(false);
    expect(phoneValid('+0123456789')).toBe(false);
  });

  it('places the decimal point of integer minor units without floating point', () => {
    expect(minorToAmount(149040, 'CDF')).toBe('149040');
    expect(minorToAmount(14904000, 'USD')).toBe('149040.00');
    expect(minorToAmount(5, 'USD')).toBe('0.05');
    expect(minorToAmount(0, 'USD')).toBe('0.00');
  });

  it('names a downloaded register from the period and 8 hex of the opaque id only', () => {
    expect(registerFileName('2026-09', 'aaaaaaaa-0000-4000-8000-000000000001', 'csv')).toBe(
      'payment-register-2026-09-aaaaaaaa.csv',
    );
    expect(registerFileName('2026-09', 'aaaaaaaa-0000-4000-8000-000000000001', 'manifest')).toBe(
      'payment-register-2026-09-aaaaaaaa.json',
    );
  });
});

type Tree = { [key: string]: string | Tree };
const flatten = (tree: Tree, prefix = ''): [string, string][] =>
  Object.entries(tree).flatMap(([key, value]) =>
    typeof value === 'string' ? [[`${prefix}${key}`, value]] : flatten(value, `${prefix}${key}.`),
  );

describe.each(['en', 'fr'] as const)('provider submission wording (%s)', (locale) => {
  const common = resources[locale].common as unknown as Record<string, Tree>;
  const submission = common.paymentSubmission as Tree;

  it('labels every state, outcome and blocker and carries the fixed notice', () => {
    for (const state of SUBMISSION_STATES) {
      expect((submission.state as Tree)[state]).toBeTruthy();
      expect((submission.stateHelp as Tree)[state]).toBeTruthy();
    }
    for (const outcome of SUBMISSION_OUTCOMES) {
      expect((submission.outcomeLabel as Tree)[outcome]).toBeTruthy();
    }
    for (const blocker of SUBMISSION_BLOCKERS) {
      expect((submission.blockers as Tree)[blocker]).toBeTruthy();
    }
    expect(submission.notice).toBe(
      locale === 'en'
        ? 'Accepted for processing does not mean paid. DivalHR has not confirmed settlement.'
        : 'Accepté pour traitement ne signifie pas payé. DivalHR n’a pas confirmé le règlement.',
    );
    expect(submission.submit).toBe(
      locale === 'en' ? 'Submit to provider' : 'Soumettre au prestataire',
    );
  });

  it('never claims a payment: only the two negations may say paid', () => {
    const own = flatten(submission, 'paymentSubmission.');
    expect(own.length).toBeGreaterThan(60);
    for (const [key, value] of own) {
      if (key === 'paymentSubmission.notice' || key === 'paymentSubmission.understand') continue;
      for (const pattern of FORBIDDEN_PHRASES[locale]) {
        expect(pattern.test(value), `${key}: ${value}`).toBe(false);
      }
    }
  });
});

describe.each(['en', 'fr'] as const)('payment wording (%s)', (locale) => {
  const common = resources[locale].common as unknown as Record<string, Tree>;
  const payments = common.payments as Tree;

  it('has a message for every closed code and a label for every provider', () => {
    for (const code of PAYMENT_CODES) {
      expect(common.errors?.[code as keyof Tree]).toBeTruthy();
    }
    for (const m of METHODS) expect((payments.method as Tree)[m]).toBeTruthy();
    for (const r of DESTINATION_REASONS) expect((payments.reason as Tree)[r]).toBeTruthy();
    for (const r of CANCEL_REASONS) expect((payments.cancelReason as Tree)[r]).toBeTruthy();
    for (const b of BATCH_BLOCKERS) expect((payments.batchBlockers as Tree)[b]).toBeTruthy();
    for (const w of BATCH_WARNINGS) expect((payments.batchWarnings as Tree)[w]).toBeTruthy();
    for (const list of Object.values(PROVIDERS)) {
      for (const provider of list) {
        if (provider.name === '') expect((payments.provider as Tree)[provider.code]).toBeTruthy();
      }
    }
  });

  it('never claims that anything was sent, disbursed or paid', () => {
    const own = flatten(payments, 'payments.');
    expect(own.length).toBeGreaterThan(100);
    for (const [key, value] of own) {
      for (const pattern of FORBIDDEN_PHRASES[locale]) {
        expect(pattern.test(value), `${key}: ${value}`).toBe(false);
      }
    }
  });

  it('carries the mandatory product wording', () => {
    expect((payments.destination as Tree).title).toBe(
      locale === 'en' ? 'Payment destination' : 'Destination de paiement',
    );
    expect((payments.batch as Tree).title).toBe(
      locale === 'en' ? 'Prepare payment batch' : 'Préparer le lot de paiement',
    );
    expect((payments.my as Tree).title).toBe(
      locale === 'en' ? 'My payment destination' : 'Ma destination de paiement',
    );
  });
});

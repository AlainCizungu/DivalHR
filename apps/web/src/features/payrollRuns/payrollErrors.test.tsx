import { resources } from '@divalhr/localization';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import problemSchema from '../../../../../packages/shared-contracts/schemas/problem.schema.json';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { ENTITY_ID, RUN_ID } from './fixtures';
import { PayrollRunDetailPage } from './PayrollRunDetailPage';
import { PayrollRunsPage } from './PayrollRunsPage';
import { PAYROLL_CODES, PLATFORM_CODES, payrollFailureOf } from './payrollRuns';

/**
 * Test 12 (Issue #177): every payroll code of the contract renders a complete, translated
 * sentence in English and in French, never the raw code. The codes are read from the shared
 * problem schema, so a new code without a message cannot slip through.
 */
const schema = problemSchema as { properties: { code: { enum: string[] } } };
const CODES = schema.properties.code.enum.filter(
  (code) => code.startsWith('PAYROLL_POLICY_') || /^PAYROLL_(PERIOD|RUN)_/u.test(code),
);

function message(locale: 'en' | 'fr', code: string): string {
  const text = (resources[locale].common.errors as Record<string, string | undefined>)[code];
  if (text === undefined) throw new Error(`${code} has no ${locale} message`);
  return text;
}

const STATUS: Record<string, number> = {
  PAYROLL_POLICY_NOT_FOUND: 409,
  PAYROLL_PERIOD_INVALID: 422,
  PAYROLL_RUN_NOT_FOUND: 404,
};

function failWith(code: string, status = STATUS[code] ?? 409) {
  vi.stubGlobal(
    'fetch',
    vi.fn((incoming: Request) => {
      const path = new URL(incoming.url).pathname;
      if (path.endsWith('/legal-entities')) {
        return Promise.resolve(
          new Response(JSON.stringify({ data: [{ id: ENTITY_ID, code: 'LE-1', name: 'Test' }] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        );
      }
      return Promise.resolve(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'x',
            status,
            code,
            params: {},
            correlationId: 'corr-pay-4242',
          }),
          { status, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      );
    }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('payroll error messages', () => {
  it('covers all eight payroll codes of the contract and of the shared schema', () => {
    expect(CODES.sort()).toEqual([...PAYROLL_CODES].sort());
    expect(CODES).toHaveLength(8);
  });

  it.each([...PAYROLL_CODES])(
    '%s has a distinct, finished sentence in English and French',
    (code) => {
      const en = message('en', code);
      const fr = message('fr', code);
      for (const text of [en, fr]) {
        expect(text).toBeTruthy();
        expect(text).not.toContain(code);
        expect(text).not.toMatch(/\{\{|\}\}/u);
        expect(text.length).toBeGreaterThan(40);
        expect(text).toMatch(/[.!]$/u);
      }
      expect(fr).not.toBe(en);
      expect(fr).not.toMatch(/\p{L}'\p{L}/u);
      expect(fr).not.toBe(resources.fr.common.errors.generic);
      // No statutory, tax or net-pay promise in an error.
      expect(en).not.toMatch(/payslip|tax|net pay/iu);
    },
  );

  it.each([...PLATFORM_CODES])('platform code %s has a message in both languages', (code) => {
    for (const locale of ['en', 'fr'] as const) {
      const text = message(locale, code);
      expect(text.length).toBeGreaterThan(20);
      expect(text).not.toContain(code);
    }
  });

  it('maps platform and unknown codes to a message key, never to the raw code', () => {
    const response = (status: number, retry?: string) =>
      new Response(null, { status, headers: retry ? { 'Retry-After': retry } : {} });
    expect(payrollFailureOf(response(429, '7'), { code: 'RATE_LIMITED' })).toMatchObject({
      kind: 'rateLimited',
      messageKey: 'errors.RATE_LIMITED',
      retryAfter: 7,
    });
    expect(payrollFailureOf(response(409), { code: 'IDEMPOTENCY_KEY_REUSED' }).kind).toBe('stale');
    expect(payrollFailureOf(response(404), { code: 'LEGAL_ENTITY_NOT_FOUND' }).messageKey).toBe(
      'errors.LEGAL_ENTITY_NOT_FOUND',
    );
    expect(payrollFailureOf(response(403), { code: 'MFA_REQUIRED' }).messageKey).toBe(
      'errors.MFA_REQUIRED',
    );
    expect(payrollFailureOf(response(409), { code: 'EMPLOYMENT_CHANGE_TIMEOUT' }).messageKey).toBe(
      'errors.EMPLOYMENT_CHANGE_TIMEOUT',
    );
    expect(payrollFailureOf(response(500), { code: 'SOMETHING_NEW' }).messageKey).toBe(
      'errors.generic',
    );
    expect(payrollFailureOf(response(403), undefined).messageKey).toBe('payrollRuns.forbidden');
  });

  describe.each(['en', 'fr'] as const)('rendered in %s', (locale) => {
    it.each([...PAYROLL_CODES])('%s on the policy panel', async (code) => {
      failWith(code);
      await renderWithSession(
        <PayrollRunsPage />,
        sessionWithRoles(['tenant-admin']),
        locale,
        '/admin/payroll-runs',
      );
      const alert = await screen.findByTestId('payroll-policy-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(message(locale, code));
      expect(alert).toHaveTextContent('corr-pay-4242');
      expect(alert.textContent).not.toContain(code);
    });

    it.each([...PAYROLL_CODES])('%s on the run detail', async (code) => {
      failWith(code);
      await renderWithSession(
        <Routes>
          <Route
            path="/admin/payroll-runs/:legalEntityId/:runId"
            element={<PayrollRunDetailPage />}
          />
        </Routes>,
        sessionWithRoles(['tenant-admin']),
        locale,
        `/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}`,
      );
      const alert = await screen.findByTestId('payroll-detail-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(message(locale, code));
      expect(alert.textContent).not.toContain(code);
    });
  });
});

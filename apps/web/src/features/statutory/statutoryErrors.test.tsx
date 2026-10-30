import { resources } from '@divalhr/localization';
import { screen } from '@testing-library/react';
import { Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import problemSchema from '../../../../../packages/shared-contracts/schemas/problem.schema.json';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { EMPLOYEE_ID, ENTITY_ID, RUN_ID } from './fixtures';
import { PLATFORM_CODES, STATUTORY_CODES, statutoryFailureOf } from './statutory';
import { StatutoryProfilePage } from './StatutoryProfilePage';
import { StatutoryRunPage } from './StatutoryRunPage';

/**
 * Every statutory code of the contract renders a complete, translated sentence in English and in
 * French, never the raw code. The codes are read from the shared problem schema, so a new code
 * without a message cannot slip through.
 */
const schema = problemSchema as { properties: { code: { enum: string[] } } };
const CODES = schema.properties.code.enum.filter((code) => code.startsWith('STATUTORY_'));

function message(locale: 'en' | 'fr', code: string): string {
  const text = (resources[locale].common.errors as Record<string, string | undefined>)[code];
  if (text === undefined) throw new Error(`${code} has no ${locale} message`);
  return text;
}

function failWith(code: string, status = 409) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() =>
      Promise.resolve(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'x',
            status,
            code,
            params: {},
            correlationId: 'corr-stat-4242',
          }),
          { status, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    ),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

const response = (status: number, retry?: string) =>
  new Response(null, { status, headers: retry ? { 'Retry-After': retry } : {} });

describe('statutory error messages', () => {
  it('covers all eleven statutory codes of the contract and of the shared schema', () => {
    expect([...CODES].sort()).toEqual([...STATUTORY_CODES].sort());
    expect(CODES).toHaveLength(11);
  });

  it.each([...STATUTORY_CODES])(
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
      expect(en).not.toMatch(/compliant|compliance/iu);
    },
  );

  it.each([...PLATFORM_CODES])('platform code %s has a message in both languages', (code) => {
    for (const locale of ['en', 'fr'] as const) {
      const text = message(locale, code);
      expect(text.length).toBeGreaterThan(20);
      expect(text).not.toContain(code);
    }
  });

  it('maps the statutory codes to their kinds', () => {
    const kind = (code: string) => statutoryFailureOf(response(409), { code }).kind;
    expect(kind('STATUTORY_PREVIEW_STALE')).toBe('stale');
    expect(kind('STATUTORY_VERSION_CONFLICT')).toBe('stale');
    expect(kind('STATUTORY_CALCULATION_NO_CHANGE')).toBe('domain');
    expect(kind('STATUTORY_RULE_PACK_NOT_VERIFIED')).toBe('domain');
    expect(kind('STATUTORY_RULE_PACK_NOT_APPLICABLE')).toBe('domain');
    expect(kind('STATUTORY_PROFILE_MISSING')).toBe('domain');
    expect(kind('STATUTORY_PROFILE_UNSUPPORTED')).toBe('domain');
    expect(kind('STATUTORY_CALCULATION_BLOCKED')).toBe('domain');
    expect(kind('STATUTORY_LIMIT_REACHED')).toBe('domain');
    expect(kind('STATUTORY_RULE_PACK_NOT_FOUND')).toBe('notFound');
    expect(kind('STATUTORY_CALCULATION_NOT_FOUND')).toBe('notFound');
    for (const code of STATUTORY_CODES) {
      expect(statutoryFailureOf(response(409), { code }).messageKey).toBe(`errors.${code}`);
    }
  });

  it('maps platform and unknown codes to a message key, never to the raw code', () => {
    expect(statutoryFailureOf(response(429, '7'), { code: 'RATE_LIMITED' })).toMatchObject({
      kind: 'rateLimited',
      messageKey: 'errors.RATE_LIMITED',
      retryAfter: 7,
    });
    expect(statutoryFailureOf(response(409), { code: 'IDEMPOTENCY_KEY_REUSED' }).kind).toBe(
      'stale',
    );
    for (const code of [
      'LEGAL_ENTITY_NOT_FOUND',
      'PAYROLL_RUN_NOT_FOUND',
      'COMPENSATION_NOT_FOUND',
    ]) {
      expect(statutoryFailureOf(response(404), { code })).toMatchObject({
        kind: 'notFound',
        messageKey: `errors.${code}`,
      });
    }
    expect(statutoryFailureOf(response(403), { code: 'MFA_REQUIRED' }).messageKey).toBe(
      'errors.MFA_REQUIRED',
    );
    expect(statutoryFailureOf(response(403), { code: 'ACCESS_DENIED' }).kind).toBe('forbidden');
    expect(statutoryFailureOf(response(400), { code: 'VALIDATION_FAILED' }).messageKey).toBe(
      'statutory.validation',
    );
    expect(statutoryFailureOf(response(500), { code: 'SOMETHING_NEW' }).messageKey).toBe(
      'errors.generic',
    );
    expect(statutoryFailureOf(response(403), undefined).messageKey).toBe('statutory.forbidden');
  });

  describe.each(['en', 'fr'] as const)('rendered in %s', (locale) => {
    it.each([...STATUTORY_CODES])('%s on the statutory run page', async (code) => {
      failWith(code);
      await renderWithSession(
        <Routes>
          <Route
            path="/admin/payroll-runs/:legalEntityId/:runId/statutory"
            element={<StatutoryRunPage />}
          />
        </Routes>,
        sessionWithRoles(['tenant-admin']),
        locale,
        `/admin/payroll-runs/${ENTITY_ID}/${RUN_ID}/statutory`,
      );
      const alert = await screen.findByTestId('statutory-run-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(message(locale, code));
      expect(alert).toHaveTextContent('corr-stat-4242');
      expect(alert.textContent).not.toContain(code);
    });

    it.each([...STATUTORY_CODES])('%s on the statutory profile page', async (code) => {
      failWith(code);
      await renderWithSession(
        <Routes>
          <Route
            path="/admin/people/:employeeId/statutory-profile"
            element={<StatutoryProfilePage />}
          />
        </Routes>,
        sessionWithRoles(['tenant-admin']),
        locale,
        `/admin/people/${EMPLOYEE_ID}/statutory-profile`,
      );
      const alert = await screen.findByTestId('statutory-profile-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(message(locale, code));
      expect(alert.textContent).not.toContain(code);
    });
  });
});

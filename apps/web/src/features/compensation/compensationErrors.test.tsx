import { resources } from '@divalhr/localization';
import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import problemSchema from '../../../../../packages/shared-contracts/schemas/problem.schema.json';
import { renderWithSession, sessionWithRoles } from '../../test/renderWithSession';
import { EmployeeCompensationSection } from './EmployeeCompensationSection';
import { MyCompensationPage } from './MyCompensationPage';

/**
 * R176-1: every compensation error code the API can return renders a real, translated sentence in
 * English and in French, in the administrator section and on the employee page. The codes are read
 * from the shared problem schema, so a new code without a message cannot slip through.
 */
const schema = problemSchema as { properties: { code: { enum: string[] } } };
const CODES = schema.properties.code.enum.filter((code) => code.startsWith('COMPENSATION_'));

const EMPLOYEE_ID = '22222222-0000-4000-8000-000000000002';
const STATUS: Record<string, number> = {
  COMPENSATION_NOT_FOUND: 404,
  COMPENSATION_INVALID_AMOUNT: 400,
  COMPENSATION_DATE_OUT_OF_RANGE: 400,
};

function failWith(code: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => {
      const status = STATUS[code] ?? 409;
      return Promise.resolve(
        new Response(
          JSON.stringify({
            type: 'about:blank',
            title: 'x',
            status,
            code,
            params: {},
            correlationId: 'corr-comp-4242',
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

describe('compensation error messages', () => {
  it('covers all eight compensation codes of the contract', () => {
    expect(CODES).toHaveLength(8);
  });

  it.each(CODES)('%s has a distinct, finished sentence in English and French', (code) => {
    const en = (resources.en.common.errors as Record<string, string>)[code];
    const fr = (resources.fr.common.errors as Record<string, string>)[code];
    for (const text of [en, fr]) {
      expect(text).toBeTruthy();
      expect(text).not.toContain(code);
      expect(text).not.toMatch(/\{\{|\}\}/u);
      expect(text.length).toBeGreaterThan(40);
      expect(text).toMatch(/[.!]$/u);
    }
    expect(fr).not.toBe(en);
    // French keeps its typographic apostrophe, never a straight one inside a word.
    expect(fr).not.toMatch(/\p{L}'\p{L}/u);
    const generic = resources.fr.common.errors.generic;
    expect(fr).not.toBe(generic);
  });

  describe.each(['en', 'fr'] as const)('rendered in %s', (locale) => {
    it.each(CODES)('%s on the administrator section', async (code) => {
      failWith(code);
      await renderWithSession(
        <EmployeeCompensationSection employeeId={EMPLOYEE_ID} revision={0} onChanged={() => {}} />,
        sessionWithRoles(['tenant-admin']),
        locale,
        `/admin/people/${EMPLOYEE_ID}`,
      );
      const alert = await screen.findByTestId('compensation-load-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(
        (resources[locale].common.errors as Record<string, string>)[code],
      );
      expect(alert).toHaveTextContent('corr-comp-4242');
      expect(alert.textContent).not.toContain(code);
    });

    it.each(CODES)('%s on the employee page', async (code) => {
      failWith(code);
      await renderWithSession(
        <MyCompensationPage />,
        sessionWithRoles(['employee']),
        locale,
        '/me/compensation',
      );
      const alert = await screen.findByTestId('compensation-error');
      expect(alert).toHaveAttribute('data-code', code);
      expect(alert).toHaveTextContent(
        (resources[locale].common.errors as Record<string, string>)[code],
      );
      expect(alert.textContent).not.toContain(code);
    });
  });
});

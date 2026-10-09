import { describe, expect, it } from 'vitest';
import {
  EMPTY_FORM,
  bodyOf,
  leaveFailureOf,
  parseEntitlement,
  problemsOf,
  toneOf,
  type LeavePolicyForm,
} from './leavePolicies';

const VALID: LeavePolicyForm = {
  code: ' annual-1 ',
  nameEn: ' Annual leave ',
  nameFr: 'Congé annuel',
  unit: 'DAYS',
  balanceMode: 'TRACKED',
  annualEntitlement: '18,5',
  minimumServiceDays: '0',
  approvalRoute: 'MANAGER',
  payrollEffect: 'PAID',
  effectiveFrom: '2026-01-01',
  effectiveTo: '',
};

const response = (status: number, headers: Record<string, string> = {}) =>
  new Response(null, { status, headers });

describe('MVP-040A leave policy form', () => {
  it('accepts a valid form and builds the exact request body', () => {
    expect([...problemsOf(VALID)]).toEqual([]);
    expect(bodyOf(VALID)).toEqual({
      code: 'ANNUAL-1',
      names: { en: 'Annual leave', fr: 'Congé annuel' },
      unit: 'DAYS',
      balanceMode: 'TRACKED',
      annualEntitlement: 18.5,
      minimumServiceDays: 0,
      approvalRoute: 'MANAGER',
      payrollEffect: 'PAID',
      effectiveFrom: '2026-01-01',
    });
    const untracked = { ...VALID, balanceMode: 'UNTRACKED' as const, effectiveTo: '2026-01-01' };
    expect([...problemsOf(untracked)]).toEqual([]);
    expect(bodyOf(untracked)).not.toHaveProperty('annualEntitlement');
    expect(bodyOf(untracked).effectiveTo).toBe('2026-01-01');
  });

  it('suggests no value: an empty form names every required field', () => {
    expect([...problemsOf(EMPTY_FORM)]).toEqual([
      'code',
      'nameEn',
      'nameFr',
      'unit',
      'balanceMode',
      'minimumServiceDays',
      'approvalRoute',
      'payrollEffect',
      'effectiveFrom',
    ]);
  });

  it('checks codes, names, numbers and dates like the server', () => {
    for (const code of ['A', 'A'.repeat(21), '-AB', 'A B', 'ÉTÉ']) {
      expect(problemsOf({ ...VALID, code }).has('code'), code).toBe(true);
    }
    for (const name of ['x', 'é'.repeat(101), 'Tab\there', 'Bidi\u202Eoverride']) {
      expect(problemsOf({ ...VALID, nameFr: name }).has('nameFr'), name).toBe(true);
    }
    // 100 code points, astral characters included.
    expect(problemsOf({ ...VALID, nameEn: '𝔸'.repeat(100) }).has('nameEn')).toBe(false);
    for (const value of ['0', '-1', '10000.01', '1.005', '1e3', '', 'abc']) {
      expect(parseEntitlement(value), value).toBeNull();
    }
    expect(parseEntitlement('10000')).toBe(10000);
    expect(parseEntitlement('0,01')).toBe(0.01);
    for (const days of ['', '-1', '3651', '1.5', 'ten']) {
      expect(problemsOf({ ...VALID, minimumServiceDays: days }).has('minimumServiceDays')).toBe(
        true,
      );
    }
    expect(problemsOf({ ...VALID, minimumServiceDays: '3650' }).size).toBe(0);
    expect(problemsOf({ ...VALID, effectiveFrom: '2026-02-30' }).has('effectiveFrom')).toBe(true);
    expect(problemsOf({ ...VALID, effectiveFrom: '1899-12-31' }).has('effectiveFrom')).toBe(true);
    expect(problemsOf({ ...VALID, effectiveTo: '2025-12-31' }).has('effectiveTo')).toBe(true);
  });

  it('maps refusals to closed kinds and allow-listed fields only', () => {
    expect(
      leaveFailureOf(response(403), { code: 'MFA_REQUIRED', correlationId: 'corr-1' }),
    ).toMatchObject({ kind: 'forbidden', messageKey: 'leavePolicies.unauthorized' });
    expect(
      leaveFailureOf(response(429, { 'Retry-After': '12' }), { code: 'RATE_LIMITED' }),
    ).toMatchObject({ kind: 'rateLimited', retryAfter: 12 });
    expect(leaveFailureOf(response(409), { code: 'LEAVE_POLICY_CODE_EXISTS' })).toMatchObject({
      kind: 'conflict',
      messageKey: 'errors.LEAVE_POLICY_CODE_EXISTS',
    });
    expect(
      leaveFailureOf(response(400), {
        code: 'VALIDATION_FAILED',
        params: {
          fields: [
            { field: 'effectiveTo', constraint: 'RANGE' },
            { field: 'names.en', constraint: 'LENGTH' },
            { field: '__proto__', constraint: 'FORMAT' },
            { field: 'body', constraint: 'UNKNOWN_PROPERTY' },
          ],
        },
      }),
    ).toMatchObject({ kind: 'validation', fields: ['nameEn', 'effectiveTo'] });
    expect(leaveFailureOf(response(500), undefined)).toMatchObject({
      kind: 'general',
      messageKey: 'errors.generic',
    });
  });

  it('gives each status a tone while the text carries the meaning', () => {
    expect(toneOf('ACTIVE')).toBe('success');
    expect(toneOf('PLANNED')).toBe('info');
    expect(toneOf('ENDED')).toBe('neutral');
  });
});

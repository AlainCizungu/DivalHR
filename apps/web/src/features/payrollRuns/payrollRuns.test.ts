import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import { closedMonths, formatMonth, payDayOf, spanDays } from './payrollRuns';

describe('closedMonths', () => {
  it('lists the 24 months before the business month, newest first', () => {
    const months = closedMonths('2026-10-29');
    expect(months).toHaveLength(24);
    expect(months[0]).toBe('2026-09');
    expect(months[23]).toBe('2024-10');
    expect(months).not.toContain('2026-10');
  });

  it('crosses the year boundary', () => {
    const months = closedMonths('2026-01-01');
    expect(months[0]).toBe('2025-12');
    expect(months[1]).toBe('2025-11');
    expect(new Set(months).size).toBe(24);
  });
});

describe('helpers', () => {
  it('builds the pay day object of the contract', () => {
    expect(payDayOf('LAST_DAY')).toEqual({ kind: 'LAST_DAY', day: null });
    expect(payDayOf('25')).toEqual({ kind: 'DAY', day: 25 });
  });

  it('formats a month in the locale and counts inclusive days', () => {
    expect(formatMonth('en', '2026-09')).toBe('September 2026');
    expect(formatMonth('fr', '2026-09')).toBe('septembre 2026');
    expect(spanDays('2026-09-16', '2026-09-30')).toBe(15);
  });
});

describe('required labels', () => {
  it('uses the exact English and French wording', () => {
    const en = resources.en.common.payrollRuns;
    const fr = resources.fr.common.payrollRuns;
    expect(en.contractualGross).toBe('Contractual gross');
    expect(en.draftLabel).toBe('Draft—not statutory payroll');
    expect(fr.contractualGross).toBe('Brut contractuel');
    expect(fr.draftLabel).toBe('Brouillon — pas une paie statutaire');
    expect(en.status.DRAFT).toBe(en.draftLabel);
    expect(fr.status.DRAFT).toBe(fr.draftLabel);
  });

  it('claims no tax, compliance or net-pay wording in the run copy', () => {
    for (const locale of ['en', 'fr'] as const) {
      const text = JSON.stringify(resources[locale].common.payrollRuns);
      expect(text).not.toMatch(/\bcompliant\b|\bconforme\b|filing|déclaration fiscale/iu);
    }
  });
});

import { resources } from '@divalhr/localization';
import { describe, expect, it } from 'vitest';
import { bracketBounds, formatBasisPoints, monthOf, ruleFor, sourceFor } from './statutory';
import { rules, sources } from './fixtures';

describe('statutory helpers', () => {
  it('formats a basis-point rate for the locale without calculating anything else', () => {
    expect(formatBasisPoints('en', 500)).toBe('5%');
    expect(formatBasisPoints('en', 150)).toBe('1.5%');
    expect(formatBasisPoints('fr', 650).replace(/\s/gu, ' ')).toBe('6,5 %');
  });

  it('finds a rule and a source by id and key', () => {
    expect(ruleFor(rules() as never, 'IRPP_SALARY_WITHHOLDING')?.sourceKey).toBe('DGI_IRPP');
    expect(sourceFor(sources() as never, 'CNSS_RATES')?.url).toBe('https://cnss.cd/?page_id=1388');
    expect(ruleFor(rules() as never, 'NOPE')).toBeUndefined();
    expect(sourceFor(sources() as never, 'DGI_PORTAL')).toBeUndefined();
  });

  it('pairs each bracket with its lower bound as a string, ending open', () => {
    expect(
      bracketBounds([
        { upToAnnual: '1944000', rateBp: 300 },
        { upToAnnual: '21600000', rateBp: 1500 },
        { upToAnnual: null, rateBp: 4000 },
      ]),
    ).toEqual([
      { from: '0', to: '1944000', rateBp: 300 },
      { from: '1944000', to: '21600000', rateBp: 1500 },
      { from: '21600000', to: null, rateBp: 4000 },
    ]);
    expect(monthOf('2026-09-01')).toBe('2026-09');
  });
});

describe('required labels', () => {
  it('uses the exact English and French wording', () => {
    const en = resources.en.common.statutory;
    const fr = resources.fr.common.statutory;
    expect(en.label).toBe('Statutory preview—not a payslip or payment');
    expect(fr.label).toBe('Aperçu statutaire — ni bulletin de paie ni paiement');
    expect(en.verificationRequired).toBe('Professional verification required');
    expect(fr.verificationRequired).toBe('Vérification professionnelle requise');
  });

  it('never claims compliance and uses typographic apostrophes in French', () => {
    for (const locale of ['en', 'fr'] as const) {
      const text = JSON.stringify(resources[locale].common.statutory);
      expect(text).not.toMatch(
        /compliant payroll|payroll compliance|paie conforme|conformité de la paie|legally compliant/iu,
      );
    }
    expect(JSON.stringify(resources.fr.common.statutory)).not.toMatch(/\p{L}'\p{L}/u);
  });

  it('has the same keys in English and French', () => {
    const keys = (value: unknown, prefix = ''): string[] =>
      typeof value === 'object' && value !== null
        ? Object.entries(value).flatMap(([key, child]) => keys(child, `${prefix}${key}.`))
        : [prefix];
    expect(keys(resources.fr.common.statutory).sort()).toEqual(
      keys(resources.en.common.statutory).sort(),
    );
  });
});

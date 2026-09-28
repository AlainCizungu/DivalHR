import { describe, expect, it } from 'vitest';
// @ts-expect-error -- plain ESM script shared with CI, no type declarations
import { compareLocales, flatten, loadLocales } from '../scripts/check-parity.mjs';
import { fileURLToPath } from 'node:url';

describe('translation parity', () => {
  it('passes for the committed French and English locales', () => {
    const root = fileURLToPath(new URL('../locales', import.meta.url));
    expect(compareLocales(loadLocales(root))).toEqual([]);
  });

  it('reports a key missing in French', () => {
    const problems = compareLocales({
      en: { common: { a: 'A', b: 'B' } },
      fr: { common: { a: 'A' } },
    });
    expect(problems).toContain('fr/common: missing key "b"');
  });

  it('reports a key missing in English', () => {
    const problems = compareLocales({ en: { common: {} }, fr: { common: { x: 'X' } } });
    expect(problems).toContain('en/common: missing key "x"');
  });

  it('reports empty values and placeholder mismatches', () => {
    const problems = compareLocales({
      en: { common: { a: 'Hello {{name}}', b: 'B' } },
      fr: { common: { a: 'Bonjour {{nom}}', b: ' ' } },
    });
    expect(problems.some((p: string) => p.includes('placeholder mismatch'))).toBe(true);
    expect(problems.some((p: string) => p.includes('empty'))).toBe(true);
  });

  it('reports a missing namespace file', () => {
    const problems = compareLocales({ en: { common: {}, people: {} }, fr: { common: {} } });
    expect(problems).toContain('fr/people.json is missing');
  });

  it('flattens nested keys', () => {
    expect(flatten({ a: { b: { c: 'x' } } })).toEqual({ 'a.b.c': 'x' });
  });
});

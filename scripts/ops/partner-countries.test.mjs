import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { TARGET, readCatalogue, render } from './partner-countries.mjs';

const codes = readCatalogue();

test('the Core catalogue is the 249 assigned ISO 3166-1 alpha-2 codes, sorted and unique', () => {
  assert.equal(codes.length, 249);
  assert.deepEqual([...new Set(codes)].sort(), codes);
  for (const code of codes) assert.match(code, /^[A-Z]{2}$/u);
  for (const absent of ['OTHER', 'XK', 'EU', 'UN', 'ZZ', 'AN', 'UK', 'AC', 'CP']) {
    assert.ok(!codes.includes(absent), absent);
  }
});

test('representative DRC, Francophone, Anglophone and international countries are present', () => {
  const representative = [
    'CD',
    'CG',
    'CI',
    'SN',
    'CM',
    'GA',
    'ML',
    'BF',
    'TG',
    'BJ',
    'MG',
    'MA',
    'TN',
    'DZ',
    'RW',
    'BI',
    'KE',
    'NG',
    'GH',
    'ZA',
    'UG',
    'TZ',
    'ZM',
    'SS',
    'FR',
    'BE',
    'CH',
    'CA',
    'US',
    'GB',
    'DE',
    'JP',
    'BR',
    'IN',
  ];
  for (const code of representative) assert.ok(codes.includes(code), code);
});

test('every code has an English and a French name from Intl.DisplayNames', () => {
  for (const locale of ['en', 'fr']) {
    const names = new Intl.DisplayNames([locale], { type: 'region', fallback: 'none' });
    for (const code of codes) {
      const name = names.of(code);
      assert.ok(name && name !== code, `${locale} ${code}`);
    }
  }
});

test('the generated web catalogue matches the Core catalogue exactly', () => {
  assert.equal(readFileSync(TARGET, 'utf8'), render(codes));
});

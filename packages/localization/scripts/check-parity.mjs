#!/usr/bin/env node
// Fails (exit 1) when French and English locale files differ in keys, when a value is empty,
// or when interpolation placeholders differ. Run in CI; see docs/I18N.md.
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const REQUIRED_LOCALES = ['en', 'fr'];

export function flatten(object, prefix = '') {
  const entries = {};
  for (const [key, value] of Object.entries(object)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      Object.assign(entries, flatten(value, path));
    } else {
      entries[path] = value;
    }
  }
  return entries;
}

const placeholders = (value) => [...String(value).matchAll(/{{\s*(\w+)\s*}}/g)].map((m) => m[1]).sort();

export function compareLocales(byLocale) {
  const problems = [];
  const [base, ...others] = REQUIRED_LOCALES;
  for (const locale of REQUIRED_LOCALES) {
    if (!byLocale[locale]) problems.push(`missing locale "${locale}"`);
  }
  if (problems.length) return problems;
  const namespaces = new Set(REQUIRED_LOCALES.flatMap((l) => Object.keys(byLocale[l])));
  for (const ns of [...namespaces].sort()) {
    for (const locale of REQUIRED_LOCALES) {
      if (!byLocale[locale][ns]) problems.push(`${locale}/${ns}.json is missing`);
    }
    if (REQUIRED_LOCALES.some((l) => !byLocale[l][ns])) continue;
    const flat = Object.fromEntries(REQUIRED_LOCALES.map((l) => [l, flatten(byLocale[l][ns])]));
    for (const other of others) {
      for (const key of Object.keys(flat[base])) {
        if (!(key in flat[other])) problems.push(`${other}/${ns}: missing key "${key}"`);
      }
      for (const key of Object.keys(flat[other])) {
        if (!(key in flat[base])) problems.push(`${base}/${ns}: missing key "${key}"`);
      }
    }
    for (const locale of REQUIRED_LOCALES) {
      for (const [key, value] of Object.entries(flat[locale])) {
        if (typeof value !== 'string' || value.trim() === '') {
          problems.push(`${locale}/${ns}: empty or non-string value for "${key}"`);
        }
      }
    }
    for (const key of Object.keys(flat[base])) {
      for (const other of others) {
        if (!(key in flat[other])) continue;
        const a = placeholders(flat[base][key]).join(',');
        const b = placeholders(flat[other][key]).join(',');
        if (a !== b) problems.push(`${ns}: placeholder mismatch for "${key}" (${base}: [${a}], ${other}: [${b}])`);
      }
    }
  }
  return problems;
}

export function loadLocales(root) {
  const result = {};
  for (const locale of readdirSync(root)) {
    result[locale] = {};
    for (const file of readdirSync(join(root, locale)).filter((f) => f.endsWith('.json'))) {
      result[locale][file.replace(/\.json$/, '')] = JSON.parse(readFileSync(join(root, locale, file), 'utf8'));
    }
  }
  return result;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'locales');
  const problems = compareLocales(loadLocales(root));
  if (problems.length) {
    console.error(`Translation parity check failed (${problems.length} problem(s)):`);
    for (const p of problems) console.error(`  - ${p}`);
    process.exit(1);
  }
  console.log('Translation parity check passed for: ' + REQUIRED_LOCALES.join(', '));
}

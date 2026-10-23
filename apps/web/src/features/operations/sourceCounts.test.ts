import { resources } from '@divalhr/localization';
import { createInstance } from 'i18next';
import { describe, expect, it } from 'vitest';
import { sourceCountsText } from './operationalSummary';

/** Typographic non-breaking spaces compare equal to plain ones. */
const plain = (value: string): string => value.replace(/\u00a0/gu, ' ');

async function line(language: 'en' | 'fr', counts: Parameters<typeof sourceCountsText>[2]) {
  const i18n = createInstance();
  await i18n.init({
    lng: language,
    fallbackLng: 'en',
    resources,
    defaultNS: 'common',
    ns: ['common'],
    interpolation: { escapeValue: false },
  });
  const number = new Intl.NumberFormat(language);
  return plain(
    sourceCountsText(
      (key, options) => i18n.t(key, options),
      (value) => number.format(value),
      counts,
    ),
  );
}

const same = (value: number) => ({
  scheduled: value,
  notCheckedIn: value,
  leave: value,
  timesheets: value,
});

describe('the briefing source-count line: singular and plural by each count', () => {
  it('English: 0, 1, 2 and 5', async () => {
    expect(await line('en', same(0))).toBe(
      'Based on 0 scheduled shifts, 0 not checked in, 0 leave requests and 0 timesheets waiting.',
    );
    expect(await line('en', same(1))).toBe(
      'Based on 1 scheduled shift, 1 not checked in, 1 leave request and 1 timesheet waiting.',
    );
    expect(await line('en', same(2))).toBe(
      'Based on 2 scheduled shifts, 2 not checked in, 2 leave requests and 2 timesheets waiting.',
    );
    expect(await line('en', same(5))).toBe(
      'Based on 5 scheduled shifts, 5 not checked in, 5 leave requests and 5 timesheets waiting.',
    );
  });

  it('French: 0 and 1 are singular, 2 and 5 plural', async () => {
    expect(await line('fr', same(0))).toBe(
      'Fondée sur 0 quart planifié, 0 sans pointage, 0 demande de congé et 0 feuille de temps en attente.',
    );
    expect(await line('fr', same(1))).toBe(
      'Fondée sur 1 quart planifié, 1 sans pointage, 1 demande de congé et 1 feuille de temps en attente.',
    );
    expect(await line('fr', same(2))).toBe(
      'Fondée sur 2 quarts planifiés, 2 sans pointage, 2 demandes de congé et 2 feuilles de temps en attente.',
    );
    expect(await line('fr', same(5))).toBe(
      'Fondée sur 5 quarts planifiés, 5 sans pointage, 5 demandes de congé et 5 feuilles de temps en attente.',
    );
  });

  it('each noun follows its own count, as the live case did (6 requests, 1 timesheet)', async () => {
    const live = { scheduled: 0, notCheckedIn: 0, leave: 6, timesheets: 1 };
    expect(await line('en', live)).toBe(
      'Based on 0 scheduled shifts, 0 not checked in, 6 leave requests and 1 timesheet waiting.',
    );
    expect(await line('fr', live)).toBe(
      'Fondée sur 0 quart planifié, 0 sans pointage, 6 demandes de congé et 1 feuille de temps en attente.',
    );
  });
});

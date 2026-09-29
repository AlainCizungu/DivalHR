import {
  FALLBACK_LOCALE,
  isSupportedLocale,
  NAMESPACES,
  resources,
  type SupportedLocale,
} from '@divalhr/localization';
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import { readPreference, writePreference } from '../app/preferences';

/** Resolution order: stored user choice, then browser language, then internal fallback. */
export function detectInitialLocale(): SupportedLocale {
  const stored = readPreference('locale');
  if (isSupportedLocale(stored)) return stored;
  for (const language of navigator.languages) {
    const base = language.slice(0, 2).toLowerCase();
    if (isSupportedLocale(base)) return base;
  }
  return FALLBACK_LOCALE;
}

function syncDocument(locale: string) {
  document.documentElement.lang = locale;
}

export async function initI18n(locale: SupportedLocale = detectInitialLocale()) {
  if (!i18n.isInitialized) {
    i18n.on('languageChanged', (lng) => {
      syncDocument(lng);
      if (isSupportedLocale(lng)) writePreference('locale', lng);
    });
    await i18n.use(initReactI18next).init({
      resources,
      lng: locale,
      fallbackLng: FALLBACK_LOCALE,
      ns: [...NAMESPACES],
      defaultNS: 'common',
      interpolation: { escapeValue: false },
      returnNull: false,
      saveMissing: import.meta.env.DEV,
      missingKeyHandler: (_lngs, ns, key) => {
        console.warn(`Missing translation key ${ns}:${key}`);
      },
    });
  } else if (i18n.language !== locale) {
    await i18n.changeLanguage(locale);
  }
  syncDocument(i18n.language);
  return i18n;
}

export default i18n;

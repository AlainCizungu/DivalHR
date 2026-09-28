import enCommon from '../locales/en/common.json';
import frCommon from '../locales/fr/common.json';

/** Launch locales. Both are first-class; neither is optional. */
export const SUPPORTED_LOCALES = ['fr', 'en'] as const;
export type SupportedLocale = (typeof SUPPORTED_LOCALES)[number];

/** Internal fallback used only after user and organization preferences (docs/I18N.md). */
export const FALLBACK_LOCALE: SupportedLocale = 'en';

export const NAMESPACES = ['common'] as const;

export const resources = {
  en: { common: enCommon },
  fr: { common: frCommon },
} as const;

export function isSupportedLocale(value: unknown): value is SupportedLocale {
  return typeof value === 'string' && (SUPPORTED_LOCALES as readonly string[]).includes(value);
}

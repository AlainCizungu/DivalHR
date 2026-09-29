/**
 * The only module allowed to use localStorage. It stores non-sensitive UI preferences (language
 * and theme). Tokens, user data and API responses must never be stored here.
 */
const KEYS = {
  locale: 'divalhr.locale',
  theme: 'divalhr.theme',
} as const;

export type PreferenceKey = keyof typeof KEYS;

export function readPreference(key: PreferenceKey): string | null {
  try {
    return window.localStorage.getItem(KEYS[key]);
  } catch {
    return null;
  }
}

export function writePreference(key: PreferenceKey, value: string): void {
  try {
    window.localStorage.setItem(KEYS[key], value);
  } catch {
    // Storage may be unavailable (private mode); the preference simply is not remembered.
  }
}

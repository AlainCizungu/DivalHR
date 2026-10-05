import { SUPPORTED_LOCALES } from '@divalhr/localization';
import { THEME_MODES, type ThemeMode } from '@divalhr/design-system';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import type { Environment } from '../config/runtime';
import { useTheme } from '../theme/ThemeProvider';
import { StatusBadge } from '../ui/primitives';

const SHORT_LOCALE = { en: 'EN', fr: 'FR' } as const;

/**
 * English/French switch. Each button's accessible name is the full language name in that
 * language; narrow screens show the two-letter code instead (lang attributes kept).
 */
export function LanguageSwitcher() {
  const { t, i18n } = useTranslation();
  return (
    <div className="segmented" role="group" aria-label={t('locale.label')}>
      {SUPPORTED_LOCALES.map((locale) => (
        <button
          key={locale}
          type="button"
          lang={locale}
          className="segmented__option"
          aria-pressed={i18n.resolvedLanguage === locale}
          onClick={() => {
            void i18n.changeLanguage(locale);
          }}
        >
          <span className="segmented__long">{t(`locale.${locale}`)}</span>
          <span className="segmented__short" aria-hidden="true">
            {SHORT_LOCALE[locale]}
          </span>
        </button>
      ))}
    </div>
  );
}

/** Theme as a native select, for the public top bar where space is tight. */
export function ThemeSelect() {
  const { t } = useTranslation();
  const { mode, setMode } = useTheme();
  const id = useId();
  return (
    <div className="theme-select">
      <label htmlFor={id} className="theme-select__label">
        {t('theme.label')}
      </label>
      <select
        id={id}
        value={mode}
        onChange={(event) => {
          setMode(event.target.value as ThemeMode);
        }}
      >
        {THEME_MODES.map((option) => (
          <option key={option} value={option}>
            {t(`theme.${option}`)}
          </option>
        ))}
      </select>
    </div>
  );
}

/** Theme as three toggle buttons, inside the user menu. */
export function ThemeChoice() {
  const { t } = useTranslation();
  const { mode, setMode } = useTheme();
  const labelId = useId();
  return (
    <div className="menu-field">
      <span className="menu-field__label" id={labelId}>
        {t('theme.label')}
      </span>
      <div className="segmented" role="group" aria-labelledby={labelId}>
        {THEME_MODES.map((option) => (
          <button
            key={option}
            type="button"
            className="segmented__option"
            aria-pressed={mode === option}
            onClick={() => {
              setMode(option);
            }}
          >
            {t(`theme.${option}`)}
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * UI-001 (UI1-2): a non-dismissible, textual badge for every non-production environment. The
 * label comes only from the allow-listed value through fixed keys; production shows nothing.
 */
export function EnvironmentBadge({ environment }: { environment: Environment }) {
  const { t } = useTranslation();
  if (environment === 'production') return null;
  return (
    <span className="environment-badge">
      <StatusBadge tone="warning" icon="environment" testId="environment-badge">
        <span className="environment-badge__long">{t(`shell.environment.${environment}`)}</span>
        <span className="environment-badge__short" aria-hidden="true">
          {t(`shell.environmentShort.${environment}`)}
        </span>
      </StatusBadge>
    </span>
  );
}

export function BrandMark() {
  return (
    <span className="brand-mark" aria-hidden="true">
      D
    </span>
  );
}

import { SUPPORTED_LOCALES } from '@divalhr/localization';
import { THEME_MODES, type ThemeMode } from '@divalhr/design-system';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router';
import { useHasRole } from '../app/SessionProvider';
import { useAuth } from '../auth/AuthProvider';
import { useTheme } from '../theme/ThemeProvider';

function LocaleSwitcher() {
  const { t, i18n } = useTranslation();
  const labelId = useId();
  return (
    <div className="switcher" role="group" aria-labelledby={labelId}>
      <span id={labelId} className="switcher__label">
        {t('locale.label')}
      </span>
      {SUPPORTED_LOCALES.map((locale) => (
        <button
          key={locale}
          type="button"
          lang={locale}
          className="switcher__option"
          aria-pressed={i18n.resolvedLanguage === locale}
          onClick={() => {
            void i18n.changeLanguage(locale);
          }}
        >
          {t(`locale.${locale}`)}
        </button>
      ))}
    </div>
  );
}

function ThemeSwitcher() {
  const { t } = useTranslation();
  const { mode, setMode } = useTheme();
  const id = useId();
  return (
    <div className="switcher">
      <label htmlFor={id} className="switcher__label">
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

function AuthButton() {
  const { t } = useTranslation();
  const { status, signIn, signOut } = useAuth();
  return status === 'authenticated' ? (
    <button type="button" className="button button--secondary" onClick={() => void signOut()}>
      {t('auth.signOut')}
    </button>
  ) : (
    <button type="button" className="button" onClick={() => void signIn(window.location.pathname)}>
      {t('auth.signIn')}
    </button>
  );
}

export function AppShell({ environment, children }: { environment: string; children: ReactNode }) {
  const { t } = useTranslation();
  const isPlatformAdmin = useHasRole('platform-admin');
  const isTenantAdmin = useHasRole('tenant-admin');
  return (
    <>
      <a className="skip-link" href="#main-content">
        {t('nav.skipToContent')}
      </a>
      {environment === 'development' && (
        <p className="notice" role="note" data-testid="dev-notice">
          {t('auth.devOnlyNotice')}
        </p>
      )}
      <header className="header">
        <div className="brand">
          <span className="brand__name">{t('app.name')}</span>
          <span className="brand__tagline">{t('app.tagline')}</span>
        </div>
        <nav aria-label={t('nav.primary')} className="nav">
          <NavLink to="/" end>
            {t('nav.home')}
          </NavLink>
          <NavLink to="/status">{t('nav.status')}</NavLink>
          {isPlatformAdmin && (
            <NavLink to="/admin/organizations/new">{t('nav.platformAdmin')}</NavLink>
          )}
          {isPlatformAdmin && (
            <NavLink to="/admin/organizations/first-admin">{t('nav.firstAdmin')}</NavLink>
          )}
          {isTenantAdmin && <NavLink to="/admin/hierarchy">{t('nav.hierarchy')}</NavLink>}
          {isTenantAdmin && <NavLink to="/admin/users">{t('nav.users')}</NavLink>}
        </nav>
        <div className="toolbar">
          <LocaleSwitcher />
          <ThemeSwitcher />
          <AuthButton />
        </div>
      </header>
      <main id="main-content" className="main" tabIndex={-1}>
        {children}
      </main>
    </>
  );
}

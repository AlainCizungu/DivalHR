import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useAuth } from '../../auth/AuthProvider';
import type { Environment } from '../../config/runtime';
import { BrandMark, EnvironmentBadge, LanguageSwitcher, Wordmark } from '../../shell/controls';
import { Icon } from '../../ui/Icon';
import { demoMailto, SECTIONS } from './catalogue';
import './landing.css';

/**
 * UI-002 (Issue #63): the frame of the public landing page, shown only to anonymous visitors at
 * `/` (D1). Signed-in users never see it: they keep the UI-001 application shell. "Sign in" starts
 * the existing Authorization Code + PKCE flow and returns to `/`.
 */
function SignInButton({ className }: { className: string }) {
  const { t } = useTranslation();
  const { signIn } = useAuth();
  return (
    <button
      type="button"
      className={className}
      onClick={() => {
        void signIn('/');
      }}
    >
      <Icon name="signIn" />
      {t('landing.signIn')}
    </button>
  );
}

function DemoLink({ className }: { className: string }) {
  const { t } = useTranslation();
  return (
    <a className={className} href={demoMailto(t('landing.demoSubject'))}>
      {t('landing.requestDemo')}
    </a>
  );
}

export function LandingFrame({
  environment,
  children,
}: {
  environment: Environment;
  children: ReactNode;
}) {
  const { t, i18n } = useTranslation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  useEffect(() => {
    const previous = document.title;
    document.title = t('landing.documentTitle');
    return () => {
      document.title = previous;
    };
  }, [t, i18n.resolvedLanguage]);

  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setMenuOpen(false);
        menuButton.current?.focus();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
    };
  }, [menuOpen]);

  const sectionLinks = (onClick?: () => void) =>
    SECTIONS.map((section) => (
      <li key={section}>
        <a href={`#${section}`} onClick={onClick}>
          {t(`landing.nav.${section}`)}
        </a>
      </li>
    ));
  return (
    <div className="landing" data-testid="landing">
      <a className="skip-link" href="#main-content">
        {t('nav.skipToContent')}
      </a>
      <header className="landing-header">
        <div className="landing-utility">
          <div className="landing-container landing-utility__row">
            <EnvironmentBadge environment={environment} />
            <span className="landing-spacer" />
            <LanguageSwitcher />
          </div>
        </div>
        <div className="landing-container landing-header__row">
          <Link className="landing-brand" to="/">
            <BrandMark />
            <span className="landing-brand__name">
              <Wordmark />
            </span>
          </Link>
          <nav className="landing-nav" aria-label={t('landing.sectionsLabel')}>
            <ul>{sectionLinks()}</ul>
          </nav>
          <span className="landing-spacer" />
          <SignInButton className="button button--secondary landing-header__signin" />
          <DemoLink className="button landing-header__demo" />
          <button
            ref={menuButton}
            type="button"
            className="landing-menu-button"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            onClick={() => {
              setMenuOpen((open) => !open);
            }}
          >
            <Icon name={menuOpen ? 'close' : 'menu'} />
            <span className="visually-hidden">{t('landing.menu')}</span>
          </button>
        </div>
        <div
          id={menuId}
          className="landing-container landing-menu"
          hidden={!menuOpen}
          data-testid="landing-menu"
        >
          <nav aria-label={t('landing.sectionsLabel')}>
            <ul>
              {sectionLinks(() => {
                setMenuOpen(false);
              })}
            </ul>
          </nav>
          <div className="landing-menu__actions">
            <SignInButton className="button" />
            <DemoLink className="button button--secondary" />
          </div>
        </div>
      </header>
      <main id="main-content" className="landing-main" tabIndex={-1}>
        {children}
      </main>
      <footer className="landing-footer">
        <div className="landing-container landing-footer__row">
          <span className="landing-brand">
            <BrandMark />
            <span className="landing-brand__name">
              <Wordmark />
            </span>
          </span>
          <span>{t('landing.footer.tagline')}</span>
          <Link to="/status">{t('nav.status')}</Link>
          <span>{t('landing.footer.copyright')}</span>
        </div>
      </footer>
    </div>
  );
}

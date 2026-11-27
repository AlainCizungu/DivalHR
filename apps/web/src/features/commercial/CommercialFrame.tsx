import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useAuth } from '../../auth/AuthProvider';
import type { Environment } from '../../config/runtime';
import { BrandMark, EnvironmentBadge, LanguageSwitcher, Wordmark } from '../../shell/controls';
import { Icon } from '../../ui/Icon';
import './commercial.css';
import { applyPageMeta } from './metadata';
import { langOf } from './truth';

/**
 * LAUNCH-004 (Issue #228): the frame of the public commercial website, shown to anonymous visitors
 * at `/` and on the pilot legal pages. Signed-in users keep the application shell. "Sign in" starts
 * the existing Authorization Code + PKCE flow and returns to `/`.
 */
export const CONTACT_EMAIL = 'contact@dival.ai';

export function contactHref(subject: string): string {
  return `mailto:${CONTACT_EMAIL}?subject=${encodeURIComponent(subject)}`;
}

export const NAV_SECTIONS = [
  'product',
  'solutions',
  'payroll',
  'services',
  'partners',
  'security',
  'pilot',
] as const;

export function SignInButton({ className }: { className: string }) {
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
      {t('commercial.nav.signIn')}
    </button>
  );
}

export function DemoLink({ className }: { className: string }) {
  const { t } = useTranslation();
  return (
    <a className={className} href={contactHref(t('commercial.cta.demoSubject'))}>
      {t('commercial.cta.demo')}
    </a>
  );
}

export function PilotLink({ className }: { className: string }) {
  const { t } = useTranslation();
  return (
    <a className={className} href={contactHref(t('commercial.cta.pilotSubject'))}>
      {t('commercial.cta.pilot')}
    </a>
  );
}

export function CommercialFrame({
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
  const lang = langOf(i18n.resolvedLanguage);

  useEffect(() => {
    return applyPageMeta({
      title: t('commercial.meta.title'),
      description: t('commercial.meta.description'),
      ogTitle: t('commercial.meta.ogTitle'),
      ogDescription: t('commercial.meta.ogDescription'),
      ogImageAlt: t('commercial.meta.ogImageAlt'),
      locale: lang,
    });
  }, [t, lang]);

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
    NAV_SECTIONS.map((section) => (
      <li key={section}>
        <a href={`/#${section}`} onClick={onClick}>
          {t(`commercial.nav.${section}`)}
        </a>
      </li>
    ));

  return (
    <div className="commercial" data-testid="landing">
      <a className="skip-link" href="#main-content">
        {t('nav.skipToContent')}
      </a>
      <header className="commercial-header">
        <div className="commercial-utility">
          <div className="commercial-container commercial-utility__row">
            <EnvironmentBadge environment={environment} />
            <span className="commercial-spacer" />
            <LanguageSwitcher />
          </div>
        </div>
        <div className="commercial-container commercial-header__row">
          <Link className="commercial-brand" to="/">
            <BrandMark />
            <span className="commercial-brand__name">
              <Wordmark />
            </span>
          </Link>
          <nav className="commercial-nav" aria-label={t('commercial.nav.label')}>
            <ul>{sectionLinks()}</ul>
          </nav>
          <span className="commercial-spacer" />
          <SignInButton className="button button--secondary commercial-header__signin" />
          <DemoLink className="button commercial-header__demo" />
          <button
            ref={menuButton}
            type="button"
            className="commercial-menu-button"
            aria-expanded={menuOpen}
            aria-controls={menuId}
            onClick={() => {
              setMenuOpen((open) => !open);
            }}
          >
            <Icon name={menuOpen ? 'close' : 'menu'} />
            <span className="visually-hidden">{t('commercial.nav.menu')}</span>
          </button>
        </div>
        <div
          id={menuId}
          className="commercial-container commercial-menu"
          hidden={!menuOpen}
          data-testid="commercial-menu"
        >
          <nav aria-label={t('commercial.nav.label')}>
            <ul>
              {sectionLinks(() => {
                setMenuOpen(false);
              })}
            </ul>
          </nav>
          <div className="commercial-menu__actions">
            <DemoLink className="button" />
            <PilotLink className="button button--secondary" />
            <SignInButton className="button button--secondary" />
          </div>
        </div>
      </header>
      <main id="main-content" className="commercial-main" tabIndex={-1}>
        {children}
      </main>
      <footer className="commercial-footer" aria-label={t('commercial.footer.label')}>
        <div className="commercial-container commercial-footer__grid">
          <div className="commercial-footer__identity">
            <span className="commercial-brand">
              <BrandMark />
              <span className="commercial-brand__name">
                <Wordmark />
              </span>
            </span>
            <p>{t('commercial.footer.tagline')}</p>
            <p>{t('commercial.footer.copyright')}</p>
          </div>
          <ul className="commercial-footer__links">
            <li>
              <a href="/#product">{t('commercial.footer.product')}</a>
            </li>
            <li>
              <a href="/#pilot">{t('commercial.footer.pilot')}</a>
            </li>
            <li>
              <Link to="/partners/apply">{t('commercial.footer.partnerApplication')}</Link>
            </li>
            <li>
              <a href="/#security">{t('commercial.footer.security')}</a>
            </li>
          </ul>
          <ul className="commercial-footer__links">
            <li>
              <Link to="/legal/privacy">{t('commercial.footer.privacy')}</Link>
            </li>
            <li>
              <Link to="/legal/terms">{t('commercial.footer.terms')}</Link>
            </li>
            <li>
              <a href={`mailto:${CONTACT_EMAIL}`}>{t('commercial.footer.contact')}</a>
            </li>
            <li>
              <SignInButton className="commercial-linkbutton" />
            </li>
          </ul>
          <p className="commercial-footer__note">{t('commercial.footer.legalNote')}</p>
        </div>
      </footer>
    </div>
  );
}

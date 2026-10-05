import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useSession } from '../app/SessionProvider';
import { useAuth } from '../auth/AuthProvider';
import { Icon } from '../ui/Icon';
import { ThemeChoice } from './controls';

/**
 * UI-001 account menu: a disclosure (not an ARIA menu) holding the session details, the theme,
 * the system-status link and sign-out. Esc, a click outside and focus moving outside close it; Esc returns
 * focus to the button. The session details keep the former session card's test ids.
 */
export function UserMenu() {
  const { t } = useTranslation();
  const { signOut } = useAuth();
  const session = useSession();
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  const roles = session.kind === 'ready' ? session.session.roles : [];
  const roleLabel =
    roles.length === 0
      ? t('session.noRoles')
      : roles.map((role) => t(`session.role.${role}`)).join(', ');

  useEffect(() => {
    if (!open) return;
    const outside = (event: Event) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        buttonRef.current?.focus();
      }
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('focusin', outside);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('focusin', outside);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="user-menu" ref={rootRef}>
      <button
        ref={buttonRef}
        type="button"
        className="user-menu__button"
        aria-expanded={open}
        aria-controls={panelId}
        disabled={session.kind === 'loading'}
        onClick={() => {
          setOpen((value) => !value);
        }}
      >
        <span className="user-menu__avatar">
          <Icon name="user" />
        </span>
        <span className="user-menu__role">{roleLabel}</span>
        <Icon name="chevronDown" />
        <span className="visually-hidden">{t('shell.accountMenu')}</span>
      </button>
      <div id={panelId} className="user-menu__panel" hidden={!open}>
        <dl className="user-menu__details">
          <dt>{t('shell.signedInAs')}</dt>
          <dd data-testid="session-roles">{roleLabel}</dd>
          <dt>{t('shell.organizationId')}</dt>
          <dd data-testid="session-tenant" className="user-menu__id">
            {session.kind === 'ready'
              ? (session.session.tenantId ?? t('session.noTenant'))
              : t('session.noTenant')}
          </dd>
        </dl>
        <ThemeChoice />
        <Link
          className="user-menu__item"
          to="/status"
          onClick={() => {
            setOpen(false);
          }}
        >
          <Icon name="pulse" />
          {t('nav.status')}
        </Link>
        <button type="button" className="user-menu__item" onClick={() => void signOut()}>
          <Icon name="signOut" />
          {t('auth.signOut')}
        </button>
      </div>
    </div>
  );
}

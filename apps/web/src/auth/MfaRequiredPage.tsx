import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from './AuthProvider';

/**
 * Shown when the Core still refuses a privileged request after a step-up sign-in (MVP-011).
 * The page never retries on its own: the next sign-in is always the user's choice.
 */
export function MfaRequiredPage({ returnTo }: { returnTo: string }) {
  const { t } = useTranslation();
  const { stepUp, signOut } = useAuth();
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    // Move focus to the explanation so screen-reader and keyboard users land on it.
    heading.current?.focus();
  }, []);

  return (
    <section aria-labelledby="mfa-required-title" className="card">
      <h1 id="mfa-required-title" ref={heading} tabIndex={-1}>
        {t('mfa.title')}
      </h1>
      <p>{t('mfa.explanation')}</p>
      <div className="button-row">
        <button type="button" className="button" onClick={() => void stepUp(returnTo)}>
          {t('mfa.retry')}
        </button>
        <button type="button" className="button button--secondary" onClick={() => void signOut()}>
          {t('auth.signOut')}
        </button>
      </div>
      <h2>{t('mfa.help.title')}</h2>
      <ul>
        <li>{t('mfa.help.noAuthenticator')}</li>
        <li>{t('mfa.help.lostDevice')}</li>
        <li>{t('mfa.help.clock')}</li>
      </ul>
    </section>
  );
}

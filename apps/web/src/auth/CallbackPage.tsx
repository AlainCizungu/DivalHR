import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { useAuth } from './AuthProvider';

export function CallbackPage() {
  const { t } = useTranslation();
  const { completeSignIn } = useAuth();
  const navigate = useNavigate();
  const [failed, setFailed] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    completeSignIn()
      .then((returnTo) => {
        void navigate(returnTo, { replace: true });
      })
      .catch(() => {
        setFailed(true);
      });
  }, [completeSignIn, navigate]);

  return (
    <section aria-live="polite">
      <p role={failed ? 'alert' : 'status'}>{failed ? t('auth.error') : t('auth.signingIn')}</p>
    </section>
  );
}

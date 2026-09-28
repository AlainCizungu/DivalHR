import { useTranslation } from 'react-i18next';
import { useAuth } from '../../auth/AuthProvider';
import { SessionCard } from '../session/SessionCard';

export function HomePage() {
  const { t } = useTranslation();
  const { status, signIn } = useAuth();
  return (
    <section aria-labelledby="home-title">
      <h1 id="home-title">{t('home.title')}</h1>
      <p className="muted">{t('home.body')}</p>
      {status === 'authenticated' ? (
        <SessionCard />
      ) : (
        <div className="card">
          <p>{t('auth.required')}</p>
          <button
            type="button"
            className="button"
            onClick={() => {
              void signIn('/');
            }}
          >
            {t('auth.signIn')}
          </button>
        </div>
      )}
    </section>
  );
}

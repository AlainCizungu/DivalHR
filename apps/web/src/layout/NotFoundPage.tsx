import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="not-found-title">
      <h1 id="not-found-title">{t('notFound.title')}</h1>
      <Link to="/">{t('notFound.back')}</Link>
    </section>
  );
}

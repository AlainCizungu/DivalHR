import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { EmptyState } from '../ui/primitives';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <EmptyState
      icon="search"
      title={t('notFound.title')}
      titleLevel={1}
      action={<Link to="/">{t('notFound.back')}</Link>}
    />
  );
}

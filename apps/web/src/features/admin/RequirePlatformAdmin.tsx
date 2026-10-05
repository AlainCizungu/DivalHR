import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useSession } from '../../app/SessionProvider';
import { ErrorPanel } from '../../ui/primitives';

/**
 * Hides platform-administration pages from users without the role. This is a usability measure
 * only: the Core API enforces authorization on every request.
 */
export function RequirePlatformAdmin({
  children,
  deniedKey = 'createOrganization.unauthorized',
  signInKey = 'createOrganization.signInRequired',
}: {
  children: ReactNode;
  deniedKey?: string;
  signInKey?: string;
}) {
  const { t } = useTranslation();
  const session = useSession();
  if (session.kind === 'anonymous') {
    return <ErrorPanel message={t(signInKey)} />;
  }
  if (session.kind === 'loading') {
    return <p role="status">{t('session.loading')}</p>;
  }
  if (session.kind === 'ready' && session.session.roles.includes('platform-admin')) {
    return <>{children}</>;
  }
  return <ErrorPanel message={t(deniedKey)} testId="not-authorized" />;
}

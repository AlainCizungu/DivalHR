import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useSession } from '../../app/SessionProvider';
import { ErrorPanel } from '../../ui/primitives';

/**
 * Hides pages from users without the role. A usability measure only: the Core API enforces
 * authorization on every request.
 */
export function RequireRole({
  requiredRole,
  deniedKey,
  signInKey,
  children,
}: {
  requiredRole: string;
  deniedKey: string;
  signInKey: string;
  children: ReactNode;
}) {
  const { t } = useTranslation();
  const session = useSession();
  if (session.kind === 'anonymous') {
    return <ErrorPanel message={t(signInKey)} />;
  }
  if (session.kind === 'loading') {
    return <p role="status">{t('session.loading')}</p>;
  }
  if (session.kind === 'ready' && session.session.roles.includes(requiredRole as never)) {
    return <>{children}</>;
  }
  return <ErrorPanel message={t(deniedKey)} testId="not-authorized" />;
}

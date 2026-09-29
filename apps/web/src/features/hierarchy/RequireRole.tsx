import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useSession } from '../../app/SessionProvider';

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
    return <p role="alert">{t(signInKey)}</p>;
  }
  if (session.kind === 'loading') {
    return <p role="status">{t('session.loading')}</p>;
  }
  if (session.kind === 'ready' && session.session.roles.includes(requiredRole as never)) {
    return <>{children}</>;
  }
  return (
    <p role="alert" data-testid="not-authorized">
      {t(deniedKey)}
    </p>
  );
}

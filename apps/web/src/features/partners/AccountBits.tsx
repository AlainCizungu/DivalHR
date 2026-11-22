import type { PartnerAccountStatus, PartnerProductStatus } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { PARTNER_TONES, PRODUCT_TONES } from './accounts';

/** The partner status as text with a tone: never colour alone. */
export function AccountStatus({ status }: { status: PartnerAccountStatus }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={PARTNER_TONES[status]}
      icon={status === 'ACTIVE' ? 'check' : undefined}
      testId="account-status"
    >
      {t(`accounts.status.${status}`)}
    </StatusBadge>
  );
}

/** The product version status as text with a tone. */
export function ProductStatus({ status }: { status: PartnerProductStatus }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={PRODUCT_TONES[status]}
      icon={status === 'ACTIVE' ? 'check' : undefined}
      testId="product-status"
    >
      {t(`accounts.productStatus.${status}`)}
    </StatusBadge>
  );
}

/** What an active partner and a published product do not do: no connection, no employee access. */
export function AccountBoundary({ testId = 'account-boundary' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="card" role="note" data-testid={testId}>
      {t('accounts.boundary')}
    </p>
  );
}

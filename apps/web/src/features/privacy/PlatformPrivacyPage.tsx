import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '../../ui/primitives';
import { ApplicationSection } from './ApplicationSection';
import { PolicySection } from './PolicySection';
import { FrameworkNotice } from './PrivacyBits';
import { PrivacyRequestsSection } from './PrivacyRequestsSection';
import { PurgeSection } from './PurgeSection';

/**
 * LAUNCH-001: Platform → Privacy governance (exact MFA). Retention policy versions, their
 * application to partner referrals, employee data-rights requests, legal holds and the two-step
 * purge. Tenant administrators cannot reach this area and cannot see private referral choices.
 */
export function PlatformPrivacyPage() {
  const { t } = useTranslation();
  const ids = useId();
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="platform-privacy-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('privacy.platform.title')}
        description={t('privacy.platform.description')}
      />
      <FrameworkNotice />
      <PolicySection />
      <ApplicationSection />
      <PrivacyRequestsSection />
      <PurgeSection />
    </section>
  );
}

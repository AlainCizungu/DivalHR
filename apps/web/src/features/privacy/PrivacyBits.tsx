import type { PrivacyPolicyState, PrivacyRequestState } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { PartnerAlert } from '../partners/PartnerBits';
import type { PartnerFailure } from '../partners/partners';
import { POLICY_TONES, REQUEST_TONES } from './privacy';

/** The status as text with a tone: never colour alone. */
export function RequestState({ state }: { state: PrivacyRequestState }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={REQUEST_TONES[state]}
      icon={state === 'COMPLETED' || state === 'APPROVED' ? 'check' : undefined}
      testId="privacy-request-state"
    >
      {t(`privacy.state.${state}`)}
    </StatusBadge>
  );
}

export function PolicyState({ state }: { state: PrivacyPolicyState }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={POLICY_TONES[state]}
      icon={state === 'ACTIVE' ? 'check' : undefined}
      testId="privacy-policy-state"
    >
      {t(`privacy.policyState.${state}`)}
    </StatusBadge>
  );
}

/** A refused or failed command: a finished sentence, never the raw code. */
export function PrivacyAlert({
  failure,
  testId,
}: {
  failure: PartnerFailure | null;
  testId: string;
}) {
  return failure ? <PartnerAlert failure={failure} testId={testId} /> : null;
}

/** What this area is and is not: shown on every platform privacy screen. */
export function FrameworkNotice() {
  const { t } = useTranslation();
  return (
    <p className="card" role="note" data-testid="privacy-framework-notice">
      {t('privacy.platform.framework')}
    </p>
  );
}

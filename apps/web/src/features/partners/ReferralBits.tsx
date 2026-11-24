import type { PartnerReferralEvent, PartnerReferralState } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant, STATE_TONES } from './referrals';

/** The state as text with a tone: never colour alone. */
export function ReferralState({ state }: { state: PartnerReferralState }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={STATE_TONES[state]}
      icon={state === 'DELIVERED' ? 'check' : undefined}
      testId="referral-state"
    >
      {t(`referrals.state.${state}`)}
    </StatusBadge>
  );
}

/** What a referral is and is not: shown wherever one is previewed, sent or read. */
export function ReferralNotice({ employer = true }: { employer?: boolean }) {
  const { t } = useTranslation();
  return (
    <div className="card" role="note" data-testid="referral-notice">
      <p>
        <b>{t('referrals.notice.referralOnly')}</b>
      </p>
      <p>{t('referrals.notice.noDecision')}</p>
      <p>{t('referrals.notice.noMoney')}</p>
      {employer && <p>{t('referrals.notice.employer')}</p>}
    </div>
  );
}

/** The append-only history, in order, as text. */
export function ReferralHistory({ events }: { events: PartnerReferralEvent[] }) {
  const { t, i18n } = useTranslation();
  return (
    <ol className="detail-list" data-testid="referral-history">
      {events.map((event) => (
        <li key={event.sequence} data-testid="referral-event">
          <b>{t(`referrals.state.${event.toState}`)}</b>
          {' · '}
          {formatInstant(event.occurredAt, i18n.language)}
          {' · '}
          {t(`referrals.actor.${event.actorRole}`)}
          {event.outcome ? ` · ${t(`referrals.outcome.${event.outcome}`)}` : ''}
        </li>
      ))}
    </ol>
  );
}

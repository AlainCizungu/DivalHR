import type { PartnerReferralEvent, PlatformPartnerReferral } from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';
import { ReferralHistory, ReferralState } from './ReferralBits';
import { formatInstant } from './referrals';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; referral: PlatformPartnerReferral; events: PartnerReferralEvent[] };

/** MVP-120F: one referral, operational view and history. Read only; contact masked. */
export function PlatformReferralDetailPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const { referralId = '' } = useParams();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    Promise.all([
      core.GET('/platform/partner-referrals/{referralId}', {
        params: { path: { referralId } },
        cache: 'no-store',
      }),
      core.GET('/platform/partner-referrals/{referralId}/history', {
        params: { path: { referralId } },
        cache: 'no-store',
      }),
    ]).then(
      ([detail, history]) => {
        if (!active) return;
        if (detail.data && history.data) {
          setLoad({ kind: 'ready', referral: detail.data, events: history.data.events });
        } else {
          setLoad({
            kind: 'failed',
            failure: detail.data
              ? partnerFailureOf(history.response, history.error)
              : partnerFailureOf(detail.response, detail.error),
          });
        }
      },
      () => {
        if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core, referralId]);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="platform-referral-page">
      {load.kind === 'loading' && (
        <p role="status" data-testid="platform-referral-loading">
          {t('referrals.requests.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <>
          <PageHeader titleId={`${ids}-title`} title={t('referrals.detail.notFoundTitle')} />
          <PartnerAlert failure={load.failure} testId="platform-referral-failure" />
          <p>
            <Link to="/admin/partner-referrals">{t('referrals.platform.back')}</Link>
          </p>
        </>
      )}
      {load.kind === 'ready' && (
        <>
          <PageHeader
            titleId={`${ids}-title`}
            title={load.referral.referralId}
            description={`${load.referral.partnerDisplayName} · ${t(`accounts.productType.${load.referral.productType}`)}`}
          />
          <p>
            <Link to="/admin/partner-referrals">{t('referrals.platform.back')}</Link>
          </p>
          <p className="card" role="note">
            {t('referrals.platform.readOnly')}
          </p>
          <dl className="detail-list">
            <dt>{t('referrals.detail.state')}</dt>
            <dd>
              <ReferralState state={load.referral.state} />
            </dd>
            {load.referral.lastOutcome && (
              <>
                <dt>{t('referrals.detail.outcome')}</dt>
                <dd>{t(`referrals.outcome.${load.referral.lastOutcome}`)}</dd>
              </>
            )}
            <dt>{t('referrals.platform.attempts')}</dt>
            <dd data-testid="platform-referral-attempts">{load.referral.attemptCount}</dd>
            <dt>{t('referrals.platform.organization')}</dt>
            <dd>{load.referral.organizationId}</dd>
            <dt>{t('referrals.platform.categories')}</dt>
            <dd>{load.referral.categories.map((c) => t(`partners.category.${c}`)).join(' · ')}</dd>
            <dt>{t('referrals.platform.contact')}</dt>
            <dd data-testid="platform-referral-contact">
              {load.referral.contact.emailMasked}
              {load.referral.contact.phoneMasked ? ` · ${load.referral.contact.phoneMasked}` : ''}
            </dd>
            <dt>{t('referrals.detail.sentAt')}</dt>
            <dd>{formatInstant(load.referral.createdAt, i18n.language)}</dd>
            <dt>{t('referrals.detail.updatedAt')}</dt>
            <dd>{formatInstant(load.referral.updatedAt, i18n.language)}</dd>
          </dl>
          <h2>{t('referrals.detail.history')}</h2>
          <ReferralHistory events={load.events} />
        </>
      )}
    </section>
  );
}

import type { PartnerReferralView } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PageHeader } from '../../ui/primitives';
import { localized } from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';
import { ReferralHistory, ReferralNotice, ReferralState } from './ReferralBits';
import { formatInstant } from './referrals';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; referral: PartnerReferralView };

/**
 * MVP-120F: one of the employee's own partner requests with its append-only history and, only
 * while the server says it is allowed, a confirmed withdrawal. The page shows the names of the
 * fields that were shared, never their values.
 */
export function MyReferralDetailPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const { referralId = '' } = useParams();
  const idempotency = useIdempotencyKey();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [confirm, setConfirm] = useState(false);
  const [missing, setMissing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [withdrawn, setWithdrawn] = useState(false);
  const submitting = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);

  const reload = () =>
    core
      .GET('/me/partner-referrals/{referralId}', {
        params: { path: { referralId } },
        cache: 'no-store',
      })
      .then(({ data, error, response }) => {
        setLoad(
          data
            ? { kind: 'ready', referral: data }
            : { kind: 'failed', failure: partnerFailureOf(response, error) },
        );
      });

  useEffect(() => {
    let active = true;
    core
      .GET('/me/partner-referrals/{referralId}', {
        params: { path: { referralId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', referral: data }
              : { kind: 'failed', failure: partnerFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, referralId]);

  const withdraw = async (referral: PartnerReferralView) => {
    if (submitting.current) return;
    if (!confirm) {
      setMissing(true);
      return;
    }
    const body = { expectedVersion: referral.version, confirmed: true as const };
    submitting.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/partner-referrals/{referralId}/withdraw',
        {
          params: {
            path: { referralId },
            header: { 'Idempotency-Key': idempotency.keyFor({ referralId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
        setLoad({ kind: 'ready', referral: data });
        setWithdrawn(true);
      } else {
        const f = partnerFailureOf(response, error);
        setFailure(f);
        if (
          f.code === 'PARTNER_REFERRAL_VERSION_STALE' ||
          f.code === 'PARTNER_REFERRAL_NOT_WITHDRAWABLE'
        ) {
          idempotency.reset();
          await reload();
        }
        requestAnimationFrame(() => alertRef.current?.focus());
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-referral-page">
      {load.kind === 'loading' && (
        <p role="status" data-testid="my-referral-loading">
          {t('referrals.requests.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <>
          <PageHeader titleId={`${ids}-title`} title={t('referrals.detail.notFoundTitle')} />
          <PartnerAlert failure={load.failure} testId="my-referral-failure" />
          <p>
            <Link to="/me/partner-requests">{t('referrals.detail.back')}</Link>
          </p>
        </>
      )}
      {load.kind === 'ready' && (
        <>
          <PageHeader
            titleId={`${ids}-title`}
            title={localized(load.referral.productName, i18n.language)}
            description={`${load.referral.partnerDisplayName} · ${t(`accounts.productType.${load.referral.productType}`)}`}
          />
          <p>
            <Link to="/me/partner-requests">{t('referrals.detail.back')}</Link>
          </p>
          {withdrawn && (
            <p className="card" role="status" data-testid="referral-withdrawn">
              {t('referrals.withdraw.done')}
            </p>
          )}
          <dl className="detail-list">
            <dt>{t('referrals.detail.reference')}</dt>
            <dd data-testid="referral-reference">{load.referral.referralId}</dd>
            <dt>{t('referrals.detail.state')}</dt>
            <dd>
              <ReferralState state={load.referral.state} />
              <p className="muted" data-testid="referral-state-help">
                {t(`referrals.stateHelp.${load.referral.state}`)}
              </p>
            </dd>
            {load.referral.lastOutcome && (
              <>
                <dt>{t('referrals.detail.outcome')}</dt>
                <dd data-testid="referral-outcome">
                  {t(`referrals.outcome.${load.referral.lastOutcome}`)}
                </dd>
              </>
            )}
            <dt>{t('referrals.detail.sentAt')}</dt>
            <dd>{formatInstant(load.referral.createdAt, i18n.language)}</dd>
            <dt>{t('referrals.detail.updatedAt')}</dt>
            <dd>{formatInstant(load.referral.updatedAt, i18n.language)}</dd>
            <dt>{t('referrals.detail.shared')}</dt>
            <dd>
              <ul data-testid="referral-shared-fields">
                {load.referral.disclosedFields.map((field) => (
                  <li key={field}>{t(`referrals.field.${field}`)}</li>
                ))}
              </ul>
              <p className="muted">
                {t('referrals.consent.version', { version: load.referral.consentVersion })}
              </p>
            </dd>
          </dl>
          <ReferralNotice />
          <h2>{t('referrals.detail.history')}</h2>
          <ReferralHistory events={load.referral.events} />

          {failure && (
            <PartnerAlert
              failure={failure}
              testId="my-referral-action-failure"
              alertRef={alertRef}
            />
          )}
          {load.referral.withdrawable ? (
            <form
              noValidate
              aria-labelledby={`${ids}-withdraw`}
              data-testid="referral-withdraw-form"
              onSubmit={(event) => {
                event.preventDefault();
                void withdraw(load.referral);
              }}
            >
              <h2 id={`${ids}-withdraw`}>{t('referrals.withdraw.title')}</h2>
              <p>{t('referrals.withdraw.help')}</p>
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={confirm}
                    disabled={busy}
                    aria-invalid={missing ? 'true' : undefined}
                    onChange={(event) => {
                      setConfirm(event.target.checked);
                      if (event.target.checked) setMissing(false);
                    }}
                    data-testid="referral-withdraw-confirm"
                  />{' '}
                  {t('referrals.withdraw.confirm')}
                </label>
                {missing && (
                  <p className="field-error" role="alert">
                    {t('referrals.withdraw.required')}
                  </p>
                )}
              </div>
              <div className="button-row">
                <button
                  type="submit"
                  className="button button--secondary"
                  disabled={busy}
                  data-testid="referral-withdraw"
                >
                  {busy ? t('referrals.withdraw.working') : t('referrals.withdraw.button')}
                </button>
              </div>
            </form>
          ) : (
            load.referral.state !== 'WITHDRAWN' && (
              <p className="muted" data-testid="referral-not-withdrawable">
                {t('referrals.withdraw.unavailable')}
              </p>
            )
          )}
        </>
      )}
    </section>
  );
}

import type { PartnerReferralState, PartnerReferralSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { localized } from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';
import { ReferralNotice, ReferralState } from './ReferralBits';
import { STATES, formatInstant, requestPath } from './referrals';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; items: PartnerReferralSummary[]; next: string | null };

/** MVP-120F: the employee's own partner requests. Only the employee can see them. */
export function MyReferralsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [state, setState] = useState<PartnerReferralState | ''>('');
  const [applied, setApplied] = useState<PartnerReferralState | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);

  const query = useCallback(
    (cursor?: string) => ({ limit: 25, cursor, state: applied || undefined }),
    [applied],
  );

  useEffect(() => {
    let active = true;
    core.GET('/me/partner-referrals', { params: { query: query() }, cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setLoad(
          data
            ? { kind: 'ready', items: data.items, next: data.nextCursor }
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
  }, [core, query]);

  const more = async () => {
    if (load.kind !== 'ready' || load.next === null || loadingMore.current) return;
    loadingMore.current = true;
    setMoreBusy(true);
    try {
      const { data, error, response } = await core.GET('/me/partner-referrals', {
        params: { query: query(load.next) },
        cache: 'no-store',
      });
      setLoad(
        data
          ? { kind: 'ready', items: [...load.items, ...data.items], next: data.nextCursor }
          : { kind: 'failed', failure: partnerFailureOf(response, error) },
      );
    } catch {
      setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
    } finally {
      loadingMore.current = false;
      setMoreBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-referrals-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('referrals.requests.title')}
        description={t('referrals.requests.description')}
      />
      <ReferralNotice />
      <form
        className="partner-filters"
        aria-label={t('catalogue.filter')}
        onSubmit={(event) => {
          event.preventDefault();
          setLoad({ kind: 'loading' });
          setApplied(state);
        }}
      >
        <div className="field">
          <label htmlFor={`${ids}-state`}>{t('referrals.requests.state')}</label>
          <select
            id={`${ids}-state`}
            value={state}
            onChange={(event) => {
              setState(event.target.value as PartnerReferralState | '');
            }}
          >
            <option value="">{t('referrals.requests.anyState')}</option>
            {STATES.map((s) => (
              <option key={s} value={s}>
                {t(`referrals.state.${s}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="button-row">
          <button type="submit" className="button">
            {t('catalogue.apply')}
          </button>
        </div>
      </form>
      {load.kind === 'loading' && (
        <p role="status" data-testid="my-referrals-loading">
          {t('referrals.requests.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PartnerAlert failure={load.failure} testId="my-referrals-failure" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="my-referrals-empty">
            {t('referrals.requests.empty')}{' '}
            <Link to="/me/partner-products">{t('referrals.requests.browse')}</Link>
          </p>
        ) : (
          <>
            <ul className="partner-catalogue" data-testid="my-referrals-list">
              {load.items.map((item) => (
                <li
                  key={item.referralId}
                  className="card"
                  data-testid="my-referral"
                  data-reference={item.referralId}
                  data-state={item.state}
                >
                  <h2>{localized(item.productName, i18n.language)}</h2>
                  <p className="muted">
                    {item.partnerDisplayName} · {t(`accounts.productType.${item.productType}`)}
                  </p>
                  <p>
                    <ReferralState state={item.state} />{' '}
                    <span className="muted">{formatInstant(item.createdAt, i18n.language)}</span>
                  </p>
                  <p>
                    <span className="muted">{item.referralId}</span>
                  </p>
                  <p>
                    <Link to={requestPath(item.referralId)} data-testid="my-referral-open">
                      {t('referrals.requests.open')}
                      <span className="visually-hidden"> — {item.referralId}</span>
                    </Link>
                  </p>
                </li>
              ))}
            </ul>
            {load.next !== null && (
              <button
                type="button"
                className="button button--secondary"
                disabled={moreBusy}
                onClick={() => void more()}
              >
                {t('catalogue.more')}
              </button>
            )}
          </>
        ))}
    </section>
  );
}

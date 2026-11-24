import type {
  PartnerCategory,
  PartnerReferralState,
  PlatformPartnerReferral,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { PartnerAlert } from './PartnerBits';
import { CATEGORIES, NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';
import { ReferralState } from './ReferralBits';
import { STATES, formatInstant, platformPath } from './referrals';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; items: PlatformPartnerReferral[]; next: string | null };

/**
 * MVP-120F: the platform's read-only operational queue (exact MFA). Operational status only:
 * reference, partner, product type, category, organization identifier, state and timestamps, with
 * the employee's contact masked. There is deliberately no approve, edit, resend, override or
 * delete control. Filters stay in memory.
 */
export function PlatformReferralsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [state, setState] = useState<PartnerReferralState | ''>('');
  const [category, setCategory] = useState<PartnerCategory | ''>('');
  const [applied, setApplied] = useState<{
    state: PartnerReferralState | '';
    category: PartnerCategory | '';
  }>({ state: '', category: '' });
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);

  const query = useCallback(
    (cursor?: string) => ({
      limit: 25,
      cursor,
      state: applied.state || undefined,
      category: applied.category || undefined,
    }),
    [applied],
  );

  useEffect(() => {
    let active = true;
    core.GET('/platform/partner-referrals', { params: { query: query() }, cache: 'no-store' }).then(
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
      const { data, error, response } = await core.GET('/platform/partner-referrals', {
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
    <section aria-labelledby={`${ids}-title`} data-testid="platform-referrals-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('referrals.platform.title')}
        description={t('referrals.platform.description')}
      />
      <p className="card" role="note" data-testid="platform-referrals-readonly">
        {t('referrals.platform.readOnly')}
      </p>
      <form
        className="partner-filters"
        aria-label={t('catalogue.filter')}
        onSubmit={(event) => {
          event.preventDefault();
          setLoad({ kind: 'loading' });
          setApplied({ state, category });
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
        <div className="field">
          <label htmlFor={`${ids}-category`}>{t('referrals.platform.category')}</label>
          <select
            id={`${ids}-category`}
            value={category}
            onChange={(event) => {
              setCategory(event.target.value as PartnerCategory | '');
            }}
          >
            <option value="">{t('referrals.platform.anyCategory')}</option>
            {CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {t(`partners.category.${c}`)}
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
        <p role="status" data-testid="platform-referrals-loading">
          {t('referrals.requests.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PartnerAlert failure={load.failure} testId="platform-referrals-failure" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="platform-referrals-empty">
            {t('referrals.platform.empty')}
          </p>
        ) : (
          <>
            <ul className="partner-catalogue" data-testid="platform-referrals-list">
              {load.items.map((item) => (
                <li
                  key={item.referralId}
                  className="card"
                  data-testid="platform-referral"
                  data-reference={item.referralId}
                >
                  <h2>{item.referralId}</h2>
                  <p className="muted">
                    {item.partnerDisplayName} · {t(`accounts.productType.${item.productType}`)} ·{' '}
                    {item.categories.map((c) => t(`partners.category.${c}`)).join(' · ')}
                  </p>
                  <p>
                    <ReferralState state={item.state} />{' '}
                    <span className="muted">{formatInstant(item.createdAt, i18n.language)}</span>
                  </p>
                  <dl className="detail-list">
                    <dt>{t('referrals.platform.organization')}</dt>
                    <dd>{item.organizationId}</dd>
                    <dt>{t('referrals.platform.contact')}</dt>
                    <dd data-testid="platform-referral-contact">
                      {item.contact.emailMasked}
                      {item.contact.phoneMasked ? ` · ${item.contact.phoneMasked}` : ''}
                    </dd>
                  </dl>
                  <p>
                    <Link to={platformPath(item.referralId)} data-testid="platform-referral-open">
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

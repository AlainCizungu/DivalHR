import type { PartnerProductType, PartnerReferralProduct } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { PRODUCT_TYPES, localized } from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';
import { ReferralNotice } from './ReferralBits';
import { productPath } from './referrals';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      items: PartnerReferralProduct[];
      next: string | null;
      unknownCountry: boolean;
    };

/**
 * MVP-120F: the employee's partner products open for an introduction. The server returns only
 * what is eligible for the employee's organization today; the browser adds no rule of its own and
 * shows no internal identifier, contact or note.
 */
export function PartnerProductsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [type, setType] = useState<PartnerProductType | ''>('');
  const [applied, setApplied] = useState<PartnerProductType | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);

  const query = useCallback(
    (cursor?: string) => ({ limit: 25, cursor, productType: applied || undefined }),
    [applied],
  );

  useEffect(() => {
    let active = true;
    core.GET('/me/partner-products', { params: { query: query() }, cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setLoad(
          data
            ? {
                kind: 'ready',
                items: data.items,
                next: data.nextCursor,
                unknownCountry: data.availability === 'ORGANIZATION_COUNTRY_UNKNOWN',
              }
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
      const { data, error, response } = await core.GET('/me/partner-products', {
        params: { query: query(load.next) },
        cache: 'no-store',
      });
      setLoad(
        data
          ? {
              kind: 'ready',
              items: [...load.items, ...data.items],
              next: data.nextCursor,
              unknownCountry: data.availability === 'ORGANIZATION_COUNTRY_UNKNOWN',
            }
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
    <section aria-labelledby={`${ids}-title`} data-testid="partner-products-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('referrals.products.title')}
        description={t('referrals.products.description')}
      />
      <ReferralNotice />
      <form
        className="partner-filters"
        aria-label={t('catalogue.filter')}
        onSubmit={(event) => {
          event.preventDefault();
          setLoad({ kind: 'loading' });
          setApplied(type);
        }}
      >
        <div className="field">
          <label htmlFor={`${ids}-type`}>{t('catalogue.type')}</label>
          <select
            id={`${ids}-type`}
            value={type}
            onChange={(event) => {
              setType(event.target.value as PartnerProductType | '');
            }}
          >
            <option value="">{t('catalogue.anyType')}</option>
            {PRODUCT_TYPES.map((p) => (
              <option key={p} value={p}>
                {t(`accounts.productType.${p}`)}
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
        <p role="status" data-testid="partner-products-loading">
          {t('referrals.products.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PartnerAlert failure={load.failure} testId="partner-products-failure" />
      )}
      {load.kind === 'ready' && load.unknownCountry && (
        <p className="card" role="status" data-testid="partner-products-country-unknown">
          {t('catalogue.countryUnknown')}
        </p>
      )}
      {load.kind === 'ready' &&
        !load.unknownCountry &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="partner-products-empty">
            {t('referrals.products.empty')}
          </p>
        ) : (
          <>
            <ul className="partner-catalogue" data-testid="partner-products-list">
              {load.items.map((item) => (
                <li
                  key={item.product.publicReference}
                  className="card"
                  data-testid="partner-product"
                  data-reference={item.product.publicReference}
                  data-category={item.categories[0]}
                >
                  <h2>{localized(item.product.name, i18n.language)}</h2>
                  <p className="muted">
                    {item.product.partnerDisplayName} ·{' '}
                    {t(`accounts.productType.${item.product.productType}`)}
                  </p>
                  <p className="muted" data-testid="partner-product-categories">
                    {item.categories.map((c) => t(`partners.category.${c}`)).join(' · ')}
                  </p>
                  <p>{localized(item.product.summary, i18n.language)}</p>
                  <p>
                    <Link
                      className="button"
                      to={productPath(item.product.publicReference)}
                      data-testid="partner-product-open"
                    >
                      {t('referrals.products.open')}
                      <span className="visually-hidden">
                        {' '}
                        — {localized(item.product.name, i18n.language)}
                      </span>
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

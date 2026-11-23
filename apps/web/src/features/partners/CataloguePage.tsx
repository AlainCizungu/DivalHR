import type {
  CatalogueAvailability,
  CataloguePartner,
  CatalogueProduct,
  PartnerProductType,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { PRODUCT_TYPES, localized } from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      items: CatalogueProduct[];
      next: string | null;
      availability: CatalogueAvailability;
    };

/**
 * MVP-120E: the controlled partner catalogue, for a tenant administrator and for an employee.
 * Information only. There is deliberately no apply, request, enrol or contact control: nothing
 * here sends anything to a partner, and nothing here shows an internal identifier, a contact, a
 * checklist result, a note or who administers the platform. The server filters by the
 * organization's own country, dates and statuses; the browser adds no rule of its own.
 */
export function CataloguePage({ audience }: { audience: 'tenant' | 'employee' }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [type, setType] = useState<PartnerProductType | ''>('');
  const [applied, setApplied] = useState<PartnerProductType | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [partners, setPartners] = useState<CataloguePartner[]>([]);
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);

  const query = useCallback(
    (cursor?: string) => ({ limit: 25, cursor, productType: applied || undefined }),
    [applied],
  );

  useEffect(() => {
    let active = true;
    core.GET('/catalogue/products', { params: { query: query() }, cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setLoad(
          data
            ? {
                kind: 'ready',
                items: data.items,
                next: data.nextCursor,
                availability: data.availability,
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

  useEffect(() => {
    let active = true;
    core.GET('/catalogue/partners', { cache: 'no-store' }).then(
      ({ data }) => {
        if (active) setPartners(data?.items ?? []);
      },
      () => {
        if (active) setPartners([]);
      },
    );
    return () => {
      active = false;
    };
  }, [core]);

  const more = async () => {
    if (load.kind !== 'ready' || load.next === null || loadingMore.current) return;
    loadingMore.current = true;
    setMoreBusy(true);
    try {
      const { data, error, response } = await core.GET('/catalogue/products', {
        params: { query: query(load.next) },
        cache: 'no-store',
      });
      setLoad(
        data
          ? {
              kind: 'ready',
              items: [...load.items, ...data.items],
              next: data.nextCursor,
              availability: data.availability,
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

  const day = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeZone: 'UTC' }).format(
      new Date(`${iso}T00:00:00Z`),
    );

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="catalogue-page" data-audience={audience}>
      <PageHeader
        titleId={`${ids}-title`}
        title={t(`catalogue.${audience}.title`)}
        description={t(`catalogue.${audience}.description`)}
      />
      <div className="card" role="note" data-testid="catalogue-notice">
        <p>
          <b>{t('catalogue.informationOnly')}</b>
        </p>
        <p>{t('catalogue.noApplication')}</p>
        <p>{t('catalogue.providerDetermines')}</p>
      </div>

      {partners.length > 0 && (
        <p className="muted" data-testid="catalogue-partners">
          {t('catalogue.partners', {
            names: partners.map((p) => p.displayName).join(', '),
          })}
        </p>
      )}

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
        <p role="status" data-testid="catalogue-loading">
          {t('catalogue.loading')}
        </p>
      )}
      {load.kind === 'failed' && <PartnerAlert failure={load.failure} testId="catalogue-failure" />}
      {load.kind === 'ready' && load.availability === 'ORGANIZATION_COUNTRY_UNKNOWN' && (
        <p className="card" role="status" data-testid="catalogue-country-unknown">
          {t('catalogue.countryUnknown')}
        </p>
      )}
      {load.kind === 'ready' &&
        load.availability === 'AVAILABLE' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="catalogue-empty">
            {t('catalogue.empty')}
          </p>
        ) : (
          <>
            <ul className="partner-catalogue" data-testid="catalogue-list">
              {load.items.map((item) => (
                <li
                  key={item.publicReference}
                  className="card"
                  data-testid="catalogue-product"
                  data-reference={item.publicReference}
                >
                  <h2>{localized(item.name, i18n.language)}</h2>
                  <p className="muted">
                    {item.partnerDisplayName} · {t(`accounts.productType.${item.productType}`)}
                  </p>
                  <p>{localized(item.summary, i18n.language)}</p>
                  <dl className="detail-list">
                    <dt>{t('catalogue.eligibility')}</dt>
                    <dd>{localized(item.eligibility, i18n.language)}</dd>
                    <dt>{t('catalogue.disclosure')}</dt>
                    <dd>{localized(item.disclosure, i18n.language)}</dd>
                    <dt>{t('catalogue.effective')}</dt>
                    <dd>
                      {day(item.effectiveDate)}
                      {item.endDate ? ` – ${day(item.endDate)}` : ''}
                    </dd>
                    {item.minMonths !== null && item.maxMonths !== null && (
                      <>
                        <dt>{t('catalogue.duration')}</dt>
                        <dd>
                          {t('catalogue.months', { min: item.minMonths, max: item.maxMonths })}
                        </dd>
                      </>
                    )}
                    {item.currencies.length > 0 && (
                      <>
                        <dt>{t('catalogue.amounts')}</dt>
                        <dd>
                          {item.currencies
                            .map(
                              (l) => `${l.currency}: ${l.minAmount ?? '—'} – ${l.maxAmount ?? '—'}`,
                            )
                            .join('; ')}
                        </dd>
                      </>
                    )}
                  </dl>
                  {item.infoUrl && (
                    <p>
                      <a href={item.infoUrl} rel="noopener noreferrer" target="_blank">
                        {t('catalogue.moreInformation')}
                        <span className="visually-hidden"> {t('catalogue.opensNewTab')}</span>
                      </a>
                    </p>
                  )}
                  <p className="muted" data-testid="catalogue-product-notice">
                    {t('catalogue.informationOnly')}. {t('catalogue.noApplication')}{' '}
                    {t('catalogue.providerDetermines')}
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

import type {
  PartnerApplicationPage,
  PartnerApplicationStatus,
  PartnerApplicationSummary,
  PartnerCapability,
  PartnerCategory,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { PartnerAlert, PartnerStatus } from './PartnerBits';
import {
  CAPABILITIES,
  CATEGORIES,
  countryOptions,
  NETWORK_FAILURE,
  STATUSES,
  partnerFailureOf,
  referencePath,
  type PartnerFailure,
} from './partners';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | {
      kind: 'ready';
      items: PartnerApplicationSummary[];
      next: string | null;
      counts: PartnerApplicationPage['statusCounts'];
    };

interface Filters {
  status: PartnerApplicationStatus | '';
  category: PartnerCategory | '';
  capability: PartnerCapability | '';
  country: string;
  submittedFrom: string;
  submittedTo: string;
}

const NO_FILTERS: Filters = {
  status: '',
  category: '',
  capability: '',
  country: '',
  submittedFrom: '',
  submittedTo: '',
};

const startOfDay = (date: string) =>
  date ? new Date(`${date}T00:00:00Z`).toISOString() : undefined;
const endOfDay = (date: string) => (date ? new Date(`${date}T23:59:59Z`).toISOString() : undefined);

/**
 * MVP-120D: the platform review queue. A platform administrator filters, counts by status and
 * opens an application. Filters stay in memory (never in the address bar) and nothing here
 * stores an application in the browser.
 */
export function PartnerApplicationsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [applied, setApplied] = useState<Filters>(NO_FILTERS);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);
  const regionNames = new Intl.DisplayNames([i18n.language], { type: 'region' });

  const query = useCallback(
    (cursor?: string) => ({
      limit: 25,
      cursor,
      status: applied.status || undefined,
      category: applied.category || undefined,
      capability: applied.capability || undefined,
      country: applied.country || undefined,
      submittedFrom: startOfDay(applied.submittedFrom),
      submittedTo: endOfDay(applied.submittedTo),
    }),
    [applied],
  );

  useEffect(() => {
    let active = true;
    setLoad({ kind: 'loading' });
    core
      .GET('/platform/partner-applications', { params: { query: query() }, cache: 'no-store' })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? {
                  kind: 'ready',
                  items: data.items,
                  next: data.nextCursor,
                  counts: data.statusCounts,
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
      const { data, error, response } = await core.GET('/platform/partner-applications', {
        params: { query: query(load.next) },
        cache: 'no-store',
      });
      setLoad(
        data
          ? {
              kind: 'ready',
              items: [...load.items, ...data.items],
              next: data.nextCursor,
              counts: data.statusCounts,
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

  const select = (
    key: Exclude<keyof Filters, 'submittedFrom' | 'submittedTo'>,
    options: readonly { value: string; label: string }[],
  ) => (
    <div className="field">
      <label htmlFor={`${ids}-${key}`}>{t(`partners.review.filters.${key}`)}</label>
      <select
        id={`${ids}-${key}`}
        value={filters[key]}
        onChange={(event) => {
          setFilters({ ...filters, [key]: event.target.value });
        }}
      >
        <option value="">{t('partners.review.filters.any')}</option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="partner-applications-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('partners.review.title')}
        description={t('partners.review.description')}
      />
      <p className="card" role="note">
        {t('partners.review.queueNotice')}
      </p>

      {load.kind === 'ready' && (
        <ul
          className="partner-counts"
          aria-label={t('partners.review.counts')}
          data-testid="partner-counts"
        >
          {STATUSES.map((status) => (
            <li key={status} data-status={status}>
              <span>{t(`partners.status.${status}`)}</span>{' '}
              <b data-testid={`partner-count-${status}`}>{load.counts[status]}</b>
            </li>
          ))}
        </ul>
      )}

      <form
        className="partner-filters"
        aria-label={t('partners.review.filters.label')}
        onSubmit={(event) => {
          event.preventDefault();
          setApplied(filters);
        }}
      >
        {select(
          'status',
          STATUSES.map((s) => ({ value: s, label: t(`partners.status.${s}`) })),
        )}
        {select(
          'category',
          CATEGORIES.map((c) => ({ value: c, label: t(`partners.category.${c}`) })),
        )}
        {select(
          'capability',
          CAPABILITIES.map((c) => ({ value: c, label: t(`partners.capability.${c}`) })),
        )}
        {select('country', countryOptions(i18n.language))}
        <div className="field">
          <label htmlFor={`${ids}-from`}>{t('partners.review.filters.submittedFrom')}</label>
          <input
            id={`${ids}-from`}
            type="date"
            value={filters.submittedFrom}
            onChange={(event) => {
              setFilters({ ...filters, submittedFrom: event.target.value });
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-to`}>{t('partners.review.filters.submittedTo')}</label>
          <input
            id={`${ids}-to`}
            type="date"
            value={filters.submittedTo}
            onChange={(event) => {
              setFilters({ ...filters, submittedTo: event.target.value });
            }}
          />
        </div>
        <div className="button-row">
          <button type="submit" className="button">
            {t('partners.review.filters.apply')}
          </button>
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              setFilters(NO_FILTERS);
              setApplied(NO_FILTERS);
            }}
          >
            {t('partners.review.filters.clear')}
          </button>
        </div>
      </form>

      {load.kind === 'loading' && (
        <p role="status" data-testid="partner-applications-loading">
          {t('partners.review.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PartnerAlert failure={load.failure} testId="partner-applications-failure" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="partner-applications-empty">
            {t('partners.review.empty')}
          </p>
        ) : (
          <>
            <ScrollRegion label={t('partners.review.tableLabel')}>
              <table className="timesheet-table" data-testid="partner-applications-table">
                <caption className="visually-hidden">{t('partners.review.tableLabel')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('partners.review.columns.reference')}</th>
                    <th scope="col">{t('partners.review.columns.organization')}</th>
                    <th scope="col">{t('partners.review.columns.category')}</th>
                    <th scope="col">{t('partners.review.columns.status')}</th>
                    <th scope="col">{t('partners.review.columns.submitted')}</th>
                  </tr>
                </thead>
                <tbody>
                  {load.items.map((item) => (
                    <tr
                      key={item.reference}
                      data-testid="partner-application-row"
                      data-reference={item.reference}
                      data-status={item.status}
                    >
                      <th scope="row">
                        <Link to={referencePath(item.reference)}>{item.reference}</Link>
                      </th>
                      <td>
                        {item.legalName}
                        <span className="muted">
                          {' '}
                          ·{' '}
                          {regionNames.of(item.countryOfRegistration) ?? item.countryOfRegistration}
                        </span>
                      </td>
                      <td>{t(`partners.category.${item.primaryCategory}`)}</td>
                      <td>
                        <PartnerStatus status={item.status} />
                      </td>
                      <td>
                        <time dateTime={item.submittedAt}>
                          {new Intl.DateTimeFormat(i18n.language, {
                            dateStyle: 'medium',
                            timeStyle: 'short',
                          }).format(new Date(item.submittedAt))}
                        </time>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
            {load.next !== null && (
              <button
                type="button"
                className="button button--secondary"
                disabled={moreBusy}
                onClick={() => void more()}
              >
                {t('partners.review.more')}
              </button>
            )}
          </>
        ))}
    </section>
  );
}

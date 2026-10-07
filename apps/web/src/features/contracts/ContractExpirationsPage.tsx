import type {
  ContractExpiration,
  ContractExpirationCategory,
  ContractExpirationCounts,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { HistoryAlert } from '../people/HistoryAlert';
import {
  codePoints,
  formatDate,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  type HistoryFailure,
} from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { CATEGORIES, COUNT_OF, daysText, toneOf } from './expirations';

const PAGE_SIZE = 25;

/** The applied filters; the draft form state is separate until "Apply filters". */
interface Filters {
  query: string | null;
  unitId: string | null;
  categories: readonly ContractExpirationCategory[];
}

const NO_FILTERS: Filters = { query: null, unitId: null, categories: CATEGORIES };

interface Page {
  asOf: string;
  timezone: string;
  counts: ContractExpirationCounts;
  items: ContractExpiration[];
  nextCursor: string | null;
}

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | ({ kind: 'ready'; loadingMore: boolean } & Page);

/** A refusal of this page reads as this page's own message. */
function failureOf(response: Response, error: unknown): HistoryFailure {
  const failure = historyFailureOf(response, error);
  return response.status === 403
    ? { ...failure, messageKey: 'contractExpirations.unauthorized' }
    : failure;
}

/** The unit filter: legal entity, then site, then a department or a cost center (A31A-3). */
function UnitFilter({
  idPrefix,
  legalEntityId,
  siteId,
  unitId,
  onChange,
}: {
  idPrefix: string;
  legalEntityId: string;
  siteId: string;
  unitId: string;
  onChange: (next: { legalEntityId: string; siteId: string; unitId: string }) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const legalEntities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const sites = useOptions(legalEntityId || null, () =>
    loadAll((cursor) =>
      core.GET('/sites', { params: { query: { legalEntityId, cursor, limit: 200 } } }),
    ),
  );
  const departments = useOptions(siteId || null, () =>
    loadAll((cursor) =>
      core.GET('/departments', { params: { query: { siteId, cursor, limit: 200 } } }),
    ),
  );
  const costCenters = useOptions(siteId || null, () =>
    loadAll((cursor) =>
      core.GET('/cost-centers', { params: { query: { siteId, cursor, limit: 200 } } }),
    ),
  );
  const failed = [legalEntities, sites, departments, costCenters].some(
    (options) => options.status === 'failed',
  );
  const label = (option: { name: string; code: string }) =>
    t('contractExpirations.unitName', { name: option.name, code: option.code });

  return (
    <fieldset className="field" data-testid="unit-filter">
      <legend>{t('contractExpirations.filters.unit')}</legend>
      <p id={`${idPrefix}-help`} className="field__help">
        {t('contractExpirations.filters.unitHelp')}
      </p>
      {failed && (
        <p className="field__error" role="alert">
          {t('contractExpirations.filters.unitsUnavailable')}
        </p>
      )}
      <label htmlFor={`${idPrefix}-le`}>{t('contractExpirations.filters.legalEntity')}</label>
      <select
        id={`${idPrefix}-le`}
        value={legalEntityId}
        aria-describedby={`${idPrefix}-help`}
        onChange={(event) => {
          onChange({ legalEntityId: event.target.value, siteId: '', unitId: '' });
        }}
      >
        <option value="">{t('contractExpirations.filters.anyLegalEntity')}</option>
        {legalEntities.items.map((option) => (
          <option key={option.id} value={option.id}>
            {label(option)}
          </option>
        ))}
      </select>
      <label htmlFor={`${idPrefix}-site`}>{t('contractExpirations.filters.site')}</label>
      <select
        id={`${idPrefix}-site`}
        value={siteId}
        disabled={!legalEntityId}
        onChange={(event) => {
          onChange({ legalEntityId, siteId: event.target.value, unitId: '' });
        }}
      >
        <option value="">{t('contractExpirations.filters.anySite')}</option>
        {sites.items.map((option) => (
          <option key={option.id} value={option.id}>
            {label(option)}
          </option>
        ))}
      </select>
      <label htmlFor={`${idPrefix}-unit`}>{t('contractExpirations.filters.department')}</label>
      <select
        id={`${idPrefix}-unit`}
        value={unitId}
        disabled={!siteId}
        onChange={(event) => {
          onChange({ legalEntityId, siteId, unitId: event.target.value });
        }}
      >
        <option value="">{t('contractExpirations.filters.anyUnit')}</option>
        {departments.items.length > 0 && (
          <optgroup label={t('contractExpirations.filters.departments')}>
            {departments.items.map((option) => (
              <option key={option.id} value={option.id}>
                {label(option)}
              </option>
            ))}
          </optgroup>
        )}
        {costCenters.items.length > 0 && (
          <optgroup label={t('contractExpirations.filters.costCenters')}>
            {costCenters.items.map((option) => (
              <option key={option.id} value={option.id}>
                {label(option)}
              </option>
            ))}
          </optgroup>
        )}
      </select>
    </fieldset>
  );
}

/**
 * MVP-031A (Issue #73): contracts that have ended or end within 90 days, one per employment, most
 * urgent first. The business date, day counts and categories come from the server; the search
 * text is sent in a POST body only and never enters the URL, history or browser storage. Rows link
 * to the employee record; the contract UUID is never displayed (C4).
 */
export function ContractExpirationsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [draftQuery, setDraftQuery] = useState('');
  const [draftUnit, setDraftUnit] = useState({ legalEntityId: '', siteId: '', unitId: '' });
  const [queryProblem, setQueryProblem] = useState(false);
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [lastCounts, setLastCounts] = useState<ContractExpirationCounts | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const queryInput = useRef<HTMLInputElement>(null);
  const firstNew = useRef<string | null>(null);
  const rowLinks = useRef(new Map<string, HTMLAnchorElement>());

  const fetchPage = useCallback(
    (applied: Filters, cursor?: string) =>
      core.POST('/contract-expirations/search', {
        body: {
          query: applied.query,
          unitId: applied.unitId,
          categories:
            applied.categories.length === CATEGORIES.length ? null : [...applied.categories],
          cursor: cursor ?? null,
          limit: PAGE_SIZE,
        },
        cache: 'no-store',
      }),
    [core],
  );

  useEffect(() => {
    let active = true;
    fetchPage(filters).then(
      ({ data, error, response }) => {
        if (!active) return;
        if (data) {
          setListing({ kind: 'ready', loadingMore: false, ...data });
          setLastCounts(data.counts);
          setAnnouncement(t('contractExpirations.found', { count: data.items.length }));
        } else {
          setListing({ kind: 'failed', failure: failureOf(response, error) });
        }
      },
      () => {
        if (active) setListing({ kind: 'failed', failure: HISTORY_NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [fetchPage, filters, t]);

  useEffect(() => {
    if (firstNew.current) {
      rowLinks.current.get(firstNew.current)?.focus();
      firstNew.current = null;
    }
  });

  const apply = (next: Filters) => {
    setListing({ kind: 'loading' });
    setFilters(next);
  };

  const onApply = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = draftQuery.normalize('NFC').trim();
    if (text !== '' && (codePoints(text) < 2 || codePoints(text) > 100)) {
      setQueryProblem(true);
      queryInput.current?.focus();
      return;
    }
    setQueryProblem(false);
    apply({ ...filters, query: text === '' ? null : text, unitId: draftUnit.unitId || null });
  };

  const onReset = () => {
    setDraftQuery('');
    setDraftUnit({ legalEntityId: '', siteId: '', unitId: '' });
    setQueryProblem(false);
    apply(NO_FILTERS);
  };

  const toggle = (category: ContractExpirationCategory) => {
    const shown = filters.categories.includes(category);
    if (shown && filters.categories.length === 1) return;
    apply({
      ...filters,
      categories: CATEGORIES.filter((c) =>
        c === category ? !shown : filters.categories.includes(c),
      ),
    });
  };

  const loadMore = async () => {
    if (listing.kind !== 'ready' || !listing.nextCursor || listing.loadingMore) return;
    setListing({ ...listing, loadingMore: true });
    try {
      const { data, error, response } = await fetchPage(filters, listing.nextCursor);
      if (data) {
        firstNew.current = data.items[0]?.contractId ?? null;
        setListing({
          ...listing,
          items: [...listing.items, ...data.items],
          nextCursor: data.nextCursor,
          loadingMore: false,
        });
        setAnnouncement(
          t('contractExpirations.found', { count: listing.items.length + data.items.length }),
        );
      } else {
        setListing({ kind: 'failed', failure: failureOf(response, error) });
      }
    } catch {
      setListing({ kind: 'failed', failure: HISTORY_NETWORK_FAILURE });
    }
  };

  const filtered =
    filters.query !== null ||
    filters.unitId !== null ||
    filters.categories.length !== CATEGORIES.length;
  const counts = listing.kind === 'ready' ? listing.counts : lastCounts;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="contract-expirations">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('contractExpirations.title')}
        description={t('contractExpirations.description')}
      />
      {listing.kind === 'ready' && (
        <p className="muted" data-testid="as-of">
          {t('contractExpirations.asOf', {
            date: formatDate(i18n.language, listing.asOf),
            timezone: listing.timezone,
          })}
        </p>
      )}
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-filters`} className="card">
        <h2 id={`${ids}-filters`}>{t('contractExpirations.filters.title')}</h2>
        <div
          role="group"
          aria-label={t('contractExpirations.categoriesLabel')}
          className="button-row"
          data-testid="category-toggles"
        >
          {CATEGORIES.map((category) => {
            const pressed = filters.categories.includes(category);
            return (
              <button
                key={category}
                type="button"
                className={pressed ? 'button' : 'button button--secondary'}
                aria-pressed={pressed}
                data-testid={`category-${category}`}
                onClick={() => {
                  toggle(category);
                }}
              >
                {counts
                  ? t('contractExpirations.categoryToggle', {
                      label: t(`contractExpirations.category.${category}`),
                      count: counts[COUNT_OF[category]],
                    })
                  : t(`contractExpirations.category.${category}`)}
              </button>
            );
          })}
        </div>
        <form noValidate role="search" onSubmit={onApply} data-testid="expiration-filters">
          <div className="field">
            <label htmlFor={`${ids}-query`}>{t('contractExpirations.filters.query')}</label>
            <p id={`${ids}-query-help`} className="field__help">
              {t('contractExpirations.filters.queryHelp')}
            </p>
            <input
              id={`${ids}-query`}
              ref={queryInput}
              type="text"
              autoComplete="off"
              value={draftQuery}
              maxLength={200}
              aria-invalid={queryProblem ? true : undefined}
              aria-describedby={
                queryProblem ? `${ids}-query-help ${ids}-query-error` : `${ids}-query-help`
              }
              onChange={(event) => {
                setDraftQuery(event.target.value);
              }}
            />
            {queryProblem && (
              <p id={`${ids}-query-error`} className="field__error" role="alert">
                {t('contractExpirations.filters.queryLength')}
              </p>
            )}
          </div>
          <UnitFilter idPrefix={`${ids}-unit`} {...draftUnit} onChange={setDraftUnit} />
          <div className="actions">
            <button type="submit" className="button">
              {t('contractExpirations.filters.apply')}
            </button>
            {filtered && (
              <button type="button" className="button button--secondary" onClick={onReset}>
                {t('contractExpirations.filters.reset')}
              </button>
            )}
          </div>
        </form>
      </section>

      <section
        aria-labelledby={`${ids}-results`}
        className="card"
        aria-busy={listing.kind === 'loading'}
      >
        <h2 id={`${ids}-results`}>{t('contractExpirations.results')}</h2>
        {listing.kind === 'loading' && <p>{t('contractExpirations.loading')}</p>}
        {listing.kind === 'failed' && (
          <>
            <HistoryAlert failure={listing.failure} data-testid="expirations-error" />
            <button
              type="button"
              className="button"
              onClick={() => {
                apply({ ...filters });
              }}
            >
              {t('contractExpirations.retry')}
            </button>
          </>
        )}
        {listing.kind === 'ready' && listing.items.length === 0 && (
          <p data-testid="expirations-empty">
            {filtered ? t('contractExpirations.emptyFiltered') : t('contractExpirations.empty')}
          </p>
        )}
        {listing.kind === 'ready' && listing.items.length > 0 && (
          <ScrollRegion label={t('contractExpirations.caption')}>
            <table data-testid="expirations-table">
              <caption>{t('contractExpirations.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('contractExpirations.columns.employee')}</th>
                  <th scope="col">{t('contractExpirations.columns.employeeNumber')}</th>
                  <th scope="col">{t('contractExpirations.columns.unit')}</th>
                  <th scope="col">{t('contractExpirations.columns.endDate')}</th>
                  <th scope="col">{t('contractExpirations.columns.status')}</th>
                </tr>
              </thead>
              <tbody>
                {listing.items.map((item) => (
                  <tr key={item.contractId} data-testid="expiration-row">
                    <th scope="row">
                      <Link
                        to={`/admin/people/${item.employeeId}#contracts`}
                        ref={(element) => {
                          if (element) rowLinks.current.set(item.contractId, element);
                          else rowLinks.current.delete(item.contractId);
                        }}
                      >
                        {t('employees.fullName', {
                          given: item.givenNames,
                          family: item.familyName,
                        })}
                      </Link>
                    </th>
                    <td>{item.employeeNumber}</td>
                    <td>
                      {item.unit
                        ? t('contractExpirations.unitName', {
                            name: item.unit.name,
                            code: item.unit.code,
                          })
                        : t('contractExpirations.noUnit')}
                    </td>
                    <td>{formatDate(i18n.language, item.endDate)}</td>
                    <td>
                      <StatusBadge tone={toneOf(item.category)}>{daysText(t, item)}</StatusBadge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {listing.kind === 'ready' && listing.nextCursor && (
          <button
            type="button"
            className="button button--secondary"
            disabled={listing.loadingMore}
            onClick={() => void loadMore()}
          >
            {listing.loadingMore ? t('contractExpirations.loading') : t('contractExpirations.more')}
          </button>
        )}
      </section>
    </section>
  );
}

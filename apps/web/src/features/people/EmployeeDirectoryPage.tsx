import type { EmployeeSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { HistoryAlert } from './HistoryAlert';
import {
  codePoints,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  type HistoryFailure,
} from './history';
import { ScrollRegion } from './ScrollRegion';

const PAGE_SIZE = 25;

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | { kind: 'ready'; items: EmployeeSummary[]; nextCursor: string | null; loadingMore: boolean };

/**
 * MVP-021: the employee directory, by employee number, and search by employee-number prefix or
 * name words (accents and case ignored). The query is sent in a POST body only: it never enters
 * the URL, history, logs or browser storage. Every read is recorded by the server as a disclosure.
 */
export function EmployeeDirectoryPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [draft, setDraft] = useState('');
  const [queryProblem, setQueryProblem] = useState(false);
  const [query, setQuery] = useState<string | null>(null);
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [announcement, setAnnouncement] = useState('');
  const queryInput = useRef<HTMLInputElement>(null);
  const firstNew = useRef<string | null>(null);
  const rowLinks = useRef(new Map<string, HTMLAnchorElement>());

  const fetchPage = useCallback(
    async (search: string | null, cursor?: string) => {
      const result =
        search === null
          ? await core.GET('/employees', {
              params: { query: { cursor, limit: PAGE_SIZE } },
              cache: 'no-store',
            })
          : await core.POST('/employees/search', {
              body: { query: search, cursor: cursor ?? null, limit: PAGE_SIZE },
              cache: 'no-store',
            });
      return result;
    },
    [core],
  );

  const load = useCallback(
    async (search: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await fetchPage(search);
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor, loadingMore: false }
          : { kind: 'failed', failure: historyFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: HISTORY_NETWORK_FAILURE };
      }
    },
    [fetchPage],
  );

  const show = useCallback(
    (next: Listing) => {
      setListing(next);
      if (next.kind === 'ready') {
        setAnnouncement(t('employees.directory.found', { count: next.items.length }));
      }
    },
    [t],
  );

  useEffect(() => {
    let active = true;
    void load(query).then((next) => {
      if (active) show(next);
    });
    return () => {
      active = false;
    };
  }, [load, query, show]);

  useEffect(() => {
    if (firstNew.current) {
      rowLinks.current.get(firstNew.current)?.focus();
      firstNew.current = null;
    }
  });

  const onSearch = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = draft.normalize('NFC').trim();
    if (codePoints(text) < 2 || codePoints(text) > 100) {
      setQueryProblem(true);
      queryInput.current?.focus();
      return;
    }
    setQueryProblem(false);
    setListing({ kind: 'loading' });
    setQuery(text);
  };

  const showAll = () => {
    setDraft('');
    setQueryProblem(false);
    setListing({ kind: 'loading' });
    setQuery(null);
  };

  const loadMore = async () => {
    if (listing.kind !== 'ready' || !listing.nextCursor || listing.loadingMore) return;
    setListing({ ...listing, loadingMore: true });
    try {
      const { data, error, response } = await fetchPage(query, listing.nextCursor);
      if (data) {
        firstNew.current = data.items[0]?.id ?? null;
        setListing({
          kind: 'ready',
          items: [...listing.items, ...data.items],
          nextCursor: data.nextCursor,
          loadingMore: false,
        });
      } else {
        setListing({ kind: 'failed', failure: historyFailureOf(response, error) });
      }
    } catch {
      setListing({ kind: 'failed', failure: HISTORY_NETWORK_FAILURE });
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('employees.directory.title')}</h1>
      <p className="muted">{t('employees.directory.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>
      <section aria-labelledby={`${ids}-search`} className="card">
        <h2 id={`${ids}-search`}>{t('employees.directory.searchTitle')}</h2>
        <form noValidate role="search" onSubmit={onSearch} data-testid="employee-search">
          <div className="field">
            <label htmlFor={`${ids}-query`}>{t('employees.directory.query')}</label>
            <p id={`${ids}-query-help`} className="field__help">
              {t('employees.directory.queryHelp')}
            </p>
            <input
              id={`${ids}-query`}
              ref={queryInput}
              type="text"
              autoComplete="off"
              value={draft}
              maxLength={200}
              aria-invalid={queryProblem ? true : undefined}
              aria-describedby={
                queryProblem ? `${ids}-query-help ${ids}-query-error` : `${ids}-query-help`
              }
              onChange={(event) => {
                setDraft(event.target.value);
              }}
            />
            {queryProblem && (
              <p id={`${ids}-query-error`} className="field__error" role="alert">
                {t('employees.directory.queryLength')}
              </p>
            )}
          </div>
          <div className="actions">
            <button type="submit" className="button">
              {t('employees.directory.search')}
            </button>
            {query !== null && (
              <button type="button" className="button button--secondary" onClick={showAll}>
                {t('employees.directory.showAll')}
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
        <h2 id={`${ids}-results`}>
          {query === null
            ? t('employees.directory.allTitle')
            : t('employees.directory.resultsTitle')}
        </h2>
        {listing.kind === 'loading' && <p>{t('employees.loading')}</p>}
        {listing.kind === 'failed' && (
          <>
            <HistoryAlert failure={listing.failure} data-testid="directory-error" />
            <button
              type="button"
              className="button"
              onClick={() => {
                setListing({ kind: 'loading' });
                void load(query).then(show);
              }}
            >
              {t('employees.retry')}
            </button>
          </>
        )}
        {listing.kind === 'ready' && listing.items.length === 0 && (
          <p data-testid="directory-empty">
            {query === null ? t('employees.directory.none') : t('employees.directory.noMatch')}
          </p>
        )}
        {listing.kind === 'ready' && listing.items.length > 0 && (
          <ScrollRegion label={t('employees.directory.caption')}>
            <table data-testid="directory-table">
              <caption>{t('employees.directory.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('employees.fields.employeeNumber')}</th>
                  <th scope="col">{t('employees.fields.name')}</th>
                  <th scope="col">{t('employees.fields.status')}</th>
                </tr>
              </thead>
              <tbody>
                {listing.items.map((employee) => (
                  <tr key={employee.id}>
                    <td>{employee.employeeNumber}</td>
                    <th scope="row">
                      <Link
                        to={`/admin/people/${employee.id}`}
                        ref={(element) => {
                          if (element) rowLinks.current.set(employee.id, element);
                          else rowLinks.current.delete(employee.id);
                        }}
                      >
                        {t('employees.fullName', {
                          given: employee.givenNames,
                          family: employee.familyName,
                        })}
                      </Link>
                    </th>
                    <td>{t(`employees.employmentStatus.${employee.employmentStatus}`)}</td>
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
            {listing.loadingMore ? t('employees.loading') : t('employees.loadMore')}
          </button>
        )}
      </section>
    </section>
  );
}

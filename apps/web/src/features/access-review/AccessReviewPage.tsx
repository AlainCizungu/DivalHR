import type {
  AccessReviewEntry,
  AccessReviewSummary,
  LegalEntity,
  Problem,
  Site,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { emailError } from '../users/invitationForm';

type Role = 'tenant-admin' | 'employee';

/** Applied filters. Held in component state only: never in the URL, history or storage (A6). */
type Filters = { role: Role | ''; legalEntityId: string; siteId: string };

type Failure = { messageKey: string; correlationId?: string; retryAfter?: number };

type Results =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | {
      kind: 'ready';
      mode: 'list' | 'lookup';
      items: AccessReviewEntry[];
      nextCursor?: string;
      loadingMore: boolean;
    };

const NO_FILTERS: Filters = { role: '', legalEntityId: '', siteId: '' };

/** Roles in the fixed summary order. */
const ROLES: readonly Role[] = ['tenant-admin', 'employee'];

function failureOf(response: Response, error: unknown): Failure {
  const problem = error as Partial<Problem> | undefined;
  const retry = Number(response.headers.get('Retry-After'));
  return {
    messageKey: problem?.code ? `errors.${problem.code}` : 'errors.generic',
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
}

/**
 * MVP-012B: the read-only access review. Shows who can access the organization's data with which
 * role; every access is organization-wide and appears as inherited in legal-entity and site
 * views. Addresses are confidential: requests use cache: 'no-store' and nothing (filters, lookup
 * input, cursors, IDs, addresses) is written to the URL, history or browser storage.
 */
export function AccessReviewPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [announcement, setAnnouncement] = useState('');
  const [summary, setSummary] = useState<AccessReviewSummary | null>(null);
  const [draft, setDraft] = useState<Filters>(NO_FILTERS);
  const [applied, setApplied] = useState<Filters>(NO_FILTERS);
  const [legalEntities, setLegalEntities] = useState<LegalEntity[]>([]);
  const [sites, setSites] = useState<Site[]>([]);
  const [results, setResults] = useState<Results>({ kind: 'loading' });
  const [email, setEmail] = useState('');
  const [emailProblem, setEmailProblem] = useState<string | null>(null);
  const firstNewRow = useRef<string | null>(null);
  const rowRefs = useRef(new Map<string, HTMLTableRowElement>());

  const formatDate = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(iso),
    );
  const roleName = (role: Role) => t(`users.form.roles.${role}`);

  const fetchPage = useCallback(
    async (filters: Filters, cursor?: string) => {
      const query: Record<string, string> = {};
      if (filters.role) query.role = filters.role;
      if (filters.siteId) query.siteId = filters.siteId;
      else if (filters.legalEntityId) query.legalEntityId = filters.legalEntityId;
      if (cursor) query.cursor = cursor;
      return core.GET('/access-review/entries', { params: { query }, cache: 'no-store' });
    },
    [core],
  );

  // Initial data: summary, unfiltered first page and the legal entities for the filter.
  useEffect(() => {
    const status = { active: true };
    void (async () => {
      try {
        const [counts, page, entities] = await Promise.all([
          core.GET('/access-review/summary', { cache: 'no-store' }),
          fetchPage(NO_FILTERS),
          core.GET('/legal-entities', { params: { query: { limit: 200 } }, cache: 'no-store' }),
        ]);
        if (!status.active) return;
        if (counts.data) setSummary(counts.data);
        if (entities.data) setLegalEntities(entities.data.data);
        if (page.data) {
          setResults({
            kind: 'ready',
            mode: 'list',
            items: page.data.data,
            nextCursor: page.data.nextCursor,
            loadingMore: false,
          });
          setAnnouncement(t('accessReview.announce.results', { count: page.data.data.length }));
        } else {
          setResults({ kind: 'failed', failure: failureOf(page.response, page.error) });
        }
      } catch {
        if (status.active) {
          setResults({ kind: 'failed', failure: { messageKey: 'errors.network' } });
        }
      }
    })();
    return () => {
      status.active = false;
    };
  }, [core, fetchPage, t]);

  // Sites of the selected legal entity.
  useEffect(() => {
    if (!draft.legalEntityId) return;
    let active = true;
    void core
      .GET('/sites', {
        params: { query: { legalEntityId: draft.legalEntityId, limit: 200 } },
        cache: 'no-store',
      })
      .then(({ data }) => {
        if (active) setSites(data?.data ?? []);
      })
      .catch(() => {
        if (active) setSites([]);
      });
    return () => {
      active = false;
    };
  }, [core, draft.legalEntityId]);

  const load = async (filters: Filters) => {
    setResults({ kind: 'loading' });
    try {
      const page = await fetchPage(filters);
      if (page.data) {
        setResults({
          kind: 'ready',
          mode: 'list',
          items: page.data.data,
          nextCursor: page.data.nextCursor,
          loadingMore: false,
        });
        setAnnouncement(t('accessReview.announce.results', { count: page.data.data.length }));
      } else {
        setResults({ kind: 'failed', failure: failureOf(page.response, page.error) });
      }
    } catch {
      setResults({ kind: 'failed', failure: { messageKey: 'errors.network' } });
    }
  };

  const apply = (filters: Filters) => {
    setDraft(filters);
    setApplied(filters);
    void load(filters);
  };

  const loadMore = async () => {
    if (results.kind !== 'ready' || !results.nextCursor || results.loadingMore) return;
    setResults({ ...results, loadingMore: true });
    try {
      const page = await fetchPage(applied, results.nextCursor);
      if (page.data) {
        const added = page.data.data;
        firstNewRow.current = added[0]?.membershipId ?? null;
        setResults({
          kind: 'ready',
          mode: 'list',
          items: [...results.items, ...added],
          nextCursor: page.data.nextCursor,
          loadingMore: false,
        });
        setAnnouncement(t('accessReview.announce.results', { count: added.length }));
      } else {
        setResults({ kind: 'failed', failure: failureOf(page.response, page.error) });
      }
    } catch {
      setResults({ kind: 'failed', failure: { messageKey: 'errors.network' } });
    }
  };

  // After "Show more", focus moves to the first new row.
  useEffect(() => {
    const id = firstNewRow.current;
    if (id && results.kind === 'ready') {
      firstNewRow.current = null;
      rowRefs.current.get(id)?.focus();
    }
  }, [results]);

  const onLookup = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const problem = emailError(email);
    if (problem) {
      setEmailProblem(problem);
      return;
    }
    setEmailProblem(null);
    setResults({ kind: 'loading' });
    try {
      const { data, error, response } = await core.POST('/access-review/lookup', {
        body: { email: email.trim() },
        cache: 'no-store',
      });
      if (data) {
        setResults({ kind: 'ready', mode: 'lookup', items: data.data, loadingMore: false });
        setAnnouncement(
          data.data.length === 0
            ? t('accessReview.announce.none')
            : t('accessReview.announce.lookup', { count: data.data.length }),
        );
      } else {
        setResults({ kind: 'failed', failure: failureOf(response, error) });
      }
    } catch {
      setResults({ kind: 'failed', failure: { messageKey: 'errors.network' } });
    }
  };

  const scopeLabel = (entry: AccessReviewEntry) => {
    if (!entry.matchedUnit) return t('accessReview.scope.entire');
    return entry.matchedUnit.type === 'SITE'
      ? t('accessReview.scope.inheritedSite')
      : t('accessReview.scope.inheritedLegalEntity');
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('accessReview.title')}</h1>
      <p className="muted">{t('accessReview.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      {summary && (
        <section aria-labelledby={`${ids}-summary`} className="card" data-testid="review-summary">
          <h2 id={`${ids}-summary`}>{t('accessReview.summary.title')}</h2>
          <ul className="hierarchy-list">
            {ROLES.map((role) => (
              <li key={role} className="hierarchy-item">
                <span data-testid={`summary-${role}`}>
                  {t('accessReview.summary.count', {
                    role: roleName(role),
                    count: summary.byRole[role],
                  })}
                </span>{' '}
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    apply({ ...NO_FILTERS, role });
                  }}
                >
                  {t('accessReview.summary.show', { role: roleName(role) })}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby={`${ids}-filters`} className="card">
        <h2 id={`${ids}-filters`}>{t('accessReview.filters.title')}</h2>
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            apply(draft);
          }}
          data-testid="review-filters"
        >
          <div className="field">
            <label htmlFor={`${ids}-role`}>{t('accessReview.filters.role')}</label>
            <select
              id={`${ids}-role`}
              value={draft.role}
              onChange={(event) => {
                setDraft({ ...draft, role: event.target.value as Role | '' });
              }}
            >
              <option value="">{t('accessReview.filters.allRoles')}</option>
              <option value="tenant-admin">{roleName('tenant-admin')}</option>
              <option value="employee">{roleName('employee')}</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-le`}>{t('accessReview.filters.legalEntity')}</label>
            <select
              id={`${ids}-le`}
              value={draft.legalEntityId}
              onChange={(event) => {
                setSites([]);
                setDraft({ ...draft, legalEntityId: event.target.value, siteId: '' });
              }}
            >
              <option value="">{t('accessReview.filters.allLegalEntities')}</option>
              {legalEntities.map((entity) => (
                <option key={entity.id} value={entity.id}>
                  {entity.name} ({entity.code})
                </option>
              ))}
            </select>
          </div>
          {draft.legalEntityId && (
            <div className="field">
              <label htmlFor={`${ids}-site`}>{t('accessReview.filters.site')}</label>
              <select
                id={`${ids}-site`}
                value={draft.siteId}
                onChange={(event) => {
                  setDraft({ ...draft, siteId: event.target.value });
                }}
              >
                <option value="">{t('accessReview.filters.allSites')}</option>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name} ({site.code})
                  </option>
                ))}
              </select>
            </div>
          )}
          <div className="actions">
            <button type="submit" className="button">
              {t('accessReview.filters.apply')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setSites([]);
                apply(NO_FILTERS);
              }}
            >
              {t('accessReview.filters.reset')}
            </button>
          </div>
        </form>
      </section>

      <section aria-labelledby={`${ids}-lookup`} className="card">
        <h2 id={`${ids}-lookup`}>{t('accessReview.lookup.title')}</h2>
        <form noValidate onSubmit={(event) => void onLookup(event)} data-testid="review-lookup">
          <div className="field">
            <label htmlFor={`${ids}-email`}>{t('accessReview.lookup.email')}</label>
            <p id={`${ids}-email-help`} className="field__help">
              {t('accessReview.lookup.help')}
            </p>
            <input
              id={`${ids}-email`}
              type="email"
              inputMode="email"
              autoComplete="off"
              spellCheck={false}
              value={email}
              aria-invalid={emailProblem ? true : undefined}
              aria-describedby={
                emailProblem ? `${ids}-email-help ${ids}-email-error` : `${ids}-email-help`
              }
              onChange={(event) => {
                setEmail(event.target.value);
              }}
            />
            {emailProblem && (
              <p id={`${ids}-email-error`} className="field__error" role="alert">
                {t(`accessReview.validation.email.${emailProblem}`)}
              </p>
            )}
          </div>
          <div className="actions">
            <button type="submit" className="button">
              {t('accessReview.lookup.submit')}
            </button>
            {results.kind === 'ready' && results.mode === 'lookup' && (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setEmail('');
                  void load(applied);
                }}
              >
                {t('accessReview.lookup.back')}
              </button>
            )}
          </div>
        </form>
      </section>

      <section
        aria-labelledby={`${ids}-results`}
        className="card"
        aria-busy={results.kind === 'loading'}
      >
        <h2 id={`${ids}-results`} className="visually-hidden">
          {t('accessReview.table.caption')}
        </h2>
        <p className="muted">
          {t('accessReview.pendingNote')}{' '}
          <Link to="/admin/users">{t('accessReview.pendingLink')}</Link>
        </p>
        <p className="muted" data-testid="time-zone-note">
          {t('accessReview.timeZoneNote')}
        </p>
        {results.kind === 'loading' && <p className="muted">{t('accessReview.loading')}</p>}
        {results.kind === 'failed' && (
          <div className="error-summary" role="alert" data-testid="review-error">
            <p>{t(results.failure.messageKey)}</p>
            {results.failure.retryAfter !== undefined && (
              <p>{t('accessReview.retryAfter', { count: results.failure.retryAfter })}</p>
            )}
            {results.failure.correlationId && (
              <p className="muted">
                {t('accessReview.reference', { id: results.failure.correlationId })}
              </p>
            )}
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void load(applied)}
            >
              {t('accessReview.retry')}
            </button>
          </div>
        )}
        {results.kind === 'ready' && results.items.length === 0 && (
          <p className="muted" data-testid="review-empty">
            {t(results.mode === 'lookup' ? 'accessReview.lookupEmpty' : 'accessReview.empty')}
          </p>
        )}
        {results.kind === 'ready' && results.items.length > 0 && (
          <div className="table-wrapper">
            <table data-testid="review-table">
              <caption>{t('accessReview.table.caption')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('accessReview.table.address')}</th>
                  <th scope="col">{t('accessReview.table.role')}</th>
                  <th scope="col">{t('accessReview.table.scope')}</th>
                  <th scope="col">{t('accessReview.table.grantedAt')}</th>
                  <th scope="col">{t('accessReview.table.access')}</th>
                </tr>
              </thead>
              <tbody>
                {results.items.map((entry) => (
                  <tr
                    key={entry.membershipId}
                    tabIndex={-1}
                    data-testid="review-row"
                    ref={(node) => {
                      if (node) rowRefs.current.set(entry.membershipId, node);
                      else rowRefs.current.delete(entry.membershipId);
                    }}
                  >
                    <td>{entry.email ?? t('accessReview.addressNotRecorded')}</td>
                    <td>{roleName(entry.role)}</td>
                    <td>{scopeLabel(entry)}</td>
                    <td>{formatDate(entry.grantedAt)}</td>
                    <td data-testid="review-access" data-access={entry.accessState}>
                      {t(`accessReview.accessState.${entry.accessState}`)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {results.kind === 'ready' && results.nextCursor && (
          <button
            type="button"
            className="button button--secondary"
            disabled={results.loadingMore}
            onClick={() => void loadMore()}
          >
            {results.loadingMore ? t('accessReview.loading') : t('accessReview.loadMore')}
          </button>
        )}
      </section>
    </section>
  );
}

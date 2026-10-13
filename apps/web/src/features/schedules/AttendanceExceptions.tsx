import type { AttendanceExceptionType } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge, type StatusTone } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { loadAll, useOptions } from '../people/unitOptions';
import { formatInstant } from './attendance';
import {
  DATE_FIELDS,
  DEFAULT_RANGE_DAYS,
  EMPTY_FILTERS,
  EXCEPTION_NETWORK_FAILURE,
  EXCEPTION_TYPES,
  MAX_RANGE_DAYS,
  exceptionEmployeeName,
  exceptionFailureOf,
  exceptionKey,
  exceptionQueryOf,
  filterProblemsOf,
  type ExceptionFailure,
  type ExceptionFilters,
  type ExceptionRow,
  type ExceptionView,
  type FilterField,
  type FilterProblem,
} from './attendanceExceptions';
import { formatTime, splitLocal } from './workSchedules';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ExceptionFailure }
  | { kind: 'ready'; items: ExceptionRow[]; nextCursor: string | null };

const TONES: Record<AttendanceExceptionType, StatusTone> = {
  MISSING_CHECK_IN: 'warning',
  LATE_CHECK_IN: 'info',
  EARLY_CHECK_OUT: 'info',
  MISSING_CHECK_OUT: 'warning',
};

interface Props {
  view: ExceptionView;
  /** The heading level of the section title; each exception card is one level below. */
  level: 1 | 2;
  /** The page title's id when the page already names this list (the organization view). */
  labelledBy?: string;
}

/**
 * MVP-060C: the read-only list of factual attendance exceptions of one view: « Anomalies de
 * présence » / "Attendance exceptions" in My attendance, « Anomalies » / "Exceptions" in Team
 * attendance and the organization's « Suivi de la présence » / "Attendance review". Each card shows
 * the neutral type, the work, the site, the scheduled local times in the site's time zone, the
 * recorded check-in and check-out and, when late or early, the difference in whole minutes. A
 * schedule comparison only: nothing here corrects, excuses or approves, and no pay or disciplinary
 * conclusion is drawn. Filters are applied explicitly; nothing is kept in browser storage or put
 * in a URL. The server decides the scope on every request.
 */
export function AttendanceExceptions({ view, level, labelledBy }: Props) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [draft, setDraft] = useState<ExceptionFilters>(EMPTY_FILTERS);
  const [legalEntityId, setLegalEntityId] = useState('');
  const [applied, setApplied] = useState<ExceptionFilters>(EMPTY_FILTERS);
  const [problems, setProblems] = useState<Map<FilterField, FilterProblem>>(new Map());
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const paging = useRef(false);
  const listRef = useRef<HTMLUListElement>(null);
  const prefix = `${ids}-${view}`;
  const titleId = labelledBy ?? `${prefix}-title`;
  const Title = level === 1 ? 'h1' : 'h2';
  const Card = level === 1 ? 'h2' : 'h3';
  // A page that already names this list (the organization view) is its only landmark.
  const Wrapper = labelledBy ? 'div' : 'section';

  const legalEntities = useOptions(view === 'admin' ? 'all' : null, () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const sites = useOptions(view === 'admin' && legalEntityId ? legalEntityId : null, () =>
    loadAll((cursor) =>
      core.GET('/sites', { params: { query: { legalEntityId, cursor, limit: 200 } } }),
    ),
  );

  const fetchPage = useCallback(
    async (filters: ExceptionFilters, cursor: string | null): Promise<Listing> => {
      try {
        const query = exceptionQueryOf(view, filters, cursor);
        const { data, error, response } =
          view === 'self'
            ? await core.GET('/me/attendance/exceptions', { params: { query }, cache: 'no-store' })
            : view === 'team'
              ? await core.GET('/me/team-attendance/exceptions', {
                  params: { query },
                  cache: 'no-store',
                })
              : await core.GET('/attendance/exceptions', { params: { query }, cache: 'no-store' });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: exceptionFailureOf(view, response, error) };
      } catch {
        return { kind: 'failed', failure: EXCEPTION_NETWORK_FAILURE };
      }
    },
    [core, view],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(applied, null).then((next) => {
      if (!active) return;
      setListing(next);
      if (next.kind === 'ready') {
        setAnnouncement(t('attendanceExceptions.found', { count: next.items.length }));
      }
    });
    return () => {
      active = false;
    };
  }, [fetchPage, applied, t]);

  const onApply = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = filterProblemsOf(draft);
    setProblems(found);
    if (found.size > 0) {
      const first = [...found.keys()][0];
      document.getElementById(`${prefix}-${first ?? 'from'}`)?.focus();
      return;
    }
    setAnnouncement('');
    setListing({ kind: 'loading' });
    setApplied({ ...draft });
  };

  const onReset = () => {
    setDraft(EMPTY_FILTERS);
    setLegalEntityId('');
    setProblems(new Map());
    setAnnouncement('');
    setListing({ kind: 'loading' });
    setApplied({ ...EMPTY_FILTERS });
  };

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    const before = listing.kind === 'ready' ? listing.items.length : 0;
    try {
      const next = await fetchPage(applied, cursor);
      setListing((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
      requestAnimationFrame(() => {
        const item = listRef.current?.children[before] as HTMLElement | undefined;
        item?.focus();
      });
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  const filtered = Object.values(applied).some((value) => value !== '');
  const fieldError = (field: FilterField) => {
    const problem = problems.get(field);
    return problem ? t(`attendanceExceptions.problems.${field}.${problem}`) : null;
  };
  const serverFields =
    listing.kind === 'failed' && listing.failure.kind === 'validation'
      ? (listing.failure.fields ?? [])
      : [];

  return (
    <Wrapper
      aria-labelledby={labelledBy ? undefined : titleId}
      className={labelledBy ? undefined : 'card'}
      data-testid={`attendance-exceptions-${view}`}
    >
      {!labelledBy && (
        <Title id={titleId} tabIndex={-1}>
          {t(`attendanceExceptions.title.${view}`)}
        </Title>
      )}
      <p data-testid="attendance-exceptions-disclaimer">{t('attendanceExceptions.disclaimer')}</p>
      <p className="muted" data-testid="attendance-exceptions-corrections">
        {t('attendanceExceptions.correctionsLater')}
      </p>
      <p role="status" className="visually-hidden" data-testid="attendance-exceptions-announcer">
        {announcement}
      </p>

      <form
        noValidate
        role="search"
        aria-label={t('attendanceExceptions.filters.label')}
        onSubmit={onApply}
        data-testid="attendance-exceptions-filters"
      >
        {view === 'admin' && (
          <fieldset className="field" data-testid="attendance-exceptions-site-filter">
            <legend>{t('attendanceExceptions.filters.site')}</legend>
            {(legalEntities.status === 'failed' || sites.status === 'failed') && (
              <p className="field__error" role="alert">
                {t('attendanceExceptions.filters.sitesUnavailable')}
              </p>
            )}
            <label htmlFor={`${prefix}-legal-entity`}>
              {t('attendanceExceptions.filters.legalEntity')}
            </label>
            <select
              id={`${prefix}-legal-entity`}
              value={legalEntityId}
              onChange={(event) => {
                setLegalEntityId(event.target.value);
                setDraft({ ...draft, siteId: '' });
              }}
            >
              <option value="">{t('attendanceExceptions.filters.anyLegalEntity')}</option>
              {legalEntities.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
            <label htmlFor={`${prefix}-siteId`}>
              {t('attendanceExceptions.filters.siteLabel')}
            </label>
            <select
              id={`${prefix}-siteId`}
              value={draft.siteId}
              disabled={!legalEntityId}
              onChange={(event) => {
                setDraft({ ...draft, siteId: event.target.value });
              }}
            >
              <option value="">{t('attendanceExceptions.filters.anySite')}</option>
              {sites.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
          </fieldset>
        )}
        <div className="field">
          <label htmlFor={`${prefix}-type`}>{t('attendanceExceptions.filters.type')}</label>
          <select
            id={`${prefix}-type`}
            value={draft.type}
            aria-invalid={serverFields.includes('type') ? true : undefined}
            onChange={(event) => {
              setDraft({ ...draft, type: event.target.value as ExceptionFilters['type'] });
            }}
          >
            <option value="">{t('attendanceExceptions.filters.anyType')}</option>
            {EXCEPTION_TYPES.map((type) => (
              <option key={type} value={type}>
                {t(`attendanceExceptions.type.${type}`)}
              </option>
            ))}
          </select>
        </div>
        <p id={`${prefix}-dates-help`} className="field__help">
          {t('attendanceExceptions.filters.datesHelp', {
            days: DEFAULT_RANGE_DAYS,
            max: MAX_RANGE_DAYS,
          })}
        </p>
        {DATE_FIELDS.map((field) => {
          const error = fieldError(field);
          const invalid = error !== null || serverFields.includes(field);
          return (
            <div className="field" key={field}>
              <label htmlFor={`${prefix}-${field}`}>
                {t(`attendanceExceptions.filters.${field}`)}
              </label>
              {error && (
                <p id={`${prefix}-${field}-error`} className="field__error">
                  {error}
                </p>
              )}
              <input
                id={`${prefix}-${field}`}
                type="date"
                value={draft[field]}
                aria-invalid={invalid ? true : undefined}
                aria-describedby={
                  error ? `${prefix}-dates-help ${prefix}-${field}-error` : `${prefix}-dates-help`
                }
                onChange={(event) => {
                  setDraft({ ...draft, [field]: event.target.value });
                }}
              />
            </div>
          );
        })}
        <div className="actions">
          <button type="submit" className="button" data-testid="attendance-exceptions-apply">
            {t('attendanceExceptions.filters.apply')}
          </button>
          {filtered && (
            <button
              type="button"
              className="button button--secondary"
              data-testid="attendance-exceptions-reset"
              onClick={onReset}
            >
              {t('attendanceExceptions.filters.reset')}
            </button>
          )}
        </div>
      </form>

      <div aria-busy={listing.kind === 'loading'}>
        {listing.kind === 'loading' && <p role="status">{t('attendanceExceptions.loading')}</p>}
        {listing.kind === 'failed' && (
          <div
            className="error-summary"
            role="alert"
            data-testid="attendance-exceptions-error"
            data-kind={listing.failure.kind}
          >
            <p>{t(listing.failure.messageKey)}</p>
            {listing.failure.correlationId && (
              <p className="muted">
                {t('employees.reference', { id: listing.failure.correlationId })}
              </p>
            )}
            {listing.failure.kind !== 'linkRequired' &&
              listing.failure.kind !== 'forbidden' &&
              listing.failure.kind !== 'validation' &&
              listing.failure.kind !== 'scopeNotFound' && (
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setListing({ kind: 'loading' });
                    setApplied({ ...applied });
                  }}
                >
                  {t('attendanceExceptions.retry')}
                </button>
              )}
          </div>
        )}
        {listing.kind === 'ready' && listing.items.length === 0 && (
          <p data-testid="attendance-exceptions-empty">
            {t(
              filtered
                ? 'attendanceExceptions.emptyFiltered'
                : `attendanceExceptions.empty.${view}`,
            )}
          </p>
        )}
        {listing.kind === 'ready' && listing.items.length > 0 && (
          <ul
            className="shift-list"
            ref={listRef}
            aria-label={t(`attendanceExceptions.label.${view}`)}
          >
            {listing.items.map((row) => {
              const start = splitLocal(row.start);
              const end = splitLocal(row.end);
              const label = language === 'fr' ? row.labels.fr : row.labels.en;
              const day = formatDate(language, start.date);
              const zone = row.siteTimezone;
              const name = exceptionEmployeeName(row);
              return (
                <li
                  key={exceptionKey(row)}
                  className="card"
                  tabIndex={-1}
                  data-testid="attendance-exception"
                  data-type={row.type}
                >
                  <Card className="shift-list__date" data-testid="attendance-exception-heading">
                    {name || day}
                  </Card>
                  <p>
                    <StatusBadge tone={TONES[row.type]} testId="attendance-exception-type">
                      {t(`attendanceExceptions.type.${row.type}`)}
                    </StatusBadge>
                  </p>
                  <p lang={language} data-testid="attendance-exception-label">
                    <strong>{label}</strong>
                  </p>
                  <dl className="decision-summary">
                    {name && (
                      <>
                        <dt>{t('scheduleSubstitutions.shift.date')}</dt>
                        <dd>{day}</dd>
                      </>
                    )}
                    <dt>{t('scheduleSubstitutions.shift.site')}</dt>
                    <dd data-testid="attendance-exception-site">
                      {row.site.name ?? t('workSchedules.siteUnknown')}
                    </dd>
                    <dt>{t('attendance.scheduled')}</dt>
                    <dd data-testid="attendance-exception-scheduled">
                      {t('mySchedule.time', {
                        start: formatTime(language, start.time),
                        end: formatTime(language, end.time),
                      })}
                      {row.overnight && (
                        <> {t('mySchedule.overnight', { date: formatDate(language, end.date) })}</>
                      )}
                    </dd>
                    <dt>{t('attendance.checkedIn')}</dt>
                    <dd data-testid="attendance-exception-checked-in">
                      {row.checkedInAt
                        ? formatInstant(language, row.checkedInAt, zone)
                        : t('attendanceExceptions.notRecorded')}
                    </dd>
                    <dt>{t('attendance.checkedOut')}</dt>
                    <dd data-testid="attendance-exception-checked-out">
                      {row.checkedOutAt
                        ? formatInstant(language, row.checkedOutAt, zone)
                        : t('attendanceExceptions.notRecorded')}
                    </dd>
                    {row.varianceMinutes !== null && (
                      <>
                        <dt>{t('attendanceExceptions.difference')}</dt>
                        <dd data-testid="attendance-exception-variance">
                          {t(`attendanceExceptions.variance.${row.type}`, {
                            minutes: row.varianceMinutes,
                          })}
                        </dd>
                      </>
                    )}
                  </dl>
                  <p className="muted" data-testid="attendance-exception-timezone">
                    {t('attendance.siteTime', { zone })}
                  </p>
                </li>
              );
            })}
          </ul>
        )}
        {listing.kind === 'ready' && listing.nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={loadingMore}
              aria-busy={loadingMore ? true : undefined}
              data-testid="attendance-exceptions-more"
              onClick={() => {
                if (listing.nextCursor) void more(listing.nextCursor);
              }}
            >
              {loadingMore ? t('attendanceExceptions.loadingMore') : t('attendanceExceptions.more')}
            </button>
          </p>
        )}
      </div>
    </Wrapper>
  );
}

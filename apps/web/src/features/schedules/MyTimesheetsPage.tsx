import type { MyTimesheet, MyTimesheetItem } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { TimesheetRows } from './TimesheetRows';
import { TotalsList } from './TimesheetsPage';
import {
  TIMESHEET_NETWORK_FAILURE,
  durationParts,
  timesheetFailureOf,
  type TimesheetFailure,
} from './timesheets';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: TimesheetFailure }
  | { kind: 'ready'; items: MyTimesheetItem[]; nextCursor: string | null };

type Opened =
  | { kind: 'loading'; id: string }
  | { kind: 'failed'; id: string; failure: TimesheetFailure }
  | { kind: 'ready'; id: string; timesheet: MyTimesheet };

/**
 * MVP-070A: « Mes feuilles de temps » / "My timesheets" under My space: only the caller's own
 * weekly drafts, newest week first, each with its site, week, « Brouillon » / "Draft", revision,
 * counts and worked time; a draft opens its shifts as the server stored them. A neutral notice says
 * these are drafts, not submitted, approved or a pay statement. No other employee, cost center,
 * internal identifier or administrator appears; nothing is kept in browser storage or in a URL.
 */
export function MyTimesheetsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const listRef = useRef<HTMLUListElement>(null);
  const [opened, setOpened] = useState<Opened | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/me/timesheets', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor ?? null }
          : { kind: 'failed', failure: timesheetFailureOf('self', response, error) };
      } catch {
        return { kind: 'failed', failure: TIMESHEET_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    setListing({ kind: 'loading' });
    setListing(await fetchPage(null));
  }, [fetchPage]);

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setListing(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    const before = listing.kind === 'ready' ? listing.items.length : 0;
    try {
      const next = await fetchPage(cursor);
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

  const open = async (id: string) => {
    setOpened({ kind: 'loading', id });
    try {
      const { data, error, response } = await core.GET('/me/timesheets/{timesheetId}', {
        params: { path: { timesheetId: id } },
        cache: 'no-store',
      });
      setOpened(
        data
          ? { kind: 'ready', id, timesheet: data }
          : { kind: 'failed', id, failure: timesheetFailureOf('self', response, error) },
      );
    } catch {
      setOpened({ kind: 'failed', id, failure: TIMESHEET_NETWORK_FAILURE });
    }
    requestAnimationFrame(() => detailHeading.current?.focus());
  };

  const week = (from: string, to: string) => ({
    from: formatDate(i18n.language, from),
    to: formatDate(i18n.language, to),
  });

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-timesheets">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('myTimesheets.title')}
        description={t('myTimesheets.description')}
      />
      <p className="card" data-testid="my-timesheets-notice">
        {t('myTimesheets.notice')}
      </p>
      {listing.kind === 'loading' && <p role="status">{t('myTimesheets.loading')}</p>}
      {listing.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="my-timesheets-error">
          <p>{t(listing.failure.messageKey)}</p>
          {listing.failure.correlationId && (
            <p className="muted">
              {t('employees.reference', { id: listing.failure.correlationId })}
            </p>
          )}
          {listing.failure.kind !== 'linkRequired' && listing.failure.kind !== 'forbidden' && (
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void reload()}
            >
              {t('myTimesheets.retry')}
            </button>
          )}
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length === 0 && (
        <div className="card" data-testid="my-timesheets-empty">
          <p>{t('myTimesheets.empty')}</p>
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length > 0 && (
        <ul className="shift-list" ref={listRef} aria-label={t('myTimesheets.label')}>
          {listing.items.map((item) => {
            const range = week(item.period.weekStart, item.period.lastDay);
            return (
              <li key={item.timesheetId} className="card" tabIndex={-1} data-testid="my-timesheet">
                <h2 className="shift-list__date">{t('myTimesheets.week', range)}</h2>
                <p>
                  <StatusBadge tone="neutral" testId="my-timesheet-status">
                    {t('myTimesheets.status')}
                  </StatusBadge>{' '}
                  <span data-testid="my-timesheet-revision">
                    {t('myTimesheets.revision', { revision: item.revision })}
                  </span>
                </p>
                <dl className="decision-summary">
                  <dt>{t('myTimesheets.site')}</dt>
                  <dd data-testid="my-timesheet-site">
                    {item.period.siteName ?? t('workSchedules.siteUnknown')}
                  </dd>
                  <dt>{t('timesheets.totals.worked')}</dt>
                  <dd data-testid="my-timesheet-worked">
                    {t('timesheets.duration', durationParts(item.totals.workedMinutes))}
                  </dd>
                  <dt>{t('timesheets.totals.ready')}</dt>
                  <dd>{item.totals.readyRows}</dd>
                  <dt>{t('timesheets.totals.blocked')}</dt>
                  <dd data-testid="my-timesheet-blocked">{item.totals.blockedRows}</dd>
                </dl>
                <p>
                  <button
                    type="button"
                    className="button button--secondary"
                    aria-label={t('myTimesheets.viewFor', { from: range.from })}
                    data-testid="my-timesheet-view"
                    onClick={(event) => {
                      opener.current = event.currentTarget;
                      void open(item.timesheetId);
                    }}
                  >
                    {t('myTimesheets.view')}
                  </button>
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
            onClick={() => {
              if (listing.nextCursor) void more(listing.nextCursor);
            }}
          >
            {loadingMore ? t('myTimesheets.loadingMore') : t('myTimesheets.more')}
          </button>
        </p>
      )}
      {opened && (
        <section
          className="card"
          aria-labelledby={opened.kind === 'ready' ? `${ids}-detail-title` : undefined}
          aria-label={opened.kind === 'ready' ? undefined : t('myTimesheets.rowsLabel')}
          data-testid="my-timesheet-detail"
        >
          {opened.kind === 'loading' && <p role="status">{t('myTimesheets.detailLoading')}</p>}
          {opened.kind === 'failed' && (
            <div className="error-summary" role="alert" data-testid="my-timesheet-detail-error">
              <p>{t(opened.failure.messageKey)}</p>
            </div>
          )}
          {opened.kind === 'ready' && (
            <>
              <h2 id={`${ids}-detail-title`} ref={detailHeading} tabIndex={-1}>
                {t(
                  'myTimesheets.detailTitle',
                  week(opened.timesheet.period.weekStart, opened.timesheet.period.lastDay),
                )}
              </h2>
              <p className="muted">
                {t('timesheets.zone', { zone: opened.timesheet.period.timezone })}
              </p>
              <TotalsList totals={opened.timesheet.totals} testId="my-timesheet-totals" />
              <TimesheetRows
                rows={opened.timesheet.rows}
                zone={opened.timesheet.period.timezone}
                label={t('myTimesheets.rowsLabel')}
                testId="my-timesheet-rows"
              />
            </>
          )}
          <p>
            <button
              type="button"
              className="button button--secondary"
              data-testid="my-timesheet-close"
              onClick={() => {
                setOpened(null);
                requestAnimationFrame(() => {
                  if (opener.current?.isConnected) opener.current.focus();
                  else document.getElementById(`${ids}-title`)?.focus();
                });
              }}
            >
              {t('myTimesheets.close')}
            </button>
          </p>
        </section>
      )}
    </section>
  );
}

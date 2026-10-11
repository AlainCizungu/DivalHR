import type { MyWorkShift } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import {
  SCHEDULE_NETWORK_FAILURE,
  formatTime,
  scheduleFailureOf,
  splitLocal,
  type ScheduleFailure,
} from './workSchedules';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ScheduleFailure }
  | { kind: 'ready'; items: MyWorkShift[]; nextCursor: string | null };

/**
 * MVP-050A: the employee's own published shifts (« Mon horaire » under My space), in progress and
 * upcoming first (the server's instant-based default view, R100-2; nothing ended):
 * the work in the current language, the site, the date and local start and end times, with an
 * overnight indicator. Never another employee, an uncovered slot or who published it.
 */
export function MySchedulePage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const listRef = useRef<HTMLUListElement>(null);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/me/work-schedule', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: scheduleFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: SCHEDULE_NETWORK_FAILURE };
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
      // Focus moves to the first newly loaded shift.
      requestAnimationFrame(() => {
        const item = listRef.current?.children[before] as HTMLElement | undefined;
        item?.focus();
      });
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-schedule">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('mySchedule.title')}
        description={t('mySchedule.description')}
      />
      {listing.kind === 'loading' && <p role="status">{t('mySchedule.loading')}</p>}
      {listing.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="my-schedule-error">
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
              {t('mySchedule.retry')}
            </button>
          )}
        </div>
      )}
      {listing.kind === 'ready' && (
        <p className="muted" data-testid="my-schedule-scope">
          {t('mySchedule.current')}
        </p>
      )}
      {listing.kind === 'ready' && listing.items.length === 0 && (
        <div className="card" data-testid="my-schedule-empty">
          <p>{t('mySchedule.empty')}</p>
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length > 0 && (
        <ul className="shift-list" ref={listRef} aria-label={t('mySchedule.label')}>
          {listing.items.map((shift) => {
            const start = splitLocal(shift.start);
            const end = splitLocal(shift.end);
            const label = language === 'fr' ? shift.labels.fr : shift.labels.en;
            return (
              <li key={shift.id} className="card" tabIndex={-1} data-testid="my-shift">
                <h2 className="shift-list__date">{formatDate(language, start.date)}</h2>
                <p className="shift-list__time" data-testid="my-shift-time">
                  {t('mySchedule.time', {
                    start: formatTime(language, start.time),
                    end: formatTime(language, end.time),
                  })}{' '}
                  {shift.overnight && (
                    <StatusBadge tone="info" testId="my-shift-overnight">
                      {t('mySchedule.overnight', { date: formatDate(language, end.date) })}
                    </StatusBadge>
                  )}
                </p>
                <p lang={language} data-testid="my-shift-label">
                  <strong>{label}</strong>
                </p>
                <p className="muted" data-testid="my-shift-site">
                  {shift.site.name
                    ? t('mySchedule.site', { name: shift.site.name })
                    : t('workSchedules.siteUnknown')}
                </p>
                <p className="muted" data-testid="my-shift-timezone">
                  {t('mySchedule.timezone', { zone: shift.siteTimezone })}
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
            {loadingMore ? t('mySchedule.loadingMore') : t('mySchedule.more')}
          </button>
        </p>
      )}
    </section>
  );
}

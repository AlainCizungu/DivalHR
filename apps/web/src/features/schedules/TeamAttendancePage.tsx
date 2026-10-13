import type { SupervisorAttendanceReceipt, TeamAttendanceAction } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatDate } from '../people/history';
import { AttendanceExceptions } from './AttendanceExceptions';
import { TeamAttendanceDialog } from './TeamAttendanceDialog';
import { formatInstant } from './attendance';
import {
  TEAM_NETWORK_FAILURE,
  employeeName,
  kindOf,
  teamFailureOf,
  type TeamAttendanceFailure,
} from './teamAttendance';
import { formatTime, splitLocal } from './workSchedules';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: TeamAttendanceFailure }
  | { kind: 'ready'; items: TeamAttendanceAction[]; nextCursor: string | null; asOf: string };

/**
 * MVP-060B: « Présence de l'équipe » / "Team attendance" under My space: the shifts of the
 * caller's direct reports that need a check-in or a check-out now, earliest deadline first. Each
 * card names the employee and number, the work, the site and the scheduled local times in the
 * site's time zone, the required action and its deadline, and the recorded check-in of an open
 * record. « Enregistrer l'arrivée » / "Record check-in" and « Enregistrer le départ » / "Record
 * check-out" open the confirmation with the reason; after a recorded or refused command the queue
 * is read again from the server. Cards stack on narrow screens; nothing is kept in browser storage
 * or put in a URL. The server decides who may act, on every request. MVP-060C: « Anomalies » /
 * "Exceptions" lists the direct reports' factual exceptions below the queue, read-only.
 */
export function TeamAttendancePage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const listRef = useRef<HTMLUListElement>(null);
  const [target, setTarget] = useState<TeamAttendanceAction | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const opener = useRef<HTMLButtonElement | null>(null);
  const actionKey = useIdempotencyKey();

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/me/team-attendance/actions', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? {
              kind: 'ready',
              items: data.items,
              nextCursor: data.nextCursor,
              asOf: data.asOfInstant,
            }
          : { kind: 'failed', failure: teamFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: TEAM_NETWORK_FAILURE };
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

  const recorded = (receipt: SupervisorAttendanceReceipt) => {
    actionKey.reset();
    const zone = target?.siteTimezone ?? 'UTC';
    const name = target ? employeeName(target) : '';
    setAnnouncement(
      t(
        receipt.status === 'COMPLETED'
          ? 'teamAttendance.checkOut.done'
          : 'teamAttendance.checkIn.done',
        {
          name,
          time: formatInstant(language, receipt.checkedOutAt ?? receipt.checkedInAt, zone),
        },
      ),
    );
    void reload();
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="team-attendance">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('teamAttendance.title')}
        description={t('teamAttendance.description')}
        actions={
          <button
            type="button"
            className="button button--secondary"
            disabled={listing.kind === 'loading'}
            data-testid="team-attendance-refresh"
            onClick={() => {
              setAnnouncement('');
              void reload();
            }}
          >
            {t('teamAttendance.refresh')}
          </button>
        }
      />
      <p role="status" className="visually-hidden" data-testid="team-attendance-announcer">
        {announcement}
      </p>
      {listing.kind === 'loading' && <p role="status">{t('teamAttendance.loading')}</p>}
      {listing.kind === 'failed' && (
        <div
          className="error-summary"
          role="alert"
          data-testid="team-attendance-error"
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
            listing.failure.kind !== 'notManager' && (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void reload()}
              >
                {t('teamAttendance.retry')}
              </button>
            )}
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length === 0 && (
        <div className="card" data-testid="team-attendance-empty">
          <p>{t('teamAttendance.empty')}</p>
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length > 0 && (
        <ul className="shift-list" ref={listRef} aria-label={t('teamAttendance.label')}>
          {listing.items.map((action) => {
            const start = splitLocal(action.start);
            const end = splitLocal(action.end);
            const label = language === 'fr' ? action.labels.fr : action.labels.en;
            const day = formatDate(language, start.date);
            const zone = action.siteTimezone;
            const name = employeeName(action);
            const kind = kindOf(action);
            return (
              <li
                key={`${action.requiredAction}-${action.slotId}`}
                className="card"
                tabIndex={-1}
                data-testid="team-attendance-action"
                data-action={action.requiredAction}
              >
                <h2 className="shift-list__date" data-testid="team-attendance-employee">
                  {name}
                </h2>
                <p>
                  <StatusBadge
                    tone={kind === 'checkOut' ? 'info' : 'warning'}
                    testId="team-attendance-required"
                  >
                    {t(`teamAttendance.required.${action.requiredAction}`)}
                  </StatusBadge>
                </p>
                <p lang={language} data-testid="team-attendance-label">
                  <strong>{label}</strong>
                </p>
                <dl className="decision-summary">
                  <dt>{t('scheduleSubstitutions.shift.date')}</dt>
                  <dd>{day}</dd>
                  <dt>{t('scheduleSubstitutions.shift.site')}</dt>
                  <dd data-testid="team-attendance-site">
                    {action.site.name ?? t('workSchedules.siteUnknown')}
                  </dd>
                  <dt>{t('attendance.scheduled')}</dt>
                  <dd data-testid="team-attendance-scheduled">
                    {t('mySchedule.time', {
                      start: formatTime(language, start.time),
                      end: formatTime(language, end.time),
                    })}
                    {action.overnight && (
                      <> {t('mySchedule.overnight', { date: formatDate(language, end.date) })}</>
                    )}
                  </dd>
                  <dt>{t('teamAttendance.deadline')}</dt>
                  <dd data-testid="team-attendance-deadline">
                    {formatInstant(language, action.actionDeadline, zone)}
                  </dd>
                  {action.checkedInAt && (
                    <>
                      <dt>{t('attendance.checkedIn')}</dt>
                      <dd data-testid="team-attendance-checked-in">
                        {formatInstant(language, action.checkedInAt, zone)}
                      </dd>
                    </>
                  )}
                </dl>
                <p className="muted" data-testid="team-attendance-timezone">
                  {t('attendance.siteTime', { zone })}
                </p>
                <p>
                  <button
                    type="button"
                    className="button"
                    disabled={target !== null}
                    aria-label={t(`teamAttendance.${kind}.actionFor`, {
                      name,
                      work: label,
                      date: day,
                    })}
                    data-testid={`team-${kind === 'checkOut' ? 'check-out' : 'check-in'}`}
                    onClick={(event) => {
                      opener.current = event.currentTarget;
                      setAnnouncement('');
                      setTarget(action);
                    }}
                  >
                    {t(`teamAttendance.${kind}.action`)}
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
            {loadingMore ? t('teamAttendance.loadingMore') : t('teamAttendance.more')}
          </button>
        </p>
      )}
      <TeamAttendanceDialog
        key={target ? `${target.requiredAction}-${target.slotId}` : 'none'}
        action={target}
        keyFor={actionKey.keyFor}
        onRecorded={recorded}
        onSettled={() => void reload()}
        onClosed={() => {
          setTarget(null);
          requestAnimationFrame(() => {
            if (opener.current?.isConnected) opener.current.focus();
            else document.getElementById(`${ids}-title`)?.focus();
          });
        }}
      />
      <AttendanceExceptions view="team" level={2} />
    </section>
  );
}

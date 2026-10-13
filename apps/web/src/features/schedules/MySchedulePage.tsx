import type {
  AttendanceReceipt,
  MyWorkScheduleSubstitution,
  MyWorkShift,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatDate } from '../people/history';
import { AttendanceDialog } from './AttendanceDialog';
import { MySubstitutionHistory, type History } from './MySubstitutionHistory';
import { SubstitutionRequestDialog } from './SubstitutionRequestDialog';
import { AssistedNote } from './AssistedNote';
import { CorrectedNote } from './CorrectedNote';
import { useOfflineAttendance } from './offline/OfflineAttendanceProvider';
import { formatInstant, receiptMessageKey, type AttendanceAction } from './attendance';
import { assistedOf } from './teamAttendance';
import {
  SUBSTITUTION_NETWORK_FAILURE,
  pendingSlotsOf,
  requestable,
  substitutionFailureOf,
} from './substitutions';
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
 *
 * MVP-051: the shifts are the employee's operational ones: a shift given to them by an approved
 * substitution carries a « Remplacement » / "Substitution" badge (never the original employee or a
 * reason), and a shift they gave away disappears. A future shift published to them offers « Demander
 * un remplacement » / "Request a substitute"; « Mes demandes de remplacement » / "My substitution
 * requests" lists their requests with each state and decision.
 *
 * MVP-060A: each shift shows the employee's own attendance (not checked in, checked in at a time,
 * or checked in and out) and, only while the server says it is possible, « Pointer l’arrivée » /
 * "Check in" or « Pointer le départ » / "Check out", each after an explicit confirmation; the
 * times shown are the server's, in the site's local time. After a command the list is reloaded
 * from the server.
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
  const [history, setHistory] = useState<History>({ kind: 'loading' });
  const [historyMore, setHistoryMore] = useState(false);
  const historyPaging = useRef(false);
  const [target, setTarget] = useState<MyWorkShift | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const opener = useRef<HTMLButtonElement | null>(null);
  const historyHeading = `${ids}-substitutions`;
  const requestKey = useIdempotencyKey();
  const attendanceKey = useIdempotencyKey();
  const offline = useOfflineAttendance();
  const appliedVersion = offline?.appliedVersion ?? 0;
  const [queuedNotice, setQueuedNotice] = useState(false);
  const [attendanceTarget, setAttendanceTarget] = useState<{
    action: AttendanceAction;
    shift: MyWorkShift;
  } | null>(null);

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

  const fetchHistory = useCallback(
    async (cursor: string | null): Promise<History> => {
      try {
        const { data, error, response } = await core.GET('/me/work-schedule-substitutions', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: substitutionFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: SUBSTITUTION_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    setListing({ kind: 'loading' });
    setListing(await fetchPage(null));
  }, [fetchPage]);

  const reloadHistory = useCallback(async () => {
    setHistory({ kind: 'loading' });
    setHistory(await fetchHistory(null));
  }, [fetchHistory]);

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setListing(next);
    });
    void fetchHistory(null).then((next) => {
      if (active) setHistory(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage, fetchHistory]);

  // MVP-061B: after saved attendance was synchronized, the shifts show the server's state.
  useEffect(() => {
    if (appliedVersion === 0) return;
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setListing(next);
    });
    return () => {
      active = false;
    };
  }, [appliedVersion, fetchPage]);

  const moreHistory = async (cursor: string) => {
    if (historyPaging.current) return;
    historyPaging.current = true;
    setHistoryMore(true);
    try {
      const next = await fetchHistory(cursor);
      setHistory((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      historyPaging.current = false;
      setHistoryMore(false);
    }
  };

  const pendingSlots = useMemo(
    () => (history.kind === 'ready' ? pendingSlotsOf(history.items) : new Set<string>()),
    [history],
  );

  const requested = (request: MyWorkScheduleSubstitution) => {
    requestKey.reset();
    setHistory((previous) =>
      previous.kind === 'ready'
        ? { ...previous, items: [request, ...previous.items.filter((i) => i.id !== request.id)] }
        : previous,
    );
    setAnnouncement(t('scheduleSubstitutions.request.sent'));
  };

  const recorded = (receipt: AttendanceReceipt) => {
    attendanceKey.reset();
    const zone = attendanceTarget?.shift.siteTimezone ?? 'UTC';
    const time = receipt.checkedOutAt ?? receipt.checkedInAt;
    setAnnouncement(t(receiptMessageKey(receipt), { time: formatInstant(language, time, zone) }));
    void reload();
  };

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
      <p role="status" className="visually-hidden" data-testid="my-schedule-announcer">
        {announcement}
      </p>
      <p role="status" data-testid="my-schedule-offline-notice">
        {queuedNotice ? t('offlineAttendance.queued') : ''}
      </p>
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
            const day = formatDate(language, start.date);
            return (
              <li
                key={shift.id}
                className="card"
                tabIndex={-1}
                data-testid="my-shift"
                data-source={shift.assignmentSource}
              >
                <h2 className="shift-list__date">{day}</h2>
                {shift.assignmentSource === 'SUBSTITUTION' && (
                  <p>
                    <StatusBadge tone="info" testId="my-shift-substitution">
                      {t('scheduleSubstitutions.badge')}
                    </StatusBadge>
                  </p>
                )}
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
                <ShiftAttendance
                  shift={shift}
                  day={day}
                  label={label}
                  busy={attendanceTarget !== null}
                  onAction={(action, button) => {
                    opener.current = button;
                    setAnnouncement('');
                    setQueuedNotice(false);
                    setAttendanceTarget({ action, shift });
                  }}
                />
                {pendingSlots.has(shift.id) && (
                  <p>
                    <StatusBadge tone="info" testId="my-shift-pending">
                      {t('scheduleSubstitutions.pendingBadge')}
                    </StatusBadge>
                  </p>
                )}
                {history.kind === 'ready' && requestable(shift, pendingSlots) && (
                  <p>
                    <button
                      type="button"
                      className="button button--secondary"
                      disabled={target !== null}
                      aria-label={t('scheduleSubstitutions.request.actionFor', {
                        date: day,
                        work: label,
                      })}
                      data-testid="request-substitute"
                      onClick={(event) => {
                        opener.current = event.currentTarget;
                        setAnnouncement('');
                        setTarget(shift);
                      }}
                    >
                      {t('scheduleSubstitutions.request.action')}
                    </button>
                  </p>
                )}
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
      <MySubstitutionHistory
        headingId={historyHeading}
        history={history}
        loadingMore={historyMore}
        onMore={(cursor) => void moreHistory(cursor)}
        onRetry={() => void reloadHistory()}
      />
      <AttendanceDialog
        key={
          attendanceTarget
            ? `attendance-${attendanceTarget.action.kind}-${attendanceTarget.shift.id}`
            : 'attendance-none'
        }
        action={attendanceTarget?.action ?? null}
        shift={
          attendanceTarget
            ? {
                start: attendanceTarget.shift.start,
                end: attendanceTarget.shift.end,
                labels: attendanceTarget.shift.labels,
                siteName: attendanceTarget.shift.site.name,
                siteTimezone: attendanceTarget.shift.siteTimezone,
                checkedInAt: attendanceTarget.shift.attendance.checkedInAt,
              }
            : null
        }
        keyFor={attendanceKey.keyFor}
        onRecorded={recorded}
        onSettled={() => void reload()}
        onQueued={() => {
          attendanceKey.reset();
          setQueuedNotice(true);
        }}
        onClosed={() => {
          setAttendanceTarget(null);
          requestAnimationFrame(() => {
            if (opener.current?.isConnected) opener.current.focus();
            else document.getElementById(`${ids}-title`)?.focus();
          });
        }}
      />
      <SubstitutionRequestDialog
        key={target?.id ?? 'none'}
        shift={target}
        keyFor={requestKey.keyFor}
        onRequested={requested}
        onSettled={() => {
          void reload();
          void reloadHistory();
        }}
        onClosed={() => {
          setTarget(null);
          requestAnimationFrame(() => {
            if (opener.current?.isConnected) opener.current.focus();
            else document.getElementById(historyHeading)?.focus();
          });
        }}
      />
    </section>
  );
}

/**
 * MVP-060A: the employee's attendance on one shift: its status, the recorded server times in the
 * site's local time and the one action the server says is possible now, if any. MVP-060B: a time
 * a supervisor recorded is marked as such, with the reason (never the supervisor).
 */
function ShiftAttendance({
  shift,
  day,
  label,
  busy,
  onAction,
}: {
  shift: MyWorkShift;
  day: string;
  label: string;
  busy: boolean;
  onAction: (action: AttendanceAction, opener: HTMLButtonElement) => void;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const attendance = shift.attendance;
  const at = (instant: string) => formatInstant(language, instant, shift.siteTimezone);
  const offline = useOfflineAttendance();
  const queuedIn = offline?.queuedFor(shift.id, 'CHECK_IN') ?? false;
  const queuedOut = offline?.queuedFor(shift.id, 'CHECK_OUT') ?? false;
  return (
    <div
      className="shift-attendance"
      data-testid="my-shift-attendance"
      data-status={attendance.status}
    >
      <p>
        <StatusBadge
          tone={attendance.status === 'NOT_RECORDED' ? 'neutral' : 'success'}
          testId="my-shift-attendance-status"
        >
          {t(`attendance.shiftStatus.${attendance.status}`)}
        </StatusBadge>
      </p>
      <CorrectedNote
        correctedAt={attendance.correctedAt}
        zone={shift.siteTimezone}
        testId="my-shift-corrected"
      />
      {attendance.checkedInAt && (
        <p data-testid="my-shift-checked-in">
          {t('attendance.shiftCheckedIn', { time: at(attendance.checkedInAt) })}
        </p>
      )}
      <AssistedNote
        event={assistedOf(
          attendance.checkInRecordingMethod,
          attendance.checkInSupervisorReasonLocale,
          attendance.checkInSupervisorReason,
        )}
        testId="my-shift-check-in-assisted"
      />
      {attendance.checkedOutAt && (
        <p data-testid="my-shift-checked-out">
          {t('attendance.shiftCheckedOut', { time: at(attendance.checkedOutAt) })}
        </p>
      )}
      <AssistedNote
        event={assistedOf(
          attendance.checkOutRecordingMethod,
          attendance.checkOutSupervisorReasonLocale,
          attendance.checkOutSupervisorReason,
        )}
        testId="my-shift-check-out-assisted"
      />
      {(queuedIn || queuedOut) && (
        <p>
          <StatusBadge tone="info" testId="my-shift-offline-queued">
            {t('offlineAttendance.queuedBadge', {
              type: t(`offlineAttendance.type.${queuedIn ? 'CHECK_IN' : 'CHECK_OUT'}`),
            })}
          </StatusBadge>
        </p>
      )}
      {attendance.checkInAvailable && !queuedIn && (
        <p>
          <button
            type="button"
            className="button"
            disabled={busy}
            aria-label={t('attendance.checkIn.actionFor', { work: label, date: day })}
            data-testid="check-in"
            onClick={(event) => {
              onAction({ kind: 'checkIn', slotId: shift.id }, event.currentTarget);
            }}
          >
            {t('attendance.checkIn.action')}
          </button>
        </p>
      )}
      {attendance.checkOutAvailable && attendance.attendanceRecordId && !queuedOut && (
        <p>
          <button
            type="button"
            className="button"
            disabled={busy}
            aria-label={t('attendance.checkOut.actionFor', { work: label, date: day })}
            data-testid="check-out"
            onClick={(event) => {
              if (attendance.attendanceRecordId) {
                onAction(
                  {
                    kind: 'checkOut',
                    recordId: attendance.attendanceRecordId,
                    slotId: shift.id,
                  },
                  event.currentTarget,
                );
              }
            }}
          >
            {t('attendance.checkOut.action')}
          </button>
        </p>
      )}
      {attendance.status === 'NOT_RECORDED' && !attendance.checkInAvailable && (
        <p className="muted" data-testid="my-shift-check-in-not-open">
          {t('attendance.checkInNotOpen')}
        </p>
      )}
    </div>
  );
}

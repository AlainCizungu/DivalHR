import type { AttendanceReceipt, MyAttendanceRecord } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatDate } from '../people/history';
import { AssistedNote } from './AssistedNote';
import { CorrectedNote } from './CorrectedNote';
import { AttendanceExceptions } from './AttendanceExceptions';
import { AttendanceDialog } from './AttendanceDialog';
import { OfflineAttendancePanel } from './offline/OfflineAttendancePanel';
import { useOfflineAttendance } from './offline/OfflineAttendanceProvider';
import {
  ATTENDANCE_NETWORK_FAILURE,
  attendanceFailureOf,
  formatInstant,
  receiptMessageKey,
  type AttendanceFailure,
} from './attendance';
import { assistedOf } from './teamAttendance';
import { formatTime, splitLocal } from './workSchedules';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: AttendanceFailure }
  | { kind: 'ready'; items: MyAttendanceRecord[]; nextCursor: string | null };

/**
 * MVP-060A: « Ma présence » / "My attendance" under My space: only the caller's own records,
 * newest shift first: the work in the current language, the site, the scheduled local times and
 * time zone, the server-recorded check-in and check-out (in the site's local time) and « En cours »
 * / "Open" or « Terminée » / "Completed". An open record still inside its check-out window offers
 * « Pointer le départ » / "Check out" after an explicit confirmation (the shift may have left My
 * schedule at its scheduled end). Cards stack on narrow screens; nothing is kept in browser
 * storage or put in a URL. MVP-060B: a check-in or check-out a supervisor recorded is marked
 * « Enregistré par un responsable » / "Recorded by a supervisor" with the reason, never the
 * supervisor. MVP-060C: « Anomalies de présence » / "Attendance exceptions" lists the caller's
 * own factual exceptions below the records, read-only.
 */
export function MyAttendancePage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const listRef = useRef<HTMLUListElement>(null);
  const [target, setTarget] = useState<MyAttendanceRecord | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const opener = useRef<HTMLButtonElement | null>(null);
  const actionKey = useIdempotencyKey();
  const offline = useOfflineAttendance();
  const appliedVersion = offline?.appliedVersion ?? 0;
  const [queuedNotice, setQueuedNotice] = useState(false);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/me/attendance', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: attendanceFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: ATTENDANCE_NETWORK_FAILURE };
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

  // MVP-061B: after saved attendance was synchronized, the records show the server's state.
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

  const recorded = (receipt: AttendanceReceipt) => {
    actionKey.reset();
    const zone = target?.shift.siteTimezone ?? 'UTC';
    setAnnouncement(
      t(receiptMessageKey(receipt), {
        time: formatInstant(language, receipt.checkedOutAt ?? receipt.checkedInAt, zone),
      }),
    );
    void reload();
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-attendance">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('attendance.title')}
        description={t('attendance.description')}
      />
      <p role="status" className="visually-hidden" data-testid="my-attendance-announcer">
        {announcement}
      </p>
      <p role="status" data-testid="my-attendance-offline-notice">
        {queuedNotice ? t('offlineAttendance.queued') : ''}
      </p>
      <OfflineAttendancePanel />
      {listing.kind === 'loading' && <p role="status">{t('attendance.loading')}</p>}
      {listing.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="my-attendance-error">
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
              {t('attendance.retry')}
            </button>
          )}
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length === 0 && (
        <div className="card" data-testid="my-attendance-empty">
          <p>{t('attendance.empty')}</p>
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length > 0 && (
        <ul className="shift-list" ref={listRef} aria-label={t('attendance.label')}>
          {listing.items.map((record) => {
            const start = splitLocal(record.shift.start);
            const end = splitLocal(record.shift.end);
            const label = language === 'fr' ? record.shift.labels.fr : record.shift.labels.en;
            const day = formatDate(language, start.date);
            const zone = record.shift.siteTimezone;
            const queuedOut = offline?.queuedFor(record.shift.slotId, 'CHECK_OUT') ?? false;
            return (
              <li
                key={record.id}
                className="card"
                tabIndex={-1}
                data-testid="my-attendance-record"
                data-status={record.status}
              >
                <h2 className="shift-list__date">{day}</h2>
                <p>
                  <StatusBadge
                    tone={record.status === 'COMPLETED' ? 'success' : 'info'}
                    testId="my-attendance-status"
                  >
                    {t(`attendance.status.${record.status}`)}
                  </StatusBadge>
                </p>
                <p lang={language} data-testid="my-attendance-label">
                  <strong>{label}</strong>
                </p>
                <CorrectedNote
                  correctedAt={record.correctedAt}
                  zone={zone}
                  testId="my-attendance-corrected"
                />
                <dl className="decision-summary">
                  <dt>{t('scheduleSubstitutions.shift.site')}</dt>
                  <dd data-testid="my-attendance-site">
                    {record.shift.site.name ?? t('workSchedules.siteUnknown')}
                  </dd>
                  <dt>{t('attendance.scheduled')}</dt>
                  <dd data-testid="my-attendance-scheduled">
                    {t('mySchedule.time', {
                      start: formatTime(language, start.time),
                      end: formatTime(language, end.time),
                    })}
                    {record.shift.overnight && (
                      <> {t('mySchedule.overnight', { date: formatDate(language, end.date) })}</>
                    )}
                  </dd>
                  <dt>{t('attendance.checkedIn')}</dt>
                  <dd data-testid="my-attendance-checked-in">
                    {formatInstant(language, record.checkedInAt, zone)}
                    <AssistedNote
                      event={assistedOf(
                        record.checkInRecordingMethod,
                        record.checkInSupervisorReasonLocale,
                        record.checkInSupervisorReason,
                      )}
                      testId="my-attendance-check-in-assisted"
                    />
                  </dd>
                  <dt>{t('attendance.checkedOut')}</dt>
                  <dd data-testid="my-attendance-checked-out">
                    {record.checkedOutAt
                      ? formatInstant(language, record.checkedOutAt, zone)
                      : t('attendance.notCheckedOut')}
                    <AssistedNote
                      event={assistedOf(
                        record.checkOutRecordingMethod,
                        record.checkOutSupervisorReasonLocale,
                        record.checkOutSupervisorReason,
                      )}
                      testId="my-attendance-check-out-assisted"
                    />
                  </dd>
                </dl>
                <p className="muted" data-testid="my-attendance-timezone">
                  {t('attendance.siteTime', { zone })}
                </p>
                {queuedOut && (
                  <p>
                    <StatusBadge tone="info" testId="my-attendance-offline-queued">
                      {t('offlineAttendance.queuedBadge', {
                        type: t('offlineAttendance.type.CHECK_OUT'),
                      })}
                    </StatusBadge>
                  </p>
                )}
                {record.checkOutAvailable && !queuedOut && (
                  <p>
                    <button
                      type="button"
                      className="button"
                      disabled={target !== null}
                      aria-label={t('attendance.checkOut.actionFor', { work: label, date: day })}
                      data-testid="check-out"
                      onClick={(event) => {
                        opener.current = event.currentTarget;
                        setAnnouncement('');
                        setQueuedNotice(false);
                        setTarget(record);
                      }}
                    >
                      {t('attendance.checkOut.action')}
                    </button>
                  </p>
                )}
                {record.status === 'OPEN' && !record.checkOutAvailable && (
                  <p className="muted" data-testid="my-attendance-check-out-closed">
                    {t('attendance.checkOutClosed')}
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
            {loadingMore ? t('attendance.loadingMore') : t('attendance.more')}
          </button>
        </p>
      )}
      <AttendanceDialog
        key={target?.id ?? 'none'}
        action={
          target ? { kind: 'checkOut', recordId: target.id, slotId: target.shift.slotId } : null
        }
        shift={
          target
            ? {
                start: target.shift.start,
                end: target.shift.end,
                labels: target.shift.labels,
                siteName: target.shift.site.name,
                siteTimezone: target.shift.siteTimezone,
                checkedInAt: target.checkedInAt,
              }
            : null
        }
        keyFor={actionKey.keyFor}
        onRecorded={recorded}
        onSettled={() => void reload()}
        onQueued={() => {
          actionKey.reset();
          setQueuedNotice(true);
        }}
        onClosed={() => {
          setTarget(null);
          requestAnimationFrame(() => {
            if (opener.current?.isConnected) opener.current.focus();
            else document.getElementById(`${ids}-title`)?.focus();
          });
        }}
      />
      <AttendanceExceptions view="self" level={2} />
    </section>
  );
}

import { useEffect, useId, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../../ui/primitives';
import { useOfflineAttendance, type OfflineNotice } from './OfflineAttendanceProvider';
import type { QueueEntry } from './offlineQueue';

/** The time recorded on this device, in this device's own time zone (display only). */
export function formatDeviceTime(language: 'en' | 'fr', deviceOccurredAt: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(deviceOccurredAt),
  );
}

/** The sentences of a notice: counts and stable keys only. */
export function noticeText(t: TFunction, notice: OfflineNotice | null): string {
  if (!notice) return '';
  if (notice.kind === 'queued') return t('offlineAttendance.queued');
  if (notice.kind === 'discarded') return t('offlineAttendance.discarded');
  const { applied, attention, stopped } = notice.result;
  const parts: string[] = [];
  if (applied > 0) parts.push(t('offlineAttendance.synced', { count: applied }));
  if (attention > 0) parts.push(t('offlineAttendance.attention', { count: attention }));
  if (stopped) parts.push(t(`offlineAttendance.stopped.${stopped}`));
  return parts.join(' ');
}

/**
 * MVP-061B (B61B-6): « Présence hors connexion » / "Offline attendance" on My attendance: the
 * connection status in words, the current owner's saved events (type, the time recorded on this
 * device, « En attente de synchronisation » / "Pending sync" or « Action requise » / "Needs
 * attention" with a neutral reason), « Synchroniser maintenant » / "Sync now" while online, and
 * a confirmed discard. It never shows an identifier and never reconstructs a name, site or label
 * from the saved data.
 */
export function OfflineAttendancePanel() {
  const { t, i18n } = useTranslation();
  const offline = useOfflineAttendance();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [target, setTarget] = useState<QueueEntry | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (target && !element.open) {
      element.showModal();
      requestAnimationFrame(() => confirmRef.current?.focus());
    }
    if (!target && element.open) element.close();
  }, [target]);

  if (!offline) return null;
  const { entries, online, syncing, notice } = offline;
  const pending = entries.filter((entry) => entry.event?.state === 'PENDING').length;
  const describe = (entry: QueueEntry) =>
    entry.event
      ? t('offlineAttendance.discardFor', {
          type: t(`offlineAttendance.type.${entry.event.type}`),
          time: formatDeviceTime(language, entry.event.deviceOccurredAt),
        })
      : t('offlineAttendance.discardUnreadable');

  return (
    <section
      className="card offline-attendance"
      aria-labelledby={`${ids}-title`}
      data-testid="offline-attendance"
    >
      <h2 id={`${ids}-title`}>{t('offlineAttendance.title')}</h2>
      <p data-testid="offline-connection">
        <StatusBadge tone={online ? 'success' : 'warning'}>
          {t(online ? 'offlineAttendance.online' : 'offlineAttendance.offline')}
        </StatusBadge>
      </p>
      <p className="muted">{t('offlineAttendance.explanation')}</p>
      {offline.unsupported && (
        <p data-testid="offline-unavailable">{t('offlineAttendance.unavailable')}</p>
      )}
      {offline.available && (
        <p data-testid="offline-count">
          {entries.length === 0
            ? t('offlineAttendance.empty')
            : t('offlineAttendance.count', { count: entries.length })}
        </p>
      )}
      {entries.length > 0 && (
        <ul className="offline-attendance__list" aria-label={t('offlineAttendance.listLabel')}>
          {entries.map((entry) => {
            const event = entry.event;
            return (
              <li
                key={entry.key}
                className="offline-attendance__item"
                data-testid="offline-entry"
                data-state={event ? event.state : 'UNREADABLE'}
              >
                {event ? (
                  <>
                    <p>
                      <strong>{t(`offlineAttendance.type.${event.type}`)}</strong>{' '}
                      <StatusBadge
                        tone={event.state === 'PENDING' ? 'info' : 'warning'}
                        testId="offline-entry-state"
                      >
                        {t(`offlineAttendance.state.${event.state}`)}
                      </StatusBadge>
                    </p>
                    <p data-testid="offline-entry-time">
                      {t('offlineAttendance.recordedOnDevice', {
                        time: formatDeviceTime(language, event.deviceOccurredAt),
                      })}
                    </p>
                    {event.state === 'NEEDS_ATTENTION' && (
                      <p data-testid="offline-entry-reason">
                        {t(
                          event.reason && event.reason !== 'GENERIC'
                            ? `errors.${event.reason}`
                            : 'offlineAttendance.reason.generic',
                        )}
                      </p>
                    )}
                  </>
                ) : (
                  <p data-testid="offline-entry-unreadable">
                    <StatusBadge tone="warning" testId="offline-entry-state">
                      {t('offlineAttendance.state.UNREADABLE')}
                    </StatusBadge>{' '}
                    {t('offlineAttendance.unreadable')}
                  </p>
                )}
                {(event === null || event.state === 'NEEDS_ATTENTION') && (
                  <p>
                    <button
                      type="button"
                      className="button button--secondary"
                      aria-label={describe(entry)}
                      data-testid="offline-discard"
                      onClick={(click) => {
                        opener.current = click.currentTarget;
                        setTarget(entry);
                      }}
                    >
                      {t('offlineAttendance.discard')}
                    </button>
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {online && pending > 0 && (
        <p>
          <button
            type="button"
            className="button"
            disabled={syncing}
            aria-busy={syncing ? true : undefined}
            data-testid="offline-sync-now"
            onClick={() => {
              offline.syncNow();
            }}
          >
            {syncing ? t('offlineAttendance.syncing') : t('offlineAttendance.syncNow')}
          </button>
        </p>
      )}
      <p role="status" data-testid="offline-status">
        {noticeText(t, notice)}
      </p>
      <dialog
        ref={dialogRef}
        className="decision-dialog"
        aria-labelledby={`${ids}-discard`}
        data-testid="offline-discard-dialog"
        onClose={() => {
          setTarget(null);
          requestAnimationFrame(() => {
            if (opener.current?.isConnected) opener.current.focus();
            else document.getElementById(`${ids}-title`)?.focus();
          });
        }}
        onCancel={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        {target && (
          <div className="decision-dialog__panel">
            <h2 id={`${ids}-discard`}>{t('offlineAttendance.discardTitle')}</h2>
            <p>{describe(target)}</p>
            <p>{t('offlineAttendance.discardBody')}</p>
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                ref={confirmRef}
                disabled={busy}
                data-testid="offline-discard-confirm"
                onClick={() => {
                  setBusy(true);
                  void offline.discard(target.key).finally(() => {
                    setBusy(false);
                    dialogRef.current?.close();
                  });
                }}
              >
                {t('offlineAttendance.discardConfirm')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                onClick={() => dialogRef.current?.close()}
              >
                {t('offlineAttendance.cancel')}
              </button>
            </div>
          </div>
        )}
      </dialog>
    </section>
  );
}

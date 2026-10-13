import type { AttendanceReceipt } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useOfflineAttendance } from './offline/OfflineAttendanceProvider';
import type { QueuedEvent } from './offline/offlineQueue';
import { ShiftFacts } from './SubstitutionParts';
import {
  ATTENDANCE_NETWORK_FAILURE,
  OFFLINE_SAVE_FAILURE,
  SETTLED_ATTENDANCE_KINDS,
  attendanceFailureOf,
  formatInstant,
  type AttendanceAction,
  type AttendanceFailure,
  type AttendanceShiftFacts,
} from './attendance';

/**
 * MVP-060A: the explicit confirmation of a check-in or a check-out in a native modal dialog: the
 * shift (and, for a check-out, the recorded check-in), one sentence that the server records the
 * time, then one command with a stable idempotency key. The confirm button is disabled while the
 * command is sent, so a double click never sends twice; a retry after a network failure reuses the
 * key. Nothing is kept in browser storage or put in a URL. The page mounts one dialog per action
 * (a `key`).
 *
 * MVP-061B: for a signed-in employee with a usable offline queue, the confirmation captures the
 * event once (client event ID, the time recorded on this device and its offset). It is saved on
 * this device, never sent online, while the browser is offline, or when the online command fails
 * without any HTTP response; an HTTP refusal is never queued. A save that fails is an error,
 * never a queued success. Online, the MVP-060A command is unchanged.
 */
export function AttendanceDialog({
  action,
  shift,
  keyFor,
  onRecorded,
  onSettled,
  onClosed,
  onQueued,
}: {
  action: AttendanceAction | null;
  shift: AttendanceShiftFacts | null;
  keyFor: (payload: unknown) => string;
  onRecorded: (receipt: AttendanceReceipt) => void;
  onSettled: () => void;
  onClosed: () => void;
  /** MVP-061B: the event was saved on this device (the dialog closes). */
  onQueued?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [failure, setFailure] = useState<AttendanceFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const sending = useRef(false);
  const offline = useOfflineAttendance();
  const captured = useRef<QueuedEvent | null>(null);
  const [duplicate, setDuplicate] = useState(false);
  const open = action !== null && shift !== null;
  const kind = action?.kind ?? 'checkIn';

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (open && !element.open) element.showModal();
    if (!open && element.open) element.close();
  }, [open]);

  useEffect(() => {
    if (!open) return;
    requestAnimationFrame(() => {
      if (failure) failureRef.current?.focus();
      else confirmRef.current?.focus();
    });
  }, [open, failure]);

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  /** Saves the captured event on this device. */
  const queue = async (): Promise<void> => {
    const event = captured.current;
    const outcome = offline && event ? await offline.capture(event) : 'failed';
    if (outcome === 'queued') {
      onQueued?.();
      dialogRef.current?.close();
      return;
    }
    if (outcome === 'duplicate') {
      setDuplicate(true);
      return;
    }
    setFailure(OFFLINE_SAVE_FAILURE);
  };

  const send = async () => {
    if (!action || sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    // Captured once, at the first confirmation: a retry never recalculates it.
    if (offline?.available && captured.current === null) {
      captured.current = offline.prepare({
        type: action.kind === 'checkIn' ? 'CHECK_IN' : 'CHECK_OUT',
        slotId: action.slotId,
      });
    }
    const queueable = captured.current !== null;
    if (queueable && !navigator.onLine) {
      try {
        await queue();
      } finally {
        sending.current = false;
        setBusy(false);
      }
      return;
    }
    try {
      const key = keyFor(action);
      const result =
        action.kind === 'checkIn'
          ? await core.POST('/me/attendance/check-in', {
              params: { header: { 'Idempotency-Key': key } },
              body: { slotId: action.slotId },
            })
          : await core.POST('/me/attendance/{attendanceRecordId}/check-out', {
              params: {
                path: { attendanceRecordId: action.recordId },
                header: { 'Idempotency-Key': key },
              },
            });
      if (result.data) {
        onRecorded(result.data);
        dialogRef.current?.close();
        return;
      }
      const refused = attendanceFailureOf(result.response, result.error);
      if (SETTLED_ATTENDANCE_KINDS.has(refused.kind)) onSettled();
      setFailure(refused);
    } catch {
      // No HTTP response at all: saved on this device when the queue is usable.
      if (queueable) await queue();
      else setFailure(ATTENDANCE_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const settled = duplicate || (failure ? SETTLED_ATTENDANCE_KINDS.has(failure.kind) : false);
  const offlineNow = offline?.available === true && !offline.online;

  return (
    <dialog
      ref={dialogRef}
      className="decision-dialog"
      aria-labelledby={`${ids}-title`}
      data-testid="attendance-dialog"
      data-kind={kind}
      onClose={onClosed}
      onCancel={(event) => {
        if (busy) event.preventDefault();
      }}
    >
      {open && (
        <div className="decision-dialog__panel">
          <h2 id={`${ids}-title`}>{t(`attendance.${kind}.title`)}</h2>
          <ShiftFacts
            start={shift.start}
            end={shift.end}
            labels={shift.labels}
            siteName={shift.siteName}
            siteTimezone={shift.siteTimezone}
            testId="attendance-dialog-shift"
          />
          {kind === 'checkOut' && shift.checkedInAt && (
            <p data-testid="attendance-dialog-checked-in">
              {t('attendance.shiftCheckedIn', {
                time: formatInstant(language, shift.checkedInAt, shift.siteTimezone),
              })}
            </p>
          )}
          {failure && (
            <div
              className="error-summary"
              role="alert"
              tabIndex={-1}
              ref={failureRef}
              data-testid="attendance-dialog-error"
              data-kind={failure.kind}
            >
              <p>{t(failure.messageKey)}</p>
              {failure.retryAfter !== undefined && (
                <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
              )}
              {failure.correlationId && (
                <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
              )}
            </div>
          )}
          {duplicate && (
            <p role="status" data-testid="attendance-dialog-duplicate">
              {t('offlineAttendance.duplicate')}
            </p>
          )}
          {offlineNow ? (
            // Offline the server records nothing now: the device time is saved instead.
            <p data-testid="attendance-dialog-offline">{t('offlineAttendance.offlineConfirm')}</p>
          ) : (
            <p>{t(`attendance.${kind}.confirm`)}</p>
          )}
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              ref={confirmRef}
              disabled={busy || settled}
              aria-busy={busy ? true : undefined}
              data-testid="attendance-confirm"
              onClick={() => void send()}
            >
              {busy ? t('attendance.form.sending') : t(`attendance.${kind}.confirmButton`)}
            </button>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              onClick={close}
            >
              {t('attendance.form.cancel')}
            </button>
          </div>
        </div>
      )}
    </dialog>
  );
}

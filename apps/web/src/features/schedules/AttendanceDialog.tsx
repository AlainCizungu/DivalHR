import type { AttendanceReceipt } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { ShiftFacts } from './SubstitutionParts';
import {
  ATTENDANCE_NETWORK_FAILURE,
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
 */
export function AttendanceDialog({
  action,
  shift,
  keyFor,
  onRecorded,
  onSettled,
  onClosed,
}: {
  action: AttendanceAction | null;
  shift: AttendanceShiftFacts | null;
  keyFor: (payload: unknown) => string;
  onRecorded: (receipt: AttendanceReceipt) => void;
  onSettled: () => void;
  onClosed: () => void;
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

  const send = async () => {
    if (!action || sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
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
      setFailure(ATTENDANCE_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const settled = failure ? SETTLED_ATTENDANCE_KINDS.has(failure.kind) : false;

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
          <p>{t(`attendance.${kind}.confirm`)}</p>
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

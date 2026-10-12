import type { SupervisorAttendanceReceipt, TeamAttendanceAction } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { ProblemList, ReasonFields, ShiftFacts } from './SubstitutionParts';
import { formatInstant } from './attendance';
import type { ReasonForm } from './substitutions';
import {
  SETTLED_TEAM_KINDS,
  TEAM_NETWORK_FAILURE,
  checkInBodyOf,
  checkOutBodyOf,
  commandOf,
  employeeName,
  kindOf,
  teamFailureOf,
  teamProblemsOf,
  type TeamAttendanceCommand,
  type TeamAttendanceFailure,
  type TeamField,
} from './teamAttendance';

/**
 * MVP-060B: the manager records a direct report's check-in or check-out in a native modal dialog:
 * the employee, the shift, the site and the action, the reason in French or English, one sentence
 * that the server records the time and that the employee sees the reason, then one explicit
 * confirmation. The confirm button is disabled while the command is sent, so a double click never
 * sends twice; a retry of the same reason reuses the idempotency key (an edited reason is a new
 * command). Nothing is kept in browser storage or put in a URL. The page mounts one dialog per row.
 */
export function TeamAttendanceDialog({
  action,
  keyFor,
  onRecorded,
  onSettled,
  onClosed,
}: {
  action: TeamAttendanceAction | null;
  keyFor: (payload: unknown) => string;
  onRecorded: (receipt: SupervisorAttendanceReceipt) => void;
  onSettled: () => void;
  onClosed: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [form, setForm] = useState<ReasonForm>({ reasonLocale: language, reason: '' });
  const [problems, setProblems] = useState<ReadonlySet<TeamField>>(new Set());
  const [failure, setFailure] = useState<TeamAttendanceFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const sending = useRef(false);
  const open = action !== null;
  const kind = action ? kindOf(action) : 'checkIn';

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
      else if (problems.size > 0) problemsRef.current?.focus();
    });
  }, [open, failure, problems]);

  useEffect(() => {
    if (open) requestAnimationFrame(() => reasonRef.current?.focus());
  }, [open]);

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  const send = async () => {
    if (!action || sending.current) return;
    const found = teamProblemsOf(form);
    setProblems(found);
    if (found.size > 0) {
      setFailure(null);
      return;
    }
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const command: TeamAttendanceCommand = commandOf(action, form);
      const key = keyFor(command);
      const result =
        command.kind === 'checkIn'
          ? await core.POST('/me/team-attendance/check-in', {
              params: { header: { 'Idempotency-Key': key } },
              body: checkInBodyOf(command),
            })
          : await core.POST('/me/team-attendance/{attendanceRecordId}/check-out', {
              params: {
                path: { attendanceRecordId: command.recordId },
                header: { 'Idempotency-Key': key },
              },
              body: checkOutBodyOf(command),
            });
      if (result.data) {
        onRecorded(result.data);
        dialogRef.current?.close();
        return;
      }
      const refused = teamFailureOf(result.response, result.error);
      if (SETTLED_TEAM_KINDS.has(refused.kind)) onSettled();
      if (refused.kind === 'validation' && refused.fields && refused.fields.length > 0) {
        setProblems(new Set(refused.fields));
      }
      setFailure(refused);
    } catch {
      setFailure(TEAM_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const settled = failure ? SETTLED_TEAM_KINDS.has(failure.kind) : false;

  return (
    <dialog
      ref={dialogRef}
      className="decision-dialog"
      aria-labelledby={`${ids}-title`}
      aria-describedby={`${ids}-employee`}
      data-testid="team-attendance-dialog"
      data-kind={kind}
      onClose={onClosed}
      onCancel={(event) => {
        if (busy) event.preventDefault();
      }}
    >
      {action && (
        <form
          className="decision-dialog__panel"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void send();
          }}
        >
          <h2 id={`${ids}-title`}>{t(`teamAttendance.${kind}.title`)}</h2>
          <p id={`${ids}-employee`} data-testid="team-attendance-dialog-employee">
            {t('teamAttendance.dialog.employee', { name: employeeName(action) })}
          </p>
          <ShiftFacts
            start={action.start}
            end={action.end}
            labels={action.labels}
            siteName={action.site.name}
            siteTimezone={action.siteTimezone}
            testId="team-attendance-dialog-shift"
          />
          {kind === 'checkOut' && action.checkedInAt && (
            <p data-testid="team-attendance-dialog-checked-in">
              {t('attendance.shiftCheckedIn', {
                time: formatInstant(language, action.checkedInAt, action.siteTimezone),
              })}
            </p>
          )}
          {problems.size > 0 && !failure && (
            <ProblemList
              idPrefix={ids}
              problems={problems}
              listRef={problemsRef}
              testId="team-attendance-problems"
            />
          )}
          {failure && (
            <div
              className="error-summary"
              role="alert"
              tabIndex={-1}
              ref={failureRef}
              data-testid="team-attendance-dialog-error"
              data-kind={failure.kind}
            >
              <p>{t(failure.messageKey)}</p>
              {failure.fields && failure.fields.length > 0 && (
                <ul>
                  {failure.fields.map((field) => (
                    <li key={field}>{t(`scheduleSubstitutions.problems.${field}`)}</li>
                  ))}
                </ul>
              )}
              {failure.retryAfter !== undefined && (
                <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
              )}
              {failure.correlationId && (
                <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
              )}
            </div>
          )}
          <ReasonFields
            idPrefix={ids}
            form={form}
            problems={problems}
            reasonRef={reasonRef}
            onChange={(change) => {
              setForm((previous) => ({ ...previous, ...change }));
            }}
          />
          <p>{t(`teamAttendance.${kind}.confirm`)}</p>
          <div className="dialog-actions">
            <button
              type="submit"
              className="button"
              disabled={busy || settled}
              aria-busy={busy ? true : undefined}
              data-testid="team-attendance-confirm"
            >
              {busy ? t('attendance.form.sending') : t(`teamAttendance.${kind}.confirmButton`)}
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
        </form>
      )}
    </dialog>
  );
}

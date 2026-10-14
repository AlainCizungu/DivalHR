import type {
  AttendanceCorrectionHistory,
  AttendanceCorrectionPreview,
  AttendanceCorrectionReceipt,
  AttendanceCorrectionTimes,
  AttendanceExceptionType,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { codePoints } from '../leave/leaveApprovals';
import { ShiftFacts } from './SubstitutionParts';
import { formatInstant } from './attendance';
import {
  CORRECTION_NETWORK_FAILURE,
  CORRECTION_REASONS,
  EXPLANATION_MAX,
  EXPLANATION_MIN,
  commitOf,
  correctionFailureOf,
  correctionProblemsOf,
  explanationOf,
  proposalOf,
  timeField,
  type CorrectionFailure,
  type CorrectionField,
  type CorrectionForm,
  type CorrectionProblem,
  type CorrectionTarget,
} from './attendanceCorrections';
import { exceptionEmployeeName } from './attendanceExceptions';

type Step = 'loading' | 'form' | 'preview' | 'done';

const EMPTY: CorrectionForm = {
  checkIn: { local: '', original: null },
  hasCheckOut: true,
  checkOut: { local: '', original: null },
  reasonCode: '',
  explanation: '',
};

/**
 * MVP-062: a tenant administrator corrects one attendance record, or creates a missing one, from a
 * factual exception, in a native modal dialog of three steps. 1. The form: the employee, the shift
 * and the site's time zone, the current effective values and the history (for a record), the
 * corrected site-local check-in and optional check-out, a closed reason and an explanation. 2. The
 * server's preview: before and after, the factual exceptions before and after, neutral warnings,
 * and an explicit confirmation. 3. The committed correction and the history, newest first. A retry
 * of the same commit reuses its idempotency key; a stale preview is previewed again, never
 * resubmitted. Nothing is kept in browser storage or put in a URL.
 */
export function AttendanceCorrectionDialog({
  target,
  onCorrected,
  onClosed,
}: {
  target: CorrectionTarget | null;
  onCorrected: (receipt: AttendanceCorrectionReceipt) => void;
  onClosed: () => void;
}) {
  const ids = useId();
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const open = target !== null;

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (open && !element.open) element.showModal();
    if (!open && element.open) element.close();
  }, [open]);

  return (
    <dialog
      ref={dialogRef}
      className="decision-dialog"
      aria-labelledby={`${ids}-title`}
      aria-describedby={`${ids}-employee`}
      data-testid="attendance-correction-dialog"
      data-operation={target?.kind === 'record' ? 'REPLACE_TIMES' : 'CREATE_MISSING_RECORD'}
      onClose={onClosed}
      onCancel={(event) => {
        if (busy) event.preventDefault();
      }}
    >
      {target && (
        <CorrectionPanel
          key={target.kind === 'record' ? target.recordId : `${target.slotId}-${target.employeeId}`}
          ids={ids}
          target={target}
          busy={busy}
          setBusy={setBusy}
          close={() => {
            if (!busy) dialogRef.current?.close();
          }}
          onCorrected={onCorrected}
        />
      )}
    </dialog>
  );
}

/** One opening of the dialog: mounted afresh for each target, so its state starts clean. */
function CorrectionPanel({
  ids,
  target,
  busy,
  setBusy,
  close,
  onCorrected,
}: {
  ids: string;
  target: CorrectionTarget;
  busy: boolean;
  setBusy: (busy: boolean) => void;
  close: () => void;
  onCorrected: (receipt: AttendanceCorrectionReceipt) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [step, setStep] = useState<Step>(target.kind === 'missing' ? 'form' : 'loading');
  const [form, setForm] = useState<CorrectionForm>(EMPTY);
  const [history, setHistory] = useState<AttendanceCorrectionHistory | null>(null);
  const [preview, setPreview] = useState<AttendanceCorrectionPreview | null>(null);
  const [receipt, setReceipt] = useState<AttendanceCorrectionReceipt | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [problems, setProblems] = useState<Map<CorrectionField, CorrectionProblem>>(new Map());
  const [failure, setFailure] = useState<CorrectionFailure | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const key = useRef<string>('');
  const sending = useRef(false);
  const zone = target.row.siteTimezone;
  const operation = target.kind === 'record' ? 'REPLACE_TIMES' : 'CREATE_MISSING_RECORD';

  // A record's current values and history prefill the form (a missing record starts empty).
  useEffect(() => {
    if (target.kind !== 'record') return;
    const abort = new AbortController();
    void (async () => {
      try {
        const { data, error, response } = await core.GET(
          '/attendance/records/{attendanceRecordId}/corrections',
          {
            params: { path: { attendanceRecordId: target.recordId } },
            cache: 'no-store',
            signal: abort.signal,
          },
        );
        if (abort.signal.aborted) return;
        if (data) {
          setForm({
            checkIn: timeField(data.record.checkedInAt, zone),
            hasCheckOut: data.record.checkedOutAt !== null,
            checkOut: timeField(data.record.checkedOutAt, zone),
            reasonCode: '',
            explanation: '',
          });
          setHistory(data);
        } else {
          setFailure(correctionFailureOf(response, error));
        }
        setStep('form');
      } catch {
        if (!abort.signal.aborted) {
          setFailure(CORRECTION_NETWORK_FAILURE);
          setStep('form');
        }
      }
    })();
    return () => {
      abort.abort();
    };
  }, [core, target, zone]);

  useEffect(() => {
    if (step === 'loading') return;
    requestAnimationFrame(() => {
      if (failure) failureRef.current?.focus();
      else headingRef.current?.focus();
    });
  }, [step, failure]);

  const fail = (refused: CorrectionFailure) => {
    if (refused.kind === 'validation' && refused.fields && refused.fields.length > 0) {
      const next = new Map<CorrectionField, CorrectionProblem>();
      for (const field of refused.fields) next.set(field, 'format');
      setProblems(next);
      setStep('form');
    }
    if (refused.kind === 'stale') setStep('form');
    setFailure(refused);
  };

  const previewNow = async () => {
    if (sending.current) return;
    const found = correctionProblemsOf(form, zone);
    setProblems(found);
    setFailure(null);
    if (found.size > 0) {
      const first = [...found.keys()][0];
      document.getElementById(`${ids}-${first ?? 'checkedInAt'}`)?.focus();
      return;
    }
    sending.current = true;
    setBusy(true);
    try {
      const proposal = proposalOf(form, zone);
      const result =
        target.kind === 'record'
          ? await core.POST('/attendance/records/{attendanceRecordId}/correction-preview', {
              params: { path: { attendanceRecordId: target.recordId } },
              body: proposal,
            })
          : await core.POST('/attendance/corrections/missing-record-preview', {
              body: { ...proposal, slotId: target.slotId, employeeId: target.employeeId },
            });
      if (result.data) {
        // One key per previewed proposal (the platform's key grammar): a retry reuses it.
        key.current = `web-${crypto.randomUUID()}`;
        setPreview(result.data);
        setConfirmed(false);
        setStep('preview');
      } else {
        fail(correctionFailureOf(result.response, result.error));
      }
    } catch {
      setFailure(CORRECTION_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const commitNow = async () => {
    if (!preview || !confirmed || sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const body = commitOf(proposalOf(form, zone), preview);
      const header = { 'Idempotency-Key': key.current };
      const result =
        target.kind === 'record'
          ? await core.POST('/attendance/records/{attendanceRecordId}/corrections', {
              params: { path: { attendanceRecordId: target.recordId }, header },
              body,
            })
          : await core.POST('/attendance/corrections/missing-record', {
              params: { header },
              body: { ...body, slotId: target.slotId, employeeId: target.employeeId },
            });
      if (result.data) {
        const done = result.data;
        setReceipt(done);
        onCorrected(done);
        try {
          const loaded = await core.GET('/attendance/records/{attendanceRecordId}/corrections', {
            params: { path: { attendanceRecordId: done.record.attendanceRecordId } },
            cache: 'no-store',
          });
          setHistory(loaded.data ?? null);
        } catch {
          setHistory(null);
        }
        setStep('done');
      } else {
        fail(correctionFailureOf(result.response, result.error));
      }
    } catch {
      setFailure(CORRECTION_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const at = (instant: string | null | undefined) =>
    instant ? formatInstant(language, instant, zone) : t('attendanceCorrection.none');
  const typesText = (types: AttendanceExceptionType[]) =>
    types.length === 0
      ? t('attendanceCorrection.noException')
      : types.map((type) => t(`attendanceExceptions.type.${type}`)).join(' · ');
  const problemText = (field: CorrectionField) => {
    const problem = problems.get(field);
    return problem
      ? t(`attendanceCorrection.problems.${field}.${problem}`, {
          min: EXPLANATION_MIN,
          max: EXPLANATION_MAX,
        })
      : null;
  };
  const row = target.row;
  const name = exceptionEmployeeName(row);
  const length = codePoints(explanationOf(form.explanation));

  return (
    <div
      className="decision-dialog__panel"
      data-testid="attendance-correction-panel"
      data-step={step}
    >
      <h2 id={`${ids}-title`} ref={headingRef} tabIndex={-1}>
        {t(`attendanceCorrection.title.${step === 'done' ? 'done' : operation}`)}
      </h2>
      <p id={`${ids}-employee`} data-testid="attendance-correction-employee">
        {t('attendanceCorrection.employee', { name })}
      </p>
      <ShiftFacts
        start={row.start}
        end={row.end}
        labels={row.labels}
        siteName={row.site.name}
        siteTimezone={zone}
        testId="attendance-correction-shift"
      />
      <p className="muted" data-testid="attendance-correction-zone">
        {t('attendanceCorrection.zone', { zone })}
      </p>
      {failure && (
        <div
          className="error-summary"
          role="alert"
          tabIndex={-1}
          ref={failureRef}
          data-testid="attendance-correction-error"
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
      {step === 'loading' && <p role="status">{t('attendanceCorrection.loading')}</p>}

      {step === 'form' && (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void previewNow();
          }}
          data-testid="attendance-correction-form"
        >
          {history && <Current history={history} at={at} testId="attendance-correction-current" />}
          <TimeInput
            id={`${ids}-checkedInAt`}
            label={t('attendanceCorrection.checkIn')}
            help={t('attendanceCorrection.timeHelp', { zone })}
            value={form.checkIn.local}
            error={problemText('checkedInAt')}
            onChange={(local) => {
              setForm({ ...form, checkIn: { ...form.checkIn, local } });
            }}
          />
          <div className="field">
            <label>
              <input
                type="checkbox"
                checked={!form.hasCheckOut}
                data-testid="attendance-correction-no-check-out"
                onChange={(event) => {
                  setForm({ ...form, hasCheckOut: !event.target.checked });
                }}
              />{' '}
              {t('attendanceCorrection.noCheckOut')}
            </label>
          </div>
          {form.hasCheckOut && (
            <TimeInput
              id={`${ids}-checkedOutAt`}
              label={t('attendanceCorrection.checkOut')}
              help={t('attendanceCorrection.timeHelp', { zone })}
              value={form.checkOut.local}
              error={problemText('checkedOutAt')}
              onChange={(local) => {
                setForm({ ...form, checkOut: { ...form.checkOut, local } });
              }}
            />
          )}
          <div className="field">
            <label htmlFor={`${ids}-reasonCode`}>{t('attendanceCorrection.reason')}</label>
            {problemText('reasonCode') && (
              <p id={`${ids}-reasonCode-error`} className="field__error">
                {problemText('reasonCode')}
              </p>
            )}
            <select
              id={`${ids}-reasonCode`}
              value={form.reasonCode}
              aria-invalid={problems.has('reasonCode') ? true : undefined}
              aria-describedby={problems.has('reasonCode') ? `${ids}-reasonCode-error` : undefined}
              onChange={(event) => {
                setForm({
                  ...form,
                  reasonCode: event.target.value as CorrectionForm['reasonCode'],
                });
              }}
            >
              <option value="">{t('attendanceCorrection.chooseReason')}</option>
              {CORRECTION_REASONS.map((reason) => (
                <option key={reason} value={reason}>
                  {t(`attendanceCorrection.reasons.${reason}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-explanation`}>{t('attendanceCorrection.explanation')}</label>
            <p id={`${ids}-explanation-help`} className="field__help">
              {t('attendanceCorrection.explanationHelp', {
                min: EXPLANATION_MIN,
                max: EXPLANATION_MAX,
              })}
            </p>
            {problemText('explanation') && (
              <p id={`${ids}-explanation-error`} className="field__error">
                {problemText('explanation')}
              </p>
            )}
            <textarea
              id={`${ids}-explanation`}
              rows={3}
              value={form.explanation}
              aria-invalid={problems.has('explanation') ? true : undefined}
              aria-describedby={
                problems.has('explanation')
                  ? `${ids}-explanation-help ${ids}-explanation-count ${ids}-explanation-error`
                  : `${ids}-explanation-help ${ids}-explanation-count`
              }
              onChange={(event) => {
                setForm({ ...form, explanation: event.target.value });
              }}
            />
            <p id={`${ids}-explanation-count`} className="muted">
              {t('attendanceCorrection.count', { count: length, max: EXPLANATION_MAX })}
            </p>
          </div>
          <div className="dialog-actions">
            <button
              type="submit"
              className="button"
              disabled={busy}
              aria-busy={busy ? true : undefined}
              data-testid="attendance-correction-preview"
            >
              {busy ? t('attendanceCorrection.previewing') : t('attendanceCorrection.preview')}
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

      {step === 'preview' && preview && (
        <form
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            void commitNow();
          }}
          data-testid="attendance-correction-review"
        >
          <BeforeAfter
            before={preview.before}
            after={preview.after}
            at={at}
            testId="attendance-correction-before-after"
          />
          <dl className="decision-summary">
            <dt>{t('attendanceCorrection.exceptionsBefore')}</dt>
            <dd data-testid="attendance-correction-exceptions-before">
              {typesText(preview.exceptionsBefore)}
            </dd>
            <dt>{t('attendanceCorrection.exceptionsAfter')}</dt>
            <dd data-testid="attendance-correction-exceptions-after">
              {typesText(preview.exceptionsAfter)}
            </dd>
            <dt>{t('attendanceCorrection.reason')}</dt>
            <dd data-testid="attendance-correction-reason">
              {t(`attendanceCorrection.reasons.${preview.reasonCode}`)}
            </dd>
          </dl>
          <div className="decision-reason">
            <p>{t('attendanceCorrection.explanation')}</p>
            <blockquote data-testid="attendance-correction-explanation">
              {preview.explanation}
            </blockquote>
          </div>
          {preview.warnings.length > 0 && (
            <ul data-testid="attendance-correction-warnings">
              {preview.warnings.map((warning) => (
                <li key={warning}>{t(`attendanceCorrection.warnings.${warning}`)}</li>
              ))}
            </ul>
          )}
          <div className="field">
            <label>
              <input
                type="checkbox"
                checked={confirmed}
                data-testid="attendance-correction-confirm-check"
                onChange={(event) => {
                  setConfirmed(event.target.checked);
                }}
              />{' '}
              {t('attendanceCorrection.confirmCheck')}
            </label>
          </div>
          <div className="dialog-actions">
            <button
              type="submit"
              className="button"
              disabled={busy || !confirmed}
              aria-busy={busy ? true : undefined}
              data-testid="attendance-correction-commit"
            >
              {busy ? t('attendanceCorrection.committing') : t('attendanceCorrection.commit')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="attendance-correction-back"
              onClick={() => {
                setFailure(null);
                setStep('form');
              }}
            >
              {t('attendanceCorrection.back')}
            </button>
          </div>
        </form>
      )}

      {step === 'done' && receipt && (
        <div data-testid="attendance-correction-done">
          <p role="status" data-testid="attendance-correction-status">
            {t(`attendanceCorrection.done.${receipt.operation}`, {
              version: receipt.correctionVersion,
            })}
          </p>
          {receipt.reopenedTimesheets > 0 && (
            <p className="card" data-testid="attendance-correction-reopened">
              {t('attendanceCorrection.reopened', { count: receipt.reopenedTimesheets })}
            </p>
          )}
          <BeforeAfter
            before={receipt.before}
            after={receipt.after}
            at={at}
            testId="attendance-correction-result"
          />
          {history && <Current history={history} at={at} testId="attendance-correction-history" />}
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="attendance-correction-close"
              onClick={close}
            >
              {t('attendanceCorrection.close')}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function TimeInput({
  id,
  label,
  help,
  value,
  error,
  onChange,
}: {
  id: string;
  label: string;
  help: string;
  value: string;
  error: string | null;
  onChange: (value: string) => void;
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <p id={`${id}-help`} className="field__help">
        {help}
      </p>
      {error && (
        <p id={`${id}-error`} className="field__error">
          {error}
        </p>
      )}
      <input
        id={id}
        type="datetime-local"
        step={1}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-help ${id}-error` : `${id}-help`}
        onChange={(event) => {
          onChange(event.target.value);
        }}
      />
    </div>
  );
}

function BeforeAfter({
  before,
  after,
  at,
  testId,
}: {
  before: AttendanceCorrectionTimes | null;
  after: AttendanceCorrectionTimes;
  at: (instant: string | null | undefined) => string;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <ScrollRegion label={t('attendanceCorrection.beforeAfter')}>
      <table data-testid={testId}>
        <caption className="visually-hidden">{t('attendanceCorrection.beforeAfter')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('attendanceCorrection.value')}</th>
            <th scope="col">{t('attendanceCorrection.before')}</th>
            <th scope="col">{t('attendanceCorrection.after')}</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row">{t('attendanceCorrection.checkIn')}</th>
            <td data-testid={`${testId}-before-in`}>
              {before ? at(before.checkedInAt) : t('attendanceCorrection.noRecord')}
            </td>
            <td data-testid={`${testId}-after-in`}>{at(after.checkedInAt)}</td>
          </tr>
          <tr>
            <th scope="row">{t('attendanceCorrection.checkOut')}</th>
            <td data-testid={`${testId}-before-out`}>
              {before ? at(before.checkedOutAt) : t('attendanceCorrection.noRecord')}
            </td>
            <td data-testid={`${testId}-after-out`}>{at(after.checkedOutAt)}</td>
          </tr>
        </tbody>
      </table>
    </ScrollRegion>
  );
}

/** The record's current effective values and its corrections, newest first. */
function Current({
  history,
  at,
  testId,
}: {
  history: AttendanceCorrectionHistory;
  at: (instant: string | null | undefined) => string;
  testId: string;
}) {
  const { t } = useTranslation();
  const record = history.record;
  return (
    <section className="correction-history" data-testid={testId}>
      <h3>{t('attendanceCorrection.current')}</h3>
      <dl className="decision-summary">
        <dt>{t('attendanceCorrection.status')}</dt>
        <dd data-testid={`${testId}-status`}>{t(`attendance.status.${record.status}`)}</dd>
        <dt>{t('attendanceCorrection.checkIn')}</dt>
        <dd data-testid={`${testId}-in`}>{at(record.checkedInAt)}</dd>
        <dt>{t('attendanceCorrection.checkOut')}</dt>
        <dd data-testid={`${testId}-out`}>{at(record.checkedOutAt)}</dd>
      </dl>
      {history.originalEvents.length > 0 && (
        <p className="muted" data-testid={`${testId}-originals`}>
          {t('attendanceCorrection.originals', {
            events: history.originalEvents
              .map(
                (event) =>
                  `${t(`attendanceCorrection.event.${event.type}`)} ${at(event.recordedAt)}`,
              )
              .join(' · '),
          })}
        </p>
      )}
      <h3>{t('attendanceCorrection.history')}</h3>
      {history.corrections.length === 0 ? (
        <p data-testid={`${testId}-empty`}>{t('attendanceCorrection.noHistory')}</p>
      ) : (
        <ol className="correction-history__list" data-testid={`${testId}-list`}>
          {history.corrections.map((entry) => (
            <li key={entry.id} data-testid="attendance-correction-entry">
              <p>
                <StatusBadge tone="neutral">
                  {t('attendanceCorrection.version', { version: entry.version })}
                </StatusBadge>{' '}
                {t(`attendanceCorrection.operation.${entry.operation}`)} · {at(entry.correctedAt)} ·{' '}
                {entry.correctedByYou
                  ? t('attendanceCorrection.byYou')
                  : t('attendanceCorrection.byAdministrator', {
                      reference: entry.administratorReference,
                    })}
              </p>
              <p>
                {t('attendanceCorrection.change', {
                  beforeIn: entry.before
                    ? at(entry.before.checkedInAt)
                    : t('attendanceCorrection.noRecord'),
                  beforeOut: entry.before
                    ? at(entry.before.checkedOutAt)
                    : t('attendanceCorrection.noRecord'),
                  afterIn: at(entry.after.checkedInAt),
                  afterOut: at(entry.after.checkedOutAt),
                })}
              </p>
              <p>
                {t(`attendanceCorrection.reasons.${entry.reasonCode}`)} — <q>{entry.explanation}</q>
              </p>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

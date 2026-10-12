import type { MyWorkScheduleSubstitution, MyWorkShift } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { normalizeReason } from '../leave/leaveApprovals';
import { ProblemList, ReasonFields, ShiftFacts, SubstitutionAlert } from './SubstitutionParts';
import {
  SETTLED_SUBSTITUTION_KINDS,
  SUBSTITUTION_NETWORK_FAILURE,
  reasonProblemsOf,
  requestBodyOf,
  substitutionFailureOf,
  type ReasonForm,
  type SubstitutionFailure,
  type SubstitutionField,
} from './substitutions';

type Step = 'form' | 'confirm';

/**
 * MVP-051: the employee asks for a substitute on one of their own future shifts in a native modal
 * dialog: the shift, a reason in French or English (the interface language by default, always
 * sent), an explicit confirmation showing the exact normalized reason, then one request with a
 * stable idempotency key (any edit is a new key). The employee does not choose the substitute.
 * Nothing is kept in browser storage or put in a URL. The page mounts one dialog per shift (a
 * `key`), so each opening starts from an empty form.
 */
export function SubstitutionRequestDialog({
  shift,
  keyFor,
  onRequested,
  onSettled,
  onClosed,
}: {
  shift: MyWorkShift | null;
  keyFor: (payload: unknown) => string;
  onRequested: (request: MyWorkScheduleSubstitution) => void;
  onSettled: () => void;
  onClosed: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [step, setStep] = useState<Step>('form');
  const [form, setForm] = useState<ReasonForm>({ reasonLocale: language, reason: '' });
  const [problems, setProblems] = useState<Set<SubstitutionField>>(new Set());
  const [failure, setFailure] = useState<SubstitutionFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const sending = useRef(false);
  const slotId = shift?.id ?? null;

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (slotId && !element.open) element.showModal();
    if (!slotId && element.open) element.close();
  }, [slotId]);

  useEffect(() => {
    if (!slotId) return;
    requestAnimationFrame(() => {
      if (failure) failureRef.current?.focus();
      else if (problems.size > 0) problemsRef.current?.focus();
      else if (step === 'confirm') confirmRef.current?.focus();
      else reasonRef.current?.focus();
    });
  }, [slotId, step, failure, problems]);

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  const review = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = reasonProblemsOf(form);
    setProblems(found);
    setFailure(null);
    setStep(found.size > 0 ? 'form' : 'confirm');
  };

  const send = async () => {
    if (!shift || sending.current) return;
    sending.current = true;
    const body = requestBodyOf(shift.id, form);
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/me/work-schedule-substitutions', {
        params: { header: { 'Idempotency-Key': keyFor(body) } },
        body,
      });
      if (data) {
        onRequested(data);
        dialogRef.current?.close();
        return;
      }
      const refused = substitutionFailureOf(response, error);
      if (SETTLED_SUBSTITUTION_KINDS.has(refused.kind)) onSettled();
      if (refused.kind === 'validation') {
        setStep('form');
        if (refused.fields) setProblems(new Set(refused.fields));
      }
      setFailure(refused);
    } catch {
      setFailure(SUBSTITUTION_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const settled = failure ? SETTLED_SUBSTITUTION_KINDS.has(failure.kind) : false;

  return (
    <dialog
      ref={dialogRef}
      className="decision-dialog"
      aria-labelledby={`${ids}-title`}
      data-testid="substitution-request-dialog"
      onClose={onClosed}
      onCancel={(event) => {
        if (busy) event.preventDefault();
      }}
    >
      {shift && (
        <div className="decision-dialog__panel">
          <h2 id={`${ids}-title`}>
            {t(
              step === 'form'
                ? 'scheduleSubstitutions.request.title'
                : 'scheduleSubstitutions.request.confirmTitle',
            )}
          </h2>
          <ShiftFacts
            start={shift.start}
            end={shift.end}
            labels={shift.labels}
            siteName={shift.site.name}
            siteTimezone={shift.siteTimezone}
            testId="substitution-request-shift"
          />
          {failure && (
            <SubstitutionAlert
              failure={failure}
              alertRef={failureRef}
              testId="substitution-request-error"
            />
          )}
          {step === 'form' && (
            <form noValidate onSubmit={review}>
              <p>{t('scheduleSubstitutions.request.intro')}</p>
              {problems.size > 0 && !failure && (
                <ProblemList
                  idPrefix={ids}
                  problems={problems}
                  listRef={problemsRef}
                  testId="substitution-request-problems"
                />
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
              <div className="dialog-actions">
                <button type="submit" className="button" disabled={busy || settled}>
                  {t('scheduleSubstitutions.form.review')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={close}
                >
                  {t('scheduleSubstitutions.form.cancel')}
                </button>
              </div>
            </form>
          )}
          {step === 'confirm' && (
            <div data-testid="substitution-request-confirm">
              <p>{t('scheduleSubstitutions.request.confirm')}</p>
              <figure className="decision-reason">
                <figcaption>{t('scheduleSubstitutions.form.reason')}</figcaption>
                <blockquote lang={form.reasonLocale} data-testid="substitution-request-reason">
                  {normalizeReason(form.reason)}
                </blockquote>
              </figure>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  ref={confirmRef}
                  disabled={busy || settled}
                  aria-busy={busy ? true : undefined}
                  onClick={() => void send()}
                >
                  {busy
                    ? t('scheduleSubstitutions.form.sending')
                    : t('scheduleSubstitutions.request.confirmButton')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy || settled}
                  onClick={() => {
                    setStep('form');
                    setFailure(null);
                  }}
                >
                  {t('scheduleSubstitutions.form.back')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={close}
                >
                  {t('scheduleSubstitutions.form.cancel')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </dialog>
  );
}

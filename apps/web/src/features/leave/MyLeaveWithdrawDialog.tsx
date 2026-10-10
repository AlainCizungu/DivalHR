import type { LeaveWithdrawalReceipt, MyLeaveRequest } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type Ref, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { formatPeriod } from '../people/history';
import { REASON_MAX, codePoints, normalizeReason } from './leaveApprovals';
import { formatAmount } from './myLeave';
import {
  SETTLED_WITHDRAWAL_KINDS,
  WITHDRAWAL_NETWORK_FAILURE,
  withdrawalBodyOf,
  withdrawalFailureOf,
  withdrawalProblemsOf,
  type WithdrawalFailure,
  type WithdrawalField,
  type WithdrawalForm,
} from './myLeaveWithdrawal';

type Step = 'form' | 'confirm';

/** A refused withdrawal: the stable message, the named fields, retry delay and reference. */
function WithdrawalAlert({
  failure,
  alertRef,
}: {
  failure: WithdrawalFailure;
  alertRef: Ref<HTMLDivElement>;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid="withdraw-error"
      data-kind={failure.kind}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.fields && failure.fields.length > 0 && (
        <ul>
          {failure.fields.map((field) => (
            <li key={field}>{t(`myLeave.withdraw.problems.${field}`)}</li>
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
  );
}

/**
 * MVP-041F: the employee withdraws their own approved leave before it starts, in a native modal
 * dialog that shows the approved period, amount, policy and the current approval: a reason in
 * French or English (the interface language by default, always sent), an explicit confirmation
 * that shows the exact normalized reason as plain text in its language, then one request with a
 * stable idempotency key for the request and the normalized reason (any edit is a new key).
 * Nothing is kept in browser storage or put in a URL. The page owns the list: it is told when the
 * leave was withdrawn or turned out to be settled already (window closed, withdrawn, or no longer
 * approved), and when the dialog closed. The page mounts one dialog per target (a `key`), so each
 * opening starts from an empty form in the interface language.
 */
export function MyLeaveWithdrawDialog({
  request,
  keyFor,
  onWithdrawn,
  onSettled,
  onClosed,
}: {
  request: MyLeaveRequest | null;
  keyFor: (payload: unknown) => string;
  onWithdrawn: (receipt: LeaveWithdrawalReceipt, request: MyLeaveRequest) => void;
  onSettled: () => void;
  onClosed: (gone: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [step, setStep] = useState<Step>('form');
  const [form, setForm] = useState<WithdrawalForm>({ reasonLocale: language, reason: '' });
  const [problems, setProblems] = useState<Set<WithdrawalField>>(new Set());
  const [failure, setFailure] = useState<WithdrawalFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const gone = useRef(false);
  // One withdrawal in flight, even across quick activations before React re-renders.
  const sending = useRef(false);
  const requestId = request?.id ?? null;

  // The native modal dialog follows the target.
  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (requestId && !element.open) element.showModal();
    if (!requestId && element.open) element.close();
  }, [requestId]);

  // Focus: the failure, the problems, the confirmation, or the reason.
  useEffect(() => {
    if (!requestId) return;
    requestAnimationFrame(() => {
      if (failure) failureRef.current?.focus();
      else if (problems.size > 0) problemsRef.current?.focus();
      else if (step === 'confirm') confirmRef.current?.focus();
      else reasonRef.current?.focus();
    });
  }, [requestId, step, failure, problems]);

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  const review = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = withdrawalProblemsOf(form);
    setProblems(found);
    setFailure(null);
    setStep(found.size > 0 ? 'form' : 'confirm');
  };

  const send = async () => {
    if (!request || sending.current) return;
    sending.current = true;
    const body = withdrawalBodyOf(form);
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/leave-requests/{requestId}/withdrawal',
        {
          params: {
            path: { requestId: request.id },
            // The same withdrawal keeps its key across retries; any edit is a new key.
            header: { 'Idempotency-Key': keyFor({ requestId: request.id, body }) },
          },
          body,
        },
      );
      if (data) {
        gone.current = true;
        onWithdrawn(data, request);
        dialogRef.current?.close();
        return;
      }
      const refused = withdrawalFailureOf(response, error);
      if (SETTLED_WITHDRAWAL_KINDS.has(refused.kind)) {
        gone.current = true;
        onSettled();
      }
      if (refused.kind === 'validation') {
        setStep('form');
        if (refused.fields) setProblems(new Set(refused.fields));
      }
      setFailure(refused);
    } catch {
      setFailure(WITHDRAWAL_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const fieldId = (field: WithdrawalField) => `${ids}-${field}`;
  const describedBy = (field: WithdrawalField) =>
    [`${fieldId(field)}-help`, problems.has(field) ? `${fieldId(field)}-error` : null]
      .filter(Boolean)
      .join(' ');
  const fieldError = (field: WithdrawalField) =>
    problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`myLeave.withdraw.problems.${field}`)}
      </p>
    ) : null;
  const settled = failure ? SETTLED_WITHDRAWAL_KINDS.has(failure.kind) : false;
  const reasonLength = codePoints(normalizeReason(form.reason));
  const name = request ? request.policyNames[language] : '';
  const period = request ? formatPeriod(t, language, request.startDate, request.endDate) : '';

  return (
    <dialog
      ref={dialogRef}
      className="decision-dialog"
      aria-labelledby={`${ids}-title`}
      data-testid="withdraw-dialog"
      onClose={() => {
        onClosed(gone.current);
      }}
      onCancel={(event) => {
        // Esc closes the dialog, except while the withdrawal is being sent.
        if (busy) event.preventDefault();
      }}
    >
      {request && (
        <div className="decision-dialog__panel">
          <h2 id={`${ids}-title`}>
            {t(step === 'form' ? 'myLeave.withdraw.title' : 'myLeave.withdraw.confirmTitle')}
          </h2>
          <dl className="decision-summary" data-testid="withdraw-summary">
            <dt>{t('myLeave.withdraw.summary.policy')}</dt>
            <dd>
              <span lang={language}>{name}</span>{' '}
              <span className="muted">({request.policyCode})</span>
            </dd>
            <dt>{t('myLeave.withdraw.summary.period')}</dt>
            <dd>{period}</dd>
            <dt>{t('myLeave.withdraw.summary.amount')}</dt>
            <dd>
              {t(`myLeave.amount.${request.unit}`, {
                amount: formatAmount(language, request.amount),
              })}
            </dd>
            <dt>{t('myLeave.withdraw.summary.approval')}</dt>
            <dd data-testid="withdraw-approval">
              {t(`myLeave.state.${request.state}`)}
              {request.decision && (
                <>
                  {' · '}
                  {t('myLeave.withdraw.summary.approvedOn', {
                    date: new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(
                      new Date(request.decision.decidedAt),
                    ),
                  })}
                </>
              )}
            </dd>
          </dl>
          {failure && <WithdrawalAlert failure={failure} alertRef={failureRef} />}
          {step === 'form' && (
            <form noValidate onSubmit={review}>
              <p>{t('myLeave.withdraw.intro')}</p>
              {problems.size > 0 && !failure && (
                <div
                  className="error-summary"
                  role="alert"
                  tabIndex={-1}
                  ref={problemsRef}
                  data-testid="withdraw-problems"
                >
                  <p>{t('employees.form.problems.title')}</p>
                  <ul>
                    {[...problems].map((problem) => (
                      <li key={problem}>
                        <a href={`#${fieldId(problem)}`}>
                          {t(`myLeave.withdraw.problems.${problem}`)}
                        </a>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="field">
                <label htmlFor={fieldId('reason')}>{t('myLeave.withdraw.reason')}</label>
                <p id={`${fieldId('reason')}-help`} className="field__help">
                  {t('myLeave.withdraw.reasonHelp', { max: REASON_MAX })}
                </p>
                {fieldError('reason')}
                <textarea
                  id={fieldId('reason')}
                  ref={reasonRef}
                  rows={4}
                  lang={form.reasonLocale}
                  value={form.reason}
                  autoComplete="off"
                  aria-invalid={problems.has('reason') ? true : undefined}
                  aria-describedby={describedBy('reason')}
                  onChange={(event) => {
                    setForm((previous) => ({ ...previous, reason: event.target.value }));
                  }}
                />
                <p className="field__help" data-testid="withdraw-reason-count">
                  {t('myLeave.withdraw.count', { count: reasonLength, max: REASON_MAX })}
                </p>
              </div>
              <div className="field">
                <label htmlFor={fieldId('reasonLocale')}>
                  {t('myLeave.withdraw.reasonLocale')}
                </label>
                <p id={`${fieldId('reasonLocale')}-help`} className="field__help">
                  {t('myLeave.withdraw.reasonLocaleHelp')}
                </p>
                {fieldError('reasonLocale')}
                <select
                  id={fieldId('reasonLocale')}
                  value={form.reasonLocale}
                  aria-invalid={problems.has('reasonLocale') ? true : undefined}
                  aria-describedby={describedBy('reasonLocale')}
                  onChange={(event) => {
                    const reasonLocale = event.target.value === 'en' ? 'en' : 'fr';
                    setForm((previous) => ({ ...previous, reasonLocale }));
                  }}
                >
                  <option value="fr" lang="fr">
                    {t('leaveApprovals.form.locales.fr')}
                  </option>
                  <option value="en" lang="en">
                    {t('leaveApprovals.form.locales.en')}
                  </option>
                </select>
              </div>
              <div className="dialog-actions">
                <button type="submit" className="button" disabled={busy || settled}>
                  {t('myLeave.withdraw.review')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={close}
                >
                  {t('myLeave.withdraw.keep')}
                </button>
              </div>
            </form>
          )}
          {step === 'confirm' && (
            <div data-testid="withdraw-confirm">
              <p>{t('myLeave.withdraw.confirm', { name, period })}</p>
              <figure className="decision-reason">
                <figcaption>{t('myLeave.withdraw.reason')}</figcaption>
                {/* The exact normalized reason, as plain text in its own language. */}
                <blockquote lang={form.reasonLocale} data-testid="withdraw-reason">
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
                  {busy ? t('myLeave.withdraw.sending') : t('myLeave.withdraw.confirmButton')}
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
                  {t('myLeave.withdraw.back')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={close}
                >
                  {t('myLeave.withdraw.keep')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </dialog>
  );
}

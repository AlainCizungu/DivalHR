import type { LeaveAmendmentReceipt, MyLeavePolicy, MyLeaveRequest } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type Ref, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { formatPeriod } from '../people/history';
import { REASON_MAX, codePoints, normalizeReason } from './leaveApprovals';
import { formatAmount, parseAmount, selectedPolicy } from './myLeave';
import {
  AMENDMENT_NETWORK_FAILURE,
  AMENDMENT_SETTLED_KINDS,
  amendmentBodyOf,
  amendmentFailureOf,
  amendmentFormOf,
  amendmentProblemsOf,
  type AmendmentFailure,
  type AmendmentField,
  type AmendmentForm,
} from './myLeaveAmendment';

type Step = 'form' | 'confirm';

/** A refused amendment: the stable message, the named fields, retry delay and reference. */
function AmendmentAlert({
  failure,
  alertRef,
}: {
  failure: AmendmentFailure;
  alertRef: Ref<HTMLDivElement>;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid="amend-error"
      data-kind={failure.kind}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.fields && failure.fields.length > 0 && (
        <ul>
          {failure.fields.map((field) => (
            <li key={field}>{t(`myLeave.amend.problems.${field}`)}</li>
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
 * MVP-041D: the employee replaces one of their own pending requests in a native modal dialog: the
 * same fields as a new request (prefilled with the current values), a reason in French or English
 * (the interface language by default, always sent), then an explicit confirmation that shows the
 * old and the new values and the exact normalized reason as plain text in its language, and one
 * request with a stable idempotency key (any edit is a new key). Nothing is kept in browser storage
 * or put in a URL. The page owns the list and allows one consequential action at a time across
 * amendment and cancellation: it is told when the request was replaced or turned out to be settled
 * already, and when the dialog closed. The page mounts one dialog per target (a `key`).
 */
export function MyLeaveAmendDialog({
  request,
  policies,
  asOf,
  keyFor,
  onAmended,
  onSettled,
  onClosed,
}: {
  request: MyLeaveRequest | null;
  policies: readonly MyLeavePolicy[];
  asOf: string | null;
  keyFor: (payload: unknown) => string;
  onAmended: (receipt: LeaveAmendmentReceipt, request: MyLeaveRequest) => void;
  onSettled: () => void;
  onClosed: (gone: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const other = language === 'fr' ? 'en' : 'fr';
  const [step, setStep] = useState<Step>('form');
  const [form, setForm] = useState<AmendmentForm | null>(
    request ? amendmentFormOf(request, language) : null,
  );
  const [problems, setProblems] = useState<Set<AmendmentField>>(new Set());
  const [failure, setFailure] = useState<AmendmentFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const firstRef = useRef<HTMLSelectElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const gone = useRef(false);
  // One amendment in flight, even across quick activations before React re-renders.
  const sending = useRef(false);
  const requestId = request?.id ?? null;

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (requestId && !element.open) element.showModal();
    if (!requestId && element.open) element.close();
  }, [requestId]);

  useEffect(() => {
    if (!requestId) return;
    requestAnimationFrame(() => {
      if (failure) failureRef.current?.focus();
      else if (problems.size > 0) problemsRef.current?.focus();
      else if (step === 'confirm') confirmRef.current?.focus();
      else firstRef.current?.focus();
    });
  }, [requestId, step, failure, problems]);

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  const set = (change: Partial<AmendmentForm>) => {
    setForm((previous) => (previous ? { ...previous, ...change } : previous));
  };

  const review = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!form) return;
    const found = amendmentProblemsOf(form, asOf);
    setProblems(found);
    setFailure(null);
    setStep(found.size > 0 ? 'form' : 'confirm');
  };

  const send = async () => {
    if (!request || !form || sending.current) return;
    sending.current = true;
    const body = amendmentBodyOf(form);
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/leave-requests/{requestId}/amendment',
        {
          params: {
            path: { requestId: request.id },
            // The same amendment keeps its key across retries; any edit is a new key.
            header: { 'Idempotency-Key': keyFor({ requestId: request.id, body }) },
          },
          body,
        },
      );
      if (data) {
        gone.current = true;
        onAmended(data, request);
        dialogRef.current?.close();
        return;
      }
      const refused = amendmentFailureOf(response, error);
      if (AMENDMENT_SETTLED_KINDS.has(refused.kind)) {
        gone.current = true;
        onSettled();
      }
      if (refused.kind === 'validation') {
        setStep('form');
        if (refused.fields) setProblems(new Set(refused.fields));
      }
      setFailure(refused);
    } catch {
      setFailure(AMENDMENT_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const fieldId = (field: AmendmentField) => `${ids}-${field}`;
  const describedBy = (field: AmendmentField, help: boolean) =>
    [help ? `${fieldId(field)}-help` : null, problems.has(field) ? `${fieldId(field)}-error` : null]
      .filter(Boolean)
      .join(' ') || undefined;
  const invalid = (field: AmendmentField) => (problems.has(field) ? true : undefined);
  const fieldError = (field: AmendmentField) =>
    problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`myLeave.amend.problems.${field}`)}
      </p>
    ) : null;
  const settled = failure ? AMENDMENT_SETTLED_KINDS.has(failure.kind) : false;
  const chosen = form ? selectedPolicy(policies, form.policyId) : undefined;
  const unit = chosen?.unit ?? request?.unit;
  const reasonLength = form ? codePoints(normalizeReason(form.reason)) : 0;
  const amountOf = (value: number, of: MyLeaveRequest['unit'] | undefined) =>
    of
      ? t(`myLeave.amount.${of}`, { amount: formatAmount(language, value) })
      : formatAmount(language, value);

  return (
    <dialog
      ref={dialogRef}
      className="decision-dialog"
      aria-labelledby={`${ids}-title`}
      data-testid="amend-dialog"
      onClose={() => {
        onClosed(gone.current);
      }}
      onCancel={(event) => {
        // Esc closes the dialog, except while the amendment is being sent.
        if (busy) event.preventDefault();
      }}
    >
      {request && form && (
        <div className="decision-dialog__panel">
          <h2 id={`${ids}-title`}>
            {t(step === 'form' ? 'myLeave.amend.title' : 'myLeave.amend.confirmTitle')}
          </h2>
          {failure && <AmendmentAlert failure={failure} alertRef={failureRef} />}
          {step === 'form' && (
            <form noValidate onSubmit={review}>
              <p>{t('myLeave.amend.intro')}</p>
              {problems.size > 0 && !failure && (
                <div
                  className="error-summary"
                  role="alert"
                  tabIndex={-1}
                  ref={problemsRef}
                  data-testid="amend-problems"
                >
                  <p>{t('employees.form.problems.title')}</p>
                  <ul>
                    {[...problems].map((problem) => (
                      <li key={problem}>
                        <a href={`#${fieldId(problem)}`}>
                          {t(`myLeave.amend.problems.${problem}`)}
                        </a>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="field">
                <label htmlFor={fieldId('policy')}>{t('myLeave.form.policy')}</label>
                {fieldError('policy')}
                <select
                  id={fieldId('policy')}
                  ref={firstRef}
                  value={form.policyId}
                  aria-invalid={invalid('policy')}
                  aria-describedby={describedBy('policy', false)}
                  onChange={(event) => {
                    set({ policyId: event.target.value });
                  }}
                >
                  <option value="">{t('myLeave.form.choose')}</option>
                  {policies.map((policy) => (
                    <option key={policy.id} value={policy.id}>
                      {t('myLeave.form.policyOption', {
                        name: policy.names[language],
                        code: policy.code,
                      })}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor={fieldId('startDate')}>{t('myLeave.form.startDate')}</label>
                {fieldError('startDate')}
                <input
                  id={fieldId('startDate')}
                  type="date"
                  value={form.startDate}
                  min={asOf ?? undefined}
                  max="2999-12-31"
                  aria-invalid={invalid('startDate')}
                  aria-describedby={describedBy('startDate', false)}
                  onChange={(event) => {
                    set({ startDate: event.target.value });
                  }}
                />
              </div>
              <div className="field">
                <label htmlFor={fieldId('endDate')}>{t('myLeave.form.endDate')}</label>
                <p id={`${fieldId('endDate')}-help`} className="field__help">
                  {t('myLeave.form.endDateHelp')}
                </p>
                {fieldError('endDate')}
                <input
                  id={fieldId('endDate')}
                  type="date"
                  value={form.endDate}
                  max="2999-12-31"
                  aria-invalid={invalid('endDate')}
                  aria-describedby={describedBy('endDate', true)}
                  onChange={(event) => {
                    set({ endDate: event.target.value });
                  }}
                />
              </div>
              <div className="field">
                <label htmlFor={fieldId('amount')}>
                  {unit ? t(`myLeave.form.amount.${unit}`) : t('myLeave.form.amount.UNKNOWN')}
                </label>
                <p id={`${fieldId('amount')}-help`} className="field__help">
                  {t('myLeave.form.amountHelp')}
                </p>
                {fieldError('amount')}
                <input
                  id={fieldId('amount')}
                  type="text"
                  inputMode="decimal"
                  value={form.amount}
                  maxLength={8}
                  autoComplete="off"
                  aria-invalid={invalid('amount')}
                  aria-describedby={describedBy('amount', true)}
                  onChange={(event) => {
                    set({ amount: event.target.value });
                  }}
                />
              </div>
              <div className="field">
                <label htmlFor={fieldId('reason')}>{t('myLeave.amend.reason')}</label>
                <p id={`${fieldId('reason')}-help`} className="field__help">
                  {t('myLeave.amend.reasonHelp', { max: REASON_MAX })}
                </p>
                {fieldError('reason')}
                <textarea
                  id={fieldId('reason')}
                  rows={4}
                  lang={form.reasonLocale}
                  value={form.reason}
                  autoComplete="off"
                  aria-invalid={invalid('reason')}
                  aria-describedby={describedBy('reason', true)}
                  onChange={(event) => {
                    set({ reason: event.target.value });
                  }}
                />
                <p className="field__help" data-testid="amend-reason-count">
                  {t('myLeave.amend.count', { count: reasonLength, max: REASON_MAX })}
                </p>
              </div>
              <div className="field">
                <label htmlFor={fieldId('reasonLocale')}>{t('myLeave.amend.reasonLocale')}</label>
                <p id={`${fieldId('reasonLocale')}-help`} className="field__help">
                  {t('myLeave.amend.reasonLocaleHelp')}
                </p>
                {fieldError('reasonLocale')}
                <select
                  id={fieldId('reasonLocale')}
                  value={form.reasonLocale}
                  aria-invalid={invalid('reasonLocale')}
                  aria-describedby={describedBy('reasonLocale', true)}
                  onChange={(event) => {
                    set({ reasonLocale: event.target.value === 'en' ? 'en' : 'fr' });
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
                  {t('myLeave.amend.review')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={close}
                >
                  {t('myLeave.amend.keep')}
                </button>
              </div>
            </form>
          )}
          {step === 'confirm' && (
            <div data-testid="amend-confirm">
              <p>{t('myLeave.amend.confirm')}</p>
              <table className="amend-comparison" data-testid="amend-comparison">
                <caption className="visually-hidden">{t('myLeave.amend.comparison')}</caption>
                <thead>
                  <tr>
                    <td />
                    <th scope="col">{t('myLeave.amend.before')}</th>
                    <th scope="col">{t('myLeave.amend.after')}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <th scope="row">{t('myLeave.amend.summary.policy')}</th>
                    <td data-testid="amend-before-policy">
                      <span lang={language}>{request.policyNames[language]}</span>{' '}
                      <span className="muted">({request.policyCode})</span>
                    </td>
                    <td data-testid="amend-after-policy">
                      {chosen ? (
                        <>
                          <span lang={language}>{chosen.names[language]}</span>{' '}
                          <span className="muted">({chosen.code})</span>
                          <br />
                          <span className="muted" lang={other}>
                            {chosen.names[other]}
                          </span>
                        </>
                      ) : (
                        <span lang={language}>{request.policyNames[language]}</span>
                      )}
                    </td>
                  </tr>
                  <tr>
                    <th scope="row">{t('myLeave.amend.summary.period')}</th>
                    <td>{formatPeriod(t, language, request.startDate, request.endDate)}</td>
                    <td data-testid="amend-after-period">
                      {formatPeriod(t, language, form.startDate, form.endDate)}
                    </td>
                  </tr>
                  <tr>
                    <th scope="row">{t('myLeave.amend.summary.amount')}</th>
                    <td>{amountOf(request.amount, request.unit)}</td>
                    <td data-testid="amend-after-amount">
                      {amountOf(parseAmount(form.amount) ?? 0, unit)}
                    </td>
                  </tr>
                </tbody>
              </table>
              <figure className="decision-reason">
                <figcaption>{t('myLeave.amend.reason')}</figcaption>
                {/* The exact normalized reason, as plain text in its own language. */}
                <blockquote lang={form.reasonLocale} data-testid="amend-reason">
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
                  {busy ? t('myLeave.amend.sending') : t('myLeave.amend.confirmButton')}
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
                  {t('myLeave.amend.back')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={close}
                >
                  {t('myLeave.amend.keep')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </dialog>
  );
}

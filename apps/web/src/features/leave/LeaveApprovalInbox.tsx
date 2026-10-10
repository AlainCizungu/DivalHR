import type { LeaveApproval } from '@divalhr/api-client';
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type Ref,
  type SyntheticEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatPeriod } from '../people/history';
import {
  APPROVAL_NETWORK_FAILURE,
  GONE_KINDS,
  REASON_MAX,
  approvalFailureOf,
  codePoints,
  decisionBodyOf,
  decisionProblemsOf,
  normalizeReason,
  type ApprovalFailure,
  type ApprovalScope,
  type DecisionField,
  type DecisionForm,
  type DecisionOutcome,
} from './leaveApprovals';
import { formatAmount } from './myLeave';

type Inbox =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ApprovalFailure }
  | { kind: 'ready'; items: LeaveApproval[]; nextCursor: string | null };

type Dialog = {
  item: LeaveApproval;
  step: 'form' | 'confirm';
  form: DecisionForm;
  problems: Set<DecisionField>;
  failure: ApprovalFailure | null;
};

/** A refused request: the stable message, the named fields, retry delay and reference. */
function ApprovalAlert({
  failure,
  alertRef,
  testId,
}: {
  failure: ApprovalFailure;
  alertRef?: Ref<HTMLDivElement>;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid={testId}
      data-kind={failure.kind}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.fields && failure.fields.length > 0 && (
        <ul>
          {failure.fields.map((field) => (
            <li key={field}>{t(`leaveApprovals.problems.${field}`)}</li>
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

/** The employee's name as plain text (never markup). */
function employeeName(item: LeaveApproval): string {
  return `${item.employee.givenNames} ${item.employee.familyName}`;
}

/**
 * MVP-041B: one approval inbox, for an employee's manager (`/me/leave/approvals`) or for tenant
 * administrators (`/admin/leave-approvals`); MVP-041E: the tenant administrators' routing
 * exceptions (`/admin/leave-routing-exceptions`), decided as an explicit override. The server decides what each inbox contains; the
 * page lists it, opens a decision form in a modal dialog, asks for an explicit confirmation, then
 * sends one decision with a stable idempotency key. Names and reasons are plain text; nothing is
 * kept in browser storage or put in a URL.
 */
export function LeaveApprovalInbox({ scope }: { scope: ApprovalScope }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const decisionKey = useIdempotencyKey();
  const [inbox, setInbox] = useState<Inbox>({ kind: 'loading' });
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  // R86-1: one page request at a time; a second activation requests nothing.
  const paging = useRef(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const decided = useRef(false);
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const other = language === 'fr' ? 'en' : 'fr';
  const path =
    scope === 'manager'
      ? '/me/leave-approvals'
      : scope === 'admin'
        ? '/leave-approvals'
        : '/leave-routing-exceptions';
  const override = scope === 'exception';
  const prefix = `leaveApprovals.${scope}`;

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Inbox> => {
      try {
        const { data, error, response } = await core.GET(path, {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: approvalFailureOf(response, error, scope) };
      } catch {
        return { kind: 'failed', failure: APPROVAL_NETWORK_FAILURE };
      }
    },
    [core, path, scope],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setInbox(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    try {
      const next = await fetchPage(cursor);
      setInbox((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  // The native modal dialog follows the dialog state; focus goes to the step's first control.
  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (dialog && !element.open) element.showModal();
    if (!dialog && element.open) element.close();
  }, [dialog]);

  const step = dialog?.step;
  const failureShown = dialog?.failure;
  const problemCount = dialog?.problems.size ?? 0;
  useEffect(() => {
    if (!step) return;
    requestAnimationFrame(() => {
      if (failureShown) failureRef.current?.focus();
      else if (problemCount > 0) problemsRef.current?.focus();
      else if (step === 'confirm') confirmRef.current?.focus();
      else reasonRef.current?.focus();
    });
  }, [step, failureShown, problemCount]);

  const removeItem = (id: string) => {
    setInbox((previous) =>
      previous.kind === 'ready'
        ? { ...previous, items: previous.items.filter((item) => item.id !== id) }
        : previous,
    );
  };

  const open = (item: LeaveApproval, decision: DecisionOutcome, button: HTMLButtonElement) => {
    opener.current = button;
    decided.current = false;
    setAnnouncement('');
    setDialog({
      item,
      step: 'form',
      form: { decision, reasonLocale: language, reason: '' },
      problems: new Set(),
      failure: null,
    });
  };

  /** Closing returns focus to the opening button, or to the heading when the item is gone. */
  const onClosed = () => {
    const current = dialog;
    setDialog(null);
    const gone =
      decided.current || (current?.failure ? GONE_KINDS.has(current.failure.kind) : false);
    if (current && gone) removeItem(current.item.id);
    requestAnimationFrame(() => {
      if (!gone && opener.current?.isConnected) opener.current.focus();
      else headingRef.current?.focus();
    });
  };

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  const update = (change: Partial<DecisionForm>) => {
    setDialog((previous) =>
      previous ? { ...previous, form: { ...previous.form, ...change } } : previous,
    );
  };

  const review = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!dialog) return;
    const problems = decisionProblemsOf(dialog.form);
    setDialog({
      ...dialog,
      problems,
      failure: null,
      step: problems.size > 0 ? 'form' : 'confirm',
    });
  };

  const send = async () => {
    if (!dialog || busy) return;
    const { item } = dialog;
    const body = decisionBodyOf(dialog.form);
    setBusy(true);
    setDialog({ ...dialog, failure: null });
    try {
      const options = {
        params: {
          path: { requestId: item.id },
          // The same decision keeps its key across retries; any edit is a new key.
          header: { 'Idempotency-Key': decisionKey.keyFor({ requestId: item.id, body }) },
        },
        body,
      };
      const { data, error, response } =
        scope === 'manager'
          ? await core.POST('/me/leave-approvals/{requestId}/decision', options)
          : scope === 'admin'
            ? await core.POST('/leave-approvals/{requestId}/decision', options)
            : await core.POST('/leave-routing-exceptions/{requestId}/decision', options);
      if (data) {
        decisionKey.reset();
        decided.current = true;
        setAnnouncement(
          t(`leaveApprovals.decided.${data.state}`, {
            name: employeeName(item),
            period: formatPeriod(t, language, item.startDate, item.endDate),
          }),
        );
        dialogRef.current?.close();
        return;
      }
      const refused = approvalFailureOf(response, error, scope);
      // MVP-041E: an exception that left the queue is announced, then removed on close.
      if (override && refused.kind === 'unavailable') setAnnouncement(t(refused.messageKey));
      setDialog((previous) =>
        previous
          ? {
              ...previous,
              failure: refused,
              step: refused.kind === 'validation' ? 'form' : previous.step,
              problems:
                refused.kind === 'validation' && refused.fields
                  ? new Set(refused.fields)
                  : previous.problems,
            }
          : previous,
      );
    } catch {
      setDialog((previous) =>
        previous ? { ...previous, failure: APPROVAL_NETWORK_FAILURE } : previous,
      );
    } finally {
      setBusy(false);
    }
  };

  const amountText = (item: LeaveApproval) =>
    t(`myLeave.amount.${item.unit}`, { amount: formatAmount(language, item.amount) });

  const fieldId = (field: DecisionField) => `${ids}-${field}`;
  const describedBy = (field: DecisionField, help: boolean) =>
    [
      help ? `${fieldId(field)}-help` : null,
      dialog?.problems.has(field) ? `${fieldId(field)}-error` : null,
    ]
      .filter(Boolean)
      .join(' ') || undefined;
  const fieldError = (field: DecisionField) =>
    dialog?.problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`leaveApprovals.problems.${field}`)}
      </p>
    ) : null;

  const summary = (item: LeaveApproval) => (
    <dl className="decision-summary" data-testid="decision-summary">
      <dt>{t('leaveApprovals.columns.employee')}</dt>
      <dd>
        {employeeName(item)} <span className="muted">({item.employee.employeeNumber})</span>
      </dd>
      <dt>{t('leaveApprovals.columns.policy')}</dt>
      <dd>
        <span lang={language}>{item.policyNames[language]}</span>{' '}
        <span className="muted">({item.policyCode})</span>
      </dd>
      <dt>{t('leaveApprovals.columns.period')}</dt>
      <dd>{formatPeriod(t, language, item.startDate, item.endDate)}</dd>
      <dt>{t('leaveApprovals.columns.amount')}</dt>
      <dd>{amountText(item)}</dd>
    </dl>
  );

  const reasonLength = dialog ? codePoints(normalizeReason(dialog.form.reason)) : 0;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="leave-approvals" data-scope={scope}>
      <PageHeader
        titleId={`${ids}-title`}
        title={t(`${prefix}.title`)}
        description={t(`${prefix}.description`)}
      />
      <div className="card" data-testid="approvals-scope">
        <p>{t('leaveApprovals.scope')}</p>
        {scope === 'manager' && <p className="muted">{t('leaveApprovals.manager.routing')}</p>}
        {override && (
          <p className="override-notice" data-testid="override-notice">
            <strong>{t('leaveApprovals.exception.override')}</strong>
          </p>
        )}
      </div>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-inbox`} className="card">
        <h2 id={`${ids}-inbox`} ref={headingRef} tabIndex={-1}>
          {t('leaveApprovals.inbox.title')}
        </h2>
        {inbox.kind === 'loading' && <p role="status">{t('leaveApprovals.inbox.loading')}</p>}
        {inbox.kind === 'failed' && <ApprovalAlert failure={inbox.failure} testId="inbox-error" />}
        {inbox.kind === 'ready' && inbox.items.length === 0 && (
          <p data-testid="inbox-empty">{t(`${prefix}.empty`)}</p>
        )}
        {inbox.kind === 'ready' && inbox.items.length > 0 && (
          <ul className="plain-list approval-list" data-testid="approval-items">
            {inbox.items.map((item) => (
              <li
                key={item.id}
                className="hierarchy-item approval-item"
                data-testid="approval-item"
              >
                <h3>{employeeName(item)}</h3>
                {override && (
                  <p className="badge" data-testid="exception-reason">
                    {t('leaveApprovals.exception.reason')}
                  </p>
                )}
                <p className="muted">
                  {t('leaveApprovals.employeeNumber', { number: item.employee.employeeNumber })}
                </p>
                <p>
                  <span lang={language}>{item.policyNames[language]}</span>{' '}
                  <span className="muted">({item.policyCode})</span>
                  <br />
                  <span className="muted" lang={other}>
                    {item.policyNames[other]}
                  </span>
                </p>
                <ul className="meta">
                  <li data-testid="approval-period">
                    {formatPeriod(t, language, item.startDate, item.endDate)}
                  </li>
                  <li data-testid="approval-amount">{amountText(item)}</li>
                  <li>
                    {t('leaveApprovals.submitted', {
                      date: new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(
                        new Date(item.submittedAt),
                      ),
                    })}
                  </li>
                </ul>
                <div className="approval-actions">
                  <button
                    type="button"
                    className="button"
                    disabled={busy || dialog !== null}
                    aria-label={t('leaveApprovals.actions.approveFor', {
                      name: employeeName(item),
                    })}
                    onClick={(event) => {
                      open(item, 'APPROVED', event.currentTarget);
                    }}
                  >
                    {t('leaveApprovals.actions.approve')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy || dialog !== null}
                    aria-label={t('leaveApprovals.actions.rejectFor', { name: employeeName(item) })}
                    onClick={(event) => {
                      open(item, 'REJECTED', event.currentTarget);
                    }}
                  >
                    {t('leaveApprovals.actions.reject')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {inbox.kind === 'ready' && inbox.nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={loadingMore}
              aria-busy={loadingMore ? true : undefined}
              onClick={() => {
                if (inbox.nextCursor) void more(inbox.nextCursor);
              }}
            >
              {loadingMore ? t('leaveApprovals.inbox.loadingMore') : t('leaveApprovals.inbox.more')}
            </button>
          </p>
        )}
      </section>

      <dialog
        ref={dialogRef}
        className="decision-dialog"
        aria-labelledby={`${ids}-dialog-title`}
        data-testid="decision-dialog"
        onClose={onClosed}
        onCancel={(event) => {
          // Esc closes the dialog, except while the decision is being sent.
          if (busy) event.preventDefault();
        }}
      >
        {dialog && (
          <div className="decision-dialog__panel">
            <h2 id={`${ids}-dialog-title`}>
              {t(
                dialog.step === 'form'
                  ? `leaveApprovals.dialog.title.${dialog.form.decision}`
                  : `leaveApprovals.dialog.confirmTitle.${dialog.form.decision}`,
              )}
            </h2>
            {override && (
              <p className="override-notice" data-testid="dialog-override-notice">
                <strong>{t('leaveApprovals.exception.override')}</strong>
              </p>
            )}
            {summary(dialog.item)}
            {dialog.failure && (
              <ApprovalAlert
                failure={dialog.failure}
                alertRef={failureRef}
                testId="decision-error"
              />
            )}
            {dialog.step === 'form' && (
              <form noValidate onSubmit={review}>
                {dialog.problems.size > 0 && !dialog.failure && (
                  <div
                    className="error-summary"
                    role="alert"
                    tabIndex={-1}
                    ref={problemsRef}
                    data-testid="decision-problems"
                  >
                    <p>{t('employees.form.problems.title')}</p>
                    <ul>
                      {[...dialog.problems].map((problem) => (
                        <li key={problem}>
                          <a href={`#${fieldId(problem)}`}>
                            {t(`leaveApprovals.problems.${problem}`)}
                          </a>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                <div className="field">
                  <label htmlFor={fieldId('reason')}>{t('leaveApprovals.form.reason')}</label>
                  <p id={`${fieldId('reason')}-help`} className="field__help">
                    {t('leaveApprovals.form.reasonHelp', { max: REASON_MAX })}
                  </p>
                  {fieldError('reason')}
                  <textarea
                    id={fieldId('reason')}
                    ref={reasonRef}
                    rows={4}
                    lang={dialog.form.reasonLocale}
                    value={dialog.form.reason}
                    autoComplete="off"
                    aria-invalid={dialog.problems.has('reason') ? true : undefined}
                    aria-describedby={describedBy('reason', true)}
                    onChange={(event) => {
                      update({ reason: event.target.value });
                    }}
                  />
                  <p className="field__help" data-testid="reason-count">
                    {t('leaveApprovals.form.count', { count: reasonLength, max: REASON_MAX })}
                  </p>
                </div>
                <div className="field">
                  <label htmlFor={fieldId('reasonLocale')}>
                    {t('leaveApprovals.form.reasonLocale')}
                  </label>
                  <p id={`${fieldId('reasonLocale')}-help`} className="field__help">
                    {t('leaveApprovals.form.reasonLocaleHelp')}
                  </p>
                  {fieldError('reasonLocale')}
                  <select
                    id={fieldId('reasonLocale')}
                    value={dialog.form.reasonLocale}
                    aria-invalid={dialog.problems.has('reasonLocale') ? true : undefined}
                    aria-describedby={describedBy('reasonLocale', true)}
                    onChange={(event) => {
                      update({ reasonLocale: event.target.value === 'en' ? 'en' : 'fr' });
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
                  <button type="submit" className="button" disabled={busy}>
                    {t('leaveApprovals.form.review')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={close}
                  >
                    {t('leaveApprovals.form.cancel')}
                  </button>
                </div>
              </form>
            )}
            {dialog.step === 'confirm' && (
              <div data-testid="decision-confirm">
                <p>
                  {t(
                    override
                      ? `leaveApprovals.exception.confirm.${dialog.form.decision}`
                      : `leaveApprovals.dialog.confirm.${dialog.form.decision}`,
                    { name: employeeName(dialog.item) },
                  )}
                </p>
                <figure className="decision-reason">
                  <figcaption>{t('leaveApprovals.form.reason')}</figcaption>
                  <blockquote lang={dialog.form.reasonLocale} data-testid="decision-reason">
                    {normalizeReason(dialog.form.reason)}
                  </blockquote>
                </figure>
                <div className="dialog-actions">
                  <button
                    type="button"
                    className="button"
                    ref={confirmRef}
                    disabled={
                      busy || (dialog.failure ? GONE_KINDS.has(dialog.failure.kind) : false)
                    }
                    aria-busy={busy ? true : undefined}
                    onClick={() => void send()}
                  >
                    {busy
                      ? t('leaveApprovals.dialog.sending')
                      : t(
                          override
                            ? `leaveApprovals.exception.confirmButton.${dialog.form.decision}`
                            : `leaveApprovals.dialog.confirmButton.${dialog.form.decision}`,
                        )}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={() => {
                      setDialog({ ...dialog, step: 'form', failure: null });
                    }}
                  >
                    {t('leaveApprovals.dialog.back')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={close}
                  >
                    {t('leaveApprovals.form.cancel')}
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </dialog>
    </section>
  );
}

/** The manager's inbox (« Approbations de congé » under My space). */
export function MyLeaveApprovalsPage() {
  return <LeaveApprovalInbox scope="manager" />;
}

/** The tenant administrators' inbox (« Approbations de congé » under People). */
export function LeaveApprovalsPage() {
  return <LeaveApprovalInbox scope="admin" />;
}

/**
 * MVP-041E: the routing exceptions (« Exceptions d’acheminement des congés » under People),
 * decided by a tenant administrator as an override because no manager is eligible.
 */
export function LeaveRoutingExceptionsPage() {
  return <LeaveApprovalInbox scope="exception" />;
}

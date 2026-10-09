import type { MyLeavePolicy, MyLeaveRequest } from '@divalhr/api-client';
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
import { PageHeader, StatusBadge, type StatusTone } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatDate, formatPeriod } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { toneOf } from './leavePolicies';
import { MyLeaveCancelDialog } from './MyLeaveCancelDialog';
import {
  EMPTY_REQUEST,
  MY_LEAVE_NETWORK_FAILURE,
  formatAmount,
  myLeaveFailureOf,
  requestBodyOf,
  requestProblemsOf,
  selectedPolicy,
  type LeaveRequestForm,
  type MyLeaveFailure,
  type RequestField,
} from './myLeave';

type Policies =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: MyLeaveFailure }
  | {
      kind: 'ready';
      items: MyLeavePolicy[];
      nextCursor: string | null;
      asOf: string;
      timezone: string;
    };

/** MVP-041B/C: the tone of each state (the text always carries the meaning). */
const STATE_TONES: Record<MyLeaveRequest['state'], StatusTone> = {
  PENDING: 'info',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
};

type History =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: MyLeaveFailure }
  | { kind: 'ready'; items: MyLeaveRequest[]; nextCursor: string | null };

/** A refused request: the stable message, the named fields, retry delay and reference. */
function MyLeaveAlert({
  failure,
  alertRef,
  testId,
}: {
  failure: MyLeaveFailure;
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
            <li key={field}>{t(`myLeave.problems.${field}`)}</li>
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
 * MVP-041A: « Mes congés » / "My leave". The employee reviews the policies they can request,
 * submits a pending request and sees their own requests; from MVP-041B, each decided request shows
 * its outcome and the approver's reason in the language it was written in (never who decided);
 * from MVP-041C, the employee cancels a pending request with a reason (MyLeaveCancelDialog) and
 * sees it as cancelled with that reason. No working day, holiday or balance is calculated; nothing
 * is kept in browser storage or put in a URL.
 */
export function MyLeavePage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const createKey = useIdempotencyKey();
  const cancelKey = useIdempotencyKey();
  const [policies, setPolicies] = useState<Policies>({ kind: 'loading' });
  const [history, setHistory] = useState<History>({ kind: 'loading' });
  const [form, setForm] = useState<LeaveRequestForm>(EMPTY_REQUEST);
  const [problems, setProblems] = useState<Set<RequestField>>(new Set());
  const [failure, setFailure] = useState<MyLeaveFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const failureBox = useRef<HTMLDivElement>(null);
  // MVP-041C: the request being cancelled (one at a time), the button that opened it, and the
  // history heading that takes focus when that button is gone.
  const [cancelTarget, setCancelTarget] = useState<MyLeaveRequest | null>(null);
  const cancelOpener = useRef<HTMLButtonElement | null>(null);
  const historyHeading = useRef<HTMLHeadingElement>(null);
  const problemsBox = useRef<HTMLDivElement>(null);
  // R86-1: one page request at a time per list; a second activation requests nothing.
  const pagingPolicies = useRef(false);
  const pagingHistory = useRef(false);
  const [loadingPolicies, setLoadingPolicies] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const other = language === 'fr' ? 'en' : 'fr';

  const fetchPolicies = useCallback(
    async (cursor: string | null): Promise<Policies> => {
      try {
        const { data, error, response } = await core.GET('/me/leave-policies', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? {
              kind: 'ready',
              items: data.items,
              nextCursor: data.nextCursor,
              asOf: data.asOf,
              timezone: data.timezone,
            }
          : { kind: 'failed', failure: myLeaveFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: MY_LEAVE_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const fetchHistory = useCallback(
    async (cursor: string | null): Promise<History> => {
      try {
        const { data, error, response } = await core.GET('/me/leave-requests', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: myLeaveFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: MY_LEAVE_NETWORK_FAILURE };
      }
    },
    [core],
  );

  useEffect(() => {
    let active = true;
    void fetchPolicies(null).then((next) => {
      if (active) setPolicies(next);
    });
    void fetchHistory(null).then((next) => {
      if (active) setHistory(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPolicies, fetchHistory]);

  useEffect(() => {
    if (failure) failureBox.current?.focus();
  }, [failure]);

  const morePolicies = async (cursor: string) => {
    if (pagingPolicies.current) return;
    pagingPolicies.current = true;
    setLoadingPolicies(true);
    try {
      const next = await fetchPolicies(cursor);
      setPolicies((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      pagingPolicies.current = false;
      setLoadingPolicies(false);
    }
  };

  const moreHistory = async (cursor: string) => {
    if (pagingHistory.current) return;
    pagingHistory.current = true;
    setLoadingHistory(true);
    try {
      const next = await fetchHistory(cursor);
      setHistory((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      pagingHistory.current = false;
      setLoadingHistory(false);
    }
  };

  const set = (key: keyof LeaveRequestForm, value: string) => {
    setForm((previous) => ({ ...previous, [key]: value }));
  };

  const available = policies.kind === 'ready' ? policies.items : [];
  const chosen = selectedPolicy(available, form.policyId);

  const onSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = requestProblemsOf(form, policies.kind === 'ready' ? policies.asOf : null);
    setProblems(found);
    setAnnouncement('');
    if (found.size > 0) {
      setFailure(null);
      requestAnimationFrame(() => problemsBox.current?.focus());
      return;
    }
    const body = requestBodyOf(form);
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/me/leave-requests', {
        body,
        // The same command keeps its key across retries; any edit is a new command (new key).
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
      });
      if (data) {
        createKey.reset();
        setForm(EMPTY_REQUEST);
        setProblems(new Set());
        setAnnouncement(
          t('myLeave.submitted', {
            name: data.policyNames[language],
            period: formatPeriod(t, language, data.startDate, data.endDate),
          }),
        );
        setHistory(await fetchHistory(null));
        return;
      }
      const refused = myLeaveFailureOf(response, error);
      if (refused.kind === 'validation' && refused.fields) setProblems(new Set(refused.fields));
      setFailure(refused);
    } catch {
      setFailure(MY_LEAVE_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const fieldId = (field: RequestField) => `${ids}-${field}`;
  const invalid = (field: RequestField) => (problems.has(field) ? true : undefined);
  const describedBy = (field: RequestField, help?: boolean) =>
    [help ? `${fieldId(field)}-help` : null, problems.has(field) ? `${fieldId(field)}-error` : null]
      .filter(Boolean)
      .join(' ') || undefined;
  const fieldError = (field: RequestField) =>
    problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`myLeave.problems.${field}`)}
      </p>
    ) : null;

  const refreshHistory = () => {
    void fetchHistory(null).then(setHistory);
  };

  const onCancelled = (request: MyLeaveRequest) => {
    cancelKey.reset();
    setAnnouncement(
      t('myLeave.cancel.done', {
        name: request.policyNames[language],
        period: formatPeriod(t, language, request.startDate, request.endDate),
      }),
    );
    refreshHistory();
  };

  /** Closing returns focus to the opening button, or to the history heading when it is gone. */
  const onCancelClosed = (gone: boolean) => {
    setCancelTarget(null);
    requestAnimationFrame(() => {
      if (!gone && cancelOpener.current?.isConnected) cancelOpener.current.focus();
      else historyHeading.current?.focus();
    });
  };

  const amountText = (amount: number, unit: MyLeavePolicy['unit']) =>
    t(`myLeave.amount.${unit}`, { amount: formatAmount(language, amount) });

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-leave">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('myLeave.title')}
        description={t('myLeave.description')}
      />
      <div className="card" data-testid="my-leave-scope">
        <p>{t('myLeave.notCalculated')}</p>
        <p className="muted">{t('myLeave.reviewed')}</p>
      </div>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-policies`} className="card">
        <h2 id={`${ids}-policies`}>{t('myLeave.policies.title')}</h2>
        {policies.kind === 'loading' && <p role="status">{t('myLeave.policies.loading')}</p>}
        {policies.kind === 'failed' && (
          <MyLeaveAlert failure={policies.failure} testId="policies-error" />
        )}
        {policies.kind === 'ready' && (
          <p className="muted" data-testid="as-of">
            {t('myLeave.policies.asOf', {
              date: formatDate(language, policies.asOf),
              timezone: policies.timezone,
            })}
          </p>
        )}
        {policies.kind === 'ready' && policies.items.length === 0 && (
          <p data-testid="policies-empty">{t('myLeave.policies.empty')}</p>
        )}
        {policies.kind === 'ready' && policies.items.length > 0 && (
          <ul className="plain-list" data-testid="my-policies">
            {policies.items.map((policy) => (
              <li key={policy.id} className="hierarchy-item" data-testid="my-policy">
                <h3>
                  <span lang={language}>{policy.names[language]}</span>{' '}
                  <StatusBadge tone={toneOf(policy.status)} testId="policy-status">
                    {t(`leavePolicies.status.${policy.status}`)}
                  </StatusBadge>
                </h3>
                <p className="muted" lang={other}>
                  {policy.names[other]}
                </p>
                <ul className="meta">
                  <li>{t(`leavePolicies.values.unit.${policy.unit}`)}</li>
                  <li>
                    {policy.minimumServiceDays === 0
                      ? t('leavePolicies.minimumServiceNone')
                      : t('leavePolicies.minimumService', { count: policy.minimumServiceDays })}
                  </li>
                  <li>{t(`leavePolicies.values.approvalRoute.${policy.approvalRoute}`)}</li>
                  <li>{t(`leavePolicies.values.payrollEffect.${policy.payrollEffect}`)}</li>
                  <li>{formatPeriod(t, language, policy.effectiveFrom, policy.effectiveTo)}</li>
                </ul>
              </li>
            ))}
          </ul>
        )}
        {policies.kind === 'ready' && policies.nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={loadingPolicies}
              aria-busy={loadingPolicies ? true : undefined}
              onClick={() => {
                if (policies.nextCursor) void morePolicies(policies.nextCursor);
              }}
            >
              {loadingPolicies ? t('myLeave.policies.loadingMore') : t('myLeave.policies.more')}
            </button>
          </p>
        )}
      </section>

      <section aria-labelledby={`${ids}-create`} className="card" data-testid="request-leave">
        <h2 id={`${ids}-create`}>{t('myLeave.form.title')}</h2>
        <p className="muted">{t('myLeave.form.intro')}</p>
        {problems.size > 0 && !failure && (
          <div
            className="error-summary"
            role="alert"
            tabIndex={-1}
            ref={problemsBox}
            data-testid="request-problems"
          >
            <p>{t('employees.form.problems.title')}</p>
            <ul>
              {[...problems].map((problem) => (
                <li key={problem}>
                  <a href={`#${fieldId(problem)}`}>{t(`myLeave.problems.${problem}`)}</a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {failure && <MyLeaveAlert failure={failure} alertRef={failureBox} testId="request-error" />}
        <form noValidate onSubmit={(event) => void onSubmit(event)}>
          <div className="field">
            <label htmlFor={fieldId('policy')}>{t('myLeave.form.policy')}</label>
            {fieldError('policy')}
            <select
              id={fieldId('policy')}
              value={form.policyId}
              aria-invalid={invalid('policy')}
              aria-describedby={describedBy('policy')}
              onChange={(event) => {
                set('policyId', event.target.value);
              }}
            >
              <option value="">{t('myLeave.form.choose')}</option>
              {available.map((policy) => (
                <option key={policy.id} value={policy.id}>
                  {t('myLeave.form.policyOption', {
                    name: policy.names[language],
                    code: policy.code,
                  })}
                </option>
              ))}
            </select>
            {chosen && (
              <p className="field__help" data-testid="chosen-policy">
                <span lang={other}>{chosen.names[other]}</span>
                {' · '}
                {t(`leavePolicies.values.unit.${chosen.unit}`)}
              </p>
            )}
          </div>
          <div className="field">
            <label htmlFor={fieldId('startDate')}>{t('myLeave.form.startDate')}</label>
            {fieldError('startDate')}
            <input
              id={fieldId('startDate')}
              type="date"
              value={form.startDate}
              min={policies.kind === 'ready' ? policies.asOf : undefined}
              max="2999-12-31"
              aria-invalid={invalid('startDate')}
              aria-describedby={describedBy('startDate')}
              onChange={(event) => {
                set('startDate', event.target.value);
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
                set('endDate', event.target.value);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={fieldId('amount')}>
              {chosen ? t(`myLeave.form.amount.${chosen.unit}`) : t('myLeave.form.amount.UNKNOWN')}
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
                set('amount', event.target.value);
              }}
            />
          </div>
          <button type="submit" className="button" disabled={busy}>
            {busy ? t('myLeave.form.submitting') : t('myLeave.form.submit')}
          </button>
        </form>
      </section>

      <section aria-labelledby={`${ids}-history`} className="card">
        <h2 id={`${ids}-history`} ref={historyHeading} tabIndex={-1}>
          {t('myLeave.history.title')}
        </h2>
        {history.kind === 'loading' && <p role="status">{t('myLeave.history.loading')}</p>}
        {history.kind === 'failed' && (
          <MyLeaveAlert failure={history.failure} testId="history-error" />
        )}
        {history.kind === 'ready' && history.items.length === 0 && (
          <p data-testid="history-empty">{t('myLeave.history.empty')}</p>
        )}
        {history.kind === 'ready' && history.items.length > 0 && (
          <ScrollRegion label={t('myLeave.history.list')}>
            <table data-testid="my-requests">
              <caption className="visually-hidden">{t('myLeave.history.list')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('myLeave.history.columns.policy')}</th>
                  <th scope="col">{t('myLeave.history.columns.period')}</th>
                  <th scope="col">{t('myLeave.history.columns.amount')}</th>
                  <th scope="col">{t('myLeave.history.columns.submitted')}</th>
                  <th scope="col">{t('myLeave.history.columns.state')}</th>
                  <th scope="col">{t('myLeave.history.columns.decision')}</th>
                </tr>
              </thead>
              <tbody>
                {history.items.map((request) => (
                  <tr key={request.id} data-testid="my-request">
                    <th scope="row">
                      <span lang={language}>{request.policyNames[language]}</span>
                      <br />
                      <span className="muted" lang={other}>
                        {request.policyNames[other]}
                      </span>
                    </th>
                    <td>{formatPeriod(t, language, request.startDate, request.endDate)}</td>
                    <td>{amountText(request.amount, request.unit)}</td>
                    <td>
                      {new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(
                        new Date(request.submittedAt),
                      )}
                    </td>
                    <td>
                      <StatusBadge tone={STATE_TONES[request.state]} testId="request-state">
                        {t(`myLeave.state.${request.state}`)}
                      </StatusBadge>
                    </td>
                    <td data-testid="request-decision">
                      {request.decision ? (
                        <>
                          {/* The reason as written, in its own language: plain text, never markup. */}
                          <p className="request-reason" lang={request.decision.reasonLocale}>
                            {request.decision.reason}
                          </p>
                          <p className="muted">
                            {t('myLeave.history.decidedOn', {
                              date: new Intl.DateTimeFormat(language, {
                                dateStyle: 'medium',
                              }).format(new Date(request.decision.decidedAt)),
                            })}
                          </p>
                        </>
                      ) : request.cancellation ? (
                        <>
                          {/* The employee's own reason as written: plain text, never markup. */}
                          <p className="request-reason" lang={request.cancellation.reasonLocale}>
                            {request.cancellation.reason}
                          </p>
                          <p className="muted">
                            {t('myLeave.history.cancelledOn', {
                              date: new Intl.DateTimeFormat(language, {
                                dateStyle: 'medium',
                              }).format(new Date(request.cancellation.cancelledAt)),
                            })}
                          </p>
                        </>
                      ) : (
                        <>
                          <p className="muted">{t('myLeave.history.awaiting')}</p>
                          {request.state === 'PENDING' && (
                            <button
                              type="button"
                              className="button button--secondary"
                              data-testid="cancel-request"
                              disabled={cancelTarget !== null}
                              aria-label={t('myLeave.cancel.actionFor', {
                                name: request.policyNames[language],
                                period: formatPeriod(
                                  t,
                                  language,
                                  request.startDate,
                                  request.endDate,
                                ),
                              })}
                              onClick={(event) => {
                                cancelOpener.current = event.currentTarget;
                                setAnnouncement('');
                                setCancelTarget(request);
                              }}
                            >
                              {t('myLeave.cancel.action')}
                            </button>
                          )}
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {history.kind === 'ready' && history.nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={loadingHistory}
              aria-busy={loadingHistory ? true : undefined}
              onClick={() => {
                if (history.nextCursor) void moreHistory(history.nextCursor);
              }}
            >
              {loadingHistory ? t('myLeave.history.loadingMore') : t('myLeave.history.more')}
            </button>
          </p>
        )}
      </section>

      <MyLeaveCancelDialog
        key={cancelTarget?.id ?? 'closed'}
        request={cancelTarget}
        keyFor={cancelKey.keyFor}
        onCancelled={(_receipt, request) => {
          onCancelled(request);
        }}
        onSettled={refreshHistory}
        onClosed={onCancelClosed}
      />
    </section>
  );
}

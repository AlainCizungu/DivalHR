import type { Expense, ExpenseDetail } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PageHeader } from '../../ui/primitives';
import {
  ApprovalNotice,
  ExpenseAlert,
  ExpenseFacts,
  ExpenseHistory,
  StateBadge,
} from './ExpenseBits';
import {
  NETWORK_FAILURE,
  REASON_MAX,
  REASON_MIN,
  codePoints,
  expenseFailureOf,
  formatAmount,
  formatDate,
  formatInstant,
  normalizeText,
  textAcceptable,
  type ExpenseFailure,
} from './expenses';

type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: Expense[] }
  | { kind: 'failed'; failure: ExpenseFailure };

const MAX_PAGES = 5;

/**
 * MVP-081: « Notes de frais à approuver » / "Expenses to approve". The manager sees only the
 * submitted expenses of the employees who report to them (checked by the server on every request),
 * approves one after an explicit confirmation, or returns it with a reason. Approval is not
 * reimbursement or payment. Amounts of different currencies are listed side by side, never
 * totalled.
 */
export function ExpenseApprovalsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const approveKey = useIdempotencyKey();
  const returnKey = useIdempotencyKey();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [detail, setDetail] = useState<ExpenseDetail | null>(null);
  const [mode, setMode] = useState<'view' | 'approve' | 'return'>('view');
  const [reason, setReason] = useState('');
  const [reasonInvalid, setReasonInvalid] = useState(false);
  const [failure, setFailure] = useState<ExpenseFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const sending = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);

  const fetchAll = useCallback(async (): Promise<Load> => {
    try {
      const items: Expense[] = [];
      let cursor: string | undefined;
      for (let page = 0; page < MAX_PAGES; page += 1) {
        const { data, error, response } = await core.GET('/me/expense-approvals', {
          params: { query: { limit: 100, ...(cursor ? { cursor } : {}) } },
          cache: 'no-store',
        });
        if (!data) return { kind: 'failed', failure: expenseFailureOf(response, error) };
        items.push(...data.items);
        cursor = data.nextCursor ?? undefined;
        if (!cursor) break;
      }
      return { kind: 'ready', items };
    } catch {
      return { kind: 'failed', failure: NETWORK_FAILURE };
    }
  }, [core]);

  useEffect(() => {
    let active = true;
    void fetchAll().then((next) => {
      if (active) setLoad(next);
    });
    return () => {
      active = false;
    };
  }, [fetchAll]);

  useEffect(() => {
    if (failure) alertRef.current?.focus();
  }, [failure]);

  useEffect(() => {
    if (mode === 'return') reasonRef.current?.focus();
  }, [mode]);

  useEffect(() => {
    headingRef.current?.focus();
  }, [detail?.expense.id]);

  const open = async (id: string) => {
    setFailure(null);
    setMode('view');
    try {
      const { data, error, response } = await core.GET('/me/expense-approvals/{expenseId}', {
        params: { path: { expenseId: id } },
        cache: 'no-store',
      });
      if (data) setDetail(data);
      else setFailure(expenseFailureOf(response, error));
    } catch {
      setFailure(NETWORK_FAILURE);
    }
  };

  const backToQueue = async () => {
    setDetail(null);
    setMode('view');
    setReason('');
    setReasonInvalid(false);
    setLoad(await fetchAll());
  };

  const decide = async (expense: Expense, action: 'approve' | 'return') => {
    if (sending.current) return;
    const text = normalizeText(reason);
    if (action === 'return' && !textAcceptable(text, REASON_MIN, REASON_MAX)) {
      setReasonInvalid(true);
      reasonRef.current?.focus();
      return;
    }
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const params = { path: { expenseId: expense.id } };
      const result =
        action === 'approve'
          ? await core.POST('/me/expense-approvals/{expenseId}/approve', {
              params: {
                ...params,
                header: {
                  'Idempotency-Key': approveKey.keyFor({ id: expense.id, v: expense.version }),
                },
              },
              body: { expectedVersion: expense.version },
            })
          : await core.POST('/me/expense-approvals/{expenseId}/return', {
              params: {
                ...params,
                header: {
                  'Idempotency-Key': returnKey.keyFor({ id: expense.id, v: expense.version, text }),
                },
              },
              body: { expectedVersion: expense.version, reason: text },
            });
      if (result.data) {
        approveKey.reset();
        returnKey.reset();
        setAnnouncement(
          t(action === 'approve' ? 'expenses.approvals.approved' : 'expenses.approvals.returned'),
        );
        setDetail(null);
        setMode('view');
        setReason('');
        setLoad(await fetchAll());
        return;
      }
      const refused = expenseFailureOf(result.response, result.error);
      setFailure(refused);
      if (['conflict', 'settled', 'notFound', 'route'].includes(refused.kind)) {
        setDetail(null);
        setMode('view');
        setLoad(await fetchAll());
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const items = load.kind === 'ready' ? load.items : [];
  const expense = detail?.expense;
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="expense-approvals">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('expenses.approvals.title')}
        description={t('expenses.approvals.description')}
      />
      <ApprovalNotice />
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>
      {failure && <ExpenseAlert failure={failure} testId="expense-error" alertRef={alertRef} />}

      {!detail && (
        <>
          {load.kind === 'loading' && <p role="status">{t('expenses.loading')}</p>}
          {load.kind === 'failed' && (
            <ExpenseAlert failure={load.failure} testId="expenses-error" />
          )}
          {load.kind === 'ready' && items.length === 0 && (
            <p className="card" data-testid="approvals-empty">
              {t('expenses.approvals.empty')}
            </p>
          )}
          {load.kind === 'ready' && items.length > 0 && (
            <section className="card" aria-labelledby={`${ids}-queue`}>
              <h2 id={`${ids}-queue`}>{t('expenses.approvals.queueTitle')}</h2>
              <ul className="plain-list">
                {items.map((item) => (
                  <li key={item.id} className="hierarchy-item" data-testid="approval-item">
                    <p>
                      <strong>
                        {item.employee.givenNames} {item.employee.familyName}
                      </strong>{' '}
                      ({item.employee.employeeNumber})
                    </p>
                    <p>
                      <span data-testid="expense-amount">
                        {formatAmount(language, item.currency, item.amount)}
                      </span>{' '}
                      · {item.merchant} · {formatDate(language, item.businessDate)}
                    </p>
                    {item.submittedAt && (
                      <p className="muted">
                        {t('expenses.approvals.submittedOn', {
                          date: formatInstant(language, item.submittedAt),
                        })}
                      </p>
                    )}
                    <button
                      type="button"
                      className="button button--secondary"
                      onClick={() => void open(item.id)}
                    >
                      {t('expenses.open', { merchant: item.merchant })}
                    </button>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}

      {detail && expense && (
        <section className="card" aria-labelledby={`${ids}-detail`} data-testid="approval-detail">
          <h2 id={`${ids}-detail`} tabIndex={-1} ref={headingRef}>
            {expense.employee.givenNames} {expense.employee.familyName} (
            {expense.employee.employeeNumber}) <StateBadge state={expense.state} />
          </h2>
          <ExpenseFacts expense={expense} language={language} />
          {mode === 'view' && (
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                disabled={busy}
                data-testid="approve-expense"
                onClick={() => {
                  setMode('approve');
                }}
              >
                {t('expenses.approvals.approve')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                data-testid="return-expense"
                onClick={() => {
                  setMode('return');
                }}
              >
                {t('expenses.approvals.return')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => void backToQueue()}
              >
                {t('expenses.back')}
              </button>
            </div>
          )}
          {mode === 'approve' && (
            <div
              role="group"
              aria-label={t('expenses.approvals.approveTitle')}
              data-testid="approve-confirm"
            >
              <p>
                {t('expenses.approvals.confirmApprove', {
                  amount: formatAmount(language, expense.currency, expense.amount),
                })}
              </p>
              <p className="muted">{t('expenses.notice')}</p>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  data-testid="confirm-approve"
                  onClick={() => void decide(expense, 'approve')}
                >
                  {busy ? t('expenses.sending') : t('expenses.approvals.approveButton')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={() => {
                    setMode('view');
                  }}
                >
                  {t('expenses.me.keep')}
                </button>
              </div>
            </div>
          )}
          {mode === 'return' && (
            <form
              noValidate
              data-testid="return-form"
              onSubmit={(event) => {
                event.preventDefault();
                void decide(expense, 'return');
              }}
            >
              <div className="field">
                <label htmlFor={`${ids}-reason`}>{t('expenses.approvals.reason')}</label>
                <p id={`${ids}-reason-help`} className="field__help">
                  {t('expenses.approvals.reasonHelp', { min: REASON_MIN, max: REASON_MAX })}
                </p>
                {reasonInvalid && (
                  <p id={`${ids}-reason-error`} className="field__error">
                    {t('expenses.problems.reason')}
                  </p>
                )}
                <textarea
                  id={`${ids}-reason`}
                  ref={reasonRef}
                  rows={3}
                  value={reason}
                  autoComplete="off"
                  aria-invalid={reasonInvalid ? true : undefined}
                  aria-describedby={`${ids}-reason-help${reasonInvalid ? ` ${ids}-reason-error` : ''}`}
                  onChange={(event) => {
                    setReason(event.target.value);
                    setReasonInvalid(false);
                  }}
                />
                <p className="field__help">
                  {t('expenses.count', { n: codePoints(normalizeText(reason)), max: REASON_MAX })}
                </p>
              </div>
              <div className="dialog-actions">
                <button
                  type="submit"
                  className="button"
                  disabled={busy}
                  data-testid="confirm-return"
                >
                  {busy ? t('expenses.sending') : t('expenses.approvals.returnButton')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={() => {
                    setMode('view');
                  }}
                >
                  {t('expenses.me.keep')}
                </button>
              </div>
            </form>
          )}
          <ExpenseHistory detail={detail} language={language} scope="approvals" />
        </section>
      )}
    </section>
  );
}

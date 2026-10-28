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
import { ExpenseFormFields } from './ExpenseFormFields';
import {
  NETWORK_FAILURE,
  bodyOf,
  emptyForm,
  expenseFailureOf,
  formatAmount,
  formatDate,
  problemsOf,
  receiptProblemOf,
  type ExpenseFailure,
  type ExpenseField,
  type ExpenseForm,
  type ReceiptProblem,
} from './expenses';

type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: Expense[] }
  | { kind: 'failed'; failure: ExpenseFailure };

type View =
  | { kind: 'list' }
  | { kind: 'form'; editing: Expense | null; prefill?: ExpenseForm }
  | { kind: 'detail'; id: string };

const MAX_PAGES = 5;

function formOf(expense: Expense): ExpenseForm {
  return {
    businessDate: expense.businessDate,
    currency: expense.currency,
    amount: expense.amount,
    category: expense.category,
    merchant: expense.merchant,
    description: expense.description,
  };
}

/**
 * MVP-081: « Mes notes de frais » / "My expenses". The linked employee creates a draft in CDF or
 * USD (never converted or combined), attaches one receipt, submits it for their manager's
 * approval, withdraws it, or corrects a returned one. Approval is not reimbursement or payment.
 * One command at a time, each with a stable idempotency key and the expected version. Merchant,
 * description, reason and file name are shown unchanged and never kept in browser storage or put
 * in a URL.
 */
export function MyExpensesPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const createKey = useIdempotencyKey();
  const updateKey = useIdempotencyKey();
  const receiptKey = useIdempotencyKey();
  const submitKey = useIdempotencyKey();
  const withdrawKey = useIdempotencyKey();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [view, setView] = useState<View>({ kind: 'list' });
  const [detail, setDetail] = useState<ExpenseDetail | null>(null);
  const [form, setForm] = useState<ExpenseForm>(() => emptyForm(new Date()));
  const [problems, setProblems] = useState<Set<ExpenseField>>(new Set());
  const [receiptProblem, setReceiptProblem] = useState<ReceiptProblem | null>(null);
  const [failure, setFailure] = useState<ExpenseFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmWithdraw, setConfirmWithdraw] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const sending = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  const fetchAll = useCallback(async (): Promise<Load> => {
    try {
      const items: Expense[] = [];
      let cursor: string | undefined;
      for (let page = 0; page < MAX_PAGES; page += 1) {
        const { data, error, response } = await core.GET('/me/expenses', {
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

  const loadDetail = useCallback(
    async (id: string): Promise<boolean> => {
      try {
        const { data, error, response } = await core.GET('/me/expenses/{expenseId}', {
          params: { path: { expenseId: id } },
          cache: 'no-store',
        });
        if (!data) {
          setFailure(expenseFailureOf(response, error));
          return false;
        }
        setDetail(data);
        return true;
      } catch {
        setFailure(NETWORK_FAILURE);
        return false;
      }
    },
    [core],
  );

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
    headingRef.current?.focus();
  }, [view.kind]);

  const open = async (id: string) => {
    setFailure(null);
    setConfirmWithdraw(false);
    setReceiptProblem(null);
    if (await loadDetail(id)) setView({ kind: 'detail', id });
  };

  const backToList = async () => {
    setView({ kind: 'list' });
    setDetail(null);
    setFailure(null);
    setProblems(new Set());
    setConfirmWithdraw(false);
    setLoad(await fetchAll());
  };

  /** Runs one command at a time; on a stale or settled answer the screen is refreshed. */
  const command = async <T,>(
    id: string | null,
    run: () => Promise<{ data?: T; error?: unknown; response: Response }>,
    done: (data: T) => Promise<void> | void,
  ) => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const result = await run();
      if (result.data) {
        await done(result.data);
        return;
      }
      const refused = expenseFailureOf(result.response, result.error);
      setFailure(refused);
      if (id && ['conflict', 'settled', 'notFound', 'route'].includes(refused.kind)) {
        await loadDetail(id);
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const startCreate = (prefill?: ExpenseForm) => {
    setFailure(null);
    setProblems(new Set());
    setForm(prefill ?? emptyForm(new Date()));
    setView({ kind: 'form', editing: null, prefill });
  };

  const startEdit = (expense: Expense) => {
    setFailure(null);
    setProblems(new Set());
    setForm(formOf(expense));
    setView({ kind: 'form', editing: expense });
  };

  const save = async () => {
    const found = problemsOf(form, new Date());
    setProblems(found);
    if (found.size > 0) return;
    const body = bodyOf(form);
    if (view.kind !== 'form') return;
    const editing = view.editing;
    if (editing) {
      await command(
        editing.id,
        () =>
          core.POST('/me/expenses/{expenseId}/draft', {
            params: {
              path: { expenseId: editing.id },
              header: {
                'Idempotency-Key': updateKey.keyFor({ id: editing.id, v: editing.version, body }),
              },
            },
            body: { expectedVersion: editing.version, ...body },
          }),
        async () => {
          updateKey.reset();
          setAnnouncement(t('expenses.saved'));
          await loadDetail(editing.id);
          setView({ kind: 'detail', id: editing.id });
        },
      );
      return;
    }
    await command(
      null,
      () =>
        core.POST('/me/expenses', {
          params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
          body,
        }),
      async (created) => {
        createKey.reset();
        setAnnouncement(t('expenses.created'));
        await loadDetail(created.id);
        setView({ kind: 'detail', id: created.id });
      },
    );
  };

  const attach = async (expense: Expense, file: File) => {
    const found = receiptProblemOf(file);
    setReceiptProblem(found);
    if (found) return;
    const bytes = await file.arrayBuffer();
    await command(
      expense.id,
      () =>
        core.PUT('/me/expenses/{expenseId}/receipt', {
          params: {
            path: { expenseId: expense.id },
            header: {
              'Idempotency-Key': receiptKey.keyFor({
                id: expense.id,
                v: expense.version,
                n: file.name,
                s: file.size,
                m: file.lastModified,
              }),
              'X-Expected-Version': expense.version,
              'X-Receipt-File-Name': encodeURIComponent(file.name.normalize('NFC').trim()),
            },
          },
          body: '',
          bodySerializer: () => bytes,
          headers: { 'Content-Type': file.type },
          cache: 'no-store',
        } as never),
      async () => {
        receiptKey.reset();
        setAnnouncement(t('expenses.receiptSaved'));
        await loadDetail(expense.id);
      },
    );
    if (fileRef.current) fileRef.current.value = '';
  };

  const removeReceipt = async (expense: Expense) => {
    await command(
      expense.id,
      () =>
        core.DELETE('/me/expenses/{expenseId}/receipt', {
          params: {
            path: { expenseId: expense.id },
            header: {
              'Idempotency-Key': receiptKey.keyFor({
                id: expense.id,
                v: expense.version,
                remove: true,
              }),
              'X-Expected-Version': expense.version,
            },
          },
        }),
      async () => {
        receiptKey.reset();
        setAnnouncement(t('expenses.receiptRemoved'));
        await loadDetail(expense.id);
      },
    );
  };

  const submit = async (expense: Expense) => {
    await command(
      expense.id,
      () =>
        core.POST('/me/expenses/{expenseId}/submit', {
          params: {
            path: { expenseId: expense.id },
            header: { 'Idempotency-Key': submitKey.keyFor({ id: expense.id, v: expense.version }) },
          },
          body: { expectedVersion: expense.version },
        }),
      async () => {
        submitKey.reset();
        setAnnouncement(t('expenses.submitted'));
        await loadDetail(expense.id);
      },
    );
  };

  const withdraw = async (expense: Expense) => {
    await command(
      expense.id,
      () =>
        core.POST('/me/expenses/{expenseId}/withdraw', {
          params: {
            path: { expenseId: expense.id },
            header: {
              'Idempotency-Key': withdrawKey.keyFor({ id: expense.id, v: expense.version }),
            },
          },
          body: { expectedVersion: expense.version },
        }),
      async () => {
        withdrawKey.reset();
        setConfirmWithdraw(false);
        setAnnouncement(t('expenses.withdrawn'));
        await loadDetail(expense.id);
      },
    );
  };

  const items = load.kind === 'ready' ? load.items : [];
  const expense = detail?.expense;
  const lastReturn = detail?.history.findLast((action) => action.action === 'RETURNED');

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-expenses">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('expenses.me.title')}
        description={t('expenses.me.description')}
      />
      <ApprovalNotice />
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>
      {failure && <ExpenseAlert failure={failure} testId="expense-error" alertRef={alertRef} />}

      {view.kind === 'list' && (
        <>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="new-expense"
              onClick={() => {
                startCreate();
              }}
            >
              {t('expenses.me.new')}
            </button>
          </div>
          {load.kind === 'loading' && <p role="status">{t('expenses.loading')}</p>}
          {load.kind === 'failed' && (
            <ExpenseAlert failure={load.failure} testId="expenses-error" />
          )}
          {load.kind === 'ready' && items.length === 0 && (
            <p className="card" data-testid="expenses-empty">
              {t('expenses.me.empty')}
            </p>
          )}
          {load.kind === 'ready' && items.length > 0 && (
            <section className="card" aria-labelledby={`${ids}-list`}>
              <h2 id={`${ids}-list`} tabIndex={-1} ref={headingRef}>
                {t('expenses.me.listTitle')}
              </h2>
              <ul className="plain-list">
                {items.map((item) => (
                  <li key={item.id} className="hierarchy-item" data-testid="my-expense">
                    <p>
                      <strong data-testid="expense-amount">
                        {formatAmount(language, item.currency, item.amount)}
                      </strong>{' '}
                      <StateBadge state={item.state} />
                    </p>
                    <p>
                      {item.merchant} · {formatDate(language, item.businessDate)}
                    </p>
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

      {view.kind === 'form' && (
        <form
          noValidate
          className="card"
          aria-labelledby={`${ids}-form`}
          data-testid="expense-form"
          onSubmit={(event) => {
            event.preventDefault();
            void save();
          }}
        >
          <h2 id={`${ids}-form`} tabIndex={-1} ref={headingRef}>
            {view.editing ? t('expenses.me.editTitle') : t('expenses.me.newTitle')}
          </h2>
          <ExpenseFormFields
            idPrefix={ids}
            form={form}
            problems={problems}
            onChange={(next) => {
              setForm(next);
              setProblems(new Set());
            }}
          />
          <div className="dialog-actions">
            <button type="submit" className="button" disabled={busy} data-testid="save-expense">
              {busy ? t('expenses.sending') : t('expenses.me.save')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              onClick={() => {
                if (view.editing) {
                  setView({ kind: 'detail', id: view.editing.id });
                } else {
                  void backToList();
                }
              }}
            >
              {t('expenses.cancel')}
            </button>
          </div>
        </form>
      )}

      {view.kind === 'detail' && detail && expense && (
        <section className="card" aria-labelledby={`${ids}-detail`} data-testid="expense-detail">
          <h2 id={`${ids}-detail`} tabIndex={-1} ref={headingRef}>
            {t('expenses.me.detailTitle')} <StateBadge state={expense.state} />
          </h2>
          <ExpenseFacts expense={expense} language={language} />
          {expense.state === 'RETURNED' && lastReturn?.returnReason && (
            <div className="card" role="note" data-testid="returned-note">
              <p>
                <strong>{t('expenses.me.returnedWith')}</strong>
              </p>
              <p className="task-text">{lastReturn.returnReason}</p>
              <p className="muted">{t('expenses.me.returnedHelp')}</p>
            </div>
          )}
          {expense.state === 'SUBMITTED' && <p className="muted">{t('expenses.me.waiting')}</p>}
          {expense.state === 'APPROVED' && <p className="muted">{t('expenses.me.approvedNote')}</p>}
          {expense.state === 'WITHDRAWN' && (
            <p className="muted">{t('expenses.me.withdrawnNote')}</p>
          )}

          {expense.editable && (
            <div className="field">
              <label htmlFor={`${ids}-receipt`}>{t('expenses.me.receiptLabel')}</label>
              <p id={`${ids}-receipt-help`} className="field__help">
                {t('expenses.me.receiptHelp')}
              </p>
              {receiptProblem && (
                <p id={`${ids}-receipt-error`} className="field__error">
                  {t(`expenses.receiptProblem.${receiptProblem}`)}
                </p>
              )}
              <input
                id={`${ids}-receipt`}
                ref={fileRef}
                type="file"
                accept="application/pdf,image/jpeg,image/png,.pdf,.jpg,.jpeg,.png"
                disabled={busy}
                aria-invalid={receiptProblem ? true : undefined}
                aria-describedby={`${ids}-receipt-help${receiptProblem ? ` ${ids}-receipt-error` : ''}`}
                data-testid="receipt-input"
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) void attach(expense, file);
                }}
              />
              {expense.receipt && (
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={() => void removeReceipt(expense)}
                >
                  {t('expenses.me.removeReceipt')}
                </button>
              )}
            </div>
          )}

          <div className="dialog-actions">
            {expense.editable && (
              <>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={() => {
                    startEdit(expense);
                  }}
                >
                  {t('expenses.me.edit')}
                </button>
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  data-testid="submit-expense"
                  onClick={() => void submit(expense)}
                >
                  {busy ? t('expenses.sending') : t('expenses.me.submit')}
                </button>
              </>
            )}
            {expense.state === 'SUBMITTED' && !confirmWithdraw && (
              <button
                type="button"
                className="button button--secondary"
                data-testid="withdraw-expense"
                onClick={() => {
                  setConfirmWithdraw(true);
                }}
              >
                {t('expenses.me.withdraw')}
              </button>
            )}
            {expense.state === 'WITHDRAWN' && (
              <button
                type="button"
                className="button"
                data-testid="correct-expense"
                onClick={() => {
                  startCreate(formOf(expense));
                }}
              >
                {t('expenses.me.correctNew')}
              </button>
            )}
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void backToList()}
            >
              {t('expenses.back')}
            </button>
          </div>
          {confirmWithdraw && (
            <div
              role="group"
              aria-label={t('expenses.me.withdrawConfirmTitle')}
              data-testid="withdraw-confirm"
            >
              <p>{t('expenses.me.withdrawConfirm')}</p>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  data-testid="confirm-withdraw"
                  onClick={() => void withdraw(expense)}
                >
                  {busy ? t('expenses.sending') : t('expenses.me.withdrawButton')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={() => {
                    setConfirmWithdraw(false);
                  }}
                >
                  {t('expenses.me.keep')}
                </button>
              </div>
            </div>
          )}
          <ExpenseHistory detail={detail} language={language} scope="mine" />
        </section>
      )}
    </section>
  );
}

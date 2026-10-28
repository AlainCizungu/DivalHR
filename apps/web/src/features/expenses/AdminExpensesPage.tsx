import type { Expense, ExpenseCurrency, ExpenseDetail, ExpenseState } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import {
  ApprovalNotice,
  ExpenseAlert,
  ExpenseFacts,
  ExpenseHistory,
  StateBadge,
} from './ExpenseBits';
import {
  CURRENCIES,
  NETWORK_FAILURE,
  STATES,
  expenseFailureOf,
  formatAmount,
  formatDate,
  type ExpenseFailure,
} from './expenses';

type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: Expense[]; next: string | null }
  | { kind: 'failed'; failure: ExpenseFailure };

/**
 * MVP-081: « Notes de frais » / "Expenses" for the tenant administrator: observation only. The
 * list shows submitted expenses (the last submitted revision), filtered by state and currency,
 * with receipts downloadable as attachments. There is no action that changes an expense, and
 * amounts of different currencies are never totalled.
 */
export function AdminExpensesPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [state, setState] = useState<ExpenseState | ''>('');
  const [currency, setCurrency] = useState<ExpenseCurrency | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [detail, setDetail] = useState<ExpenseDetail | null>(null);
  const [failure, setFailure] = useState<ExpenseFailure | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const fetchPage = useCallback(
    async (
      cursor?: string,
    ): Promise<{ items: Expense[]; next: string | null } | ExpenseFailure> => {
      try {
        const { data, error, response } = await core.GET('/expenses', {
          params: {
            query: {
              limit: 50,
              ...(state ? { state } : {}),
              ...(currency ? { currency } : {}),
              ...(cursor ? { cursor } : {}),
            },
          },
          cache: 'no-store',
        });
        if (!data) return expenseFailureOf(response, error);
        return { items: data.items, next: data.nextCursor ?? null };
      } catch {
        return NETWORK_FAILURE;
      }
    },
    [core, state, currency],
  );

  useEffect(() => {
    let active = true;
    void fetchPage().then((result) => {
      if (!active) return;
      setLoad(
        'kind' in result ? { kind: 'failed', failure: result } : { kind: 'ready', ...result },
      );
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  useEffect(() => {
    headingRef.current?.focus();
  }, [detail?.expense.id]);

  const more = async () => {
    if (load.kind !== 'ready' || !load.next) return;
    const result = await fetchPage(load.next);
    if ('kind' in result) setFailure(result);
    else setLoad({ kind: 'ready', items: [...load.items, ...result.items], next: result.next });
  };

  const open = async (id: string) => {
    setFailure(null);
    try {
      const { data, error, response } = await core.GET('/expenses/{expenseId}', {
        params: { path: { expenseId: id } },
        cache: 'no-store',
      });
      if (data) setDetail(data);
      else setFailure(expenseFailureOf(response, error));
    } catch {
      setFailure(NETWORK_FAILURE);
    }
  };

  const items = load.kind === 'ready' ? load.items : [];
  const expense = detail?.expense;
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="admin-expenses">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('expenses.admin.title')}
        description={t('expenses.admin.description')}
      />
      <ApprovalNotice />
      {failure && <ExpenseAlert failure={failure} testId="expense-error" />}
      {!detail && (
        <>
          <div className="card">
            <div className="field">
              <label htmlFor={`${ids}-state`}>{t('expenses.admin.filterState')}</label>
              <select
                id={`${ids}-state`}
                value={state}
                onChange={(event) => {
                  setLoad({ kind: 'loading' });
                  setState(event.target.value as ExpenseState | '');
                }}
              >
                <option value="">{t('expenses.admin.all')}</option>
                {STATES.filter((value) => value !== 'DRAFT').map((value) => (
                  <option key={value} value={value}>
                    {t(`expenses.state.${value}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor={`${ids}-currency`}>{t('expenses.admin.filterCurrency')}</label>
              <select
                id={`${ids}-currency`}
                value={currency}
                onChange={(event) => {
                  setLoad({ kind: 'loading' });
                  setCurrency(event.target.value as ExpenseCurrency | '');
                }}
              >
                <option value="">{t('expenses.admin.all')}</option>
                {CURRENCIES.map((value) => (
                  <option key={value} value={value}>
                    {t(`expenses.currency.${value}`)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          {load.kind === 'loading' && <p role="status">{t('expenses.loading')}</p>}
          {load.kind === 'failed' && (
            <ExpenseAlert failure={load.failure} testId="expenses-error" />
          )}
          {load.kind === 'ready' && items.length === 0 && (
            <p className="card" data-testid="expenses-empty">
              {t('expenses.admin.empty')}
            </p>
          )}
          {load.kind === 'ready' && items.length > 0 && (
            <section className="card" aria-labelledby={`${ids}-list`}>
              <h2 id={`${ids}-list`}>{t('expenses.admin.listTitle')}</h2>
              <ul className="plain-list">
                {items.map((item) => (
                  <li key={item.id} className="hierarchy-item" data-testid="admin-expense">
                    <p>
                      <strong>
                        {item.employee.givenNames} {item.employee.familyName}
                      </strong>{' '}
                      ({item.employee.employeeNumber}) <StateBadge state={item.state} />
                    </p>
                    <p>
                      <span data-testid="expense-amount">
                        {formatAmount(language, item.currency, item.amount)}
                      </span>{' '}
                      · {item.merchant} · {formatDate(language, item.businessDate)}
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
              {load.next && (
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => void more()}
                >
                  {t('expenses.admin.more')}
                </button>
              )}
            </section>
          )}
        </>
      )}
      {detail && expense && (
        <section
          className="card"
          aria-labelledby={`${ids}-detail`}
          data-testid="admin-expense-detail"
        >
          <h2 id={`${ids}-detail`} tabIndex={-1} ref={headingRef}>
            {expense.employee.givenNames} {expense.employee.familyName} (
            {expense.employee.employeeNumber}) <StateBadge state={expense.state} />
          </h2>
          <ExpenseFacts expense={expense} language={language} />
          <ExpenseHistory detail={detail} language={language} scope="admin" />
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              setDetail(null);
            }}
          >
            {t('expenses.back')}
          </button>
        </section>
      )}
    </section>
  );
}

import type { Expense, ExpenseDetail, ExpenseState } from '@divalhr/api-client';
import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import {
  downloadNameOf,
  expenseFailureOf,
  formatAmount,
  formatDate,
  formatInstant,
  NETWORK_FAILURE,
  stateTone,
  type ExpenseFailure,
} from './expenses';

/** State as text, never by colour alone. */
export function StateBadge({ state }: { state: ExpenseState }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={stateTone(state)} testId="expense-state">
      {t(`expenses.state.${state}`)}
    </StatusBadge>
  );
}

/** A refused command: the stable message, the named fields, retry delay and reference. */
export function ExpenseAlert({
  failure,
  testId,
  alertRef,
}: {
  failure: ExpenseFailure;
  testId: string;
  alertRef?: React.Ref<HTMLDivElement>;
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
            <li key={field}>{t(`expenses.problems.${field}`)}</li>
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

/** The fixed notice: approval is not reimbursement or payment. */
export function ApprovalNotice() {
  const { t } = useTranslation();
  return (
    <p className="card muted" data-testid="approval-notice">
      {t('expenses.notice')}
    </p>
  );
}

export type ReceiptScope = 'mine' | 'approvals' | 'admin';

/** Downloads one revision's receipt as an attachment through the authenticated client. */
export function useReceiptDownload(scope: ReceiptScope) {
  const { core } = useApi();
  const [failure, setFailure] = useState<ExpenseFailure | null>(null);
  const download = useCallback(
    async (expenseId: string, revision: number, mediaType: string) => {
      setFailure(null);
      try {
        const params = { path: { expenseId, revision } };
        const result =
          scope === 'mine'
            ? await core.GET('/me/expenses/{expenseId}/revisions/{revision}/receipt', {
                params,
                parseAs: 'blob',
                cache: 'no-store',
              })
            : scope === 'approvals'
              ? await core.GET('/me/expense-approvals/{expenseId}/revisions/{revision}/receipt', {
                  params,
                  parseAs: 'blob',
                  cache: 'no-store',
                })
              : await core.GET('/expenses/{expenseId}/revisions/{revision}/receipt', {
                  params,
                  parseAs: 'blob',
                  cache: 'no-store',
                });
        if (!result.data) {
          setFailure(expenseFailureOf(result.response, result.error));
          return;
        }
        const url = URL.createObjectURL(result.data);
        const link = document.createElement('a');
        link.href = url;
        link.download = downloadNameOf(mediaType);
        link.rel = 'noopener';
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => {
          URL.revokeObjectURL(url);
        }, 0);
      } catch {
        setFailure(NETWORK_FAILURE);
      }
    },
    [core, scope],
  );
  return { download, failure };
}

/** The values of one expense, in the interface language, with the currency always shown. */
export function ExpenseFacts({ expense, language }: { expense: Expense; language: string }) {
  const { t } = useTranslation();
  return (
    <dl className="facts" data-testid="expense-facts">
      <dt>{t('expenses.fields.amount')}</dt>
      <dd data-testid="expense-amount">
        {formatAmount(language, expense.currency, expense.amount)}
      </dd>
      <dt>{t('expenses.fields.businessDate')}</dt>
      <dd>{formatDate(language, expense.businessDate)}</dd>
      <dt>{t('expenses.fields.category')}</dt>
      <dd>{t(`expenses.category.${expense.category}`)}</dd>
      <dt>{t('expenses.fields.merchant')}</dt>
      <dd data-testid="expense-merchant">{expense.merchant}</dd>
      <dt>{t('expenses.fields.description')}</dt>
      <dd className="task-text" data-testid="expense-description">
        {expense.description}
      </dd>
      <dt>{t('expenses.fields.receipt')}</dt>
      <dd>
        {expense.receipt
          ? `${expense.receipt.fileName} (${Math.max(1, Math.round(expense.receipt.sizeBytes / 1024))} ${t('expenses.kilobytes')})`
          : t('expenses.noReceipt')}
      </dd>
    </dl>
  );
}

/** Revisions and history of one expense; receipts download per submitted revision. */
export function ExpenseHistory({
  detail,
  language,
  scope,
}: {
  detail: ExpenseDetail;
  language: string;
  scope: ReceiptScope;
}) {
  const { t } = useTranslation();
  const { download, failure } = useReceiptDownload(scope);
  const expenseId = detail.expense.id;
  return (
    <>
      {failure && <ExpenseAlert failure={failure} testId="receipt-error" />}
      <h3>{t('expenses.revisions')}</h3>
      <ol className="plain-list" data-testid="expense-revisions">
        {detail.revisions.map((revision) => (
          <li key={revision.revision} className="hierarchy-item" data-testid="expense-revision">
            <p>
              <strong>{t('expenses.revisionN', { n: revision.revision })}</strong>{' '}
              <span data-testid="revision-amount">
                {formatAmount(language, revision.currency, revision.amount)}
              </span>{' '}
              · {formatDate(language, revision.businessDate)} ·{' '}
              {t(`expenses.category.${revision.category}`)}
            </p>
            <p>{revision.merchant}</p>
            {revision.receipt && (
              <button
                type="button"
                className="button button--secondary"
                data-testid="receipt-download"
                onClick={() => {
                  if (revision.receipt) {
                    void download(expenseId, revision.revision, revision.receipt.mediaType);
                  }
                }}
              >
                {t('expenses.downloadReceipt', { name: revision.receipt.fileName })}
              </button>
            )}
          </li>
        ))}
      </ol>
      <h3>{t('expenses.history')}</h3>
      <ol className="plain-list" data-testid="expense-history">
        {detail.history.map((action) => (
          <li key={action.sequence} data-testid="expense-action">
            {formatInstant(language, action.occurredAt)} · {t(`expenses.action.${action.action}`)} ·{' '}
            {t(`expenses.role.${action.actorRole}`)}
            {action.returnReason && (
              <span className="task-text" data-testid="return-reason">
                {' '}
                — {action.returnReason}
              </span>
            )}
          </li>
        ))}
      </ol>
    </>
  );
}

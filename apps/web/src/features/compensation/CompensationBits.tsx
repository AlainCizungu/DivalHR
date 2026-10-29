import type {
  CompensationChangeView,
  CompensationInterval,
  CompensationMoney,
} from '@divalhr/api-client';
import { useCallback, useEffect, useRef, useState, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import {
  compensationFailureOf,
  formatDate,
  formatInstant,
  formatMoney,
  formatPeriod,
  NETWORK_FAILURE,
  type CompensationFailure,
} from './compensation';

/** What both readers show: the administrator's record and the employee's own. */
export interface RecordView {
  version: number | null;
  businessDate: string;
  current: CompensationInterval | null;
  scheduled: CompensationInterval[];
  changes: CompensationChangeView[];
  nextCursor: string | null;
}

export type RecordLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: CompensationFailure }
  | { kind: 'ready'; record: RecordView };

/**
 * Loads the first page of a compensation record and appends further history pages on request. The
 * cursor is kept in memory only; nothing is written to browser storage and every request is
 * `no-store`. `revision` reloads the first page.
 */
export function useCompensationRecord(employeeId: string | null, revision = 0) {
  const { core } = useApi();
  const [load, setLoad] = useState<RecordLoad>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<CompensationFailure | null>(null);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (
      cursor: string | null,
    ): Promise<{ record: RecordView } | { failure: CompensationFailure }> => {
      const query = cursor ? { cursor } : {};
      try {
        if (employeeId === null) {
          const { data, error, response } = await core.GET('/me/compensation', {
            params: { query },
            cache: 'no-store',
          });
          return data
            ? { record: { ...data, version: null } }
            : { failure: compensationFailureOf(response, error) };
        }
        const { data, error, response } = await core.GET('/employees/{employeeId}/compensation', {
          params: { path: { employeeId }, query },
          cache: 'no-store',
        });
        return data ? { record: data } : { failure: compensationFailureOf(response, error) };
      } catch {
        return { failure: NETWORK_FAILURE };
      }
    },
    [core, employeeId],
  );

  const reload = useCallback(async () => {
    const result = await fetchPage(null);
    setMoreFailure(null);
    setLoad(
      'record' in result
        ? { kind: 'ready', record: result.record }
        : { kind: 'failed', failure: result.failure },
    );
  }, [fetchPage]);

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((result) => {
      if (!active) return;
      setMoreFailure(null);
      setLoad(
        'record' in result
          ? { kind: 'ready', record: result.record }
          : { kind: 'failed', failure: result.failure },
      );
    });
    return () => {
      active = false;
    };
  }, [fetchPage, revision]);

  const loadMore = useCallback(async () => {
    if (load.kind !== 'ready' || !load.record.nextCursor || loading.current) return;
    loading.current = true;
    setMoreBusy(true);
    setMoreFailure(null);
    const result = await fetchPage(load.record.nextCursor);
    if ('record' in result) {
      const page = result.record;
      setLoad((current) =>
        current.kind === 'ready'
          ? {
              kind: 'ready',
              record: {
                ...current.record,
                changes: [...current.record.changes, ...page.changes],
                nextCursor: page.nextCursor,
              },
            }
          : current,
      );
    } else {
      setMoreFailure(result.failure);
    }
    loading.current = false;
    setMoreBusy(false);
  }, [fetchPage, load]);

  return { load, reload, loadMore, moreBusy, moreFailure };
}

/** A refused command: the stable message, the retry delay and the reference. */
export function CompensationAlert({
  failure,
  testId,
  alertRef,
}: {
  failure: CompensationFailure;
  testId: string;
  alertRef?: Ref<HTMLDivElement>;
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
      data-code={failure.code}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** Money for display: the currency code is always part of the text, never converted. */
export function Money({ money }: { money: CompensationMoney }) {
  const { i18n } = useTranslation();
  return (
    <strong data-testid="money" data-currency={money.currency} data-amount={money.amount}>
      {formatMoney(i18n.language, money)}
    </strong>
  );
}

/** One effective interval; `actions` are the administrator's buttons for it. */
export function IntervalItem({
  interval,
  testId,
  actions,
}: {
  interval: CompensationInterval;
  testId: string;
  actions?: ReactNode;
}) {
  const { t, i18n } = useTranslation();
  return (
    <li className="hierarchy-item" data-testid={testId}>
      <div className="hierarchy-item__main">
        <Money money={interval.money} />
        <span className="muted">
          {' '}
          {t('compensation.monthly')} ·{' '}
          {formatPeriod(t, i18n.language, interval.effectiveFrom, interval.effectiveTo)}
        </span>
      </div>
      {actions}
    </li>
  );
}

/** The immutable history, newest first. The explanation is shown to administrators only. */
export function HistoryList({
  changes,
  showExplanation,
}: {
  changes: CompensationChangeView[];
  showExplanation: boolean;
}) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="plain-list" data-testid="compensation-history">
      {changes.map((change) => (
        <li key={change.changeId} className="hierarchy-item" data-testid="compensation-change">
          <div className="hierarchy-item__main">
            <p>
              <strong>{t(`compensation.action.${change.action}`)}</strong> ·{' '}
              <Money money={change.money} />
            </p>
            <dl className="summary">
              <dt>{t('compensation.history.effectiveFrom')}</dt>
              <dd>{formatDate(i18n.language, change.effectiveFrom)}</dd>
              {change.reasonCode && (
                <>
                  <dt>{t('compensation.history.reason')}</dt>
                  <dd>{t(`compensation.reason.${change.reasonCode}`)}</dd>
                </>
              )}
              {change.retroactive && (
                <>
                  <dt>{t('compensation.history.timing')}</dt>
                  <dd>{t('compensation.history.retroactive')}</dd>
                </>
              )}
              {showExplanation && change.explanation && (
                <>
                  <dt>{t('compensation.history.explanation')}</dt>
                  <dd className="preformatted" data-testid="change-explanation">
                    {change.explanation}
                  </dd>
                </>
              )}
              <dt>{t('compensation.history.recordedAt')}</dt>
              <dd>{formatInstant(i18n.language, change.recordedAt)}</dd>
            </dl>
          </div>
        </li>
      ))}
    </ul>
  );
}

/** The scheduled changes, the history and its "Load more" button; shared by both readers. */
export function HistoryBlock({
  record,
  showExplanation,
  moreBusy,
  moreFailure,
  onMore,
  headingId,
  level,
}: {
  record: RecordView;
  showExplanation: boolean;
  moreBusy: boolean;
  moreFailure: CompensationFailure | null;
  onMore: () => void;
  headingId: string;
  level: 2 | 3;
}) {
  const { t } = useTranslation();
  const Heading = level === 2 ? 'h2' : 'h3';
  return (
    <section aria-labelledby={headingId} data-testid="compensation-history-section">
      <Heading id={headingId}>{t('compensation.history.title')}</Heading>
      {record.changes.length === 0 ? (
        <p className="muted" data-testid="compensation-history-empty">
          {t('compensation.history.empty')}
        </p>
      ) : (
        <HistoryList changes={record.changes} showExplanation={showExplanation} />
      )}
      {moreFailure && <CompensationAlert failure={moreFailure} testId="compensation-more-error" />}
      {record.nextCursor && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            data-testid="compensation-more"
            disabled={moreBusy}
            onClick={onMore}
          >
            {moreBusy ? t('compensation.loadingMore') : t('compensation.loadMore')}
          </button>
        </div>
      )}
    </section>
  );
}

import type { Assignment, AssignmentKind, AssignmentPage } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { HistoryAlert } from './HistoryAlert';
import {
  formatPeriod,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  KINDS,
  valueText,
  type HistoryFailure,
} from './history';
import { ScrollRegion } from './ScrollRegion';

type Rows =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | { kind: 'ready'; items: Assignment[]; nextCursor: string | null; loadingMore: boolean };

/**
 * MVP-021: an employee's effective-dated rows by kind then start date. By default only the current
 * history; rows replaced by later changes, corrections or cancellations can be shown too. Active
 * rows can be corrected (their value for their own dates).
 */
export function TimelineSection({
  employeeId,
  revision,
  onCorrect,
}: {
  employeeId: string;
  revision: number;
  onCorrect: (row: Assignment) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [kind, setKind] = useState<AssignmentKind | ''>('');
  const [superseded, setSuperseded] = useState(false);
  const [rows, setRows] = useState<Rows>({ kind: 'loading' });

  const fetchPage = useCallback(
    async (cursor?: string) =>
      (await core.GET('/employees/{employeeId}/timeline', {
        params: {
          path: { employeeId },
          query: { kind: kind || undefined, includeSuperseded: superseded, cursor },
        },
        cache: 'no-store',
      })) as { data?: AssignmentPage; error?: unknown; response: Response },
    [core, employeeId, kind, superseded],
  );

  const load = useCallback(async (): Promise<Rows> => {
    try {
      const { data, error, response } = await fetchPage();
      return data
        ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor, loadingMore: false }
        : { kind: 'failed', failure: historyFailureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: HISTORY_NETWORK_FAILURE };
    }
  }, [fetchPage]);

  useEffect(() => {
    let active = true;
    void load().then((next) => {
      if (active) setRows(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision]);

  const loadMore = async () => {
    if (rows.kind !== 'ready' || !rows.nextCursor || rows.loadingMore) return;
    setRows({ ...rows, loadingMore: true });
    try {
      const { data, error, response } = await fetchPage(rows.nextCursor);
      setRows(
        data
          ? {
              kind: 'ready',
              items: [...rows.items, ...data.items],
              nextCursor: data.nextCursor,
              loadingMore: false,
            }
          : { kind: 'failed', failure: historyFailureOf(response, error) },
      );
    } catch {
      setRows({ kind: 'failed', failure: HISTORY_NETWORK_FAILURE });
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="timeline">
      <h2 id={`${ids}-title`}>{t('employees.timeline.title')}</h2>
      <div className="toolbar">
        <div className="field">
          <label htmlFor={`${ids}-kind`}>{t('employees.timeline.kind')}</label>
          <select
            id={`${ids}-kind`}
            value={kind}
            onChange={(event) => {
              setRows({ kind: 'loading' });
              setKind(event.target.value as AssignmentKind | '');
            }}
          >
            <option value="">{t('employees.timeline.allKinds')}</option>
            {KINDS.map((value) => (
              <option key={value} value={value}>
                {t(`employees.kinds.${value}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="choice">
          <input
            id={`${ids}-superseded`}
            type="checkbox"
            checked={superseded}
            onChange={(event) => {
              setRows({ kind: 'loading' });
              setSuperseded(event.target.checked);
            }}
          />
          <label htmlFor={`${ids}-superseded`}>{t('employees.timeline.includeSuperseded')}</label>
        </div>
      </div>
      {rows.kind === 'loading' && <p>{t('employees.loading')}</p>}
      {rows.kind === 'failed' && (
        <>
          <HistoryAlert failure={rows.failure} data-testid="timeline-error" />
          <button
            type="button"
            className="button"
            onClick={() => {
              setRows({ kind: 'loading' });
              void load().then(setRows);
            }}
          >
            {t('employees.retry')}
          </button>
        </>
      )}
      {rows.kind === 'ready' && rows.items.length === 0 && <p>{t('employees.timeline.none')}</p>}
      {rows.kind === 'ready' && rows.items.length > 0 && (
        <ScrollRegion label={t('employees.timeline.caption')}>
          <table data-testid="timeline-table">
            <caption>{t('employees.timeline.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('employees.timeline.kind')}</th>
                <th scope="col">{t('employees.timeline.period')}</th>
                <th scope="col">{t('employees.timeline.value')}</th>
                <th scope="col">{t('employees.timeline.status')}</th>
                <th scope="col">{t('employees.timeline.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.items.map((row) => (
                <tr key={row.id}>
                  <th scope="row">{t(`employees.kinds.${row.kind}`)}</th>
                  <td>{formatPeriod(t, i18n.language, row.effectiveFrom, row.effectiveTo)}</td>
                  <td>{valueText(t, row)}</td>
                  <td>
                    {row.supersededAt
                      ? t('employees.timeline.replaced')
                      : t(`employees.assignmentStatus.${row.status}`)}
                  </td>
                  <td>
                    {!row.supersededAt && (
                      <button
                        type="button"
                        className="button button--secondary"
                        aria-label={t('employees.timeline.correctRow', {
                          kind: t(`employees.kinds.${row.kind}`),
                          period: formatPeriod(
                            t,
                            i18n.language,
                            row.effectiveFrom,
                            row.effectiveTo,
                          ),
                        })}
                        onClick={() => {
                          onCorrect(row);
                        }}
                      >
                        {t('employees.timeline.correct')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {rows.kind === 'ready' && rows.nextCursor && (
        <button
          type="button"
          className="button button--secondary"
          disabled={rows.loadingMore}
          onClick={() => void loadMore()}
        >
          {rows.loadingMore ? t('employees.loading') : t('employees.loadMore')}
        </button>
      )}
    </section>
  );
}

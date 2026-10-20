import type { PayrollInputExport } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { formatDate } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from '../schedules/attendance';
import { durationParts } from '../schedules/timesheets';
import {
  PAYROLL_NETWORK_FAILURE,
  attachmentName,
  payrollFailureOf,
  type PayrollFailure,
} from './payrollInputs';
import { SalaryNotice } from './SalaryNotice';

/** A Blob saved under a safe name; nothing is kept in browser storage or put in a URL. */
function save(data: Blob, filename: string) {
  const url = URL.createObjectURL(data);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

/**
 * MVP-092: the worked-time exports of locked payroll-input runs, newest first, each with the exact
 * CSV and its integrity manifest (the manifest carries the CSV's SHA-256). The fixed monthly-salary
 * notice sits on the history and the download area: this is never a payroll, salary, payment or
 * bank file. {@code refresh} changes whenever the list must be reloaded.
 */
export function PayrollExports({
  refresh,
  announce,
}: {
  refresh: number;
  announce: (text: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const [items, setItems] = useState<PayrollInputExport[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading');
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data } = await core.GET('/payroll-input-exports', {
          params: { query: cursor ? { cursor, limit: 20 } : { limit: 20 } },
          cache: 'no-store',
        });
        return data ?? null;
      } catch {
        return null;
      }
    },
    [core],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((data) => {
      if (!active) return;
      if (!data) {
        setState('failed');
        return;
      }
      setItems(data.items);
      setNextCursor(data.nextCursor ?? null);
      setState('ready');
    });
    return () => {
      active = false;
    };
  }, [fetchPage, refresh]);

  const guarded = async (work: () => Promise<void>) => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await work();
    } catch {
      setFailure(PAYROLL_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const more = () => {
    if (!nextCursor) return;
    void guarded(async () => {
      const data = await fetchPage(nextCursor);
      if (!data) {
        setFailure(PAYROLL_NETWORK_FAILURE);
        return;
      }
      setItems((previous) => [...previous, ...data.items]);
      setNextCursor(data.nextCursor ?? null);
    });
  };

  const download = (item: PayrollInputExport, kind: 'csv' | 'manifest') => {
    void guarded(async () => {
      const path =
        kind === 'csv'
          ? ('/payroll-input-exports/{exportId}/csv' as const)
          : ('/payroll-input-exports/{exportId}/manifest' as const);
      const { data, error, response } = await core.GET(path, {
        params: { path: { exportId: item.exportId } },
        parseAs: 'blob',
        cache: 'no-store',
      });
      if (!data) {
        setFailure(payrollFailureOf(response, error));
        return;
      }
      save(
        data,
        attachmentName(
          response.headers.get('Content-Disposition'),
          `divalhr-worked-time-inputs-${item.scope.legalEntityId}-${item.scope.periodStart}-${item.scope.periodEnd}-v${String(item.runVersion)}.${kind === 'csv' ? 'csv' : 'manifest.json'}`,
        ),
      );
      announce(
        kind === 'csv'
          ? t('payrollPreparation.exports.csvDownloaded')
          : t('payrollPreparation.exports.manifestDownloaded'),
      );
    });
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="payroll-prep-exports">
      <h2 id={`${ids}-title`}>{t('payrollPreparation.exports.title')}</h2>
      <SalaryNotice testId="payroll-prep-exports-notice" />
      <p className="muted">{t('payrollPreparation.exports.hint')}</p>
      {state === 'loading' && (
        <p className="muted" role="status" data-testid="payroll-prep-exports-loading">
          {t('payrollPreparation.exports.loading')}
        </p>
      )}
      {state === 'failed' && (
        <p className="field__error" role="alert" data-testid="payroll-prep-exports-failed">
          {t('payrollPreparation.exports.unavailable')}
        </p>
      )}
      {failure && (
        <div className="error-summary" role="alert" data-testid="payroll-prep-exports-error">
          <p>{t(failure.messageKey)}</p>
          {failure.correlationId && (
            <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}
      {state === 'ready' && items.length === 0 && (
        <p className="muted" data-testid="payroll-prep-exports-empty">
          {t('payrollPreparation.exports.empty')}
        </p>
      )}
      {items.length > 0 && (
        <ScrollRegion label={t('payrollPreparation.exports.table')}>
          <table className="timesheet-table">
            <caption className="visually-hidden">{t('payrollPreparation.exports.table')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('payrollPreparation.history.period')}</th>
                <th scope="col">{t('payrollPreparation.detail.version')}</th>
                <th scope="col">{t('payrollPreparation.counts.employees')}</th>
                <th scope="col">{t('payrollPreparation.counts.rows')}</th>
                <th scope="col">{t('payrollPreparation.counts.worked')}</th>
                <th scope="col">{t('payrollPreparation.exports.sha')}</th>
                <th scope="col">{t('payrollPreparation.exports.created')}</th>
                <th scope="col">{t('payrollPreparation.history.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => {
                const period = {
                  from: formatDate(i18n.language, item.scope.periodStart),
                  to: formatDate(i18n.language, item.scope.periodEnd),
                };
                return (
                  <tr key={item.exportId} data-testid="payroll-prep-export" data-run={item.runId}>
                    <td>{t('timesheets.period', period)}</td>
                    <td>{item.runVersion}</td>
                    <td>{item.employees}</td>
                    <td data-testid="payroll-prep-export-rows">{item.rows}</td>
                    <td>{t('timesheets.duration', durationParts(item.workedMinutes))}</td>
                    <td className="break-anywhere">
                      <code data-testid="payroll-prep-export-sha">{item.csvSha256}</code>
                    </td>
                    <td>{formatInstant(language, item.createdAt, item.scope.businessTimezone)}</td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('payrollPreparation.exports.csvFor', period)}
                        data-testid="payroll-prep-export-csv"
                        onClick={() => {
                          download(item, 'csv');
                        }}
                      >
                        {t('payrollPreparation.exports.csv')}
                      </button>{' '}
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('payrollPreparation.exports.manifestFor', period)}
                        data-testid="payroll-prep-export-manifest"
                        onClick={() => {
                          download(item, 'manifest');
                        }}
                      >
                        {t('payrollPreparation.exports.manifest')}
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {nextCursor && (
        <p>
          <button
            type="button"
            className="button button--secondary"
            disabled={busy}
            data-testid="payroll-prep-exports-more"
            onClick={more}
          >
            {t('payrollPreparation.history.more')}
          </button>
        </p>
      )}
    </section>
  );
}

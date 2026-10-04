import type {
  EmploymentChange,
  EmploymentChangeCancellationPreview,
  EmploymentChangePage,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { HistoryAlert } from './HistoryAlert';
import {
  formatDate,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  type HistoryFailure,
} from './history';
import { PreviewTable } from './PreviewTable';
import { ScrollRegion } from './ScrollRegion';

type Changes =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | { kind: 'ready'; items: EmploymentChange[]; nextCursor: string | null; loadingMore: boolean };

type Cancellation = {
  change: EmploymentChange;
  preview: EmploymentChangeCancellationPreview | null;
  failure: HistoryFailure | null;
  busy: boolean;
};

/**
 * MVP-021: an employee's recorded changes, newest first. An active business change that starts
 * after the business date can be cancelled: the cancellation is previewed (rows removed and the
 * value restored, bounded by the next change, which is never altered) and confirmed with the
 * preview's version and digest. The cancelled change and its rows stay in the history.
 */
export function ChangesSection({
  employeeId,
  businessDate,
  revision,
  onCancelled,
}: {
  employeeId: string;
  businessDate: string;
  revision: number;
  onCancelled: (cancellation: EmploymentChange) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const cancelKey = useIdempotencyKey();
  const [changes, setChanges] = useState<Changes>({ kind: 'loading' });
  const [cancellation, setCancellation] = useState<Cancellation | null>(null);
  const panelHeading = useRef<HTMLHeadingElement>(null);
  const focusPanel = useRef(false);

  useEffect(() => {
    if (focusPanel.current && panelHeading.current) {
      focusPanel.current = false;
      panelHeading.current.focus();
    }
  });

  const fetchPage = useCallback(
    async (cursor?: string) =>
      (await core.GET('/employees/{employeeId}/employment-changes', {
        params: { path: { employeeId }, query: { cursor } },
        cache: 'no-store',
      })) as { data?: EmploymentChangePage; error?: unknown; response: Response },
    [core, employeeId],
  );

  const load = useCallback(async (): Promise<Changes> => {
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
      if (active) setChanges(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision]);

  const loadMore = async () => {
    if (changes.kind !== 'ready' || !changes.nextCursor || changes.loadingMore) return;
    setChanges({ ...changes, loadingMore: true });
    try {
      const { data, error, response } = await fetchPage(changes.nextCursor);
      setChanges(
        data
          ? {
              kind: 'ready',
              items: [...changes.items, ...data.items],
              nextCursor: data.nextCursor,
              loadingMore: false,
            }
          : { kind: 'failed', failure: historyFailureOf(response, error) },
      );
    } catch {
      setChanges({ kind: 'failed', failure: HISTORY_NETWORK_FAILURE });
    }
  };

  const cancellable = (change: EmploymentChange) =>
    change.type === 'CHANGE' &&
    change.state === 'ACTIVE' &&
    change.reasonCode !== 'MANAGER_SEPARATED' &&
    change.effectiveFrom > businessDate;

  const previewCancellation = async (change: EmploymentChange) => {
    cancelKey.reset();
    setCancellation({ change, preview: null, failure: null, busy: true });
    focusPanel.current = true;
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/employment-changes/{changeId}/cancel/preview',
        { params: { path: { employeeId, changeId: change.id } }, cache: 'no-store' },
      );
      setCancellation({
        change,
        preview: data ?? null,
        failure: data ? null : historyFailureOf(response, error),
        busy: false,
      });
    } catch {
      setCancellation({ change, preview: null, failure: HISTORY_NETWORK_FAILURE, busy: false });
    }
  };

  const confirmCancellation = async () => {
    if (!cancellation?.preview || cancellation.busy) return;
    const { change, preview } = cancellation;
    const body = {
      expectedVersion: preview.expectedVersion,
      cancellationDigest: preview.cancellationDigest,
    };
    setCancellation({ ...cancellation, busy: true, failure: null });
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/employment-changes/{changeId}/cancel',
        {
          params: {
            path: { employeeId, changeId: change.id },
            header: { 'Idempotency-Key': cancelKey.keyFor({ changeId: change.id, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        cancelKey.reset();
        setCancellation(null);
        onCancelled(data.change);
        return;
      }
      const failure = historyFailureOf(response, error);
      const stale =
        failure.code === 'EMPLOYMENT_PREVIEW_CHANGED' ||
        failure.code === 'EMPLOYMENT_VERSION_CONFLICT';
      if (stale) cancelKey.reset();
      setCancellation({ change, preview: stale ? null : preview, failure, busy: false });
    } catch {
      setCancellation({ change, preview, failure: HISTORY_NETWORK_FAILURE, busy: false });
    }
  };

  const describe = (change: EmploymentChange) =>
    change.kinds.map((kind) => t(`employees.kinds.${kind}`)).join(', ');

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="changes">
      <h2 id={`${ids}-title`}>{t('employees.changes.title')}</h2>
      {changes.kind === 'loading' && <p>{t('employees.loading')}</p>}
      {changes.kind === 'failed' && (
        <>
          <HistoryAlert failure={changes.failure} data-testid="changes-error" />
          <button
            type="button"
            className="button"
            onClick={() => {
              setChanges({ kind: 'loading' });
              void load().then(setChanges);
            }}
          >
            {t('employees.retry')}
          </button>
        </>
      )}
      {changes.kind === 'ready' && (
        <ScrollRegion label={t('employees.changes.caption')}>
          <table data-testid="changes-table">
            <caption>{t('employees.changes.caption')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('employees.changes.type')}</th>
                <th scope="col">{t('employees.changes.effectiveFrom')}</th>
                <th scope="col">{t('employees.changes.kinds')}</th>
                <th scope="col">{t('employees.changes.reason')}</th>
                <th scope="col">{t('employees.changes.state')}</th>
                <th scope="col">{t('employees.timeline.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {changes.items.map((change) => (
                <tr key={change.id}>
                  <th scope="row">
                    {t(`employees.changeType.${change.type}`)}
                    {change.timing && (
                      <span className="muted"> · {t(`employees.timing.${change.timing}`)}</span>
                    )}
                  </th>
                  <td>{formatDate(i18n.language, change.effectiveFrom)}</td>
                  <td>{describe(change)}</td>
                  <td>
                    {change.reasonCode
                      ? t(`employees.reasons.${change.reasonCode}`)
                      : t('employees.none')}
                  </td>
                  <td>{t(`employees.changeState.${change.state}`)}</td>
                  <td>
                    {cancellable(change) && (
                      <button
                        type="button"
                        className="button button--secondary"
                        aria-label={t('employees.changes.cancelOne', {
                          date: formatDate(i18n.language, change.effectiveFrom),
                          kinds: describe(change),
                        })}
                        onClick={() => void previewCancellation(change)}
                      >
                        {t('employees.changes.cancel')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {changes.kind === 'ready' && changes.nextCursor && (
        <button
          type="button"
          className="button button--secondary"
          disabled={changes.loadingMore}
          onClick={() => void loadMore()}
        >
          {changes.loadingMore ? t('employees.loading') : t('employees.loadMore')}
        </button>
      )}

      {cancellation && (
        <section aria-labelledby={`${ids}-cancel`} data-testid="cancel-panel">
          <h3 id={`${ids}-cancel`} tabIndex={-1} ref={panelHeading}>
            {t('employees.changes.cancelTitle', {
              date: formatDate(i18n.language, cancellation.change.effectiveFrom),
            })}
          </h3>
          {cancellation.busy && !cancellation.preview && <p>{t('employees.loading')}</p>}
          {cancellation.failure && (
            <HistoryAlert failure={cancellation.failure} data-testid="cancel-error" />
          )}
          {cancellation.preview && (
            <>
              <p>{t('employees.changes.cancelHelp')}</p>
              <PreviewTable
                kinds={cancellation.preview.kinds}
                beforeLabel={t('employees.preview.removed')}
                afterLabel={t('employees.preview.restored')}
                data-testid="cancel-preview"
              />
            </>
          )}
          <div className="actions">
            {cancellation.preview && (
              <button
                type="button"
                className="button"
                disabled={cancellation.busy}
                onClick={() => void confirmCancellation()}
              >
                {cancellation.busy
                  ? t('employees.form.saving')
                  : t('employees.changes.confirmCancel')}
              </button>
            )}
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setCancellation(null);
              }}
            >
              {t('employees.changes.keep')}
            </button>
          </div>
        </section>
      )}
    </section>
  );
}

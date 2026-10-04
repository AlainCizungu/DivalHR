import type {
  Separation,
  SeparationCancellationPreview,
  SeparationTask,
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
import { SeparationForm } from './SeparationForm';
import {
  formatInstant,
  SEPARATION_STALE,
  TASK_STATUSES,
  type SettableTaskStatus,
} from './separation';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | { kind: 'ready'; items: Separation[] };

type Cancellation = {
  separation: Separation;
  preview: SeparationCancellationPreview | null;
  failure: HistoryFailure | null;
  busy: boolean;
};

/**
 * MVP-022: the employee's separations (newest first) with their access state and follow-up
 * checklist, the separation form while no separation is in force, the cancellation of a scheduled
 * separation (previewed, then confirmed with the preview's version and digest) and the retry of a
 * sign-in removal that needs an administrator.
 */
export function SeparationsSection({
  employeeId,
  businessDate,
  revision,
  onChanged,
}: {
  employeeId: string;
  businessDate: string;
  revision: number;
  onChanged: (messageKey: string, values: Record<string, string>, refresh: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const cancelKey = useIdempotencyKey();
  const taskKey = useIdempotencyKey();
  const retryKey = useIdempotencyKey();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [cancellation, setCancellation] = useState<Cancellation | null>(null);
  const [failure, setFailure] = useState<HistoryFailure | null>(null);
  const [busyTask, setBusyTask] = useState<string | null>(null);
  const [formKey, setFormKey] = useState(0);
  const panelHeading = useRef<HTMLHeadingElement>(null);
  const failureBox = useRef<HTMLDivElement>(null);
  const focusNext = useRef<'panel' | 'failure' | null>(null);

  useEffect(() => {
    const element = focusNext.current === 'panel' ? panelHeading.current : failureBox.current;
    if (focusNext.current && element) {
      focusNext.current = null;
      element.focus();
    }
  });

  const load = useCallback(async (): Promise<Loaded> => {
    try {
      const { data, error, response } = await core.GET('/employees/{employeeId}/separations', {
        params: { path: { employeeId } },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', items: data.items }
        : { kind: 'failed', failure: historyFailureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: HISTORY_NETWORK_FAILURE };
    }
  }, [core, employeeId]);

  useEffect(() => {
    let active = true;
    void load().then((next) => {
      if (active) setLoaded(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision]);

  const refused = (next: HistoryFailure) => {
    setFailure(next);
    focusNext.current = 'failure';
  };

  const replace = (separation: Separation) => {
    setLoaded((current) =>
      current.kind === 'ready'
        ? {
            kind: 'ready',
            items: current.items.map((item) => (item.id === separation.id ? separation : item)),
          }
        : current,
    );
  };

  const previewCancellation = async (separation: Separation) => {
    cancelKey.reset();
    setFailure(null);
    setCancellation({ separation, preview: null, failure: null, busy: true });
    focusNext.current = 'panel';
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/separations/{separationId}/cancel/preview',
        { params: { path: { employeeId, separationId: separation.id } }, cache: 'no-store' },
      );
      setCancellation({
        separation,
        preview: data ?? null,
        failure: data ? null : historyFailureOf(response, error),
        busy: false,
      });
    } catch {
      setCancellation({ separation, preview: null, failure: HISTORY_NETWORK_FAILURE, busy: false });
    }
  };

  const confirmCancellation = async () => {
    if (!cancellation?.preview || cancellation.busy) return;
    const { separation, preview } = cancellation;
    const body = {
      expectedVersion: preview.expectedVersion,
      cancellationDigest: preview.cancellationDigest,
    };
    setCancellation({ ...cancellation, busy: true, failure: null });
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/separations/{separationId}/cancel',
        {
          params: {
            path: { employeeId, separationId: separation.id },
            header: {
              'Idempotency-Key': cancelKey.keyFor({ separationId: separation.id, ...body }),
            },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        cancelKey.reset();
        setCancellation(null);
        setFormKey((key) => key + 1);
        onChanged(
          'separation.announce.cancelled',
          { date: formatDate(i18n.language, separation.lastDay) },
          true,
        );
        return;
      }
      const next = historyFailureOf(response, error);
      const stale = next.code !== undefined && SEPARATION_STALE.has(next.code);
      if (stale) cancelKey.reset();
      setCancellation({ separation, preview: stale ? null : preview, failure: next, busy: false });
    } catch {
      setCancellation({ separation, preview, failure: HISTORY_NETWORK_FAILURE, busy: false });
    }
  };

  const updateTask = async (
    separation: Separation,
    task: SeparationTask,
    status: SettableTaskStatus,
  ) => {
    if (busyTask || status === task.status) return;
    const body = { status, expectedVersion: task.version };
    setBusyTask(task.id);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/separations/{separationId}/tasks/{taskId}/status',
        {
          params: {
            path: { employeeId, separationId: separation.id, taskId: task.id },
            header: { 'Idempotency-Key': taskKey.keyFor({ taskId: task.id, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        taskKey.reset();
        replace({
          ...separation,
          tasks: separation.tasks.map((item) => (item.id === data.id ? data : item)),
        });
        onChanged(
          'separation.announce.task',
          {
            task: t(`separation.tasks.${data.code}`),
            status: t(`separation.taskStatus.${data.status}`),
          },
          false,
        );
      } else {
        const next = historyFailureOf(response, error);
        if (next.code === 'SEPARATION_TASK_VERSION_CONFLICT') {
          taskKey.reset();
          setLoaded(await load());
        }
        refused(next);
      }
    } catch {
      refused(HISTORY_NETWORK_FAILURE);
    } finally {
      setBusyTask(null);
    }
  };

  const retry = async (separation: Separation) => {
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/separations/{separationId}/access-revocation/retry',
        {
          params: {
            path: { employeeId, separationId: separation.id },
            header: { 'Idempotency-Key': retryKey.keyFor({ retry: separation.id }) },
          },
          cache: 'no-store',
        },
      );
      if (data) {
        retryKey.reset();
        replace(data);
        onChanged('separation.announce.retried', {}, false);
      } else {
        retryKey.reset();
        refused(historyFailureOf(response, error));
      }
    } catch {
      refused(HISTORY_NETWORK_FAILURE);
    }
  };

  const inForce =
    loaded.kind === 'ready' && loaded.items.some((item) => item.state !== 'CANCELLED');

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="separations">
      <h2 id={`${ids}-title`}>{t('separation.title')}</h2>
      {loaded.kind === 'loading' && <p>{t('employees.loading')}</p>}
      {loaded.kind === 'failed' && (
        <>
          <HistoryAlert failure={loaded.failure} data-testid="separations-error" />
          <button
            type="button"
            className="button"
            onClick={() => {
              setLoaded({ kind: 'loading' });
              void load().then(setLoaded);
            }}
          >
            {t('employees.retry')}
          </button>
        </>
      )}
      {loaded.kind === 'ready' && loaded.items.length === 0 && (
        <p className="muted">{t('separation.none')}</p>
      )}
      {loaded.kind === 'ready' &&
        loaded.items.map((separation) => (
          <article
            key={separation.id}
            className="separation"
            data-testid="separation"
            data-state={separation.state}
            aria-labelledby={`${ids}-${separation.id}`}
          >
            <h3 id={`${ids}-${separation.id}`}>
              {t('separation.heading', {
                date: formatDate(i18n.language, separation.lastDay),
                state: t(`separation.state.${separation.state}`),
              })}
            </h3>
            <dl className="summary">
              <dt>{t('separation.form.reason')}</dt>
              <dd>{t(`separation.reasons.${separation.reasonCode}`)}</dd>
              <dt>{t('separation.form.accessTiming')}</dt>
              <dd>{t(`separation.timing.${separation.accessTiming}`)}</dd>
              <dt>{t('separation.accessLabel')}</dt>
              <dd data-testid="separation-access-state" data-access={separation.access}>
                {t(`separation.access.state.${separation.access}`, {
                  at: separation.accessEndsAt
                    ? formatInstant(i18n.language, separation.accessEndsAt)
                    : '',
                })}
              </dd>
              <dt>{t('separation.reports.label')}</dt>
              <dd>
                {separation.reportCount === 0
                  ? t('separation.reports.noneAffected')
                  : t('separation.reports.summary', {
                      count: separation.reportCount,
                      intervals: separation.intervalCount,
                      action: separation.reportAction
                        ? t(`separation.plan.${separation.reportAction}`)
                        : '',
                    })}
              </dd>
            </dl>
            {separation.tasks.length > 0 && (
              <ScrollRegion label={t('separation.checklist.caption')}>
                <table data-testid="separation-tasks">
                  <caption>{t('separation.checklist.caption')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('separation.checklist.task')}</th>
                      <th scope="col">{t('separation.checklist.due')}</th>
                      <th scope="col">{t('separation.checklist.status')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {separation.tasks.map((task) => (
                      <tr key={task.id} data-testid="separation-task">
                        <th scope="row">{t(`separation.tasks.${task.code}`)}</th>
                        <td>{formatDate(i18n.language, task.dueDate)}</td>
                        <td>
                          {task.status === 'CANCELLED' || separation.state === 'CANCELLED' ? (
                            t(`separation.taskStatus.${task.status}`)
                          ) : (
                            <select
                              aria-label={t('separation.checklist.statusOf', {
                                task: t(`separation.tasks.${task.code}`),
                              })}
                              value={task.status}
                              disabled={busyTask !== null}
                              onChange={(event) =>
                                void updateTask(
                                  separation,
                                  task,
                                  event.target.value as SettableTaskStatus,
                                )
                              }
                            >
                              {TASK_STATUSES.map((status) => (
                                <option key={status} value={status}>
                                  {t(`separation.taskStatus.${status}`)}
                                </option>
                              ))}
                            </select>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
            )}
            <div className="actions">
              {separation.cancellable && (
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => void previewCancellation(separation)}
                >
                  {t('separation.cancel')}
                </button>
              )}
              {separation.access === 'MANUAL_INTERVENTION' && (
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => void retry(separation)}
                >
                  {t('separation.retry')}
                </button>
              )}
            </div>
          </article>
        ))}
      {failure && (
        <HistoryAlert failure={failure} ref={failureBox} data-testid="separations-failure" />
      )}

      {cancellation && (
        <section aria-labelledby={`${ids}-cancel`} data-testid="separation-cancel-panel">
          <h3 id={`${ids}-cancel`} tabIndex={-1} ref={panelHeading}>
            {t('separation.cancelTitle', {
              date: formatDate(i18n.language, cancellation.separation.lastDay),
            })}
          </h3>
          {cancellation.busy && !cancellation.preview && <p>{t('employees.loading')}</p>}
          {cancellation.failure && (
            <HistoryAlert failure={cancellation.failure} data-testid="separation-cancel-error" />
          )}
          {cancellation.preview && (
            <>
              <p>
                {t('separation.cancelHelp', {
                  count: cancellation.preview.reportCount,
                  intervals: cancellation.preview.intervalCount,
                })}
              </p>
              {cancellation.preview.kinds.length > 0 && (
                <PreviewTable
                  kinds={cancellation.preview.kinds}
                  beforeLabel={t('employees.preview.removed')}
                  afterLabel={t('employees.preview.restored')}
                  data-testid="separation-cancel-preview"
                />
              )}
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
                {cancellation.busy ? t('employees.form.saving') : t('separation.confirmCancel')}
              </button>
            )}
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setCancellation(null);
              }}
            >
              {t('separation.keep')}
            </button>
          </div>
        </section>
      )}

      {loaded.kind === 'ready' && !inForce && (
        <SeparationForm
          key={formKey}
          employeeId={employeeId}
          businessDate={businessDate}
          onRecorded={(separation) => {
            setFormKey((key) => key + 1);
            onChanged(
              'separation.announce.recorded',
              { date: formatDate(i18n.language, separation.lastDay) },
              true,
            );
          }}
        />
      )}
    </section>
  );
}

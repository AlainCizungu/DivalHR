import type {
  EmployeeSummary,
  Task,
  TaskCancelReason,
  TaskDetail,
  TaskDueClass,
  TaskState,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { EmployeeSearchPicker } from '../leave/EmployeeSearchPicker';
import { PageHeader } from '../../ui/primitives';
import { DueLine, TaskAlert, TaskBadges } from './TaskBits';
import {
  CANCEL_REASONS,
  DUE_FILTERS,
  EMPTY_CREATE,
  EXPLANATION_MAX,
  EXPLANATION_MIN,
  INSTRUCTIONS_MAX,
  NETWORK_FAILURE,
  STATE_FILTERS,
  TITLE_MAX,
  cancelProblemsOf,
  codePoints,
  createBodyOf,
  createProblemsOf,
  formatInstant,
  normalizeText,
  taskFailureOf,
  type CancelField,
  type CreateField,
  type CreateForm,
  type TaskFailure,
} from './tasks';

type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: Task[]; next: string | null }
  | { kind: 'failed'; failure: TaskFailure };

/** The employee as shown next to a task: the name when this session knows it, else a short reference. */
const shortRef = (id: string) => id.slice(0, 8);

/**
 * MVP-080: administrator "Tasks". Assigns a task to one active employee found with the directory
 * search, lists tasks with state and due filters (keyset paging, newest cursor bound to the
 * filters), reads one task with its immutable history, and cancels an unfinished one after an
 * explicit confirmation with a closed reason. One command at a time; each carries a stable
 * idempotency key and the expected version. Nothing is kept in browser storage or put in a URL.
 */
export function AdminTasksPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const createKey = useIdempotencyKey();
  const cancelKey = useIdempotencyKey();
  const [form, setForm] = useState<CreateForm>(EMPTY_CREATE);
  const [assignee, setAssignee] = useState<EmployeeSummary | null>(null);
  const [problems, setProblems] = useState<Set<CreateField>>(new Set());
  const [createFailure, setCreateFailure] = useState<TaskFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const [state, setState] = useState<TaskState | ''>('');
  const [due, setDue] = useState<TaskDueClass | ''>('');
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [more, setMore] = useState(false);
  const [detail, setDetail] = useState<TaskDetail | null>(null);
  const [detailName, setDetailName] = useState<string | null>(null);
  const [detailFailure, setDetailFailure] = useState<TaskFailure | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState<TaskCancelReason | ''>('');
  const [explanation, setExplanation] = useState('');
  const [cancelProblems, setCancelProblems] = useState<Set<CancelField>>(new Set());
  const [cancelFailure, setCancelFailure] = useState<TaskFailure | null>(null);
  const sending = useRef(false);
  const [names, setNames] = useState<Record<string, string>>({});
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const detailRef = useRef<HTMLHeadingElement>(null);
  const titleRef = useRef<HTMLInputElement>(null);
  const reasonRef = useRef<HTMLSelectElement>(null);
  const listHeading = useRef<HTMLHeadingElement>(null);

  const query = useCallback(
    (cursor?: string) => ({
      limit: 25,
      ...(state ? { state } : {}),
      ...(due ? { due } : {}),
      ...(cursor ? { cursor } : {}),
    }),
    [state, due],
  );

  const fetchFirst = useCallback(async (): Promise<Load> => {
    try {
      const { data, error, response } = await core.GET('/tasks', {
        params: { query: query() },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', items: data.items, next: data.nextCursor }
        : { kind: 'failed', failure: taskFailureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: NETWORK_FAILURE };
    }
  }, [core, query]);

  const refresh = useCallback(async () => {
    setLoad(await fetchFirst());
  }, [fetchFirst]);

  useEffect(() => {
    let active = true;
    void fetchFirst().then((next) => {
      if (active) setLoad(next);
    });
    return () => {
      active = false;
    };
  }, [fetchFirst]);

  const loadMore = async () => {
    if (load.kind !== 'ready' || !load.next || more) return;
    setMore(true);
    try {
      const { data, error, response } = await core.GET('/tasks', {
        params: { query: query(load.next) },
        cache: 'no-store',
      });
      if (data) {
        setLoad({ kind: 'ready', items: [...load.items, ...data.items], next: data.nextCursor });
      } else {
        setLoad({ kind: 'failed', failure: taskFailureOf(response, error) });
      }
    } catch {
      setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
    } finally {
      setMore(false);
    }
  };

  useEffect(() => {
    if (createFailure) failureRef.current?.focus();
    else if (problems.size > 0) problemsRef.current?.focus();
  }, [createFailure, problems]);

  const set = <K extends keyof CreateForm>(key: K, value: CreateForm[K]) => {
    setForm((previous) => ({ ...previous, [key]: value }));
  };

  const create = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sending.current) return;
    const found = createProblemsOf(form, new Date());
    setProblems(found);
    setCreateFailure(null);
    if (found.size > 0) return;
    sending.current = true;
    setBusy(true);
    const body = createBodyOf(form);
    try {
      const { data, error, response } = await core.POST('/tasks', {
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
        body,
      });
      if (data) {
        if (assignee) {
          const known = t('tasks.admin.assignee', {
            given: assignee.givenNames,
            family: assignee.familyName,
            number: assignee.employeeNumber,
          });
          setNames((previous) => ({ ...previous, [assignee.id]: known }));
        }
        setForm(EMPTY_CREATE);
        setAssignee(null);
        createKey.reset();
        setAnnouncement(t('tasks.admin.created'));
        await refresh();
        requestAnimationFrame(() => listHeading.current?.focus());
        return;
      }
      const refused = taskFailureOf(response, error);
      if (refused.kind === 'validation' && refused.fields) {
        setProblems(new Set(refused.fields as CreateField[]));
      }
      setCreateFailure(refused);
    } catch {
      setCreateFailure(NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const open = async (id: string) => {
    setDetailFailure(null);
    setCancelling(false);
    setCancelFailure(null);
    try {
      const { data, error, response } = await core.GET('/tasks/{taskId}', {
        params: { path: { taskId: id } },
        cache: 'no-store',
      });
      if (!data) {
        setDetail(null);
        setDetailFailure(taskFailureOf(response, error));
        return;
      }
      setDetail(data);
      let label = names[data.assigneeEmployeeId] ?? null;
      if (!label) {
        const profile = await core.GET('/employees/{employeeId}', {
          params: { path: { employeeId: data.assigneeEmployeeId } },
          cache: 'no-store',
        });
        if (profile.data) {
          label = t('tasks.admin.assignee', {
            given: profile.data.givenNames,
            family: profile.data.familyName,
            number: profile.data.employeeNumber,
          });
          const known = label;
          setNames((previous) => ({ ...previous, [data.assigneeEmployeeId]: known }));
        }
      }
      setDetailName(label);
      requestAnimationFrame(() => detailRef.current?.focus());
    } catch {
      setDetailFailure(NETWORK_FAILURE);
    }
  };

  const cancel = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!detail || sending.current) return;
    const found = cancelProblemsOf(reason, explanation);
    setCancelProblems(found);
    setCancelFailure(null);
    if (found.size > 0 || !reason) {
      reasonRef.current?.focus();
      return;
    }
    sending.current = true;
    setBusy(true);
    const text = normalizeText(explanation);
    const body = {
      expectedVersion: detail.version,
      reason,
      explanation: reason === 'OTHER' ? text : null,
    };
    try {
      const { data, error, response } = await core.POST('/tasks/{taskId}/cancel', {
        params: {
          path: { taskId: detail.id },
          header: { 'Idempotency-Key': cancelKey.keyFor({ id: detail.id, body }) },
        },
        body,
      });
      if (data) {
        setCancelling(false);
        setReason('');
        setExplanation('');
        setAnnouncement(t('tasks.admin.cancelled'));
        await Promise.all([refresh(), open(detail.id)]);
        return;
      }
      const refused = taskFailureOf(response, error);
      setCancelFailure(refused);
      if (refused.kind === 'conflict' || refused.kind === 'settled') {
        void refresh();
        void open(detail.id);
      }
    } catch {
      setCancelFailure(NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const label = (id: string) => names[id] ?? t('tasks.admin.ref', { id: shortRef(id) });
  const problemText = (field: CreateField) => t(`tasks.problems.${field}`);
  const fieldError = (field: CreateField) =>
    problems.has(field) ? (
      <p id={`${ids}-${field}-error`} className="field__error">
        {problemText(field)}
      </p>
    ) : null;
  const describedBy = (field: CreateField) =>
    [`${ids}-${field}-help`, problems.has(field) ? `${ids}-${field}-error` : null]
      .filter(Boolean)
      .join(' ');
  const unfinished = detail?.state === 'OPEN' || detail?.state === 'IN_PROGRESS';

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="admin-tasks">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('tasks.admin.title')}
        description={t('tasks.admin.description')}
      />
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-create`} className="card">
        <h2 id={`${ids}-create`}>{t('tasks.admin.create.title')}</h2>
        <form noValidate onSubmit={(event) => void create(event)} data-testid="task-form">
          {problems.size > 0 && !createFailure && (
            <div
              className="error-summary"
              role="alert"
              tabIndex={-1}
              ref={problemsRef}
              data-testid="task-problems"
            >
              <p>{t('employees.form.problems.title')}</p>
              <ul>
                {[...problems].map((problem) => (
                  <li key={problem}>{problemText(problem)}</li>
                ))}
              </ul>
            </div>
          )}
          {createFailure && (
            <TaskAlert failure={createFailure} testId="task-create-error" alertRef={failureRef} />
          )}
          <EmployeeSearchPicker
            idPrefix={`${ids}-assignee`}
            legend={t('tasks.admin.create.assignee')}
            value={assignee}
            onChange={(chosen) => {
              setAssignee(chosen);
              set('assigneeId', chosen?.id ?? '');
            }}
            invalid={problems.has('assignee')}
            errorId={`${ids}-assignee-error`}
            error={problems.has('assignee') ? problemText('assignee') : null}
            testId="task-assignee"
          />
          <div className="field">
            <label htmlFor={`${ids}-title-input`}>{t('tasks.admin.create.titleLabel')}</label>
            <p id={`${ids}-title-help`} className="field__help">
              {t('tasks.admin.create.titleHelp', { max: TITLE_MAX })}
            </p>
            {fieldError('title')}
            <input
              id={`${ids}-title-input`}
              ref={titleRef}
              value={form.title}
              autoComplete="off"
              aria-invalid={problems.has('title') ? true : undefined}
              aria-describedby={describedBy('title')}
              onChange={(event) => {
                set('title', event.target.value);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={`${ids}-instructions-input`}>
              {t('tasks.admin.create.instructions')}
            </label>
            <p id={`${ids}-instructions-help`} className="field__help">
              {t('tasks.admin.create.instructionsHelp', { max: INSTRUCTIONS_MAX })}
            </p>
            {fieldError('instructions')}
            <textarea
              id={`${ids}-instructions-input`}
              rows={4}
              value={form.instructions}
              autoComplete="off"
              aria-invalid={problems.has('instructions') ? true : undefined}
              aria-describedby={describedBy('instructions')}
              onChange={(event) => {
                set('instructions', event.target.value);
              }}
            />
            <p className="field__help">
              {t('tasks.count', {
                n: codePoints(normalizeText(form.instructions)),
                max: INSTRUCTIONS_MAX,
              })}
            </p>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-priority-input`}>{t('tasks.admin.create.priority')}</label>
            <select
              id={`${ids}-priority-input`}
              value={form.priority}
              onChange={(event) => {
                set('priority', event.target.value as CreateForm['priority']);
              }}
            >
              {(['NORMAL', 'HIGH', 'URGENT'] as const).map((priority) => (
                <option key={priority} value={priority}>
                  {t(`tasks.priority.${priority}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-due-input`}>{t('tasks.admin.create.due')}</label>
            <p id={`${ids}-dueAt-help`} className="field__help">
              {t('tasks.admin.create.dueHelp')}
            </p>
            {fieldError('dueAt')}
            <input
              id={`${ids}-due-input`}
              type="datetime-local"
              value={form.due}
              aria-invalid={problems.has('dueAt') ? true : undefined}
              aria-describedby={describedBy('dueAt')}
              onChange={(event) => {
                set('due', event.target.value);
              }}
            />
          </div>
          <div className="dialog-actions">
            <button
              type="submit"
              className="button"
              disabled={busy}
              aria-busy={busy ? true : undefined}
            >
              {busy ? t('tasks.sending') : t('tasks.admin.create.submit')}
            </button>
          </div>
        </form>
      </section>

      <section aria-labelledby={`${ids}-list`} className="card" data-testid="task-list">
        <h2 id={`${ids}-list`} tabIndex={-1} ref={listHeading}>
          {t('tasks.admin.list.title')}
        </h2>
        <div className="toolbar">
          <div className="field">
            <label htmlFor={`${ids}-state-filter`}>{t('tasks.admin.list.state')}</label>
            <select
              id={`${ids}-state-filter`}
              value={state}
              onChange={(event) => {
                setState(event.target.value as TaskState | '');
              }}
            >
              <option value="">{t('tasks.admin.list.any')}</option>
              {STATE_FILTERS.map((value) => (
                <option key={value} value={value}>
                  {t(`tasks.state.${value}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-due-filter`}>{t('tasks.admin.list.due')}</label>
            <select
              id={`${ids}-due-filter`}
              value={due}
              onChange={(event) => {
                setDue(event.target.value as TaskDueClass | '');
              }}
            >
              <option value="">{t('tasks.admin.list.any')}</option>
              {DUE_FILTERS.map((value) => (
                <option key={value} value={value}>
                  {t(`tasks.dueClass.${value}`)}
                </option>
              ))}
            </select>
          </div>
        </div>
        {load.kind === 'loading' && <p role="status">{t('tasks.loading')}</p>}
        {load.kind === 'failed' && <TaskAlert failure={load.failure} testId="tasks-error" />}
        {load.kind === 'ready' && load.items.length === 0 && (
          <p data-testid="tasks-empty">{t('tasks.admin.list.empty')}</p>
        )}
        {load.kind === 'ready' && load.items.length > 0 && (
          <ul className="plain-list">
            {load.items.map((task) => (
              <li key={task.id} className="hierarchy-item" data-testid="admin-task">
                <h3 data-testid="task-title">{task.title}</h3>
                <TaskBadges state={task.state} priority={task.priority} dueClass={task.dueClass} />
                <p className="muted">
                  {label(task.assigneeEmployeeId)} ·{' '}
                  <DueLine dueAt={task.dueAt} language={language} />
                </p>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => void open(task.id)}
                >
                  {t('tasks.admin.list.open')}
                  <span className="visually-hidden"> {task.title}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {load.kind === 'ready' && load.next && (
          <button
            type="button"
            className="button button--secondary"
            disabled={more}
            onClick={() => void loadMore()}
          >
            {t('tasks.admin.list.more')}
          </button>
        )}
      </section>

      {(detail || detailFailure) && (
        <section aria-labelledby={`${ids}-detail`} className="card" data-testid="task-detail">
          {detailFailure && <TaskAlert failure={detailFailure} testId="detail-error" />}
          {detail && (
            <>
              <h2 id={`${ids}-detail`} tabIndex={-1} ref={detailRef}>
                {detail.title}
              </h2>
              <TaskBadges
                state={detail.state}
                priority={detail.priority}
                dueClass={detail.dueClass}
              />
              <dl className="decision-summary">
                <dt>{t('tasks.admin.detail.assignee')}</dt>
                <dd>{detailName ?? label(detail.assigneeEmployeeId)}</dd>
                <dt>{t('tasks.admin.detail.due')}</dt>
                <dd>
                  <DueLine dueAt={detail.dueAt} language={language} />
                </dd>
                {detail.instructions && (
                  <>
                    <dt>{t('tasks.admin.detail.instructions')}</dt>
                    <dd className="task-text">{detail.instructions}</dd>
                  </>
                )}
                {detail.completionNote && (
                  <>
                    <dt>{t('tasks.admin.detail.note')}</dt>
                    <dd className="task-text">{detail.completionNote}</dd>
                  </>
                )}
                {detail.cancelReason && (
                  <>
                    <dt>{t('tasks.admin.detail.cancelReason')}</dt>
                    <dd>
                      {t(`tasks.cancelReason.${detail.cancelReason}`)}
                      {detail.cancelExplanation ? ` — ${detail.cancelExplanation}` : ''}
                    </dd>
                  </>
                )}
              </dl>
              <h3>{t('tasks.admin.detail.history')}</h3>
              <ol className="plain-list" data-testid="task-history">
                {detail.history.map((action) => (
                  <li key={action.seq}>
                    {t(`tasks.action.${action.action}`, {
                      date: formatInstant(language, action.occurredAt),
                      role: t(
                        action.actorRole === 'tenant-admin'
                          ? 'tasks.admin.detail.byAdmin'
                          : 'tasks.admin.detail.byAssignee',
                      ),
                    })}
                  </li>
                ))}
              </ol>
              {unfinished && !cancelling && (
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setCancelling(true);
                    setCancelProblems(new Set());
                    setCancelFailure(null);
                    requestAnimationFrame(() => reasonRef.current?.focus());
                  }}
                >
                  {t('tasks.admin.cancel.open')}
                </button>
              )}
              {unfinished && cancelling && (
                <form
                  noValidate
                  onSubmit={(event) => void cancel(event)}
                  data-testid="cancel-confirm"
                  aria-labelledby={`${ids}-cancel-title`}
                >
                  <h3 id={`${ids}-cancel-title`}>{t('tasks.admin.cancel.title')}</h3>
                  <p>{t('tasks.admin.cancel.warning', { title: detail.title })}</p>
                  {cancelFailure && <TaskAlert failure={cancelFailure} testId="cancel-error" />}
                  <div className="field">
                    <label htmlFor={`${ids}-reason`}>{t('tasks.admin.cancel.reason')}</label>
                    {cancelProblems.has('reason') && (
                      <p id={`${ids}-reason-error`} className="field__error">
                        {t('tasks.problems.reason')}
                      </p>
                    )}
                    <select
                      id={`${ids}-reason`}
                      ref={reasonRef}
                      value={reason}
                      aria-invalid={cancelProblems.has('reason') ? true : undefined}
                      aria-describedby={
                        cancelProblems.has('reason') ? `${ids}-reason-error` : undefined
                      }
                      onChange={(event) => {
                        setReason(event.target.value as TaskCancelReason | '');
                      }}
                    >
                      <option value="">{t('tasks.admin.cancel.choose')}</option>
                      {CANCEL_REASONS.map((value) => (
                        <option key={value} value={value}>
                          {t(`tasks.cancelReason.${value}`)}
                        </option>
                      ))}
                    </select>
                  </div>
                  {reason === 'OTHER' && (
                    <div className="field">
                      <label htmlFor={`${ids}-explanation`}>
                        {t('tasks.admin.cancel.explanation')}
                      </label>
                      <p id={`${ids}-explanation-help`} className="field__help">
                        {t('tasks.admin.cancel.explanationHelp', {
                          min: EXPLANATION_MIN,
                          max: EXPLANATION_MAX,
                        })}
                      </p>
                      {cancelProblems.has('explanation') && (
                        <p id={`${ids}-explanation-error`} className="field__error">
                          {t('tasks.problems.explanation')}
                        </p>
                      )}
                      <textarea
                        id={`${ids}-explanation`}
                        rows={3}
                        value={explanation}
                        autoComplete="off"
                        aria-invalid={cancelProblems.has('explanation') ? true : undefined}
                        aria-describedby={`${ids}-explanation-help${
                          cancelProblems.has('explanation') ? ` ${ids}-explanation-error` : ''
                        }`}
                        onChange={(event) => {
                          setExplanation(event.target.value);
                        }}
                      />
                    </div>
                  )}
                  <div className="dialog-actions">
                    <button type="submit" className="button" disabled={busy}>
                      {busy ? t('tasks.sending') : t('tasks.admin.cancel.confirm')}
                    </button>
                    <button
                      type="button"
                      className="button button--secondary"
                      disabled={busy}
                      onClick={() => {
                        setCancelling(false);
                      }}
                    >
                      {t('tasks.admin.cancel.keep')}
                    </button>
                  </div>
                </form>
              )}
            </>
          )}
        </section>
      )}
    </section>
  );
}

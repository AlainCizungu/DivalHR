import type { MyTask } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PageHeader } from '../../ui/primitives';
import { DueLine, TaskAlert, TaskBadges } from './TaskBits';
import {
  NETWORK_FAILURE,
  NOTE_MAX,
  codePoints,
  groupOf,
  normalizeText,
  taskFailureOf,
  textAcceptable,
  formatInstant,
  type Group,
  type TaskFailure,
} from './tasks';

type Load =
  | { kind: 'loading' }
  | { kind: 'ready'; items: MyTask[] }
  | { kind: 'failed'; failure: TaskFailure };
const GROUPS: readonly Group[] = ['open', 'inProgress', 'closed'];
const MAX_PAGES = 5;

/**
 * MVP-080: « Mes tâches » / "My tasks". The linked employee sees only their own tasks, grouped
 * Open, In progress and Completed or cancelled, starts one, and completes one after an explicit
 * confirmation with an optional note. One command at a time; every command carries a stable
 * idempotency key and the expected version. Text entered by others is shown unchanged and never
 * kept in browser storage or put in a URL.
 */
export function MyTasksPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const startKey = useIdempotencyKey();
  const completeKey = useIdempotencyKey();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<{ id: string; failure: TaskFailure } | null>(null);
  const [completing, setCompleting] = useState<MyTask | null>(null);
  const [note, setNote] = useState('');
  const [noteInvalid, setNoteInvalid] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const sending = useRef(false);
  const headingRefs = useRef(new Map<Group, HTMLHeadingElement>());
  const noteRef = useRef<HTMLTextAreaElement>(null);

  const fetchAll = useCallback(async (): Promise<Load> => {
    try {
      const items: MyTask[] = [];
      let cursor: string | undefined;
      for (let page = 0; page < MAX_PAGES; page += 1) {
        const { data, error, response } = await core.GET('/me/tasks', {
          params: { query: { limit: 100, ...(cursor ? { cursor } : {}) } },
          cache: 'no-store',
        });
        if (!data) return { kind: 'failed', failure: taskFailureOf(response, error) };
        items.push(...data.items);
        cursor = data.nextCursor ?? undefined;
        if (!cursor) break;
      }
      return { kind: 'ready', items };
    } catch {
      return { kind: 'failed', failure: NETWORK_FAILURE };
    }
  }, [core]);

  const refresh = useCallback(async () => {
    setLoad(await fetchAll());
  }, [fetchAll]);

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
    if (completing) noteRef.current?.focus();
  }, [completing]);

  const run = async (task: MyTask, action: 'start' | 'complete') => {
    if (sending.current) return;
    const text = normalizeText(note);
    if (action === 'complete' && text && !textAcceptable(text, 1, NOTE_MAX, { multiline: true })) {
      setNoteInvalid(true);
      noteRef.current?.focus();
      return;
    }
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      const params = { path: { taskId: task.id } };
      const result =
        action === 'start'
          ? await core.POST('/me/tasks/{taskId}/start', {
              params: {
                ...params,
                header: { 'Idempotency-Key': startKey.keyFor({ id: task.id, v: task.version }) },
              },
              body: { expectedVersion: task.version },
            })
          : await core.POST('/me/tasks/{taskId}/complete', {
              params: {
                ...params,
                header: {
                  'Idempotency-Key': completeKey.keyFor({ id: task.id, v: task.version, text }),
                },
              },
              body: { expectedVersion: task.version, note: text || null },
            });
      if (result.data) {
        setCompleting(null);
        setNote('');
        setNoteInvalid(false);
        setAnnouncement(t(action === 'start' ? 'tasks.me.started' : 'tasks.me.completed'));
        await refresh();
        const group: Group = action === 'start' ? 'inProgress' : 'closed';
        requestAnimationFrame(() => headingRefs.current.get(group)?.focus());
        return;
      }
      const refused = taskFailureOf(result.response, result.error);
      setFailure({ id: task.id, failure: refused });
      if (
        refused.kind === 'conflict' ||
        refused.kind === 'settled' ||
        refused.kind === 'notFound'
      ) {
        setCompleting(null);
        await refresh();
      }
    } catch {
      setFailure({ id: task.id, failure: NETWORK_FAILURE });
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const items = load.kind === 'ready' ? load.items : [];
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-tasks">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('tasks.me.title')}
        description={t('tasks.me.description')}
      />
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>
      {load.kind === 'loading' && <p role="status">{t('tasks.loading')}</p>}
      {load.kind === 'failed' && <TaskAlert failure={load.failure} testId="tasks-error" />}
      {load.kind === 'ready' && items.length === 0 && (
        <p className="card" data-testid="tasks-empty">
          {t('tasks.me.empty')}
        </p>
      )}
      {load.kind === 'ready' &&
        items.length > 0 &&
        GROUPS.map((group) => {
          const inGroup = items.filter((task) => groupOf(task.state) === group);
          return (
            <section
              key={group}
              aria-labelledby={`${ids}-${group}`}
              className="card"
              data-testid={`group-${group}`}
            >
              <h2
                id={`${ids}-${group}`}
                tabIndex={-1}
                ref={(element) => {
                  if (element) headingRefs.current.set(group, element);
                }}
              >
                {t(`tasks.me.groups.${group}`, { n: inGroup.length })}
              </h2>
              {inGroup.length === 0 && <p className="muted">{t('tasks.me.groupEmpty')}</p>}
              <ul className="plain-list">
                {inGroup.map((task) => (
                  <li key={task.id} className="hierarchy-item" data-testid="my-task">
                    <h3 data-testid="task-title">{task.title}</h3>
                    <TaskBadges
                      state={task.state}
                      priority={task.priority}
                      dueClass={task.dueClass}
                    />
                    <p className="muted">
                      <DueLine dueAt={task.dueAt} language={language} />
                    </p>
                    {task.instructions && (
                      <p className="task-text" data-testid="task-instructions">
                        {task.instructions}
                      </p>
                    )}
                    {task.state === 'COMPLETED' && task.completedAt && (
                      <p className="muted">
                        {t('tasks.completedOn', {
                          date: formatInstant(language, task.completedAt),
                        })}
                      </p>
                    )}
                    {task.completionNote && (
                      <p className="task-text">
                        {t('tasks.noteLabel')} {task.completionNote}
                      </p>
                    )}
                    {task.state === 'CANCELLED' && (
                      <p className="muted">
                        {t('tasks.cancelledWith', {
                          reason: t(`tasks.cancelReason.${task.cancelReason ?? 'OTHER'}`),
                        })}
                      </p>
                    )}
                    {failure?.id === task.id && (
                      <TaskAlert failure={failure.failure} testId="task-error" />
                    )}
                    {(task.state === 'OPEN' || task.state === 'IN_PROGRESS') &&
                      completing?.id !== task.id && (
                        <div className="dialog-actions">
                          {task.state === 'OPEN' && (
                            <button
                              type="button"
                              className="button button--secondary"
                              disabled={busy || completing !== null}
                              aria-busy={busy ? true : undefined}
                              onClick={() => void run(task, 'start')}
                            >
                              {t('tasks.me.start')}
                            </button>
                          )}
                          <button
                            type="button"
                            className="button"
                            disabled={busy || completing !== null}
                            onClick={() => {
                              setFailure(null);
                              setNote('');
                              setNoteInvalid(false);
                              setCompleting(task);
                            }}
                          >
                            {t('tasks.me.complete')}
                          </button>
                        </div>
                      )}
                    {completing?.id === task.id && (
                      <form
                        noValidate
                        data-testid="complete-confirm"
                        onSubmit={(event) => {
                          event.preventDefault();
                          void run(task, 'complete');
                        }}
                      >
                        <p>{t('tasks.me.confirmComplete', { title: task.title })}</p>
                        <div className="field">
                          <label htmlFor={`${ids}-note`}>{t('tasks.me.note')}</label>
                          <p id={`${ids}-note-help`} className="field__help">
                            {t('tasks.me.noteHelp', { max: NOTE_MAX })}
                          </p>
                          {noteInvalid && (
                            <p id={`${ids}-note-error`} className="field__error">
                              {t('tasks.problems.note')}
                            </p>
                          )}
                          <textarea
                            id={`${ids}-note`}
                            ref={noteRef}
                            rows={3}
                            value={note}
                            autoComplete="off"
                            aria-invalid={noteInvalid ? true : undefined}
                            aria-describedby={`${ids}-note-help${noteInvalid ? ` ${ids}-note-error` : ''}`}
                            onChange={(event) => {
                              setNote(event.target.value);
                              setNoteInvalid(false);
                            }}
                          />
                          <p className="field__help">
                            {t('tasks.count', {
                              n: codePoints(normalizeText(note)),
                              max: NOTE_MAX,
                            })}
                          </p>
                        </div>
                        <div className="dialog-actions">
                          <button type="submit" className="button" disabled={busy}>
                            {busy ? t('tasks.sending') : t('tasks.me.confirmButton')}
                          </button>
                          <button
                            type="button"
                            className="button button--secondary"
                            disabled={busy}
                            onClick={() => {
                              setCompleting(null);
                            }}
                          >
                            {t('tasks.me.keep')}
                          </button>
                        </div>
                      </form>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
    </section>
  );
}

import type { TaskDueClass, TaskPriority, TaskState } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusTone } from '../../ui/primitives';
import { dueTone, formatInstant, type TaskFailure } from './tasks';

const STATE_TONE: Record<TaskState, StatusTone> = {
  OPEN: 'info',
  IN_PROGRESS: 'info',
  COMPLETED: 'success',
  CANCELLED: 'neutral',
};

/** State, priority and due classification, always as text. */
export function TaskBadges({
  state,
  priority,
  dueClass,
}: {
  state: TaskState;
  priority: TaskPriority;
  dueClass: TaskDueClass | null;
}) {
  const { t } = useTranslation();
  return (
    <span className="meta">
      <StatusBadge tone={STATE_TONE[state]} testId="task-state">
        {t(`tasks.state.${state}`)}
      </StatusBadge>{' '}
      {priority !== 'NORMAL' && (
        <StatusBadge tone={priority === 'URGENT' ? 'danger' : 'warning'} testId="task-priority">
          {t(`tasks.priority.${priority}`)}
        </StatusBadge>
      )}{' '}
      {dueClass && (dueClass === 'OVERDUE' || dueClass === 'DUE_SOON') && (
        <StatusBadge tone={dueTone(dueClass)} testId="task-due-class">
          {t(`tasks.dueClass.${dueClass}`)}
        </StatusBadge>
      )}
    </span>
  );
}

/** The due line: the instant in the interface language, or that there is none. */
export function DueLine({ dueAt, language }: { dueAt: string | null; language: string }) {
  const { t } = useTranslation();
  return (
    <span>
      {dueAt ? t('tasks.due', { date: formatInstant(language, dueAt) }) : t('tasks.noDue')}
    </span>
  );
}

/** A refused command: the stable message, the named fields, retry delay and reference. */
export function TaskAlert({
  failure,
  testId,
  alertRef,
}: {
  failure: TaskFailure;
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
            <li key={field}>{t(`tasks.problems.${field}`)}</li>
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

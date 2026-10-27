import type { Problem, TaskCancelReason, TaskDueClass, TaskState } from '@divalhr/api-client';

/** MVP-080 limits (D80-2, D80-4); the server checks everything again. */
export const TITLE_MAX = 120;
export const INSTRUCTIONS_MAX = 1000;
export const NOTE_MAX = 500;
export const EXPLANATION_MIN = 10;
export const EXPLANATION_MAX = 500;

export const CANCEL_REASONS: readonly TaskCancelReason[] = [
  'NO_LONGER_REQUIRED',
  'CREATED_IN_ERROR',
  'WORK_MOVED_ELSEWHERE',
  'OTHER',
];

export const STATE_FILTERS: readonly TaskState[] = [
  'OPEN',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED',
];
export const DUE_FILTERS: readonly TaskDueClass[] = ['OVERDUE', 'DUE_SOON', 'LATER', 'NONE'];

/** NFC and trimmed: the text the server stores and the browser sends. */
export function normalizeText(raw: string): string {
  return raw.normalize('NFC').trim();
}

/** Unicode code points, as the server counts characters. */
export function codePoints(text: string): number {
  // eslint-disable-next-line @typescript-eslint/no-misused-spread
  return [...text].length;
}

// What the browser can already tell is refused: markup, addresses, encoded content and control
// characters. The server owns the real grammar and refuses more.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\u2028\u2029]/u;
const FORBIDDEN = /[<>`]|https?:|www\.|:\/\/|%[0-9a-f]{2}|&#|&[a-z][a-z0-9]{1,31};/iu;

/** True when the text (already normalized) is within its length and not obviously refused. */
export function textAcceptable(
  text: string,
  min: number,
  max: number,
  { multiline = false }: { multiline?: boolean } = {},
): boolean {
  const length = codePoints(text);
  if (length < min || length > max) return false;
  if (FORBIDDEN.test(text)) return false;
  const rest = multiline ? text.replace(/\n/gu, '') : text;
  return !CONTROL.test(rest) && (multiline || !/[\n\r]/u.test(text));
}

export type CreateField = 'assignee' | 'title' | 'instructions' | 'priority' | 'dueAt';
export const CREATE_FIELDS: readonly CreateField[] = [
  'assignee',
  'title',
  'instructions',
  'priority',
  'dueAt',
];

export interface CreateForm {
  assigneeId: string;
  title: string;
  instructions: string;
  priority: 'NORMAL' | 'HIGH' | 'URGENT';
  /** A `datetime-local` value in the browser's zone; empty for none. */
  due: string;
}

export const EMPTY_CREATE: CreateForm = {
  assigneeId: '',
  title: '',
  instructions: '',
  priority: 'NORMAL',
  due: '',
};

/** The due instant of a `datetime-local` value, or null when empty or not a real date. */
export function dueInstantOf(value: string): string | null {
  if (!value) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

export function createProblemsOf(form: CreateForm, now: Date): Set<CreateField> {
  const problems = new Set<CreateField>();
  if (!form.assigneeId) problems.add('assignee');
  if (!textAcceptable(normalizeText(form.title), 1, TITLE_MAX)) problems.add('title');
  const instructions = normalizeText(form.instructions);
  if (instructions && !textAcceptable(instructions, 1, INSTRUCTIONS_MAX, { multiline: true })) {
    problems.add('instructions');
  }
  if (form.due) {
    const instant = dueInstantOf(form.due);
    const limit = now.getTime() + 366 * 24 * 3600 * 1000;
    if (
      !instant ||
      new Date(instant).getTime() <= now.getTime() ||
      new Date(instant).getTime() > limit
    ) {
      problems.add('dueAt');
    }
  }
  return problems;
}

/** The request body of a valid form. */
export function createBodyOf(form: CreateForm) {
  const instructions = normalizeText(form.instructions);
  return {
    assigneeEmployeeId: form.assigneeId,
    title: normalizeText(form.title),
    instructions: instructions || null,
    priority: form.priority,
    dueAt: dueInstantOf(form.due),
  };
}

export type CancelField = 'reason' | 'explanation';

export function cancelProblemsOf(
  reason: TaskCancelReason | '',
  explanation: string,
): Set<CancelField> {
  const problems = new Set<CancelField>();
  if (!reason) problems.add('reason');
  const text = normalizeText(explanation);
  if (reason === 'OTHER') {
    if (!textAcceptable(text, EXPLANATION_MIN, EXPLANATION_MAX)) problems.add('explanation');
  } else if (text) {
    problems.add('explanation');
  }
  return problems;
}

/** A refused or failed task command, reduced to a stable message key and allow-listed details. */
export interface TaskFailure {
  kind:
    | 'forbidden'
    | 'linkRequired'
    | 'rateLimited'
    | 'validation'
    | 'notFound'
    | 'assignee'
    | 'conflict'
    | 'settled'
    | 'limit'
    | 'general';
  messageKey: string;
  fields?: string[];
  correlationId?: string;
  retryAfter?: number;
}

export const NETWORK_FAILURE: TaskFailure = { kind: 'general', messageKey: 'errors.network' };

const FIELD_NAMES: Record<string, string> = {
  assigneeEmployeeId: 'assignee',
  title: 'title',
  instructions: 'instructions',
  priority: 'priority',
  dueAt: 'dueAt',
  reason: 'reason',
  explanation: 'explanation',
  note: 'note',
};

/** Maps a Problem response; params outside the allow-list are dropped, never shown. */
export function taskFailureOf(response: Response, error: unknown): TaskFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'errors.EMPLOYEE_LINK_REQUIRED' };
  }
  if (response.status === 403) return { ...base, kind: 'forbidden', messageKey: 'tasks.forbidden' };
  if (response.status === 429)
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  if (code === 'TASK_NOT_FOUND')
    return { ...base, kind: 'notFound', messageKey: 'errors.TASK_NOT_FOUND' };
  if (code === 'TASK_ASSIGNEE_NOT_FOUND') {
    return { ...base, kind: 'assignee', messageKey: 'errors.TASK_ASSIGNEE_NOT_FOUND' };
  }
  if (code === 'TASK_TRANSITION_NOT_ALLOWED') {
    return { ...base, kind: 'settled', messageKey: 'errors.TASK_TRANSITION_NOT_ALLOWED' };
  }
  if (code === 'TASK_VERSION_CONFLICT') {
    return { ...base, kind: 'conflict', messageKey: 'errors.TASK_VERSION_CONFLICT' };
  }
  if (code === 'TASK_LIMIT_REACHED') {
    return { ...base, kind: 'limit', messageKey: 'errors.TASK_LIMIT_REACHED' };
  }
  if (code === 'IDEMPOTENCY_KEY_REUSED') {
    return { ...base, kind: 'conflict', messageKey: 'errors.IDEMPOTENCY_KEY_REUSED' };
  }
  if (code === 'VALIDATION_FAILED') {
    const raw: unknown = problem?.params?.fields;
    const fields = new Set<string>();
    if (Array.isArray(raw)) {
      for (const entry of raw) {
        const name = (entry as { field?: unknown }).field;
        const mapped = typeof name === 'string' ? FIELD_NAMES[name] : undefined;
        if (mapped && Object.hasOwn(FIELD_NAMES, String(name))) fields.add(mapped);
      }
    }
    return { ...base, kind: 'validation', messageKey: 'tasks.validation', fields: [...fields] };
  }
  return { ...base, kind: 'general', messageKey: 'errors.generic' };
}

/** The tone of a due classification; the text always carries the meaning. */
export function dueTone(due: TaskDueClass | null): 'danger' | 'warning' | 'neutral' {
  if (due === 'OVERDUE') return 'danger';
  if (due === 'DUE_SOON') return 'warning';
  return 'neutral';
}

export type Group = 'open' | 'inProgress' | 'closed';

/** The employee page's three groups, in display order. */
export function groupOf(state: TaskState): Group {
  if (state === 'OPEN') return 'open';
  if (state === 'IN_PROGRESS') return 'inProgress';
  return 'closed';
}

export function formatInstant(language: string, instant: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

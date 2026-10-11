import type {
  EmployeeSummary,
  Problem,
  WorkScheduleRequest,
  WorkScheduleSlotIssue,
} from '@divalhr/api-client';
import { codePoints } from '../leave/leaveApprovals';
import { validDate } from '../leave/leaveSubstitutes';

/** MVP-050A bounds (the server checks every one again). */
export const MAX_DAYS = 31;
export const MAX_SLOTS = 500;
export const LABEL_MIN = 2;
export const LABEL_MAX = 60;

/** One slot being edited: one required worker. The key is the client reference, never stored. */
export interface SlotDraft {
  key: string;
  date: string;
  start: string;
  end: string;
  labelEn: string;
  labelFr: string;
  employee: EmployeeSummary | null;
}

/** The schedule being edited. Kept in memory only: no draft is stored anywhere. */
export interface ScheduleForm {
  legalEntityId: string;
  siteId: string;
  firstDate: string;
  lastDate: string;
  slots: SlotDraft[];
}

export const FORM_FIELDS = ['site', 'firstDate', 'lastDate', 'slots'] as const;
export type FormField = (typeof FORM_FIELDS)[number];

export const SLOT_FIELDS = ['date', 'start', 'end', 'labelEn', 'labelFr'] as const;
export type SlotField = (typeof SLOT_FIELDS)[number];

/** The period's date fields, the slot's time fields and its label fields, in display order. */
export const PERIOD_FIELDS = ['firstDate', 'lastDate'] as const;
export const TIME_FIELDS = ['date', 'start', 'end'] as const;
export const LABEL_FIELDS = ['labelEn', 'labelFr'] as const;

export interface FormProblems {
  form: Set<FormField>;
  slots: Map<string, Set<SlotField>>;
}

let counter = 0;

/** A fresh client reference: unique in the page, safe for the server's pattern. */
export function newKey(): string {
  counter += 1;
  return `s${Date.now().toString(36)}${counter.toString(36)}`;
}

export function emptySlot(date: string): SlotDraft {
  return {
    key: newKey(),
    date,
    start: '08:00',
    end: '16:00',
    labelEn: '',
    labelFr: '',
    employee: null,
  };
}

export function emptyScheduleForm(): ScheduleForm {
  return { legalEntityId: '', siteId: '', firstDate: '', lastDate: '', slots: [emptySlot('')] };
}

/** A copy of the slot with a new reference (the same position and times, the same person). */
export function duplicateSlot(slot: SlotDraft): SlotDraft {
  return { ...slot, key: newKey() };
}

const LETTER = 'A-Za-z0-9\\u00C0-\\u00D6\\u00D8-\\u00F6\\u00F8-\\u024F';
const MARK = '\\u0300-\\u036F';
// The class lists the combining accents (U+0300-U+036F) as their own code points on purpose: it
// mirrors the server's grammar character for character.
/* eslint-disable no-misleading-character-class */
const LABEL_SHAPE = new RegExp(
  `^[${LETTER}]([${LETTER}${MARK} .,'\\u2019()/&#\\u00B0-]*[${LETTER}${MARK}).])?$`,
  'u',
);
/* eslint-enable no-misleading-character-class */
const DOT_LETTER = /\.[A-Za-z\u00C0-\u00D6\u00D8-\u00F6\u00F8-\u024F]/u;

/** NFC, trimmed, runs of spaces collapsed: what the server stores. */
export function normalizeLabel(text: string): string {
  return text.normalize('NFC').trim().replace(/ {2,}/g, ' ');
}

/**
 * The closed label grammar version 1 (the same rule as the server): Latin letters with accents,
 * digits, single spaces and . , ' ’ - ( ) / & # °; no markup, link or e-mail address.
 */
export function labelValid(text: string): boolean {
  const label = normalizeLabel(text);
  const length = codePoints(label);
  return (
    length >= LABEL_MIN &&
    length <= LABEL_MAX &&
    LABEL_SHAPE.test(label) &&
    !label.includes('  ') &&
    !DOT_LETTER.test(label)
  );
}

const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

export function validTime(text: string): boolean {
  return TIME.test(text);
}

/** The next calendar date of an ISO date. */
export function nextDate(date: string): string {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  return new Date(Date.UTC(year, month - 1, day + 1)).toISOString().slice(0, 10);
}

/** Days between two ISO dates (inclusive count minus one). */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000);
}

/** An end time at or before the start time ends on the next date (an overnight slot). */
export function overnight(slot: Pick<SlotDraft, 'start' | 'end'>): boolean {
  return validTime(slot.start) && validTime(slot.end) && slot.end < slot.start;
}

/** The slot's local start and end as the server expects them. */
export function slotTimes(slot: SlotDraft): { start: string; end: string } {
  const endDate = overnight(slot) ? nextDate(slot.date) : slot.date;
  return { start: `${slot.date}T${slot.start}`, end: `${endDate}T${slot.end}` };
}

/** What the browser can already tell is wrong; the server checks everything again. */
export function scheduleProblemsOf(form: ScheduleForm): FormProblems {
  const problems: FormProblems = { form: new Set(), slots: new Map() };
  if (!form.siteId) problems.form.add('site');
  const first = validDate(form.firstDate);
  const last = validDate(form.lastDate);
  if (!first) problems.form.add('firstDate');
  if (
    !last ||
    (first &&
      (form.lastDate < form.firstDate || daysBetween(form.firstDate, form.lastDate) >= MAX_DAYS))
  ) {
    problems.form.add('lastDate');
  }
  if (form.slots.length === 0 || form.slots.length > MAX_SLOTS) problems.form.add('slots');
  for (const slot of form.slots) {
    const found = new Set<SlotField>();
    if (
      !validDate(slot.date) ||
      (first && slot.date < form.firstDate) ||
      (last && slot.date > form.lastDate)
    ) {
      found.add('date');
    }
    if (!validTime(slot.start)) found.add('start');
    if (!validTime(slot.end) || slot.end === slot.start) found.add('end');
    if (!labelValid(slot.labelEn)) found.add('labelEn');
    if (!labelValid(slot.labelFr)) found.add('labelFr');
    if (found.size > 0) problems.slots.set(slot.key, found);
  }
  return problems;
}

export function hasProblems(problems: FormProblems): boolean {
  return problems.form.size > 0 || problems.slots.size > 0;
}

/** The request body of a valid form: IDs, dates, local times and normalized labels. */
export function scheduleBodyOf(form: ScheduleForm): WorkScheduleRequest {
  return {
    siteId: form.siteId,
    firstDate: form.firstDate,
    lastDate: form.lastDate,
    slots: form.slots.map((slot) => {
      const times = slotTimes(slot);
      return {
        clientRef: slot.key,
        start: times.start,
        end: times.end,
        labels: { en: normalizeLabel(slot.labelEn), fr: normalizeLabel(slot.labelFr) },
        ...(slot.employee ? { employeeId: slot.employee.id } : {}),
      };
    }),
  };
}

/** The stable whole-schedule refusals, each with its own message. */
const REFUSALS = new Set([
  'WORK_SCHEDULE_PERIOD_INVALID',
  'WORK_SCHEDULE_SITE_NOT_ACTIVE',
  'WORK_SCHEDULE_OVERLAP',
  'WORK_SCHEDULE_LIMIT_EXCEEDED',
  'WORK_SCHEDULE_SLOT_INVALID',
  'WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE',
  'WORK_SCHEDULE_EMPLOYEE_CONFLICT',
  'IDEMPOTENCY_KEY_REUSED',
]);

const REASONS = new Set([
  'ORDER',
  'PAST',
  'TOO_LONG',
  'OUTSIDE_PERIOD',
  'END_NOT_AFTER_START',
  'LOCAL_TIME_INVALID',
  'NOT_FOUND',
  'SEPARATED',
  'NOT_EMPLOYED',
  'NOT_AT_SITE',
  'PUBLISHED',
  'IN_SCHEDULE',
]);

/** A refused or failed request, reduced to stable codes and allow-listed details. */
export interface ScheduleFailure {
  kind:
    | 'mfaRequired'
    | 'forbidden'
    | 'rateLimited'
    | 'validation'
    | 'stale'
    | 'refused'
    | 'gone'
    | 'linkRequired'
    | 'general';
  messageKey: string;
  reasonKey?: string;
  position?: number;
  correlationId?: string;
  retryAfter?: number;
}

export const SCHEDULE_NETWORK_FAILURE: ScheduleFailure = {
  kind: 'general',
  messageKey: 'errors.network',
};

/** Maps a Problem response; params outside the allow-list are dropped, never shown. */
export function scheduleFailureOf(response: Response, error: unknown): ScheduleFailure {
  const problem = error as Partial<Problem> | undefined;
  const code = problem?.code;
  const retry = Number(response.headers.get('Retry-After'));
  const base = {
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
  if (code === 'MFA_REQUIRED') {
    return { ...base, kind: 'mfaRequired', messageKey: 'errors.MFA_REQUIRED' };
  }
  if (code === 'EMPLOYEE_LINK_REQUIRED') {
    return { ...base, kind: 'linkRequired', messageKey: 'mySchedule.linkRequired' };
  }
  if (response.status === 403) {
    return { ...base, kind: 'forbidden', messageKey: 'workSchedules.unauthorized' };
  }
  if (response.status === 429) {
    return { ...base, kind: 'rateLimited', messageKey: 'errors.RATE_LIMITED' };
  }
  if (code === 'WORK_SCHEDULE_NOT_FOUND') {
    return { ...base, kind: 'gone', messageKey: 'errors.WORK_SCHEDULE_NOT_FOUND' };
  }
  if (code === 'WORK_SCHEDULE_PREVIEW_STALE') {
    return { ...base, kind: 'stale', messageKey: 'errors.WORK_SCHEDULE_PREVIEW_STALE' };
  }
  if (code && REFUSALS.has(code)) {
    const params = (problem.params ?? {}) as {
      reason?: unknown;
      position?: unknown;
    };
    const reason =
      typeof params.reason === 'string' && REASONS.has(params.reason) ? params.reason : undefined;
    const position =
      typeof params.position === 'string' && /^[0-9]{1,3}$/.test(params.position)
        ? Number(params.position)
        : undefined;
    return {
      ...base,
      kind: 'refused',
      messageKey: `errors.${code}`,
      reasonKey: reason
        ? code === 'WORK_SCHEDULE_PERIOD_INVALID'
          ? `workSchedules.periodReasons.${reason}`
          : `workSchedules.issues.${reason}`
        : undefined,
      position,
    };
  }
  if (code === 'VALIDATION_FAILED') {
    return { ...base, kind: 'validation', messageKey: 'errors.VALIDATION_FAILED' };
  }
  return {
    ...base,
    kind: 'general',
    messageKey: code === 'CURSOR_INVALID' ? 'errors.CURSOR_INVALID' : 'errors.generic',
  };
}

/** The translation key of a slot issue: its code and reason, both from closed lists. */
export function issueKey(issue: WorkScheduleSlotIssue): string {
  return `workSchedules.issues.${issue.reason}`;
}

/** A local {@code HH:MM} time in the language's clock (no time zone conversion). */
export function formatTime(language: string, time: string): string {
  const [hour, minute] = time.split(':').map(Number) as [number, number];
  return new Intl.DateTimeFormat(language, {
    hour: 'numeric',
    minute: '2-digit',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(2000, 0, 1, hour, minute)));
}

/** The date part and the time part of a {@code YYYY-MM-DDTHH:MM} local date-time. */
export function splitLocal(local: string): { date: string; time: string } {
  return { date: local.slice(0, 10), time: local.slice(11, 16) };
}

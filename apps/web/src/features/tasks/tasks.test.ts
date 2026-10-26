import { describe, expect, it } from 'vitest';
import {
  cancelProblemsOf,
  createBodyOf,
  createProblemsOf,
  dueInstantOf,
  EMPTY_CREATE,
  groupOf,
  normalizeText,
  taskFailureOf,
  textAcceptable,
} from './tasks';

const NOW = new Date('2026-10-26T10:00:00Z');
const valid = { ...EMPTY_CREATE, assigneeId: 'a-1', title: 'Inventaire' };
const problem = (code: string, status: number, params: Record<string, unknown> = {}) => ({
  code,
  status,
  params,
  correlationId: 'corr-task-1234',
});
const response = (status: number, headers: Record<string, string> = {}) =>
  new Response(null, { status, headers });

describe('MVP-080 task form rules', () => {
  it('normalizes like the server and counts code points', () => {
    expect(normalizeText('  Café  ')).toBe('Café');
    expect(textAcceptable('😀'.repeat(120), 1, 120)).toBe(true);
    expect(textAcceptable('😀'.repeat(121), 1, 120)).toBe(false);
  });

  it.each([
    '<b>x</b>',
    'see https://evil.example',
    'www.example.com',
    'a%41b',
    '&#65;',
    '&amp;',
    'a`b',
    'tab\u0007bell',
  ])('refuses markup, addresses and encoded text: %s', (text) => {
    expect(textAcceptable(text, 1, 120)).toBe(false);
  });

  it('allows line breaks only in multi-line fields', () => {
    expect(textAcceptable('a\nb', 1, 100)).toBe(false);
    expect(textAcceptable('a\nb', 1, 100, { multiline: true })).toBe(true);
  });

  it('requires an assignee and a title, and bounds the due date', () => {
    expect(createProblemsOf(valid, NOW).size).toBe(0);
    expect([...createProblemsOf(EMPTY_CREATE, NOW)]).toEqual(['assignee', 'title']);
    // `datetime-local` values are in the browser's zone, whatever it is.
    const local = (offsetHours: number) => {
      const d = new Date(NOW.getTime() + offsetHours * 3600_000);
      const two = (n: number) => String(n).padStart(2, '0');
      return `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}T${two(d.getHours())}:${two(d.getMinutes())}`;
    };
    const at = (hours: number) =>
      createProblemsOf({ ...valid, due: local(hours) }, NOW).has('dueAt');
    expect(at(-1)).toBe(true);
    expect(at(0)).toBe(true);
    expect(at(1)).toBe(false);
    expect(at(366 * 24 + 2)).toBe(true);
    expect(at(30 * 24)).toBe(false);
  });

  it('builds the body with nulls for empty optional fields', () => {
    expect(createBodyOf(valid)).toEqual({
      assigneeEmployeeId: 'a-1',
      title: 'Inventaire',
      instructions: null,
      priority: 'NORMAL',
      dueAt: null,
    });
    expect(dueInstantOf('not a date')).toBeNull();
  });

  it('applies the cancellation reason rules', () => {
    expect([...cancelProblemsOf('', '')]).toEqual(['reason']);
    expect(cancelProblemsOf('NO_LONGER_REQUIRED', '').size).toBe(0);
    expect([...cancelProblemsOf('NO_LONGER_REQUIRED', 'pas permis')]).toEqual(['explanation']);
    expect([...cancelProblemsOf('OTHER', 'court')]).toEqual(['explanation']);
    expect(cancelProblemsOf('OTHER', 'Le chantier est reporté').size).toBe(0);
  });

  it('groups states for the employee page', () => {
    expect(
      ['OPEN', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].map((s) => groupOf(s as 'OPEN')),
    ).toEqual(['open', 'inProgress', 'closed', 'closed']);
  });
});

describe('MVP-080 failures', () => {
  it.each([
    ['TASK_NOT_FOUND', 404, 'notFound'],
    ['TASK_ASSIGNEE_NOT_FOUND', 404, 'assignee'],
    ['TASK_VERSION_CONFLICT', 409, 'conflict'],
    ['TASK_TRANSITION_NOT_ALLOWED', 409, 'settled'],
    ['TASK_LIMIT_REACHED', 409, 'limit'],
    ['IDEMPOTENCY_KEY_REUSED', 409, 'conflict'],
    ['EMPLOYEE_LINK_REQUIRED', 403, 'linkRequired'],
  ])('maps %s to a stable kind and message key', (code, status, kind) => {
    const failure = taskFailureOf(response(status), problem(code, status));
    expect(failure.kind).toBe(kind);
    expect(failure.messageKey).toMatch(/^(errors|tasks)\./u);
    expect(failure.correlationId).toBe('corr-task-1234');
  });

  it('keeps only allow-listed fields and the retry delay', () => {
    expect(
      taskFailureOf(
        response(400),
        problem('VALIDATION_FAILED', 400, {
          fields: [
            { field: 'title', constraint: 'FORMAT' },
            { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
          ],
        }),
      ).fields,
    ).toEqual(['title']);
    expect(taskFailureOf(response(429, { 'Retry-After': '12' }), undefined).retryAfter).toBe(12);
    expect(taskFailureOf(response(500), undefined).messageKey).toBe('errors.generic');
  });
});

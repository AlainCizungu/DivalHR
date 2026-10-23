import type { Problem, WebhookEventType } from '@divalhr/api-client';

/** The closed public event catalogue (D110-3), in display order. */
export const WEBHOOK_EVENTS: readonly WebhookEventType[] = [
  'leave.request.approved.v1',
  'leave.request.rejected.v1',
  'timesheet.approved.v1',
  'attendance.corrected.v1',
  'payroll.input.locked.v1',
  'payroll.input-export.created.v1',
];

/** Fixed catalogue limits shown to the administrator. */
export const MAX_WEBHOOKS = 20;
export const MAX_NAME_LENGTH = 100;

export type WebhookValues = { name: string; url: string; eventTypes: WebhookEventType[] };

export type WebhookFieldErrors = Partial<Record<'name' | 'url' | 'events' | 'confirm', string>>;

/** A message key (resolved by the page) per field; the server remains authoritative. */
export function validateWebhook(values: WebhookValues, confirmed: boolean): WebhookFieldErrors {
  const errors: WebhookFieldErrors = {};
  const name = values.name.trim();
  if (name.length === 0) errors.name = 'webhooks.errors.nameRequired';
  else if (Array.from(name).length > MAX_NAME_LENGTH) errors.name = 'webhooks.errors.nameTooLong';
  const url = values.url.trim();
  if (url.length === 0) errors.url = 'webhooks.errors.urlRequired';
  else if (!/^https:\/\/[^\s/?#@]+/i.test(url)) errors.url = 'webhooks.errors.urlHttps';
  if (values.eventTypes.length === 0) errors.events = 'webhooks.errors.eventsRequired';
  if (!confirmed) errors.confirm = 'webhooks.errors.confirmRequired';
  return errors;
}

export type Failure = { messageKey: string; correlationId?: string; retryAfter?: number };

/** Turns a problem response into a stable, translatable failure. */
export function failureOf(response: Response, error: unknown): Failure {
  const problem = error as Partial<Problem> | undefined;
  const retry = Number(response.headers.get('Retry-After'));
  return {
    messageKey: problem?.code ? `errors.${problem.code}` : 'errors.generic',
    correlationId: problem?.correlationId,
    retryAfter: Number.isFinite(retry) && retry > 0 ? retry : undefined,
  };
}

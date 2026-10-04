import type { SeparationReason, SeparationTaskStatus } from '@divalhr/api-client';

/** MVP-022 closed separation reasons, in the contract's order. */
export const SEPARATION_REASONS: readonly SeparationReason[] = [
  'RESIGNATION',
  'END_OF_FIXED_TERM',
  'DISMISSAL',
  'MUTUAL_AGREEMENT',
  'RETIREMENT',
  'OTHER_SEPARATION',
];

export type SettableTaskStatus = Exclude<SeparationTaskStatus, 'CANCELLED'>;

/** Statuses an administrator can set on a follow-up task. */
export const TASK_STATUSES: readonly SettableTaskStatus[] = ['OPEN', 'DONE', 'NOT_APPLICABLE'];

/** Failures after which a separation or cancellation preview must be repeated. */
export const SEPARATION_STALE = new Set([
  'SEPARATION_PREVIEW_CHANGED',
  'EMPLOYMENT_PREVIEW_CHANGED',
  'EMPLOYMENT_VERSION_CONFLICT',
]);

/** An instant (when access ends) in the user's language and the device's time zone. */
export function formatInstant(language: string, instant: string): string {
  return new Intl.DateTimeFormat(language, { dateStyle: 'long', timeStyle: 'short' }).format(
    new Date(instant),
  );
}

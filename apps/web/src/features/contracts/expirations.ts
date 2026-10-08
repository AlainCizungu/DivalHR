import type { ContractExpiration, ContractExpirationCategory } from '@divalhr/api-client';
import type { TFunction } from 'i18next';
import type { StatusTone } from '../../ui/primitives';

/** MVP-031A categories, most urgent first (the API's order). */
export const CATEGORIES: readonly ContractExpirationCategory[] = [
  'EXPIRED',
  'NEXT_30_DAYS',
  'DAYS_31_TO_60',
  'DAYS_61_TO_90',
];

/** The counts property of each category. */
export const COUNT_OF = {
  EXPIRED: 'expired',
  NEXT_30_DAYS: 'next30Days',
  DAYS_31_TO_60: 'days31To60',
  DAYS_61_TO_90: 'days61To90',
} as const satisfies Record<ContractExpirationCategory, string>;

/** Colour only reinforces the text, which always carries the meaning. */
export function toneOf(category: ContractExpirationCategory): StatusTone {
  if (category === 'EXPIRED') return 'danger';
  if (category === 'NEXT_30_DAYS') return 'warning';
  return 'info';
}

/**
 * The server's day count as words. The browser never computes days or categories: both come from
 * the organization's business date on the server.
 */
export function daysText(t: TFunction, item: Pick<ContractExpiration, 'daysUntilEnd'>): string {
  const days = item.daysUntilEnd;
  if (days === 0) return t('contractExpirations.days.today');
  return days > 0
    ? t('contractExpirations.days.remaining', { count: days })
    : t('contractExpirations.days.overdue', { count: -days });
}

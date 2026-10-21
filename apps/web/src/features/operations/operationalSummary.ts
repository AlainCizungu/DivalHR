import type { OperationalSummary } from '@divalhr/api-client';
import type { StatusView } from './operationalStatus';

/**
 * MVP-101: the grounded daily briefing. The browser asks Core only, with the display language and
 * nothing else; it never calls the AI service, never builds a prompt and never stores the answer
 * (no browser storage, no URL). Every sentence is rendered by the server from reviewed templates:
 * the browser shows it as plain text and maps the allow-listed route identifier of each highlight
 * to an existing screen the caller already has.
 */

export type SummaryRouteId = OperationalSummary['highlights'][number]['routeId'];

/** The existing screens each source identifier leads to, per audience; routes only. */
export const SUMMARY_ROUTES: Record<StatusView, Record<SummaryRouteId, string>> = {
  admin: {
    ATTENDANCE: '/admin/attendance-review',
    SCHEDULES: '/admin/work-schedules',
    LEAVE_APPROVALS: '/admin/leave-approvals',
    TIMESHEETS: '/admin/timesheets',
  },
  team: {
    ATTENDANCE: '/me/team-attendance',
    SCHEDULES: '/me/schedule',
    LEAVE_APPROVALS: '/me/leave/approvals',
    TIMESHEETS: '/me/time-approvals',
  },
};

/** The route of a source identifier, or null for an identifier this build does not know. */
export function summaryRouteOf(view: StatusView, routeId: string): string | null {
  const routes: Record<string, string> = SUMMARY_ROUTES[view];
  return Object.prototype.hasOwnProperty.call(routes, routeId) ? routes[routeId] : null;
}

/** The request body: the language and nothing else. */
export function summaryBodyOf(language: 'en' | 'fr'): { language: 'en' | 'fr' } {
  return { language };
}

/** Translation keys of the two generation modes (never a provider or model name). */
export function summaryModeKey(mode: string): string {
  return mode === 'MODEL' ? 'operationalSummary.mode.MODEL' : 'operationalSummary.mode.FALLBACK';
}

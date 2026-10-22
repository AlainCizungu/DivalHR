import type { OperationalSummary } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { formatInstant } from '../schedules/attendance';
import {
  STATUS_NETWORK_FAILURE,
  statusFailureOf,
  statusQueryOf,
  type StatusFailure,
  type StatusFilters,
  type StatusView,
} from './operationalStatus';
import { summaryBodyOf, summaryModeKey, summaryRouteOf } from './operationalSummary';

type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'failed'; failure: StatusFailure }
  | { kind: 'ready'; summary: OperationalSummary };

const IDLE: State = { kind: 'idle' };

interface Props {
  view: StatusView;
  /** The filters the status currently shows; a change discards the briefing. */
  filters: StatusFilters;
}

function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return 'UTC';
  }
}

/**
 * MVP-101: « Synthèse du jour » / "Daily briefing". Generated on request, in the display
 * language, from the same aggregate figures as the status; nothing is kept. Changing the language
 * or the filters discards the briefing so it never contradicts what the screen shows. Facts only.
 */
export function OperationalSummaryPanel({ view, filters }: Props) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  // A briefing belongs to the language and scope it was asked for: under any other it is not shown
  // (and an answer still in flight for another scope is dropped), so it never contradicts the screen.
  const scope = `${view}|${language}|${filters.legalEntityId}|${filters.siteId}`;
  const [held, setHeld] = useState<{ scope: string; state: State; announcement: string }>({
    scope,
    state: IDLE,
    announcement: '',
  });
  const state: State = held.scope === scope ? held.state : IDLE;
  const announcement = held.scope === scope ? held.announcement : '';
  const generation = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (state.kind === 'ready') heading.current?.focus();
  }, [state]);

  const generate = async () => {
    const mine = ++generation.current;
    setHeld({ scope, state: { kind: 'loading' }, announcement: '' });
    let next: State;
    try {
      const body = summaryBodyOf(language);
      const { data, error, response } =
        view === 'admin'
          ? await core.POST('/operations/status-summary', {
              params: { query: statusQueryOf(view, filters) },
              body,
              cache: 'no-store',
            })
          : await core.POST('/me/team-operations/status-summary', { body, cache: 'no-store' });
      next = data
        ? { kind: 'ready', summary: data }
        : { kind: 'failed', failure: statusFailureOf(view, response, error) };
    } catch {
      next = { kind: 'failed', failure: STATUS_NETWORK_FAILURE };
    }
    if (mine !== generation.current) return;
    setHeld({
      scope,
      state: next,
      announcement: next.kind === 'ready' ? t('operationalSummary.ready') : '',
    });
  };

  const zone = browserZone();
  const number = new Intl.NumberFormat(language);

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid={`operational-summary-${view}`}
    >
      <h2 id={`${ids}-title`}>{t('operationalSummary.title')}</h2>
      <p className="muted">{t('operationalSummary.description')}</p>
      <p role="status" className="visually-hidden" data-testid="operational-summary-announcer">
        {announcement}
      </p>
      <p>
        <button
          type="button"
          className="button"
          data-testid="operational-summary-generate"
          disabled={state.kind === 'loading'}
          aria-busy={state.kind === 'loading'}
          onClick={() => {
            void generate();
          }}
        >
          {state.kind === 'ready'
            ? t('operationalSummary.regenerate')
            : t('operationalSummary.generate')}
        </button>
      </p>

      {state.kind === 'loading' && <p role="status">{t('operationalSummary.loading')}</p>}
      {state.kind === 'failed' && (
        <div
          className="error-summary"
          role="alert"
          data-testid="operational-summary-error"
          data-kind={state.failure.kind}
        >
          <p>{t(state.failure.messageKey)}</p>
          {state.failure.correlationId && (
            <p className="muted">{t('employees.reference', { id: state.failure.correlationId })}</p>
          )}
        </div>
      )}
      {state.kind === 'ready' && (
        <div data-testid="operational-summary-result" lang={state.summary.language}>
          <h3 ref={heading} tabIndex={-1} data-testid="operational-summary-headline">
            {state.summary.headline.text}
          </h3>
          <p className="muted" data-testid="operational-summary-meta">
            {t('operationalSummary.asOf', {
              time: formatInstant(language, state.summary.asOfInstant, zone),
              zone,
            })}{' '}
            <span data-testid="operational-summary-mode">
              {t(summaryModeKey(state.summary.generationMode))}
            </span>
          </p>
          <ul data-testid="operational-summary-highlights">
            {state.summary.highlights.map((highlight) => {
              const to = summaryRouteOf(view, highlight.routeId);
              return (
                <li
                  key={highlight.factType}
                  data-fact-type={highlight.factType}
                  data-severity={highlight.severity}
                >
                  {highlight.text}
                  {to && (
                    <>
                      {' '}
                      <Link to={to}>{t(`operationalSummary.source.${highlight.routeId}`)}</Link>
                    </>
                  )}
                </li>
              );
            })}
          </ul>
          <p className="muted" data-testid="operational-summary-source">
            {t('operationalSummary.sourceCounts', {
              scheduled: number.format(state.summary.sourceSnapshot.shifts.scheduled),
              notCheckedIn: number.format(state.summary.sourceSnapshot.shifts.notCheckedIn),
              pendingLeave: number.format(state.summary.sourceSnapshot.approvals.leave.pending),
              pendingTimesheets: number.format(
                state.summary.sourceSnapshot.approvals.timesheets.pending,
              ),
            })}
          </p>
          <ul className="muted" data-testid="operational-summary-limitations">
            {state.summary.limitations.map((limitation) => (
              <li key={limitation.code}>{limitation.text}</li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

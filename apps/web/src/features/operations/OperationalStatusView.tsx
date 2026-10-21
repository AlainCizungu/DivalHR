import type {
  OperationalStatus,
  OperationalStatusNotCheckedIn,
  OperationalStatusShift,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { formatDate } from '../people/history';
import { loadAll, useOptions } from '../people/unitOptions';
import { formatInstant } from '../schedules/attendance';
import { formatTime, splitLocal } from '../schedules/workSchedules';
import {
  EMPTY_STATUS_FILTERS,
  STATUS_NETWORK_FAILURE,
  personName,
  statusFailureOf,
  statusQueryOf,
  type StatusFailure,
  type StatusFilters,
  type StatusView,
} from './operationalStatus';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: StatusFailure }
  | { kind: 'ready'; status: OperationalStatus };

/** The existing screens each figure leads to; routes only, never an identifier or a name. */
const DRILL_DOWN: Record<StatusView, { key: string; to: string }[]> = {
  admin: [
    { key: 'attendance', to: '/admin/attendance-review' },
    { key: 'schedules', to: '/admin/work-schedules' },
    { key: 'leave', to: '/admin/leave-approvals' },
    { key: 'timesheets', to: '/admin/timesheets' },
  ],
  team: [
    { key: 'attendance', to: '/me/team-attendance' },
    { key: 'schedules', to: '/me/schedule' },
    { key: 'leave', to: '/me/leave/approvals' },
    { key: 'timesheets', to: '/me/time-approvals' },
  ],
};

interface Props {
  view: StatusView;
}

/**
 * MVP-100: « Opérations du jour » / "Today's operations". Every figure is computed by the server
 * at one instant, shown here with that instant, and counts shift occurrences (not people):
 * « Quarts planifiés » / "Scheduled shifts" is the total and the four states below it partition it;
 * « Retards » / "Late" is a labelled subset. Facts only: no absence, leave, pay or disciplinary
 * conclusion. Read-only; nothing is kept in browser storage or put in a URL. The server decides
 * the scope of every request.
 */
export function OperationalStatusView({ view }: Props) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [draft, setDraft] = useState<StatusFilters>(EMPTY_STATUS_FILTERS);
  const [applied, setApplied] = useState<StatusFilters>(EMPTY_STATUS_FILTERS);
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [refresh, setRefresh] = useState(0);
  const [announcement, setAnnouncement] = useState('');
  const number = new Intl.NumberFormat(language);

  const legalEntities = useOptions(view === 'admin' ? 'all' : null, () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const legalEntityId = draft.legalEntityId;
  const sites = useOptions(view === 'admin' && legalEntityId ? legalEntityId : null, () =>
    loadAll((cursor) =>
      core.GET('/sites', { params: { query: { legalEntityId, cursor, limit: 200 } } }),
    ),
  );

  const fetchStatus = useCallback(
    async (filters: StatusFilters): Promise<Loaded> => {
      try {
        const query = statusQueryOf(view, filters);
        const { data, error, response } =
          view === 'admin'
            ? await core.GET('/operations/status', { params: { query }, cache: 'no-store' })
            : await core.GET('/me/team-operations/status', { cache: 'no-store' });
        return data
          ? { kind: 'ready', status: data }
          : { kind: 'failed', failure: statusFailureOf(view, response, error) };
      } catch {
        return { kind: 'failed', failure: STATUS_NETWORK_FAILURE };
      }
    },
    [core, view],
  );

  useEffect(() => {
    let active = true;
    void fetchStatus(applied).then((next) => {
      if (!active) return;
      setLoaded(next);
      if (next.kind === 'ready') {
        setAnnouncement(
          t('operationsStatus.updated', {
            time: formatInstant(language, next.status.asOfInstant, browserZone()),
          }),
        );
      }
    });
    return () => {
      active = false;
    };
  }, [fetchStatus, applied, refresh, t, language]);

  const onApply = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setAnnouncement('');
    setLoaded({ kind: 'loading' });
    setApplied({ ...draft });
  };
  const onReset = () => {
    setDraft(EMPTY_STATUS_FILTERS);
    setAnnouncement('');
    setLoaded({ kind: 'loading' });
    setApplied({ ...EMPTY_STATUS_FILTERS });
  };
  const onRefresh = () => {
    setAnnouncement('');
    setLoaded({ kind: 'loading' });
    setRefresh((count) => count + 1);
  };

  const filtered = applied.legalEntityId !== '' || applied.siteId !== '';

  return (
    <div data-testid={`operations-status-${view}`}>
      <p data-testid="operations-status-disclaimer">{t('operationsStatus.disclaimer')}</p>
      <p role="status" className="visually-hidden" data-testid="operations-status-announcer">
        {announcement}
      </p>

      {view === 'admin' && (
        <form
          noValidate
          role="search"
          aria-label={t('operationsStatus.filters.label')}
          onSubmit={onApply}
          data-testid="operations-status-filters"
        >
          {(legalEntities.status === 'failed' || sites.status === 'failed') && (
            <p className="field__error" role="alert">
              {t('operationsStatus.filters.unavailable')}
            </p>
          )}
          <div className="field">
            <label htmlFor={`${ids}-legal-entity`}>
              {t('operationsStatus.filters.legalEntity')}
            </label>
            <select
              id={`${ids}-legal-entity`}
              value={draft.legalEntityId}
              onChange={(event) => {
                setDraft({ legalEntityId: event.target.value, siteId: '' });
              }}
            >
              <option value="">{t('operationsStatus.filters.anyLegalEntity')}</option>
              {legalEntities.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-site`}>{t('operationsStatus.filters.site')}</label>
            <select
              id={`${ids}-site`}
              value={draft.siteId}
              disabled={!draft.legalEntityId}
              onChange={(event) => {
                setDraft({ ...draft, siteId: event.target.value });
              }}
            >
              <option value="">{t('operationsStatus.filters.anySite')}</option>
              {sites.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
          </div>
          <div className="actions">
            <button type="submit" className="button" data-testid="operations-status-apply">
              {t('operationsStatus.filters.apply')}
            </button>
            {filtered && (
              <button
                type="button"
                className="button button--secondary"
                data-testid="operations-status-reset"
                onClick={onReset}
              >
                {t('operationsStatus.filters.reset')}
              </button>
            )}
          </div>
        </form>
      )}

      <div aria-busy={loaded.kind === 'loading'}>
        {loaded.kind === 'loading' && <p role="status">{t('operationsStatus.loading')}</p>}
        {loaded.kind === 'failed' && (
          <div
            className="error-summary"
            role="alert"
            data-testid="operations-status-error"
            data-kind={loaded.failure.kind}
          >
            <p>{t(loaded.failure.messageKey)}</p>
            {loaded.failure.correlationId && (
              <p className="muted">
                {t('employees.reference', { id: loaded.failure.correlationId })}
              </p>
            )}
            {loaded.failure.kind !== 'linkRequired' &&
              loaded.failure.kind !== 'forbidden' &&
              loaded.failure.kind !== 'scopeNotFound' && (
                <button type="button" className="button button--secondary" onClick={onRefresh}>
                  {t('operationsStatus.retry')}
                </button>
              )}
          </div>
        )}
        {loaded.kind === 'ready' && (
          <Ready
            view={view}
            status={loaded.status}
            language={language}
            format={(value) => number.format(value)}
            onRefresh={onRefresh}
          />
        )}
      </div>
    </div>
  );
}

function browserZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return 'UTC';
  }
}

interface ReadyProps {
  view: StatusView;
  status: OperationalStatus;
  language: 'en' | 'fr';
  format: (value: number) => string;
  onRefresh: () => void;
}

function Ready({ view, status, language, format, onRefresh }: ReadyProps) {
  const { t } = useTranslation();
  const ids = useId();
  const { shifts, approvals, thresholds, coverage, details } = status;
  const zone = browserZone();

  const tiles: { key: string; value: number; testId: string }[] = [
    { key: 'scheduled', value: shifts.scheduled, testId: 'tile-scheduled' },
    { key: 'expected', value: shifts.expected, testId: 'tile-expected' },
    { key: 'notCheckedIn', value: shifts.notCheckedIn, testId: 'tile-not-checked-in' },
    { key: 'present', value: shifts.present, testId: 'tile-present' },
    { key: 'completed', value: shifts.completed, testId: 'tile-completed' },
  ];

  return (
    <>
      <p className="muted" data-testid="operations-status-asof">
        {t('operationsStatus.asOf', {
          time: formatInstant(language, status.asOfInstant, zone),
          zone,
        })}
      </p>
      <p>
        <button
          type="button"
          className="button button--secondary"
          data-testid="operations-status-refresh"
          onClick={onRefresh}
        >
          {t('operationsStatus.refresh')}
        </button>
      </p>

      <section className="card" aria-labelledby={`${ids}-shifts`} data-testid="status-shifts">
        <h2 id={`${ids}-shifts`}>{t('operationsStatus.shifts.title')}</h2>
        <p className="muted">
          {t('operationsStatus.shifts.explanation', { grace: thresholds.notCheckedInGraceMinutes })}
        </p>
        <dl className="status-tiles">
          {tiles.map((tile) => (
            <div key={tile.key} className="status-tile" data-testid={tile.testId}>
              <dt>{t(`operationsStatus.shifts.${tile.key}`)}</dt>
              <dd>{format(tile.value)}</dd>
            </div>
          ))}
          {coverage && (
            <div className="status-tile" data-testid="tile-uncovered">
              <dt>{t('operationsStatus.shifts.uncovered')}</dt>
              <dd>{format(coverage.uncovered)}</dd>
            </div>
          )}
        </dl>
        <p data-testid="tile-late">
          {t('operationsStatus.shifts.late', {
            count: shifts.late,
            value: format(shifts.late),
            minutes: thresholds.lateMinutes,
          })}
        </p>
      </section>

      <section className="card" aria-labelledby={`${ids}-approvals`} data-testid="status-approvals">
        <h2 id={`${ids}-approvals`}>{t('operationsStatus.approvals.title')}</h2>
        <p className="muted">{t(`operationsStatus.approvals.scope.${view}`)}</p>
        <dl className="status-tiles">
          <Waiting
            id="leave"
            label={t('operationsStatus.approvals.leave')}
            waiting={approvals.leave}
            format={format}
          />
          <Waiting
            id="timesheets"
            label={t('operationsStatus.approvals.timesheets')}
            waiting={approvals.timesheets}
            format={format}
          />
        </dl>
        <p className="muted" data-testid="status-thresholds">
          {t('operationsStatus.approvals.thresholds', {
            leave: thresholds.leaveOverdueHours,
            timesheets: thresholds.timesheetOverdueHours,
          })}
        </p>
      </section>

      <Details
        title={t('operationsStatus.details.notCheckedIn')}
        id={`${ids}-nci`}
        testId="details-not-checked-in"
        limit={thresholds.detailLimit}
        truncated={details.notCheckedIn.truncated}
        empty={t('operationsStatus.details.none')}
        rows={details.notCheckedIn.items.map((row) => (
          <NotCheckedInRow
            key={`${row.shift.slotId}-${row.employee.id}`}
            row={row}
            language={language}
          />
        ))}
      />
      {details.uncovered && (
        <Details
          title={t('operationsStatus.details.uncovered')}
          id={`${ids}-unc`}
          testId="details-uncovered"
          limit={thresholds.detailLimit}
          truncated={details.uncovered.truncated}
          empty={t('operationsStatus.details.none')}
          rows={details.uncovered.items.map((shift) => (
            <ShiftRow key={shift.slotId} shift={shift} language={language} />
          ))}
        />
      )}
      {details.overdueLeave && (
        <Details
          title={t('operationsStatus.details.overdueLeave')}
          id={`${ids}-ol`}
          testId="details-overdue-leave"
          limit={thresholds.detailLimit}
          truncated={details.overdueLeave.truncated}
          empty={t('operationsStatus.details.none')}
          rows={details.overdueLeave.items.map((row) => (
            <li key={row.requestId} className="card" data-testid="detail-overdue-leave">
              <p>
                <strong>{personName(row.employee)}</strong>
              </p>
              <p>
                {t('operationsStatus.details.waiting', {
                  count: row.ageHours,
                  since: formatInstant(language, row.submittedAt, zone),
                })}
              </p>
            </li>
          ))}
        />
      )}
      {details.overdueTimesheets && (
        <Details
          title={t('operationsStatus.details.overdueTimesheets')}
          id={`${ids}-ot`}
          testId="details-overdue-timesheets"
          limit={thresholds.detailLimit}
          truncated={details.overdueTimesheets.truncated}
          empty={t('operationsStatus.details.none')}
          rows={details.overdueTimesheets.items.map((row) => (
            <li key={row.timesheetId} className="card" data-testid="detail-overdue-timesheet">
              <p>
                <strong>{personName(row.employee)}</strong>
              </p>
              <p>
                {t('operationsStatus.details.week', {
                  date: formatDate(language, row.weekStart),
                  site: row.site.name ?? t('workSchedules.siteUnknown'),
                })}
              </p>
              <p>
                {t('operationsStatus.details.waiting', {
                  count: row.ageHours,
                  since: formatInstant(language, row.submittedAt, zone),
                })}
              </p>
            </li>
          ))}
        />
      )}

      <nav aria-label={t('operationsStatus.drill.label')} data-testid="status-drill-down">
        <ul className="status-links">
          {DRILL_DOWN[view].map((link) => (
            <li key={link.key}>
              <Link to={link.to} className="button button--secondary">
                {t(`operationsStatus.drill.${view}.${link.key}`)}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </>
  );
}

function Waiting({
  id,
  label,
  waiting,
  format,
}: {
  id: string;
  label: string;
  waiting: { pending: number; overdue: number };
  format: (value: number) => string;
}) {
  const { t } = useTranslation();
  return (
    <div className="status-tile" data-testid={`tile-${id}`}>
      <dt>{label}</dt>
      <dd>
        <span data-testid={`tile-${id}-pending`}>
          {t('operationsStatus.approvals.pending', { value: format(waiting.pending) })}
        </span>
        <br />
        <span data-testid={`tile-${id}-within`}>
          {t('operationsStatus.approvals.within', {
            value: format(waiting.pending - waiting.overdue),
          })}
        </span>
        <br />
        <span data-testid={`tile-${id}-overdue`}>
          {t('operationsStatus.approvals.overdue', { value: format(waiting.overdue) })}
        </span>
      </dd>
    </div>
  );
}

function Details({
  title,
  id,
  testId,
  limit,
  truncated,
  empty,
  rows,
}: {
  title: string;
  id: string;
  testId: string;
  limit: number;
  truncated: boolean;
  empty: string;
  rows: React.ReactNode[];
}) {
  const { t } = useTranslation();
  return (
    <section className="card" aria-labelledby={id} data-testid={testId}>
      <h2 id={id}>{title}</h2>
      {rows.length === 0 ? (
        <p>{empty}</p>
      ) : (
        <ul className="shift-list" aria-labelledby={id}>
          {rows}
        </ul>
      )}
      {truncated && (
        <p className="muted" data-testid={`${testId}-truncated`}>
          {t('operationsStatus.details.truncated', { limit })}
        </p>
      )}
    </section>
  );
}

function ShiftFacts({ shift, language }: { shift: OperationalStatusShift; language: 'en' | 'fr' }) {
  const { t } = useTranslation();
  const start = splitLocal(shift.start);
  const end = splitLocal(shift.end);
  const label = language === 'fr' ? shift.labels.fr : shift.labels.en;
  return (
    <>
      <p lang={language}>
        <strong>{label}</strong>
      </p>
      <p>
        {formatDate(language, start.date)}{' '}
        {t('mySchedule.time', {
          start: formatTime(language, start.time),
          end: formatTime(language, end.time),
        })}
        {shift.overnight && (
          <> {t('mySchedule.overnight', { date: formatDate(language, end.date) })}</>
        )}
      </p>
      <p>
        {shift.site.name ?? t('workSchedules.siteUnknown')} ·{' '}
        {t('attendance.siteTime', { zone: shift.siteTimezone })}
      </p>
    </>
  );
}

function ShiftRow({ shift, language }: { shift: OperationalStatusShift; language: 'en' | 'fr' }) {
  return (
    <li className="card" data-testid="detail-uncovered">
      <ShiftFacts shift={shift} language={language} />
    </li>
  );
}

function NotCheckedInRow({
  row,
  language,
}: {
  row: OperationalStatusNotCheckedIn;
  language: 'en' | 'fr';
}) {
  const { t } = useTranslation();
  return (
    <li className="card" data-testid="detail-not-checked-in">
      <p>
        <strong>{personName(row.employee)}</strong>
      </p>
      <ShiftFacts shift={row.shift} language={language} />
      <p>{t('operationsStatus.details.minutesSince', { count: row.minutesSinceStart })}</p>
    </li>
  );
}

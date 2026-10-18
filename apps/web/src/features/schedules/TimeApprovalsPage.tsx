import type { TimeApprovalDetail, TimeApprovalItem } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { formatInstant } from './attendance';
import { TimesheetHistory } from './TimesheetHistory';
import { TimesheetRows } from './TimesheetRows';
import { TotalsList } from './TimesheetsPage';
import {
  REASON_MAX,
  STATUS_TONES,
  TIMESHEET_NETWORK_FAILURE,
  durationParts,
  reasonProblemOf,
  timesheetFailureOf,
  type ReasonProblem,
  type TimesheetFailure,
} from './timesheets';

type Queue =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: TimesheetFailure }
  | { kind: 'ready'; items: TimeApprovalItem[]; nextCursor: string | null };

type Opened =
  | { kind: 'loading'; id: string }
  | { kind: 'failed'; id: string; failure: TimesheetFailure }
  | { kind: 'ready'; id: string; timesheet: TimeApprovalDetail };

type Decision = 'approve' | 'return';

/** A fresh key per reviewed decision (the platform's key grammar): a retry reuses it. */
function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

function employeeName(
  t: (key: string, options?: Record<string, unknown>) => string,
  employee: { givenNames: string; familyName: string; employeeNumber: string },
): string {
  return t('timeApprovals.employee', {
    givenNames: employee.givenNames,
    familyName: employee.familyName,
    number: employee.employeeNumber,
  });
}

/**
 * MVP-070B/C: « Approbations du temps » / "Time approvals" under My space: the weekly timesheets
 * submitted by the caller's direct reports that the server routes to the caller now (the manager
 * effective on the week's site-local Sunday, evaluated on every call), newest submission first.
 * A timesheet opens its submitted shifts, totals, revision and history; the manager approves it
 * after an explicit confirmation, or returns it with a required reason (plain text, checked again
 * by the server). The decision binds the reviewed revision, generation version, cycle and
 * submission, with one stable idempotency key per attempt; a decided timesheet leaves the queue.
 * Nothing is kept in browser storage or in a URL.
 */
export function TimeApprovalsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [queue, setQueue] = useState<Queue>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const [opened, setOpened] = useState<Opened | null>(null);
  const [decision, setDecision] = useState<Decision | null>(null);
  const [reason, setReason] = useState('');
  const [reasonProblem, setReasonProblem] = useState<ReasonProblem | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [saving, setSaving] = useState(false);
  const [failure, setFailure] = useState<TimesheetFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const decisionKey = useRef<string>(newKey());
  const opener = useRef<HTMLButtonElement | null>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const receiptRef = useRef<HTMLParagraphElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const checkRef = useRef<HTMLInputElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const approveButton = useRef<HTMLButtonElement>(null);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Queue> => {
      try {
        const { data, error, response } = await core.GET('/me/time-approvals', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor ?? null }
          : { kind: 'failed', failure: timesheetFailureOf('manager', response, error) };
      } catch {
        return { kind: 'failed', failure: TIMESHEET_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    setQueue({ kind: 'loading' });
    setQueue(await fetchPage(null));
  }, [fetchPage]);

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setQueue(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    try {
      const next = await fetchPage(cursor);
      setQueue((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  const resetDecision = () => {
    setDecision(null);
    setReason('');
    setReasonProblem(null);
    setConfirmed(false);
    setFailure(null);
    decisionKey.current = newKey();
  };

  const open = async (id: string) => {
    resetDecision();
    setOpened({ kind: 'loading', id });
    try {
      const { data, error, response } = await core.GET('/me/time-approvals/{timesheetId}', {
        params: { path: { timesheetId: id } },
        cache: 'no-store',
      });
      setOpened(
        data
          ? { kind: 'ready', id, timesheet: data }
          : { kind: 'failed', id, failure: timesheetFailureOf('manager', response, error) },
      );
    } catch {
      setOpened({ kind: 'failed', id, failure: TIMESHEET_NETWORK_FAILURE });
    }
    requestAnimationFrame(() => detailHeading.current?.focus());
  };

  const close = () => {
    setOpened(null);
    resetDecision();
    requestAnimationFrame(() => {
      if (opener.current?.isConnected) opener.current.focus();
      else document.getElementById(`${ids}-title`)?.focus();
    });
  };

  const choose = (next: Decision) => {
    resetDecision();
    setDecision(next);
    requestAnimationFrame(() => {
      if (next === 'return') reasonRef.current?.focus();
      else checkRef.current?.focus();
    });
  };

  const decide = async (timesheet: TimeApprovalDetail) => {
    if (!decision || !confirmed || saving) return;
    const evidence = {
      revision: timesheet.revision,
      generationVersion: timesheet.generationVersion,
      cycle: timesheet.cycle,
      submissionActionId: timesheet.submissionActionId,
      confirm: true as const,
    };
    if (decision === 'return') {
      const problem = reasonProblemOf(reason);
      setReasonProblem(problem);
      if (problem) {
        requestAnimationFrame(() => reasonRef.current?.focus());
        return;
      }
    }
    setSaving(true);
    setFailure(null);
    try {
      const params = {
        path: { timesheetId: timesheet.timesheetId },
        header: { 'Idempotency-Key': decisionKey.current },
      };
      const { data, error, response } =
        decision === 'approve'
          ? await core.POST('/me/time-approvals/{timesheetId}/approval', {
              params,
              body: evidence,
            })
          : await core.POST('/me/time-approvals/{timesheetId}/return', {
              params,
              body: { ...evidence, reason: reason.trim() },
            });
      if (!data) {
        const mapped = timesheetFailureOf('manager', response, error);
        if (mapped.reason) setReasonProblem('format');
        setFailure(mapped);
        requestAnimationFrame(() => failureRef.current?.focus());
        return;
      }
      const name = employeeName(t, timesheet.employee);
      setAnnouncement(
        data.action === 'APPROVED'
          ? t('timeApprovals.decision.approved', { name })
          : t('timeApprovals.decision.returned', { name }),
      );
      setOpened(null);
      resetDecision();
      await reload();
      requestAnimationFrame(() => receiptRef.current?.focus());
    } catch {
      setFailure(TIMESHEET_NETWORK_FAILURE);
      requestAnimationFrame(() => failureRef.current?.focus());
    } finally {
      setSaving(false);
    }
  };

  const week = (from: string, to: string) => ({
    from: formatDate(i18n.language, from),
    to: formatDate(i18n.language, to),
  });

  const reasonHintId = `${ids}-reason-hint`;
  const reasonErrorId = `${ids}-reason-error`;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="time-approvals">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('timeApprovals.title')}
        description={t('timeApprovals.description')}
      />
      <p role="status" className="visually-hidden" data-testid="time-approvals-announcer">
        {announcement}
      </p>
      {announcement && (
        <p className="card" tabIndex={-1} ref={receiptRef} data-testid="time-approvals-receipt">
          {announcement}
        </p>
      )}
      {queue.kind === 'loading' && <p role="status">{t('timeApprovals.loading')}</p>}
      {queue.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="time-approvals-error">
          <p>{t(queue.failure.messageKey)}</p>
          {queue.failure.correlationId && (
            <p className="muted">{t('employees.reference', { id: queue.failure.correlationId })}</p>
          )}
          {queue.failure.kind !== 'linkRequired' && queue.failure.kind !== 'forbidden' && (
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void reload()}
            >
              {t('timeApprovals.retry')}
            </button>
          )}
        </div>
      )}
      {queue.kind === 'ready' && queue.items.length === 0 && (
        <div className="card" data-testid="time-approvals-empty">
          <p>{t('timeApprovals.empty')}</p>
        </div>
      )}
      {queue.kind === 'ready' && queue.items.length > 0 && (
        <ul className="shift-list" aria-label={t('timeApprovals.label')}>
          {queue.items.map((item) => {
            const name = employeeName(t, item.employee);
            return (
              <li key={item.timesheetId} className="card" data-testid="time-approval">
                <h2 className="shift-list__date">{name}</h2>
                <dl className="decision-summary">
                  <dt>{t('timeApprovals.weekLabel')}</dt>
                  <dd data-testid="time-approval-week">
                    {t('timeApprovals.week', week(item.period.weekStart, item.period.lastDay))}
                  </dd>
                  <dt>{t('timeApprovals.site')}</dt>
                  <dd>{item.period.siteName ?? t('workSchedules.siteUnknown')}</dd>
                  <dt>{t('timeApprovals.rows')}</dt>
                  <dd>{item.rowCount}</dd>
                  <dt>{t('timeApprovals.worked')}</dt>
                  <dd data-testid="time-approval-worked">
                    {t('timesheets.duration', durationParts(item.workedMinutes))}
                  </dd>
                </dl>
                <p className="muted" data-testid="time-approval-submitted">
                  {t('timeApprovals.submitted', {
                    time: formatInstant(language, item.submittedAt, item.period.timezone),
                  })}{' '}
                  {t('timeApprovals.cycle', { cycle: item.cycle })}
                </p>
                <p>
                  <button
                    type="button"
                    className="button button--secondary"
                    aria-label={t('timeApprovals.openFor', { name })}
                    data-testid="time-approval-open"
                    onClick={(event) => {
                      opener.current = event.currentTarget;
                      void open(item.timesheetId);
                    }}
                  >
                    {t('timeApprovals.open')}
                  </button>
                </p>
              </li>
            );
          })}
        </ul>
      )}
      {queue.kind === 'ready' && queue.nextCursor && (
        <p>
          <button
            type="button"
            className="button button--secondary"
            disabled={loadingMore}
            aria-busy={loadingMore ? true : undefined}
            onClick={() => {
              if (queue.nextCursor) void more(queue.nextCursor);
            }}
          >
            {loadingMore ? t('timeApprovals.loadingMore') : t('timeApprovals.more')}
          </button>
        </p>
      )}
      {opened && (
        <section
          className="card"
          aria-labelledby={opened.kind === 'ready' ? `${ids}-detail-title` : undefined}
          aria-label={opened.kind === 'ready' ? undefined : t('timeApprovals.rowsLabel')}
          data-testid="time-approval-detail"
        >
          {opened.kind === 'loading' && <p role="status">{t('timeApprovals.detailLoading')}</p>}
          {opened.kind === 'failed' && (
            <div className="error-summary" role="alert" data-testid="time-approval-detail-error">
              <p>{t(opened.failure.messageKey)}</p>
            </div>
          )}
          {opened.kind === 'ready' && (
            <>
              <h2 id={`${ids}-detail-title`} ref={detailHeading} tabIndex={-1}>
                {t('timeApprovals.detailTitle', {
                  name: employeeName(t, opened.timesheet.employee),
                })}
              </h2>
              <p>
                {t(
                  'timeApprovals.week',
                  week(opened.timesheet.period.weekStart, opened.timesheet.period.lastDay),
                )}
              </p>
              <p>
                <StatusBadge tone={STATUS_TONES.SUBMITTED} testId="time-approval-status">
                  {t('timesheets.status.SUBMITTED')}
                </StatusBadge>{' '}
                <span>{t('timeApprovals.revision', { revision: opened.timesheet.revision })}</span>{' '}
                <span>{t('timeApprovals.cycle', { cycle: opened.timesheet.cycle })}</span>
                {opened.timesheet.periodLocked && (
                  <>
                    {' '}
                    <StatusBadge tone="info" icon="shield" testId="time-approval-period-locked">
                      {t('timesheetPeriod.badge')}
                    </StatusBadge>
                  </>
                )}
              </p>
              {opened.timesheet.periodLocked && (
                <p className="muted" data-testid="time-approval-period-locked-hint">
                  {t('timesheetPeriod.badgeHint')}
                </p>
              )}
              <p className="muted">
                {t('timeApprovals.submitted', {
                  time: formatInstant(
                    language,
                    opened.timesheet.submittedAt,
                    opened.timesheet.period.timezone,
                  ),
                })}{' '}
                {t('timesheets.zone', { zone: opened.timesheet.period.timezone })}
              </p>
              <TotalsList totals={opened.timesheet.totals} testId="time-approval-totals" />
              <TimesheetRows
                rows={opened.timesheet.rows}
                zone={opened.timesheet.period.timezone}
                label={t('timeApprovals.rowsLabel')}
                testId="time-approval-rows"
              />
              <h3>{t('timesheets.history.title')}</h3>
              <TimesheetHistory
                actions={opened.timesheet.actions}
                zone={opened.timesheet.period.timezone}
                testId="time-approval-history"
              />
              {!decision && (
                <div className="button-row" data-testid="time-approval-actions">
                  <button
                    type="button"
                    className="button"
                    ref={approveButton}
                    data-testid="time-approval-approve"
                    onClick={() => {
                      choose('approve');
                    }}
                  >
                    {t('timeApprovals.decision.approve')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    data-testid="time-approval-return"
                    onClick={() => {
                      choose('return');
                    }}
                  >
                    {t('timeApprovals.decision.giveBack')}
                  </button>
                </div>
              )}
              {decision && (
                <section
                  aria-labelledby={`${ids}-decision-title`}
                  data-testid="time-approval-decision"
                >
                  <h3 id={`${ids}-decision-title`}>
                    {decision === 'approve'
                      ? t('timeApprovals.decision.approveTitle')
                      : t('timeApprovals.decision.returnTitle')}
                  </h3>
                  {decision === 'return' && (
                    <div className="field">
                      <label htmlFor={`${ids}-reason`}>{t('timeApprovals.decision.reason')}</label>
                      <p id={reasonHintId} className="field__help">
                        {t('timeApprovals.decision.reasonHint')}
                      </p>
                      {reasonProblem && (
                        <p
                          id={reasonErrorId}
                          className="field__error"
                          data-testid="time-approval-reason-error"
                        >
                          {t(`timeApprovals.decision.problem.${reasonProblem}`)}
                        </p>
                      )}
                      <textarea
                        id={`${ids}-reason`}
                        ref={reasonRef}
                        rows={4}
                        maxLength={REASON_MAX + 50}
                        value={reason}
                        required
                        aria-invalid={reasonProblem ? true : undefined}
                        aria-describedby={
                          reasonProblem ? `${reasonHintId} ${reasonErrorId}` : reasonHintId
                        }
                        data-testid="time-approval-reason"
                        onChange={(event) => {
                          setReason(event.target.value);
                          if (reasonProblem) setReasonProblem(null);
                        }}
                      />
                    </div>
                  )}
                  <div className="field">
                    <label>
                      <input
                        type="checkbox"
                        ref={checkRef}
                        checked={confirmed}
                        data-testid="time-approval-confirm-check"
                        onChange={(event) => {
                          setConfirmed(event.target.checked);
                        }}
                      />{' '}
                      {decision === 'approve'
                        ? t('timeApprovals.decision.approveCheck')
                        : t('timeApprovals.decision.returnCheck')}
                    </label>
                  </div>
                  <div className="button-row">
                    <button
                      type="button"
                      className="button"
                      disabled={!confirmed || saving}
                      aria-busy={saving ? true : undefined}
                      data-testid="time-approval-confirm"
                      onClick={() => void decide(opened.timesheet)}
                    >
                      {saving
                        ? t('timeApprovals.decision.saving')
                        : decision === 'approve'
                          ? t('timeApprovals.decision.confirmApprove')
                          : t('timeApprovals.decision.confirmReturn')}
                    </button>
                    <button
                      type="button"
                      className="button button--secondary"
                      disabled={saving}
                      onClick={() => {
                        resetDecision();
                        requestAnimationFrame(() => approveButton.current?.focus());
                      }}
                    >
                      {t('timeApprovals.decision.cancel')}
                    </button>
                  </div>
                </section>
              )}
              {failure && (
                <div
                  className="error-summary"
                  role="alert"
                  tabIndex={-1}
                  ref={failureRef}
                  data-testid="time-approval-error"
                >
                  <p>{t(failure.messageKey)}</p>
                  {failure.correlationId && (
                    <p className="muted">
                      {t('employees.reference', { id: failure.correlationId })}
                    </p>
                  )}
                </div>
              )}
            </>
          )}
          <p>
            <button
              type="button"
              className="button button--secondary"
              data-testid="time-approval-close"
              onClick={close}
            >
              {t('timeApprovals.close')}
            </button>
          </p>
        </section>
      )}
    </section>
  );
}

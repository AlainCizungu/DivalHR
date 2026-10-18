import type {
  TimesheetGenerationSummary,
  TimesheetPeriodAction,
  TimesheetPeriodHistory,
  TimesheetPeriodLockPreview,
  TimesheetPeriodUnlockPreview,
  TimesheetPeriodUnlockReason,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from './attendance';
import {
  TIMESHEET_NETWORK_FAILURE,
  UNLOCK_REASONS,
  durationParts,
  reasonProblemOf,
  timesheetFailureOf,
  type ReasonProblem,
  type TimesheetFailure,
} from './timesheets';

function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

/** The counts a lock binds or bound, as the server computed them. */
function CountsList({
  counts,
  testId,
}: {
  counts: TimesheetPeriodAction['counts'];
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <dl className="decision-summary" data-testid={testId}>
      <dt>{t('timesheetPeriod.counts.timesheets')}</dt>
      <dd data-testid={`${testId}-timesheets`}>{counts.timesheets}</dd>
      <dt>{t('timesheetPeriod.counts.employees')}</dt>
      <dd data-testid={`${testId}-employees`}>{counts.employees}</dd>
      <dt>{t('timesheetPeriod.counts.rows')}</dt>
      <dd data-testid={`${testId}-rows`}>{counts.rows}</dd>
      <dt>{t('timesheetPeriod.counts.worked')}</dt>
      <dd data-testid={`${testId}-worked`}>
        {t('timesheets.duration', durationParts(counts.workedMinutes))}
      </dd>
    </dl>
  );
}

/**
 * MVP-071 (Issue #139, L71-9): the lock of one generated week on the administrator's Timesheets
 * page. The state (text, never colour alone) and, while locked, the receipt of the current lock;
 * "Lock period" previews the readiness (counts by state, the stable blockers, whether this evidence
 * was exported) and locks only with that exact preview after an explicit confirmation; "Unlock
 * period" takes a closed reason and an explanation, previews the unlock with its warnings and
 * unlocks only with that exact preview; the immutable history, newest first. The browser decides
 * nothing: a stale or refused preview is shown as the server said and taken again. Nothing is kept
 * in browser storage or put in a URL; the explanation is sent once and never shown back.
 */
export function TimesheetPeriodLock({
  legalEntityId,
  generation,
  disabled,
  onTransition,
}: {
  legalEntityId: string;
  generation: TimesheetGenerationSummary;
  /** The page is busy with something else. */
  disabled: boolean;
  /** The week was locked or unlocked: the page reloads the generation. */
  onTransition: () => Promise<void>;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);
  const [failure, setFailure] = useState<TimesheetFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [history, setHistory] = useState<TimesheetPeriodHistory | null>(null);
  const [lockPreview, setLockPreview] = useState<TimesheetPeriodLockPreview | null>(null);
  const [lockConfirmed, setLockConfirmed] = useState(false);
  const lockKey = useRef(newKey());
  const [unlockOpen, setUnlockOpen] = useState(false);
  const [reasonCode, setReasonCode] = useState<TimesheetPeriodUnlockReason | ''>('');
  const [explanation, setExplanation] = useState('');
  const [reasonProblem, setReasonProblem] = useState<'reasonRequired' | null>(null);
  const [explanationProblem, setExplanationProblem] = useState<ReasonProblem | null>(null);
  const [unlockPreview, setUnlockPreview] = useState<TimesheetPeriodUnlockPreview | null>(null);
  const [unlockConfirmed, setUnlockConfirmed] = useState(false);
  const unlockKey = useRef(newKey());
  const lockHeading = useRef<HTMLHeadingElement>(null);
  const unlockHeading = useRef<HTMLHeadingElement>(null);
  const lockOpener = useRef<HTMLButtonElement>(null);
  const unlockOpener = useRef<HTMLButtonElement>(null);

  const period = generation.period;
  const lock = generation.periodLock;
  const locked = lock.state === 'LOCKED';
  const scope = { legalEntityId, siteId: period.siteId, weekStart: period.weekStart };
  const generationId = generation.generationId;
  const lockVersion = lock.version;

  useEffect(() => {
    let cancelled = false;
    void core
      .GET('/timesheet-periods/{generationId}', {
        params: { path: { generationId } },
        cache: 'no-store',
      })
      .then(({ data }) => {
        if (!cancelled && data) setHistory(data);
      })
      .catch(() => {
        // The history stays unavailable; the state above comes from the generation itself.
      });
    return () => {
      cancelled = true;
    };
  }, [core, generationId, lockVersion]);

  const run = async (work: () => Promise<void>) => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await work();
    } catch {
      setFailure(TIMESHEET_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const onLockPreview = () => {
    void run(async () => {
      const { data, error, response } = await core.POST('/timesheet-periods/lock-preview', {
        body: scope,
      });
      if (!data) {
        setFailure(timesheetFailureOf('admin', response, error));
        return;
      }
      lockKey.current = newKey();
      setLockConfirmed(false);
      setLockPreview(data);
      requestAnimationFrame(() => lockHeading.current?.focus());
    });
  };

  const onLock = () => {
    if (!lockPreview || !lockConfirmed || !lockPreview.ready) return;
    void run(async () => {
      const { data, error, response } = await core.POST('/timesheet-periods/locks', {
        params: { header: { 'Idempotency-Key': lockKey.current } },
        body: {
          ...scope,
          generationVersion: lockPreview.generationVersion,
          lockVersion: lockPreview.lock.version,
          previewDigest: lockPreview.previewDigest,
          confirm: true,
        },
      });
      if (!data) {
        const mapped = timesheetFailureOf('admin', response, error);
        if (mapped.kind === 'stale' || mapped.kind === 'notAllowed') setLockPreview(null);
        setFailure(mapped);
        return;
      }
      setLockPreview(null);
      setAnnouncement(
        data.outcome === 'UNCHANGED'
          ? t('timesheetPeriod.lock.unchanged')
          : t('timesheetPeriod.lock.done'),
      );
      await onTransition();
    });
  };

  const onUnlockPreview = () => {
    const reason = reasonCode ? null : 'reasonRequired';
    const problem = reasonProblemOf(explanation);
    setReasonProblem(reason);
    setExplanationProblem(problem);
    if (reason || problem || !reasonCode) return;
    void run(async () => {
      const { data, error, response } = await core.POST('/timesheet-periods/unlock-preview', {
        body: { ...scope, reasonCode, explanation: explanation.trim() },
      });
      if (!data) {
        const mapped = timesheetFailureOf('admin', response, error);
        if (mapped.reason) setExplanationProblem('format');
        setFailure(mapped);
        return;
      }
      unlockKey.current = newKey();
      setUnlockConfirmed(false);
      setUnlockPreview(data);
      requestAnimationFrame(() => unlockHeading.current?.focus());
    });
  };

  const onUnlock = () => {
    if (!unlockPreview || !unlockConfirmed || !reasonCode) return;
    void run(async () => {
      const { data, error, response } = await core.POST('/timesheet-periods/unlocks', {
        params: { header: { 'Idempotency-Key': unlockKey.current } },
        body: {
          ...scope,
          reasonCode,
          explanation: explanation.trim(),
          lockVersion: unlockPreview.lock.version,
          lockActionId: unlockPreview.currentLock.actionId,
          previewDigest: unlockPreview.previewDigest,
          confirm: true,
        },
      });
      if (!data) {
        const mapped = timesheetFailureOf('admin', response, error);
        if (mapped.kind === 'stale' || mapped.kind === 'notAllowed') setUnlockPreview(null);
        setFailure(mapped);
        return;
      }
      setUnlockPreview(null);
      setUnlockOpen(false);
      setReasonCode('');
      setExplanation('');
      setAnnouncement(t('timesheetPeriod.unlock.done'));
      await onTransition();
    });
  };

  const closeLock = () => {
    setLockPreview(null);
    requestAnimationFrame(() => lockOpener.current?.focus());
  };

  const closeUnlock = () => {
    setUnlockPreview(null);
    setUnlockOpen(false);
    setReasonProblem(null);
    setExplanationProblem(null);
    requestAnimationFrame(() => unlockOpener.current?.focus());
  };

  // Only the history of this generation at this lock version is shown (a reload replaces it).
  const shown =
    history && history.generationId === generationId && history.lock.version === lockVersion
      ? history
      : null;
  const current = shown?.actions.find((action) => action.actionId === lock.currentActionId);
  const idle = busy || disabled;

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="period-lock"
      data-state={lock.state}
    >
      <h3 id={`${ids}-title`}>{t('timesheetPeriod.title')}</h3>
      <p className="muted">{t('timesheetPeriod.description')}</p>
      <p role="status" className="visually-hidden" data-testid="period-lock-announcer">
        {announcement}
      </p>
      <p>
        <StatusBadge
          tone={locked ? 'info' : 'neutral'}
          icon={locked ? 'shield' : undefined}
          testId="period-lock-state"
        >
          {t(`timesheetPeriod.state.${lock.state}`)}
        </StatusBadge>
      </p>
      {locked && (
        <div className="card" data-testid="period-lock-banner">
          <p>
            <strong>{t('timesheetPeriod.lockedFor')}</strong>
          </p>
          {lock.updatedAt && (
            <p data-testid="period-lock-receipt">
              {t('timesheetPeriod.receipt', {
                version: lock.version,
                time: formatInstant(language, lock.updatedAt, period.timezone),
              })}
            </p>
          )}
          {current && (
            <p className="muted" data-testid="period-lock-receipt-counts">
              {t('timesheetPeriod.receiptCounts', {
                timesheets: current.counts.timesheets,
                employees: current.counts.employees,
                rows: current.counts.rows,
                worked: t('timesheets.duration', durationParts(current.counts.workedMinutes)),
              })}
            </p>
          )}
        </div>
      )}

      {failure && (
        <div className="error-summary" role="alert" data-testid="period-lock-error">
          <p>{t(failure.messageKey)}</p>
          {failure.correlationId && (
            <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}

      {!locked && !lockPreview && (
        <p>
          <button
            type="button"
            className="button"
            disabled={idle}
            aria-busy={busy ? true : undefined}
            ref={lockOpener}
            data-testid="period-lock-preview"
            onClick={onLockPreview}
          >
            {busy ? t('timesheetPeriod.lock.previewing') : t('timesheetPeriod.lock.action')}
          </button>
        </p>
      )}

      {lockPreview && (
        <section
          className="card"
          aria-labelledby={`${ids}-lock-title`}
          data-testid="period-lock-preview-card"
        >
          <h4 id={`${ids}-lock-title`} ref={lockHeading} tabIndex={-1}>
            {t('timesheetPeriod.lock.title')}
          </h4>
          <dl className="decision-summary" data-testid="period-lock-states">
            <dt>{t('timesheetPeriod.counts.approved')}</dt>
            <dd data-testid="period-lock-states-approved">{lockPreview.states.approved}</dd>
            <dt>{t('timesheetPeriod.counts.draft')}</dt>
            <dd data-testid="period-lock-states-draft">{lockPreview.states.draft}</dd>
            <dt>{t('timesheetPeriod.counts.submitted')}</dt>
            <dd data-testid="period-lock-states-submitted">{lockPreview.states.submitted}</dd>
            <dt>{t('timesheetPeriod.counts.reopened')}</dt>
            <dd data-testid="period-lock-states-reopened">{lockPreview.states.reopened}</dd>
          </dl>
          <CountsList counts={lockPreview.counts} testId="period-lock-counts" />
          <p className="muted" data-testid="period-lock-exported">
            {lockPreview.exported
              ? t('timesheetPeriod.lock.exported')
              : t('timesheetPeriod.lock.notExported')}
          </p>
          {lockPreview.ready ? (
            <>
              <p data-testid="period-lock-ready">{t('timesheetPeriod.lock.ready')}</p>
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={lockConfirmed}
                    data-testid="period-lock-confirm"
                    onChange={(event) => {
                      setLockConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('timesheetPeriod.lock.confirmCheck')}
                </label>
              </div>
            </>
          ) : (
            <div data-testid="period-lock-not-ready">
              <p>
                <strong>{t('timesheetPeriod.lock.notReady')}</strong>
              </p>
              <ul>
                {lockPreview.blockers.map((blocker) => (
                  <li key={blocker} data-testid="period-lock-blocker" data-blocker={blocker}>
                    {t(`timesheetPeriod.lock.blocker.${blocker}`)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="button-row">
            {lockPreview.ready && (
              <button
                type="button"
                className="button"
                disabled={idle || !lockConfirmed}
                aria-busy={busy ? true : undefined}
                data-testid="period-lock-apply"
                onClick={onLock}
              >
                {busy ? t('timesheetPeriod.lock.confirming') : t('timesheetPeriod.lock.confirm')}
              </button>
            )}
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="period-lock-cancel"
              onClick={closeLock}
            >
              {t('timesheetPeriod.lock.cancel')}
            </button>
          </div>
        </section>
      )}

      {locked && !unlockOpen && (
        <p>
          <button
            type="button"
            className="button button--secondary"
            disabled={idle}
            ref={unlockOpener}
            data-testid="period-unlock-open"
            onClick={() => {
              setUnlockOpen(true);
            }}
          >
            {t('timesheetPeriod.unlock.action')}
          </button>
        </p>
      )}

      {locked && unlockOpen && (
        <section
          className="card"
          aria-labelledby={`${ids}-unlock-title`}
          data-testid="period-unlock"
        >
          <h4 id={`${ids}-unlock-title`}>{t('timesheetPeriod.unlock.title')}</h4>
          <p className="muted">{t('timesheetPeriod.unlock.description')}</p>
          {!unlockPreview && (
            <form
              noValidate
              aria-label={t('timesheetPeriod.unlock.title')}
              onSubmit={(event) => {
                event.preventDefault();
                onUnlockPreview();
              }}
            >
              <div className="field">
                <label htmlFor={`${ids}-reason`}>{t('timesheetPeriod.unlock.reason')}</label>
                <select
                  id={`${ids}-reason`}
                  value={reasonCode}
                  aria-invalid={reasonProblem ? true : undefined}
                  aria-describedby={reasonProblem ? `${ids}-reason-error` : undefined}
                  data-testid="period-unlock-reason"
                  onChange={(event) => {
                    setReasonCode(event.target.value as TimesheetPeriodUnlockReason | '');
                    setReasonProblem(null);
                  }}
                >
                  <option value="">{t('timesheetPeriod.unlock.chooseReason')}</option>
                  {UNLOCK_REASONS.map((reason) => (
                    <option key={reason} value={reason}>
                      {t(`timesheetPeriod.reason.${reason}`)}
                    </option>
                  ))}
                </select>
                {reasonProblem && (
                  <p id={`${ids}-reason-error`} className="field__error">
                    {t(`timesheetPeriod.unlock.problem.${reasonProblem}`)}
                  </p>
                )}
              </div>
              <div className="field">
                <label htmlFor={`${ids}-explanation`}>
                  {t('timesheetPeriod.unlock.explanation')}
                </label>
                <textarea
                  id={`${ids}-explanation`}
                  rows={3}
                  value={explanation}
                  aria-invalid={explanationProblem ? true : undefined}
                  aria-describedby={`${ids}-explanation-hint${explanationProblem ? ` ${ids}-explanation-error` : ''}`}
                  data-testid="period-unlock-explanation"
                  onChange={(event) => {
                    setExplanation(event.target.value);
                    setExplanationProblem(null);
                  }}
                />
                <p id={`${ids}-explanation-hint`} className="muted">
                  {t('timesheetPeriod.unlock.explanationHint')}
                </p>
                {explanationProblem && (
                  <p id={`${ids}-explanation-error`} className="field__error">
                    {t(`timesheetPeriod.unlock.problem.${explanationProblem}`)}
                  </p>
                )}
              </div>
              <div className="button-row">
                <button
                  type="submit"
                  className="button"
                  disabled={idle}
                  aria-busy={busy ? true : undefined}
                  data-testid="period-unlock-preview"
                >
                  {busy
                    ? t('timesheetPeriod.unlock.previewing')
                    : t('timesheetPeriod.unlock.preview')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  data-testid="period-unlock-cancel"
                  onClick={closeUnlock}
                >
                  {t('timesheetPeriod.unlock.cancel')}
                </button>
              </div>
            </form>
          )}
          {unlockPreview && (
            <div data-testid="period-unlock-preview-card">
              <h5 ref={unlockHeading} tabIndex={-1}>
                {t('timesheetPeriod.unlock.previewTitle')}
              </h5>
              <p>
                <strong>{t('timesheetPeriod.unlock.currentLock')}</strong>{' '}
                <span data-testid="period-unlock-current">
                  {t('timesheetPeriod.receipt', {
                    version: unlockPreview.currentLock.sequence,
                    time: formatInstant(
                      language,
                      unlockPreview.currentLock.occurredAt,
                      period.timezone,
                    ),
                  })}
                </span>
              </p>
              <CountsList counts={unlockPreview.currentLock.counts} testId="period-unlock-counts" />
              <p data-testid="period-unlock-reason-text">
                {t('timesheetPeriod.unlock.reason')}
                {': '}
                {t(`timesheetPeriod.reason.${unlockPreview.reasonCode}`)}
              </p>
              <ul data-testid="period-unlock-warnings">
                {unlockPreview.warnings.map((warning) => (
                  <li key={warning}>
                    <strong>{t(`timesheetPeriod.unlock.warning.${warning}`)}</strong>
                  </li>
                ))}
              </ul>
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={unlockConfirmed}
                    data-testid="period-unlock-confirm"
                    onChange={(event) => {
                      setUnlockConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('timesheetPeriod.unlock.confirmCheck')}
                </label>
              </div>
              <div className="button-row">
                <button
                  type="button"
                  className="button"
                  disabled={idle || !unlockConfirmed}
                  aria-busy={busy ? true : undefined}
                  data-testid="period-unlock-apply"
                  onClick={onUnlock}
                >
                  {busy
                    ? t('timesheetPeriod.unlock.confirming')
                    : t('timesheetPeriod.unlock.confirm')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  data-testid="period-unlock-cancel"
                  onClick={closeUnlock}
                >
                  {t('timesheetPeriod.unlock.cancel')}
                </button>
              </div>
            </div>
          )}
        </section>
      )}

      <h4>{t('timesheetPeriod.history.title')}</h4>
      {shown && shown.actions.length === 0 && (
        <p className="muted" data-testid="period-lock-history-none">
          {t('timesheetPeriod.history.none')}
        </p>
      )}
      {shown && shown.actions.length > 0 && (
        <ScrollRegion label={t('timesheetPeriod.history.label')}>
          <table className="timesheet-table" data-testid="period-lock-history">
            <caption className="visually-hidden">{t('timesheetPeriod.history.label')}</caption>
            <thead>
              <tr>
                <th scope="col">{t('timesheetPeriod.history.columns.action')}</th>
                <th scope="col">{t('timesheetPeriod.history.columns.version')}</th>
                <th scope="col">{t('timesheetPeriod.history.columns.when')}</th>
                <th scope="col">{t('timesheetPeriod.history.columns.timesheets')}</th>
                <th scope="col">{t('timesheetPeriod.history.columns.worked')}</th>
                <th scope="col">{t('timesheetPeriod.history.columns.reason')}</th>
              </tr>
            </thead>
            <tbody>
              {shown.actions.map((action) => (
                <tr
                  key={action.actionId}
                  data-testid="period-lock-action"
                  data-action={action.action}
                >
                  <th scope="row">{t(`timesheetPeriod.history.action.${action.action}`)}</th>
                  <td>{action.sequence}</td>
                  <td>{formatInstant(language, action.occurredAt, period.timezone)}</td>
                  <td>{action.counts.timesheets}</td>
                  <td>{t('timesheets.duration', durationParts(action.counts.workedMinutes))}</td>
                  <td data-testid="period-lock-action-reason">
                    {action.unlockReason
                      ? t(`timesheetPeriod.reason.${action.unlockReason}`)
                      : t('timesheetPeriod.history.noReason')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
    </section>
  );
}

import type {
  PayrollInputLockPreview,
  PayrollInputRun,
  PayrollInputSupersedePreview,
  PayrollInputVersion,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from '../schedules/attendance';
import { durationParts } from '../schedules/timesheets';
import {
  LIFECYCLE_TONES,
  MAX_EXPLANATION,
  PAYROLL_NETWORK_FAILURE,
  SUPERSEDE_REASONS,
  explanationProblemOf,
  payrollFailureOf,
  type PayrollFailure,
  type SupersedeReason,
} from './payrollInputs';
import { SalaryNotice } from './SalaryNotice';

/** A fresh key per reviewed action (the platform's key grammar): a retry reuses it. */
function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

/** The lifecycle badge: the text OPEN or LOCKED carries the meaning, never the colour alone. */
export function LifecycleBadge({ run }: { run: PayrollInputRun }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={LIFECYCLE_TONES[run.lifecycle.state]}>
      {t(`payrollPreparation.lifecycle.state.${run.lifecycle.state}`)}
    </StatusBadge>
  );
}

function Blockers({ codes, testId }: { codes: readonly string[]; testId: string }) {
  const { t } = useTranslation();
  return (
    <ul className="plain-list" data-testid={testId}>
      {codes.map((code) => (
        <li key={code} data-code={code} data-testid={`${testId}-${code}`}>
          {t(`payrollPreparation.lifecycle.blocker.${code}`)}
        </li>
      ))}
    </ul>
  );
}

/**
 * MVP-092: the version chain, the supersede flow, the final lock and the worked-time export of one
 * payroll-input run. The server decides every state, blocker and digest; the browser only shows
 * them, asks for the reviewed confirmation and sends one Idempotency-Key per reviewed action.
 * Worked-time inputs only: nothing here calculates or authorizes pay. Nothing is kept in browser
 * storage or put in a URL.
 */
export function PayrollLifecyclePanel({
  run,
  onOpenRun,
  onChanged,
  announce,
}: {
  run: PayrollInputRun;
  onOpenRun: (runId: string) => void;
  /** Reloads the run (and the exports) after a lock or an export. */
  onChanged: (runId: string) => Promise<void>;
  announce: (text: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const runId = run.runId;
  const open = run.lifecycle.state === 'OPEN';
  const [versions, setVersions] = useState<PayrollInputVersion[] | 'loading' | 'failed'>('loading');
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [supersede, setSupersede] = useState<PayrollInputSupersedePreview | null>(null);
  const [reason, setReason] = useState<SupersedeReason>('UPSTREAM_CORRECTION');
  const [explanation, setExplanation] = useState('');
  const [explanationShown, setExplanationShown] = useState(false);
  const [supersedeConfirmed, setSupersedeConfirmed] = useState(false);
  const supersedeKey = useRef(newKey());
  const [lock, setLock] = useState<PayrollInputLockPreview | null>(null);
  const [lockConfirmed, setLockConfirmed] = useState(false);
  const lockKey = useRef(newKey());
  const [exportConfirmed, setExportConfirmed] = useState(false);
  const exportKey = useRef(newKey());
  const supersedeHeading = useRef<HTMLHeadingElement>(null);
  const lockHeading = useRef<HTMLHeadingElement>(null);

  const loadVersions = async () => {
    try {
      const { data } = await core.GET('/payroll-inputs/{runId}/versions', {
        params: { path: { runId } },
        cache: 'no-store',
      });
      setVersions(data ? data.items : 'failed');
    } catch {
      setVersions('failed');
    }
  };

  useEffect(() => {
    let active = true;
    void core
      .GET('/payroll-inputs/{runId}/versions', {
        params: { path: { runId } },
        cache: 'no-store',
      })
      .then(({ data }) => {
        if (active) setVersions(data ? data.items : 'failed');
      })
      .catch(() => {
        if (active) setVersions('failed');
      });
    return () => {
      active = false;
    };
  }, [core, runId]);

  const act = async (work: () => Promise<void>) => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await work();
    } catch {
      setFailure(PAYROLL_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  /** A conflict that makes a reviewed preview worthless: drop it and refresh the chain. */
  const failed = (response: Response, error: unknown) => {
    const mapped = payrollFailureOf(response, error);
    if (
      mapped.kind === 'stale' ||
      mapped.kind === 'notReady' ||
      mapped.kind === 'notLatest' ||
      mapped.kind === 'locked'
    ) {
      setSupersede(null);
      setLock(null);
      void loadVersions();
    }
    setFailure(
      mapped.kind === 'validation'
        ? { ...mapped, messageKey: 'payrollPreparation.lifecycle.supersede.validation' }
        : mapped,
    );
  };

  const onCheckSupersede = () => {
    void act(async () => {
      const { data, error, response } = await core.POST(
        '/payroll-inputs/{runId}/supersede-preview',
        { params: { path: { runId } } },
      );
      if (!data) {
        setSupersede(null);
        failed(response, error);
        return;
      }
      supersedeKey.current = newKey();
      setSupersedeConfirmed(false);
      setExplanationShown(false);
      setSupersede(data);
      requestAnimationFrame(() => supersedeHeading.current?.focus());
    });
  };

  const explanationProblem = explanationShown ? explanationProblemOf(reason, explanation) : null;

  const onSupersede = () => {
    if (!supersede?.ready || !supersedeConfirmed) return;
    setExplanationShown(true);
    if (explanationProblemOf(reason, explanation)) return;
    const preview = supersede;
    void act(async () => {
      const { data, error, response } = await core.POST('/payroll-inputs/{runId}/supersede', {
        params: { path: { runId }, header: { 'Idempotency-Key': supersedeKey.current } },
        body: {
          reasonCode: reason,
          ...(reason === 'OTHER' ? { explanation } : {}),
          previewDigest: preview.previewDigest,
          confirm: true,
        },
      });
      if (!data) {
        failed(response, error);
        return;
      }
      announce(t('payrollPreparation.lifecycle.supersede.done', { version: data.run.version }));
      onOpenRun(data.run.runId);
    });
  };

  const onCheckLock = () => {
    void act(async () => {
      const { data, error, response } = await core.POST('/payroll-inputs/{runId}/lock-preview', {
        params: { path: { runId } },
      });
      if (!data) {
        setLock(null);
        failed(response, error);
        return;
      }
      lockKey.current = newKey();
      setLockConfirmed(false);
      setLock(data);
      requestAnimationFrame(() => lockHeading.current?.focus());
    });
  };

  const onLock = () => {
    if (!lock?.ready || !lockConfirmed) return;
    const preview = lock;
    void act(async () => {
      const { data, error, response } = await core.POST('/payroll-inputs/{runId}/lock', {
        params: { path: { runId }, header: { 'Idempotency-Key': lockKey.current } },
        body: { previewDigest: preview.previewDigest, confirm: true },
      });
      if (!data) {
        failed(response, error);
        return;
      }
      announce(t('payrollPreparation.lifecycle.lock.done', { version: data.run.version }));
      await onChanged(runId);
    });
  };

  const onExport = () => {
    if (!exportConfirmed) return;
    void act(async () => {
      const { data, error, response } = await core.POST('/payroll-input-exports', {
        params: { header: { 'Idempotency-Key': exportKey.current } },
        body: { runId, confirm: true },
      });
      if (!data) {
        setFailure(payrollFailureOf(response, error));
        return;
      }
      announce(
        data.outcome === 'CREATED'
          ? t('payrollPreparation.export.created')
          : t('payrollPreparation.export.existing'),
      );
      await onChanged(runId);
    });
  };

  const chain = Array.isArray(versions) ? versions : [];
  const versionOf = (id: string | null) => chain.find((item) => item.run.runId === id)?.run.version;
  const latest = chain.find((item) => item.run.lifecycle.latest)?.run;

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="payroll-prep-lifecycle"
      data-state={run.lifecycle.state}
      data-latest={String(run.lifecycle.latest)}
    >
      <h3 id={`${ids}-title`}>{t('payrollPreparation.lifecycle.title')}</h3>
      <p>
        <span data-testid="payroll-prep-lifecycle-state">
          <LifecycleBadge run={run} />
        </span>{' '}
        {run.lifecycle.latest
          ? t('payrollPreparation.lifecycle.latest')
          : t('payrollPreparation.lifecycle.notLatest')}
      </p>
      {run.lifecycle.state === 'LOCKED' && run.lifecycle.lockedAt && (
        <p data-testid="payroll-prep-locked-at">
          {t('payrollPreparation.lifecycle.lock.locked', {
            time: formatInstant(language, run.lifecycle.lockedAt, run.scope.businessTimezone),
          })}
        </p>
      )}
      {!run.lifecycle.latest && latest && (
        <p>
          <button
            type="button"
            className="button button--secondary"
            disabled={busy}
            data-testid="payroll-prep-open-latest"
            onClick={() => {
              onOpenRun(latest.runId);
            }}
          >
            {t('payrollPreparation.lifecycle.openLatest', { version: latest.version })}
          </button>
        </p>
      )}

      <h4>{t('payrollPreparation.lifecycle.versions.title')}</h4>
      {versions === 'loading' && (
        <p className="muted" role="status" data-testid="payroll-prep-versions-loading">
          {t('payrollPreparation.lifecycle.versions.loading')}
        </p>
      )}
      {versions === 'failed' && (
        <p className="field__error" role="alert" data-testid="payroll-prep-versions-failed">
          {t('payrollPreparation.lifecycle.versions.unavailable')}
        </p>
      )}
      {chain.length > 0 && (
        <ScrollRegion label={t('payrollPreparation.lifecycle.versions.table')}>
          <table className="timesheet-table" data-testid="payroll-prep-versions">
            <caption className="visually-hidden">
              {t('payrollPreparation.lifecycle.versions.table')}
            </caption>
            <thead>
              <tr>
                <th scope="col">{t('payrollPreparation.detail.version')}</th>
                <th scope="col">{t('payrollPreparation.weeks.status')}</th>
                <th scope="col">{t('payrollPreparation.lifecycle.versions.replaces')}</th>
                <th scope="col">{t('payrollPreparation.history.generated')}</th>
                <th scope="col">{t('payrollPreparation.history.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {chain.map((item) => (
                <tr
                  key={item.run.runId}
                  data-testid="payroll-prep-version"
                  data-version={item.run.version}
                  data-state={item.run.lifecycle.state}
                  data-latest={String(item.run.lifecycle.latest)}
                >
                  <td>
                    {t('payrollPreparation.lifecycle.versions.item', { version: item.run.version })}
                    {item.run.lifecycle.latest && (
                      <>
                        {' · '}
                        {t('payrollPreparation.lifecycle.versions.latestTag')}
                      </>
                    )}
                  </td>
                  <td>
                    <LifecycleBadge run={item.run} />
                  </td>
                  <td>
                    {item.run.lifecycle.reasonCode ? (
                      <>
                        {t('payrollPreparation.lifecycle.versions.replacesVersion', {
                          version: versionOf(item.run.lifecycle.supersedesRunId) ?? '—',
                        })}
                        {' · '}
                        {t(`payrollPreparation.lifecycle.reason.${item.run.lifecycle.reasonCode}`)}
                        {item.explanation && (
                          <>
                            {' · '}
                            <span className="break-anywhere" data-testid="payroll-prep-explanation">
                              {t('payrollPreparation.lifecycle.versions.explanation', {
                                text: item.explanation,
                              })}
                            </span>
                          </>
                        )}
                      </>
                    ) : (
                      '—'
                    )}
                  </td>
                  <td>
                    {formatInstant(language, item.run.generatedAt, item.run.scope.businessTimezone)}
                  </td>
                  <td>
                    {item.run.runId === runId ? (
                      <span className="muted">
                        {t('payrollPreparation.lifecycle.versions.shown')}
                      </span>
                    ) : (
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('payrollPreparation.lifecycle.versions.openFor', {
                          version: item.run.version,
                        })}
                        data-testid="payroll-prep-version-open"
                        onClick={() => {
                          onOpenRun(item.run.runId);
                        }}
                      >
                        {t('payrollPreparation.history.open')}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}

      {failure && (
        <div className="error-summary" role="alert" data-testid="payroll-prep-lifecycle-error">
          <p>{t(failure.messageKey)}</p>
          {failure.correlationId && (
            <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}

      {open && (
        <>
          <h4>{t('payrollPreparation.lifecycle.supersede.title')}</h4>
          <p className="muted">{t('payrollPreparation.lifecycle.supersede.hint')}</p>
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              aria-busy={busy ? true : undefined}
              data-testid="payroll-prep-supersede-check"
              onClick={onCheckSupersede}
            >
              {busy
                ? t('payrollPreparation.lifecycle.checking')
                : t('payrollPreparation.lifecycle.supersede.check')}
            </button>
          </p>
          {supersede && (
            <div
              data-testid="payroll-prep-supersede-preview"
              data-ready={supersede.ready ? 'true' : 'false'}
            >
              <h5 ref={supersedeHeading} tabIndex={-1}>
                {t('payrollPreparation.lifecycle.supersede.previewTitle')}
              </h5>
              {supersede.ready ? (
                <>
                  <p>
                    {t('payrollPreparation.lifecycle.supersede.changed', {
                      rows: supersede.eligible.rows,
                      employees: supersede.eligible.employees,
                      time: t(
                        'timesheets.duration',
                        durationParts(supersede.eligible.workedMinutes),
                      ),
                    })}
                  </p>
                  <p className="muted" data-testid="payroll-prep-supersede-expiry">
                    {t('payrollPreparation.preview.expires', {
                      time: formatInstant(
                        language,
                        supersede.expiresAt,
                        run.scope.businessTimezone,
                      ),
                    })}
                  </p>
                  <div className="field">
                    <label htmlFor={`${ids}-reason`}>
                      {t('payrollPreparation.lifecycle.supersede.reasonLabel')}
                    </label>
                    <select
                      id={`${ids}-reason`}
                      value={reason}
                      data-testid="payroll-prep-supersede-reason"
                      onChange={(event) => {
                        setReason(event.target.value as SupersedeReason);
                      }}
                    >
                      {SUPERSEDE_REASONS.map((code) => (
                        <option key={code} value={code}>
                          {t(`payrollPreparation.lifecycle.reason.${code}`)}
                        </option>
                      ))}
                    </select>
                  </div>
                  {reason === 'OTHER' && (
                    <div className="field">
                      <label htmlFor={`${ids}-explanation`}>
                        {t('payrollPreparation.lifecycle.supersede.explanationLabel')}
                      </label>
                      <textarea
                        id={`${ids}-explanation`}
                        rows={3}
                        maxLength={MAX_EXPLANATION}
                        value={explanation}
                        aria-invalid={explanationProblem ? true : undefined}
                        aria-describedby={
                          `${ids}-explanation-hint` +
                          (explanationProblem ? ` ${ids}-explanation-error` : '')
                        }
                        data-testid="payroll-prep-supersede-explanation"
                        onChange={(event) => {
                          setExplanation(event.target.value);
                        }}
                      />
                      <p id={`${ids}-explanation-hint`} className="muted">
                        {t('payrollPreparation.lifecycle.supersede.explanationHint', {
                          max: MAX_EXPLANATION,
                        })}
                      </p>
                      {explanationProblem && (
                        <p id={`${ids}-explanation-error`} className="field__error">
                          {t(
                            `payrollPreparation.lifecycle.supersede.problem.${explanationProblem}`,
                          )}
                        </p>
                      )}
                    </div>
                  )}
                  <div className="field">
                    <label>
                      <input
                        type="checkbox"
                        checked={supersedeConfirmed}
                        data-testid="payroll-prep-supersede-confirm"
                        onChange={(event) => {
                          setSupersedeConfirmed(event.target.checked);
                        }}
                      />{' '}
                      {t('payrollPreparation.lifecycle.supersede.confirmCheck')}
                    </label>
                  </div>
                  <SalaryNotice testId="payroll-prep-supersede-notice" />
                  <p>
                    <button
                      type="button"
                      className="button"
                      disabled={busy || !supersedeConfirmed}
                      aria-busy={busy ? true : undefined}
                      data-testid="payroll-prep-supersede"
                      onClick={onSupersede}
                    >
                      {busy
                        ? t('payrollPreparation.lifecycle.acting')
                        : t('payrollPreparation.lifecycle.supersede.action')}
                    </button>
                  </p>
                </>
              ) : (
                <div
                  className="error-summary"
                  role="status"
                  data-testid="payroll-prep-supersede-blocked"
                >
                  <p>
                    {supersede.blockers.includes('NO_CHANGED_EVIDENCE')
                      ? t('payrollPreparation.lifecycle.supersede.noChange')
                      : t('payrollPreparation.lifecycle.supersede.blocked')}
                  </p>
                  <Blockers codes={supersede.blockers} testId="payroll-prep-supersede-blocker" />
                </div>
              )}
            </div>
          )}

          <h4>{t('payrollPreparation.lifecycle.lock.title')}</h4>
          <p className="muted">{t('payrollPreparation.lifecycle.lock.hint')}</p>
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              aria-busy={busy ? true : undefined}
              data-testid="payroll-prep-lock-check"
              onClick={onCheckLock}
            >
              {busy
                ? t('payrollPreparation.lifecycle.checking')
                : t('payrollPreparation.lifecycle.lock.check')}
            </button>
          </p>
          {lock && (
            <div data-testid="payroll-prep-lock-preview" data-ready={lock.ready ? 'true' : 'false'}>
              <h5 ref={lockHeading} tabIndex={-1}>
                {t('payrollPreparation.lifecycle.lock.previewTitle', { version: lock.run.version })}
              </h5>
              <dl className="decision-summary">
                <dt>{t('payrollPreparation.counts.employees')}</dt>
                <dd data-testid="payroll-prep-lock-employees">{lock.counts.employees}</dd>
                <dt>{t('payrollPreparation.counts.rows')}</dt>
                <dd data-testid="payroll-prep-lock-rows">{lock.counts.rows}</dd>
                <dt>{t('payrollPreparation.counts.worked')}</dt>
                <dd data-testid="payroll-prep-lock-worked">
                  {t('timesheets.duration', durationParts(lock.counts.workedMinutes))}
                </dd>
                <dt>{t('payrollPreparation.detail.weeks')}</dt>
                <dd>{lock.weeks.length}</dd>
              </dl>
              {lock.supersedesVersion !== null && (
                <p>
                  {t('payrollPreparation.lifecycle.lock.supersedes', {
                    version: lock.supersedesVersion,
                  })}
                </p>
              )}
              <SalaryNotice testId="payroll-prep-lock-notice" />
              {lock.ready ? (
                <>
                  <p className="muted" data-testid="payroll-prep-lock-expiry">
                    {t('payrollPreparation.preview.expires', {
                      time: formatInstant(language, lock.expiresAt, run.scope.businessTimezone),
                    })}
                  </p>
                  <p>{t('payrollPreparation.lifecycle.lock.final')}</p>
                  <div className="field">
                    <label>
                      <input
                        type="checkbox"
                        checked={lockConfirmed}
                        data-testid="payroll-prep-lock-confirm"
                        onChange={(event) => {
                          setLockConfirmed(event.target.checked);
                        }}
                      />{' '}
                      {t('payrollPreparation.lifecycle.lock.confirmCheck')}
                    </label>
                  </div>
                  <p>
                    <button
                      type="button"
                      className="button"
                      disabled={busy || !lockConfirmed}
                      aria-busy={busy ? true : undefined}
                      data-testid="payroll-prep-lock"
                      onClick={onLock}
                    >
                      {busy
                        ? t('payrollPreparation.lifecycle.acting')
                        : t('payrollPreparation.lifecycle.lock.action')}
                    </button>
                  </p>
                </>
              ) : (
                <div
                  className="error-summary"
                  role="status"
                  data-testid="payroll-prep-lock-blocked"
                >
                  <p>{t('payrollPreparation.lifecycle.lock.blocked')}</p>
                  <Blockers codes={lock.blockers} testId="payroll-prep-lock-blocker" />
                </div>
              )}
            </div>
          )}
        </>
      )}

      {!open && (
        <>
          <h4>{t('payrollPreparation.export.title')}</h4>
          {run.lifecycle.exportId ? (
            <p data-testid="payroll-prep-export-exists">{t('payrollPreparation.export.exists')}</p>
          ) : (
            <>
              <p className="muted">{t('payrollPreparation.export.hint')}</p>
              <SalaryNotice testId="payroll-prep-export-notice" />
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={exportConfirmed}
                    data-testid="payroll-prep-export-confirm"
                    onChange={(event) => {
                      setExportConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('payrollPreparation.export.confirmCheck')}
                </label>
              </div>
              <p>
                <button
                  type="button"
                  className="button"
                  disabled={busy || !exportConfirmed}
                  aria-busy={busy ? true : undefined}
                  data-testid="payroll-prep-export-create"
                  onClick={onExport}
                >
                  {busy
                    ? t('payrollPreparation.export.creating')
                    : t('payrollPreparation.export.create')}
                </button>
              </p>
            </>
          )}
        </>
      )}
      <p className="muted">{t('payrollPreparation.lifecycle.immutable')}</p>
    </section>
  );
}

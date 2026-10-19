import type {
  PayrollInputCodeCount,
  PayrollInputPreview,
  PayrollInputRun,
  PayrollInputRunDetail,
  PayrollInputScope,
  PayrollInputWeek,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { PayrollVarianceReview } from './PayrollVarianceReview';
import { formatInstant } from '../schedules/attendance';
import { durationParts } from '../schedules/timesheets';
import {
  PAYROLL_NETWORK_FAILURE,
  WEEK_TONES,
  firstOfMonth,
  payrollFailureOf,
  periodProblemsOf,
  todayIn,
  type PayrollFailure,
  type PeriodField,
  type PeriodForm,
  type PeriodProblem,
} from './payrollInputs';

/** A fresh key per reviewed preview (the platform's key grammar): a retry reuses it. */
function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

function Failure({ failure, testId }: { failure: PayrollFailure; testId: string }) {
  const { t } = useTranslation();
  return (
    <div className="error-summary" role="alert" data-testid={testId}>
      <p>{t(failure.messageKey)}</p>
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** The scope: legal entity and the inclusive period in the business zone. */
function ScopeText({ scope }: { scope: PayrollInputScope }) {
  const { t, i18n } = useTranslation();
  return (
    <>
      <p data-testid="payroll-prep-scope">
        <strong>
          {scope.legalEntityName && scope.legalEntityCode
            ? t('workSchedules.siteName', {
                name: scope.legalEntityName,
                code: scope.legalEntityCode,
              })
            : t('payrollPreparation.scope.unknownEntity')}
        </strong>
        {' · '}
        {t('timesheets.period', {
          from: formatDate(i18n.language, scope.periodStart),
          to: formatDate(i18n.language, scope.periodEnd),
        })}
      </p>
      <p className="muted">
        {t('payrollPreparation.scope.zone', { zone: scope.businessTimezone })}
      </p>
    </>
  );
}

/** Timesheets, employees, rows and approved worked time. */
function Counts({
  counts,
  testId,
}: {
  counts: { timesheets: number; employees: number; rows: number; workedMinutes: number };
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <dl className="decision-summary" data-testid={testId}>
      <dt>{t('payrollPreparation.counts.employees')}</dt>
      <dd data-testid={`${testId}-employees`}>{counts.employees}</dd>
      <dt>{t('payrollPreparation.counts.timesheets')}</dt>
      <dd data-testid={`${testId}-timesheets`}>{counts.timesheets}</dd>
      <dt>{t('payrollPreparation.counts.rows')}</dt>
      <dd data-testid={`${testId}-rows`}>{counts.rows}</dd>
      <dt>{t('payrollPreparation.counts.worked')}</dt>
      <dd data-testid={`${testId}-worked`}>
        {t('timesheets.duration', durationParts(counts.workedMinutes))}
      </dd>
    </dl>
  );
}

/** Stable codes with counts, each translated; only the codes with a count unless all are shown. */
function CodeList({
  pairs,
  prefix,
  testId,
  onlyPositive,
}: {
  pairs: PayrollInputCodeCount[];
  prefix: string;
  testId: string;
  onlyPositive: boolean;
}) {
  const { t } = useTranslation();
  const shown = onlyPositive ? pairs.filter((pair) => pair.count > 0) : pairs;
  if (shown.length === 0) {
    return (
      <p className="muted" data-testid={`${testId}-none`}>
        {t(`${prefix}.none`)}
      </p>
    );
  }
  return (
    <ul className="plain-list" data-testid={testId}>
      {shown.map((pair) => (
        <li key={pair.code} data-testid={`${testId}-${pair.code}`} data-code={pair.code}>
          {t(`${prefix}.${pair.code}`, { count: pair.count })}
        </li>
      ))}
    </ul>
  );
}

/** The weeks the period touches, with their lock status; never a person. */
function WeeksTable({ weeks, testId }: { weeks: PayrollInputWeek[]; testId: string }) {
  const { t, i18n } = useTranslation();
  return (
    <ScrollRegion label={t('payrollPreparation.weeks.table')}>
      <table className="timesheet-table">
        <caption className="visually-hidden">{t('payrollPreparation.weeks.table')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('payrollPreparation.weeks.site')}</th>
            <th scope="col">{t('payrollPreparation.weeks.week')}</th>
            <th scope="col">{t('payrollPreparation.weeks.status')}</th>
            <th scope="col">{t('payrollPreparation.weeks.versions')}</th>
            <th scope="col">{t('payrollPreparation.counts.rows')}</th>
            <th scope="col">{t('payrollPreparation.counts.worked')}</th>
          </tr>
        </thead>
        <tbody>
          {weeks.map((week) => (
            <tr
              key={`${week.siteId}-${week.weekStart}`}
              data-testid={`${testId}-row`}
              data-status={week.status}
            >
              <td>
                {week.siteName && week.siteCode
                  ? t('workSchedules.siteName', { name: week.siteName, code: week.siteCode })
                  : t('workSchedules.siteUnknown')}
              </td>
              <td>{formatDate(i18n.language, week.weekStart)}</td>
              <td>
                <StatusBadge tone={WEEK_TONES[week.status]}>
                  {t(`payrollPreparation.weekStatus.${week.status}`)}
                </StatusBadge>
              </td>
              <td>
                {week.generationVersion === null
                  ? '—'
                  : t('payrollPreparation.weeks.versionText', {
                      generation: week.generationVersion,
                      lock: week.lockVersion ?? '—',
                    })}
              </td>
              <td>{week.counts ? week.counts.rows : '—'}</td>
              <td>
                {week.counts
                  ? t('timesheets.duration', durationParts(week.counts.workedMinutes))
                  : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

/** A stored source week shown as a LOCKED week row. */
function sourceWeekRows(detail: PayrollInputRunDetail): PayrollInputWeek[] {
  return detail.weeks.map((week) => ({
    siteId: week.siteId,
    siteCode: week.siteCode,
    siteName: week.siteName,
    weekStart: week.weekStart,
    timezone: week.timezone,
    status: 'LOCKED' as const,
    generationId: week.generationId,
    generationVersion: week.generationVersion,
    lockActionId: week.lockActionId,
    lockVersion: week.lockVersion,
    counts: week.counts,
  }));
}

/**
 * MVP-090A: « Préparation de la paie » / "Payroll preparation" under Payroll. A tenant
 * administrator with MFA chooses a legal entity and an inclusive period, previews exactly what the
 * locked approved time holds (every week the period touches with its lock status, the counts, the
 * exclusions and the blockers with stable codes), confirms and generates an immutable run of payroll
 * inputs, then reads it and the history. Payroll inputs only: DivalHR has not calculated gross pay,
 * deductions, tax, net pay or payment. No name, employee number or sensitive value is shown, kept in
 * browser storage or put in a URL.
 */
export function PayrollPreparationPage() {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const today = todayIn(undefined);
  const [form, setForm] = useState<PeriodForm>({
    legalEntityId: '',
    periodStart: firstOfMonth(today),
    periodEnd: today,
  });
  const [problems, setProblems] = useState<Map<PeriodField, PeriodProblem>>(new Map());
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [preview, setPreview] = useState<PayrollInputPreview | null>(null);
  const reviewed = useRef<PeriodForm | null>(null);
  const generateKey = useRef(newKey());
  const [confirmed, setConfirmed] = useState(false);
  const [history, setHistory] = useState<PayrollInputRun[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [historyFailed, setHistoryFailed] = useState(false);
  const [detail, setDetail] = useState<PayrollInputRunDetail | null>(null);
  // MVP-091: the run under variance review (component state only; never stored or in a URL).
  const [varianceRun, setVarianceRun] = useState<string | null>(null);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);

  const legalEntities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );

  const run = async (work: () => Promise<void>) => {
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

  const fetchHistory = useCallback(
    async (cursor: string | null) => {
      try {
        const { data } = await core.GET('/payroll-inputs', {
          params: { query: cursor ? { cursor, limit: 20 } : { limit: 20 } },
          cache: 'no-store',
        });
        return data ?? null;
      } catch {
        return null;
      }
    },
    [core],
  );

  const applyHistory = useCallback(
    (data: { items: PayrollInputRun[]; nextCursor?: string | null } | null, append: boolean) => {
      if (!data) {
        setHistoryFailed(true);
        return;
      }
      setHistoryFailed(false);
      setHistory((previous) => (append ? [...previous, ...data.items] : data.items));
      setNextCursor(data.nextCursor ?? null);
    },
    [],
  );

  const loadHistory = async (cursor: string | null) => {
    applyHistory(await fetchHistory(cursor), cursor !== null);
  };

  useEffect(() => {
    let active = true;
    void fetchHistory(null).then((data) => {
      if (active) applyHistory(data, false);
    });
    return () => {
      active = false;
    };
  }, [fetchHistory, applyHistory]);

  const loadDetail = async (runId: string) => {
    const { data, error, response } = await core.GET('/payroll-inputs/{runId}', {
      params: { path: { runId } },
      cache: 'no-store',
    });
    if (!data) {
      setFailure(payrollFailureOf(response, error));
      return;
    }
    setDetail(data);
    requestAnimationFrame(() => detailHeading.current?.focus());
  };

  const open = (runId: string) => {
    void run(() => loadDetail(runId));
  };

  const onPreview = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = periodProblemsOf(form);
    setProblems(found);
    if (found.size > 0) return;
    void run(async () => {
      const body = {
        legalEntityId: form.legalEntityId,
        periodStart: form.periodStart,
        periodEnd: form.periodEnd,
      };
      const { data, error, response } = await core.POST('/payroll-inputs/preview', { body });
      if (!data) {
        setPreview(null);
        setFailure(payrollFailureOf(response, error));
        return;
      }
      reviewed.current = body;
      generateKey.current = newKey();
      setConfirmed(false);
      setPreview(data);
      requestAnimationFrame(() => previewHeading.current?.focus());
    });
  };

  const onGenerate = () => {
    const body = reviewed.current;
    if (!body || !preview || !preview.ready || !confirmed) return;
    void run(async () => {
      const { data, error, response } = await core.POST('/payroll-inputs', {
        params: { header: { 'Idempotency-Key': generateKey.current } },
        body: {
          ...body,
          previewDigest: preview.previewDigest,
          sourceVersionsDigest: preview.sourceVersionsDigest,
          confirm: true,
        },
      });
      if (!data) {
        const mapped = payrollFailureOf(response, error);
        if (mapped.kind === 'stale' || mapped.kind === 'notReady' || mapped.kind === 'exists') {
          setPreview(null);
        }
        setFailure(mapped);
        return;
      }
      setPreview(null);
      setAnnouncement(
        data.outcome === 'GENERATED'
          ? t('payrollPreparation.generated')
          : t('payrollPreparation.existing'),
      );
      await loadHistory(null);
      await loadDetail(data.run.runId);
    });
  };

  const problemText = (field: PeriodField) => {
    const problem = problems.get(field);
    if (problem) return t(`payrollPreparation.form.problem.${field}.${problem}`);
    if (failure?.kind === 'validation' && failure.fields?.includes(field)) {
      return t(`payrollPreparation.form.problem.${field}.server`);
    }
    return null;
  };

  const describedBy = (field: PeriodField, hint?: string) =>
    [hint, problemText(field) ? `${ids}-${field}-error` : null].filter(Boolean).join(' ') ||
    undefined;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="payroll-prep">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('payrollPreparation.title')}
        description={t('payrollPreparation.description')}
      />
      <p className="card" data-testid="payroll-prep-notice">
        <strong>{t('payrollPreparation.notice')}</strong>
      </p>
      <p role="status" className="visually-hidden" data-testid="payroll-prep-announcer">
        {announcement}
      </p>

      <form
        noValidate
        className="card"
        aria-label={t('payrollPreparation.form.label')}
        onSubmit={onPreview}
        data-testid="payroll-prep-form"
      >
        <fieldset className="field">
          <legend>{t('payrollPreparation.form.label')}</legend>
          {legalEntities.status === 'failed' && (
            <p className="field__error" role="alert">
              {t('payrollPreparation.form.entitiesUnavailable')}
            </p>
          )}
          <label htmlFor={`${ids}-legalEntityId`}>{t('timesheets.form.legalEntity')}</label>
          <select
            id={`${ids}-legalEntityId`}
            value={form.legalEntityId}
            aria-invalid={problemText('legalEntityId') ? true : undefined}
            aria-describedby={describedBy('legalEntityId')}
            data-testid="payroll-prep-legal-entity"
            onChange={(event) => {
              setForm({ ...form, legalEntityId: event.target.value });
            }}
          >
            <option value="">{t('timesheets.form.chooseLegalEntity')}</option>
            {legalEntities.items.map((option) => (
              <option key={option.id} value={option.id}>
                {t('workSchedules.siteName', { name: option.name, code: option.code })}
              </option>
            ))}
          </select>
          {problemText('legalEntityId') && (
            <p id={`${ids}-legalEntityId-error`} className="field__error">
              {problemText('legalEntityId')}
            </p>
          )}
          <label htmlFor={`${ids}-periodStart`}>{t('payrollPreparation.form.periodStart')}</label>
          <input
            id={`${ids}-periodStart`}
            type="date"
            value={form.periodStart}
            aria-invalid={problemText('periodStart') ? true : undefined}
            aria-describedby={describedBy('periodStart', `${ids}-period-hint`)}
            data-testid="payroll-prep-period-start"
            onChange={(event) => {
              setForm({ ...form, periodStart: event.target.value });
            }}
          />
          {problemText('periodStart') && (
            <p id={`${ids}-periodStart-error`} className="field__error">
              {problemText('periodStart')}
            </p>
          )}
          <label htmlFor={`${ids}-periodEnd`}>{t('payrollPreparation.form.periodEnd')}</label>
          <input
            id={`${ids}-periodEnd`}
            type="date"
            value={form.periodEnd}
            aria-invalid={problemText('periodEnd') ? true : undefined}
            aria-describedby={describedBy('periodEnd', `${ids}-period-hint`)}
            data-testid="payroll-prep-period-end"
            onChange={(event) => {
              setForm({ ...form, periodEnd: event.target.value });
            }}
          />
          {problemText('periodEnd') && (
            <p id={`${ids}-periodEnd-error`} className="field__error">
              {problemText('periodEnd')}
            </p>
          )}
          <p id={`${ids}-period-hint`} className="muted">
            {t('payrollPreparation.form.periodHint')}
          </p>
        </fieldset>
        <p>
          <button
            type="submit"
            className="button"
            disabled={busy}
            aria-busy={busy ? true : undefined}
            data-testid="payroll-prep-preview-button"
          >
            {busy ? t('timesheets.form.previewing') : t('timesheets.form.preview')}
          </button>
        </p>
      </form>

      {failure && <Failure failure={failure} testId="payroll-prep-error" />}

      {preview && (
        <section
          className="card"
          aria-labelledby={`${ids}-preview-title`}
          data-testid="payroll-prep-preview"
          data-ready={preview.ready ? 'true' : 'false'}
        >
          <h2 id={`${ids}-preview-title`} ref={previewHeading} tabIndex={-1}>
            {t('payrollPreparation.preview.title')}
          </h2>
          <ScopeText scope={preview.scope} />
          <h3>{t('payrollPreparation.preview.weeks')}</h3>
          {preview.weeks.length === 0 ? (
            <p className="muted" data-testid="payroll-prep-weeks-none">
              {t('payrollPreparation.weeks.none')}
            </p>
          ) : (
            <WeeksTable weeks={preview.weeks} testId="payroll-prep-week" />
          )}
          <h3>{t('payrollPreparation.preview.eligible')}</h3>
          <Counts counts={preview.eligible} testId="payroll-prep-eligible" />
          <h3>{t('payrollPreparation.preview.excluded')}</h3>
          <CodeList
            pairs={preview.excluded}
            prefix="payrollPreparation.excluded"
            testId="payroll-prep-excluded"
            onlyPositive
          />
          {preview.ready ? (
            <>
              <p data-testid="payroll-prep-ready">{t('payrollPreparation.preview.ready')}</p>
              {preview.existingRunId && (
                <p
                  data-testid="payroll-prep-existing"
                  data-unchanged={String(preview.existingRunUnchanged)}
                >
                  {preview.existingRunUnchanged
                    ? t('payrollPreparation.preview.existingUnchanged')
                    : t('payrollPreparation.preview.existingChanged')}
                </p>
              )}
              <p className="muted" data-testid="payroll-prep-expiry">
                {t('payrollPreparation.preview.expires', {
                  time: formatInstant(language, preview.expiresAt, preview.scope.businessTimezone),
                })}
              </p>
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={confirmed}
                    data-testid="payroll-prep-confirm"
                    onChange={(event) => {
                      setConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('payrollPreparation.preview.confirmCheck')}
                </label>
              </div>
              <p>
                <button
                  type="button"
                  className="button"
                  disabled={busy || !confirmed}
                  aria-busy={busy ? true : undefined}
                  data-testid="payroll-prep-generate"
                  onClick={onGenerate}
                >
                  {busy
                    ? t('payrollPreparation.preview.generating')
                    : t('payrollPreparation.preview.generate')}
                </button>
              </p>
            </>
          ) : (
            <div className="error-summary" role="status" data-testid="payroll-prep-blocked">
              <h3>{t('payrollPreparation.preview.blocked')}</h3>
              <CodeList
                pairs={preview.blockers}
                prefix="payrollPreparation.blockers"
                testId="payroll-prep-blocker"
                onlyPositive={false}
              />
              <p className="muted">{t('payrollPreparation.preview.blockedHint')}</p>
            </div>
          )}
        </section>
      )}

      {detail && (
        <section
          className="card"
          aria-labelledby={`${ids}-detail-title`}
          data-testid="payroll-prep-detail"
        >
          <h2 id={`${ids}-detail-title`} ref={detailHeading} tabIndex={-1}>
            {t('payrollPreparation.detail.title')}
          </h2>
          <p>
            <StatusBadge tone="success">{t('payrollPreparation.status.GENERATED')}</StatusBadge>
          </p>
          <ScopeText scope={detail.run.scope} />
          <dl className="decision-summary">
            <dt>{t('payrollPreparation.detail.id')}</dt>
            <dd data-testid="payroll-prep-detail-id">
              <code>{detail.run.runId}</code>
            </dd>
            <dt>{t('payrollPreparation.detail.version')}</dt>
            <dd data-testid="payroll-prep-detail-version">{detail.run.version}</dd>
            <dt>{t('payrollPreparation.detail.generated')}</dt>
            <dd>
              {formatInstant(language, detail.run.generatedAt, detail.run.scope.businessTimezone)}
            </dd>
            <dt>{t('payrollPreparation.detail.weeks')}</dt>
            <dd data-testid="payroll-prep-detail-weeks">{detail.run.weekCount}</dd>
            <dt>{t('payrollPreparation.detail.digest')}</dt>
            <dd data-testid="payroll-prep-detail-digest" className="break-anywhere">
              <code>{detail.run.evidenceDigest}</code>
            </dd>
          </dl>
          <Counts counts={detail.run.counts} testId="payroll-prep-detail-counts" />
          <h3>{t('payrollPreparation.preview.excluded')}</h3>
          <CodeList
            pairs={detail.run.excluded}
            prefix="payrollPreparation.excluded"
            testId="payroll-prep-detail-excluded"
            onlyPositive
          />
          <h3>{t('payrollPreparation.detail.sourceWeeks')}</h3>
          <WeeksTable weeks={sourceWeekRows(detail)} testId="payroll-prep-detail-week" />
          <h3>{t('payrollPreparation.detail.employees')}</h3>
          <ScrollRegion label={t('payrollPreparation.employees.table')}>
            <table className="timesheet-table">
              <caption className="visually-hidden">
                {t('payrollPreparation.employees.table')}
              </caption>
              <thead>
                <tr>
                  <th scope="col">{t('payrollPreparation.employees.employee')}</th>
                  <th scope="col">{t('payrollPreparation.employees.employment')}</th>
                  <th scope="col">{t('payrollPreparation.employees.dates')}</th>
                  <th scope="col">{t('payrollPreparation.counts.timesheets')}</th>
                  <th scope="col">{t('payrollPreparation.counts.rows')}</th>
                  <th scope="col">{t('payrollPreparation.counts.worked')}</th>
                </tr>
              </thead>
              <tbody>
                {detail.employees.map((employee) => (
                  <tr
                    key={`${employee.employeeId}-${employee.employmentId}`}
                    data-testid="payroll-prep-employee"
                  >
                    <td>
                      <code>{employee.employeeId}</code>
                    </td>
                    <td>
                      <code>{employee.employmentId}</code>
                    </td>
                    <td>
                      {t('timesheets.period', {
                        from: formatDate(i18n.language, employee.firstWorkDate),
                        to: formatDate(i18n.language, employee.lastWorkDate),
                      })}
                    </td>
                    <td>{employee.counts.timesheets}</td>
                    <td>{employee.counts.rows}</td>
                    <td data-testid="payroll-prep-employee-worked">
                      {t('timesheets.duration', durationParts(employee.counts.workedMinutes))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
          <p className="muted">{t('payrollPreparation.detail.immutable')}</p>
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="payroll-prep-review-variance"
              onClick={() => {
                setVarianceRun(detail.run.runId);
              }}
            >
              {t('payrollPreparation.variance.open')}
            </button>
          </p>
        </section>
      )}

      {varianceRun && (
        <PayrollVarianceReview
          key={varianceRun}
          runId={varianceRun}
          onOpenRun={open}
          onClose={() => {
            setVarianceRun(null);
          }}
        />
      )}

      <section
        className="card"
        aria-labelledby={`${ids}-history-title`}
        data-testid="payroll-prep-history"
      >
        <h2 id={`${ids}-history-title`}>{t('payrollPreparation.history.title')}</h2>
        {historyFailed && (
          <p className="field__error" role="alert">
            {t('payrollPreparation.history.unavailable')}
          </p>
        )}
        {history.length === 0 && !historyFailed ? (
          <p className="muted" data-testid="payroll-prep-history-empty">
            {t('payrollPreparation.history.empty')}
          </p>
        ) : (
          <ScrollRegion label={t('payrollPreparation.history.table')}>
            <table className="timesheet-table">
              <caption className="visually-hidden">{t('payrollPreparation.history.table')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('payrollPreparation.history.period')}</th>
                  <th scope="col">{t('payrollPreparation.history.legalEntity')}</th>
                  <th scope="col">{t('payrollPreparation.counts.employees')}</th>
                  <th scope="col">{t('payrollPreparation.counts.worked')}</th>
                  <th scope="col">{t('payrollPreparation.history.generated')}</th>
                  <th scope="col">{t('payrollPreparation.history.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {history.map((item) => (
                  <tr
                    key={item.runId}
                    data-testid="payroll-prep-item"
                    data-period-start={item.scope.periodStart}
                  >
                    <td>
                      {t('timesheets.period', {
                        from: formatDate(i18n.language, item.scope.periodStart),
                        to: formatDate(i18n.language, item.scope.periodEnd),
                      })}
                    </td>
                    <td>
                      {item.scope.legalEntityName && item.scope.legalEntityCode
                        ? t('workSchedules.siteName', {
                            name: item.scope.legalEntityName,
                            code: item.scope.legalEntityCode,
                          })
                        : t('payrollPreparation.scope.unknownEntity')}
                    </td>
                    <td data-testid="payroll-prep-item-employees">{item.counts.employees}</td>
                    <td>{t('timesheets.duration', durationParts(item.counts.workedMinutes))}</td>
                    <td>
                      {formatInstant(language, item.generatedAt, item.scope.businessTimezone)}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('payrollPreparation.history.openFor', {
                          from: formatDate(i18n.language, item.scope.periodStart),
                          to: formatDate(i18n.language, item.scope.periodEnd),
                        })}
                        data-testid="payroll-prep-item-open"
                        onClick={() => {
                          open(item.runId);
                        }}
                      >
                        {t('payrollPreparation.history.open')}
                      </button>{' '}
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('payrollPreparation.variance.openFor', {
                          from: formatDate(i18n.language, item.scope.periodStart),
                          to: formatDate(i18n.language, item.scope.periodEnd),
                        })}
                        data-testid="payroll-prep-item-variance"
                        onClick={() => {
                          setVarianceRun(item.runId);
                        }}
                      >
                        {t('payrollPreparation.variance.open')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="payroll-prep-more"
              onClick={() => {
                void run(() => loadHistory(nextCursor));
              }}
            >
              {t('payrollPreparation.history.more')}
            </button>
          </p>
        )}
      </section>
    </section>
  );
}

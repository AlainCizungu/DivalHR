import type {
  PayrollInputRun,
  PayrollVarianceCandidates,
  PayrollVarianceLine,
  PayrollVarianceReview as Review,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import type { StatusTone } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from '../schedules/attendance';
import { durationParts } from '../schedules/timesheets';
import {
  DEFAULT_ABSOLUTE,
  DEFAULT_PERCENTAGE,
  VARIANCE_NETWORK_FAILURE,
  VIEWS,
  formatBasisPoints,
  signOf,
  thresholdProblemsOf,
  varianceFailureOf,
  type ThresholdField,
  type VarianceFailure,
  type VarianceView,
} from './payrollVariance';

const PAGE_SIZE = 25;

const TONES: Record<PayrollVarianceLine['classification'], StatusTone> = {
  NEW: 'info',
  MISSING: 'warning',
  CHANGED: 'warning',
  UNCHANGED: 'neutral',
};

/**
 * MVP-091: « Examiner les écarts » / "Review variance" for one generated payroll-input run. The
 * administrator compares it with an eligible earlier run of the same legal entity (the newest by
 * default), sets two review thresholds and a filter, and reads the server's employee-by-employee
 * worked-time differences. The thresholds only filter this review: nothing is saved. Worked-time
 * inputs only: no pay, tax, deduction or payment difference is shown or implied. Nothing (run,
 * employee, cursor or threshold) is kept in browser storage or put in a URL.
 */
export function PayrollVarianceReview({
  runId,
  onOpenRun,
  onClose,
}: {
  runId: string;
  onOpenRun: (runId: string) => void;
  onClose: () => void;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const latest = useRef(0);
  const [candidates, setCandidates] = useState<PayrollVarianceCandidates | null>(null);
  const [comparisonRunId, setComparisonRunId] = useState('');
  const [form, setForm] = useState({ absolute: DEFAULT_ABSOLUTE, percentage: DEFAULT_PERCENTAGE });
  const [applied, setApplied] = useState({
    absolute: DEFAULT_ABSOLUTE,
    percentage: DEFAULT_PERCENTAGE,
  });
  const [view, setView] = useState<VarianceView>('ALL');
  const [problems, setProblems] = useState<Set<ThresholdField>>(new Set());
  const [review, setReview] = useState<Review | null>(null);
  const [items, setItems] = useState<PayrollVarianceLine[]>([]);
  const [busy, setBusy] = useState(true);
  const [failure, setFailure] = useState<VarianceFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const load = async (
    comparison: string,
    thresholds: { absolute: string; percentage: string },
    filter: VarianceView,
    cursor: string | null,
  ) => {
    const ticket = ++latest.current;
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.GET('/payroll-inputs/{runId}/variance', {
        params: {
          path: { runId },
          query: {
            comparisonRunId: comparison,
            absoluteMinuteThreshold: Number(thresholds.absolute),
            percentageThreshold: Number(thresholds.percentage),
            view: filter,
            limit: PAGE_SIZE,
            ...(cursor ? { cursor } : {}),
          },
        },
        cache: 'no-store',
      });
      if (ticket !== latest.current) return;
      if (!data) {
        setFailure(varianceFailureOf(response, error));
        return;
      }
      setReview(data);
      setItems((previous) => (cursor ? [...previous, ...data.items] : data.items));
      setAnnouncement(t('payrollPreparation.variance.matching', { count: data.matchingCount }));
    } catch {
      if (ticket === latest.current) setFailure(VARIANCE_NETWORK_FAILURE);
    } finally {
      if (ticket === latest.current) setBusy(false);
    }
  };

  useEffect(() => {
    let active = true;
    const first = async () => {
      try {
        const { data, error, response } = await core.GET(
          '/payroll-inputs/{runId}/variance-candidates',
          { params: { path: { runId } }, cache: 'no-store' },
        );
        if (!active) return;
        if (!data) {
          setFailure(varianceFailureOf(response, error));
          setBusy(false);
          return;
        }
        setCandidates(data);
        if (data.defaultRunId) {
          setComparisonRunId(data.defaultRunId);
          await load(
            data.defaultRunId,
            { absolute: DEFAULT_ABSOLUTE, percentage: DEFAULT_PERCENTAGE },
            'ALL',
            null,
          );
        } else {
          setBusy(false);
        }
      } catch {
        if (active) {
          setFailure(VARIANCE_NETWORK_FAILURE);
          setBusy(false);
        }
      }
    };
    void first();
    requestAnimationFrame(() => heading.current?.focus());
    return () => {
      active = false;
      latest.current += 1;
    };
    // The review is loaded once per run: `load` reads only stable values.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [core, runId]);

  const onApply = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = thresholdProblemsOf(form);
    setProblems(found);
    if (found.size > 0 || !comparisonRunId) return;
    setApplied(form);
    void load(comparisonRunId, form, view, null);
  };

  const onView = (next: VarianceView) => {
    setView(next);
    if (comparisonRunId && problems.size === 0) void load(comparisonRunId, applied, next, null);
  };

  const onComparison = (next: string) => {
    setComparisonRunId(next);
    if (next && problems.size === 0) void load(next, applied, view, null);
  };

  const problemText = (field: ThresholdField) =>
    problems.has(field) || failure?.fields?.includes(field)
      ? t(`payrollPreparation.variance.thresholds.problem.${field}`)
      : null;

  const periodText = (run: PayrollInputRun) =>
    t('timesheets.period', {
      from: formatDate(i18n.language, run.scope.periodStart),
      to: formatDate(i18n.language, run.scope.periodEnd),
    });

  const minutesText = (minutes: number) => t('timesheets.duration', durationParts(minutes));
  const signedMinutes = (delta: number) => `${signOf(delta)}${minutesText(Math.abs(delta))}`;

  const emptyBaseline = candidates !== null && candidates.candidates.length === 0;

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="payroll-variance"
      aria-busy={busy ? true : undefined}
    >
      <h2 id={`${ids}-title`} ref={heading} tabIndex={-1}>
        {t('payrollPreparation.variance.title')}
      </h2>
      <p data-testid="payroll-variance-notice">
        <strong>{t('payrollPreparation.variance.notice')}</strong>
      </p>
      <p role="status" className="visually-hidden" data-testid="payroll-variance-announcer">
        {announcement}
      </p>
      {failure && (
        <div className="error-summary" role="alert" data-testid="payroll-variance-error">
          <p>{t(failure.messageKey)}</p>
          {failure.correlationId && (
            <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}
      {busy && !review && !failure && (
        <p className="muted" data-testid="payroll-variance-loading">
          {t('payrollPreparation.variance.loading')}
        </p>
      )}
      {emptyBaseline && (
        <p data-testid="payroll-variance-empty">{t('payrollPreparation.variance.empty')}</p>
      )}

      {candidates && !emptyBaseline && (
        <>
          <p className="muted">
            <strong>{t('payrollPreparation.variance.periods.current')}</strong>{' '}
            <span data-testid="payroll-variance-current-period">
              {periodText(candidates.current)}
            </span>
          </p>
          <form
            noValidate
            aria-label={t('payrollPreparation.variance.thresholds.legend')}
            onSubmit={onApply}
            data-testid="payroll-variance-form"
          >
            <div className="field">
              <label htmlFor={`${ids}-comparison`}>
                {t('payrollPreparation.variance.comparison.label')}
              </label>
              <select
                id={`${ids}-comparison`}
                value={comparisonRunId}
                aria-describedby={`${ids}-comparison-hint`}
                disabled={busy}
                data-testid="payroll-variance-comparison"
                onChange={(event) => {
                  onComparison(event.target.value);
                }}
              >
                {candidates.candidates.map((option) => (
                  <option key={option.runId} value={option.runId}>
                    {t('payrollPreparation.variance.comparison.option', {
                      from: formatDate(i18n.language, option.scope.periodStart),
                      to: formatDate(i18n.language, option.scope.periodEnd),
                      generated: formatInstant(
                        language,
                        option.generatedAt,
                        option.scope.businessTimezone,
                      ),
                    })}
                  </option>
                ))}
              </select>
              <p id={`${ids}-comparison-hint`} className="muted">
                {t('payrollPreparation.variance.comparison.hint')}
              </p>
            </div>
            <fieldset className="field">
              <legend>{t('payrollPreparation.variance.thresholds.legend')}</legend>
              <p className="muted">{t('payrollPreparation.variance.thresholds.hint')}</p>
              <label htmlFor={`${ids}-absolute`}>
                {t('payrollPreparation.variance.thresholds.absolute')}
              </label>
              <input
                id={`${ids}-absolute`}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                value={form.absolute}
                aria-invalid={problemText('absoluteMinuteThreshold') ? true : undefined}
                aria-describedby={[
                  `${ids}-absolute-hint`,
                  problemText('absoluteMinuteThreshold') ? `${ids}-absolute-error` : null,
                ]
                  .filter(Boolean)
                  .join(' ')}
                data-testid="payroll-variance-absolute"
                onChange={(event) => {
                  setForm({ ...form, absolute: event.target.value.trim() });
                }}
              />
              <p id={`${ids}-absolute-hint`} className="muted">
                {t('payrollPreparation.variance.thresholds.absoluteHint')}
              </p>
              {problemText('absoluteMinuteThreshold') && (
                <p id={`${ids}-absolute-error`} className="field__error">
                  {problemText('absoluteMinuteThreshold')}
                </p>
              )}
              <label htmlFor={`${ids}-percentage`}>
                {t('payrollPreparation.variance.thresholds.percentage')}
              </label>
              <input
                id={`${ids}-percentage`}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                value={form.percentage}
                aria-invalid={problemText('percentageThreshold') ? true : undefined}
                aria-describedby={[
                  `${ids}-percentage-hint`,
                  problemText('percentageThreshold') ? `${ids}-percentage-error` : null,
                ]
                  .filter(Boolean)
                  .join(' ')}
                data-testid="payroll-variance-percentage"
                onChange={(event) => {
                  setForm({ ...form, percentage: event.target.value.trim() });
                }}
              />
              <p id={`${ids}-percentage-hint`} className="muted">
                {t('payrollPreparation.variance.thresholds.percentageHint')}
              </p>
              {problemText('percentageThreshold') && (
                <p id={`${ids}-percentage-error`} className="field__error">
                  {problemText('percentageThreshold')}
                </p>
              )}
            </fieldset>
            <p>
              <button
                type="submit"
                className="button"
                disabled={busy}
                aria-busy={busy ? true : undefined}
                data-testid="payroll-variance-apply"
              >
                {busy
                  ? t('payrollPreparation.variance.thresholds.applying')
                  : t('payrollPreparation.variance.thresholds.apply')}
              </button>
            </p>
            <div className="field">
              <label htmlFor={`${ids}-view`}>{t('payrollPreparation.variance.view.label')}</label>
              <select
                id={`${ids}-view`}
                value={view}
                disabled={busy}
                data-testid="payroll-variance-view"
                onChange={(event) => {
                  onView(event.target.value as VarianceView);
                }}
              >
                {VIEWS.map((option) => (
                  <option key={option} value={option}>
                    {t(`payrollPreparation.variance.view.${option}`)}
                  </option>
                ))}
              </select>
            </div>
          </form>
        </>
      )}

      {review && !emptyBaseline && (
        <>
          <p className="muted">
            <strong>{t('payrollPreparation.variance.periods.comparison')}</strong>{' '}
            <span data-testid="payroll-variance-comparison-period">
              {periodText(review.comparison)}
            </span>
          </p>
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="payroll-variance-open-current"
              onClick={() => {
                onOpenRun(review.current.runId);
              }}
            >
              {t('payrollPreparation.variance.openRun.current')}
            </button>{' '}
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="payroll-variance-open-comparison"
              onClick={() => {
                onOpenRun(review.comparison.runId);
              }}
            >
              {t('payrollPreparation.variance.openRun.comparison')}
            </button>
          </p>
          <h3>{t('payrollPreparation.variance.totals.title')}</h3>
          <dl className="decision-summary" data-testid="payroll-variance-totals">
            <dt>{t('payrollPreparation.variance.totals.currentEmployees')}</dt>
            <dd data-testid="payroll-variance-total-current-employees">
              {review.totals.currentEmployees}
            </dd>
            <dt>{t('payrollPreparation.variance.totals.comparisonEmployees')}</dt>
            <dd data-testid="payroll-variance-total-comparison-employees">
              {review.totals.comparisonEmployees}
            </dd>
            <dt>{t('payrollPreparation.variance.totals.currentMinutes')}</dt>
            <dd data-testid="payroll-variance-total-current-minutes">
              {minutesText(review.totals.currentMinutes)}
            </dd>
            <dt>{t('payrollPreparation.variance.totals.comparisonMinutes')}</dt>
            <dd data-testid="payroll-variance-total-comparison-minutes">
              {minutesText(review.totals.comparisonMinutes)}
            </dd>
            <dt>{t('payrollPreparation.variance.totals.delta')}</dt>
            <dd data-testid="payroll-variance-total-delta">
              {signedMinutes(review.totals.minuteDelta)}
            </dd>
            <dt>{t('payrollPreparation.variance.totals.new')}</dt>
            <dd data-testid="payroll-variance-total-new">{review.totals.newCount}</dd>
            <dt>{t('payrollPreparation.variance.totals.missing')}</dt>
            <dd data-testid="payroll-variance-total-missing">{review.totals.missingCount}</dd>
            <dt>{t('payrollPreparation.variance.totals.changed')}</dt>
            <dd data-testid="payroll-variance-total-changed">{review.totals.changedCount}</dd>
            <dt>{t('payrollPreparation.variance.totals.unchanged')}</dt>
            <dd data-testid="payroll-variance-total-unchanged">{review.totals.unchangedCount}</dd>
            <dt>{t('payrollPreparation.variance.totals.material')}</dt>
            <dd data-testid="payroll-variance-total-material">{review.totals.materialCount}</dd>
          </dl>
          <p className="muted" data-testid="payroll-variance-matching">
            {t('payrollPreparation.variance.matching', { count: review.matchingCount })}
          </p>
          {items.length === 0 ? (
            <p data-testid="payroll-variance-none">{t('payrollPreparation.variance.none')}</p>
          ) : (
            <ScrollRegion label={t('payrollPreparation.variance.table.caption')}>
              <table className="timesheet-table">
                <caption className="visually-hidden">
                  {t('payrollPreparation.variance.table.caption')}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{t('payrollPreparation.variance.table.employee')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.classification')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.current')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.comparison')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.delta')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.percent')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.material')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.rows')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.timesheets')}</th>
                    <th scope="col">{t('payrollPreparation.variance.table.employments')}</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((line) => (
                    <tr
                      key={line.employeeId}
                      data-testid="payroll-variance-line"
                      data-classification={line.classification}
                      data-material={String(line.material)}
                    >
                      <td>
                        <code>{line.employeeId}</code>
                      </td>
                      <td>
                        <StatusBadge tone={TONES[line.classification]}>
                          {t(`payrollPreparation.variance.classification.${line.classification}`)}
                        </StatusBadge>
                      </td>
                      <td data-testid="payroll-variance-line-current">
                        {line.current ? minutesText(line.currentMinutes) : '—'}
                      </td>
                      <td data-testid="payroll-variance-line-comparison">
                        {line.comparison ? minutesText(line.comparisonMinutes) : '—'}
                      </td>
                      <td data-testid="payroll-variance-line-delta">
                        {signedMinutes(line.minuteDelta)}
                      </td>
                      <td data-testid="payroll-variance-line-percent">
                        {line.percentageDeltaBasisPoints === null
                          ? t('payrollPreparation.variance.table.noPercent')
                          : formatBasisPoints(language, line.percentageDeltaBasisPoints)}
                      </td>
                      <td>
                        {line.material
                          ? t('payrollPreparation.variance.table.materialYes')
                          : t('payrollPreparation.variance.table.materialNo')}
                      </td>
                      <td>
                        {line.current?.rows ?? '—'} / {line.comparison?.rows ?? '—'}
                      </td>
                      <td>
                        {line.current?.timesheets ?? '—'} / {line.comparison?.timesheets ?? '—'}
                      </td>
                      <td>
                        {(line.current?.employmentIds ?? []).map((id) => (
                          <code key={`c-${id}`} className="break-anywhere">
                            {id}
                          </code>
                        ))}{' '}
                        /{' '}
                        {(line.comparison?.employmentIds ?? []).map((id) => (
                          <code key={`p-${id}`} className="break-anywhere">
                            {id}
                          </code>
                        ))}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
          )}
          {review.nextCursor && (
            <p>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                data-testid="payroll-variance-more"
                onClick={() => {
                  void load(comparisonRunId, applied, view, review.nextCursor ?? null);
                }}
              >
                {t('payrollPreparation.variance.more')}
              </button>
            </p>
          )}
        </>
      )}
      <p>
        <button
          type="button"
          className="button button--secondary"
          data-testid="payroll-variance-close"
          onClick={onClose}
        >
          {t('payrollPreparation.variance.close')}
        </button>
      </p>
    </section>
  );
}

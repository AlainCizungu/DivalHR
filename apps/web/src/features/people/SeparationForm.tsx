import type {
  Separation,
  SeparationAccessTiming,
  SeparationAcknowledgement,
  SeparationCommand,
  SeparationPreview,
  SeparationReason,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { HistoryAlert } from './HistoryAlert';
import {
  formatDate,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  type HistoryFailure,
} from './history';
import { ManagerPicker, type ManagerChoice } from './ManagerPicker';
import { PreviewTable } from './PreviewTable';
import { ScrollRegion } from './ScrollRegion';
import { formatInstant, SEPARATION_REASONS, SEPARATION_STALE } from './separation';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;

type Problem = 'lastDay' | 'reason' | 'timing' | 'plan' | 'manager' | 'acknowledge';
type Plan = '' | 'REASSIGN' | 'CLEAR';

/**
 * MVP-022: records a separation. The command (last day, closed reason, when DivalHR access ends
 * and, when direct reports are affected, the plan for them) is previewed first: the employee's own
 * rows, future changes that must be cancelled first, every affected direct-report interval, the
 * access that will be revoked, the follow-up checklist and the acknowledgements required. The
 * commit repeats the preview's version and digest. Immediate access removal is offered only when
 * the last day is today or earlier (A22-1).
 */
export function SeparationForm({
  employeeId,
  businessDate,
  onRecorded,
}: {
  employeeId: string;
  businessDate: string;
  onRecorded: (separation: Separation) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const [lastDay, setLastDay] = useState('');
  const [reason, setReason] = useState<SeparationReason | ''>('');
  const [timing, setTiming] = useState<SeparationAccessTiming | ''>('');
  const [plan, setPlan] = useState<Plan>('');
  const [manager, setManager] = useState<ManagerChoice>({ mode: 'set', employee: null });
  const [problems, setProblems] = useState<ReadonlySet<Problem>>(new Set());
  const [preview, setPreview] = useState<SeparationPreview | null>(null);
  const [previewed, setPreviewed] = useState<SeparationCommand | null>(null);
  const [acknowledged, setAcknowledged] = useState<ReadonlySet<SeparationAcknowledgement>>(
    new Set(),
  );
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<HistoryFailure | null>(null);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const failureBox = useRef<HTMLDivElement>(null);
  const summaryBox = useRef<HTMLDivElement>(null);
  const focusNext = useRef<'preview' | 'failure' | 'summary' | null>(null);

  useEffect(() => {
    const element = {
      preview: previewHeading.current,
      failure: failureBox.current,
      summary: summaryBox.current,
    }[focusNext.current ?? 'preview'];
    if (focusNext.current && element) {
      focusNext.current = null;
      element.focus();
    }
  });

  const edited = () => {
    setPreview(null);
    setPreviewed(null);
    setAcknowledged(new Set());
    setFailure(null);
  };

  const validDate = ISO_DATE.test(lastDay);
  const future = validDate && lastDay > businessDate;
  const past = validDate && lastDay < businessDate;
  // A22-1: IMMEDIATELY only when the last day is today or earlier; a past last day removes access
  // at once.
  const timings: readonly SeparationAccessTiming[] = !validDate
    ? ['END_OF_LAST_DAY', 'IMMEDIATELY']
    : future
      ? ['END_OF_LAST_DAY']
      : past
        ? ['IMMEDIATELY']
        : ['END_OF_LAST_DAY', 'IMMEDIATELY'];
  const effectiveTiming: SeparationAccessTiming | '' =
    timings.length === 1 ? (timings[0] ?? '') : timing;

  const validate = (): ReadonlySet<Problem> => {
    const found = new Set<Problem>();
    if (!validDate) found.add('lastDay');
    if (!reason) found.add('reason');
    if (!effectiveTiming) found.add('timing');
    if (plan === 'REASSIGN' && (manager.mode !== 'set' || !manager.employee)) found.add('manager');
    return found;
  };

  const command = (): SeparationCommand => ({
    lastDay,
    reasonCode: reason as SeparationReason,
    accessTiming: effectiveTiming as SeparationAccessTiming,
    reportPlan:
      plan === ''
        ? null
        : plan === 'CLEAR'
          ? { action: 'CLEAR', managerEmployeeId: null }
          : {
              action: 'REASSIGN',
              managerEmployeeId: manager.mode === 'set' ? (manager.employee?.id ?? null) : null,
            },
  });

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const found = validate();
    setProblems(found);
    if (found.size > 0) {
      focusNext.current = 'summary';
      return;
    }
    const body = command();
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/separations/preview',
        { params: { path: { employeeId } }, body, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        setPreviewed(body);
        setAcknowledged(new Set());
        focusNext.current = 'preview';
      } else {
        setFailure(historyFailureOf(response, error));
        focusNext.current = 'failure';
      }
    } catch {
      setFailure(HISTORY_NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(null);
    }
  };

  const blocked =
    preview !== null &&
    (preview.blockers.length > 0 ||
      preview.access.status === 'SELF' ||
      preview.access.status === 'PROTECTED_ADMIN' ||
      (preview.reportPlanRequired && previewed?.reportPlan == null));

  const onCommit = async () => {
    if (!preview || !previewed || busy || blocked) return;
    const missing = preview.requiredAcknowledgements.filter((ack) => !acknowledged.has(ack));
    if (missing.length > 0) {
      setProblems(new Set<Problem>(['acknowledge']));
      focusNext.current = 'summary';
      return;
    }
    setProblems(new Set());
    const body = {
      ...previewed,
      expectedVersion: preview.expectedVersion,
      previewDigest: preview.previewDigest,
      acknowledgements: [...preview.requiredAcknowledgements],
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/employees/{employeeId}/separations', {
        params: {
          path: { employeeId },
          header: { 'Idempotency-Key': commitKey.keyFor({ employeeId, ...body }) },
        },
        body,
        cache: 'no-store',
      });
      if (data) {
        commitKey.reset();
        onRecorded(data.separation);
        return;
      }
      const next = historyFailureOf(response, error);
      if (next.code && SEPARATION_STALE.has(next.code)) {
        commitKey.reset();
        setPreview(null);
        setPreviewed(null);
      }
      setFailure(next);
      focusNext.current = 'failure';
    } catch {
      setFailure(HISTORY_NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(null);
    }
  };

  const titleId = `${ids}-title`;
  const managerName = (employee: {
    givenNames: string;
    familyName: string;
    employeeNumber: string;
  }) =>
    t('employees.managerName', {
      given: employee.givenNames,
      family: employee.familyName,
      number: employee.employeeNumber,
    });

  return (
    <section aria-labelledby={titleId} data-testid="separation-form">
      <h3 id={titleId}>{t('separation.form.title')}</h3>
      <p className="muted">
        {t('separation.form.help', { date: formatDate(i18n.language, businessDate) })}
      </p>
      {problems.size > 0 && (
        <div
          className="error-summary"
          role="alert"
          tabIndex={-1}
          ref={summaryBox}
          data-testid="separation-problems"
        >
          <p>{t('employees.form.problems.title')}</p>
          <ul>
            {[...problems].map((problem) => (
              <li key={problem}>{t(`separation.form.problems.${problem}`)}</li>
            ))}
          </ul>
        </div>
      )}
      <form noValidate onSubmit={(event) => void onPreview(event)}>
        <div className="field">
          <label htmlFor={`${ids}-last`}>{t('separation.form.lastDay')}</label>
          <p id={`${ids}-last-help`} className="field__help">
            {t('separation.form.lastDayHelp')}
          </p>
          <input
            id={`${ids}-last`}
            type="date"
            value={lastDay}
            aria-invalid={problems.has('lastDay') ? true : undefined}
            aria-describedby={`${ids}-last-help`}
            onChange={(event) => {
              setLastDay(event.target.value);
              edited();
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-reason`}>{t('separation.form.reason')}</label>
          <select
            id={`${ids}-reason`}
            value={reason}
            aria-invalid={problems.has('reason') ? true : undefined}
            onChange={(event) => {
              setReason(event.target.value as SeparationReason | '');
              edited();
            }}
          >
            <option value="">{t('employees.placement.choose')}</option>
            {SEPARATION_REASONS.map((code) => (
              <option key={code} value={code}>
                {t(`separation.reasons.${code}`)}
              </option>
            ))}
          </select>
        </div>
        <fieldset className="field">
          <legend>{t('separation.form.accessTiming')}</legend>
          <p className="field__help">
            {future
              ? t('separation.form.timingFuture')
              : past
                ? t('separation.form.timingPast')
                : t('separation.form.timingHelp')}
          </p>
          {timings.map((code) => (
            <div className="choice" key={code}>
              <input
                id={`${ids}-timing-${code}`}
                type="radio"
                name={`${ids}-timing`}
                checked={effectiveTiming === code}
                onChange={() => {
                  setTiming(code);
                  edited();
                }}
              />
              <label htmlFor={`${ids}-timing-${code}`}>{t(`separation.timing.${code}`)}</label>
            </div>
          ))}
        </fieldset>
        <fieldset className="field" data-testid="report-plan">
          <legend>{t('separation.form.reportPlan')}</legend>
          <p className="field__help">{t('separation.form.reportPlanHelp')}</p>
          {(['', 'REASSIGN', 'CLEAR'] as const).map((code) => (
            <div className="choice" key={code || 'NONE'}>
              <input
                id={`${ids}-plan-${code || 'NONE'}`}
                type="radio"
                name={`${ids}-plan`}
                checked={plan === code}
                onChange={() => {
                  setPlan(code);
                  edited();
                }}
              />
              <label htmlFor={`${ids}-plan-${code || 'NONE'}`}>
                {t(`separation.plan.${code || 'NONE'}`)}
              </label>
            </div>
          ))}
          {plan === 'REASSIGN' && (
            <ManagerPicker
              idPrefix={`${ids}-manager`}
              employeeId={employeeId}
              value={manager}
              allowClear={false}
              invalid={problems.has('manager')}
              onChange={(value) => {
                setManager(value);
                edited();
              }}
            />
          )}
        </fieldset>
        <div className="actions">
          <button type="submit" className="button" disabled={busy !== null}>
            {busy === 'preview' ? t('employees.form.previewing') : t('separation.form.preview')}
          </button>
        </div>
      </form>

      {failure && (
        <HistoryAlert failure={failure} ref={failureBox} data-testid="separation-error" />
      )}

      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="separation-preview">
          <h4 id={`${ids}-preview`} tabIndex={-1} ref={previewHeading}>
            {t('employees.preview.title')}
          </h4>
          <p data-testid="separation-timing">{t(`employees.timing.${preview.timing}`)}</p>
          <p data-testid="separation-access" data-status={preview.access.status}>
            {preview.access.accessEndsAt
              ? t(`separation.access.preview.${preview.access.status}`, {
                  at:
                    preview.accessTiming === 'IMMEDIATELY'
                      ? t('separation.access.atCommit')
                      : t('separation.access.on', {
                          date: formatInstant(i18n.language, preview.access.accessEndsAt),
                        }),
                })
              : t(`separation.access.preview.${preview.access.status}`, { at: '' })}
          </p>
          {preview.kinds.length > 0 && (
            <PreviewTable
              kinds={preview.kinds}
              beforeLabel={t('employees.preview.before')}
              afterLabel={t('employees.preview.after')}
              data-testid="separation-own"
            />
          )}
          {preview.blockers.length > 0 && (
            <div className="notice" data-testid="separation-blockers">
              <p>{t('separation.blockers.title')}</p>
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={`${blocker.kind}-${blocker.effectiveFrom}`}>
                    {t(`separation.blockers.${blocker.resolution}`, {
                      kind: t(`employees.kinds.${blocker.kind}`),
                      date: formatDate(i18n.language, blocker.effectiveFrom),
                    })}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {preview.reports.length > 0 && (
            <>
              <p data-testid="separation-report-count">
                {t('separation.reports.count', {
                  count: preview.reports.length,
                  intervals: preview.reports.reduce((sum, r) => sum + r.intervals.length, 0),
                })}
              </p>
              <ScrollRegion label={t('separation.reports.caption')}>
                <table data-testid="separation-reports">
                  <caption>{t('separation.reports.caption')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('separation.reports.employee')}</th>
                      <th scope="col">{t('separation.reports.periods')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.reports.map((report) => (
                      <tr key={report.employee.id}>
                        <th scope="row">{managerName(report.employee)}</th>
                        <td className="preformatted">
                          {report.intervals
                            .map((interval) =>
                              interval.effectiveTo
                                ? t('employees.period.closed', {
                                    from: formatDate(i18n.language, interval.effectiveFrom),
                                    to: formatDate(i18n.language, interval.effectiveTo),
                                  })
                                : t('employees.period.open', {
                                    from: formatDate(i18n.language, interval.effectiveFrom),
                                  }),
                            )
                            .join('\n')}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
              {preview.reportPlanRequired && previewed?.reportPlan == null && (
                <p className="notice" data-testid="separation-plan-required">
                  {t('separation.reports.planRequired')}
                </p>
              )}
              {previewed?.reportPlan && (
                <p>
                  {previewed.reportPlan.action === 'CLEAR'
                    ? t('separation.reports.willClear')
                    : manager.mode === 'set' && manager.employee
                      ? t('separation.reports.willReassign', {
                          name: managerName(manager.employee),
                        })
                      : null}
                </p>
              )}
            </>
          )}
          <p>{t('separation.checklist.title')}</p>
          <ul data-testid="separation-checklist">
            {preview.checklist.map((item) => (
              <li key={item.code}>
                {t('separation.checklist.item', {
                  task: t(`separation.tasks.${item.code}`),
                  date: formatDate(i18n.language, item.dueDate),
                })}
              </li>
            ))}
          </ul>
          {preview.requiredAcknowledgements.length > 0 && (
            <fieldset className="field" data-testid="separation-acknowledgements">
              <legend>{t('separation.acknowledgements.title')}</legend>
              {preview.requiredAcknowledgements.map((ack) => (
                <div className="choice" key={ack}>
                  <input
                    id={`${ids}-ack-${ack}`}
                    type="checkbox"
                    checked={acknowledged.has(ack)}
                    aria-invalid={
                      problems.has('acknowledge') && !acknowledged.has(ack) ? true : undefined
                    }
                    onChange={(event) => {
                      const next = new Set(acknowledged);
                      if (event.target.checked) next.add(ack);
                      else next.delete(ack);
                      setAcknowledged(next);
                    }}
                  />
                  <label htmlFor={`${ids}-ack-${ack}`}>
                    {t(`separation.acknowledgements.${ack}`)}
                  </label>
                </div>
              ))}
            </fieldset>
          )}
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={busy !== null || blocked}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('employees.form.saving') : t('separation.form.confirm')}
            </button>
          </div>
        </section>
      )}
    </section>
  );
}

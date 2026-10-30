import type {
  CommitPayrollPolicy,
  PayrollPolicyCommand,
  PayrollPolicyEntry,
  PayrollPolicyPreview,
  PayrollPolicyView,
} from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from './PayrollBits';
import {
  formatDay,
  ISO_DATE,
  NETWORK_FAILURE,
  PAY_DAYS,
  payDayOf,
  payDayText,
  payrollFailureOf,
  POLICY_BLOCKERS,
  PRORATION_METHODS,
  type PayrollFailure,
  type PolicyForm,
} from './payrollRuns';

export type PolicyLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; view: PayrollPolicyView };

function Timeline({
  entries,
  caption,
  testId,
}: {
  entries: PayrollPolicyEntry[];
  caption: string;
  testId: string;
}) {
  const { t, i18n } = useTranslation();
  return (
    <ScrollRegion label={caption}>
      <table className="timesheet-table" data-testid={testId}>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('payrollRuns.policy.columns.sequence')}</th>
            <th scope="col">{t('payrollRuns.policy.columns.effectiveFrom')}</th>
            <th scope="col">{t('payrollRuns.policy.columns.effectiveTo')}</th>
            <th scope="col">{t('payrollRuns.policy.columns.payDay')}</th>
            <th scope="col">{t('payrollRuns.policy.columns.proration')}</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.policyId} data-testid="policy-row">
              <th scope="row">{entry.sequence}</th>
              <td>{formatDay(i18n.language, entry.effectiveFrom)}</td>
              <td>
                {entry.effectiveTo
                  ? formatDay(i18n.language, entry.effectiveTo)
                  : t('payrollRuns.policy.open')}
              </td>
              <td>{payDayText(t, entry.payDay)}</td>
              <td>{t(`payrollRuns.proration.${entry.prorationMethod}`)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

/**
 * The legal entity's employer policy: the one in force, the timeline, and a form that adds a
 * policy by preview then explicit confirmation (Idempotency-Key per payload). These are employer
 * policies, not legal-compliance claims: the copy advises payroll and legal review.
 */
export function PolicyPanel({
  legalEntityId,
  load,
  onReload,
  onChanged,
}: {
  legalEntityId: string;
  load: PolicyLoad;
  onReload: () => void;
  onChanged: (message: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const focus = useFocusRequest();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<PolicyForm | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [preview, setPreview] = useState<PayrollPolicyPreview | null>(null);
  const [previewed, setPreviewed] = useState<PayrollPolicyCommand | null>(null);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);

  const view = load.kind === 'ready' ? load.view : null;
  const values: PolicyForm = form ?? {
    effectiveFrom: view?.businessDate ?? '',
    payDay: 'LAST_DAY',
    prorationMethod: 'CALENDAR_DAYS',
  };

  const edit = (next: Partial<PolicyForm>) => {
    setForm({ ...values, ...next });
    setPreview(null);
    setPreviewed(null);
    setFailure(null);
  };

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (!ISO_DATE.test(values.effectiveFrom)) {
      setProblems(['effectiveFrom']);
      focus.request('summary');
      return;
    }
    setProblems([]);
    const command: PayrollPolicyCommand = {
      effectiveFrom: values.effectiveFrom,
      payDay: payDayOf(values.payDay),
      prorationMethod: values.prorationMethod,
    };
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-policy/preview',
        { params: { path: { legalEntityId } }, body: command, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        setPreviewed(command);
        focus.request('preview');
      } else {
        setFailure(payrollFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const blocked =
    preview !== null &&
    (preview.blockers.length > 0 || preview.previewDigest === null || preview.expiresAt === null);

  const onCommit = async () => {
    if (!preview || !previewed || busy || blocked) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitPayrollPolicy = {
      ...previewed,
      expectedVersion: preview.version,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-policy',
        {
          params: {
            path: { legalEntityId },
            header: { 'Idempotency-Key': commitKey.keyFor({ legalEntityId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        setOpen(false);
        setForm(null);
        setPreview(null);
        setPreviewed(null);
        onChanged(t('payrollRuns.policy.announce'));
        onReload();
        focus.request('title');
        return;
      }
      const next = payrollFailureOf(response, error);
      if (next.kind === 'stale') {
        commitKey.reset();
        setPreview(null);
        setPreviewed(null);
        onReload();
      }
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="payroll-policy"
      aria-busy={load.kind === 'loading' ? true : undefined}
    >
      <h2 id={`${ids}-title`} tabIndex={-1} ref={focus.register('title')}>
        {t('payrollRuns.policy.title')}
      </h2>
      <p className="muted" data-testid="payroll-policy-advisory">
        {t('payrollRuns.policy.advisory')}
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="payroll-policy-loading">
          {t('payrollRuns.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <>
          <PayrollAlert failure={load.failure} testId="payroll-policy-error" />
          <div className="dialog-actions">
            <button type="button" className="button button--secondary" onClick={onReload}>
              {t('employees.retry')}
            </button>
          </div>
        </>
      )}
      {view && (
        <>
          <h3>{t('payrollRuns.policy.current')}</h3>
          {view.current ? (
            <p data-testid="payroll-policy-current">
              {t('payrollRuns.policy.currentText', {
                payDay: payDayText(t, view.current.payDay),
                proration: t(`payrollRuns.proration.${view.current.prorationMethod}`),
                from: formatDay(i18n.language, view.current.effectiveFrom),
              })}
            </p>
          ) : (
            <p className="muted" data-testid="payroll-policy-none">
              {t('payrollRuns.policy.none')}
            </p>
          )}
          {view.policies.length > 0 && (
            <>
              <h3>{t('payrollRuns.policy.timeline')}</h3>
              <Timeline
                entries={view.policies}
                caption={t('payrollRuns.policy.timeline')}
                testId="payroll-policy-timeline"
              />
            </>
          )}
          {!open && (
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                data-testid="payroll-policy-open"
                onClick={() => {
                  setOpen(true);
                  focus.request('form');
                }}
              >
                {t('payrollRuns.policy.add')}
              </button>
            </div>
          )}
          {open && (
            <section aria-labelledby={`${ids}-form`} data-testid="payroll-policy-form">
              <h3 id={`${ids}-form`} tabIndex={-1} ref={focus.register('form')}>
                {t('payrollRuns.policy.formTitle')}
              </h3>
              <p className="muted">{t('payrollRuns.policy.formHelp')}</p>
              {problems.length > 0 && (
                <div
                  className="error-summary"
                  role="alert"
                  tabIndex={-1}
                  ref={focus.register('summary')}
                  data-testid="payroll-policy-problems"
                >
                  <p>{t('employees.form.problems.title')}</p>
                  <ul>
                    {problems.map((problem) => (
                      <li key={problem}>{t(`payrollRuns.policy.problems.${problem}`)}</li>
                    ))}
                  </ul>
                </div>
              )}
              <form noValidate onSubmit={(event) => void onPreview(event)}>
                <div className="field">
                  <label htmlFor={`${ids}-date`}>{t('payrollRuns.policy.effectiveFrom')}</label>
                  <p id={`${ids}-date-help`} className="field__help">
                    {t('payrollRuns.policy.effectiveFromHelp')}
                  </p>
                  <input
                    id={`${ids}-date`}
                    type="date"
                    value={values.effectiveFrom}
                    aria-invalid={problems.includes('effectiveFrom') ? true : undefined}
                    aria-describedby={`${ids}-date-help`}
                    onChange={(event) => {
                      edit({ effectiveFrom: event.target.value });
                    }}
                  />
                </div>
                <div className="field">
                  <label htmlFor={`${ids}-payday`}>{t('payrollRuns.policy.payDay')}</label>
                  <select
                    id={`${ids}-payday`}
                    value={values.payDay}
                    onChange={(event) => {
                      edit({ payDay: event.target.value });
                    }}
                  >
                    {PAY_DAYS.map((day) => (
                      <option key={day} value={day}>
                        {day === 'LAST_DAY'
                          ? t('payrollRuns.payDay.LAST_DAY')
                          : t('payrollRuns.payDay.DAY', { day })}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label htmlFor={`${ids}-method`}>{t('payrollRuns.policy.proration')}</label>
                  <p id={`${ids}-method-help`} className="field__help">
                    {t(`payrollRuns.prorationHelp.${values.prorationMethod}`)}
                  </p>
                  <select
                    id={`${ids}-method`}
                    value={values.prorationMethod}
                    aria-describedby={`${ids}-method-help`}
                    onChange={(event) => {
                      edit({
                        prorationMethod: event.target.value as PolicyForm['prorationMethod'],
                      });
                    }}
                  >
                    {PRORATION_METHODS.map((method) => (
                      <option key={method} value={method}>
                        {t(`payrollRuns.proration.${method}`)}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="dialog-actions">
                  <button type="submit" className="button" disabled={busy !== null}>
                    {busy === 'preview'
                      ? t('payrollRuns.previewing')
                      : t('payrollRuns.policy.preview')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => {
                      setOpen(false);
                      setForm(null);
                      setPreview(null);
                      setProblems([]);
                      setFailure(null);
                      focus.request('title');
                    }}
                  >
                    {t('payrollRuns.close')}
                  </button>
                </div>
              </form>
              {failure && (
                <PayrollAlert
                  failure={failure}
                  testId="payroll-policy-failure"
                  alertRef={focus.register('failure')}
                />
              )}
              {preview && (
                <section aria-labelledby={`${ids}-preview`} data-testid="payroll-policy-preview">
                  <h4 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
                    {t('payrollRuns.preview.title')}
                  </h4>
                  <h5>{t('payrollRuns.policy.before')}</h5>
                  {preview.before.length === 0 ? (
                    <p className="muted">{t('payrollRuns.policy.nothing')}</p>
                  ) : (
                    <Timeline
                      entries={preview.before}
                      caption={t('payrollRuns.policy.before')}
                      testId="policy-preview-before"
                    />
                  )}
                  <h5>{t('payrollRuns.policy.after')}</h5>
                  {preview.after.length === 0 ? (
                    <p className="muted">{t('payrollRuns.policy.nothing')}</p>
                  ) : (
                    <Timeline
                      entries={preview.after}
                      caption={t('payrollRuns.policy.after')}
                      testId="policy-preview-after"
                    />
                  )}
                  {preview.blockers.length > 0 && (
                    <div
                      className="error-summary"
                      role="alert"
                      data-testid="payroll-policy-blockers"
                    >
                      <p>{t('payrollRuns.policy.blockersTitle')}</p>
                      <ul>
                        {POLICY_BLOCKERS.filter((code) => preview.blockers.includes(code)).map(
                          (code) => (
                            <li key={code} data-code={code}>
                              {t(`payrollRuns.policy.blockers.${code}`)}
                            </li>
                          ),
                        )}
                      </ul>
                    </div>
                  )}
                  {!blocked && preview.expiresAt && (
                    <p className="muted">
                      {t('payrollRuns.preview.expires', {
                        at: formatInstant(i18n.language, preview.expiresAt),
                      })}
                    </p>
                  )}
                  {!blocked && (
                    <div className="dialog-actions">
                      <button
                        type="button"
                        className="button"
                        data-testid="payroll-policy-confirm"
                        disabled={busy !== null}
                        onClick={() => void onCommit()}
                      >
                        {busy === 'commit'
                          ? t('payrollRuns.saving')
                          : t('payrollRuns.policy.confirm')}
                      </button>
                    </div>
                  )}
                </section>
              )}
            </section>
          )}
        </>
      )}
    </section>
  );
}

import type { CommitPayrollRun, PayrollRunCommand, PayrollRunPreview } from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { DraftLabel, LinesTable, Money, PayrollAlert, useFocusRequest } from './PayrollBits';
import {
  closedMonths,
  formatMonth,
  NETWORK_FAILURE,
  PAYROLL_CURRENCIES,
  payDayText,
  payrollFailureOf,
  type PayrollFailure,
} from './payrollRuns';

/**
 * The create flow: a closed month and a currency, a server preview (policy applied, counts, total,
 * blocked employees and the per-employee calculation), an explicit confirmation, then the frozen
 * run. The creation repeats exactly what the preview returned with an Idempotency-Key that is
 * reused for a retry of the same payload. Nothing here is kept in browser storage or a URL.
 */
export function RunCreatePanel({
  legalEntityId,
  businessDate,
  onCreated,
}: {
  legalEntityId: string;
  businessDate: string | null;
  onCreated: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const navigate = useNavigate();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const focus = useFocusRequest();
  const months = businessDate ? closedMonths(businessDate) : [];
  const [period, setPeriod] = useState('');
  const [currency, setCurrency] = useState<PayrollRunCommand['currency']>('CDF');
  const [preview, setPreview] = useState<PayrollRunPreview | null>(null);
  const [previewed, setPreviewed] = useState<PayrollRunCommand | null>(null);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const chosen = period || months[0] || '';

  const edit = () => {
    setPreview(null);
    setPreviewed(null);
    setFailure(null);
  };

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy || !chosen) return;
    const command: PayrollRunCommand = { period: chosen, currency };
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-runs/preview',
        { params: { path: { legalEntityId } }, body: command, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        setPreviewed(command);
        focus.request('preview');
      } else {
        setPreview(null);
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

  const cannotCreate =
    preview !== null &&
    (preview.blockedEmployeeCount > 0 ||
      preview.status === 'NO_CHANGE' ||
      preview.status === 'BLOCKED' ||
      preview.previewDigest === null ||
      preview.expiresAt === null);

  const onCommit = async () => {
    if (!preview || !previewed || busy || cannotCreate) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitPayrollRun = {
      ...previewed,
      expectedVersion: preview.expectedVersion,
      inputDigest: preview.inputDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-runs',
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
        onCreated();
        void navigate(`/admin/payroll-runs/${data.legalEntityId}/${data.runId}`);
        return;
      }
      const next = payrollFailureOf(response, error);
      if (next.kind === 'stale') {
        // The preview is out of date: a new one is needed, with a new key.
        commitKey.reset();
        setPreview(null);
        setPreviewed(null);
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
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="payroll-create">
      <h2 id={`${ids}-title`}>{t('payrollRuns.create.title')}</h2>
      <p className="muted">{t('payrollRuns.create.help')}</p>
      {businessDate === null ? (
        <p className="muted" data-testid="payroll-create-unavailable">
          {t('payrollRuns.create.unavailable')}
        </p>
      ) : (
        <form noValidate onSubmit={(event) => void onPreview(event)}>
          <div className="field">
            <label htmlFor={`${ids}-period`}>{t('payrollRuns.create.period')}</label>
            <p id={`${ids}-period-help`} className="field__help">
              {t('payrollRuns.create.periodHelp')}
            </p>
            <select
              id={`${ids}-period`}
              value={chosen}
              aria-describedby={`${ids}-period-help`}
              data-testid="payroll-create-period"
              onChange={(event) => {
                setPeriod(event.target.value);
                edit();
              }}
            >
              {months.map((month) => (
                <option key={month} value={month}>
                  {formatMonth(i18n.language, month)}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-currency`}>{t('payrollRuns.create.currency')}</label>
            <p id={`${ids}-currency-help`} className="field__help">
              {t('payrollRuns.create.currencyHelp')}
            </p>
            <select
              id={`${ids}-currency`}
              value={currency}
              aria-describedby={`${ids}-currency-help`}
              data-testid="payroll-create-currency"
              onChange={(event) => {
                setCurrency(event.target.value as PayrollRunCommand['currency']);
                edit();
              }}
            >
              {PAYROLL_CURRENCIES.map((code) => (
                <option key={code} value={code}>
                  {t(`compensation.currency.${code}`)}
                </option>
              ))}
            </select>
          </div>
          <div className="dialog-actions">
            <button
              type="submit"
              className="button"
              disabled={busy !== null}
              data-testid="payroll-create-preview"
            >
              {busy === 'preview' ? t('payrollRuns.previewing') : t('payrollRuns.create.preview')}
            </button>
          </div>
        </form>
      )}

      {failure && (
        <PayrollAlert
          failure={failure}
          testId="payroll-create-failure"
          alertRef={focus.register('failure')}
        />
      )}

      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="payroll-create-preview-panel">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('payrollRuns.preview.heading', {
              period: formatMonth(i18n.language, preview.period),
              currency: preview.currency,
            })}
          </h3>
          <DraftLabel />
          <dl className="decision-summary decision-summary--wrapping">
            <dt>{t('payrollRuns.preview.policy')}</dt>
            <dd data-testid="payroll-preview-policy">
              {t('payrollRuns.preview.policyText', {
                sequence: preview.policy.sequence,
                payDay: payDayText(t, preview.policy.payDay),
                proration: t(`payrollRuns.proration.${preview.policy.prorationMethod}`),
              })}
            </dd>
            <dt>{t('payrollRuns.employeeCount')}</dt>
            <dd data-testid="payroll-preview-count">{preview.employeeCount}</dd>
            <dt>{t('payrollRuns.blockedCount')}</dt>
            <dd data-testid="payroll-preview-blocked">{preview.blockedEmployeeCount}</dd>
            <dt>{t('payrollRuns.contractualGross')}</dt>
            <dd data-testid="payroll-preview-total">
              <Money money={preview.total} />
            </dd>
          </dl>
          {preview.blockedEmployeeCount > 0 && (
            <div className="error-summary" role="alert" data-testid="payroll-preview-blocked-note">
              <p>{t('payrollRuns.preview.blocked', { count: preview.blockedEmployeeCount })}</p>
            </div>
          )}
          {preview.excludedEmployeeCount > 0 && (
            <p className="muted" role="status" data-testid="payroll-preview-excluded-note">
              {t('payrollRuns.preview.excluded', { count: preview.excludedEmployeeCount })}
            </p>
          )}
          {preview.status === 'NO_CHANGE' && (
            <div className="card" role="status" data-testid="payroll-preview-identical">
              <p>{t('payrollRuns.preview.identical')}</p>
              {preview.existingRun && (
                <Link
                  className="table-link"
                  to={`/admin/payroll-runs/${preview.legalEntityId}/${preview.existingRun.runId}`}
                >
                  {t('payrollRuns.preview.openExisting', { version: preview.existingRun.version })}
                </Link>
              )}
            </div>
          )}
          {preview.status !== 'NO_CHANGE' && preview.existingRun && (
            <p className="muted" data-testid="payroll-preview-existing">
              {t('payrollRuns.preview.existing', { version: preview.existingRun.version })}
            </p>
          )}
          {preview.lines.length === 0 ? (
            <p className="muted">{t('payrollRuns.preview.noLines')}</p>
          ) : (
            <LinesTable
              lines={preview.lines}
              caption={t('payrollRuns.preview.linesCaption')}
              testId="payroll-preview-lines"
              withSources={false}
            />
          )}
          {!cannotCreate && preview.expiresAt && (
            <p className="muted">
              {t('payrollRuns.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="payroll-create-confirm"
              disabled={busy !== null || cannotCreate}
              aria-describedby={cannotCreate ? `${ids}-why` : undefined}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('payrollRuns.saving') : t('payrollRuns.create.confirm')}
            </button>
          </div>
          {cannotCreate && (
            <p id={`${ids}-why`} className="muted" data-testid="payroll-create-disabled-why">
              {preview.status === 'NO_CHANGE'
                ? t('payrollRuns.create.whyIdentical')
                : t('payrollRuns.create.whyBlocked')}
            </p>
          )}
        </section>
      )}
    </section>
  );
}

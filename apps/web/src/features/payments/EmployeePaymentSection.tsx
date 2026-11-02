import type {
  CommitPaymentDestination,
  PaymentDestinationCommand,
  PaymentDestinationPreview,
  PaymentDestinationReasonCode,
  PaymentDestinationRecord,
  PaymentDestinationView,
  PaymentMethod,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { formatDate, formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import {
  accountValid,
  branchValid,
  DESTINATION_REASONS,
  EXPLANATION_MAX,
  EXPLANATION_MIN,
  explanationValid,
  holderValid,
  METHODS,
  normalizeAccount,
  paymentFailureOf,
  phoneValid,
  PROVIDERS,
} from './payments';

interface FormState {
  method: PaymentMethod | '';
  providerCode: string;
  account: string;
  phone: string;
  holder: string;
  branch: string;
  effectiveFrom: string;
  reason: PaymentDestinationReasonCode | '';
  explanation: string;
}

const EMPTY: FormState = {
  method: '',
  providerCode: '',
  account: '',
  phone: '',
  holder: '',
  branch: '',
  effectiveFrom: '',
  reason: '',
  explanation: '',
};

/** The request body of a valid form, or null while any field is missing or invalid. */
export function commandOf(form: FormState): PaymentDestinationCommand | null {
  if (form.method === '' || form.reason === '' || form.effectiveFrom === '') return null;
  const other = form.reason === 'OTHER_CONTROLLED';
  if (other && !explanationValid(form.explanation)) return null;
  const common = {
    method: form.method,
    effectiveFrom: form.effectiveFrom,
    reasonCode: form.reason,
    explanation: other ? form.explanation.trim() : null,
  };
  if (form.method === 'MANUAL') {
    return {
      ...common,
      countryCode: null,
      providerCode: null,
      accountNumber: null,
      holderName: null,
      branchCode: null,
      phoneNumber: null,
    };
  }
  if (form.providerCode === '' || !holderValid(form.holder)) return null;
  if (form.method === 'BANK_ACCOUNT') {
    if (!accountValid(form.account) || !branchValid(form.branch)) return null;
    return {
      ...common,
      countryCode: 'CD',
      providerCode: form.providerCode,
      accountNumber: normalizeAccount(form.account),
      holderName: form.holder.trim(),
      branchCode: form.branch.trim() === '' ? null : form.branch.trim(),
      phoneNumber: null,
    };
  }
  if (!phoneValid(form.phone)) return null;
  return {
    ...common,
    countryCode: 'CD',
    providerCode: form.providerCode,
    accountNumber: null,
    holderName: form.holder.trim(),
    branchCode: null,
    phoneNumber: form.phone.trim(),
  };
}

function DestinationLine({ view, testId }: { view: PaymentDestinationView; testId: string }) {
  const { t, i18n } = useTranslation();
  return (
    <p data-testid={testId}>
      <strong>{t(`payments.method.${view.method}`)}</strong>
      {view.provider ? ` · ${view.provider.name}` : ''}
      {view.maskedValue ? ` · ` : ''}
      {view.maskedValue && <span data-testid="payment-masked">{view.maskedValue}</span>}
      {` · ${t('payments.destination.effective', { date: formatDate(i18n.language, view.effectiveFrom) })}`}
    </p>
  );
}

/**
 * MVP-095A: the « Destination de paiement » / "Payment destination" section of the employee
 * record (administrator, exact MFA). The current MASKED destination, the append-only history, and
 * the record action, previewed and then confirmed. A destination is RECORDED, never verified with a
 * bank or a provider, and recording it sends nothing. Account numbers, phone numbers and holder
 * names are held in memory only until the command is sent and are cleared afterwards.
 */
export function EmployeePaymentSection({
  employeeId,
  onChanged,
}: {
  employeeId: string;
  onChanged: (message: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [record, setRecord] = useState<PaymentDestinationRecord | null>(null);
  const [history, setHistory] = useState<PaymentDestinationView[]>([]);
  const [loadFailure, setLoadFailure] = useState<PayrollFailure | null>(null);
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY);
  const [preview, setPreview] = useState<PaymentDestinationPreview | null>(null);
  const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [version, setVersion] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const command = commandOf(form);

  useEffect(() => {
    const live = { active: true };
    const alive = () => live.active;
    void (async () => {
      try {
        const current = await core.GET('/employees/{employeeId}/payment-destination', {
          params: { path: { employeeId } },
          cache: 'no-store',
        });
        if (!alive()) return;
        if (current.data) {
          setRecord(current.data);
          setLoadFailure(null);
        } else if (current.response.status === 404) {
          setRecord({ employeeId, latestVersion: 0, current: null, scheduled: null });
          setLoadFailure(null);
        } else {
          setLoadFailure(paymentFailureOf(current.response, current.error));
          return;
        }
        const page = await core.GET('/employees/{employeeId}/payment-destination/history', {
          params: { path: { employeeId }, query: { limit: 25 } },
          cache: 'no-store',
        });
        if (alive()) setHistory(page.data ? page.data.items : []);
      } catch {
        if (alive()) setLoadFailure(NETWORK_FAILURE);
      }
    })();
    return () => {
      live.active = false;
    };
  }, [core, employeeId, version]);

  const discard = () => {
    setPreview(null);
    setReviewed(false);
    key.reset();
  };
  const change = (patch: Partial<FormState>) => {
    setForm((previous) => ({ ...previous, ...patch }));
    discard();
  };
  const clearAll = () => {
    setForm(EMPTY);
    setPreview(null);
    setReviewed(false);
    setOpen(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy || !command) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/payment-destination/preview',
        { params: { path: { employeeId } }, body: command, cache: 'no-store' },
      );
      setReviewed(false);
      key.reset();
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(paymentFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const committable =
    preview !== null &&
    preview.status === 'READY' &&
    preview.previewDigest !== null &&
    preview.expiresAt !== null;

  const onCommit = async () => {
    if (!preview || !command || busy || !committable || !reviewed) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitPaymentDestination = {
      ...command,
      expectedVersion: preview.expectedVersion,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      reviewedDetails: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/payment-destination/commit',
        {
          params: {
            path: { employeeId },
            header: { 'Idempotency-Key': key.keyFor({ employeeId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        clearAll();
        setVersion((value) => value + 1);
        onChanged(t('payments.destination.recorded'));
        headingRef.current?.focus();
        return;
      }
      const next = paymentFailureOf(response, error);
      if (next.kind === 'stale') discard();
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const providers =
    form.method === 'BANK_ACCOUNT' || form.method === 'MOBILE_MONEY' ? PROVIDERS[form.method] : [];
  const other = form.reason === 'OTHER_CONTROLLED';

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="payment-section">
      <h2 id={`${ids}-title`} tabIndex={-1} ref={headingRef}>
        {t('payments.destination.title')}
      </h2>
      <p className="muted">{t('payments.destination.help')}</p>
      {loadFailure && <PayrollAlert failure={loadFailure} testId="payment-load-failure" />}
      {record && !record.current && !record.scheduled && (
        <p data-testid="payment-none">{t('payments.destination.none')}</p>
      )}
      {record?.current && <DestinationLine view={record.current} testId="payment-current" />}
      {record?.scheduled && (
        <div data-testid="payment-scheduled">
          <p className="muted">{t('payments.destination.scheduled')}</p>
          <DestinationLine view={record.scheduled} testId="payment-scheduled-line" />
        </div>
      )}
      {history.length > 0 && (
        <section aria-labelledby={`${ids}-history`}>
          <h3 id={`${ids}-history`}>{t('payments.destination.history')}</h3>
          <ScrollRegion label={t('payments.destination.history')}>
            <table data-testid="payment-history">
              <caption className="visually-hidden">{t('payments.destination.history')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('payments.destination.version')}</th>
                  <th scope="col">{t('payments.destination.methodLabel')}</th>
                  <th scope="col">{t('payments.destination.maskedLabel')}</th>
                  <th scope="col">{t('payments.destination.effectiveLabel')}</th>
                  <th scope="col">{t('payments.destination.reasonLabel')}</th>
                  <th scope="col">{t('payments.destination.recordedLabel')}</th>
                </tr>
              </thead>
              <tbody>
                {history.map((entry) => (
                  <tr key={entry.id} data-testid="payment-history-row">
                    <td>{entry.version}</td>
                    <td>
                      {t(`payments.method.${entry.method}`)}
                      {entry.provider ? ` · ${entry.provider.name}` : ''}
                    </td>
                    <td>{entry.maskedValue ?? '—'}</td>
                    <td>{formatDate(i18n.language, entry.effectiveFrom)}</td>
                    <td>{t(`payments.reason.${entry.reasonCode}`)}</td>
                    <td>{formatInstant(i18n.language, entry.recordedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        </section>
      )}
      {!open && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            data-testid="payment-open-form"
            onClick={() => {
              setOpen(true);
            }}
          >
            {record?.current || record?.scheduled
              ? t('payments.destination.change')
              : t('payments.destination.add')}
          </button>
        </div>
      )}
      {open && (
        <form
          autoComplete="off"
          data-testid="payment-form"
          onSubmit={(event) => {
            event.preventDefault();
            void onPreview();
          }}
        >
          <div className="field">
            <label htmlFor={`${ids}-method`}>{t('payments.destination.methodLabel')}</label>
            <select
              id={`${ids}-method`}
              value={form.method}
              data-testid="payment-method"
              onChange={(event) => {
                change({
                  method: event.target.value as PaymentMethod | '',
                  providerCode: '',
                  account: '',
                  phone: '',
                  branch: '',
                });
              }}
            >
              <option value="">{t('payments.destination.choose')}</option>
              {METHODS.map((method) => (
                <option key={method} value={method}>
                  {t(`payments.method.${method}`)}
                </option>
              ))}
            </select>
          </div>
          {form.method === 'MANUAL' && (
            <p className="muted">{t('payments.destination.manualHelp')}</p>
          )}
          {providers.length > 0 && (
            <>
              <div className="field">
                <label htmlFor={`${ids}-provider`}>{t('payments.destination.providerLabel')}</label>
                <select
                  id={`${ids}-provider`}
                  value={form.providerCode}
                  data-testid="payment-provider"
                  onChange={(event) => {
                    change({ providerCode: event.target.value });
                  }}
                >
                  <option value="">{t('payments.destination.choose')}</option>
                  {providers.map((provider) => (
                    <option key={provider.code} value={provider.code}>
                      {provider.name === ''
                        ? t(`payments.provider.${provider.code}`)
                        : provider.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor={`${ids}-holder`}>{t('payments.destination.holderLabel')}</label>
                <input
                  id={`${ids}-holder`}
                  type="text"
                  value={form.holder}
                  maxLength={120}
                  autoComplete="off"
                  data-testid="payment-holder"
                  onChange={(event) => {
                    change({ holder: event.target.value });
                  }}
                />
              </div>
              {form.method === 'BANK_ACCOUNT' ? (
                <>
                  <div className="field">
                    <label htmlFor={`${ids}-account`}>
                      {t('payments.destination.accountLabel')}
                    </label>
                    <input
                      id={`${ids}-account`}
                      type="text"
                      inputMode="text"
                      value={form.account}
                      maxLength={50}
                      autoComplete="off"
                      spellCheck={false}
                      aria-describedby={`${ids}-account-help`}
                      aria-invalid={
                        form.account !== '' && !accountValid(form.account) ? true : undefined
                      }
                      data-testid="payment-account"
                      onChange={(event) => {
                        change({ account: event.target.value });
                      }}
                    />
                    <p id={`${ids}-account-help`} className="muted">
                      {t('payments.destination.accountHelp')}
                    </p>
                  </div>
                  <div className="field">
                    <label htmlFor={`${ids}-branch`}>{t('payments.destination.branchLabel')}</label>
                    <input
                      id={`${ids}-branch`}
                      type="text"
                      value={form.branch}
                      maxLength={20}
                      autoComplete="off"
                      data-testid="payment-branch"
                      onChange={(event) => {
                        change({ branch: event.target.value });
                      }}
                    />
                  </div>
                </>
              ) : (
                <div className="field">
                  <label htmlFor={`${ids}-phone`}>{t('payments.destination.phoneLabel')}</label>
                  <input
                    id={`${ids}-phone`}
                    type="tel"
                    value={form.phone}
                    maxLength={20}
                    autoComplete="off"
                    aria-describedby={`${ids}-phone-help`}
                    aria-invalid={form.phone !== '' && !phoneValid(form.phone) ? true : undefined}
                    data-testid="payment-phone"
                    onChange={(event) => {
                      change({ phone: event.target.value });
                    }}
                  />
                  <p id={`${ids}-phone-help`} className="muted">
                    {t('payments.destination.phoneHelp')}
                  </p>
                </div>
              )}
            </>
          )}
          <div className="field">
            <label htmlFor={`${ids}-from`}>{t('payments.destination.effectiveLabel')}</label>
            <input
              id={`${ids}-from`}
              type="date"
              value={form.effectiveFrom}
              data-testid="payment-effective"
              onChange={(event) => {
                change({ effectiveFrom: event.target.value });
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={`${ids}-reason`}>{t('payments.destination.reasonLabel')}</label>
            <select
              id={`${ids}-reason`}
              value={form.reason}
              data-testid="payment-reason"
              onChange={(event) => {
                change({ reason: event.target.value as PaymentDestinationReasonCode | '' });
              }}
            >
              <option value="">{t('payments.destination.choose')}</option>
              {DESTINATION_REASONS.map((code) => (
                <option key={code} value={code}>
                  {t(`payments.reason.${code}`)}
                </option>
              ))}
            </select>
          </div>
          {other && (
            <div className="field">
              <label htmlFor={`${ids}-explanation`}>{t('payments.destination.explanation')}</label>
              <textarea
                id={`${ids}-explanation`}
                rows={3}
                value={form.explanation}
                maxLength={EXPLANATION_MAX + 200}
                aria-describedby={`${ids}-explanation-help`}
                data-testid="payment-explanation"
                onChange={(event) => {
                  change({ explanation: event.target.value });
                }}
              />
              <p id={`${ids}-explanation-help`} className="muted">
                {t('payments.destination.explanationHelp', {
                  min: EXPLANATION_MIN,
                  max: EXPLANATION_MAX,
                })}
              </p>
            </div>
          )}
          <div className="dialog-actions">
            <button
              type="submit"
              className="button button--secondary"
              disabled={busy !== null || !command}
              data-testid="payment-preview-button"
            >
              {busy === 'preview' ? t('payrollRuns.previewing') : t('payments.destination.preview')}
            </button>
            <button type="button" className="button button--tertiary" onClick={clearAll}>
              {t('payments.destination.cancel')}
            </button>
          </div>
        </form>
      )}
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="payment-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="payment-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('payments.destination.previewHeading')}
          </h3>
          <p>
            <StatusBadge
              tone={preview.status === 'READY' ? 'success' : 'danger'}
              testId="payment-preview-status"
            >
              {t(`payments.previewStatus.${preview.status}`)}
            </StatusBadge>
          </p>
          <p data-testid="payment-preview-line">
            {t(`payments.method.${preview.method}`)}
            {preview.provider ? ` · ${preview.provider.name}` : ''}
            {preview.maskedValue ? ` · ${preview.maskedValue}` : ''}
          </p>
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="payment-blockers">
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker}>{t(`payments.destinationBlockers.${blocker}`)}</li>
                ))}
              </ul>
            </div>
          )}
          {preview.warnings.length > 0 && (
            <ul data-testid="payment-warnings">
              {preview.warnings.map((warning) => (
                <li key={warning}>{t(`payments.destinationWarnings.${warning}`)}</li>
              ))}
            </ul>
          )}
          {committable && preview.expiresAt && (
            <p className="muted">
              {t('payrollRuns.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <div className="choice">
            <input
              id={`${ids}-reviewed`}
              type="checkbox"
              checked={reviewed}
              disabled={!committable}
              data-testid="payment-reviewed"
              onChange={(event) => {
                setReviewed(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-reviewed`}>{t('payments.destination.reviewed')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="payment-commit"
              disabled={busy !== null || !committable || !reviewed}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('payrollRuns.saving') : t('payments.destination.commit')}
            </button>
          </div>
        </section>
      )}
    </section>
  );
}

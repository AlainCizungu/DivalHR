import type {
  CommitCompensation,
  CompensationCommand,
  CompensationInterval,
  CompensationPreview,
  CompensationReceipt,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { normalizeText, textAcceptable } from '../expenses/expenses';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import {
  compensationFailureOf,
  CURRENCIES,
  EXPLANATION_MAX,
  EXPLANATION_MIN,
  formatDate,
  formatInstant,
  formatMoney,
  NETWORK_FAILURE,
  parseAmount,
  problemsOf,
  REASON_CODES,
  type CompensationFailure,
  type CompensationField,
  type CompensationForm as FormState,
} from './compensation';
import { CompensationAlert } from './CompensationBits';
import { PreviewIntervals, PreviewNotes } from './PreviewBits';

export type FormMode = 'ESTABLISHED' | 'CHANGED' | 'CORRECTED';

type Problem = CompensationField | 'acknowledge';

/**
 * MVP-093A: establishes, changes or corrects the monthly base compensation. The command is
 * previewed first (the intervals before and after, warnings, blockers); only an explicit confirm
 * commits it, repeating exactly what the preview returned (version, employment digest, preview
 * digest, expiry) with the acknowledgement and a fresh Idempotency-Key. The amount is text and is
 * never a JS number on its way to the API.
 */
export function CompensationForm({
  employeeId,
  mode,
  target,
  businessDate,
  onDone,
  onClose,
  onStale,
}: {
  employeeId: string;
  mode: FormMode;
  target: CompensationInterval | null;
  businessDate: string;
  onDone: (receipt: CompensationReceipt) => void;
  onClose: () => void;
  onStale: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const [form, setForm] = useState<FormState>({
    effectiveFrom: target?.effectiveFrom ?? businessDate,
    currency: target?.money.currency ?? 'USD',
    amount: target?.money.amount ?? '',
    reasonCode: '',
    explanation: '',
  });
  const [problems, setProblems] = useState<ReadonlySet<Problem>>(new Set());
  const [preview, setPreview] = useState<CompensationPreview | null>(null);
  const [previewed, setPreviewed] = useState<CompensationCommand | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<CompensationFailure | null>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const previewRef = useRef<HTMLHeadingElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const summaryRef = useRef<HTMLDivElement>(null);
  const focusNext = useRef<'title' | 'preview' | 'failure' | 'summary' | null>('title');

  useEffect(() => {
    const element = {
      title: titleRef.current,
      preview: previewRef.current,
      failure: failureRef.current,
      summary: summaryRef.current,
    }[focusNext.current ?? 'title'];
    if (focusNext.current && element) {
      focusNext.current = null;
      element.focus();
    }
  });

  const edited = () => {
    setPreview(null);
    setPreviewed(null);
    setAcknowledged(false);
    setFailure(null);
  };
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((current) => ({ ...current, [key]: value }));
    edited();
  };

  const textOk = (text: string) =>
    textAcceptable(normalizeText(text), EXPLANATION_MIN, EXPLANATION_MAX);

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const found = problemsOf(form, mode, textOk);
    setProblems(found);
    if (found.size > 0) {
      focusNext.current = 'summary';
      return;
    }
    const command: CompensationCommand = {
      action: mode,
      effectiveFrom: form.effectiveFrom,
      currency: form.currency,
      amount: parseAmount(form.amount, form.currency) ?? '',
      ...(mode === 'CORRECTED' && target
        ? {
            targetChangeId: target.originChangeId,
            reasonCode: form.reasonCode === '' ? null : form.reasonCode,
            explanation: normalizeText(form.explanation),
          }
        : {}),
    };
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/compensation/preview',
        { params: { path: { employeeId } }, body: command, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        setPreviewed(command);
        setAcknowledged(false);
        focusNext.current = 'preview';
      } else {
        setFailure(compensationFailureOf(response, error));
        focusNext.current = 'failure';
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focusNext.current = 'failure';
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
    if (preview.requiresAcknowledgement && !acknowledged) {
      setProblems(new Set<Problem>(['acknowledge']));
      focusNext.current = 'summary';
      return;
    }
    setProblems(new Set());
    const body: CommitCompensation = {
      action: previewed.action,
      effectiveFrom: previewed.effectiveFrom,
      currency: previewed.currency,
      amount: previewed.amount,
      targetChangeId: previewed.targetChangeId ?? null,
      reasonCode: previewed.reasonCode ?? null,
      explanation: previewed.explanation ?? null,
      expectedVersion: preview.version,
      employmentDigest: preview.employmentDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      acknowledgeRetroactive: acknowledged,
      confirmed: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/compensation/commit',
        {
          params: {
            path: { employeeId },
            header: { 'Idempotency-Key': commitKey.keyFor({ employeeId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        onDone(data);
        return;
      }
      const next = compensationFailureOf(response, error);
      if (next.kind === 'stale') {
        commitKey.reset();
        setPreview(null);
        setPreviewed(null);
        setAcknowledged(false);
        onStale();
      }
      setFailure(next);
      focusNext.current = 'failure';
    } catch {
      setFailure(NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(null);
    }
  };

  const reasonCorrect = mode === 'CORRECTED';
  const amountHelp =
    form.currency === 'CDF' ? 'compensation.form.amountHelpCdf' : 'compensation.form.amountHelpUsd';
  const invalid = (field: Problem) => (problems.has(field) ? true : undefined);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="compensation-form" data-mode={mode}>
      <h3 id={`${ids}-title`} tabIndex={-1} ref={titleRef}>
        {t(`compensation.form.title.${mode}`)}
      </h3>
      <p className="muted">{t(`compensation.form.help.${mode}`)}</p>
      {problems.size > 0 && (
        <div
          className="error-summary"
          role="alert"
          tabIndex={-1}
          ref={summaryRef}
          data-testid="compensation-problems"
        >
          <p>{t('employees.form.problems.title')}</p>
          <ul>
            {[...problems].map((problem) => (
              <li key={problem}>{t(`compensation.form.problems.${problem}`)}</li>
            ))}
          </ul>
        </div>
      )}
      <form noValidate onSubmit={(event) => void onPreview(event)}>
        <div className="field">
          <label htmlFor={`${ids}-date`}>{t('compensation.form.effectiveFrom')}</label>
          <p id={`${ids}-date-help`} className="field__help">
            {t('compensation.form.effectiveFromHelp')}
          </p>
          <input
            id={`${ids}-date`}
            type="date"
            value={form.effectiveFrom}
            aria-invalid={invalid('effectiveFrom')}
            aria-describedby={`${ids}-date-help`}
            onChange={(event) => {
              set('effectiveFrom', event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-currency`}>{t('compensation.form.currency')}</label>
          <p id={`${ids}-currency-help`} className="field__help">
            {t('compensation.form.currencyHelp')}
          </p>
          <select
            id={`${ids}-currency`}
            value={form.currency}
            aria-describedby={`${ids}-currency-help`}
            onChange={(event) => {
              set('currency', event.target.value as FormState['currency']);
            }}
          >
            {CURRENCIES.map((code) => (
              <option key={code} value={code}>
                {t(`compensation.currency.${code}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${ids}-amount`}>{t('compensation.form.amount')}</label>
          <p id={`${ids}-amount-help`} className="field__help">
            {t(amountHelp)}
          </p>
          <input
            id={`${ids}-amount`}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            spellCheck={false}
            value={form.amount}
            aria-invalid={invalid('amount')}
            aria-describedby={`${ids}-amount-help`}
            onChange={(event) => {
              set('amount', event.target.value);
            }}
          />
        </div>
        {reasonCorrect && (
          <>
            <div className="field">
              <label htmlFor={`${ids}-reason`}>{t('compensation.form.reasonCode')}</label>
              <select
                id={`${ids}-reason`}
                value={form.reasonCode}
                aria-invalid={invalid('reasonCode')}
                onChange={(event) => {
                  set('reasonCode', event.target.value as FormState['reasonCode']);
                }}
              >
                <option value="">{t('compensation.form.chooseReason')}</option>
                {REASON_CODES.map((code) => (
                  <option key={code} value={code}>
                    {t(`compensation.reason.${code}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor={`${ids}-explanation`}>{t('compensation.form.explanation')}</label>
              <p id={`${ids}-explanation-help`} className="field__help">
                {t('compensation.form.explanationHelp', {
                  min: EXPLANATION_MIN,
                  max: EXPLANATION_MAX,
                })}
              </p>
              <textarea
                id={`${ids}-explanation`}
                rows={4}
                maxLength={EXPLANATION_MAX}
                value={form.explanation}
                aria-invalid={invalid('explanation')}
                aria-describedby={`${ids}-explanation-help`}
                onChange={(event) => {
                  set('explanation', event.target.value);
                }}
              />
            </div>
          </>
        )}
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={busy !== null}>
            {busy === 'preview'
              ? t('compensation.form.previewing')
              : t('compensation.form.preview')}
          </button>
          <button type="button" className="button button--secondary" onClick={onClose}>
            {t('compensation.form.close')}
          </button>
        </div>
      </form>

      {failure && (
        <CompensationAlert failure={failure} testId="compensation-error" alertRef={failureRef} />
      )}

      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="compensation-preview">
          <h4 id={`${ids}-preview`} tabIndex={-1} ref={previewRef}>
            {t('compensation.preview.title')}
          </h4>
          <p data-testid="preview-summary">
            {t('compensation.preview.summary', {
              action: t(`compensation.action.${preview.action}`),
              money: formatMoney(i18n.language, preview.money),
              date: formatDate(i18n.language, preview.effectiveFrom),
            })}
          </p>
          <PreviewIntervals
            before={preview.before}
            after={preview.after}
            testId="preview-intervals"
          />
          <PreviewNotes warnings={preview.warnings} blockers={preview.blockers} />
          {!blocked && preview.expiresAt && (
            <p className="muted" data-testid="preview-expiry">
              {t('compensation.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          {preview.requiresAcknowledgement && !blocked && (
            <div className="choice">
              <input
                id={`${ids}-ack`}
                type="checkbox"
                checked={acknowledged}
                aria-invalid={invalid('acknowledge')}
                onChange={(event) => {
                  setAcknowledged(event.target.checked);
                }}
              />
              <label htmlFor={`${ids}-ack`}>{t('compensation.preview.acknowledge')}</label>
            </div>
          )}
          <div className="dialog-actions">
            {!blocked && (
              <button
                type="button"
                className="button"
                data-testid="compensation-confirm"
                disabled={busy !== null}
                onClick={() => void onCommit()}
              >
                {busy === 'commit'
                  ? t('compensation.form.saving')
                  : t('compensation.preview.confirm')}
              </button>
            )}
          </div>
        </section>
      )}
    </section>
  );
}

import type {
  PayrollVoidCommand,
  PayrollVoidPreview,
  PayrollVoidReasonCode,
} from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import {
  EXPLANATION_MAX,
  EXPLANATION_MIN,
  explanationValid,
  packageFailureOf,
  VOID_REASONS,
} from './payslips';

/**
 * Void preview and confirmation of one APPROVED package: a controlled reason, an explanation
 * required (and sent) only for OTHER_CONTROLLED, and the acknowledgement that no payment
 * integration has run. Changing the reason or the text discards the preview, and a stale or
 * conflicting answer asks for a new review. The explanation is never logged or stored by the
 * browser.
 */
export function VoidPanel({
  legalEntityId,
  packageId,
  onVoided,
  onGone,
}: {
  legalEntityId: string;
  packageId: string;
  onVoided: () => void;
  onGone: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [reason, setReason] = useState<PayrollVoidReasonCode | ''>('');
  const [explanation, setExplanation] = useState('');
  const [preview, setPreview] = useState<PayrollVoidPreview | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);

  const other = reason === 'OTHER_CONTROLLED';
  const explanationOk = !other || explanationValid(explanation);
  const command: PayrollVoidCommand | null =
    reason === '' || !explanationOk
      ? null
      : { reasonCode: reason, explanation: other ? explanation.trim() : null };
  const discard = () => {
    setPreview(null);
    setAcknowledged(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy || !command) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/void/preview',
        { params: { path: { legalEntityId, packageId } }, body: command, cache: 'no-store' },
      );
      setAcknowledged(false);
      key.reset();
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(packageFailureOf(response, error));
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
  const whyKey =
    preview === null
      ? null
      : !committable
        ? 'payrollPackages.void.whyBlocked'
        : !acknowledged
          ? 'payrollPackages.void.whyAcknowledge'
          : null;

  const onCommit = async () => {
    if (!preview || !command || busy || !committable || !acknowledged) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body = {
      ...command,
      packageVersion: preview.packageVersion,
      approvalDigest: preview.approvalDigest,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      noPaymentAcknowledged: true as const,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/void',
        {
          params: {
            path: { legalEntityId, packageId },
            header: { 'Idempotency-Key': key.keyFor({ legalEntityId, packageId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        key.reset();
        setPreview(null);
        setExplanation('');
        setReason('');
        setAcknowledged(false);
        onVoided();
        return;
      }
      const next = packageFailureOf(response, error);
      if (next.kind === 'stale') {
        discard();
        if (next.code === 'PAYROLL_PACKAGE_VOIDED') onGone();
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
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="void-panel">
      <h2 id={`${ids}-title`}>{t('payrollPackages.void.title')}</h2>
      <p className="muted">{t('payrollPackages.void.help')}</p>
      <div className="field">
        <label htmlFor={`${ids}-reason`}>{t('payrollPackages.void.reason')}</label>
        <select
          id={`${ids}-reason`}
          value={reason}
          data-testid="void-reason"
          onChange={(event) => {
            setReason(event.target.value as PayrollVoidReasonCode | '');
            discard();
          }}
        >
          <option value="">{t('payrollPackages.void.choose')}</option>
          {VOID_REASONS.map((code) => (
            <option key={code} value={code}>
              {t(`payrollPackages.voidReason.${code}`)}
            </option>
          ))}
        </select>
      </div>
      {other && (
        <div className="field">
          <label htmlFor={`${ids}-explanation`}>{t('payrollPackages.void.explanation')}</label>
          <textarea
            id={`${ids}-explanation`}
            value={explanation}
            rows={4}
            required
            maxLength={EXPLANATION_MAX + 200}
            aria-describedby={`${ids}-explanation-help`}
            aria-invalid={explanation !== '' && !explanationOk ? true : undefined}
            data-testid="void-explanation"
            onChange={(event) => {
              setExplanation(event.target.value);
              discard();
            }}
          />
          <p id={`${ids}-explanation-help`} className="muted">
            {t('payrollPackages.void.explanationHelp', {
              min: EXPLANATION_MIN,
              max: EXPLANATION_MAX,
            })}
          </p>
        </div>
      )}
      <div className="dialog-actions">
        <button
          type="button"
          className="button button--secondary"
          disabled={busy !== null || !command}
          data-testid="void-preview-button"
          onClick={() => void onPreview()}
        >
          {busy === 'preview' ? t('payrollRuns.previewing') : t('payrollPackages.void.preview')}
        </button>
      </div>
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="void-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {failure?.kind === 'stale' && (
        <p role="status" data-testid="void-stale-refresh">
          {t('payrollPackages.void.staleRefresh')}
        </p>
      )}
      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="void-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('payrollPackages.void.previewHeading')}
          </h3>
          <p>
            <StatusBadge
              tone={preview.status === 'READY' ? 'success' : 'danger'}
              testId="void-preview-status"
            >
              {t(`payrollPackages.previewStatus.${preview.status}`)}
            </StatusBadge>
          </p>
          <p data-testid="void-preview-reason">
            {t('payrollPackages.void.reasonIs', {
              reason: t(`payrollPackages.voidReason.${preview.reasonCode}`),
            })}
          </p>
          <p>{t('payrollPackages.void.affects', { count: preview.payslipCount })}</p>
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="void-blockers">
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker} data-code={blocker}>
                    {t(`payrollPackages.voidBlockers.${blocker}`)}
                  </li>
                ))}
              </ul>
            </div>
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
              id={`${ids}-ack`}
              type="checkbox"
              checked={acknowledged}
              disabled={!committable}
              data-testid="void-ack"
              onChange={(event) => {
                setAcknowledged(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-ack`}>{t('payrollPackages.void.acknowledge')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="void-commit"
              disabled={busy !== null || !committable || !acknowledged}
              aria-describedby={whyKey ? `${ids}-why` : undefined}
              onClick={() => void onCommit()}
            >
              {busy === 'commit' ? t('payrollRuns.saving') : t('payrollPackages.void.commit')}
            </button>
          </div>
          {whyKey && (
            <p id={`${ids}-why`} className="muted" data-testid="void-commit-why">
              {t(whyKey)}
            </p>
          )}
        </section>
      )}
    </section>
  );
}

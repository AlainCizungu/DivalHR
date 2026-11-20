import type {
  SalaryAdvancePurgePreview,
  SalaryAdvancePurgeResult,
  SalaryAdvanceRetentionPolicy,
} from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { formatInstant } from '../compensation/compensation';
import { CompensationAlert } from '../compensation/CompensationBits';
import {
  NETWORK_FAILURE,
  salaryAdvanceFailureOf,
  wholeNumber,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

/**
 * MVP-120C (D120C-8 to D120C-12): the administrator's evidence retention. Retention is disabled
 * until the organization chooses a period (30 to 3,650 days, its own choice, not legal advice). A
 * purge is never automatic: preview the eligible delivery diagnostics (counts only), then confirm
 * that exact preview. Requests, confirmations, consent, statuses, history and audit are never part
 * of it, and nothing here shows a payload, a signature or an identity.
 */
export function RetentionPanel() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [policy, setPolicy] = useState<SalaryAdvanceRetentionPolicy | null>(null);
  const [days, setDays] = useState('');
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [saved, setSaved] = useState(false);
  const [preview, setPreview] = useState<SalaryAdvancePurgePreview | null>(null);
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<SalaryAdvancePurgeResult | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let active = true;
    void core
      .GET('/salary-advance-retention-policy', { cache: 'no-store' })
      .then(({ data, error, response }) => {
        if (!active) return;
        if (data) {
          setPolicy(data);
          setDays(data.retentionDays === null ? '' : String(data.retentionDays));
          setFailure(null);
        } else {
          setFailure(salaryAdvanceFailureOf(response, error));
        }
      })
      .catch(() => {
        if (active) setFailure(NETWORK_FAILURE);
      });
    return () => {
      active = false;
    };
  }, [core]);

  const save = async () => {
    const value = wholeNumber(days, 30, 3650);
    setSaved(false);
    if (value === null) {
      setFailure({
        kind: 'validation',
        messageKey: 'errors.SALARY_ADVANCE_RETENTION_PERIOD_INVALID',
      });
      return;
    }
    setBusy(true);
    try {
      const { data, error, response } = await core.PUT('/salary-advance-retention-policy', {
        body: { retentionDays: value },
      });
      if (data) {
        setPolicy(data);
        setFailure(null);
        setSaved(true);
        setPreview(null);
        setResult(null);
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const runPreview = async () => {
    setBusy(true);
    setResult(null);
    setConfirming(false);
    try {
      const { data, error, response } = await core.POST(
        '/salary-advance-evidence-purge/preview',
        {},
      );
      if (data) {
        setPreview(data);
        setFailure(null);
      } else {
        setPreview(null);
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const confirm = async () => {
    if (!preview) return;
    setBusy(true);
    try {
      const { data, error, response } = await core.POST(
        '/salary-advance-evidence-purge/{previewId}/confirm',
        {
          params: { path: { previewId: preview.previewId } },
          body: { previewDigest: preview.digest, confirmed: true as const },
        },
      );
      if (data) {
        setResult(data);
        setPreview(null);
        setConfirming(false);
        setFailure(null);
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
        setPreview(null);
        setConfirming(false);
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="retention-panel">
      <h2 id={`${ids}-title`}>{t('salaryAdvance.handoff.retention.title')}</h2>
      <p>{t('salaryAdvance.handoff.retention.intro')}</p>
      {failure && <CompensationAlert failure={failure} testId="retention-error" />}
      {policy && (
        <p data-testid="retention-current">
          {policy.configured
            ? t('salaryAdvance.handoff.retention.current', {
                days: policy.retentionDays,
                version: policy.version,
              })
            : t('salaryAdvance.handoff.retention.disabled')}
        </p>
      )}
      <div className="field">
        <label htmlFor={`${ids}-days`}>{t('salaryAdvance.handoff.retention.daysLabel')}</label>
        <input
          id={`${ids}-days`}
          type="text"
          inputMode="numeric"
          value={days}
          data-testid="retention-days"
          onChange={(event) => {
            setDays(event.target.value);
            setSaved(false);
          }}
        />
      </div>
      <div className="dialog-actions">
        <button
          type="button"
          className="button"
          disabled={busy}
          data-testid="retention-save"
          onClick={() => void save()}
        >
          {t('salaryAdvance.handoff.retention.save')}
        </button>
        <button
          type="button"
          className="button button--secondary"
          disabled={busy || !policy?.configured}
          data-testid="purge-preview"
          onClick={() => void runPreview()}
        >
          {t('salaryAdvance.handoff.retention.preview')}
        </button>
      </div>
      {saved && (
        <p role="status" data-testid="retention-saved">
          {t('salaryAdvance.handoff.retention.saved')}
        </p>
      )}
      {preview && preview.attemptCount === 0 && (
        <p role="status" data-testid="purge-none">
          {t('salaryAdvance.handoff.retention.previewNone')}
        </p>
      )}
      {preview && preview.attemptCount > 0 && (
        <div data-testid="purge-preview-result">
          <p role="status">
            {t('salaryAdvance.handoff.retention.previewResult', {
              attempts: preview.attemptCount,
              handoffs: preview.handoffCount,
              at: formatInstant(i18n.language, preview.expiresAt),
            })}
          </p>
          {!confirming ? (
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--danger"
                data-testid="purge-open"
                onClick={() => {
                  setConfirming(true);
                }}
              >
                {t('salaryAdvance.handoff.retention.delete')}
              </button>
            </div>
          ) : (
            <div data-testid="purge-confirm-panel">
              <p>
                {t('salaryAdvance.handoff.retention.deleteStatement', {
                  attempts: preview.attemptCount,
                })}
              </p>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--danger"
                  disabled={busy}
                  data-testid="purge-confirm"
                  onClick={() => void confirm()}
                >
                  {t('salaryAdvance.handoff.retention.confirm')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setConfirming(false);
                  }}
                >
                  {t('salaryAdvance.requests.back')}
                </button>
              </div>
            </div>
          )}
        </div>
      )}
      {result && (
        <p role="status" data-testid="purge-done">
          {t(
            result.replayed
              ? 'salaryAdvance.handoff.retention.doneReplay'
              : 'salaryAdvance.handoff.retention.done',
            { attempts: result.attemptCount, handoffs: result.handoffCount },
          )}
        </p>
      )}
    </section>
  );
}

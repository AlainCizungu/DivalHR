import type {
  PrivacyPolicyApplicationPreview,
  PrivacyPolicyApplicationResult,
} from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PrivacyAlert } from './PrivacyBits';
import { useCommand } from './privacy';

/**
 * Policy application: assigns an ACTIVE reviewed policy to partner referrals that are still
 * POLICY_PENDING in one country. A preview counts them; the confirmation revalidates and stamps
 * them. A referral that already carries a stamp is never touched.
 */
export function ApplicationSection() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [country, setCountry] = useState('');
  const [preview, setPreview] = useState<PrivacyPolicyApplicationPreview | null>(null);
  const [result, setResult] = useState<PrivacyPolicyApplicationResult | null>(null);
  const previewing = useCommand();
  const committing = useCommand();
  const previewKey = useIdempotencyKey();
  const commitKey = useIdempotencyKey();

  const previewIt = async (event: SyntheticEvent) => {
    event.preventDefault();
    const body = { countryCode: country.trim().toUpperCase() };
    setResult(null);
    const data = await previewing.run(() =>
      core.POST('/platform/privacy/policy-application/preview', {
        params: { header: { 'Idempotency-Key': previewKey.keyFor(body) } },
        body,
      }),
    );
    setPreview(data);
  };

  const commit = async () => {
    if (!preview) return;
    const body = { previewId: preview.previewId, previewDigest: preview.previewDigest };
    const data = await committing.run(() =>
      core.POST('/platform/privacy/policy-application', {
        params: { header: { 'Idempotency-Key': commitKey.keyFor(body) } },
        body,
      }),
    );
    if (data) {
      commitKey.reset();
      setResult(data);
      setPreview(null);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="privacy-application">
      <h2 id={`${ids}-title`}>{t('privacy.application.title')}</h2>
      <p className="muted">{t('privacy.application.help')}</p>
      <form className="card" onSubmit={(event) => void previewIt(event)}>
        <div className="field">
          <label htmlFor={`${ids}-country`}>{t('privacy.policy.country')}</label>
          <input
            id={`${ids}-country`}
            value={country}
            maxLength={2}
            autoComplete="off"
            onChange={(event) => {
              setCountry(event.target.value);
              setPreview(null);
            }}
          />
        </div>
        <PrivacyAlert failure={previewing.failure} testId="privacy-application-preview-failure" />
        <div className="button-row">
          <button type="submit" className="button" disabled={previewing.working}>
            {t('privacy.application.preview')}
          </button>
        </div>
      </form>
      {preview && (
        <div className="card" role="status" data-testid="privacy-application-preview">
          <p data-testid="privacy-application-total" data-total={preview.totalPending}>
            {t('privacy.application.total', { count: preview.totalPending })}
          </p>
          <ul>
            {preview.items.map((item) => (
              <li key={item.policyId}>
                {item.countryCode} · {item.policyId} ·{' '}
                {t('privacy.application.pending', { count: item.pendingReferrals })}
              </li>
            ))}
          </ul>
          <PrivacyAlert failure={committing.failure} testId="privacy-application-commit-failure" />
          <div className="button-row">
            <button
              type="button"
              className="button"
              disabled={committing.working}
              data-testid="privacy-application-confirm"
              onClick={() => void commit()}
            >
              {t('privacy.application.confirm')}
            </button>
          </div>
        </div>
      )}
      {result && (
        <p className="card" role="status" data-testid="privacy-application-result">
          {t('privacy.application.done', { count: result.applied })}
        </p>
      )}
    </section>
  );
}

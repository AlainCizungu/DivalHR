import type {
  PrivacyHoldReason,
  PrivacyHoldView,
  PrivacyPurgePreview,
  PrivacyPurgeResult,
} from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PrivacyAlert } from './PrivacyBits';
import { HOLD_REASONS, NOTE_MAX, REFERRAL_PATTERN, todayIso, useCommand } from './privacy';

/**
 * Legal holds and the two-step purge. A hold blocks every purge of a referral until released. A
 * purge is a preview (what each data class would do, with no personal value) followed by an exact
 * confirmation that revalidates under locks. Nothing is purged by a clock or in the background.
 */
export function PurgeSection() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [referral, setReferral] = useState('');
  const [reason, setReason] = useState<PrivacyHoldReason>('DISPUTE');
  const [review, setReview] = useState(todayIso());
  const [external, setExternal] = useState('');
  const [note, setNote] = useState('');
  const [holds, setHolds] = useState<PrivacyHoldView[]>([]);
  const [action, setAction] = useState<'MINIMIZE' | 'ERASE'>('MINIMIZE');
  const [preview, setPreview] = useState<PrivacyPurgePreview | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [result, setResult] = useState<PrivacyPurgeResult | null>(null);
  const hold = useCommand();
  const release = useCommand();
  const previewing = useCommand();
  const committing = useCommand();
  const holdKey = useIdempotencyKey();
  const releaseKey = useIdempotencyKey();
  const previewKey = useIdempotencyKey();
  const commitKey = useIdempotencyKey();
  const valid = REFERRAL_PATTERN.test(referral.trim());

  const place = async (event: SyntheticEvent) => {
    event.preventDefault();
    const ref = referral.trim();
    const body = {
      reasonCode: reason,
      reviewDate: review,
      externalReference: external.trim() === '' ? null : external.trim(),
      internalNote: note.trim() === '' ? null : note.trim(),
    };
    const placed = await hold.run(() =>
      core.POST('/platform/privacy/referrals/{referralId}/holds', {
        params: {
          path: { referralId: ref },
          header: { 'Idempotency-Key': holdKey.keyFor({ ref, ...body }) },
        },
        body,
      }),
    );
    if (placed) {
      holdKey.reset();
      setHolds([placed, ...holds]);
      setNote('');
      setPreview(null);
    }
  };

  const releaseHold = async (item: PrivacyHoldView) => {
    const body = { internalNote: null };
    const done = await release.run(() =>
      core.POST('/platform/privacy/referrals/{referralId}/holds/{holdId}/release', {
        params: {
          path: { referralId: item.referralId, holdId: item.id },
          header: { 'Idempotency-Key': releaseKey.keyFor({ id: item.id }) },
        },
        body,
      }),
    );
    if (done) {
      releaseKey.reset();
      setHolds(holds.map((h) => (h.id === done.id ? done : h)));
      setPreview(null);
    }
  };

  const previewIt = async (event: SyntheticEvent) => {
    event.preventDefault();
    const body = { referralIds: [referral.trim()], action };
    setResult(null);
    setConfirmed(false);
    const data = await previewing.run(() =>
      core.POST('/platform/privacy/purge/preview', {
        params: { header: { 'Idempotency-Key': previewKey.keyFor(body) } },
        body,
      }),
    );
    // Each preview reads the state of the day: a repeated one must not replay the earlier answer.
    if (data) previewKey.reset();
    setPreview(data);
  };

  const commit = async () => {
    if (!preview || !confirmed) return;
    const body = { previewId: preview.previewId, previewDigest: preview.previewDigest };
    const data = await committing.run(() =>
      core.POST('/platform/privacy/purge', {
        params: { header: { 'Idempotency-Key': commitKey.keyFor(body) } },
        body,
      }),
    );
    if (data) {
      commitKey.reset();
      setResult(data);
      setPreview(null);
      setConfirmed(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="privacy-purge">
      <h2 id={`${ids}-title`}>{t('privacy.purge.title')}</h2>
      <p className="muted">{t('privacy.purge.help')}</p>
      <div className="field">
        <label htmlFor={`${ids}-referral`}>{t('privacy.purge.referral')}</label>
        <input
          id={`${ids}-referral`}
          value={referral}
          maxLength={19}
          autoComplete="off"
          aria-invalid={referral !== '' && !valid}
          onChange={(event) => {
            setReferral(event.target.value);
            setPreview(null);
          }}
        />
      </div>
      <form
        className="card"
        aria-labelledby={`${ids}-hold`}
        onSubmit={(event) => void place(event)}
        data-testid="privacy-hold-form"
      >
        <h3 id={`${ids}-hold`}>{t('privacy.hold.title')}</h3>
        <div className="field">
          <label htmlFor={`${ids}-reason`}>{t('privacy.hold.reason')}</label>
          <select
            id={`${ids}-reason`}
            value={reason}
            onChange={(event) => {
              setReason(event.target.value as PrivacyHoldReason);
            }}
          >
            {HOLD_REASONS.map((r) => (
              <option key={r} value={r}>
                {t(`privacy.holdReason.${r}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${ids}-review`}>{t('privacy.hold.reviewDate')}</label>
          <input
            id={`${ids}-review`}
            type="date"
            value={review}
            onChange={(event) => {
              setReview(event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-external`}>{t('privacy.hold.external')}</label>
          <input
            id={`${ids}-external`}
            value={external}
            maxLength={120}
            autoComplete="off"
            onChange={(event) => {
              setExternal(event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-note`}>{t('privacy.requests.internalNote')}</label>
          <textarea
            id={`${ids}-note`}
            value={note}
            maxLength={NOTE_MAX}
            onChange={(event) => {
              setNote(event.target.value);
            }}
          />
        </div>
        <PrivacyAlert failure={hold.failure} testId="privacy-hold-failure" />
        <div className="button-row">
          <button type="submit" className="button" disabled={!valid || hold.working}>
            {t('privacy.hold.place')}
          </button>
        </div>
      </form>
      <PrivacyAlert failure={release.failure} testId="privacy-release-failure" />
      {holds.length > 0 && (
        <ul className="partner-catalogue" data-testid="privacy-holds">
          {holds.map((item) => (
            <li
              key={item.id}
              className="card"
              data-testid="privacy-hold"
              data-reference={item.id}
              data-state={item.state}
            >
              <p>
                {item.referralId} · {t(`privacy.holdReason.${item.reasonCode}`)} ·{' '}
                {t(`privacy.holdState.${item.state}`)}
              </p>
              {item.state === 'ACTIVE' && (
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={release.working}
                  data-testid="privacy-hold-release"
                  onClick={() => void releaseHold(item)}
                >
                  {t('privacy.hold.release')}
                  <span className="visually-hidden"> — {item.id}</span>
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      <form
        className="card"
        aria-labelledby={`${ids}-purge`}
        onSubmit={(event) => void previewIt(event)}
        data-testid="privacy-purge-form"
      >
        <h3 id={`${ids}-purge`}>{t('privacy.purge.previewTitle')}</h3>
        <div className="field">
          <label htmlFor={`${ids}-action`}>{t('privacy.purge.action')}</label>
          <select
            id={`${ids}-action`}
            value={action}
            onChange={(event) => {
              setAction(event.target.value as 'MINIMIZE' | 'ERASE');
              setPreview(null);
            }}
          >
            <option value="MINIMIZE">{t('privacy.action.MINIMIZE')}</option>
            <option value="ERASE">{t('privacy.action.ERASE')}</option>
          </select>
          <p className="muted">{t(`privacy.purge.actionHelp.${action}`)}</p>
        </div>
        <PrivacyAlert failure={previewing.failure} testId="privacy-purge-preview-failure" />
        <div className="button-row">
          <button type="submit" className="button" disabled={!valid || previewing.working}>
            {t('privacy.purge.preview')}
          </button>
        </div>
      </form>
      {preview && (
        <div className="card" role="status" data-testid="privacy-purge-preview">
          <p data-testid="privacy-purge-eligible" data-eligible={preview.eligibleClasses}>
            {t('privacy.purge.eligible', { count: preview.eligibleClasses })}
          </p>
          <ul>
            {preview.items.flatMap((item) =>
              item.classes.map((c) => (
                <li key={`${item.referralId}-${c.dataClass}`} data-testid="privacy-purge-class">
                  {t(`privacy.dataClass.${c.dataClass}`)} ·{' '}
                  {c.eligible
                    ? t('privacy.purge.willAct')
                    : t(`privacy.refusal.${c.refusal ?? 'NOT_YET_ELIGIBLE'}`)}
                </li>
              )),
            )}
          </ul>
          <div className="field field--checkbox">
            <input
              id={`${ids}-confirm`}
              type="checkbox"
              checked={confirmed}
              onChange={(event) => {
                setConfirmed(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-confirm`}>{t('privacy.purge.confirmLabel')}</label>
          </div>
          <PrivacyAlert failure={committing.failure} testId="privacy-purge-commit-failure" />
          <div className="button-row">
            <button
              type="button"
              className="button"
              disabled={!confirmed || preview.eligibleClasses === 0 || committing.working}
              data-testid="privacy-purge-confirm"
              onClick={() => void commit()}
            >
              {t('privacy.purge.confirm')}
            </button>
          </div>
        </div>
      )}
      {result && (
        <div className="card" role="status" data-testid="privacy-purge-result">
          <p>{t('privacy.purge.done')}</p>
          <ul>
            {result.items.flatMap((item) =>
              item.classes.map((c) => (
                <li key={`${item.referralId}-${c.dataClass}`}>
                  {t(`privacy.dataClass.${c.dataClass}`)} · {t(`privacy.outcome.${c.outcome}`)} ·{' '}
                  {t('privacy.purge.rows', { count: c.rows })}
                </li>
              )),
            )}
          </ul>
        </div>
      )}
    </section>
  );
}

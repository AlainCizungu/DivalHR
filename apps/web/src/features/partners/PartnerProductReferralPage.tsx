import type {
  PartnerReferralPreview,
  PartnerReferralProduct,
  PartnerReferralView,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { localized } from './accounts';
import { PartnerAlert } from './PartnerBits';
import { NETWORK_FAILURE, partnerFailureOf, type PartnerFailure } from './partners';
import { ReferralNotice } from './ReferralBits';
import {
  CONSENT_VERSION,
  LANGUAGES,
  NEVER_SHARED,
  formSignature,
  formatInstant,
  requestPath,
  toPreviewBody,
  validateForm,
  type ReferralForm,
} from './referrals';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; product: PartnerReferralProduct };

type Phase =
  | { kind: 'editing' }
  | { kind: 'previewing' }
  | { kind: 'preview'; preview: PartnerReferralPreview; signature: string }
  | { kind: 'sending'; preview: PartnerReferralPreview; signature: string }
  | { kind: 'done'; referral: PartnerReferralView };

/**
 * MVP-120F: one eligible partner product, the choice of what to share, the exact disclosure
 * preview, an explicit and initially unchecked consent, and the confirmation. The preview is
 * stateless and read-only; the request is sent only after the employee agrees to exactly what the
 * preview shows. Changing any input discards the preview. A refusal keeps what the employee typed.
 * Nothing here is kept in the address bar, browser storage or a log.
 */
export function PartnerProductReferralPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const { productId = '' } = useParams();
  const idempotency = useIdempotencyKey();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [form, setForm] = useState<ReferralForm>({
    language: i18n.language.startsWith('fr') ? 'fr' : 'en',
    includePhone: false,
    phone: '',
    includeOrganizationName: false,
  });
  const [errors, setErrors] = useState<{ phone?: 'REQUIRED' | 'FORMAT' }>({});
  const [phase, setPhase] = useState<Phase>({ kind: 'editing' });
  const [consent, setConsent] = useState(false);
  const [consentMissing, setConsentMissing] = useState(false);
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const busy = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const previewRef = useRef<HTMLHeadingElement>(null);
  const doneRef = useRef<HTMLHeadingElement>(null);
  const phoneRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let active = true;
    core
      .GET('/me/partner-products/{productId}', {
        params: { path: { productId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', product: data }
              : { kind: 'failed', failure: partnerFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, productId]);

  const change = (next: Partial<ReferralForm>) => {
    setForm((current) => ({ ...current, ...next }));
    // any edit makes the earlier preview a different request: it and its consent are discarded
    if (phase.kind === 'preview') {
      setPhase({ kind: 'editing' });
      setConsent(false);
      setConsentMissing(false);
    }
    setErrors({});
    setFailure(null);
  };

  const fail = (f: PartnerFailure) => {
    setFailure(f);
    requestAnimationFrame(() => alertRef.current?.focus());
  };

  const review = async () => {
    if (busy.current) return;
    const invalid = validateForm(form);
    if (invalid.phone) {
      setErrors(invalid);
      requestAnimationFrame(() => phoneRef.current?.focus());
      return;
    }
    busy.current = true;
    setFailure(null);
    setPhase({ kind: 'previewing' });
    try {
      const { data, error, response } = await core.POST('/me/partner-referrals/preview', {
        body: toPreviewBody(productId, form),
      });
      if (data) {
        setConsent(false);
        setConsentMissing(false);
        setPhase({ kind: 'preview', preview: data, signature: formSignature(productId, form) });
        requestAnimationFrame(() => previewRef.current?.focus());
      } else {
        setPhase({ kind: 'editing' });
        fail(partnerFailureOf(response, error));
      }
    } catch {
      setPhase({ kind: 'editing' });
      fail(NETWORK_FAILURE);
    } finally {
      busy.current = false;
    }
  };

  const send = async () => {
    if (phase.kind !== 'preview' || busy.current) return;
    if (!consent) {
      setConsentMissing(true);
      return;
    }
    const { preview, signature } = phase;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body = {
      ...toPreviewBody(productId, form),
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      consent: { version: CONSENT_VERSION as 'PR-REFERRAL-1', affirmed: true },
    };
    busy.current = true;
    setFailure(null);
    setPhase({ kind: 'sending', preview, signature });
    try {
      const { data, error, response } = await core.POST('/me/partner-referrals', {
        params: { header: { 'Idempotency-Key': idempotency.keyFor(body) } },
        body,
      });
      if (data) {
        idempotency.reset();
        setConsent(false);
        setPhase({ kind: 'done', referral: data });
        requestAnimationFrame(() => doneRef.current?.focus());
      } else {
        const f = partnerFailureOf(response, error);
        if (
          f.code === 'PARTNER_REFERRAL_PREVIEW_EXPIRED' ||
          f.code === 'PARTNER_REFERRAL_PREVIEW_STALE' ||
          f.code === 'PARTNER_REFERRAL_IDEMPOTENCY_REUSED'
        ) {
          idempotency.reset();
          setConsent(false);
          setPhase({ kind: 'editing' });
        } else {
          setPhase({ kind: 'preview', preview, signature });
        }
        fail(f);
      }
    } catch {
      setPhase({ kind: 'preview', preview, signature });
      fail(NETWORK_FAILURE);
    } finally {
      busy.current = false;
    }
  };

  const fieldsBusy = phase.kind === 'previewing' || phase.kind === 'sending';

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="partner-referral-page">
      {load.kind === 'loading' && (
        <p role="status" data-testid="partner-referral-loading">
          {t('referrals.products.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <>
          <PageHeader titleId={`${ids}-title`} title={t('referrals.product.unavailableTitle')} />
          <PartnerAlert failure={load.failure} testId="partner-referral-failure" />
          <p>
            <Link to="/me/partner-products">{t('referrals.product.back')}</Link>
          </p>
        </>
      )}
      {load.kind === 'ready' && phase.kind === 'done' && (
        <div data-testid="referral-confirmation">
          <PageHeader title={t('referrals.done.title')} titleId={`${ids}-title`} />
          <h2 ref={doneRef} tabIndex={-1}>
            {t('referrals.done.heading')}
          </h2>
          <dl className="detail-list">
            <dt>{t('referrals.done.reference')}</dt>
            <dd data-testid="referral-reference">{phase.referral.referralId}</dd>
            <dt>{t('referrals.done.sentAt')}</dt>
            <dd>{formatInstant(phase.referral.createdAt, i18n.language)}</dd>
          </dl>
          <p>{t('referrals.done.nextStep')}</p>
          <ReferralNotice />
          <p>
            <Link
              className="button"
              to={requestPath(phase.referral.referralId)}
              data-testid="referral-view"
            >
              {t('referrals.done.view')}
            </Link>{' '}
            <Link to="/me/partner-products">{t('referrals.done.back')}</Link>
          </p>
        </div>
      )}
      {load.kind === 'ready' && phase.kind !== 'done' && (
        <>
          <PageHeader
            titleId={`${ids}-title`}
            title={localized(load.product.product.name, i18n.language)}
            description={`${load.product.product.partnerDisplayName} · ${t(`accounts.productType.${load.product.product.productType}`)}`}
          />
          <p>
            <Link to="/me/partner-products">{t('referrals.product.back')}</Link>
          </p>
          <div className="card" data-testid="partner-referral-product">
            <p>{localized(load.product.product.summary, i18n.language)}</p>
            <dl className="detail-list">
              <dt>{t('referrals.product.categories')}</dt>
              <dd>{load.product.categories.map((c) => t(`partners.category.${c}`)).join(' · ')}</dd>
              <dt>{t('catalogue.eligibility')}</dt>
              <dd>{localized(load.product.product.eligibility, i18n.language)}</dd>
              <dt>{t('catalogue.disclosure')}</dt>
              <dd>{localized(load.product.product.disclosure, i18n.language)}</dd>
            </dl>
            <p className="muted" data-testid="partner-referral-category-note">
              {t('referrals.product.categoryNote')}
            </p>
          </div>
          <ReferralNotice />

          {failure && (
            <PartnerAlert failure={failure} testId="partner-referral-failure" alertRef={alertRef} />
          )}

          <form
            noValidate
            aria-labelledby={`${ids}-form`}
            onSubmit={(event) => {
              event.preventDefault();
              void review();
            }}
          >
            <h2 id={`${ids}-form`}>{t('referrals.form.title')}</h2>
            <fieldset className="field" disabled={fieldsBusy}>
              <legend>{t('referrals.form.language')}</legend>
              {LANGUAGES.map((language) => (
                <label key={language}>
                  <input
                    type="radio"
                    name={`${ids}-language`}
                    value={language}
                    checked={form.language === language}
                    onChange={() => {
                      change({ language });
                    }}
                  />{' '}
                  {t(`referrals.form.lang.${language}`)}
                </label>
              ))}
            </fieldset>
            <div className="field">
              <label>
                <input
                  type="checkbox"
                  checked={form.includePhone}
                  disabled={fieldsBusy}
                  onChange={(event) => {
                    change({ includePhone: event.target.checked });
                  }}
                  data-testid="include-phone"
                />{' '}
                {t('referrals.form.includePhone')}
              </label>
            </div>
            {form.includePhone && (
              <div className="field">
                <label htmlFor={`${ids}-phone`}>{t('referrals.form.phone')}</label>
                <input
                  id={`${ids}-phone`}
                  ref={phoneRef}
                  type="tel"
                  inputMode="tel"
                  autoComplete="tel"
                  value={form.phone}
                  disabled={fieldsBusy}
                  aria-invalid={errors.phone ? 'true' : undefined}
                  aria-describedby={`${ids}-phone-help${errors.phone ? ` ${ids}-phone-error` : ''}`}
                  onChange={(event) => {
                    change({ phone: event.target.value });
                  }}
                  data-testid="phone"
                />
                <p id={`${ids}-phone-help`} className="muted">
                  {t('referrals.form.phoneHelp')}
                </p>
                {errors.phone && (
                  <p id={`${ids}-phone-error`} className="field-error" data-testid="phone-error">
                    {t(
                      `referrals.form.phone${errors.phone === 'REQUIRED' ? 'Required' : 'Format'}`,
                    )}
                  </p>
                )}
              </div>
            )}
            <div className="field">
              <label>
                <input
                  type="checkbox"
                  checked={form.includeOrganizationName}
                  disabled={fieldsBusy}
                  onChange={(event) => {
                    change({ includeOrganizationName: event.target.checked });
                  }}
                  data-testid="include-organization"
                />{' '}
                {t('referrals.form.includeOrganization')}
              </label>
            </div>
            <div className="button-row">
              <button
                type="submit"
                className="button"
                disabled={fieldsBusy}
                data-testid="referral-review"
              >
                {phase.kind === 'previewing'
                  ? t('referrals.form.reviewing')
                  : t('referrals.form.review')}
              </button>
            </div>
          </form>

          {(phase.kind === 'preview' || phase.kind === 'sending') && (
            <PreviewPanel
              preview={phase.preview}
              headingRef={previewRef}
              consent={consent}
              consentMissing={consentMissing}
              sending={phase.kind === 'sending'}
              partner={load.product.product.partnerDisplayName}
              product={localized(load.product.product.name, i18n.language)}
              onConsent={(value) => {
                setConsent(value);
                if (value) setConsentMissing(false);
              }}
              onSend={() => void send()}
            />
          )}
        </>
      )}
    </section>
  );
}

function PreviewPanel({
  preview,
  headingRef,
  consent,
  consentMissing,
  sending,
  partner,
  product,
  onConsent,
  onSend,
}: {
  preview: PartnerReferralPreview;
  headingRef: React.Ref<HTMLHeadingElement>;
  consent: boolean;
  consentMissing: boolean;
  sending: boolean;
  partner: string;
  product: string;
  onConsent: (value: boolean) => void;
  onSend: () => void;
}) {
  const { t, i18n } = useTranslation();
  const ids = useId();
  const blocked = preview.blockers.length > 0;
  return (
    <section
      aria-labelledby={`${ids}-title`}
      className="card"
      data-testid="referral-preview"
      data-blocked={blocked ? 'true' : 'false'}
    >
      <h2 id={`${ids}-title`} ref={headingRef} tabIndex={-1}>
        {t('referrals.preview.title')}
      </h2>
      {blocked ? (
        <ul data-testid="referral-blockers" role="alert">
          {preview.blockers.map((blocker) => (
            <li key={blocker}>{t(`referrals.blocker.${blocker}`)}</li>
          ))}
        </ul>
      ) : (
        <>
          <p>{t('referrals.preview.intro', { partner })}</p>
          <dl className="detail-list" data-testid="referral-fields">
            {preview.fields.map((field) => (
              <div key={field.field} data-testid="referral-field" data-field={field.field}>
                <dt>{t(`referrals.field.${field.field}`)}</dt>
                <dd>
                  {field.assignedAtSubmission || field.value === null ? (
                    <i>{t('referrals.preview.assigned')}</i>
                  ) : (
                    <span data-testid="referral-field-value">{field.value}</span>
                  )}
                  <span className="muted">
                    {' '}
                    ({t(`referrals.source.${field.source}`)} ·{' '}
                    {t(`referrals.purpose.${field.purpose}`)})
                  </span>
                </dd>
              </div>
            ))}
          </dl>
          <h3>{t('referrals.preview.neverSharedTitle')}</h3>
          <ul data-testid="referral-never-shared">
            {NEVER_SHARED.filter((item) => preview.neverShared.includes(item)).map((item) => (
              <li key={item}>{t(`referrals.neverShared.${item}`)}</li>
            ))}
          </ul>
          {preview.expiresAt && (
            <p className="muted">
              {t('referrals.preview.expires', {
                time: formatInstant(preview.expiresAt, i18n.language),
              })}
            </p>
          )}
          <fieldset className="field" data-testid="referral-consent">
            <legend>{t('referrals.consent.legend')}</legend>
            <label>
              <input
                type="checkbox"
                checked={consent}
                disabled={sending}
                aria-describedby={consentMissing ? `${ids}-consent-error` : undefined}
                aria-invalid={consentMissing ? 'true' : undefined}
                onChange={(event) => {
                  onConsent(event.target.checked);
                }}
                data-testid="referral-consent-checkbox"
              />{' '}
              {t('referrals.consent.text', { partner, product })}
            </label>
            <p className="muted">{t('referrals.consent.version', { version: CONSENT_VERSION })}</p>
            {consentMissing && (
              <p id={`${ids}-consent-error`} className="field-error" role="alert">
                {t('referrals.consent.required')}
              </p>
            )}
          </fieldset>
          <div className="button-row">
            <button
              type="button"
              className="button"
              disabled={sending}
              onClick={onSend}
              data-testid="referral-send"
            >
              {sending ? t('referrals.consent.sending') : t('referrals.consent.send')}
            </button>
          </div>
        </>
      )}
    </section>
  );
}

import type { PartnerApplicationReceipt, PartnerLanguage } from '@divalhr/api-client';
import { useCallback, useId, useMemo, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { CheckGroup, Field, RadioGroup } from './PartnerFields';
import { clearReceipt, loadReceipt, saveReceipt } from './partnerReceipt';
import {
  API_AVAILABILITY,
  AUTOCOMPLETE,
  CAPABILITIES,
  CATEGORIES,
  MAX_OPERATION,
  countryOptions as buildCountryOptions,
  FIELD_ORDER,
  LANGUAGES,
  MAX_ADDITIONAL,
  NETWORK_FAILURE,
  emptyValues,
  fieldErrorsFromProblem,
  isRegulated,
  partnerFailureOf,
  toSubmission,
  validate,
  type ApplyErrors,
  type ApplyField,
  type ApplyValues,
  type PartnerFailure,
} from './partners';

type Phase =
  | { kind: 'editing' }
  | { kind: 'submitting' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'done'; receipt: PartnerApplicationReceipt };

function localeOf(language: string): PartnerLanguage {
  return language.startsWith('fr') ? 'fr' : 'en';
}

/**
 * MVP-120D: the public partner application. It collects the fixed contract fields, keeps what was
 * typed across ordinary validation errors, and ends on a confirmation that shows only the opaque
 * reference, the submission time and the next step. Only the safe receipt is kept in
 * sessionStorage (R216-4), and the hidden honeypot is read from the DOM at submit time only: it is never state and never shown.
 */
export function PartnerApplyPage() {
  const { t, i18n } = useTranslation();
  const { publicCore } = useApi();
  const ids = useId();
  const locale = localeOf(i18n.resolvedLanguage ?? i18n.language);
  const [values, setValues] = useState<ApplyValues>(() => emptyValues(locale));
  const [errors, setErrors] = useState<ApplyErrors>({});
  const [phase, setPhase] = useState<Phase>(() => {
    const stored = loadReceipt();
    return stored ? { kind: 'done', receipt: stored } : { kind: 'editing' };
  });
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle');
  const summaryRef = useRef<HTMLDivElement>(null);
  const doneRef = useRef<HTMLHeadingElement>(null);
  const honeypotRef = useRef<HTMLInputElement>(null);
  const submitting = useRef(false);
  const idempotency = useIdempotencyKey();

  const fid = (field: string) => `${ids}-${field}`;
  const label = (field: ApplyField) => t(`partners.apply.fields.${field}.label`);
  const help = (field: ApplyField) => t(`partners.apply.fields.${field}.help`);
  const message = (field: ApplyField) =>
    errors[field] ? t(`partners.apply.validation.${errors[field]}`) : null;
  const update = (patch: Partial<ApplyValues>) => {
    setValues((current) => ({ ...current, ...patch }));
  };
  const showErrors = (next: ApplyErrors) => {
    setErrors(next);
    requestAnimationFrame(() => summaryRef.current?.focus());
  };

  const countryOptions = buildCountryOptions(locale);
  const operationFull = values.countriesOfOperation.length >= MAX_OPERATION;
  // 249 options: keep the lists and the registration <option> elements stable between keystrokes.
  const operationOptions = useMemo(
    () => countryOptions.map((option) => ({ ...option, disabled: operationFull })),
    [countryOptions, operationFull],
  );
  const registrationOptions = useMemo(
    () =>
      countryOptions.map((option) => (
        <option key={option.value} value={option.value}>
          {option.label}
        </option>
      )),
    [countryOptions],
  );
  const setOperation = useCallback((next: string[]) => {
    setValues((current) => ({ ...current, countriesOfOperation: next }));
  }, []);
  const regulated = isRegulated(values.primaryCategory, values.additionalCategories);

  const onSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting.current) return;
    const clientErrors = validate(values);
    if (Object.keys(clientErrors).length > 0) {
      showErrors(clientErrors);
      return;
    }
    setErrors({});
    const body = { ...toSubmission(values, locale), faxNumber: honeypotRef.current?.value ?? '' };
    submitting.current = true;
    setPhase({ kind: 'submitting' });
    try {
      const { data, error, response } = await publicCore.POST('/public/partner-applications', {
        params: { header: { 'Idempotency-Key': idempotency.keyFor(body) } },
        body,
      });
      if (data) {
        idempotency.reset();
        setValues(emptyValues(locale));
        saveReceipt(data);
        setPhase({ kind: 'done', receipt: data });
        requestAnimationFrame(() => doneRef.current?.focus());
        return;
      }
      const failure = partnerFailureOf(response, error);
      if (failure.code === 'PARTNER_APPLICATION_IDEMPOTENCY_REUSED') idempotency.reset();
      const fields =
        failure.code === 'PARTNER_APPLICATION_CONSENT_REQUIRED'
          ? { acknowledgment: 'REQUIRED' }
          : fieldErrorsFromProblem(error);
      setPhase({ kind: 'failed', failure });
      showErrors(fields ?? {});
    } catch {
      setPhase({ kind: 'failed', failure: NETWORK_FAILURE });
      requestAnimationFrame(() => summaryRef.current?.focus());
    } finally {
      submitting.current = false;
    }
  };

  const copyReference = async (reference: string) => {
    try {
      await navigator.clipboard.writeText(reference);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
  };

  const startAnother = () => {
    clearReceipt();
    idempotency.reset();
    setValues(emptyValues(locale));
    setErrors({});
    setCopy('idle');
    setPhase({ kind: 'editing' });
    requestAnimationFrame(() => summaryRef.current?.focus());
  };

  if (phase.kind === 'done') {
    return (
      <section aria-labelledby={fid('done')} data-testid="partner-confirmation">
        <h1 id={fid('done')} ref={doneRef} tabIndex={-1}>
          {t('partners.confirmation.title')}
        </h1>
        <p role="status">{t('partners.confirmation.body')}</p>
        <dl className="card summary">
          <dt>{t('partners.confirmation.reference')}</dt>
          <dd data-testid="partner-reference">{phase.receipt.reference}</dd>
          <dt>{t('partners.confirmation.submittedAt')}</dt>
          <dd data-testid="partner-submitted-at">
            <time dateTime={phase.receipt.submittedAt}>
              {new Intl.DateTimeFormat(i18n.language, {
                dateStyle: 'long',
                timeStyle: 'short',
              }).format(new Date(phase.receipt.submittedAt))}
            </time>
          </dd>
        </dl>
        <p data-testid="partner-next-step">
          {t(`partners.confirmation.nextStep.${phase.receipt.nextStep}`)}
        </p>
        <p className="muted">{t('partners.confirmation.sessionNote')}</p>
        <div className="button-row">
          <button
            type="button"
            className="button"
            data-testid="partner-copy-reference"
            onClick={() => void copyReference(phase.receipt.reference)}
          >
            {t('partners.confirmation.copy')}
          </button>
          <button
            type="button"
            className="button button--secondary"
            data-testid="partner-start-another"
            onClick={startAnother}
          >
            {t('partners.confirmation.startAnother')}
          </button>
        </div>
        <p role="status" data-testid="partner-copy-status">
          {copy === 'copied' && t('partners.confirmation.copied')}
          {copy === 'failed' && t('partners.confirmation.copyFailed')}
        </p>
      </section>
    );
  }

  const errorFields = FIELD_ORDER.filter((field) => errors[field]);
  const failure = phase.kind === 'failed' ? phase.failure : null;
  const busy = phase.kind === 'submitting';
  const additionalOptions = CATEGORIES.filter((c) => c !== values.primaryCategory).map((c) => ({
    value: c,
    label: t(`partners.category.${c}`),
    disabled: values.additionalCategories.length >= MAX_ADDITIONAL,
  }));
  const text = (
    field: Exclude<
      ApplyField,
      | 'body'
      | 'countriesOfOperation'
      | 'additionalCategories'
      | 'requestedCapabilities'
      | 'supportedLanguages'
      | 'acknowledgment'
    >,
    props: {
      type?: 'text' | 'email' | 'url' | 'tel';
      autoComplete?: (typeof AUTOCOMPLETE)[keyof typeof AUTOCOMPLETE];
      maxLength: number;
      required?: boolean;
      inputMode?: 'text' | 'email' | 'url' | 'tel';
    },
  ) => (
    <Field
      id={fid(field)}
      label={label(field)}
      help={help(field)}
      error={message(field)}
      required={props.required ?? true}
    >
      {(describedBy) => (
        <input
          id={fid(field)}
          name={field}
          type={props.type ?? 'text'}
          inputMode={props.inputMode}
          autoComplete={props.autoComplete ?? 'off'}
          maxLength={props.maxLength}
          required={props.required ?? true}
          value={values[field]}
          aria-invalid={errors[field] ? true : undefined}
          aria-describedby={describedBy}
          onChange={(event) => {
            update({ [field]: event.target.value });
          }}
        />
      )}
    </Field>
  );

  return (
    <section aria-labelledby={fid('title')} data-testid="partner-apply-page">
      <h1 id={fid('title')}>{t('partners.apply.title')}</h1>
      <p className="muted">{t('partners.apply.description')}</p>
      <p className="card" data-testid="partner-boundary-notice">
        {t('partners.apply.boundary')}
      </p>

      <div ref={summaryRef} tabIndex={-1} className="form-summary" data-testid="form-summary">
        {errorFields.length > 0 && (
          <div role="alert" className="error-summary" data-testid="partner-error-summary">
            <p>{t('partners.apply.errorSummary')}</p>
            <ul>
              {errorFields.map((field) => (
                <li key={field}>
                  {field === 'body' ? (
                    message(field)
                  ) : (
                    <a href={`#${fid(field)}`}>
                      {label(field)}: {message(field)}
                    </a>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}
        {failure && (
          <div
            role="alert"
            className="error-summary"
            data-testid="partner-failure"
            data-kind={failure.kind}
            data-code={failure.code}
          >
            <p>{t(failure.messageKey)}</p>
            {failure.retryAfter !== undefined && (
              <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
            )}
            {failure.correlationId && (
              <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
            )}
          </div>
        )}
      </div>

      <form noValidate onSubmit={(event) => void onSubmit(event)} aria-busy={busy}>
        <p className="muted">{t('partners.apply.requiredNote')}</p>

        <h2>{t('partners.apply.sections.organization')}</h2>
        {text('legalName', { maxLength: 200, autoComplete: AUTOCOMPLETE.organization })}
        {text('tradingName', { maxLength: 200, required: false })}
        <Field
          id={fid('countryOfRegistration')}
          label={label('countryOfRegistration')}
          help={help('countryOfRegistration')}
          error={message('countryOfRegistration')}
          required
        >
          {(describedBy) => (
            <select
              id={fid('countryOfRegistration')}
              name="countryOfRegistration"
              required
              value={values.countryOfRegistration}
              aria-invalid={errors.countryOfRegistration ? true : undefined}
              aria-describedby={describedBy}
              onChange={(event) => {
                update({ countryOfRegistration: event.target.value });
              }}
            >
              <option value="">{t('partners.apply.select')}</option>
              {registrationOptions}
            </select>
          )}
        </Field>
        <CheckGroup
          id={fid('countriesOfOperation')}
          legend={label('countriesOfOperation')}
          help={t('partners.apply.fields.countriesOfOperation.help', { max: MAX_OPERATION })}
          error={message('countriesOfOperation')}
          scroll
          options={operationOptions}
          selected={values.countriesOfOperation}
          onChange={setOperation}
        />
        {text('registrationReference', { maxLength: 100 })}

        <h2>{t('partners.apply.sections.activity')}</h2>
        <Field
          id={fid('primaryCategory')}
          label={label('primaryCategory')}
          help={help('primaryCategory')}
          error={message('primaryCategory')}
          required
        >
          {(describedBy) => (
            <select
              id={fid('primaryCategory')}
              name="primaryCategory"
              required
              value={values.primaryCategory}
              aria-invalid={errors.primaryCategory ? true : undefined}
              aria-describedby={describedBy}
              onChange={(event) => {
                const primaryCategory = event.target.value as ApplyValues['primaryCategory'];
                update({
                  primaryCategory,
                  additionalCategories: values.additionalCategories.filter(
                    (category) => category !== primaryCategory,
                  ),
                });
              }}
            >
              <option value="">{t('partners.apply.select')}</option>
              {CATEGORIES.map((category) => (
                <option key={category} value={category}>
                  {t(`partners.category.${category}`)}
                </option>
              ))}
            </select>
          )}
        </Field>
        <CheckGroup
          id={fid('additionalCategories')}
          legend={label('additionalCategories')}
          help={t('partners.apply.fields.additionalCategories.help', { max: MAX_ADDITIONAL })}
          error={message('additionalCategories')}
          options={additionalOptions}
          selected={values.additionalCategories}
          onChange={(next) => {
            update({ additionalCategories: next as ApplyValues['additionalCategories'] });
          }}
        />
        {regulated && (
          <div data-testid="partner-regulatory-fields">
            <p className="card">{t('partners.apply.regulatoryNote')}</p>
            {text('regulatorName', { maxLength: 200 })}
            {text('licenceReference', { maxLength: 100 })}
          </div>
        )}
        <CheckGroup
          id={fid('requestedCapabilities')}
          legend={label('requestedCapabilities')}
          help={help('requestedCapabilities')}
          error={message('requestedCapabilities')}
          options={CAPABILITIES.map((c) => ({ value: c, label: t(`partners.capability.${c}`) }))}
          selected={values.requestedCapabilities}
          onChange={(next) => {
            update({ requestedCapabilities: next as ApplyValues['requestedCapabilities'] });
          }}
        />
        <Field
          id={fid('serviceSummary')}
          label={label('serviceSummary')}
          help={help('serviceSummary')}
          error={message('serviceSummary')}
          required
        >
          {(describedBy) => (
            <textarea
              id={fid('serviceSummary')}
              name="serviceSummary"
              rows={5}
              maxLength={1000}
              required
              value={values.serviceSummary}
              aria-invalid={errors.serviceSummary ? true : undefined}
              aria-describedby={describedBy}
              onChange={(event) => {
                update({ serviceSummary: event.target.value });
              }}
            />
          )}
        </Field>
        {text('website', {
          type: 'url',
          inputMode: 'url',
          maxLength: 300,
          autoComplete: AUTOCOMPLETE.url,
        })}
        <RadioGroup
          id={fid('apiAvailability')}
          name="apiAvailability"
          legend={label('apiAvailability')}
          help={help('apiAvailability')}
          error={message('apiAvailability')}
          options={API_AVAILABILITY.map((a) => ({ value: a, label: t(`partners.api.${a}`) }))}
          value={values.apiAvailability}
          onChange={(next) => {
            update({ apiAvailability: next as ApplyValues['apiAvailability'] });
          }}
        />
        <CheckGroup
          id={fid('supportedLanguages')}
          legend={label('supportedLanguages')}
          help={help('supportedLanguages')}
          error={message('supportedLanguages')}
          grid={false}
          options={LANGUAGES.map((l) => ({ value: l, label: t(`language.${l}`) }))}
          selected={values.supportedLanguages}
          onChange={(next) => {
            update({ supportedLanguages: next as ApplyValues['supportedLanguages'] });
          }}
        />

        <h2>{t('partners.apply.sections.contact')}</h2>
        {text('contactName', { maxLength: 150, autoComplete: AUTOCOMPLETE.name })}
        {text('contactRole', { maxLength: 120, autoComplete: AUTOCOMPLETE.title })}
        {text('businessEmail', {
          type: 'email',
          inputMode: 'email',
          maxLength: 254,
          autoComplete: AUTOCOMPLETE.email,
        })}
        {text('businessPhone', {
          type: 'tel',
          inputMode: 'tel',
          maxLength: 16,
          autoComplete: AUTOCOMPLETE.tel,
          required: false,
        })}
        {text('privacyContactEmail', { type: 'email', inputMode: 'email', maxLength: 254 })}

        <h2>{t('partners.apply.sections.acknowledgment')}</h2>
        <section
          className="card"
          aria-labelledby={fid('notice-title')}
          data-testid="partner-notice"
        >
          <h3 id={fid('notice-title')}>{t('partners.notice.title')}</h3>
          <p>{t('partners.notice.body')}</p>
        </section>
        <fieldset
          className="field"
          aria-describedby={errors.acknowledgment ? fid('acknowledgment-error') : undefined}
        >
          <legend className="visually-hidden">{label('acknowledgment')}</legend>
          <label className="choice">
            <input
              id={fid('acknowledgment')}
              type="checkbox"
              name="acknowledgment"
              checked={values.acknowledgment}
              aria-invalid={errors.acknowledgment ? true : undefined}
              onChange={(event) => {
                update({ acknowledgment: event.target.checked });
              }}
            />
            {t('partners.apply.fields.acknowledgment.label')}
          </label>
          <p className="field__help">{t('partners.apply.fields.acknowledgment.help')}</p>
          {errors.acknowledgment && (
            <p id={fid('acknowledgment-error')} className="field__error">
              {message('acknowledgment')}
            </p>
          )}
        </fieldset>

        {/* Honeypot: off-screen, inert, out of the tab order and the accessibility tree. Read from
            the DOM only when submitting; never state, never redisplayed. */}
        <div className="partner-trap" aria-hidden="true" inert data-testid="partner-trap">
          <input
            ref={honeypotRef}
            type="text"
            name="faxNumber"
            tabIndex={-1}
            autoComplete="off"
            defaultValue=""
          />
        </div>

        <button type="submit" className="button" disabled={busy}>
          {t(busy ? 'partners.apply.submitting' : 'partners.apply.submit')}
        </button>
      </form>
    </section>
  );
}

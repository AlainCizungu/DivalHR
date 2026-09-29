import type { Organization, Problem } from '@divalhr/api-client';
import { useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import {
  COUNTRIES,
  CURRENCIES_BY_COUNTRY,
  FIELD_ORDER,
  LOCALES,
  TIMEZONES_BY_COUNTRY,
  fieldErrorsFromProblem,
  toPayload,
  validate,
  type FieldErrors,
  type FieldName,
  type FormValues,
} from './organizationForm';

type Phase =
  | { kind: 'editing' }
  | { kind: 'submitting' }
  | { kind: 'failed'; messageKey: string }
  | { kind: 'created'; organization: Organization };

const initialValues = (): FormValues => ({
  name: '',
  countryCode: 'CD',
  defaultLocale: 'fr',
  timezone: 'Africa/Kinshasa',
  currencies: ['CDF'],
});

function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

export function CreateOrganizationPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [values, setValues] = useState<FormValues>(initialValues);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [phase, setPhase] = useState<Phase>({ kind: 'editing' });
  const summaryRef = useRef<HTMLDivElement>(null);
  const successRef = useRef<HTMLHeadingElement>(null);
  // One idempotency key per distinct payload: retrying the same payload reuses it, any edit
  // produces a new key so the server never sees a reused key with different content.
  const attempt = useRef<{ payload: string; key: string } | null>(null);

  const fieldId = (field: string) => `${ids}-${field}`;
  const errorId = (field: FieldName) => `${ids}-${field}-error`;
  const helpId = (field: string) => `${ids}-${field}-help`;
  const describedBy = (field: FieldName, help: string) =>
    [helpId(help), errors[field] ? errorId(field) : null].filter(Boolean).join(' ');
  const message = (field: FieldName) =>
    errors[field] ? t(`createOrganization.validation.${field}.${errors[field]}`) : null;

  const update = (patch: Partial<FormValues>) => {
    setValues((current) => ({ ...current, ...patch }));
    if (phase.kind === 'failed') setPhase({ kind: 'editing' });
  };

  const showErrors = (next: FieldErrors) => {
    setErrors(next);
    requestAnimationFrame(() => summaryRef.current?.focus());
  };

  const onSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (phase.kind === 'submitting') return;
    const clientErrors = validate(values);
    if (Object.keys(clientErrors).length > 0) {
      showErrors(clientErrors);
      return;
    }
    setErrors({});
    const payload = toPayload(values);
    const serialized = JSON.stringify(payload);
    if (attempt.current?.payload !== serialized) {
      attempt.current = { payload: serialized, key: newKey() };
    }
    setPhase({ kind: 'submitting' });
    try {
      const { data, error, response } = await core.POST('/organizations', {
        params: { header: { 'Idempotency-Key': attempt.current.key } },
        body: payload,
      });
      if (data) {
        attempt.current = null;
        setPhase({ kind: 'created', organization: data });
        requestAnimationFrame(() => successRef.current?.focus());
        return;
      }
      const problem = error as Partial<Problem> | undefined;
      const fieldErrors = problem ? fieldErrorsFromProblem(problem) : null;
      if (fieldErrors) {
        setPhase({ kind: 'editing' });
        showErrors(fieldErrors);
        return;
      }
      const key =
        response.status === 403
          ? 'createOrganization.unauthorized'
          : problem?.code
            ? `errors.${problem.code}`
            : 'errors.generic';
      setPhase({ kind: 'failed', messageKey: key });
      requestAnimationFrame(() => summaryRef.current?.focus());
    } catch {
      setPhase({ kind: 'failed', messageKey: 'errors.network' });
      requestAnimationFrame(() => summaryRef.current?.focus());
    }
  };

  if (phase.kind === 'created') {
    const org = phase.organization;
    return (
      <section aria-labelledby={`${ids}-success`} data-testid="organization-created">
        <h1 id={`${ids}-success`} ref={successRef} tabIndex={-1}>
          {t('createOrganization.success.title')}
        </h1>
        <p role="status">{t('createOrganization.success.body', { name: org.name })}</p>
        <dl className="card summary">
          <dt>{t('createOrganization.name.label')}</dt>
          <dd data-testid="created-name">{org.name}</dd>
          <dt>{t('createOrganization.country.label')}</dt>
          <dd>{t(`country.${org.countryCode}`)}</dd>
          <dt>{t('createOrganization.locale.legend')}</dt>
          <dd>{t(`language.${org.defaultLocale}`)}</dd>
          <dt>{t('createOrganization.timezone.label')}</dt>
          <dd>{t(`timezones.${org.timezone}`)}</dd>
          <dt>{t('createOrganization.currencies.legend')}</dt>
          <dd>{org.currencies.map((c) => t(`currency.${c}`)).join(', ')}</dd>
          <dt>{t('createOrganization.success.status')}</dt>
          <dd>{t(`orgStatus.${org.status}`)}</dd>
          <dt>{t('createOrganization.success.createdAt')}</dt>
          <dd>
            {new Intl.DateTimeFormat(i18n.language, {
              dateStyle: 'long',
              timeStyle: 'short',
            }).format(new Date(org.createdAt))}
          </dd>
          <dt>{t('createOrganization.success.reference')}</dt>
          <dd className="muted" data-testid="created-id">
            {org.id}
          </dd>
        </dl>
        <button
          type="button"
          className="button"
          onClick={() => {
            setValues(initialValues());
            setPhase({ kind: 'editing' });
          }}
        >
          {t('createOrganization.success.another')}
        </button>
      </section>
    );
  }

  const country = values.countryCode as (typeof COUNTRIES)[number];
  const zones = TIMEZONES_BY_COUNTRY[country];
  const currencies = CURRENCIES_BY_COUNTRY[country];
  const errorFields = FIELD_ORDER.filter((field) => errors[field]);
  const submitting = phase.kind === 'submitting';

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('createOrganization.title')}</h1>
      <p className="muted">{t('createOrganization.description')}</p>

      <div ref={summaryRef} tabIndex={-1} className="form-summary" data-testid="form-summary">
        {errorFields.length > 0 && (
          <div role="alert" className="error-summary">
            <p>{t('createOrganization.errorSummary')}</p>
            <ul>
              {errorFields.map((field) => (
                <li key={field}>
                  <a href={`#${fieldId(field)}`}>{message(field)}</a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {phase.kind === 'failed' && (
          <p role="alert" className="error-summary" data-testid="form-error">
            {t(phase.messageKey)}
          </p>
        )}
      </div>

      <form noValidate onSubmit={(event) => void onSubmit(event)} aria-busy={submitting}>
        <p className="muted">{t('createOrganization.requiredNote')}</p>

        <div className="field">
          <label htmlFor={fieldId('name')}>{t('createOrganization.name.label')}</label>
          <p id={helpId('name')} className="field__help">
            {t('createOrganization.name.help')}
          </p>
          <input
            id={fieldId('name')}
            name="name"
            type="text"
            autoComplete="organization"
            required
            maxLength={200}
            value={values.name}
            aria-invalid={errors.name ? true : undefined}
            aria-describedby={describedBy('name', 'name')}
            onChange={(event) => {
              update({ name: event.target.value });
            }}
          />
          {errors.name && (
            <p id={errorId('name')} className="field__error">
              {message('name')}
            </p>
          )}
        </div>

        <div className="field">
          <label htmlFor={fieldId('countryCode')}>{t('createOrganization.country.label')}</label>
          <p id={helpId('country')} className="field__help">
            {t('createOrganization.country.help')}
          </p>
          <select
            id={fieldId('countryCode')}
            name="countryCode"
            required
            value={values.countryCode}
            aria-invalid={errors.countryCode ? true : undefined}
            aria-describedby={describedBy('countryCode', 'country')}
            onChange={(event) => {
              update({ countryCode: event.target.value });
            }}
          >
            {COUNTRIES.map((code) => (
              <option key={code} value={code}>
                {t(`country.${code}`)}
              </option>
            ))}
          </select>
          {errors.countryCode && (
            <p id={errorId('countryCode')} className="field__error">
              {message('countryCode')}
            </p>
          )}
        </div>

        <fieldset
          className="field"
          id={fieldId('defaultLocale')}
          aria-invalid={errors.defaultLocale ? true : undefined}
          aria-describedby={describedBy('defaultLocale', 'locale')}
        >
          <legend>{t('createOrganization.locale.legend')}</legend>
          <p id={helpId('locale')} className="field__help">
            {t('createOrganization.locale.help')}
          </p>
          {LOCALES.map((locale) => (
            <label key={locale} className="choice" lang={locale}>
              <input
                type="radio"
                name="defaultLocale"
                value={locale}
                checked={values.defaultLocale === locale}
                onChange={() => {
                  update({ defaultLocale: locale });
                }}
              />
              {t(`language.${locale}`)}
            </label>
          ))}
          {errors.defaultLocale && (
            <p id={errorId('defaultLocale')} className="field__error">
              {message('defaultLocale')}
            </p>
          )}
        </fieldset>

        <div className="field">
          <label htmlFor={fieldId('timezone')}>{t('createOrganization.timezone.label')}</label>
          <p id={helpId('timezone')} className="field__help">
            {t('createOrganization.timezone.help')}
          </p>
          <select
            id={fieldId('timezone')}
            name="timezone"
            required
            value={values.timezone}
            aria-invalid={errors.timezone ? true : undefined}
            aria-describedby={describedBy('timezone', 'timezone')}
            onChange={(event) => {
              update({ timezone: event.target.value });
            }}
          >
            {zones.map((zone) => (
              <option key={zone} value={zone}>
                {t(`timezones.${zone}`)}
              </option>
            ))}
          </select>
          {errors.timezone && (
            <p id={errorId('timezone')} className="field__error">
              {message('timezone')}
            </p>
          )}
        </div>

        <fieldset
          className="field"
          id={fieldId('currencies')}
          aria-invalid={errors.currencies ? true : undefined}
          aria-describedby={describedBy('currencies', 'currencies')}
        >
          <legend>{t('createOrganization.currencies.legend')}</legend>
          <p id={helpId('currencies')} className="field__help">
            {t('createOrganization.currencies.help')}
          </p>
          {currencies.map((currency) => (
            <label key={currency} className="choice">
              <input
                type="checkbox"
                name="currencies"
                value={currency}
                checked={values.currencies.includes(currency)}
                onChange={(event) => {
                  update({
                    currencies: event.target.checked
                      ? [...values.currencies, currency]
                      : values.currencies.filter((c) => c !== currency),
                  });
                }}
              />
              {t(`currency.${currency}`)}
            </label>
          ))}
          {errors.currencies && (
            <p id={errorId('currencies')} className="field__error">
              {message('currencies')}
            </p>
          )}
        </fieldset>

        <button type="submit" className="button" disabled={submitting}>
          {submitting ? t('createOrganization.submitting') : t('createOrganization.submit')}
        </button>
        {submitting && (
          <p role="status" className="muted">
            {t('createOrganization.submitting')}
          </p>
        )}
      </form>
    </section>
  );
}

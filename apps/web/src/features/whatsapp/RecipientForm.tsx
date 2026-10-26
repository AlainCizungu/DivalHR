import type { WhatsAppRecipient, WhatsAppSelectableNotificationType } from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  CONSENT_SOURCES,
  LANGUAGES,
  MAX_LABEL_LENGTH,
  SELECTABLE_TYPES,
  today,
  validateRecipient,
  type Failure,
  type RecipientFieldErrors,
  type RecipientValues,
} from './whatsapp';

export const EMPTY_RECIPIENT: RecipientValues = {
  label: '',
  phoneNumber: '',
  language: 'en',
  notificationTypes: [],
  consentSource: '',
  consentDate: '',
  attested: false,
};

export const recipientValues = (recipient: WhatsAppRecipient): RecipientValues => ({
  ...EMPTY_RECIPIENT,
  label: recipient.label,
  language: recipient.language,
  notificationTypes: [...recipient.notificationTypes],
});

/** Registering an opted-in recipient: number, language, notifications and the recorded consent. */
export function RecipientForm({
  initial,
  mode,
  busy,
  failure,
  onSubmit,
  onCancel,
}: {
  initial: RecipientValues;
  mode: 'create' | 'edit';
  busy: boolean;
  failure: Failure | null;
  onSubmit: (values: RecipientValues) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const ids = useId();
  const creating = mode === 'create';
  const [values, setValues] = useState<RecipientValues>(initial);
  const [confirmed, setConfirmed] = useState(false);
  const [errors, setErrors] = useState<RecipientFieldErrors>({});

  const toggle = (type: WhatsAppSelectableNotificationType, checked: boolean) => {
    setValues({
      ...values,
      notificationTypes: checked
        ? [...values.notificationTypes, type]
        : values.notificationTypes.filter((x) => x !== type),
    });
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = validateRecipient(values, { creating, confirmed, today: today() });
    setErrors(found);
    const first = Object.keys(found)[0];
    if (first) {
      document.getElementById(`${ids}-${first}`)?.focus();
      return;
    }
    onSubmit({ ...values, label: values.label.trim(), phoneNumber: values.phoneNumber.trim() });
  };

  const describe = (field: keyof RecipientFieldErrors, hint?: string) =>
    [errors[field] ? `${ids}-${field}-error` : null, hint].filter(Boolean).join(' ') || undefined;
  const error = (field: keyof RecipientFieldErrors) =>
    errors[field] && (
      <p id={`${ids}-${field}-error`} className="field__error">
        {t(errors[field])}
      </p>
    );

  return (
    <form
      noValidate
      onSubmit={submit}
      data-testid="whatsapp-recipient-form"
      aria-label={t(creating ? 'whatsapp.recipients.createTitle' : 'whatsapp.recipients.editTitle')}
    >
      {Object.keys(errors).length > 0 && (
        <div className="error-summary" role="alert">
          <p>{t('whatsapp.errors.summary')}</p>
        </div>
      )}
      {failure && (
        <div className="error-summary" role="alert" data-testid="whatsapp-failure">
          <p>{t(failure.messageKey)}</p>
          {failure.correlationId && (
            <p className="muted">{t('whatsapp.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}
      <div className="field">
        <label htmlFor={`${ids}-label`}>{t('whatsapp.recipients.form.label')}</label>
        <input
          id={`${ids}-label`}
          value={values.label}
          maxLength={MAX_LABEL_LENGTH * 2}
          autoComplete="off"
          aria-invalid={errors.label ? true : undefined}
          aria-describedby={describe('label', `${ids}-label-hint`)}
          onChange={(event) => {
            setValues({ ...values, label: event.target.value });
          }}
        />
        <p id={`${ids}-label-hint`} className="muted">
          {t('whatsapp.recipients.form.labelHint', { max: MAX_LABEL_LENGTH })}
        </p>
        {error('label')}
      </div>
      {creating ? (
        <div className="field">
          <label htmlFor={`${ids}-phoneNumber`}>{t('whatsapp.recipients.form.phone')}</label>
          <input
            id={`${ids}-phoneNumber`}
            type="tel"
            inputMode="tel"
            value={values.phoneNumber}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={errors.phoneNumber ? true : undefined}
            aria-describedby={describe('phoneNumber', `${ids}-phoneNumber-hint`)}
            onChange={(event) => {
              setValues({ ...values, phoneNumber: event.target.value });
            }}
          />
          <p id={`${ids}-phoneNumber-hint`} className="muted">
            {t('whatsapp.recipients.form.phoneHint')}
          </p>
          {error('phoneNumber')}
        </div>
      ) : (
        <p className="muted">{t('whatsapp.recipients.form.editNote')}</p>
      )}
      <div className="field">
        <label htmlFor={`${ids}-language`}>{t('whatsapp.recipients.form.language')}</label>
        <select
          id={`${ids}-language`}
          value={values.language}
          onChange={(event) => {
            setValues({ ...values, language: event.target.value as RecipientValues['language'] });
          }}
        >
          {LANGUAGES.map((language) => (
            <option key={language} value={language}>
              {t(`whatsapp.language.${language}`)}
            </option>
          ))}
        </select>
      </div>
      <fieldset
        id={`${ids}-types`}
        tabIndex={-1}
        aria-describedby={describe('types')}
        aria-invalid={errors.types ? true : undefined}
      >
        <legend>{t('whatsapp.recipients.form.types')}</legend>
        {SELECTABLE_TYPES.map((type) => (
          <div className="field" key={type}>
            <label>
              <input
                type="checkbox"
                checked={values.notificationTypes.includes(type)}
                onChange={(change) => {
                  toggle(type, change.target.checked);
                }}
              />{' '}
              {t(`whatsapp.types.${type}`)}
            </label>
          </div>
        ))}
        {error('types')}
      </fieldset>
      {creating && (
        <fieldset>
          <legend>{t('whatsapp.recipients.form.consentTitle')}</legend>
          <div className="field">
            <label htmlFor={`${ids}-consentSource`}>
              {t('whatsapp.recipients.form.consentSource')}
            </label>
            <select
              id={`${ids}-consentSource`}
              value={values.consentSource}
              aria-invalid={errors.consentSource ? true : undefined}
              aria-describedby={describe('consentSource')}
              onChange={(event) => {
                setValues({
                  ...values,
                  consentSource: event.target.value as RecipientValues['consentSource'],
                });
              }}
            >
              <option value="">{t('whatsapp.recipients.form.consentSourcePlaceholder')}</option>
              {CONSENT_SOURCES.map((source) => (
                <option key={source} value={source}>
                  {t(`whatsapp.consentSource.${source}`)}
                </option>
              ))}
            </select>
            {error('consentSource')}
          </div>
          <div className="field">
            <label htmlFor={`${ids}-consentDate`}>
              {t('whatsapp.recipients.form.consentDate')}
            </label>
            <input
              id={`${ids}-consentDate`}
              type="date"
              max={today()}
              value={values.consentDate}
              aria-invalid={errors.consentDate ? true : undefined}
              aria-describedby={describe('consentDate')}
              onChange={(event) => {
                setValues({ ...values, consentDate: event.target.value });
              }}
            />
            {error('consentDate')}
          </div>
          <div className="field">
            <label>
              <input
                id={`${ids}-attested`}
                type="checkbox"
                checked={values.attested}
                aria-describedby={describe('attested')}
                onChange={(event) => {
                  setValues({ ...values, attested: event.target.checked });
                }}
              />{' '}
              {t('whatsapp.recipients.form.consentAttest')}
            </label>
            {error('attested')}
          </div>
        </fieldset>
      )}
      <div className="field">
        <label>
          <input
            id={`${ids}-confirm`}
            type="checkbox"
            checked={confirmed}
            aria-describedby={describe('confirm')}
            onChange={(event) => {
              setConfirmed(event.target.checked);
            }}
          />{' '}
          {t('whatsapp.recipients.form.confirm')}
        </label>
        {error('confirm')}
      </div>
      <div className="actions">
        <button type="submit" className="button" disabled={busy}>
          {t(
            busy
              ? 'whatsapp.working'
              : creating
                ? 'whatsapp.recipients.form.create'
                : 'whatsapp.recipients.form.save',
          )}
        </button>
        <button type="button" className="button button--secondary" onClick={onCancel}>
          {t('whatsapp.cancel')}
        </button>
      </div>
    </form>
  );
}

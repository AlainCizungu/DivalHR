import type { WhatsAppConnector } from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  LANGUAGES,
  TEMPLATE_TYPES,
  templateKey,
  validateConnector,
  type ConnectorFieldErrors,
  type ConnectorValues,
  type Failure,
} from './whatsapp';

export const EMPTY_CONNECTOR: ConnectorValues = {
  wabaId: '',
  phoneNumberId: '',
  displayNumber: '',
  accessToken: '',
  templates: {},
};

export function valuesOf(connector: WhatsAppConnector): ConnectorValues {
  return {
    wabaId: connector.wabaId,
    phoneNumberId: connector.phoneNumberId,
    displayNumber: connector.displayNumber,
    accessToken: '',
    templates: Object.fromEntries(
      connector.templates.map((t) => [templateKey(t.notificationType, t.language), t.templateName]),
    ),
  };
}

/** Connect (with the write-only token) and edit (without it) share one accessible form. */
export function ConnectorForm({
  initial,
  mode,
  busy,
  failure,
  onSubmit,
  onCancel,
}: {
  initial: ConnectorValues;
  mode: 'create' | 'edit';
  busy: boolean;
  failure: Failure | null;
  onSubmit: (values: ConnectorValues) => void;
  onCancel?: () => void;
}) {
  const { t } = useTranslation();
  const ids = useId();
  const [values, setValues] = useState<ConnectorValues>(initial);
  const [confirmed, setConfirmed] = useState(false);
  const [errors, setErrors] = useState<ConnectorFieldErrors>({});
  const creating = mode === 'create';

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = validateConnector(values, { needsToken: creating, confirmed });
    setErrors(found);
    const first = Object.keys(found)[0];
    if (first) {
      document.getElementById(`${ids}-${first}`)?.focus();
      return;
    }
    onSubmit(values);
  };

  const describe = (field: keyof ConnectorFieldErrors, hint?: string) =>
    [errors[field] ? `${ids}-${field}-error` : null, hint].filter(Boolean).join(' ') || undefined;

  const field = (
    name: 'wabaId' | 'phoneNumberId' | 'displayNumber',
    labelKey: string,
    hintKey: string,
    extra: { inputMode?: 'numeric' | 'tel'; autoComplete?: string } = {},
  ) => (
    <div className="field">
      <label htmlFor={`${ids}-${name}`}>{t(labelKey)}</label>
      <input
        id={`${ids}-${name}`}
        value={values[name]}
        inputMode={extra.inputMode}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={errors[name] ? true : undefined}
        aria-describedby={describe(name, `${ids}-${name}-hint`)}
        onChange={(event) => {
          setValues({ ...values, [name]: event.target.value });
        }}
      />
      <p id={`${ids}-${name}-hint`} className="muted">
        {t(hintKey)}
      </p>
      {errors[name] && (
        <p id={`${ids}-${name}-error`} className="field__error">
          {t(errors[name])}
        </p>
      )}
    </div>
  );

  const identifierFields = [
    field('wabaId', 'whatsapp.connect.wabaId', 'whatsapp.connect.wabaIdHint', {
      inputMode: 'numeric',
    }),
    field('phoneNumberId', 'whatsapp.connect.phoneNumberId', 'whatsapp.connect.phoneNumberIdHint', {
      inputMode: 'numeric',
    }),
    field('displayNumber', 'whatsapp.connect.displayNumber', 'whatsapp.connect.displayNumberHint', {
      inputMode: 'tel',
    }),
  ];

  return (
    <form
      noValidate
      onSubmit={submit}
      data-testid="whatsapp-connector-form"
      aria-label={t(creating ? 'whatsapp.connect.title' : 'whatsapp.actions.edit')}
    >
      {Object.keys(errors).length > 0 && (
        <div className="error-summary" role="alert">
          <p>{t('whatsapp.errors.summary')}</p>
        </div>
      )}
      {failure && (
        <div className="error-summary" role="alert" data-testid="whatsapp-failure">
          <p>{t(failure.messageKey)}</p>
          {failure.retryAfter !== undefined && (
            <p>{t('whatsapp.retryAfter', { count: failure.retryAfter })}</p>
          )}
          {failure.correlationId && (
            <p className="muted">{t('whatsapp.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}
      {identifierFields}
      {creating && (
        <div className="field">
          <label htmlFor={`${ids}-accessToken`}>{t('whatsapp.connect.accessToken')}</label>
          <input
            id={`${ids}-accessToken`}
            type="password"
            value={values.accessToken}
            autoComplete="new-password"
            spellCheck={false}
            aria-invalid={errors.accessToken ? true : undefined}
            aria-describedby={describe('accessToken', `${ids}-accessToken-hint`)}
            onChange={(event) => {
              setValues({ ...values, accessToken: event.target.value });
            }}
          />
          <p id={`${ids}-accessToken-hint`} className="muted">
            {t('whatsapp.connect.accessTokenHint')}
          </p>
          {errors.accessToken && (
            <p id={`${ids}-accessToken-error`} className="field__error">
              {t(errors.accessToken)}
            </p>
          )}
        </div>
      )}
      <fieldset
        id={`${ids}-templates`}
        tabIndex={-1}
        aria-describedby={describe('templates', `${ids}-templates-hint`)}
      >
        <legend>{t('whatsapp.connect.templatesTitle')}</legend>
        <p id={`${ids}-templates-hint`} className="muted">
          {t('whatsapp.connect.templatesHint')}
        </p>
        {TEMPLATE_TYPES.flatMap((type) =>
          LANGUAGES.map((language) => {
            const key = templateKey(type, language);
            return (
              <div className="field" key={key}>
                <label htmlFor={`${ids}-t-${key}`}>
                  {t('whatsapp.connect.templateName', {
                    type: t(`whatsapp.types.${type}`),
                    language: t(`whatsapp.language.${language}`),
                  })}
                </label>
                <input
                  id={`${ids}-t-${key}`}
                  value={values.templates[key] ?? ''}
                  autoComplete="off"
                  spellCheck={false}
                  onChange={(event) => {
                    setValues({
                      ...values,
                      templates: { ...values.templates, [key]: event.target.value },
                    });
                  }}
                />
              </div>
            );
          }),
        )}
        {errors.templates && (
          <p id={`${ids}-templates-error`} className="field__error">
            {t(errors.templates)}
          </p>
        )}
      </fieldset>
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
          {t('whatsapp.connect.confirm')}
        </label>
        {errors.confirm && (
          <p id={`${ids}-confirm-error`} className="field__error">
            {t(errors.confirm)}
          </p>
        )}
      </div>
      <div className="actions">
        <button type="submit" className="button" disabled={busy}>
          {t(
            busy
              ? 'whatsapp.working'
              : creating
                ? 'whatsapp.connect.create'
                : 'whatsapp.connect.save',
          )}
        </button>
        {onCancel && (
          <button type="button" className="button button--secondary" onClick={onCancel}>
            {t('whatsapp.cancel')}
          </button>
        )}
      </div>
    </form>
  );
}

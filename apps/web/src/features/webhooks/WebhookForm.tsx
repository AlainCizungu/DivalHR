import type { WebhookEventType } from '@divalhr/api-client';
import { useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import {
  MAX_NAME_LENGTH,
  WEBHOOK_EVENTS,
  validateWebhook,
  type Failure,
  type WebhookFieldErrors,
  type WebhookValues,
} from './webhooks';

/** Create and edit share one accessible form: a fieldset of events and an explicit confirmation. */
export function WebhookForm({
  initial,
  submitKey,
  busy,
  failure,
  onSubmit,
  onCancel,
}: {
  initial: WebhookValues;
  submitKey: string;
  busy: boolean;
  failure: Failure | null;
  onSubmit: (values: WebhookValues) => void;
  onCancel: () => void;
}) {
  const { t } = useTranslation();
  const ids = useId();
  const [values, setValues] = useState<WebhookValues>(initial);
  const [confirmed, setConfirmed] = useState(false);
  const [errors, setErrors] = useState<WebhookFieldErrors>({});

  const toggle = (event: WebhookEventType, checked: boolean) => {
    setValues({
      ...values,
      eventTypes: checked
        ? [...values.eventTypes, event]
        : values.eventTypes.filter((e) => e !== event),
    });
  };

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = validateWebhook(values, confirmed);
    setErrors(found);
    if (Object.keys(found).length > 0) {
      const first = Object.keys(found)[0];
      document.getElementById(`${ids}-${first}`)?.focus();
      return;
    }
    onSubmit({ ...values, name: values.name.trim(), url: values.url.trim() });
  };

  const describe = (field: keyof WebhookFieldErrors, extra?: string) =>
    [errors[field] ? `${ids}-${field}-error` : null, extra].filter(Boolean).join(' ') || undefined;

  return (
    <form noValidate onSubmit={submit} data-testid="webhook-form" aria-label={t(submitKey)}>
      {Object.keys(errors).length > 0 && (
        <div className="error-summary" role="alert">
          <p>{t('webhooks.errors.summary')}</p>
        </div>
      )}
      {failure && (
        <div className="error-summary" role="alert" data-testid="webhook-failure">
          <p>{t(failure.messageKey)}</p>
          {failure.retryAfter !== undefined && (
            <p>{t('webhooks.retryAfter', { count: failure.retryAfter })}</p>
          )}
          {failure.correlationId && (
            <p className="muted">{t('webhooks.reference', { id: failure.correlationId })}</p>
          )}
        </div>
      )}
      <div className="field">
        <label htmlFor={`${ids}-name`}>{t('webhooks.form.name')}</label>
        <input
          id={`${ids}-name`}
          value={values.name}
          maxLength={MAX_NAME_LENGTH * 2}
          autoComplete="off"
          aria-invalid={errors.name ? true : undefined}
          aria-describedby={describe('name', `${ids}-name-hint`)}
          onChange={(event) => {
            setValues({ ...values, name: event.target.value });
          }}
        />
        <p id={`${ids}-name-hint`} className="muted">
          {t('webhooks.form.nameHint', { max: MAX_NAME_LENGTH })}
        </p>
        {errors.name && (
          <p id={`${ids}-name-error`} className="field__error">
            {t(errors.name)}
          </p>
        )}
      </div>
      <div className="field">
        <label htmlFor={`${ids}-url`}>{t('webhooks.form.url')}</label>
        <input
          id={`${ids}-url`}
          type="url"
          inputMode="url"
          value={values.url}
          autoComplete="off"
          spellCheck={false}
          placeholder={t('webhooks.form.urlPlaceholder')}
          aria-invalid={errors.url ? true : undefined}
          aria-describedby={describe('url', `${ids}-url-hint`)}
          onChange={(event) => {
            setValues({ ...values, url: event.target.value });
          }}
        />
        <p id={`${ids}-url-hint`} className="muted">
          {t('webhooks.form.urlHint')}
        </p>
        {errors.url && (
          <p id={`${ids}-url-error`} className="field__error">
            {t(errors.url)}
          </p>
        )}
      </div>
      <fieldset
        id={`${ids}-events`}
        tabIndex={-1}
        aria-describedby={describe('events')}
        aria-invalid={errors.events ? true : undefined}
      >
        <legend>{t('webhooks.form.events')}</legend>
        {WEBHOOK_EVENTS.map((event) => (
          <div className="field" key={event}>
            <label>
              <input
                type="checkbox"
                checked={values.eventTypes.includes(event)}
                onChange={(change) => {
                  toggle(event, change.target.checked);
                }}
              />{' '}
              {t(`webhooks.events.${event}`)} <code>{event}</code>
            </label>
          </div>
        ))}
        {errors.events && (
          <p id={`${ids}-events-error`} className="field__error">
            {t(errors.events)}
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
          {t('webhooks.form.confirm')}
        </label>
        {errors.confirm && (
          <p id={`${ids}-confirm-error`} className="field__error">
            {t(errors.confirm)}
          </p>
        )}
      </div>
      <div className="actions">
        <button type="submit" className="button" disabled={busy}>
          {t(busy ? 'webhooks.working' : submitKey)}
        </button>
        <button type="button" className="button button--secondary" onClick={onCancel}>
          {t('webhooks.cancel')}
        </button>
      </div>
    </form>
  );
}

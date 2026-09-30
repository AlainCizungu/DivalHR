import { SUPPORTED_LOCALES, isSupportedLocale } from '@divalhr/localization';
import type { Invitation, Problem } from '@divalhr/api-client';
import { useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import {
  EMAIL_MAX_LENGTH,
  INVITATION_FIELDS,
  INVITATION_ROLES,
  invitationErrorsFromProblem,
  rowFromReceipt,
  toInvitationPayload,
  validateInvitation,
  type InvitationErrors,
  type InvitationField,
  type InvitationValues,
} from './invitationForm';

type Phase = { kind: 'editing' } | { kind: 'submitting' } | { kind: 'failed'; messageKey: string };

function failureKey(status: number, problem: Partial<Problem> | undefined): string {
  if (status === 403) return 'users.unauthorized';
  return problem?.code ? `errors.${problem.code}` : 'errors.generic';
}

/**
 * Invites one person with one tenant role. The submit flow matches the hierarchy forms: client
 * validation, one idempotency key per payload (a retry after a network failure cannot create a
 * second invitation), server field errors mapped back to fields, and focus moved to the summary.
 */
export function InviteForm({ onCreated }: { onCreated: (row: Invitation) => void }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const initialLocale = isSupportedLocale(i18n.resolvedLanguage) ? i18n.resolvedLanguage : 'fr';
  const empty = (): InvitationValues => ({ email: '', role: 'employee', locale: initialLocale });
  const [values, setValues] = useState<InvitationValues>(empty);
  const [errors, setErrors] = useState<InvitationErrors>({});
  const [phase, setPhase] = useState<Phase>({ kind: 'editing' });
  const summaryRef = useRef<HTMLDivElement>(null);
  const keys = useIdempotencyKey();
  const fieldId = (field: InvitationField) => `${ids}-${field}`;
  const focusSummary = () => requestAnimationFrame(() => summaryRef.current?.focus());
  const message = (field: InvitationField) =>
    errors[field]
      ? t([`users.validation.${field}.${errors[field]}`, 'users.validation.generic'])
      : null;

  const update = (patch: Partial<InvitationValues>) => {
    setValues((current) => ({ ...current, ...patch }));
    if (phase.kind === 'failed') setPhase({ kind: 'editing' });
  };

  const onSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (phase.kind === 'submitting') return;
    const clientErrors = validateInvitation(values);
    if (Object.keys(clientErrors).length > 0) {
      setErrors(clientErrors);
      focusSummary();
      return;
    }
    const payload = toInvitationPayload(values);
    setErrors({});
    setPhase({ kind: 'submitting' });
    try {
      const { data, error, response } = await core.POST('/invitations', {
        params: { header: { 'Idempotency-Key': keys.keyFor(payload) } },
        body: payload,
        cache: 'no-store',
      });
      if (data) {
        keys.reset();
        setPhase({ kind: 'editing' });
        setValues(empty());
        onCreated(rowFromReceipt(data, payload.email));
        return;
      }
      const problem = error as Partial<Problem> | undefined;
      const mapped = problem ? invitationErrorsFromProblem(problem) : null;
      if (mapped) {
        setPhase({ kind: 'editing' });
        setErrors(mapped);
      } else {
        setPhase({ kind: 'failed', messageKey: failureKey(response.status, problem) });
      }
      focusSummary();
    } catch {
      setPhase({ kind: 'failed', messageKey: 'errors.network' });
      focusSummary();
    }
  };

  const submitting = phase.kind === 'submitting';
  const errorFields = INVITATION_FIELDS.filter((field) => errors[field]);
  const describedBy = (field: InvitationField, help?: string) =>
    [help, errors[field] ? `${fieldId(field)}-error` : null].filter(Boolean).join(' ') || undefined;

  return (
    <form
      noValidate
      aria-labelledby={`${ids}-title`}
      aria-busy={submitting}
      onSubmit={(event) => void onSubmit(event)}
      data-testid="invite-form"
    >
      <h2 id={`${ids}-title`}>{t('users.form.title')}</h2>
      <div ref={summaryRef} tabIndex={-1} className="form-summary" data-testid="form-summary">
        {errorFields.length > 0 && (
          <div className="error-summary">
            <p>{t('users.errorSummary')}</p>
            <ul>
              {errorFields.map((field) => (
                <li key={field}>
                  <a href={`#${field === 'role' ? `${fieldId('role')}-employee` : fieldId(field)}`}>
                    {message(field)}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {phase.kind === 'failed' && (
          <p className="error-summary" data-testid="form-error">
            {t(phase.messageKey)}
          </p>
        )}
      </div>

      <div className="field">
        <label htmlFor={fieldId('email')}>{t('users.form.email')}</label>
        <p id={`${fieldId('email')}-help`} className="field__help">
          {t('users.form.emailHelp')}
        </p>
        <input
          id={fieldId('email')}
          type="email"
          inputMode="email"
          autoComplete="off"
          spellCheck={false}
          maxLength={EMAIL_MAX_LENGTH}
          value={values.email}
          aria-invalid={errors.email ? true : undefined}
          aria-describedby={describedBy('email', `${fieldId('email')}-help`)}
          onChange={(event) => {
            update({ email: event.target.value });
          }}
        />
        {errors.email && (
          <p id={`${fieldId('email')}-error`} className="field__error">
            {message('email')}
          </p>
        )}
      </div>

      <fieldset
        className="field"
        aria-invalid={errors.role ? true : undefined}
        aria-describedby={describedBy('role')}
      >
        <legend>{t('users.form.role')}</legend>
        {INVITATION_ROLES.map((role) => (
          <div key={role} className="choice">
            <input
              id={`${fieldId('role')}-${role}`}
              type="radio"
              name={`${ids}-role`}
              value={role}
              checked={values.role === role}
              aria-describedby={`${fieldId('role')}-${role}-help`}
              onChange={() => {
                update({ role });
              }}
            />
            <label htmlFor={`${fieldId('role')}-${role}`}>{t(`users.form.roles.${role}`)}</label>
            <p id={`${fieldId('role')}-${role}-help`} className="field__help">
              {t(`users.form.roleHelp.${role}`)}
            </p>
          </div>
        ))}
        {errors.role && (
          <p id={`${fieldId('role')}-error`} className="field__error">
            {message('role')}
          </p>
        )}
      </fieldset>

      <div className="field">
        <label htmlFor={fieldId('locale')}>{t('users.form.locale')}</label>
        <select
          id={fieldId('locale')}
          value={values.locale}
          aria-invalid={errors.locale ? true : undefined}
          aria-describedby={describedBy('locale')}
          onChange={(event) => {
            if (isSupportedLocale(event.target.value)) update({ locale: event.target.value });
          }}
        >
          {SUPPORTED_LOCALES.map((locale) => (
            <option key={locale} value={locale} lang={locale}>
              {t(`locale.${locale}`)}
            </option>
          ))}
        </select>
        {errors.locale && (
          <p id={`${fieldId('locale')}-error`} className="field__error">
            {message('locale')}
          </p>
        )}
      </div>

      <button type="submit" className="button" disabled={submitting}>
        {submitting ? t('users.form.submitting') : t('users.form.submit')}
      </button>
    </form>
  );
}

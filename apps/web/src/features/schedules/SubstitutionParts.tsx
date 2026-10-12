import type { WorkScheduleLabels, WorkScheduleSubstitutionState } from '@divalhr/api-client';
import type { Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { StatusBadge, type StatusTone } from '../../ui/primitives';
import {
  REASON_MAX,
  codePoints,
  normalizeReason,
  type ReasonLocale,
} from '../leave/leaveApprovals';
import { formatDate } from '../people/history';
import type { ReasonForm, SubstitutionFailure, SubstitutionField } from './substitutions';
import { formatTime, splitLocal } from './workSchedules';

/** MVP-051: a refused call: the stable message, the named fields, retry delay and reference. */
export function SubstitutionAlert({
  failure,
  alertRef,
  testId,
}: {
  failure: SubstitutionFailure;
  alertRef?: Ref<HTMLDivElement>;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid={testId}
      data-kind={failure.kind}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.fields && failure.fields.length > 0 && (
        <ul>
          {failure.fields.map((field) => (
            <li key={field}>{t(`scheduleSubstitutions.problems.${field}`)}</li>
          ))}
        </ul>
      )}
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** The shift as the employee and the manager see it: date, local times, work, site and zone. */
export function ShiftFacts({
  start,
  end,
  labels,
  siteName,
  siteTimezone,
  testId,
}: {
  start: string;
  end: string;
  labels: WorkScheduleLabels;
  siteName: string | null;
  siteTimezone: string;
  testId?: string;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const from = splitLocal(start);
  const to = splitLocal(end);
  return (
    <dl className="decision-summary" data-testid={testId}>
      <dt>{t('scheduleSubstitutions.shift.date')}</dt>
      <dd>{formatDate(language, from.date)}</dd>
      <dt>{t('scheduleSubstitutions.shift.time')}</dt>
      <dd>
        {t('mySchedule.time', {
          start: formatTime(language, from.time),
          end: formatTime(language, to.time),
        })}
        {to.date !== from.date && (
          <> {t('mySchedule.overnight', { date: formatDate(language, to.date) })}</>
        )}
      </dd>
      <dt>{t('scheduleSubstitutions.shift.work')}</dt>
      <dd lang={language}>{labels[language]}</dd>
      <dt>{t('scheduleSubstitutions.shift.site')}</dt>
      <dd>{siteName ?? t('workSchedules.siteUnknown')}</dd>
      <dt>{t('scheduleSubstitutions.shift.zone')}</dt>
      <dd>{siteTimezone}</dd>
    </dl>
  );
}

const TONES: Record<WorkScheduleSubstitutionState, StatusTone> = {
  PENDING: 'info',
  APPROVED: 'success',
  REJECTED: 'danger',
  EXPIRED: 'neutral',
  VOIDED: 'neutral',
};

export function SubstitutionStateBadge({ state }: { state: WorkScheduleSubstitutionState }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={TONES[state]} testId="substitution-state">
      {t(`scheduleSubstitutions.states.${state}`)}
    </StatusBadge>
  );
}

/** The reason and its language, the same fields for a request, an approval and a rejection. */
export function ReasonFields({
  idPrefix,
  form,
  problems,
  reasonRef,
  onChange,
}: {
  idPrefix: string;
  form: ReasonForm;
  problems: ReadonlySet<SubstitutionField>;
  reasonRef?: Ref<HTMLTextAreaElement>;
  onChange: (change: Partial<ReasonForm>) => void;
}) {
  const { t } = useTranslation();
  const fieldId = (field: SubstitutionField) => `${idPrefix}-${field}`;
  const describedBy = (field: SubstitutionField) =>
    [`${fieldId(field)}-help`, problems.has(field) ? `${fieldId(field)}-error` : null]
      .filter(Boolean)
      .join(' ');
  const fieldError = (field: SubstitutionField) =>
    problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`scheduleSubstitutions.problems.${field}`)}
      </p>
    ) : null;
  const length = codePoints(normalizeReason(form.reason));
  return (
    <>
      <div className="field">
        <label htmlFor={fieldId('reason')}>{t('scheduleSubstitutions.form.reason')}</label>
        <p id={`${fieldId('reason')}-help`} className="field__help">
          {t('scheduleSubstitutions.form.reasonHelp', { max: REASON_MAX })}
        </p>
        {fieldError('reason')}
        <textarea
          id={fieldId('reason')}
          ref={reasonRef}
          rows={4}
          lang={form.reasonLocale}
          value={form.reason}
          autoComplete="off"
          aria-invalid={problems.has('reason') ? true : undefined}
          aria-describedby={describedBy('reason')}
          onChange={(event) => {
            onChange({ reason: event.target.value });
          }}
        />
        <p className="field__help" data-testid="substitution-reason-count">
          {t('scheduleSubstitutions.form.count', { count: length, max: REASON_MAX })}
        </p>
      </div>
      <div className="field">
        <label htmlFor={fieldId('reasonLocale')}>
          {t('scheduleSubstitutions.form.reasonLocale')}
        </label>
        <p id={`${fieldId('reasonLocale')}-help`} className="field__help">
          {t('scheduleSubstitutions.form.reasonLocaleHelp')}
        </p>
        {fieldError('reasonLocale')}
        <select
          id={fieldId('reasonLocale')}
          value={form.reasonLocale}
          aria-invalid={problems.has('reasonLocale') ? true : undefined}
          aria-describedby={describedBy('reasonLocale')}
          onChange={(event) => {
            const reasonLocale: ReasonLocale = event.target.value === 'en' ? 'en' : 'fr';
            onChange({ reasonLocale });
          }}
        >
          <option value="fr" lang="fr">
            {t('leaveApprovals.form.locales.fr')}
          </option>
          <option value="en" lang="en">
            {t('leaveApprovals.form.locales.en')}
          </option>
        </select>
      </div>
    </>
  );
}

/** The problems found before sending, each linked to its field. */
export function ProblemList({
  idPrefix,
  problems,
  listRef,
  testId,
}: {
  idPrefix: string;
  problems: ReadonlySet<SubstitutionField>;
  listRef?: Ref<HTMLDivElement>;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <div className="error-summary" role="alert" tabIndex={-1} ref={listRef} data-testid={testId}>
      <p>{t('employees.form.problems.title')}</p>
      <ul>
        {[...problems].map((problem) => (
          <li key={problem}>
            <a href={`#${idPrefix}-${problem}`}>{t(`scheduleSubstitutions.problems.${problem}`)}</a>
          </li>
        ))}
      </ul>
    </div>
  );
}

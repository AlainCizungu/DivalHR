import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { Constraint } from './hierarchyForm';

interface FieldProps {
  id: string;
  label: string;
  help?: string;
  error?: Constraint;
  errorMessage?: string | null;
  children: (describedBy: string | undefined, invalid: true | undefined) => ReactNode;
}

/** A labelled control with optional help and an error linked through aria-describedby. */
export function Field({ id, label, help, error, errorMessage, children }: FieldProps) {
  const helpId = help ? `${id}-help` : null;
  const errorId = error ? `${id}-error` : null;
  const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {help && (
        <p id={helpId ?? undefined} className="field__help">
          {help}
        </p>
      )}
      {children(describedBy, error ? true : undefined)}
      {error && (
        <p id={errorId ?? undefined} className="field__error">
          {errorMessage}
        </p>
      )}
    </div>
  );
}

/** Formats an ISO business date (no time zone shift) in the active language. */
export function useBusinessDate() {
  const { i18n } = useTranslation();
  return (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeZone: 'UTC' }).format(
      new Date(`${iso}T00:00:00Z`),
    );
}

/** "1 Jan 2026 – open-ended" style period text. */
export function usePeriodText() {
  const { t } = useTranslation();
  const date = useBusinessDate();
  return (from: string, to: string | null | undefined) =>
    to
      ? t('hierarchy.period.closed', { from: date(from), to: date(to) })
      : t('hierarchy.period.open', { from: date(from) });
}

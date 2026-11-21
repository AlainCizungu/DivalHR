import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * Small accessible field wrappers for the partner forms: a visible label, optional help and an
 * error that is tied to the control with aria-describedby (never colour alone).
 */

export function Field({
  id,
  label,
  help,
  error,
  required,
  children,
}: {
  id: string;
  label: string;
  help?: string;
  error?: string | null;
  required?: boolean;
  children: (describedBy: string | undefined) => ReactNode;
}) {
  const { t } = useTranslation();
  const describedBy = [help ? `${id}-help` : null, error ? `${id}-error` : null]
    .filter(Boolean)
    .join(' ');
  return (
    <div className="field">
      <label htmlFor={id}>
        {label}
        {required ? null : <span className="muted"> {t('partners.apply.optional')}</span>}
      </label>
      {help && (
        <p id={`${id}-help`} className="field__help">
          {help}
        </p>
      )}
      {children(describedBy || undefined)}
      {error && (
        <p id={`${id}-error`} className="field__error">
          {error}
        </p>
      )}
    </div>
  );
}

export interface Option {
  value: string;
  label: string;
  disabled?: boolean;
}

/** A group of checkboxes in a fieldset; the legend names the group. */
export function CheckGroup({
  id,
  legend,
  help,
  error,
  options,
  selected,
  onChange,
  grid = true,
  scroll = false,
}: {
  id: string;
  legend: string;
  help?: string;
  error?: string | null;
  options: readonly Option[];
  selected: readonly string[];
  onChange: (next: string[]) => void;
  grid?: boolean;
  scroll?: boolean;
}) {
  const describedBy = [help ? `${id}-help` : null, error ? `${id}-error` : null]
    .filter(Boolean)
    .join(' ');
  return (
    <fieldset
      className="field"
      id={id}
      aria-describedby={describedBy || undefined}
      aria-invalid={error ? true : undefined}
    >
      <legend>{legend}</legend>
      {help && (
        <p id={`${id}-help`} className="field__help">
          {help}
        </p>
      )}
      <div
        className={
          [grid ? 'partner-grid' : '', scroll ? 'partner-scroll' : ''].filter(Boolean).join(' ') ||
          undefined
        }
      >
        {options.map((option) => (
          <label key={option.value} className="choice">
            <input
              type="checkbox"
              value={option.value}
              checked={selected.includes(option.value)}
              disabled={option.disabled && !selected.includes(option.value)}
              onChange={(event) => {
                onChange(
                  event.target.checked
                    ? [...selected, option.value]
                    : selected.filter((value) => value !== option.value),
                );
              }}
            />
            {option.label}
          </label>
        ))}
      </div>
      {error && (
        <p id={`${id}-error`} className="field__error">
          {error}
        </p>
      )}
    </fieldset>
  );
}

/** A group of radio buttons in a fieldset. */
export function RadioGroup({
  id,
  name,
  legend,
  help,
  error,
  options,
  value,
  onChange,
}: {
  id: string;
  name: string;
  legend: string;
  help?: string;
  error?: string | null;
  options: readonly Option[];
  value: string;
  onChange: (next: string) => void;
}) {
  const describedBy = [help ? `${id}-help` : null, error ? `${id}-error` : null]
    .filter(Boolean)
    .join(' ');
  return (
    <fieldset
      className="field"
      id={id}
      aria-describedby={describedBy || undefined}
      aria-invalid={error ? true : undefined}
    >
      <legend>{legend}</legend>
      {help && (
        <p id={`${id}-help`} className="field__help">
          {help}
        </p>
      )}
      {options.map((option) => (
        <label key={option.value} className="choice">
          <input
            type="radio"
            name={name}
            value={option.value}
            checked={value === option.value}
            onChange={() => {
              onChange(option.value);
            }}
          />
          {option.label}
        </label>
      ))}
      {error && (
        <p id={`${id}-error`} className="field__error">
          {error}
        </p>
      )}
    </fieldset>
  );
}

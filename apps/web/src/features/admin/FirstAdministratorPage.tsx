import { useCallback, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { FirstAdministratorPanel } from './FirstAdministratorPanel';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type ReferenceProblem = 'REQUIRED' | 'FORMAT' | 'NOT_FOUND';

/**
 * MVP-014: lets a platform administrator reach the first-administrator panel of an existing
 * organization by its reference. The reference is held in component state only: it is never put
 * in the URL, history, storage or logs, and an invalid value never leaves the browser.
 */
export function FirstAdministratorPage() {
  const { t, i18n } = useTranslation();
  const ids = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [reference, setReference] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [problem, setProblem] = useState<ReferenceProblem | null>(null);

  const onNotFound = useCallback(() => {
    setSelected(null);
    setProblem('NOT_FOUND');
    requestAnimationFrame(() => inputRef.current?.focus());
  }, []);

  const onSubmit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const value = reference.trim();
    const next: ReferenceProblem | null =
      value === '' ? 'REQUIRED' : UUID.test(value) ? null : 'FORMAT';
    setProblem(next);
    if (next) {
      setSelected(null);
      inputRef.current?.focus();
      return;
    }
    setSelected(value.toLowerCase());
  };

  const inputId = `${ids}-reference`;
  const helpId = `${ids}-reference-help`;
  const errorId = `${ids}-reference-error`;
  const message =
    problem === 'NOT_FOUND'
      ? t('firstAdmin.notFound')
      : problem
        ? t(`firstAdmin.reference.${problem}`)
        : null;

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('firstAdmin.pageTitle')}</h1>
      <p>{t('firstAdmin.pageDescription')}</p>
      <form noValidate onSubmit={onSubmit} className="card" data-testid="first-admin-reference">
        <div className="field">
          <label htmlFor={inputId}>{t('firstAdmin.reference.label')}</label>
          <p id={helpId} className="field__help">
            {t('firstAdmin.reference.help')}
          </p>
          <input
            ref={inputRef}
            id={inputId}
            name="organizationReference"
            type="text"
            autoComplete="off"
            spellCheck={false}
            inputMode="text"
            maxLength={36}
            value={reference}
            aria-invalid={problem ? true : undefined}
            aria-describedby={problem ? `${helpId} ${errorId}` : helpId}
            onChange={(event) => {
              setReference(event.target.value);
              if (problem) setProblem(null);
            }}
          />
          {message && (
            <p id={errorId} className="field__error" role="alert">
              {message}
            </p>
          )}
        </div>
        <button type="submit" className="button">
          {t('firstAdmin.reference.submit')}
        </button>
      </form>
      {selected && (
        <FirstAdministratorPanel
          key={selected}
          organizationId={selected}
          defaultLocale={i18n.language}
          onNotFound={onNotFound}
        />
      )}
    </section>
  );
}

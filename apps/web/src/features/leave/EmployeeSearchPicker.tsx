import type { EmployeeSummary } from '@divalhr/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { codePoints } from '../people/history';

/**
 * MVP-041G: finds one employee by number or name with the directory's disclosure-audited search
 * (the same request as the manager picker of the employment history). The search text is sent in
 * the request body only: never in a URL or browser storage. The server checks the choice again.
 */
export function EmployeeSearchPicker({
  idPrefix,
  legend,
  value,
  onChange,
  invalid,
  errorId,
  error,
  testId,
}: {
  idPrefix: string;
  legend: string;
  /** The chosen employee: only the identity and names are shown (MVP-050B prefills it). */
  value: Pick<EmployeeSummary, 'id' | 'employeeNumber' | 'givenNames' | 'familyName'> | null;
  onChange: (value: EmployeeSummary | null) => void;
  invalid: boolean;
  errorId: string;
  error: string | null;
  testId: string;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<EmployeeSummary[] | null>(null);
  const [state, setState] = useState<'idle' | 'searching' | 'failed' | 'short'>('idle');

  const search = async () => {
    if (state === 'searching') return;
    const text = query.normalize('NFC').trim();
    if (codePoints(text) < 2) {
      setState('short');
      return;
    }
    setState('searching');
    try {
      const { data } = await core.POST('/employees/search', {
        body: { query: text, limit: 10 },
        cache: 'no-store',
      });
      if (data) {
        setResults(data.items);
        setState('idle');
      } else {
        setState('failed');
      }
    } catch {
      setState('failed');
    }
  };

  const name = (employee: Pick<EmployeeSummary, 'givenNames' | 'familyName' | 'employeeNumber'>) =>
    t('leaveSubstitutes.employee', {
      given: employee.givenNames,
      family: employee.familyName,
      number: employee.employeeNumber,
    });

  return (
    <fieldset className="field" data-testid={testId}>
      <legend>{legend}</legend>
      {error && (
        <p id={errorId} className="field__error">
          {error}
        </p>
      )}
      {value ? (
        <>
          <p data-testid={`${testId}-selected`}>
            {t('leaveSubstitutes.picker.selected', { name: name(value) })}
          </p>
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              onChange(null);
            }}
          >
            {t('leaveSubstitutes.picker.change')}
          </button>
        </>
      ) : (
        <>
          <label htmlFor={`${idPrefix}-find`}>{t('leaveSubstitutes.picker.find')}</label>
          <div className="toolbar">
            <input
              id={`${idPrefix}-find`}
              type="search"
              autoComplete="off"
              value={query}
              aria-invalid={invalid ? true : undefined}
              aria-describedby={invalid && error ? errorId : undefined}
              onChange={(event) => {
                setQuery(event.target.value);
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  void search();
                }
              }}
            />
            <button
              type="button"
              className="button button--secondary"
              disabled={state === 'searching'}
              aria-busy={state === 'searching' ? true : undefined}
              onClick={() => void search()}
            >
              {state === 'searching'
                ? t('leaveSubstitutes.picker.searching')
                : t('leaveSubstitutes.picker.search')}
            </button>
          </div>
          {state === 'short' && (
            <p className="field__error">{t('leaveSubstitutes.picker.short')}</p>
          )}
          {state === 'failed' && (
            <p className="field__error" role="alert">
              {t('leaveSubstitutes.picker.failed')}
            </p>
          )}
          {results !== null && results.length === 0 && (
            <p className="muted" role="status">
              {t('leaveSubstitutes.picker.noMatch')}
            </p>
          )}
          {results !== null && results.length > 0 && (
            <ul className="plain-list" aria-label={t('leaveSubstitutes.picker.results')}>
              {results.map((employee) => (
                <li key={employee.id}>
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => {
                      onChange(employee);
                      setResults(null);
                      setQuery('');
                    }}
                  >
                    {name(employee)}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </fieldset>
  );
}

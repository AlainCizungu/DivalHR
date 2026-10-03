import type { EmployeeSummary } from '@divalhr/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { codePoints } from './history';

/** The manager of a change: an employee found by search, or the end of the reporting line. */
export type ManagerChoice = { mode: 'set'; employee: EmployeeSummary | null } | { mode: 'clear' };

/**
 * Finds a manager by employee number or name (the same disclosure-audited search as the
 * directory) and offers to end the reporting line instead. The server checks again that the
 * manager is another employee of the organization, employed over the whole period, and that no
 * reporting loop or over-long chain would result.
 */
export function ManagerPicker({
  idPrefix,
  employeeId,
  value,
  onChange,
  invalid,
  allowClear,
}: {
  idPrefix: string;
  employeeId: string;
  value: ManagerChoice;
  onChange: (value: ManagerChoice) => void;
  invalid: boolean;
  allowClear: boolean;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<EmployeeSummary[] | null>(null);
  const [state, setState] = useState<'idle' | 'searching' | 'failed' | 'short'>('idle');

  const search = async () => {
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
        setResults(data.items.filter((item) => item.id !== employeeId));
        setState('idle');
      } else {
        setState('failed');
      }
    } catch {
      setState('failed');
    }
  };

  const selected = value.mode === 'set' ? value.employee : null;
  return (
    <fieldset className="field" data-testid="manager-fields">
      <legend>{t('employees.kinds.MANAGER')}</legend>
      {allowClear && (
        <div className="choice">
          <input
            id={`${idPrefix}-clear`}
            type="checkbox"
            checked={value.mode === 'clear'}
            onChange={(event) => {
              onChange(event.target.checked ? { mode: 'clear' } : { mode: 'set', employee: null });
            }}
          />
          <label htmlFor={`${idPrefix}-clear`}>{t('employees.manager.clear')}</label>
        </div>
      )}
      {value.mode === 'set' && (
        <>
          <label htmlFor={`${idPrefix}-find`}>{t('employees.manager.find')}</label>
          <div className="toolbar">
            <input
              id={`${idPrefix}-find`}
              type="text"
              autoComplete="off"
              value={query}
              aria-invalid={invalid && !selected ? true : undefined}
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
              onClick={() => void search()}
            >
              {t('employees.manager.search')}
            </button>
          </div>
          {state === 'short' && (
            <p className="field__error">{t('employees.directory.queryLength')}</p>
          )}
          {state === 'failed' && (
            <p className="field__error" role="alert">
              {t('employees.manager.failed')}
            </p>
          )}
          {results !== null && results.length === 0 && (
            <p className="muted">{t('employees.directory.noMatch')}</p>
          )}
          {results !== null && results.length > 0 && (
            <fieldset className="field">
              <legend>{t('employees.manager.results')}</legend>
              {results.map((employee) => (
                <div className="choice" key={employee.id}>
                  <input
                    id={`${idPrefix}-m-${employee.id}`}
                    type="radio"
                    name={`${idPrefix}-manager`}
                    checked={selected?.id === employee.id}
                    onChange={() => {
                      onChange({ mode: 'set', employee });
                    }}
                  />
                  <label htmlFor={`${idPrefix}-m-${employee.id}`}>
                    {t('employees.managerName', {
                      given: employee.givenNames,
                      family: employee.familyName,
                      number: employee.employeeNumber,
                    })}
                  </label>
                </div>
              ))}
            </fieldset>
          )}
          {selected && (
            <p data-testid="manager-selected">
              {t('employees.manager.selected', {
                name: t('employees.managerName', {
                  given: selected.givenNames,
                  family: selected.familyName,
                  number: selected.employeeNumber,
                }),
              })}
            </p>
          )}
        </>
      )}
    </fieldset>
  );
}

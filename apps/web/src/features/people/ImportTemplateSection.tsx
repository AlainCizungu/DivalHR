import type { EmployeeImportColumn } from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { ScrollRegion } from './ScrollRegion';

type Language = 'fr' | 'en';

const LANGUAGES: readonly Language[] = ['fr', 'en'];

/** The import columns in template order; the first six are required (server ImportColumn). */
const COLUMNS: readonly { key: EmployeeImportColumn; required: boolean }[] = [
  { key: 'employee_number', required: true },
  { key: 'given_names', required: true },
  { key: 'family_name', required: true },
  { key: 'start_date', required: true },
  { key: 'legal_entity_code', required: true },
  { key: 'site_code', required: true },
  { key: 'department_code', required: false },
  { key: 'cost_center_code', required: false },
  { key: 'team_code', required: false },
];

/** Step 1: download the header-only template and read the expected format of each column. */
export function ImportTemplateSection() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [failed, setFailed] = useState(false);

  const download = async (lang: Language) => {
    setFailed(false);
    try {
      const { data } = await core.GET('/employee-imports/template', {
        params: { query: { lang } },
        parseAs: 'blob',
        cache: 'no-store',
      });
      if (!data) {
        setFailed(true);
        return;
      }
      const url = URL.createObjectURL(data);
      const link = document.createElement('a');
      link.href = url;
      link.download = `divalhr-employee-import-${lang}.csv`;
      document.body.append(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch {
      setFailed(true);
    }
  };

  return (
    <section aria-labelledby={`${ids}-template`} className="card">
      <h2 id={`${ids}-template`}>{t('employeeImport.template.title')}</h2>
      <p className="muted">{t('employeeImport.template.help')}</p>
      <div className="actions">
        {LANGUAGES.map((lang) => (
          <button
            key={lang}
            type="button"
            className="button button--secondary"
            onClick={() => void download(lang)}
          >
            {t(`employeeImport.template.${lang}`)}
          </button>
        ))}
      </div>
      {failed && (
        <p className="field__error" role="alert">
          {t('employeeImport.template.failed')}
        </p>
      )}
      <ScrollRegion label={t('employeeImport.format.caption')}>
        <table data-testid="format-table">
          <caption>{t('employeeImport.format.caption')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('employeeImport.format.column')}</th>
              <th scope="col">{t('employeeImport.format.required')}</th>
              <th scope="col">{t('employeeImport.format.content')}</th>
            </tr>
          </thead>
          <tbody>
            {COLUMNS.map((column) => (
              <tr key={column.key}>
                <th scope="row">{t(`employeeImport.columns.${column.key}`)}</th>
                <td>
                  {column.required ? t('employeeImport.format.yes') : t('employeeImport.format.no')}
                </td>
                <td>{t(`employeeImport.formats.${column.key}`)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollRegion>
    </section>
  );
}

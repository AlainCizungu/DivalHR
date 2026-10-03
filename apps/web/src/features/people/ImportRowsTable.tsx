import type { EmployeeImportRow } from '@divalhr/api-client';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { FailureDetails } from './FailureDetails';
import { failureOf, NETWORK_FAILURE, type Failure } from './importFailure';
import { ScrollRegion } from './ScrollRegion';

export type RowFilter = 'all' | 'valid' | 'invalid';

type Rows =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: Failure }
  | { kind: 'ready'; items: EmployeeImportRow[]; nextCursor: string | null; loadingMore: boolean };

const STATUS_KEYS: Record<EmployeeImportRow['status'], string> = {
  VALID: 'employeeImport.table.valid',
  INVALID: 'employeeImport.table.invalid',
  CREATED: 'employeeImport.table.created',
  NOT_IMPORTED: 'employeeImport.table.notImported',
};

/**
 * One filtered, paginated view of an import's rows. Mount it with a React key per import and
 * filter: each instance loads its own first page. Rows and cursors stay in memory only; "Show
 * more" moves focus to the first new row; errors are text, never colour only.
 */
export function ImportRowsTable({
  importId,
  filter,
  caption,
  onAnnounce,
  'data-testid': testId,
}: {
  importId: string;
  filter: RowFilter;
  caption: string;
  onAnnounce: (text: string) => void;
  'data-testid': string;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const [rows, setRows] = useState<Rows>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const firstNewRow = useRef<number | null>(null);
  const rowRefs = useRef(new Map<number, HTMLTableRowElement>());
  const empty = t('employeeImport.table.optionalEmpty');

  useEffect(() => {
    const status = { active: true };
    void (async () => {
      try {
        const page = await core.GET('/employee-imports/{importId}/rows', {
          params: { path: { importId }, query: { status: filter } },
          cache: 'no-store',
        });
        if (!status.active) return;
        if (page.data) {
          setRows({
            kind: 'ready',
            items: page.data.items,
            nextCursor: page.data.nextCursor,
            loadingMore: false,
          });
          onAnnounce(t('employeeImport.announce.rows', { count: page.data.items.length }));
        } else {
          setRows({ kind: 'failed', failure: failureOf(page.response, page.error) });
        }
      } catch {
        if (status.active) setRows({ kind: 'failed', failure: NETWORK_FAILURE });
      }
    })();
    return () => {
      status.active = false;
    };
  }, [core, importId, filter, attempt, onAnnounce, t]);

  // After "Show more", focus moves to the first new row.
  useEffect(() => {
    const row = firstNewRow.current;
    if (row !== null && rows.kind === 'ready') {
      firstNewRow.current = null;
      rowRefs.current.get(row)?.focus();
    }
  }, [rows]);

  const loadMore = async () => {
    if (rows.kind !== 'ready' || !rows.nextCursor || rows.loadingMore) return;
    setRows({ ...rows, loadingMore: true });
    try {
      const page = await core.GET('/employee-imports/{importId}/rows', {
        params: { path: { importId }, query: { status: filter, cursor: rows.nextCursor } },
        cache: 'no-store',
      });
      if (page.data) {
        const added = page.data.items;
        firstNewRow.current = added[0]?.rowNumber ?? null;
        setRows({
          kind: 'ready',
          items: [...rows.items, ...added],
          nextCursor: page.data.nextCursor,
          loadingMore: false,
        });
        onAnnounce(t('employeeImport.announce.rows', { count: added.length }));
      } else {
        setRows({ kind: 'failed', failure: failureOf(page.response, page.error) });
      }
    } catch {
      setRows({ kind: 'failed', failure: NETWORK_FAILURE });
    }
  };

  const formatDay = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeZone: 'UTC' }).format(
      new Date(`${iso}T00:00:00Z`),
    );

  const units = (row: EmployeeImportRow) =>
    row.values
      ? [
          row.values.legalEntityCode,
          row.values.siteCode,
          row.values.departmentCode,
          row.values.costCenterCode,
          row.values.teamCode,
        ]
          .filter((code): code is string => Boolean(code))
          .join(' › ')
      : empty;

  return (
    <div aria-busy={rows.kind === 'loading'}>
      {rows.kind === 'loading' && <p className="muted">{t('employeeImport.preview.loading')}</p>}
      {rows.kind === 'failed' && (
        <>
          <FailureDetails failure={rows.failure} data-testid="rows-error" />
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              setRows({ kind: 'loading' });
              setAttempt((value) => value + 1);
            }}
          >
            {t('employeeImport.preview.retry')}
          </button>
        </>
      )}
      {rows.kind === 'ready' && rows.items.length === 0 && (
        <p className="muted" data-testid="rows-empty">
          {t('employeeImport.preview.empty')}
        </p>
      )}
      {rows.kind === 'ready' && rows.items.length > 0 && (
        <ScrollRegion label={caption}>
          <table data-testid={testId}>
            <caption>{caption}</caption>
            <thead>
              <tr>
                <th scope="col">{t('employeeImport.table.row')}</th>
                <th scope="col">{t('employeeImport.table.status')}</th>
                <th scope="col">{t('employeeImport.table.employeeNumber')}</th>
                <th scope="col">{t('employeeImport.table.name')}</th>
                <th scope="col">{t('employeeImport.table.startDate')}</th>
                <th scope="col">{t('employeeImport.table.units')}</th>
                <th scope="col">{t('employeeImport.table.problems')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.items.map((row) => (
                <tr
                  key={row.rowNumber}
                  tabIndex={-1}
                  data-testid="import-row"
                  ref={(node) => {
                    if (node) rowRefs.current.set(row.rowNumber, node);
                    else rowRefs.current.delete(row.rowNumber);
                  }}
                >
                  <th scope="row">{row.rowNumber}</th>
                  <td>{t(STATUS_KEYS[row.status])}</td>
                  <td>{row.values?.employeeNumber ?? empty}</td>
                  <td>
                    {row.values ? `${row.values.givenNames} ${row.values.familyName}` : empty}
                  </td>
                  <td>{row.values ? formatDay(row.values.startDate) : empty}</td>
                  <td>{units(row)}</td>
                  <td>
                    {row.errors.length === 0 ? (
                      empty
                    ) : (
                      <ul>
                        {row.errors.map((problem) => (
                          <li key={`${problem.column}-${problem.code}`}>
                            {t('employeeImport.rowError', {
                              column: t(`employeeImport.columns.${problem.column}`),
                              message: t(`employeeImport.rowErrors.${problem.code}`),
                            })}
                          </li>
                        ))}
                      </ul>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {rows.kind === 'ready' && rows.nextCursor && (
        <button
          type="button"
          className="button button--secondary"
          disabled={rows.loadingMore}
          onClick={() => void loadMore()}
        >
          {rows.loadingMore
            ? t('employeeImport.preview.loading')
            : t('employeeImport.preview.loadMore')}
        </button>
      )}
    </div>
  );
}

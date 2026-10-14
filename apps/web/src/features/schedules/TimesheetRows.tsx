import type { MyTimesheetRow, TimesheetRow } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { ScrollRegion } from '../people/ScrollRegion';
import { STATE_TONES, durationParts, formatShiftDate, formatShiftTime } from './timesheets';

type Row = TimesheetRow | MyTimesheetRow;

function isAdminRow(row: Row): row is TimesheetRow {
  return 'slotId' in row;
}

/**
 * MVP-070A: the rows of one timesheet revision, as the server stored them: the shift's local date
 * and times in the site's zone, the work in the current language, the state (the text carries the
 * meaning), the effective check-in and check-out with « Corrigé » / "Corrected" when an
 * administrator corrected them, and the worked time of READY rows only. Administrators also see
 * the cost center of the dated placement.
 */
export function TimesheetRows({
  rows,
  zone,
  label,
  testId,
}: {
  rows: Row[];
  zone: string;
  label: string;
  testId: string;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const admin = rows.some(isAdminRow);
  const time = (instant: string | null | undefined) =>
    instant ? formatShiftTime(language, instant, zone) : t('timesheets.detail.notRecorded');
  return (
    <ScrollRegion label={label}>
      <table className="timesheet-table" data-testid={testId}>
        <caption className="visually-hidden">{label}</caption>
        <thead>
          <tr>
            <th scope="col">{t('timesheets.detail.columns.shift')}</th>
            <th scope="col">{t('timesheets.detail.columns.work')}</th>
            <th scope="col">{t('timesheets.detail.columns.state')}</th>
            <th scope="col">{t('timesheets.detail.columns.checkIn')}</th>
            <th scope="col">{t('timesheets.detail.columns.checkOut')}</th>
            {admin && <th scope="col">{t('timesheets.detail.columns.costCenter')}</th>}
            <th scope="col">{t('timesheets.detail.columns.worked')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = isAdminRow(row) ? row.slotId : `${row.scheduledStartAt}-${row.labels.en}`;
            return (
              <tr key={key} data-testid="timesheet-row" data-state={row.state}>
                <th scope="row">
                  {t('timesheets.shiftTime', {
                    date: formatShiftDate(language, row.scheduledStartAt, zone),
                    start: formatShiftTime(language, row.scheduledStartAt, zone),
                    end: formatShiftTime(language, row.scheduledEndAt, zone),
                  })}
                </th>
                <td lang={language}>{language === 'fr' ? row.labels.fr : row.labels.en}</td>
                <td>
                  <StatusBadge tone={STATE_TONES[row.state]} testId="timesheet-row-state">
                    {t(`timesheets.state.${row.state}`)}
                  </StatusBadge>
                </td>
                <td data-testid="timesheet-row-check-in">
                  {row.state === 'READY' || row.state === 'OPEN_ATTENDANCE'
                    ? time(row.checkedInAt)
                    : '—'}
                  {row.corrected && (
                    <>
                      {' '}
                      <StatusBadge tone="info" testId="timesheet-row-corrected">
                        {isAdminRow(row) && row.correctionVersion
                          ? t('timesheets.detail.corrected', { version: row.correctionVersion })
                          : t('attendance.corrected.label')}
                      </StatusBadge>
                    </>
                  )}
                </td>
                <td data-testid="timesheet-row-check-out">
                  {row.state === 'READY' || row.state === 'OPEN_ATTENDANCE'
                    ? time(row.checkedOutAt)
                    : '—'}
                </td>
                {admin && (
                  <td data-testid="timesheet-row-cost-center">
                    {isAdminRow(row) && row.costCenter
                      ? row.costCenter.name && row.costCenter.code
                        ? t('workSchedules.siteName', {
                            name: row.costCenter.name,
                            code: row.costCenter.code,
                          })
                        : (row.costCenter.code ?? row.costCenter.id)
                      : t('timesheets.detail.noCostCenter')}
                  </td>
                )}
                <td data-testid="timesheet-row-worked">
                  {row.workedMinutes === null
                    ? '—'
                    : t('timesheets.duration', durationParts(row.workedMinutes))}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

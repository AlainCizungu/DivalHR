import type { MyTimesheetAction, TimesheetAction } from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatInstant } from './attendance';
import { durationParts } from './timesheets';

type Action = TimesheetAction | MyTimesheetAction;

/**
 * MVP-070B/C: a timesheet's immutable submissions and decisions, newest first, as the server
 * stored them: the action (text, never colour alone), its cycle and revision, the instant in the
 * site's zone, the worked time it bound and a return's reason as plain text. Nobody who decided is
 * ever shown.
 */
export function TimesheetHistory({
  actions,
  zone,
  testId,
}: {
  actions: Action[];
  zone: string;
  testId: string;
}) {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  if (actions.length === 0) {
    return (
      <p className="muted" data-testid={`${testId}-none`}>
        {t('timesheets.history.none')}
      </p>
    );
  }
  return (
    <ScrollRegion label={t('timesheets.history.label')}>
      <table className="timesheet-table" data-testid={testId}>
        <caption className="visually-hidden">{t('timesheets.history.label')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('timesheets.history.columns.action')}</th>
            <th scope="col">{t('timesheets.history.columns.cycle')}</th>
            <th scope="col">{t('timesheets.history.columns.revision')}</th>
            <th scope="col">{t('timesheets.history.columns.when')}</th>
            <th scope="col">{t('timesheets.history.columns.worked')}</th>
            <th scope="col">{t('timesheets.history.columns.reason')}</th>
          </tr>
        </thead>
        <tbody>
          {actions.map((action) => (
            <tr
              key={`${action.action}-${String(action.cycle)}-${action.occurredAt}`}
              data-testid="timesheet-action"
              data-action={action.action}
            >
              <th scope="row">{t(`timesheets.history.action.${action.action}`)}</th>
              <td>{action.cycle}</td>
              <td>{action.revision}</td>
              <td>{formatInstant(language, action.occurredAt, zone)}</td>
              <td>{t('timesheets.duration', durationParts(action.workedMinutes))}</td>
              <td data-testid="timesheet-action-reason">
                {action.returnReason ?? t('timesheets.history.noReason')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

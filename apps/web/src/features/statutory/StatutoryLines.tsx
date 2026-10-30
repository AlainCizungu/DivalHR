import type {
  CompensationMoney,
  StatutoryComponent,
  StatutoryLineBlocker,
  StatutoryRuleComponent,
  StatutorySource,
} from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { ScrollRegion } from '../people/ScrollRegion';
import { Amount, ExplanationDetails } from './StatutoryBits';
import { PROFILE_FIXABLE } from './statutory';

/** One employee of a preview or of a frozen calculation, as the server returned it. */
export interface StatutoryLineView {
  employeeId: string;
  employeeNumber: string;
  excluded: boolean;
  blockers: StatutoryLineBlocker[];
  contractualGross: CompensationMoney;
  employeeDeductions: CompensationMoney | null;
  employerContributions: CompensationMoney | null;
  previewNet: CompensationMoney | null;
  employerCost: CompensationMoney | null;
  components: StatutoryComponent[];
}

/** The page of the employee's statutory profile (identifiers only in the URL). */
export const profilePath = (employeeId: string) => `/admin/people/${employeeId}/statutory-profile`;

/** What to do about one line blocker, with a link when the profile page can resolve it. */
export function BlockerItem({
  blocker,
  employeeId,
  employeeNumber,
}: {
  blocker: StatutoryLineBlocker;
  employeeId: string;
  employeeNumber: string;
}) {
  const { t } = useTranslation();
  return (
    <li data-code={blocker} data-testid="statutory-line-blocker">
      {t(`statutory.lineBlockers.${blocker}`)}
      {PROFILE_FIXABLE.has(blocker) && (
        <>
          {' '}
          <Link
            className="table-link"
            to={profilePath(employeeId)}
            data-testid="statutory-profile-link"
          >
            {t('statutory.lines.openProfile')}{' '}
            <span className="visually-hidden">
              {t('statutory.lines.openProfileContext', { number: employeeNumber })}
            </span>
          </Link>
        </>
      )}
    </li>
  );
}

/**
 * The per-employee result as a semantic table in a labelled, focusable scroll region: it scrolls
 * inside the region, never the page, at 320 px, 390 px, 640 px and 200 % zoom. Values are the
 * server's; an unknown amount is a dash.
 */
export function StatutoryLinesTable({
  lines,
  caption,
  testId,
}: {
  lines: StatutoryLineView[];
  caption: string;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <ScrollRegion label={caption}>
      <table className="timesheet-table" data-testid={testId}>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('statutory.lines.employee')}</th>
            <th scope="col">{t('statutory.lines.state')}</th>
            <th scope="col">{t('payrollRuns.contractualGross')}</th>
            <th scope="col">{t('statutory.totals.employeeDeductions')}</th>
            <th scope="col">{t('statutory.totals.employerContributions')}</th>
            <th scope="col">{t('statutory.totals.previewNet')}</th>
            <th scope="col">{t('statutory.totals.employerCost')}</th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr
              key={line.employeeId}
              data-testid="statutory-line"
              data-blocked={line.blockers.length > 0 ? 'true' : 'false'}
              data-excluded={line.excluded ? 'true' : 'false'}
            >
              <th scope="row">{line.employeeNumber}</th>
              <td data-testid="statutory-line-state">
                {line.blockers.length > 0 ? (
                  <ul className="plain-list">
                    {line.blockers.map((blocker) => (
                      <BlockerItem
                        key={blocker}
                        blocker={blocker}
                        employeeId={line.employeeId}
                        employeeNumber={line.employeeNumber}
                      />
                    ))}
                  </ul>
                ) : line.excluded ? (
                  t('statutory.lines.excluded')
                ) : (
                  t('statutory.lines.calculated')
                )}
              </td>
              <td data-testid="statutory-line-gross">
                <Amount money={line.contractualGross} />
              </td>
              <td data-testid="statutory-line-deductions">
                <Amount money={line.employeeDeductions} />
              </td>
              <td data-testid="statutory-line-employer">
                <Amount money={line.employerContributions} />
              </td>
              <td data-testid="statutory-line-net">
                <Amount money={line.previewNet} />
              </td>
              <td data-testid="statutory-line-cost">
                <Amount money={line.employerCost} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

/** One expandable explanation per employee that has components. */
export function StatutoryExplanations({
  lines,
  rules,
  sources,
}: {
  lines: StatutoryLineView[];
  rules: StatutoryRuleComponent[];
  sources: StatutorySource[];
}) {
  const { t } = useTranslation();
  const withComponents = lines.filter((line) => line.components.length > 0);
  if (withComponents.length === 0) return null;
  return (
    <>
      <h3>{t('statutory.explain.title')}</h3>
      <p className="muted">{t('statutory.explain.help')}</p>
      {withComponents.map((line) => (
        <ExplanationDetails
          key={line.employeeId}
          employeeNumber={line.employeeNumber}
          components={line.components}
          rules={rules}
          sources={sources}
        />
      ))}
    </>
  );
}

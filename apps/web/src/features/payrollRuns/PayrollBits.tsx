import type {
  CompensationMoney,
  PayrollDateRange,
  PayrollEmployeeBlocker,
  PayrollExplanationCode,
  PayrollSourceReference,
} from '@divalhr/api-client';
import { useEffect, useRef, useState, type ReactNode, type Ref } from 'react';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { formatMoney } from '../compensation/compensation';
import { ScrollRegion } from '../people/ScrollRegion';
import { formatDay, type PayrollFailure } from './payrollRuns';

/** Moves focus to a named element once it is rendered (after a preview, a commit or an error). */
export function useFocusRequest() {
  const elements = useRef(new Map<string, HTMLElement>());
  const wanted = useRef<string | null>(null);
  const [, setTick] = useState(0);
  const register = (name: string) => (element: HTMLElement | null) => {
    if (element) elements.current.set(name, element);
    else elements.current.delete(name);
  };
  const request = (name: string) => {
    wanted.current = name;
    setTick((value) => value + 1);
  };
  useEffect(() => {
    const name = wanted.current;
    const element = name ? elements.current.get(name) : undefined;
    if (element) {
      wanted.current = null;
      element.focus();
    }
  });
  return { register, request };
}

/** A refused or failed request: a finished sentence, never the raw code. */
export function PayrollAlert({
  failure,
  testId,
  alertRef,
}: {
  failure: PayrollFailure;
  testId: string;
  alertRef?: Ref<HTMLDivElement>;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid={testId}
      data-kind={failure.kind}
      data-code={failure.code}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** "Draft—not statutory payroll": shown wherever an amount or a run status appears. */
export function DraftLabel({ testId = 'payroll-draft-label' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="muted" data-testid={testId}>
      <strong>{t('payrollRuns.draftLabel')}</strong>
    </p>
  );
}

export function RunStatus({ status }: { status: 'DRAFT' | 'SUPERSEDED' }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={status === 'DRAFT' ? 'warning' : 'neutral'} testId="payroll-run-status">
      {t(`payrollRuns.status.${status}`)}
    </StatusBadge>
  );
}

export function Money({ money }: { money: CompensationMoney | null }) {
  const { i18n } = useTranslation();
  return <>{money ? formatMoney(i18n.language, money) : '—'}</>;
}

function Ranges({ ranges }: { ranges: PayrollDateRange[] }) {
  const { t, i18n } = useTranslation();
  if (ranges.length === 0) return <>—</>;
  return (
    <ul className="plain-list">
      {ranges.map((range) => (
        <li key={`${range.from}-${range.to}`} data-testid="payroll-range">
          {t('payrollRuns.lines.range', {
            from: formatDay(i18n.language, range.from),
            to: formatDay(i18n.language, range.to),
          })}
        </li>
      ))}
    </ul>
  );
}

function Sources({ sources }: { sources: PayrollSourceReference[] }) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="plain-list">
      {sources.map((source) => (
        <li
          key={`${source.kind}-${source.sourceId}-${String(source.sourceVersion)}`}
          data-testid="payroll-source"
          data-kind={source.kind}
        >
          {t('payrollRuns.lines.source', {
            kind: t(`payrollRuns.sourceKind.${source.kind}`),
            id: source.sourceId,
            version: source.sourceVersion,
            from: formatDay(i18n.language, source.validFrom),
            to: source.validTo ? formatDay(i18n.language, source.validTo) : '…',
          })}
        </li>
      ))}
    </ul>
  );
}

export interface LineView {
  employeeId: string;
  employeeNumber: string;
  eligibleDays: number;
  ranges: PayrollDateRange[];
  denominator: number | null;
  fullMonth: boolean;
  monthly: CompensationMoney | null;
  gross: CompensationMoney | null;
  explanationCodes: PayrollExplanationCode[];
  blockers?: PayrollEmployeeBlocker[];
  excludedOtherCurrency?: boolean;
  sources?: PayrollSourceReference[];
}

/**
 * The per-employee calculation as a semantic table in a labelled, focusable scroll region: it
 * scrolls inside the region, never the page, at 320 px, 390 px, 640 px and 200 % zoom.
 */
export function LinesTable({
  lines,
  caption,
  testId,
  withSources,
}: {
  lines: LineView[];
  caption: string;
  testId: string;
  withSources: boolean;
}) {
  const { t } = useTranslation();
  const labelled = (text: ReactNode) => text;
  return (
    <ScrollRegion label={caption}>
      <table className="timesheet-table" data-testid={testId}>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('payrollRuns.lines.employee')}</th>
            <th scope="col">{t('payrollRuns.lines.eligible')}</th>
            <th scope="col">{t('payrollRuns.lines.denominator')}</th>
            <th scope="col">{t('payrollRuns.lines.monthly')}</th>
            <th scope="col">{t('payrollRuns.contractualGross')}</th>
            <th scope="col">{t('payrollRuns.lines.explanation')}</th>
            <th scope="col">
              {withSources ? t('payrollRuns.lines.sources') : t('payrollRuns.lines.blockers')}
            </th>
          </tr>
        </thead>
        <tbody>
          {lines.map((line) => (
            <tr
              key={line.employeeId}
              data-testid="payroll-line"
              data-blocked={(line.blockers?.length ?? 0) > 0 ? 'true' : 'false'}
              data-excluded={line.excludedOtherCurrency ? 'true' : 'false'}
            >
              <th scope="row">{line.employeeNumber}</th>
              <td>
                <span data-testid="payroll-line-days">
                  {t('payrollRuns.lines.days', { count: line.eligibleDays })}
                </span>
                <Ranges ranges={line.ranges} />
              </td>
              <td>
                {line.fullMonth
                  ? t('payrollRuns.lines.fullMonth')
                  : labelled(line.denominator === null ? '—' : String(line.denominator))}
              </td>
              <td>
                <Money money={line.monthly} />
              </td>
              <td data-testid="payroll-line-gross">
                <Money money={line.gross} />
              </td>
              <td>
                {line.explanationCodes.length === 0 ? (
                  '—'
                ) : (
                  <ul className="plain-list">
                    {line.explanationCodes.map((code) => (
                      <li key={code} data-code={code}>
                        {t(`payrollRuns.explanation.${code}`)}
                      </li>
                    ))}
                  </ul>
                )}
              </td>
              <td>
                {withSources ? (
                  <Sources sources={line.sources ?? []} />
                ) : line.excludedOtherCurrency ? (
                  <span data-testid="payroll-line-excluded">{t('payrollRuns.lines.excluded')}</span>
                ) : (line.blockers?.length ?? 0) === 0 ? (
                  '—'
                ) : (
                  <ul className="plain-list">
                    {line.blockers?.map((blocker) => (
                      <li key={blocker} data-code={blocker}>
                        {t(`payrollRuns.employeeBlockers.${blocker}`)}
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
  );
}

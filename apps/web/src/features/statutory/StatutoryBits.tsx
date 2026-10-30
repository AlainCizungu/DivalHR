import type {
  CompensationMoney,
  StatutoryComponent,
  StatutoryRuleComponent,
  StatutorySource,
} from '@divalhr/api-client';
import { useTranslation } from 'react-i18next';
import { StatusBadge } from '../../ui/primitives';
import { formatMoney } from '../compensation/compensation';
import { formatDay } from '../payrollRuns/payrollRuns';
import { bracketBounds, formatBasisPoints, ruleFor, sourceFor } from './statutory';

/**
 * "Statutory preview—not a payslip or payment": shown on every statutory view, next to every
 * amount. It is a preview, not a payslip, an approval, a filing or a payment, and no claim of
 * legal compliance.
 */
export function StatutoryLabel({ testId = 'statutory-label' }: { testId?: string }) {
  const { t } = useTranslation();
  return (
    <p className="muted" data-testid={testId}>
      <strong>{t('statutory.label')}</strong>
    </p>
  );
}

export function PackStatus({ status }: { status: 'DRAFT' | 'VERIFIED' | 'RETIRED' }) {
  const { t } = useTranslation();
  return (
    <StatusBadge
      tone={status === 'VERIFIED' ? 'success' : status === 'DRAFT' ? 'warning' : 'neutral'}
      testId="statutory-pack-status"
    >
      {t(`statutory.packStatus.${status}`)}
    </StatusBadge>
  );
}

export function Amount({ money }: { money: CompensationMoney | null }) {
  const { i18n } = useTranslation();
  return <>{money ? formatMoney(i18n.language, money) : '—'}</>;
}

/** Annual trace and bracket values are whole-franc decimal strings in CDF. */
function Francs({ value }: { value: string }) {
  return <Amount money={{ currency: 'CDF', amount: value }} />;
}

/** Official sources: links open in a new tab; the document hash is shown, or "not hashed". */
export function SourceList({ sources, testId }: { sources: StatutorySource[]; testId: string }) {
  const { t, i18n } = useTranslation();
  return (
    <ul className="plain-list" data-testid={testId}>
      {sources.map((source) => (
        <li key={source.key} data-testid="statutory-source" data-key={source.key}>
          <a
            className="table-link"
            href={source.url}
            target="_blank"
            rel="noopener noreferrer"
            data-testid="statutory-source-link"
          >
            {source.title} <span className="visually-hidden">{t('statutory.source.newTab')}</span>
          </a>
          <br />
          <span className="muted">
            {t('statutory.source.key', { key: t(`statutory.sourceKey.${source.key}`) })}
            {' · '}
            {t('statutory.source.retrieved', {
              date: formatDay(i18n.language, source.retrievedOn),
            })}
            {' · '}
            {source.sha256 ? (
              <span data-testid="statutory-source-hash">
                {t('statutory.source.hash')} <code>{source.sha256}</code>
              </span>
            ) : (
              <span data-testid="statutory-source-unhashed">{t('statutory.source.unhashed')}</span>
            )}
          </span>
        </li>
      ))}
    </ul>
  );
}

function Method({ rule }: { rule: StatutoryRuleComponent }) {
  const { t, i18n } = useTranslation();
  const { method } = rule;
  if (method.type === 'RATE') {
    return (
      <>
        {t('statutory.explain.rate', {
          rate: formatBasisPoints(i18n.language, method.rateBp ?? 0),
        })}
      </>
    );
  }
  return (
    <>
      {t('statutory.explain.progressive', { periods: method.periodsPerYear ?? 12 })}
      <ul className="plain-list" data-testid="statutory-brackets">
        {bracketBounds(method.brackets ?? []).map((bracket) => (
          <li key={`${bracket.from}-${bracket.to ?? 'open'}`}>
            {bracket.to === null ? (
              <>
                {t('statutory.explain.bracketOpen', {
                  rate: formatBasisPoints(i18n.language, bracket.rateBp),
                })}{' '}
                (<Francs value={bracket.from} />)
              </>
            ) : (
              <>
                <Francs value={bracket.from} /> – <Francs value={bracket.to} />
                {': '}
                {formatBasisPoints(i18n.language, bracket.rateBp)}
              </>
            )}
          </li>
        ))}
      </ul>
      {method.capBp !== undefined && (
        <span data-testid="statutory-cap">
          {t('statutory.explain.cap', { rate: formatBasisPoints(i18n.language, method.capBp) })}
        </span>
      )}
    </>
  );
}

/**
 * One component of one employee: what it is, its base, the pack's rate or brackets, the intermediate
 * values the server recorded, the rounding and the official source it cites. Server values only.
 */
function ComponentExplanation({
  component,
  rules,
  sources,
}: {
  component: StatutoryComponent;
  rules: StatutoryRuleComponent[];
  sources: StatutorySource[];
}) {
  const { t, i18n } = useTranslation();
  const rule = ruleFor(rules, component.componentId);
  const source = rule ? sourceFor(sources, rule.sourceKey) : undefined;
  const trace = component.trace;
  const slices = trace.slices ?? [];
  return (
    <section
      className="statutory-component"
      data-testid="statutory-component"
      data-component-id={component.componentId}
    >
      <h4>
        {t(`statutory.component.${component.componentId}`, { defaultValue: component.componentId })}
      </h4>
      <dl className="decision-summary decision-summary--wrapping">
        <dt>{t('statutory.explain.party')}</dt>
        <dd>{t(`statutory.party.${component.party}`)}</dd>
        <dt>{t('statutory.explain.category')}</dt>
        <dd>{t(`statutory.category.${component.category}`)}</dd>
        <dt>{t('statutory.explain.base')}</dt>
        <dd data-testid="statutory-component-base">
          <Amount money={component.base} />
          {rule && <> ({t(`statutory.baseKind.${rule.base}`)})</>}
        </dd>
        {!rule && (
          <>
            <dt>{t('statutory.explain.method')}</dt>
            <dd data-testid="statutory-component-rule-unavailable">
              {t('statutory.explain.ruleUnavailable')}
            </dd>
          </>
        )}
        {rule && (
          <>
            <dt>{t('statutory.explain.method')}</dt>
            <dd data-testid="statutory-component-method">
              <Method rule={rule} />
            </dd>
            <dt>{t('statutory.explain.rounding')}</dt>
            <dd>{t(`statutory.rounding.${rule.rounding}`)}</dd>
          </>
        )}
        {trace.annualBase !== undefined && (
          <>
            <dt>{t('statutory.explain.annualBase')}</dt>
            <dd data-testid="statutory-trace-annual">
              <Francs value={trace.annualBase} />
            </dd>
          </>
        )}
        {slices.length > 0 && (
          <>
            <dt>{t('statutory.explain.slices')}</dt>
            <dd>
              <ul className="plain-list" data-testid="statutory-trace-slices">
                {slices.map((slice) => (
                  <li key={`${slice.from}-${slice.to}`}>
                    {t('statutory.explain.slice', {
                      rate: formatBasisPoints(i18n.language, slice.rateBp),
                    })}
                    {': '}
                    <Francs value={slice.from} /> – <Francs value={slice.to} /> (
                    <Francs value={slice.amount} />)
                  </li>
                ))}
              </ul>
            </dd>
          </>
        )}
        {trace.numerator !== undefined && (
          <>
            <dt>{t('statutory.explain.numerator')}</dt>
            <dd data-testid="statutory-trace-numerator">{trace.numerator}</dd>
          </>
        )}
        {trace.capApplied !== undefined && (
          <>
            <dt>{t('statutory.explain.capApplied')}</dt>
            <dd data-testid="statutory-trace-cap">
              {trace.capApplied ? t('statutory.yes') : t('statutory.no')}
            </dd>
          </>
        )}
        <dt>{t('statutory.explain.result')}</dt>
        <dd data-testid="statutory-component-amount">
          <Amount money={component.amount} />
        </dd>
        <dt>{t('statutory.explain.codes')}</dt>
        <dd>
          <ul className="plain-list">
            {component.explanationCodes.map((code) => (
              <li key={code} data-code={code}>
                {t(`statutory.explanation.${code}`)}
              </li>
            ))}
          </ul>
        </dd>
        <dt>{t('statutory.explain.source')}</dt>
        <dd data-testid="statutory-component-source">
          {source ? (
            <SourceList sources={[source]} testId="statutory-component-source-list" />
          ) : (
            t('statutory.explain.sourceUnavailable')
          )}
        </dd>
      </dl>
    </section>
  );
}

/**
 * An expandable explanation of one employee's calculation, naming every component with its base,
 * the pack's rate or brackets, the recorded intermediate values, the rounding and the official
 * source. A native details element: operable by keyboard, announced by assistive technology.
 */
export function ExplanationDetails({
  employeeNumber,
  components,
  rules,
  sources,
}: {
  employeeNumber: string;
  components: StatutoryComponent[];
  rules: StatutoryRuleComponent[];
  sources: StatutorySource[];
}) {
  const { t } = useTranslation();
  return (
    <details className="card statutory-explanation" data-testid="statutory-explanation">
      <summary>{t('statutory.explain.summary', { number: employeeNumber })}</summary>
      {components.length === 0 ? (
        <p className="muted">{t('statutory.explain.none')}</p>
      ) : (
        components.map((component) => (
          <ComponentExplanation
            key={component.componentId}
            component={component}
            rules={rules}
            sources={sources}
          />
        ))
      )}
    </details>
  );
}

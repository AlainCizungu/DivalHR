import type { StatutoryPackApplicability } from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { formatDay, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { packFailureOf } from './statutoryPacks';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; view: StatutoryPackApplicability };

/**
 * MVP-097A (D97A-12): what a tenant administrator may see of the statutory pack that applies to a
 * legal entity and a payroll period: the pack code, version and public provenance, read-only. No
 * formula, note or document is ever returned for a tenant. With no ACTIVE pack the refusal is stated
 * plainly: payroll cannot be calculated for that period.
 */
export function ApplicabilityPanel({
  legalEntityId,
  month,
}: {
  legalEntityId: string;
  month: string;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [state, setState] = useState<{ key: string; load: Load } | null>(null);
  const key = `${legalEntityId}:${month}`;

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/statutory-pack-applicability', {
        params: { path: { legalEntityId }, query: { payrollPeriod: month } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setState({
            key,
            load: data
              ? { kind: 'ready', view: data }
              : { kind: 'failed', failure: packFailureOf(response, error) },
          });
        },
        () => {
          if (active) setState({ key, load: { kind: 'failed', failure: NETWORK_FAILURE } });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, month, key]);

  const load: Load = state?.key === key ? state.load : { kind: 'loading' };

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="statutory-pack-applicability"
    >
      <h2 id={`${ids}-title`}>{t('statutoryPacks.applicability.title')}</h2>
      {load.kind === 'loading' && <p role="status">{t('statutoryPacks.loading')}</p>}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="statutory-pack-applicability-failure" />
      )}
      {load.kind === 'ready' && !load.view.applicable && (
        <p data-testid="statutory-pack-applicability-none">
          {t('statutoryPacks.applicability.none', {
            country: load.view.country,
            period: load.view.payrollPeriod,
          })}
        </p>
      )}
      {load.kind === 'ready' && load.view.applicable && (
        <dl className="definition-list" data-testid="statutory-pack-applicability-summary">
          <dt>{t('statutoryPacks.applicability.pack')}</dt>
          <dd>
            {t('statutoryPacks.list.packVersion', {
              code: load.view.packCode ?? '—',
              version: load.view.version ?? '—',
            })}
          </dd>
          <dt>{t('statutoryPacks.applicability.effective')}</dt>
          <dd>
            {load.view.effectiveFrom ? formatDay(i18n.language, load.view.effectiveFrom) : '—'}
            {' – '}
            {load.view.effectiveTo
              ? formatDay(i18n.language, load.view.effectiveTo)
              : t('statutoryPacks.openEnded')}
          </dd>
          <dt>{t('statutoryPacks.evidence.authority')}</dt>
          <dd>{load.view.sourceAuthority ?? '—'}</dd>
          <dt>{t('statutoryPacks.evidence.documentTitle')}</dt>
          <dd>{load.view.sourceTitle ?? '—'}</dd>
          <dt>{t('statutoryPacks.evidence.publishedOn')}</dt>
          <dd>
            {load.view.sourcePublishedOn
              ? formatDay(i18n.language, load.view.sourcePublishedOn)
              : '—'}
          </dd>
          <dt>{t('statutoryPacks.evidence.reference')}</dt>
          <dd className="wrap-anywhere">{load.view.sourceReference ?? '—'}</dd>
        </dl>
      )}
      <p className="muted">{t('statutoryPacks.applicability.note')}</p>
    </section>
  );
}

import type { PayrollRunDetail } from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { CalculationPanel } from './CalculationPanel';
import { RulePackPanel } from './RulePackPanel';
import { StatutoryLabel } from './StatutoryBits';
import { statutoryFailureOf } from './statutory';

type RunLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; run: PayrollRunDetail['run'] };

/**
 * MVP-094B: the statutory preview of one frozen payroll run, an administrator workflow under
 * Payroll runs: the rule-pack status and activation, the preview of the calculation with its
 * blockers and explanations, the immutable versions. An unknown run, or one of another tenant, is
 * the same safe not-found state. There is no approval, payslip, filing or payment on this page.
 */
export function StatutoryRunPage() {
  const { legalEntityId = '', runId = '' } = useParams();
  return (
    <Statutory key={`${legalEntityId}/${runId}`} legalEntityId={legalEntityId} runId={runId} />
  );
}

function Statutory({ legalEntityId, runId }: { legalEntityId: string; runId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const focus = useFocusRequest();
  const [load, setLoad] = useState<RunLoad>({ kind: 'loading' });
  const [packRevision, setPackRevision] = useState(0);

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payroll-runs/{runId}', {
        params: { path: { legalEntityId, runId }, query: { limit: 1 } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', run: data.run }
              : { kind: 'failed', failure: statutoryFailureOf(response, error) },
          );
          focus.request('title');
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
    // The focus helper is stable in behaviour.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [core, legalEntityId, runId]);

  const run = load.kind === 'ready' ? load.run : null;
  return (
    <section
      className="statutory-page"
      aria-labelledby={`${ids}-title`}
      data-testid="statutory-run"
    >
      <PageHeader
        titleId={`${ids}-title`}
        title={
          run
            ? t('statutory.run.title', {
                period: formatMonth(i18n.language, run.period),
                currency: run.currency,
              })
            : t('statutory.run.fallbackTitle')
        }
        description={t('statutory.run.description')}
      />
      <StatutoryLabel />
      <p>
        <Link className="table-link" to={`/admin/payroll-runs/${legalEntityId}/${runId}`}>
          {t('statutory.run.back')}
        </Link>
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="statutory-run-loading">
          {t('payrollRuns.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert
          failure={load.failure}
          testId="statutory-run-error"
          alertRef={focus.register('title')}
        />
      )}
      {run && (
        <>
          <RulePackPanel
            legalEntityId={legalEntityId}
            month={run.period}
            onChanged={() => {
              setPackRevision((value) => value + 1);
            }}
          />
          <CalculationPanel legalEntityId={legalEntityId} runId={runId} resetToken={packRevision} />
        </>
      )}
    </section>
  );
}

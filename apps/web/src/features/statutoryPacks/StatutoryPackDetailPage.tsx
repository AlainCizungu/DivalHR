import type { StatutoryPackVersionDetail } from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { formatDay, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { LegalNotice, StateBadge } from './StatutoryPackBits';
import { PackActions } from './PackActions';
import { packFailureOf } from './statutoryPacks';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; detail: StatutoryPackVersionDetail };

const dash = (value: string | number | null | undefined) => value ?? '—';

/** One pack version: evidence, digests, validation results, review and history, and its actions. */
export function StatutoryPackDetailPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const params = useParams();
  const packCode = params.packCode ?? '';
  const version = Number(params.version);
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [announcement, setAnnouncement] = useState('');

  useEffect(() => {
    let active = true;
    core
      .GET('/platform/statutory-packs/{packCode}/versions/{version}', {
        params: { path: { packCode, version: String(version) } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', detail: data }
              : { kind: 'failed', failure: packFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, packCode, version, revision]);

  const detail = load.kind === 'ready' ? load.detail : null;
  const summary = detail?.summary;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="statutory-pack-detail">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('statutoryPacks.detail.title', { code: packCode, version: params.version })}
        actions={
          <Link className="button button--secondary" to="/admin/statutory-packs">
            {t('statutoryPacks.detail.back')}
          </Link>
        }
      />
      <LegalNotice />
      <p role="status" className="visually-hidden" data-testid="statutory-pack-announcement">
        {announcement}
      </p>
      {load.kind === 'loading' && <p role="status">{t('statutoryPacks.loading')}</p>}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="statutory-pack-failure" />
      )}
      {detail && summary && (
        <>
          <section className="card" aria-labelledby={`${ids}-state`}>
            <h2 id={`${ids}-state`}>{t('statutoryPacks.detail.stateTitle')}</h2>
            <p>
              <StateBadge state={summary.state} />{' '}
              <span className="muted">
                {t('statutoryPacks.detail.country', { country: summary.country })}
                {' · '}
                {t('statutoryPacks.detail.semantic', { value: summary.semanticVersion })}
              </span>
            </p>
            {summary.state === 'DRAFT' && (
              <p data-testid="statutory-pack-draft-notice">{t('statutoryPacks.draftNotice')}</p>
            )}
            {summary.state === 'LEGACY' && (
              <p data-testid="statutory-pack-legacy-notice">{t('statutoryPacks.legacyNotice')}</p>
            )}
            <p>
              {t('statutoryPacks.detail.interval', {
                from: formatDay(i18n.language, summary.effectiveFrom),
                to: summary.effectiveTo
                  ? formatDay(i18n.language, summary.effectiveTo)
                  : t('statutoryPacks.openEnded'),
              })}
            </p>
            {summary.activeFrom && (
              <p>
                {t('statutoryPacks.detail.activeFrom', {
                  date: formatDay(i18n.language, summary.activeFrom),
                })}
              </p>
            )}
            {detail.retireReason && (
              <p>
                {t('statutoryPacks.detail.retireReason', {
                  reason: t(`statutoryPacks.reason.${detail.retireReason}`, {
                    defaultValue: detail.retireReason,
                  }),
                })}
              </p>
            )}
          </section>

          <section className="card" aria-labelledby={`${ids}-evidence`}>
            <h2 id={`${ids}-evidence`}>{t('statutoryPacks.evidence.title')}</h2>
            <p data-testid="statutory-pack-evidence-status">
              <StatusBadge tone={summary.evidenceComplete ? 'success' : 'warning'}>
                {t(
                  summary.evidenceComplete
                    ? 'statutoryPacks.evidence.complete'
                    : 'statutoryPacks.evidence.incomplete',
                )}
              </StatusBadge>
            </p>
            <dl className="definition-list">
              <dt>{t('statutoryPacks.evidence.authority')}</dt>
              <dd>{dash(detail.evidence.sourceAuthority)}</dd>
              <dt>{t('statutoryPacks.evidence.documentTitle')}</dt>
              <dd>{dash(detail.evidence.sourceTitle)}</dd>
              <dt>{t('statutoryPacks.evidence.publishedOn')}</dt>
              <dd>
                {detail.evidence.sourcePublishedOn
                  ? formatDay(i18n.language, detail.evidence.sourcePublishedOn)
                  : '—'}
              </dd>
              <dt>{t('statutoryPacks.evidence.reference')}</dt>
              <dd className="wrap-anywhere">{dash(detail.evidence.sourceReference)}</dd>
              <dt>{t('statutoryPacks.evidence.bundleSha')}</dt>
              <dd>
                <code className="wrap-anywhere">{dash(detail.evidence.sourceBundleSha256)}</code>
              </dd>
              <dt>{t('statutoryPacks.evidence.counts')}</dt>
              <dd>
                {t('statutoryPacks.evidence.countsValue', {
                  sources: detail.sourceCount,
                  cases: detail.caseCount,
                })}
              </dd>
            </dl>
          </section>

          <section className="card" aria-labelledby={`${ids}-digests`}>
            <h2 id={`${ids}-digests`}>{t('statutoryPacks.digests.title')}</h2>
            <dl className="definition-list">
              <dt>{t('statutoryPacks.digests.rules')}</dt>
              <dd>
                <code className="wrap-anywhere">{detail.digests.rulesSha256}</code>
              </dd>
              <dt>{t('statutoryPacks.digests.pack')}</dt>
              <dd>
                <code className="wrap-anywhere">{dash(detail.digests.packSha256)}</code>
              </dd>
              <dt>{t('statutoryPacks.digests.fixtures')}</dt>
              <dd>
                <code className="wrap-anywhere">{dash(detail.digests.fixtureSha256)}</code>
              </dd>
            </dl>
          </section>

          <section
            className="card"
            aria-labelledby={`${ids}-runs`}
            data-testid="statutory-pack-runs"
          >
            <h2 id={`${ids}-runs`}>{t('statutoryPacks.runs.title')}</h2>
            {detail.validationRuns.length === 0 ? (
              <p className="muted">{t('statutoryPacks.runs.empty')}</p>
            ) : (
              <ol className="plain-list">
                {detail.validationRuns.map((run) => (
                  <li key={run.runId} data-testid="statutory-pack-run" data-outcome={run.outcome}>
                    <StatusBadge tone={run.outcome === 'PASSED' ? 'success' : 'danger'}>
                      {t(`statutoryPacks.runs.outcome.${run.outcome}`)}
                    </StatusBadge>{' '}
                    {formatInstant(i18n.language, run.ranAt)}
                    {' · '}
                    {t('statutoryPacks.runs.counts', {
                      passed: run.checkCount - run.failedCount,
                      total: run.checkCount,
                    })}
                    <ul className="plain-list">
                      {run.checks.map((check) => (
                        <li key={check.code}>
                          <code>{check.code}</code>{' '}
                          {t(
                            check.passed
                              ? 'statutoryPacks.runs.checkPassed'
                              : 'statutoryPacks.runs.checkFailed',
                          )}
                        </li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ol>
            )}
          </section>

          <section className="card" aria-labelledby={`${ids}-history`}>
            <h2 id={`${ids}-history`}>{t('statutoryPacks.history.title')}</h2>
            {detail.review && (
              <p data-testid="statutory-pack-review">
                {t(`statutoryPacks.history.review.${detail.review.decision}`, {
                  date: formatInstant(i18n.language, detail.review.decidedAt),
                })}
              </p>
            )}
            {detail.history.length === 0 ? (
              <p className="muted">{t('statutoryPacks.history.empty')}</p>
            ) : (
              <ol className="plain-list" data-testid="statutory-pack-history">
                {detail.history.map((entry) => (
                  <li key={entry.seq}>
                    {t(`statutoryPacks.history.kind.${entry.kind}`)}
                    {' · '}
                    {t(`statutoryPacks.state.${entry.fromState}`)}
                    {' → '}
                    {t(`statutoryPacks.state.${entry.toState}`)}
                    {' · '}
                    {formatInstant(i18n.language, entry.occurredAt)}
                    {entry.byCaller ? ` · ${t('statutoryPacks.history.byYou')}` : ''}
                  </li>
                ))}
              </ol>
            )}
          </section>

          <PackActions
            detail={detail}
            onDone={(message) => {
              setAnnouncement(message);
              setRevision((value) => value + 1);
            }}
          />
        </>
      )}
    </section>
  );
}

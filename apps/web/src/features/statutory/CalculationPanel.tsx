import type {
  CommitStatutoryCalculation,
  StatutoryCalculationDetail,
  StatutoryCalculationPreview,
  StatutoryCalculationSummary,
  StatutoryTotals,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { formatMonth, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { Amount, PackStatus, StatutoryLabel } from './StatutoryBits';
import {
  BlockerItem,
  StatutoryExplanations,
  StatutoryLinesTable,
  type StatutoryLineView,
} from './StatutoryLines';
import { LINES_LIMIT, statutoryFailureOf, VERSION_LIMIT } from './statutory';
import { usePackDetail } from './usePackDetail';

/** The five aggregates as cards: server values, never summed in the browser. */
function TotalsCards({ totals, testId }: { totals: StatutoryTotals; testId: string }) {
  const { t } = useTranslation();
  const entries = [
    ['contractualGross', totals.contractualGross, t('payrollRuns.contractualGross')],
    ['employeeDeductions', totals.employeeDeductions, t('statutory.totals.employeeDeductions')],
    [
      'employerContributions',
      totals.employerContributions,
      t('statutory.totals.employerContributions'),
    ],
    ['previewNet', totals.previewNet, t('statutory.totals.previewNet')],
    ['employerCost', totals.employerCost, t('statutory.totals.employerCost')],
  ] as const;
  return (
    <ul className="statutory-totals" data-testid={testId}>
      {entries.map(([key, money, label]) => (
        <li key={key} data-testid={`statutory-total-${key}`}>
          <span className="muted">{label}</span>
          <strong>
            <Amount money={money} />
          </strong>
        </li>
      ))}
    </ul>
  );
}

function CalculationStatus({ status }: { status: 'DRAFT' | 'SUPERSEDED' }) {
  const { t } = useTranslation();
  return (
    <StatusBadge tone={status === 'DRAFT' ? 'warning' : 'neutral'} testId="statutory-calc-status">
      {t(`statutory.calculationStatus.${status}`)}
    </StatusBadge>
  );
}

/** A frozen calculation: totals, the per-employee table, explanations and keyset line pages. */
function FrozenCalculation({
  legalEntityId,
  runId,
  calculationId,
}: {
  legalEntityId: string;
  runId: string;
  calculationId: string;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const focus = useFocusRequest();
  const [state, setState] = useState<
    | { kind: 'loading' }
    | { kind: 'failed'; failure: PayrollFailure }
    | { kind: 'ready'; detail: StatutoryCalculationDetail }
  >({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const [moreFailure, setMoreFailure] = useState<PayrollFailure | null>(null);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/{calculationId}',
          {
            params: {
              path: { legalEntityId, runId, calculationId },
              query: cursor ? { cursor, limit: LINES_LIMIT } : { limit: LINES_LIMIT },
            },
            cache: 'no-store',
          },
        );
        return data
          ? { data, failure: null }
          : { data: null, failure: statutoryFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core, legalEntityId, runId, calculationId],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((result) => {
      if (!active) return;
      setState(
        result.data
          ? { kind: 'ready', detail: result.data }
          : { kind: 'failed', failure: result.failure },
      );
      focus.request('heading');
    });
    return () => {
      active = false;
    };
    // The focus helper is stable in behaviour; a new page must not reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchPage]);

  const detail = state.kind === 'ready' ? state.detail : null;
  const pack = usePackDetail(detail?.pack.packId ?? null);

  const loadMore = async () => {
    if (state.kind !== 'ready' || !state.detail.nextCursor || loading.current) return;
    loading.current = true;
    setMoreBusy(true);
    setMoreFailure(null);
    const result = await fetchPage(state.detail.nextCursor);
    if (result.data) {
      const page = result.data;
      setState((current) =>
        current.kind === 'ready'
          ? {
              kind: 'ready',
              detail: {
                ...current.detail,
                lines: [...current.detail.lines, ...page.lines],
                nextCursor: page.nextCursor,
              },
            }
          : current,
      );
    } else {
      setMoreFailure(result.failure);
    }
    loading.current = false;
    setMoreBusy(false);
  };

  const lines: StatutoryLineView[] = detail?.lines.map((line) => ({ ...line, blockers: [] })) ?? [];
  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="statutory-frozen"
      aria-busy={state.kind === 'loading' ? true : undefined}
    >
      {state.kind === 'loading' && <p role="status">{t('payrollRuns.loading')}</p>}
      {state.kind === 'failed' && (
        <PayrollAlert
          failure={state.failure}
          testId="statutory-frozen-error"
          alertRef={focus.register('heading')}
        />
      )}
      {detail && (
        <>
          <h3 id={`${ids}-title`} tabIndex={-1} ref={focus.register('heading')}>
            {t('statutory.frozen.title', { version: detail.calculation.version })}
          </h3>
          <StatutoryLabel testId="statutory-frozen-label" />
          <CalculationStatus status={detail.calculation.status} />
          <p data-testid="statutory-frozen-pack">
            {t('statutory.frozen.pack', {
              pack: t('statutory.pack.title', {
                code: detail.calculation.packCode,
                version: detail.calculation.packVersion,
              }),
            })}
          </p>
          <p className="muted">
            {t('statutory.frozen.createdAt', {
              at: formatInstant(i18n.language, detail.calculation.createdAt),
            })}
          </p>
          <TotalsCards totals={detail.calculation.totals} testId="statutory-frozen-totals" />
          <StatutoryLinesTable
            lines={lines}
            caption={t('statutory.frozen.linesCaption')}
            testId="statutory-frozen-lines"
          />
          {moreFailure && (
            <PayrollAlert failure={moreFailure} testId="statutory-frozen-more-error" />
          )}
          {detail.nextCursor && (
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--secondary"
                data-testid="statutory-frozen-more"
                disabled={moreBusy}
                onClick={() => void loadMore()}
              >
                {moreBusy ? t('payrollRuns.loadingMore') : t('payrollRuns.loadMoreLines')}
              </button>
            </div>
          )}
          <StatutoryExplanations
            lines={lines}
            rules={pack.kind === 'ready' ? pack.detail.components : []}
            sources={detail.sources}
          />
        </>
      )}
    </section>
  );
}

function RunBlockers({ preview }: { preview: StatutoryCalculationPreview }) {
  const { t } = useTranslation();
  const verification =
    preview.runBlockers.includes('RULE_PACK_NOT_VERIFIED') ||
    preview.runBlockers.includes('RULE_PACK_MISSING');
  return (
    <div className="error-summary" role="alert" data-testid="statutory-run-blockers">
      {verification && (
        <p>
          <strong data-testid="statutory-preview-verification">
            {t('statutory.verificationRequired')}
          </strong>
        </p>
      )}
      <p>{t('statutory.preview.runBlockersTitle')}</p>
      <ul>
        {preview.runBlockers.map((blocker) => (
          <li key={blocker} data-code={blocker} data-testid="statutory-run-blocker">
            {t(`statutory.runBlockers.${blocker}`)}
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * The statutory calculation of one frozen payroll run: a server preview (status, pack, blockers,
 * totals, per-employee results and explanations), an explicit confirmation that repeats the
 * evidence the preview returned with an Idempotency-Key (reused for a retry of the same payload),
 * the immutable versions and the frozen calculation. There is no approval, payslip, filing or
 * payment control, and nothing is calculated in the browser.
 */
export function CalculationPanel({
  legalEntityId,
  runId,
  resetToken,
}: {
  legalEntityId: string;
  runId: string;
  resetToken: number;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const focus = useFocusRequest();
  const [preview, setPreview] = useState<StatutoryCalculationPreview | null>(null);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [opened, setOpened] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const [versions, setVersions] = useState<
    | { kind: 'loading' }
    | { kind: 'failed'; failure: PayrollFailure }
    | { kind: 'ready'; items: StatutoryCalculationSummary[]; nextCursor: string | null }
  >({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);
  const pack = usePackDetail(preview?.pack?.packId ?? null);

  // A newly activated pack makes a displayed preview out of date.
  const [seenReset, setSeenReset] = useState(resetToken);
  if (seenReset !== resetToken) {
    setSeenReset(resetToken);
    setPreview(null);
    commitKey.reset();
  }

  const fetchVersions = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET(
          '/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations',
          {
            params: {
              path: { legalEntityId, runId },
              query: cursor ? { cursor, limit: VERSION_LIMIT } : { limit: VERSION_LIMIT },
            },
            cache: 'no-store',
          },
        );
        return data
          ? { data, failure: null }
          : { data: null, failure: statutoryFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core, legalEntityId, runId],
  );

  useEffect(() => {
    let active = true;
    void fetchVersions(null).then((result) => {
      if (!active) return;
      setVersions(
        result.data
          ? { kind: 'ready', items: result.data.items, nextCursor: result.data.nextCursor }
          : { kind: 'failed', failure: result.failure },
      );
    });
    return () => {
      active = false;
    };
  }, [fetchVersions, revision]);

  const loadMoreVersions = async () => {
    if (versions.kind !== 'ready' || !versions.nextCursor || loadingMore.current) return;
    loadingMore.current = true;
    setMoreBusy(true);
    const result = await fetchVersions(versions.nextCursor);
    if (result.data) {
      const page = result.data;
      setVersions((current) =>
        current.kind === 'ready'
          ? { kind: 'ready', items: [...current.items, ...page.items], nextCursor: page.nextCursor }
          : current,
      );
    }
    loadingMore.current = false;
    setMoreBusy(false);
  };

  const onPreview = async () => {
    if (busy) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations/preview',
        { params: { path: { legalEntityId, runId } }, body: {}, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(statutoryFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const creatable =
    preview !== null &&
    preview.status === 'READY' &&
    preview.calculationDigest !== null &&
    preview.rulePackDigest !== null &&
    preview.expiresAt !== null;
  const whyKey =
    preview === null
      ? null
      : preview.status === 'NO_CHANGE'
        ? 'statutory.preview.whyNoChange'
        : preview.status === 'BLOCKED'
          ? 'statutory.preview.whyBlocked'
          : creatable
            ? null
            : 'statutory.preview.whyBlocked';

  const onCommit = async () => {
    if (!preview || busy || !creatable) return;
    if (
      preview.calculationDigest === null ||
      preview.rulePackDigest === null ||
      preview.expiresAt === null
    ) {
      return;
    }
    const body: CommitStatutoryCalculation = {
      expectedVersion: preview.expectedVersion,
      sourceRunDigest: preview.sourceRunDigest,
      profileDigest: preview.profileDigest,
      rulePackDigest: preview.rulePackDigest,
      calculationDigest: preview.calculationDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations',
        {
          params: {
            path: { legalEntityId, runId },
            header: { 'Idempotency-Key': commitKey.keyFor({ legalEntityId, runId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        setPreview(null);
        setOpened(data.calculationId);
        setRevision((value) => value + 1);
        setAnnouncement(t('statutory.create.announce'));
        return;
      }
      const next = statutoryFailureOf(response, error);
      if (next.kind === 'stale') {
        // The preview is out of date: a new one is needed, with a new key.
        commitKey.reset();
        setPreview(null);
      }
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const blockedLines = preview?.lines.filter((line) => line.blockers.length > 0) ?? [];
  return (
    <>
      <section
        className="card"
        aria-labelledby={`${ids}-title`}
        data-testid="statutory-calculation"
      >
        <h2 id={`${ids}-title`}>{t('statutory.calculation.title')}</h2>
        <StatutoryLabel testId="statutory-calculation-label" />
        <p className="muted">{t('statutory.calculation.help')}</p>
        <p role="status" className="visually-hidden" data-testid="statutory-calculation-announcer">
          {announcement}
        </p>
        <div className="dialog-actions">
          <button
            type="button"
            className="button"
            disabled={busy !== null}
            data-testid="statutory-preview-button"
            onClick={() => void onPreview()}
          >
            {busy === 'preview' ? t('payrollRuns.previewing') : t('statutory.calculation.preview')}
          </button>
        </div>
        {failure && (
          <PayrollAlert
            failure={failure}
            testId="statutory-calculation-failure"
            alertRef={focus.register('failure')}
          />
        )}
        {preview && (
          <section aria-labelledby={`${ids}-preview`} data-testid="statutory-preview">
            <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
              {t('statutory.preview.heading', {
                period: formatMonth(i18n.language, preview.period),
                currency: preview.currency,
              })}
            </h3>
            <StatutoryLabel testId="statutory-preview-label" />
            <StatusBadge
              tone={
                preview.status === 'READY'
                  ? 'success'
                  : preview.status === 'BLOCKED'
                    ? 'danger'
                    : 'neutral'
              }
              testId="statutory-preview-status"
            >
              {t(`statutory.previewStatus.${preview.status}`)}
            </StatusBadge>
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('statutory.preview.pack')}</dt>
              <dd data-testid="statutory-preview-pack">
                {preview.pack ? (
                  <>
                    {t('statutory.pack.title', {
                      code: preview.pack.code,
                      version: preview.pack.version,
                    })}{' '}
                    <PackStatus status={preview.pack.status} />
                  </>
                ) : (
                  t('statutory.preview.noPack')
                )}
              </dd>
              <dt>{t('statutory.preview.supported')}</dt>
              <dd data-testid="statutory-preview-supported">{preview.supportedCount}</dd>
              <dt>{t('statutory.preview.excluded')}</dt>
              <dd data-testid="statutory-preview-excluded">{preview.excludedCount}</dd>
              <dt>{t('statutory.preview.blocked')}</dt>
              <dd data-testid="statutory-preview-blocked">{preview.blockedCount}</dd>
            </dl>
            {preview.runBlockers.length > 0 && <RunBlockers preview={preview} />}
            {blockedLines.length > 0 && (
              <div className="error-summary" role="alert" data-testid="statutory-employee-blockers">
                <p>{t('statutory.preview.employeeBlockersTitle')}</p>
                <ul>
                  {blockedLines.map((line) => (
                    <li key={line.employeeId} data-testid="statutory-employee-blocker">
                      <strong>{line.employeeNumber}</strong>
                      <ul>
                        {line.blockers.map((blocker) => (
                          <BlockerItem
                            key={blocker}
                            blocker={blocker}
                            employeeId={line.employeeId}
                            employeeNumber={line.employeeNumber}
                          />
                        ))}
                      </ul>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {preview.totals ? (
              <TotalsCards totals={preview.totals} testId="statutory-preview-totals" />
            ) : (
              <p className="muted" data-testid="statutory-preview-no-totals">
                {t('statutory.preview.noTotals')}
              </p>
            )}
            {preview.status === 'NO_CHANGE' && (
              <div className="card" role="status" data-testid="statutory-preview-identical">
                <p>{t('statutory.preview.identical')}</p>
                {preview.existingCalculation && (
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => {
                      setOpened(preview.existingCalculation?.calculationId ?? null);
                    }}
                  >
                    {t('statutory.versions.open', { version: preview.existingCalculation.version })}
                  </button>
                )}
              </div>
            )}
            {preview.status !== 'NO_CHANGE' && preview.existingCalculation && (
              <p className="muted" data-testid="statutory-preview-existing">
                {t('statutory.preview.existing', { version: preview.existingCalculation.version })}
              </p>
            )}
            {preview.lines.length === 0 ? (
              <p className="muted">{t('statutory.preview.noLines')}</p>
            ) : (
              <StatutoryLinesTable
                lines={preview.lines}
                caption={t('statutory.preview.linesCaption')}
                testId="statutory-preview-lines"
              />
            )}
            <StatutoryExplanations
              lines={preview.lines}
              rules={pack.kind === 'ready' ? pack.detail.components : []}
              sources={pack.kind === 'ready' ? pack.detail.sources : []}
            />
            {creatable && preview.expiresAt && (
              <p className="muted">
                {t('payrollRuns.preview.expires', {
                  at: formatInstant(i18n.language, preview.expiresAt),
                })}
              </p>
            )}
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                data-testid="statutory-create-confirm"
                disabled={busy !== null || !creatable}
                aria-describedby={whyKey ? `${ids}-why` : undefined}
                onClick={() => void onCommit()}
              >
                {busy === 'commit' ? t('payrollRuns.saving') : t('statutory.create.confirm')}
              </button>
            </div>
            {whyKey && (
              <p id={`${ids}-why`} className="muted" data-testid="statutory-create-why">
                {t(whyKey)}
              </p>
            )}
          </section>
        )}
      </section>

      <section
        className="card"
        aria-labelledby={`${ids}-versions`}
        data-testid="statutory-versions"
      >
        <h2 id={`${ids}-versions`}>{t('statutory.versions.title')}</h2>
        <StatutoryLabel testId="statutory-versions-label" />
        {versions.kind === 'loading' && <p role="status">{t('payrollRuns.loading')}</p>}
        {versions.kind === 'failed' && (
          <PayrollAlert failure={versions.failure} testId="statutory-versions-error" />
        )}
        {versions.kind === 'ready' &&
          (versions.items.length === 0 ? (
            <p className="muted" data-testid="statutory-versions-empty">
              {t('statutory.versions.empty')}
            </p>
          ) : (
            <ScrollRegion label={t('statutory.versions.tableLabel')}>
              <table className="timesheet-table" data-testid="statutory-versions-table">
                <caption className="visually-hidden">{t('statutory.versions.tableLabel')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('statutory.versions.version')}</th>
                    <th scope="col">{t('statutory.versions.status')}</th>
                    <th scope="col">{t('statutory.preview.pack')}</th>
                    <th scope="col">{t('statutory.preview.supported')}</th>
                    <th scope="col">{t('statutory.versions.createdAt')}</th>
                    <th scope="col">{t('statutory.versions.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {versions.items.map((item) => (
                    <tr
                      key={item.calculationId}
                      data-testid="statutory-version-row"
                      data-status={item.status}
                    >
                      <th scope="row">{item.version}</th>
                      <td>
                        <CalculationStatus status={item.status} />
                      </td>
                      <td>
                        {t('statutory.pack.title', {
                          code: item.packCode,
                          version: item.packVersion,
                        })}
                      </td>
                      <td>{item.supportedCount}</td>
                      <td>{formatInstant(i18n.language, item.createdAt)}</td>
                      <td>
                        <button
                          type="button"
                          className="button button--secondary"
                          data-testid="statutory-version-open"
                          aria-expanded={opened === item.calculationId}
                          onClick={() => {
                            setOpened(item.calculationId);
                          }}
                        >
                          {t('statutory.versions.open', { version: item.version })}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
          ))}
        {versions.kind === 'ready' && versions.nextCursor && (
          <div className="dialog-actions">
            <button
              type="button"
              className="button button--secondary"
              data-testid="statutory-versions-more"
              disabled={moreBusy}
              onClick={() => void loadMoreVersions()}
            >
              {moreBusy ? t('payrollRuns.loadingMore') : t('statutory.versions.loadMore')}
            </button>
          </div>
        )}
      </section>

      {opened && (
        <FrozenCalculation
          key={opened}
          legalEntityId={legalEntityId}
          runId={runId}
          calculationId={opened}
        />
      )}
    </>
  );
}

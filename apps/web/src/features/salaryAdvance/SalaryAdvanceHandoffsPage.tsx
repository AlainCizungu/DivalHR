import type {
  SalaryAdvanceHandoffReviewState,
  SalaryAdvanceHandoffState,
  SalaryAdvanceHandoffSummary,
  SalaryAdvanceHandoffView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { CompensationAlert, Money } from '../compensation/CompensationBits';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { HandoffNotice, HandoffReview, HandoffState } from './HandoffBits';
import { RetentionPanel } from './RetentionPanel';
import { LenderNotice } from './SalaryAdvanceBits';
import {
  HANDOFF_STATES,
  handoffReconcilable,
  NETWORK_FAILURE,
  PAGE_LIMIT,
  REVIEW_STATES,
  salaryAdvanceFailureOf,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

type Page = { items: SalaryAdvanceHandoffSummary[]; nextCursor: string | null };

/** One handoff in full: closed history, safe delivery diagnostics and the manual reconcile. */
function HandoffDetail({ handoffId, onChanged }: { handoffId: string; onChanged: () => void }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const reconcileKey = useIdempotencyKey();
  const [view, setView] = useState<SalaryAdvanceHandoffView | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    void core
      .GET('/salary-advance-handoffs/{handoffId}', {
        params: { path: { handoffId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setView(data);
            setFailure(null);
          } else {
            setFailure(salaryAdvanceFailureOf(response, error));
          }
        },
        () => {
          if (active) setFailure(NETWORK_FAILURE);
        },
      );
    return () => {
      active = false;
    };
  }, [core, handoffId, revision]);

  const reconcile = async () => {
    if (!view) return;
    const body = { expectedVersion: view.version, confirmed: true as const };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/salary-advance-handoffs/{handoffId}/reconcile',
        {
          params: {
            path: { handoffId },
            header: { 'Idempotency-Key': reconcileKey.keyFor({ handoffId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        reconcileKey.reset();
        setConfirming(false);
        setRevision((value) => value + 1);
        onChanged();
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="handoff-detail">
      <h2 id={`${ids}-title`}>{t('salaryAdvance.handoff.admin.detailTitle')}</h2>
      {failure && <CompensationAlert failure={failure} testId="handoff-detail-error" />}
      {view && (
        <>
          <dl>
            <dt>{t('salaryAdvance.col.employee')}</dt>
            <dd>{view.employeeNumber ?? '—'}</dd>
            <dt>{t('salaryAdvance.handoff.col.delivery')}</dt>
            <dd>
              <HandoffState state={view.state} />
            </dd>
            <dt>{t('salaryAdvance.handoff.col.review')}</dt>
            <dd>
              <HandoffReview review={view.reviewState} />
              {view.reviewReason && (
                <span className="muted">
                  {' '}
                  · {t(`salaryAdvance.handoff.reviewReason.${view.reviewReason}`)}
                </span>
              )}
            </dd>
            <dt>{t('salaryAdvance.col.amount')}</dt>
            <dd>
              <Money money={view.requested} />
            </dd>
            <dt>{t('salaryAdvance.me.ceiling')}</dt>
            <dd>
              <Money money={view.ceiling} />
            </dd>
            <dt>{t('salaryAdvance.handoff.admin.applicationId')}</dt>
            <dd data-testid="handoff-application-id">{view.applicationId ?? '—'}</dd>
            <dt>{t('salaryAdvance.handoff.admin.consentLine')}</dt>
            <dd>
              {t('salaryAdvance.requests.consentLine', {
                version: view.consent.version,
                language: view.consent.language.toUpperCase(),
                at: formatInstant(i18n.language, view.consent.consentedAt),
              })}
            </dd>
            <dt>{t('salaryAdvance.handoff.admin.attemptCount')}</dt>
            <dd>{view.attemptCount}</dd>
            {view.lastOutcomeCode && (
              <>
                <dt>{t('salaryAdvance.handoff.admin.lastOutcome')}</dt>
                <dd>{t(`salaryAdvance.handoff.outcome.${view.lastOutcomeCode}`)}</dd>
              </>
            )}
          </dl>
          <h3>{t('salaryAdvance.handoff.admin.history')}</h3>
          <ol data-testid="handoff-events">
            {view.events.map((event) => (
              <li key={event.sequence}>
                {t(`salaryAdvance.handoff.state.${event.toState}`)} ·{' '}
                {t(`salaryAdvance.handoff.actor.${event.actorRole}`)}
                {event.outcomeCode
                  ? ` · ${t(`salaryAdvance.handoff.outcome.${event.outcomeCode}`)}`
                  : ''}{' '}
                · {formatInstant(i18n.language, event.occurredAt)}
              </li>
            ))}
          </ol>
          {view.reviewEvents.length > 0 && (
            <>
              <h3>{t('salaryAdvance.handoff.admin.reviewHistory')}</h3>
              <ol data-testid="handoff-review-events">
                {view.reviewEvents.map((event) => (
                  <li key={event.sequence}>
                    {t(`salaryAdvance.handoff.review.${event.toState}`)}
                    {event.reasonCode
                      ? ` · ${t(`salaryAdvance.handoff.reviewReason.${event.reasonCode}`)}`
                      : ''}{' '}
                    · {formatInstant(i18n.language, event.occurredAt)}
                  </li>
                ))}
              </ol>
            </>
          )}
          {view.attempts.length > 0 && (
            <>
              <h3>{t('salaryAdvance.handoff.admin.attempts')}</h3>
              <ol data-testid="handoff-attempts">
                {view.attempts.map((attempt) => (
                  <li key={attempt.attempt}>
                    {t('salaryAdvance.handoff.admin.attemptLine', { number: attempt.attempt })} ·{' '}
                    {t(`salaryAdvance.handoff.outcome.${attempt.outcomeCode}`)} ·{' '}
                    {formatInstant(i18n.language, attempt.startedAt)}
                  </li>
                ))}
              </ol>
            </>
          )}
          {handoffReconcilable(view.state) && !confirming && (
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                data-testid="handoff-reconcile-open"
                onClick={() => {
                  setConfirming(true);
                }}
              >
                {t(
                  view.state === 'FAILED'
                    ? 'salaryAdvance.handoff.admin.retry'
                    : 'salaryAdvance.handoff.admin.reconcile',
                )}
              </button>
            </div>
          )}
          {confirming && (
            <div data-testid="handoff-reconcile-panel">
              <p>
                {t(
                  view.state === 'FAILED'
                    ? 'salaryAdvance.handoff.admin.retryStatement'
                    : 'salaryAdvance.handoff.admin.reconcileStatement',
                )}
              </p>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  data-testid="handoff-reconcile"
                  onClick={() => void reconcile()}
                >
                  {t('salaryAdvance.handoff.admin.confirmAction')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setConfirming(false);
                  }}
                >
                  {t('salaryAdvance.requests.back')}
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}

/**
 * MVP-120B: « Transmissions au partenaire » / "Partner handoffs" under Payroll. A tenant
 * administrator with exact MFA sees the delivery and bounded review status of controlled handoffs
 * to the synthetic test partner and can run the manual reconcile of an ambiguous or failed
 * delivery. The administrator can never create or change an employee's consent, and nothing here is
 * a decision, a payout or a deduction.
 */
export function SalaryAdvanceHandoffsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [state, setState] = useState<SalaryAdvanceHandoffState | ''>('');
  const [review, setReview] = useState<SalaryAdvanceHandoffReviewState | ''>('');
  const [page, setPage] = useState<Page | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET('/salary-advance-handoffs', {
          params: {
            query: {
              limit: PAGE_LIMIT,
              ...(cursor ? { cursor } : {}),
              ...(state ? { state } : {}),
              ...(review ? { reviewState: review } : {}),
            },
          },
          cache: 'no-store',
        });
        return data ? { data } : { failure: salaryAdvanceFailureOf(response, error) };
      } catch {
        return { failure: NETWORK_FAILURE };
      }
    },
    [core, state, review],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((result) => {
      if (!active) return;
      if (result.data) {
        setPage(result.data);
        setFailure(null);
      } else {
        setPage(null);
        setFailure(result.failure);
      }
    });
    return () => {
      active = false;
    };
  }, [fetchPage, revision]);

  const loadMore = async () => {
    if (!page?.nextCursor || loading.current) return;
    loading.current = true;
    const result = await fetchPage(page.nextCursor);
    if (result.data) {
      const next = result.data;
      setPage((current) =>
        current ? { items: [...current.items, ...next.items], nextCursor: next.nextCursor } : next,
      );
    } else {
      setFailure(result.failure);
    }
    loading.current = false;
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="salary-advance-handoffs-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('salaryAdvance.handoff.admin.title')}
        description={t('salaryAdvance.handoff.admin.description')}
      />
      <LenderNotice />
      <HandoffNotice />
      <section className="card">
        <div className="field">
          <label htmlFor={`${ids}-state`}>{t('salaryAdvance.handoff.admin.filter')}</label>
          <select
            id={`${ids}-state`}
            value={state}
            data-testid="handoffs-filter"
            onChange={(event) => {
              setState(event.target.value as SalaryAdvanceHandoffState | '');
              setSelected(null);
            }}
          >
            <option value="">{t('salaryAdvance.requests.allStatuses')}</option>
            {HANDOFF_STATES.map((code) => (
              <option key={code} value={code}>
                {t(`salaryAdvance.handoff.state.${code}`)}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor={`${ids}-review`}>{t('salaryAdvance.handoff.admin.filterReview')}</label>
          <select
            id={`${ids}-review`}
            value={review}
            data-testid="handoffs-review-filter"
            onChange={(event) => {
              setReview(event.target.value as SalaryAdvanceHandoffReviewState | '');
              setSelected(null);
            }}
          >
            <option value="">{t('salaryAdvance.requests.allStatuses')}</option>
            {REVIEW_STATES.map((code) => (
              <option key={code} value={code}>
                {t(`salaryAdvance.handoff.review.${code}`)}
              </option>
            ))}
          </select>
        </div>
        {failure && <CompensationAlert failure={failure} testId="handoffs-list-error" />}
        {!page && !failure && <p role="status">{t('salaryAdvance.loading')}</p>}
        {page && page.items.length === 0 && (
          <p className="muted" data-testid="handoffs-list-empty">
            {t('salaryAdvance.handoff.admin.empty')}
          </p>
        )}
        {page && page.items.length > 0 && (
          <ScrollRegion label={t('salaryAdvance.handoff.admin.table')}>
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('salaryAdvance.handoff.col.created')}</th>
                  <th scope="col">{t('salaryAdvance.col.employee')}</th>
                  <th scope="col">{t('salaryAdvance.col.amount')}</th>
                  <th scope="col">{t('salaryAdvance.handoff.col.delivery')}</th>
                  <th scope="col">{t('salaryAdvance.handoff.col.review')}</th>
                  <th scope="col">{t('salaryAdvance.col.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((item) => (
                  <tr key={item.handoffId} data-testid="handoff-admin-row" data-state={item.state}>
                    <th scope="row">{formatInstant(i18n.language, item.createdAt)}</th>
                    <td>{item.employeeNumber ?? '—'}</td>
                    <td>
                      <Money money={item.requested} />
                    </td>
                    <td>
                      <HandoffState state={item.state} />
                    </td>
                    <td>
                      <HandoffReview review={item.reviewState} />
                    </td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        data-testid="handoff-open"
                        aria-label={t('salaryAdvance.handoff.admin.openContext', {
                          date: formatInstant(i18n.language, item.createdAt),
                        })}
                        onClick={() => {
                          setSelected(item.handoffId);
                        }}
                      >
                        {t('salaryAdvance.requests.open')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {page?.nextCursor && (
          <div className="dialog-actions">
            <button
              type="button"
              className="button button--secondary"
              data-testid="handoffs-more"
              onClick={() => void loadMore()}
            >
              {t('salaryAdvance.loadMore')}
            </button>
          </div>
        )}
      </section>
      <RetentionPanel />
      {selected && (
        <HandoffDetail
          key={selected + String(revision)}
          handoffId={selected}
          onChanged={() => {
            setRevision((value) => value + 1);
          }}
        />
      )}
    </section>
  );
}

import type {
  SalaryAdvanceRefusalReason,
  SalaryAdvanceRequestStatus,
  SalaryAdvanceRequestSummary,
  SalaryAdvanceRequestView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatDate, formatInstant } from '../compensation/compensation';
import { CompensationAlert, Money } from '../compensation/CompensationBits';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { LenderNotice, RequestStatus } from './SalaryAdvanceBits';
import {
  NETWORK_FAILURE,
  NOTE_MAX,
  PAGE_LIMIT,
  REFUSAL_REASONS,
  salaryAdvanceFailureOf,
  STATUSES,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

type Page = { items: SalaryAdvanceRequestSummary[]; nextCursor: string | null };

/** One request in full, with the employer's two decisions while it is still SUBMITTED. */
function RequestDetail({ requestId, onChanged }: { requestId: string; onChanged: () => void }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const decisionKey = useIdempotencyKey();
  const [view, setView] = useState<SalaryAdvanceRequestView | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<'none' | 'confirm' | 'refuse'>('none');
  const [reason, setReason] = useState<SalaryAdvanceRefusalReason | ''>('');
  const [note, setNote] = useState('');
  const [reasonBad, setReasonBad] = useState(false);

  useEffect(() => {
    let active = true;
    void core
      .GET('/salary-advance-requests/{requestId}', {
        params: { path: { requestId } },
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
  }, [core, requestId]);

  const decide = async (kind: 'confirm' | 'refuse') => {
    if (!view) return;
    if (kind === 'refuse' && !reason) {
      setReasonBad(true);
      return;
    }
    setBusy(true);
    setFailure(null);
    try {
      const key = (payload: unknown) => decisionKey.keyFor({ requestId, kind, payload });
      const result =
        kind === 'confirm'
          ? await core.POST('/salary-advance-requests/{requestId}/confirm-employer', {
              params: {
                path: { requestId },
                header: {
                  'Idempotency-Key': key({ v: view.version }),
                },
              },
              body: { expectedVersion: view.version, confirmed: true },
            })
          : await core.POST('/salary-advance-requests/{requestId}/refuse-employer', {
              params: {
                path: { requestId },
                header: { 'Idempotency-Key': key({ v: view.version, reason, note }) },
              },
              body: {
                expectedVersion: view.version,
                reasonCode: reason as SalaryAdvanceRefusalReason,
                note: note.trim() ? note.trim() : null,
                confirmed: true,
              },
            });
      if (result.data) {
        decisionKey.reset();
        setMode('none');
        onChanged();
      } else {
        setFailure(salaryAdvanceFailureOf(result.response, result.error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="request-detail">
      <h2 id={`${ids}-title`}>{t('salaryAdvance.requests.detailTitle')}</h2>
      {failure && <CompensationAlert failure={failure} testId="request-detail-error" />}
      {view && (
        <>
          <dl>
            <dt>{t('salaryAdvance.col.employee')}</dt>
            <dd>{view.employeeNumber ?? t('salaryAdvance.requests.unknownEmployee')}</dd>
            <dt>{t('salaryAdvance.col.status')}</dt>
            <dd>
              <RequestStatus status={view.status} />
            </dd>
            <dt>{t('salaryAdvance.col.amount')}</dt>
            <dd>
              <Money money={view.requested} />
            </dd>
            <dt>{t('salaryAdvance.me.ceiling')}</dt>
            <dd>
              <Money money={view.ceiling} />
            </dd>
            <dt>{t('salaryAdvance.requests.reference')}</dt>
            <dd>{view.referencePeriod}</dd>
            <dt>{t('salaryAdvance.requests.tenure')}</dt>
            <dd>{t('salaryAdvance.requests.tenureMonths', { count: view.tenureMonths })}</dd>
            <dt>{t('salaryAdvance.requests.consent')}</dt>
            <dd>
              {t('salaryAdvance.requests.consentLine', {
                version: view.consent.version,
                language: view.consent.language.toUpperCase(),
                at: formatInstant(i18n.language, view.consent.consentedAt),
              })}
            </dd>
            {view.refusalReason && (
              <>
                <dt>{t('salaryAdvance.requests.refusalReason')}</dt>
                <dd>{t(`salaryAdvance.refusal.${view.refusalReason}`)}</dd>
              </>
            )}
            {view.internalNote && (
              <>
                <dt>{t('salaryAdvance.requests.note')}</dt>
                <dd data-testid="request-note">{view.internalNote}</dd>
              </>
            )}
          </dl>
          <h3>{t('salaryAdvance.requests.lifecycle')}</h3>
          <ol data-testid="request-lifecycle">
            {view.lifecycle.map((entry) => (
              <li key={entry.sequence}>
                {t(`salaryAdvance.status.${entry.toStatus}`)} ·{' '}
                {t(`salaryAdvance.actor.${entry.actorRole}`)} ·{' '}
                {formatInstant(i18n.language, entry.occurredAt)}
              </li>
            ))}
          </ol>
          {view.status === 'SUBMITTED' && mode === 'none' && (
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                data-testid="request-confirm-open"
                onClick={() => {
                  setMode('confirm');
                }}
              >
                {t('salaryAdvance.requests.confirm')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                data-testid="request-refuse-open"
                onClick={() => {
                  setMode('refuse');
                }}
              >
                {t('salaryAdvance.requests.refuse')}
              </button>
            </div>
          )}
          {mode === 'confirm' && (
            <div data-testid="request-confirm-panel">
              <p>{t('salaryAdvance.requests.confirmStatement')}</p>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  data-testid="request-confirm"
                  onClick={() => void decide('confirm')}
                >
                  {t('salaryAdvance.requests.confirmAction')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setMode('none');
                  }}
                >
                  {t('salaryAdvance.requests.back')}
                </button>
              </div>
            </div>
          )}
          {mode === 'refuse' && (
            <div data-testid="request-refuse-panel">
              <div className={reasonBad ? 'field field--invalid' : 'field'}>
                <label htmlFor={`${ids}-reason`}>{t('salaryAdvance.requests.refusalReason')}</label>
                <select
                  id={`${ids}-reason`}
                  value={reason}
                  aria-invalid={reasonBad}
                  data-testid="request-refuse-reason"
                  onChange={(event) => {
                    setReason(event.target.value as SalaryAdvanceRefusalReason | '');
                    setReasonBad(false);
                  }}
                >
                  <option value="">{t('salaryAdvance.requests.choose')}</option>
                  {REFUSAL_REASONS.map((code) => (
                    <option key={code} value={code}>
                      {t(`salaryAdvance.refusal.${code}`)}
                    </option>
                  ))}
                </select>
                {reasonBad && (
                  <p className="field__error" role="alert">
                    {t('salaryAdvance.requests.reasonRequired')}
                  </p>
                )}
              </div>
              <div className="field">
                <label htmlFor={`${ids}-note`}>{t('salaryAdvance.requests.noteLabel')}</label>
                <textarea
                  id={`${ids}-note`}
                  maxLength={NOTE_MAX}
                  value={note}
                  data-testid="request-refuse-note"
                  onChange={(event) => {
                    setNote(event.target.value);
                  }}
                />
                <p className="muted">{t('salaryAdvance.requests.noteHint')}</p>
              </div>
              <div className="dialog-actions">
                <button
                  type="button"
                  className="button button--danger"
                  disabled={busy}
                  data-testid="request-refuse"
                  onClick={() => void decide('refuse')}
                >
                  {t('salaryAdvance.requests.refuseAction')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  onClick={() => {
                    setMode('none');
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
 * MVP-120A: « Demandes d’avance » / "Salary-advance requests" under Payroll. A tenant
 * administrator with exact MFA reviews the requests of the organization, newest first, and either
 * confirms the employer's participation or refuses with a closed reason. A confirmation is not
 * credit approval; nothing is sent to any institution and no funds move.
 */
export function SalaryAdvanceRequestsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [status, setStatus] = useState<SalaryAdvanceRequestStatus | ''>('');
  const [page, setPage] = useState<Page | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET('/salary-advance-requests', {
          params: {
            query: {
              limit: PAGE_LIMIT,
              ...(cursor ? { cursor } : {}),
              ...(status ? { status } : {}),
            },
          },
          cache: 'no-store',
        });
        return data ? { data } : { failure: salaryAdvanceFailureOf(response, error) };
      } catch {
        return { failure: NETWORK_FAILURE };
      }
    },
    [core, status],
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
    <section aria-labelledby={`${ids}-title`} data-testid="salary-advance-requests-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('salaryAdvance.requests.title')}
        description={t('salaryAdvance.requests.description')}
      />
      <LenderNotice />
      <section className="card">
        <div className="field">
          <label htmlFor={`${ids}-status`}>{t('salaryAdvance.requests.filter')}</label>
          <select
            id={`${ids}-status`}
            value={status}
            data-testid="requests-filter"
            onChange={(event) => {
              setStatus(event.target.value as SalaryAdvanceRequestStatus | '');
              setSelected(null);
            }}
          >
            <option value="">{t('salaryAdvance.requests.allStatuses')}</option>
            {STATUSES.map((code) => (
              <option key={code} value={code}>
                {t(`salaryAdvance.status.${code}`)}
              </option>
            ))}
          </select>
        </div>
        {failure && <CompensationAlert failure={failure} testId="requests-list-error" />}
        {!page && !failure && <p role="status">{t('salaryAdvance.loading')}</p>}
        {page && page.items.length === 0 && (
          <p className="muted" data-testid="requests-list-empty">
            {t('salaryAdvance.requests.empty')}
          </p>
        )}
        {page && page.items.length > 0 && (
          <ScrollRegion label={t('salaryAdvance.requests.table')}>
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('salaryAdvance.col.submitted')}</th>
                  <th scope="col">{t('salaryAdvance.col.employee')}</th>
                  <th scope="col">{t('salaryAdvance.col.amount')}</th>
                  <th scope="col">{t('salaryAdvance.col.status')}</th>
                  <th scope="col">{t('salaryAdvance.col.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((item) => (
                  <tr key={item.requestId} data-testid="request-row" data-status={item.status}>
                    <th scope="row">{formatInstant(i18n.language, item.submittedAt)}</th>
                    <td>{item.employeeNumber ?? '—'}</td>
                    <td>
                      <Money money={item.requested} />
                    </td>
                    <td>
                      <RequestStatus status={item.status} />
                    </td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        data-testid="request-open"
                        aria-label={t('salaryAdvance.requests.openContext', {
                          date: formatDate(i18n.language, item.submittedAt.slice(0, 10)),
                        })}
                        onClick={() => {
                          setSelected(item.requestId);
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
              data-testid="requests-more"
              onClick={() => void loadMore()}
            >
              {t('salaryAdvance.loadMore')}
            </button>
          </div>
        )}
      </section>
      {selected && (
        <RequestDetail
          key={selected + String(revision)}
          requestId={selected}
          onChanged={() => {
            setRevision((value) => value + 1);
          }}
        />
      )}
    </section>
  );
}

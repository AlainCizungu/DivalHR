import type {
  SalaryAdvanceHandoffPreview,
  SalaryAdvanceHandoffSummary,
  SalaryAdvanceRequestSummary,
} from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { formatInstant } from '../compensation/compensation';
import { CompensationAlert, Money } from '../compensation/CompensationBits';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { HandoffNotice, HandoffReview, HandoffState } from './HandoffBits';
import { ReviewTimeline } from './ReviewTimeline';
import {
  handoffLive,
  handoffWithdrawable,
  NETWORK_FAILURE,
  PAGE_LIMIT,
  salaryAdvanceFailureOf,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

/** The closed handoff disclosure, translated from stable codes (never free text). */
function HandoffDisclosure({ consent }: { consent: SalaryAdvanceHandoffPreview['consent'] }) {
  const { t } = useTranslation();
  return (
    <div data-testid="handoff-disclosure" data-version={consent.version}>
      <p>{t('salaryAdvance.handoff.disclosure.purpose')}</p>
      <h4>{t('salaryAdvance.handoff.disclosure.shared')}</h4>
      <ul>
        {consent.shared.map((item) => (
          <li key={item}>{t(`salaryAdvance.handoff.disclosure.sharedItem.${item}`)}</li>
        ))}
      </ul>
      <h4>{t('salaryAdvance.handoff.disclosure.notShared')}</h4>
      <ul>
        {consent.notShared.map((item) => (
          <li key={item}>{t(`salaryAdvance.handoff.disclosure.notSharedItem.${item}`)}</li>
        ))}
      </ul>
      <p>{t('salaryAdvance.handoff.disclosure.promise')}</p>
      <p>{t('salaryAdvance.handoff.disclosure.withdrawal')}</p>
      <p>{t('salaryAdvance.handoff.disclosure.retention')}</p>
    </div>
  );
}

/** One employer-confirmed request: preview the disclosure, then authorize with two fresh affirmations. */
function HandoffStart({
  request,
  onAuthorized,
}: {
  request: SalaryAdvanceRequestSummary;
  onAuthorized: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const [preview, setPreview] = useState<SalaryAdvanceHandoffPreview | null>(null);
  const [affirmed, setAffirmed] = useState(false);
  const [understands, setUnderstands] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const language: 'fr' | 'en' = i18n.language.startsWith('fr') ? 'fr' : 'en';

  const runPreview = async () => {
    setBusy(true);
    setFailure(null);
    setAffirmed(false);
    setUnderstands(false);
    try {
      const { data, error, response } = await core.POST(
        '/me/salary-advance-requests/{requestId}/handoff/preview',
        { params: { path: { requestId: request.requestId } } },
      );
      if (data) setPreview(data);
      else setFailure(salaryAdvanceFailureOf(response, error));
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const authorize = async () => {
    if (!preview?.previewDigest || !preview.expiresAt || !affirmed || !understands) return;
    const body = {
      expectedRequestVersion: preview.requestVersion,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      consent: { version: preview.consent.version, language, affirmed: true },
      understandsSyntheticTest: true,
    };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/salary-advance-requests/{requestId}/handoff',
        {
          params: {
            path: { requestId: request.requestId },
            header: { 'Idempotency-Key': commitKey.keyFor({ id: request.requestId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        commitKey.reset();
        setPreview(null);
        setAffirmed(false);
        setUnderstands(false);
        onAuthorized();
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const blockers = preview?.blockers ?? [];
  const ready = preview !== null && preview.previewDigest !== null && blockers.length === 0;

  return (
    <li className="card" data-testid="handoff-candidate" data-request={request.requestId}>
      <p>
        {t('salaryAdvance.handoff.candidate')} <Money money={request.requested} />
      </p>
      {!preview && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button"
            disabled={busy}
            data-testid="handoff-preview"
            onClick={() => void runPreview()}
          >
            {t('salaryAdvance.handoff.start')}
          </button>
        </div>
      )}
      {failure && <CompensationAlert failure={failure} testId="handoff-error" />}
      {preview && blockers.length > 0 && (
        <ul data-testid="handoff-blockers" className="field__error">
          {blockers.map((code) => (
            <li key={code} data-blocker={code}>
              {t(`salaryAdvance.handoff.blocker.${code}`)}
            </li>
          ))}
        </ul>
      )}
      {ready && (
        <div data-testid="handoff-confirmation">
          <h3>{t('salaryAdvance.handoff.reviewTitle')}</h3>
          <p>
            {t('salaryAdvance.me.reviewAmount')} <Money money={preview.requested} />
          </p>
          <HandoffDisclosure consent={preview.consent} />
          <div className="field field--checkbox">
            <input
              id={`${ids}-affirm`}
              type="checkbox"
              checked={affirmed}
              data-testid="handoff-affirm"
              onChange={(event) => {
                setAffirmed(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-affirm`}>{t('salaryAdvance.handoff.affirm')}</label>
          </div>
          <div className="field field--checkbox">
            <input
              id={`${ids}-understand`}
              type="checkbox"
              checked={understands}
              data-testid="handoff-understand"
              onChange={(event) => {
                setUnderstands(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-understand`}>{t('salaryAdvance.handoff.understand')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={busy || !affirmed || !understands}
              data-testid="handoff-authorize"
              onClick={() => void authorize()}
            >
              {t('salaryAdvance.handoff.authorize')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                setPreview(null);
              }}
            >
              {t('salaryAdvance.requests.back')}
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

/**
 * MVP-120B: the employee's controlled handoff of an employer-confirmed request to the synthetic
 * test partner. The delivery status and the partner's bounded review status are shown apart, in
 * plain words, and a withdrawal is offered only until the partner has durably received the request.
 * Nothing here is a decision, a payout or a deduction, and nothing is kept in browser storage.
 */
export function MyHandoffs({
  requests,
  handoffs,
  onChanged,
}: {
  requests: SalaryAdvanceRequestSummary[];
  handoffs: SalaryAdvanceHandoffSummary[];
  onChanged: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const withdrawKey = useIdempotencyKey();
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);

  const live = new Set(handoffs.filter((item) => handoffLive(item.state)).map((i) => i.requestId));
  const candidates = requests.filter(
    (request) => request.status === 'EMPLOYER_CONFIRMED' && !live.has(request.requestId),
  );

  const withdraw = async (item: SalaryAdvanceHandoffSummary) => {
    const body = { expectedVersion: item.version, confirmed: true as const };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/salary-advance-requests/{requestId}/handoff/withdraw',
        {
          params: {
            path: { requestId: item.requestId },
            header: { 'Idempotency-Key': withdrawKey.keyFor({ id: item.requestId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        setConfirming(null);
        withdrawKey.reset();
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
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="my-handoffs">
      <h2 id={`${ids}-title`}>{t('salaryAdvance.handoff.title')}</h2>
      <p>{t('salaryAdvance.handoff.intro')}</p>
      <HandoffNotice />
      {failure && <CompensationAlert failure={failure} testId="handoff-withdraw-error" />}
      {candidates.length > 0 && (
        <ul className="plain-list" data-testid="handoff-candidates">
          {candidates.map((request) => (
            <HandoffStart key={request.requestId} request={request} onAuthorized={onChanged} />
          ))}
        </ul>
      )}
      {handoffs.length === 0 && candidates.length === 0 && (
        <p className="muted" data-testid="handoffs-empty">
          {t('salaryAdvance.handoff.empty')}
        </p>
      )}
      {handoffs.length > 0 && (
        <ScrollRegion label={t('salaryAdvance.handoff.table')}>
          <table>
            <thead>
              <tr>
                <th scope="col">{t('salaryAdvance.handoff.col.created')}</th>
                <th scope="col">{t('salaryAdvance.col.amount')}</th>
                <th scope="col">{t('salaryAdvance.handoff.col.delivery')}</th>
                <th scope="col">{t('salaryAdvance.handoff.col.review')}</th>
                <th scope="col">{t('salaryAdvance.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {handoffs.map((item) => (
                <tr key={item.handoffId} data-testid="handoff-row" data-state={item.state}>
                  <th scope="row">{formatInstant(i18n.language, item.createdAt)}</th>
                  <td>
                    <Money money={item.requested} />
                  </td>
                  <td>
                    <HandoffState state={item.state} />
                    <p className="muted">{t(`salaryAdvance.handoff.stateHelp.${item.state}`)}</p>
                  </td>
                  <td>
                    <HandoffReview review={item.reviewState} />
                    {item.reviewState !== null && (
                      <ReviewTimeline requestId={item.requestId} review={item.reviewState} />
                    )}
                  </td>
                  <td>
                    {handoffWithdrawable(item.state) &&
                      (confirming === item.handoffId ? (
                        <span className="dialog-actions">
                          <button
                            type="button"
                            className="button button--danger"
                            disabled={busy}
                            data-testid="handoff-withdraw-confirm"
                            onClick={() => void withdraw(item)}
                          >
                            {t('salaryAdvance.handoff.withdrawConfirm')}
                          </button>
                          <button
                            type="button"
                            className="button button--secondary"
                            onClick={() => {
                              setConfirming(null);
                            }}
                          >
                            {t('salaryAdvance.me.withdrawKeep')}
                          </button>
                        </span>
                      ) : (
                        <button
                          type="button"
                          className="button button--secondary"
                          data-testid="handoff-withdraw"
                          onClick={() => {
                            setConfirming(item.handoffId);
                          }}
                        >
                          {t('salaryAdvance.handoff.withdraw')}
                        </button>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
    </section>
  );
}

/** Loads the employee's own handoffs (newest first); the caller refreshes it after a change. */
export function useMyHandoffs(revision: number) {
  const { core } = useApi();
  const [handoffs, setHandoffs] = useState<SalaryAdvanceHandoffSummary[] | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);

  useEffect(() => {
    let active = true;
    void core
      .GET('/me/salary-advance-handoffs', {
        params: { query: { limit: PAGE_LIMIT } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setHandoffs(data.items);
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
  }, [core, revision]);

  return { handoffs, failure };
}

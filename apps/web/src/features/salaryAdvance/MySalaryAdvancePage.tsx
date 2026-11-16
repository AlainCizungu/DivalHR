import type {
  SalaryAdvanceConsentDocument,
  SalaryAdvanceEligibilityEntry,
  SalaryAdvanceEligibilityView,
  SalaryAdvanceRequestPreview,
  SalaryAdvanceRequestSummary,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatDate, formatInstant } from '../compensation/compensation';
import { CompensationAlert, Money } from '../compensation/CompensationBits';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { LenderNotice, RequestStatus } from './SalaryAdvanceBits';
import {
  moneyOf,
  NETWORK_FAILURE,
  PAGE_LIMIT,
  salaryAdvanceFailureOf,
  withdrawable,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

type Load<T> =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: SalaryAdvanceFailure }
  | { kind: 'ready'; value: T };

/** What the employee agrees to share, from the closed consent document (never free text). */
function ConsentSummary({ consent }: { consent: SalaryAdvanceConsentDocument }) {
  const { t } = useTranslation();
  return (
    <div data-testid="consent-document" data-version={consent.version}>
      <p>{t('salaryAdvance.consent.purpose')}</p>
      <h4>{t('salaryAdvance.consent.shared')}</h4>
      <ul>
        {consent.shared.map((item) => (
          <li key={item}>{t(`salaryAdvance.consent.sharedItem.${item}`)}</li>
        ))}
      </ul>
      <h4>{t('salaryAdvance.consent.notShared')}</h4>
      <ul>
        {consent.notShared.map((item) => (
          <li key={item}>{t(`salaryAdvance.consent.notSharedItem.${item}`)}</li>
        ))}
      </ul>
      <p>{t('salaryAdvance.consent.external')}</p>
      <p>{t('salaryAdvance.consent.withdrawal')}</p>
      <p>{t('salaryAdvance.consent.retention')}</p>
    </div>
  );
}

/** One currency: why the employee can or cannot request, then the request form. */
function CurrencyCard({
  entry,
  onSubmitted,
}: {
  entry: SalaryAdvanceEligibilityEntry;
  onSubmitted: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const [amount, setAmount] = useState('');
  const [amountBad, setAmountBad] = useState(false);
  const [preview, setPreview] = useState<SalaryAdvanceRequestPreview | null>(null);
  const [affirmed, setAffirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [done, setDone] = useState(false);
  const language: 'fr' | 'en' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const eligible = entry.status === 'ELIGIBLE';

  const runPreview = async (event: SyntheticEvent) => {
    event.preventDefault();
    setFailure(null);
    setPreview(null);
    setAffirmed(false);
    setDone(false);
    const money = moneyOf(amount, entry.currency);
    setAmountBad(money === null);
    if (money === null) return;
    setBusy(true);
    try {
      const { data, error, response } = await core.POST('/me/salary-advance-requests/preview', {
        body: { currency: entry.currency, amount: money },
      });
      if (data) setPreview(data);
      else setFailure(salaryAdvanceFailureOf(response, error));
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const submit = async () => {
    const consent = preview?.eligibility.consent;
    const money = moneyOf(amount, entry.currency);
    if (!preview?.previewDigest || !preview.expiresAt || !consent || !money || !affirmed) return;
    const body = {
      currency: entry.currency,
      amount: money,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      consent: { version: consent.version, language, affirmed: true as const },
    };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/me/salary-advance-requests', {
        params: { header: { 'Idempotency-Key': commitKey.keyFor(body) } },
        body,
      });
      if (data) {
        setDone(true);
        setPreview(null);
        setAmount('');
        setAffirmed(false);
        commitKey.reset();
        onSubmitted();
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
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="salary-advance-currency"
      data-currency={entry.currency}
      data-status={entry.status}
    >
      <h2 id={`${ids}-title`}>
        {t('salaryAdvance.me.currencyTitle', { currency: entry.currency })}
      </h2>
      <p data-testid="eligibility-status">
        <strong>
          {eligible ? t('salaryAdvance.me.eligible') : t('salaryAdvance.me.notEligible')}
        </strong>
      </p>
      {!eligible && (
        <ul data-testid="eligibility-reasons">
          {entry.reasons.map((reason) => (
            <li key={reason} data-reason={reason}>
              {t(`salaryAdvance.reason.${reason}`)}
            </li>
          ))}
        </ul>
      )}
      {entry.ceiling && (
        <p data-testid="eligibility-ceiling">
          {t('salaryAdvance.me.ceiling')} <Money money={entry.ceiling} />
        </p>
      )}
      {entry.minRequest && (
        <p className="muted">
          {t('salaryAdvance.me.minimum')} <Money money={entry.minRequest} />
        </p>
      )}
      {entry.waitingUntil && (
        <p className="muted">
          {t('salaryAdvance.me.waitingUntil', {
            date: formatDate(i18n.language, entry.waitingUntil),
          })}
        </p>
      )}
      {eligible && (
        <form onSubmit={(event) => void runPreview(event)} noValidate>
          <div className={amountBad ? 'field field--invalid' : 'field'}>
            <label htmlFor={`${ids}-amount`}>
              {t('salaryAdvance.me.amountLabel', { currency: entry.currency })}
            </label>
            <input
              id={`${ids}-amount`}
              inputMode="decimal"
              autoComplete="off"
              value={amount}
              aria-invalid={amountBad}
              aria-describedby={amountBad ? `${ids}-amount-error` : undefined}
              data-testid="request-amount"
              onChange={(event) => {
                setAmount(event.target.value);
                setPreview(null);
                setAffirmed(false);
              }}
            />
            {amountBad && (
              <p id={`${ids}-amount-error`} className="field__error" role="alert">
                {t('salaryAdvance.me.amountInvalid')}
              </p>
            )}
          </div>
          <div className="dialog-actions">
            <button type="submit" className="button" disabled={busy} data-testid="request-preview">
              {t('salaryAdvance.me.preview')}
            </button>
          </div>
        </form>
      )}
      {failure && <CompensationAlert failure={failure} testId="salary-advance-error" />}
      {preview && blockers.length > 0 && (
        <ul data-testid="request-blockers" className="field__error">
          {blockers.map((reason) => (
            <li key={reason} data-reason={reason}>
              {t(`salaryAdvance.reason.${reason}`)}
            </li>
          ))}
        </ul>
      )}
      {ready && preview.eligibility.consent && (
        <div data-testid="request-confirmation">
          <h3>{t('salaryAdvance.me.reviewTitle')}</h3>
          <p>
            {t('salaryAdvance.me.reviewAmount')}{' '}
            {(() => {
              const money = moneyOf(amount, entry.currency);
              return money ? <Money money={money} /> : null;
            })()}
          </p>
          <ConsentSummary consent={preview.eligibility.consent} />
          <div className="field field--checkbox">
            <input
              id={`${ids}-consent`}
              type="checkbox"
              checked={affirmed}
              data-testid="request-consent"
              onChange={(event) => {
                setAffirmed(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-consent`}>{t('salaryAdvance.consent.affirm')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={busy || !affirmed}
              data-testid="request-submit"
              onClick={() => void submit()}
            >
              {t('salaryAdvance.me.submit')}
            </button>
          </div>
        </div>
      )}
      {done && (
        <p role="status" data-testid="request-submitted">
          {t('salaryAdvance.me.submitted')}
        </p>
      )}
    </section>
  );
}

/** The employee's own requests, newest first, with a two-step withdrawal. */
function MyRequests({ revision }: { revision: number }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const withdrawKey = useIdempotencyKey();
  const [load, setLoad] = useState<
    Load<{ items: SalaryAdvanceRequestSummary[]; nextCursor: string | null }>
  >({ kind: 'loading' });
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const loading = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET('/me/salary-advance-requests', {
          params: { query: { limit: PAGE_LIMIT, ...(cursor ? { cursor } : {}) } },
          cache: 'no-store',
        });
        return data ? { data } : { failure: salaryAdvanceFailureOf(response, error) };
      } catch {
        return { failure: NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    const result = await fetchPage(null);
    setLoad(
      result.data
        ? { kind: 'ready', value: result.data }
        : { kind: 'failed', failure: result.failure },
    );
  }, [fetchPage]);

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((result) => {
      if (!active) return;
      setLoad(
        result.data
          ? { kind: 'ready', value: result.data }
          : { kind: 'failed', failure: result.failure },
      );
    });
    return () => {
      active = false;
    };
  }, [fetchPage, revision]);

  const loadMore = async () => {
    if (load.kind !== 'ready' || !load.value.nextCursor || loading.current) return;
    loading.current = true;
    const result = await fetchPage(load.value.nextCursor);
    if (result.data) {
      const page = result.data;
      setLoad((current) =>
        current.kind === 'ready'
          ? {
              kind: 'ready',
              value: {
                items: [...current.value.items, ...page.items],
                nextCursor: page.nextCursor,
              },
            }
          : current,
      );
    } else {
      setFailure(result.failure);
    }
    loading.current = false;
  };

  const withdraw = async (item: SalaryAdvanceRequestSummary) => {
    const body = { expectedVersion: item.version, confirmed: true as const };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/salary-advance-requests/{requestId}/withdraw',
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
        await reload();
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="my-requests">
      <h2 id={`${ids}-title`}>{t('salaryAdvance.me.requestsTitle')}</h2>
      {load.kind === 'loading' && <p role="status">{t('salaryAdvance.loading')}</p>}
      {load.kind === 'failed' && (
        <CompensationAlert failure={load.failure} testId="requests-error" />
      )}
      {failure && <CompensationAlert failure={failure} testId="withdraw-error" />}
      {load.kind === 'ready' && load.value.items.length === 0 && (
        <p className="muted" data-testid="requests-empty">
          {t('salaryAdvance.me.requestsEmpty')}
        </p>
      )}
      {load.kind === 'ready' && load.value.items.length > 0 && (
        <ScrollRegion label={t('salaryAdvance.me.requestsTable')}>
          <table>
            <thead>
              <tr>
                <th scope="col">{t('salaryAdvance.col.submitted')}</th>
                <th scope="col">{t('salaryAdvance.col.amount')}</th>
                <th scope="col">{t('salaryAdvance.col.status')}</th>
                <th scope="col">{t('salaryAdvance.col.actions')}</th>
              </tr>
            </thead>
            <tbody>
              {load.value.items.map((item) => (
                <tr key={item.requestId} data-testid="my-request-row" data-status={item.status}>
                  <th scope="row">{formatInstant(i18n.language, item.submittedAt)}</th>
                  <td>
                    <Money money={item.requested} />
                  </td>
                  <td>
                    <RequestStatus status={item.status} />
                  </td>
                  <td>
                    {withdrawable(item.status) &&
                      (confirming === item.requestId ? (
                        <span className="dialog-actions">
                          <button
                            type="button"
                            className="button button--danger"
                            disabled={busy}
                            data-testid="withdraw-confirm"
                            onClick={() => void withdraw(item)}
                          >
                            {t('salaryAdvance.me.withdrawConfirm')}
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
                          data-testid="withdraw"
                          onClick={() => {
                            setConfirming(item.requestId);
                          }}
                        >
                          {t('salaryAdvance.me.withdraw')}
                        </button>
                      ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollRegion>
      )}
      {load.kind === 'ready' && load.value.nextCursor && (
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
  );
}

/**
 * MVP-120A: « Avance sur salaire » / "Salary advance" under My space. A linked employee sees
 * whether the employer's policy lets them ask, previews an amount, reads and affirms the bilingual
 * consent, and submits or withdraws a request. This is an employer-participation request, not a
 * loan: nothing is sent to any institution and no funds move. Nothing is kept in browser storage.
 */
export function MySalaryAdvancePage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load<SalaryAdvanceEligibilityView>>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    void core.GET('/me/salary-advance-eligibility', { cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setLoad(
          data
            ? { kind: 'ready', value: data }
            : { kind: 'failed', failure: salaryAdvanceFailureOf(response, error) },
        );
      },
      () => {
        if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core, revision]);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-salary-advance">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('salaryAdvance.me.title')}
        description={t('salaryAdvance.me.description')}
      />
      <LenderNotice />
      {load.kind === 'loading' && (
        <p role="status" data-testid="salary-advance-loading">
          {t('salaryAdvance.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <CompensationAlert failure={load.failure} testId="salary-advance-load-error" />
      )}
      {load.kind === 'ready' &&
        load.value.items.map((entry) => (
          <CurrencyCard
            key={entry.currency}
            entry={entry}
            onSubmitted={() => {
              setRevision((value) => value + 1);
            }}
          />
        ))}
      <MyRequests revision={revision} />
    </section>
  );
}

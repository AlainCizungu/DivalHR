import type {
  StatutoryActivationView,
  StatutoryRulePackDetail,
  StatutoryRulePackSummary,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { ScrollRegion } from '../people/ScrollRegion';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatInstant } from '../compensation/compensation';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import {
  formatDay,
  formatMonth,
  NETWORK_FAILURE,
  type PayrollFailure,
} from '../payrollRuns/payrollRuns';
import { PackStatus, SourceList, StatutoryLabel } from './StatutoryBits';
import { ISO_MONTH, PACK_LIMIT, statutoryFailureOf } from './statutory';

type ViewLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; view: StatutoryActivationView };

type PackLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: StatutoryRulePackSummary[]; nextCursor: string | null };

type DetailLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; detail: StatutoryRulePackDetail };

/**
 * One rule pack: identity, status, effective interval, official sources (links open in a new tab,
 * with the hash or "not hashed"), the professional-review evidence of a verified pack and the
 * activation form. The form is enabled only for a VERIFIED pack; a DRAFT or RETIRED pack explains
 * why it cannot be activated. Activation needs the acknowledgement that the employer remains
 * responsible for legal review.
 */
function PackCard({
  pack,
  legalEntityId,
  defaultMonth,
  version,
  onActivated,
}: {
  pack: StatutoryRulePackSummary;
  legalEntityId: string;
  defaultMonth: string;
  version: number;
  onActivated: (message: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const activationKey = useIdempotencyKey();
  const focus = useFocusRequest();
  const [detail, setDetail] = useState<DetailLoad>({ kind: 'loading' });
  const [month, setMonth] = useState(defaultMonth);
  const [acknowledged, setAcknowledged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const verified = pack.status === 'VERIFIED';

  useEffect(() => {
    let active = true;
    core
      .GET('/statutory-rule-packs/{packId}', {
        params: { path: { packId: pack.packId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setDetail(
            data
              ? { kind: 'ready', detail: data }
              : { kind: 'failed', failure: statutoryFailureOf(response, error) },
          );
        },
        () => {
          if (active) setDetail({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, pack.packId]);

  const monthValid = ISO_MONTH.test(month);
  const canActivate = verified && acknowledged && monthValid && !busy;
  const reasonId = `${ids}-why`;
  const reason = !verified
    ? t('statutory.activation.whyNotVerified')
    : !acknowledged
      ? t('statutory.activation.whyAcknowledge')
      : !monthValid
        ? t('statutory.activation.whyMonth')
        : null;

  const onSubmit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canActivate) return;
    const body = {
      packId: pack.packId,
      fromMonth: month,
      expectedVersion: version,
      acknowledged: true,
    };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/statutory-rule-pack/activation',
        {
          params: {
            path: { legalEntityId },
            header: { 'Idempotency-Key': activationKey.keyFor({ legalEntityId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        activationKey.reset();
        setAcknowledged(false);
        onActivated(t('statutory.activation.announce'));
        return;
      }
      const next = statutoryFailureOf(response, error);
      if (next.kind === 'stale') activationKey.reset();
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(false);
    }
  };

  const verification = detail.kind === 'ready' ? detail.detail.verification : null;
  return (
    <article
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="statutory-pack"
      data-code={pack.code}
      data-status={pack.status}
    >
      <h3 id={`${ids}-title`}>
        {t('statutory.pack.title', { code: pack.code, version: pack.version })}
      </h3>
      <PackStatus status={pack.status} />
      <dl className="decision-summary decision-summary--wrapping">
        <dt>{t('statutory.pack.interval')}</dt>
        <dd data-testid="statutory-pack-interval">
          {pack.effectiveTo
            ? t('statutory.pack.intervalClosed', {
                from: formatDay(i18n.language, pack.effectiveFrom),
                to: formatDay(i18n.language, pack.effectiveTo),
              })
            : t('statutory.pack.intervalOpen', {
                from: formatDay(i18n.language, pack.effectiveFrom),
              })}
        </dd>
        <dt>{t('statutory.pack.method')}</dt>
        <dd>{pack.methodVersion}</dd>
        <dt>{t('statutory.pack.sourceCount')}</dt>
        <dd>{pack.sourceCount}</dd>
        {verification && (
          <>
            <dt>{t('statutory.pack.reviewer')}</dt>
            <dd data-testid="statutory-pack-reviewer">
              {t('statutory.pack.reviewerText', {
                name: verification.reviewerName,
                organization: verification.reviewerOrganization,
                date: formatDay(i18n.language, verification.reviewedOn),
              })}
            </dd>
          </>
        )}
      </dl>
      <h4>{t('statutory.pack.sources')}</h4>
      {detail.kind === 'loading' && <p role="status">{t('payrollRuns.loading')}</p>}
      {detail.kind === 'failed' && (
        <PayrollAlert failure={detail.failure} testId="statutory-pack-error" />
      )}
      {detail.kind === 'ready' && (
        <SourceList sources={detail.detail.sources} testId="statutory-pack-sources" />
      )}

      <form
        noValidate
        onSubmit={(event) => void onSubmit(event)}
        data-testid="statutory-activation-form"
      >
        <fieldset className="field" disabled={!verified}>
          <legend>{t('statutory.activation.title')}</legend>
          <p className="field__help">{t('statutory.activation.help')}</p>
          <div className="field">
            <label htmlFor={`${ids}-month`}>{t('statutory.activation.month')}</label>
            <input
              id={`${ids}-month`}
              type="month"
              value={month}
              aria-describedby={reason ? reasonId : undefined}
              data-testid="statutory-activation-month"
              onChange={(event) => {
                setMonth(event.target.value);
                setFailure(null);
              }}
            />
          </div>
          <div className="choice">
            <input
              id={`${ids}-ack`}
              type="checkbox"
              checked={acknowledged}
              data-testid="statutory-activation-ack"
              onChange={(event) => {
                setAcknowledged(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-ack`}>{t('statutory.activation.acknowledge')}</label>
          </div>
        </fieldset>
        <div className="dialog-actions">
          <button
            type="submit"
            className="button"
            disabled={!canActivate}
            aria-describedby={reason ? reasonId : undefined}
            data-testid="statutory-activate"
          >
            {busy ? t('payrollRuns.saving') : t('statutory.activation.submit')}
          </button>
        </div>
        {reason && (
          <p id={reasonId} className="muted" data-testid="statutory-activation-why">
            {reason}
          </p>
        )}
      </form>
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="statutory-activation-failure"
          alertRef={focus.register('failure')}
        />
      )}
    </article>
  );
}

/**
 * The legal entity's rule-pack status for the run's month: whether a VERIFIED pack is in effect,
 * the explicit "Professional verification required" state when none is, the activation history and
 * the available packs with their official sources. Rates, brackets and formulas are shown for
 * transparency only: no one can edit them.
 */
export function RulePackPanel({
  legalEntityId,
  month,
  onChanged,
}: {
  legalEntityId: string;
  month: string;
  onChanged: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [view, setView] = useState<ViewLoad>({ kind: 'loading' });
  const [packs, setPacks] = useState<PackLoad>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [announcement, setAnnouncement] = useState('');
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/statutory-rule-pack', {
        params: { path: { legalEntityId }, query: { month } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setView(
            data
              ? { kind: 'ready', view: data }
              : { kind: 'failed', failure: statutoryFailureOf(response, error) },
          );
        },
        () => {
          if (active) setView({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, month, revision]);

  const fetchPacks = useCallback(
    async (cursor: string | null) => {
      try {
        const { data, error, response } = await core.GET('/statutory-rule-packs', {
          params: { query: cursor ? { cursor, limit: PACK_LIMIT } : { limit: PACK_LIMIT } },
          cache: 'no-store',
        });
        return data
          ? { data, failure: null }
          : { data: null, failure: statutoryFailureOf(response, error) };
      } catch {
        return { data: null, failure: NETWORK_FAILURE };
      }
    },
    [core],
  );

  useEffect(() => {
    let active = true;
    void fetchPacks(null).then((result) => {
      if (!active) return;
      setPacks(
        result.data
          ? { kind: 'ready', items: result.data.items, nextCursor: result.data.nextCursor }
          : { kind: 'failed', failure: result.failure },
      );
    });
    return () => {
      active = false;
    };
  }, [fetchPacks]);

  const loadMore = async () => {
    if (packs.kind !== 'ready' || !packs.nextCursor || loadingMore.current) return;
    loadingMore.current = true;
    setMoreBusy(true);
    const result = await fetchPacks(packs.nextCursor);
    if (result.data) {
      const page = result.data;
      setPacks((current) =>
        current.kind === 'ready'
          ? { kind: 'ready', items: [...current.items, ...page.items], nextCursor: page.nextCursor }
          : current,
      );
    }
    loadingMore.current = false;
    setMoreBusy(false);
  };

  const current = view.kind === 'ready' ? view.view : null;
  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="statutory-rule-pack">
      <h2 id={`${ids}-title`}>{t('statutory.rulePack.title')}</h2>
      <StatutoryLabel testId="statutory-rule-pack-label" />
      <p className="muted">{t('statutory.rulePack.help')}</p>
      <p role="status" className="visually-hidden" data-testid="statutory-announcer">
        {announcement}
      </p>
      {view.kind === 'loading' && <p role="status">{t('payrollRuns.loading')}</p>}
      {view.kind === 'failed' && (
        <PayrollAlert failure={view.failure} testId="statutory-activation-error" />
      )}
      {current && (
        <>
          <h3>{t('statutory.rulePack.inEffect', { month: formatMonth(i18n.language, month) })}</h3>
          {current.verificationRequired || !current.inEffect ? (
            <div
              className="error-summary"
              role="status"
              data-testid="statutory-verification-required"
            >
              <p>
                <strong>{t('statutory.verificationRequired')}</strong>
              </p>
              <p>{t('statutory.verificationRequiredHelp')}</p>
            </div>
          ) : (
            <p data-testid="statutory-in-effect">
              {t('statutory.rulePack.inEffectText', {
                code: current.inEffect.pack.code,
                version: current.inEffect.pack.version,
                from: formatMonth(i18n.language, current.inEffect.fromMonth),
              })}
            </p>
          )}
          {current.activations.length > 0 && (
            <>
              <h3>{t('statutory.rulePack.history')}</h3>
              <ScrollRegion label={t('statutory.rulePack.history')}>
                <table className="timesheet-table" data-testid="statutory-activations">
                  <caption className="visually-hidden">{t('statutory.rulePack.history')}</caption>
                  <thead>
                    <tr>
                      <th scope="col">{t('statutory.rulePack.columns.sequence')}</th>
                      <th scope="col">{t('statutory.rulePack.columns.fromMonth')}</th>
                      <th scope="col">{t('statutory.rulePack.columns.pack')}</th>
                      <th scope="col">{t('statutory.rulePack.columns.activatedAt')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {current.activations.map((entry) => (
                      <tr key={entry.activationId} data-testid="statutory-activation-row">
                        <th scope="row">{entry.sequence}</th>
                        <td>{formatMonth(i18n.language, entry.fromMonth)}</td>
                        <td>
                          {t('statutory.pack.title', {
                            code: entry.packCode,
                            version: entry.packVersion,
                          })}
                        </td>
                        <td>{formatInstant(i18n.language, entry.activatedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollRegion>
            </>
          )}
        </>
      )}
      <h3>{t('statutory.rulePack.available')}</h3>
      {packs.kind === 'loading' && <p role="status">{t('payrollRuns.loading')}</p>}
      {packs.kind === 'failed' && (
        <PayrollAlert failure={packs.failure} testId="statutory-packs-error" />
      )}
      {packs.kind === 'ready' && packs.items.length === 0 && (
        <p className="muted" data-testid="statutory-packs-empty">
          {t('statutory.rulePack.none')}
        </p>
      )}
      {current &&
        packs.kind === 'ready' &&
        packs.items.map((pack) => (
          <PackCard
            key={`${pack.packId}-${String(revision)}`}
            pack={pack}
            legalEntityId={legalEntityId}
            defaultMonth={month}
            version={current.version}
            onActivated={(message) => {
              setAnnouncement(message);
              setRevision((value) => value + 1);
              onChanged();
            }}
          />
        ))}
      {packs.kind === 'ready' && packs.nextCursor && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            data-testid="statutory-packs-more"
            disabled={moreBusy}
            onClick={() => void loadMore()}
          >
            {moreBusy ? t('payrollRuns.loadingMore') : t('statutory.rulePack.loadMore')}
          </button>
        </div>
      )}
    </section>
  );
}

import type {
  SalaryAdvanceCurrency,
  SalaryAdvancePolicyCommand,
  SalaryAdvancePolicyEntry,
  SalaryAdvancePolicyPreview,
  SalaryAdvancePolicyView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { CURRENCIES, formatDate } from '../compensation/compensation';
import { CompensationAlert, Money } from '../compensation/CompensationBits';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { LenderNotice } from './SalaryAdvanceBits';
import {
  CONSENT_VERSION,
  moneyOf,
  NETWORK_FAILURE,
  PARTNER_CODE,
  salaryAdvanceFailureOf,
  wholeNumber,
  type SalaryAdvanceFailure,
} from './salaryAdvance';

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/u;
const POLICY_CODE = /^[A-Z][A-Z0-9-]{2,31}$/u;

interface Form {
  currency: SalaryAdvanceCurrency;
  code: string;
  effectiveFrom: string;
  effectiveTo: string;
  enabled: boolean;
  minTenureMonths: string;
  maxPercent: string;
  absoluteMax: string;
  minRequest: string;
  maxOpenRequests: string;
  waitingDays: string;
  consentVersion: string;
}

const EMPTY: Form = {
  currency: 'CDF',
  code: '',
  effectiveFrom: '',
  effectiveTo: '',
  enabled: true,
  minTenureMonths: '3',
  maxPercent: '30',
  absoluteMax: '',
  minRequest: '',
  maxOpenRequests: '1',
  waitingDays: '30',
  consentVersion: CONSENT_VERSION,
};

type Field = keyof Form;

/** The command the form describes, or the fields that are not yet valid. */
function commandOf(form: Form): { command: SalaryAdvancePolicyCommand } | { bad: Set<Field> } {
  const bad = new Set<Field>();
  const minTenureMonths = wholeNumber(form.minTenureMonths, 0, 60);
  const maxPercent = wholeNumber(form.maxPercent, 1, 50);
  const maxOpenRequests = wholeNumber(form.maxOpenRequests, 1, 3);
  const waitingDays = wholeNumber(form.waitingDays, 0, 90);
  const minRequest = moneyOf(form.minRequest, form.currency);
  const absoluteMax = form.absoluteMax.trim() ? moneyOf(form.absoluteMax, form.currency) : null;
  if (!POLICY_CODE.test(form.code)) bad.add('code');
  if (!ISO_DATE.test(form.effectiveFrom)) bad.add('effectiveFrom');
  if (form.effectiveTo && !ISO_DATE.test(form.effectiveTo)) bad.add('effectiveTo');
  if (minTenureMonths === null) bad.add('minTenureMonths');
  if (maxPercent === null) bad.add('maxPercent');
  if (maxOpenRequests === null) bad.add('maxOpenRequests');
  if (waitingDays === null) bad.add('waitingDays');
  if (minRequest === null) bad.add('minRequest');
  if (form.absoluteMax.trim() && absoluteMax === null) bad.add('absoluteMax');
  if (!/^[A-Za-z0-9._-]{1,32}$/u.test(form.consentVersion)) bad.add('consentVersion');
  if (
    bad.size > 0 ||
    minTenureMonths === null ||
    maxPercent === null ||
    maxOpenRequests === null ||
    waitingDays === null ||
    minRequest === null
  ) {
    return { bad };
  }
  return {
    command: {
      currency: form.currency,
      code: form.code,
      effectiveFrom: form.effectiveFrom,
      effectiveTo: form.effectiveTo || null,
      enabled: form.enabled,
      minTenureMonths,
      maxPercent,
      absoluteMax,
      minRequest,
      maxOpenRequests,
      waitingDays,
      consentVersion: form.consentVersion,
      partnerCode: PARTNER_CODE,
    },
  };
}

function PolicyRows({
  items,
  onCancel,
  busy,
}: {
  items: SalaryAdvancePolicyEntry[];
  onCancel: (item: SalaryAdvancePolicyEntry) => void;
  busy: boolean;
}) {
  const { t, i18n } = useTranslation();
  const [confirming, setConfirming] = useState<string | null>(null);
  return (
    <ScrollRegion label={t('salaryAdvance.policies.table')}>
      <table>
        <thead>
          <tr>
            <th scope="col">{t('salaryAdvance.col.policy')}</th>
            <th scope="col">{t('salaryAdvance.col.period')}</th>
            <th scope="col">{t('salaryAdvance.col.limits')}</th>
            <th scope="col">{t('salaryAdvance.col.status')}</th>
            <th scope="col">{t('salaryAdvance.col.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.policyId} data-testid="policy-row" data-status={item.status}>
              <th scope="row">
                {t('salaryAdvance.policies.rowTitle', {
                  currency: item.currency,
                  code: item.code,
                  sequence: item.sequence,
                })}
              </th>
              <td>
                {item.effectiveTo
                  ? t('compensation.period.closed', {
                      from: formatDate(i18n.language, item.effectiveFrom),
                      to: formatDate(i18n.language, item.effectiveTo),
                    })
                  : t('compensation.period.open', {
                      from: formatDate(i18n.language, item.effectiveFrom),
                    })}
              </td>
              <td>
                {t('salaryAdvance.policies.limitsSummary', {
                  percent: item.maxPercent,
                  tenure: item.minTenureMonths,
                  open: item.maxOpenRequests,
                  waiting: item.waitingDays,
                })}{' '}
                <Money money={item.minRequest} />
                {item.absoluteMax && (
                  <>
                    {' '}
                    · {t('salaryAdvance.policies.absoluteMax')} <Money money={item.absoluteMax} />
                  </>
                )}
              </td>
              <td>
                <StatusBadge tone={item.status === 'IN_FORCE' ? 'success' : 'neutral'}>
                  {t(`salaryAdvance.policyStatus.${item.status}`)}
                </StatusBadge>{' '}
                {!item.enabled && <span className="muted">{t('salaryAdvance.policies.off')}</span>}
              </td>
              <td>
                {item.status === 'SCHEDULED' &&
                  (confirming === item.policyId ? (
                    <span className="dialog-actions">
                      <button
                        type="button"
                        className="button button--danger"
                        disabled={busy}
                        data-testid="policy-cancel-confirm"
                        onClick={() => {
                          onCancel(item);
                          setConfirming(null);
                        }}
                      >
                        {t('salaryAdvance.policies.cancelConfirm')}
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        onClick={() => {
                          setConfirming(null);
                        }}
                      >
                        {t('salaryAdvance.policies.cancelKeep')}
                      </button>
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="button button--secondary"
                      data-testid="policy-cancel"
                      onClick={() => {
                        setConfirming(item.policyId);
                      }}
                    >
                      {t('salaryAdvance.policies.cancel')}
                    </button>
                  ))}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

function PolicyForm({
  legalEntityId,
  versionOf,
  onCommitted,
}: {
  legalEntityId: string;
  versionOf: (currency: SalaryAdvanceCurrency) => number;
  onCommitted: () => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const [form, setForm] = useState<Form>(EMPTY);
  const [bad, setBad] = useState<Set<Field>>(new Set());
  const [preview, setPreview] = useState<SalaryAdvancePolicyPreview | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const set = <K extends Field>(key: K, value: Form[K]) => {
    setForm((current) => ({ ...current, [key]: value }));
    setPreview(null);
    setDone(false);
  };

  const runPreview = async (event: SyntheticEvent) => {
    event.preventDefault();
    setFailure(null);
    setDone(false);
    const result = commandOf(form);
    if ('bad' in result) {
      setBad(result.bad);
      return;
    }
    setBad(new Set());
    setBusy(true);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/salary-advance-policies/preview',
        { params: { path: { legalEntityId } }, body: result.command },
      );
      if (data) setPreview(data);
      else setFailure(salaryAdvanceFailureOf(response, error));
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const commit = async () => {
    if (!preview?.previewDigest || !preview.expiresAt) return;
    const body = {
      policy: preview.policy,
      expectedVersion: preview.version,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      confirmed: true as const,
    };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/salary-advance-policies',
        {
          params: {
            path: { legalEntityId },
            header: { 'Idempotency-Key': commitKey.keyFor({ legalEntityId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        setDone(true);
        setPreview(null);
        setForm(EMPTY);
        commitKey.reset();
        onCommitted();
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  const text = (key: Field, label: string, hint?: string) => (
    <div className={bad.has(key) ? 'field field--invalid' : 'field'} key={key}>
      <label htmlFor={`${ids}-${key}`}>{label}</label>
      <input
        id={`${ids}-${key}`}
        value={String(form[key])}
        aria-invalid={bad.has(key)}
        aria-describedby={bad.has(key) ? `${ids}-${key}-error` : undefined}
        autoComplete="off"
        data-testid={`policy-${key}`}
        onChange={(event) => {
          set(key, event.target.value as Form[typeof key]);
        }}
      />
      {hint && <p className="muted">{hint}</p>}
      {bad.has(key) && (
        <p id={`${ids}-${key}-error`} className="field__error" role="alert">
          {t('salaryAdvance.policies.fieldInvalid')}
        </p>
      )}
    </div>
  );

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="policy-form">
      <h2 id={`${ids}-title`}>{t('salaryAdvance.policies.newTitle')}</h2>
      <p className="muted">{t('salaryAdvance.policies.newHint')}</p>
      <form onSubmit={(event) => void runPreview(event)} noValidate>
        <div className="field">
          <label htmlFor={`${ids}-currency`}>{t('salaryAdvance.policies.currency')}</label>
          <select
            id={`${ids}-currency`}
            value={form.currency}
            data-testid="policy-currency"
            onChange={(event) => {
              set('currency', event.target.value as SalaryAdvanceCurrency);
            }}
          >
            {CURRENCIES.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
        </div>
        {text('code', t('salaryAdvance.policies.code'))}
        {text('effectiveFrom', t('salaryAdvance.policies.effectiveFrom'), 'YYYY-MM-DD')}
        {text('effectiveTo', t('salaryAdvance.policies.effectiveTo'), 'YYYY-MM-DD')}
        {text('minTenureMonths', t('salaryAdvance.policies.minTenure'))}
        {text('maxPercent', t('salaryAdvance.policies.maxPercent'))}
        {text('absoluteMax', t('salaryAdvance.policies.absoluteMaxLabel'))}
        {text('minRequest', t('salaryAdvance.policies.minRequest'))}
        {text('maxOpenRequests', t('salaryAdvance.policies.maxOpen'))}
        {text('waitingDays', t('salaryAdvance.policies.waitingDays'))}
        {text('consentVersion', t('salaryAdvance.policies.consentVersion'))}
        <div className="field field--checkbox">
          <input
            id={`${ids}-enabled`}
            type="checkbox"
            checked={form.enabled}
            data-testid="policy-enabled"
            onChange={(event) => {
              set('enabled', event.target.checked);
            }}
          />
          <label htmlFor={`${ids}-enabled`}>{t('salaryAdvance.policies.enabled')}</label>
        </div>
        <p className="muted">{t('salaryAdvance.policies.partner', { code: PARTNER_CODE })}</p>
        <div className="dialog-actions">
          <button type="submit" className="button" disabled={busy} data-testid="policy-preview">
            {t('salaryAdvance.policies.preview')}
          </button>
        </div>
      </form>
      {failure && <CompensationAlert failure={failure} testId="policy-error" />}
      {preview && preview.blockers.length > 0 && (
        <ul className="field__error" data-testid="policy-blockers">
          {preview.blockers.map((blocker) => (
            <li key={blocker} data-blocker={blocker}>
              {t(`salaryAdvance.policies.blocker.${blocker}`)}
            </li>
          ))}
        </ul>
      )}
      {preview && preview.blockers.length === 0 && preview.previewDigest && (
        <div data-testid="policy-preview-result">
          <h3>{t('salaryAdvance.policies.previewTitle')}</h3>
          <p>
            {t('salaryAdvance.policies.previewCounts', {
              before: preview.before.length,
              after: preview.after.length,
            })}
          </p>
          <p className="muted">
            {t('salaryAdvance.policies.chainVersion', { version: versionOf(form.currency) })}
          </p>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              disabled={busy}
              data-testid="policy-commit"
              onClick={() => void commit()}
            >
              {t('salaryAdvance.policies.commit')}
            </button>
          </div>
        </div>
      )}
      {done && (
        <p role="status" data-testid="policy-committed">
          {t('salaryAdvance.policies.committed')}
        </p>
      )}
    </section>
  );
}

function EntityPolicies({ legalEntityId }: { legalEntityId: string }) {
  const { t } = useTranslation();
  const { core } = useApi();
  const cancelKey = useIdempotencyKey();
  const [view, setView] = useState<SalaryAdvancePolicyView | null>(null);
  const [failure, setFailure] = useState<SalaryAdvanceFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    void core
      .GET('/legal-entities/{legalEntityId}/salary-advance-policies', {
        params: { path: { legalEntityId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setView(data);
            setFailure(null);
          } else {
            setView(null);
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
  }, [core, legalEntityId, revision]);

  const versionOf = useCallback(
    (currency: SalaryAdvanceCurrency) =>
      view?.versions.find((item) => item.currency === currency)?.version ?? 0,
    [view],
  );

  const cancel = async (item: SalaryAdvancePolicyEntry) => {
    const body = { expectedVersion: item.sequence, confirmed: true as const };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/salary-advance-policies/{policyId}/cancel',
        {
          params: {
            path: { legalEntityId, policyId: item.policyId },
            header: { 'Idempotency-Key': cancelKey.keyFor({ id: item.policyId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        cancelKey.reset();
        setRevision((value) => value + 1);
      } else {
        setFailure(salaryAdvanceFailureOf(response, error));
      }
    } catch {
      setFailure(NETWORK_FAILURE);
    }
    setBusy(false);
  };

  return (
    <>
      {failure && <CompensationAlert failure={failure} testId="policies-error" />}
      {view && (
        <section className="card" data-testid="policy-list">
          <h2>{t('salaryAdvance.policies.listTitle')}</h2>
          {view.items.length === 0 ? (
            <p className="muted" data-testid="policy-empty">
              {t('salaryAdvance.policies.empty')}
            </p>
          ) : (
            <PolicyRows
              items={view.items}
              busy={busy}
              onCancel={(item) => {
                void cancel(item);
              }}
            />
          )}
        </section>
      )}
      {view && (
        <PolicyForm
          legalEntityId={legalEntityId}
          versionOf={versionOf}
          onCommitted={() => {
            setRevision((value) => value + 1);
          }}
        />
      )}
    </>
  );
}

/**
 * MVP-120A: « Politique d’avance sur salaire » / "Salary-advance policy" under Payroll. A tenant
 * administrator with exact MFA chooses a legal entity, reads its employer participation policy
 * versions and adds one through preview and commit. A policy is an employer participation limit,
 * never a lending term, and nothing is enabled automatically.
 */
export function SalaryAdvancePoliciesPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [entityId, setEntityId] = useState('');
  const entities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const selected = entityId || (entities.items.length === 1 ? (entities.items[0]?.id ?? '') : '');
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="salary-advance-policies-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('salaryAdvance.policies.title')}
        description={t('salaryAdvance.policies.description')}
      />
      <LenderNotice />
      <section className="card" aria-labelledby={`${ids}-entity`}>
        <h2 id={`${ids}-entity`}>{t('payrollRuns.entity.title')}</h2>
        {entities.status === 'loading' && <p role="status">{t('salaryAdvance.loading')}</p>}
        {entities.status === 'failed' && (
          <p className="field__error" role="alert">
            {t('payrollRuns.entity.unavailable')}
          </p>
        )}
        {entities.status === 'ready' && entities.items.length === 0 && (
          <p className="muted">{t('payrollRuns.entity.none')}</p>
        )}
        {entities.items.length === 1 && entities.items[0] && (
          <p data-testid="policy-entity-name">
            {t('workSchedules.siteName', {
              name: entities.items[0].name,
              code: entities.items[0].code,
            })}
          </p>
        )}
        {entities.items.length > 1 && (
          <div className="field">
            <label htmlFor={`${ids}-entity-select`}>{t('payrollRuns.entity.label')}</label>
            <select
              id={`${ids}-entity-select`}
              value={entityId}
              data-testid="policy-entity"
              onChange={(event) => {
                setEntityId(event.target.value);
              }}
            >
              <option value="">{t('payrollRuns.entity.choose')}</option>
              {entities.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
          </div>
        )}
      </section>
      {selected && <EntityPolicies key={selected} legalEntityId={selected} />}
    </section>
  );
}

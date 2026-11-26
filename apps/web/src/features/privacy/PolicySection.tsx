import type { PrivacyPolicyState, PrivacyPolicyView } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PolicyState, PrivacyAlert } from './PrivacyBits';
import {
  ACTIONS,
  CHECKLIST_ITEMS,
  DATA_CLASSES,
  NEXT_POLICY_STATES,
  TRIGGERS,
  formatInstant,
  privacyFailureOf,
  todayIso,
  useCommand,
  type RuleForm,
} from './privacy';

const BLANK: RuleForm = { action: 'HOLD', trigger: 'REFERRAL_CLOSED', days: '' };
const EMPTY_RULES: RuleForm[] = DATA_CLASSES.map(() => BLANK);

/**
 * Policy versions. A version is created as a DRAFT with one rule per data class, reviewed through
 * a checklist, then approved and activated. DivalHR ships no country duration: every value here is
 * entered by an operator from a reviewed source, and an unreviewed version can never become ACTIVE.
 */
export function PolicySection() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [items, setItems] = useState<PrivacyPolicyView[]>([]);
  const [reload, setReload] = useState(0);
  const [loadFailure, setLoadFailure] = useState<ReturnType<typeof privacyFailureOf> | null>(null);
  const [country, setCountry] = useState('');
  const [wording, setWording] = useState('');
  const [source, setSource] = useState('');
  const [from, setFrom] = useState(todayIso());
  const [rules, setRules] = useState<RuleForm[]>(EMPTY_RULES);
  const [open, setOpen] = useState<string | null>(null);
  const [checks, setChecks] = useState<Record<string, boolean>>({});
  const [reviewRef, setReviewRef] = useState('');
  const ruleAt = (i: number): RuleForm => rules[i] ?? BLANK;
  const create = useCommand();
  const act = useCommand();
  const createKey = useIdempotencyKey();
  const actKey = useIdempotencyKey();

  useEffect(() => {
    let active = true;
    core
      .GET('/platform/privacy/policies', { params: { query: { limit: 50 } }, cache: 'no-store' })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setItems(data.items);
            setLoadFailure(null);
          } else {
            setLoadFailure(privacyFailureOf(response, error));
          }
        },
        () => undefined,
      );
    return () => {
      active = false;
    };
  }, [core, reload]);

  const submit = async (event: SyntheticEvent) => {
    event.preventDefault();
    const body = {
      countryCode: country.trim().toUpperCase(),
      wordingVersion: wording.trim(),
      sourceReference: source.trim(),
      reviewDate: todayIso(),
      effectiveFrom: from,
      effectiveTo: null,
      rules: DATA_CLASSES.map((c, i) => ({
        dataClass: c.dataClass,
        purpose: c.purpose,
        action: ruleAt(i).action,
        trigger: ruleAt(i).trigger,
        durationDays: ruleAt(i).action === 'HOLD' ? null : Number(ruleAt(i).days),
      })),
    };
    const created = await create.run(() =>
      core.POST('/platform/privacy/policies', {
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
        body,
      }),
    );
    if (created) {
      createKey.reset();
      setReload((n) => n + 1);
    }
  };

  const move = useCallback(
    async (policy: PrivacyPolicyView, to: PrivacyPolicyState) => {
      const body = { expectedVersion: policy.version, toState: to };
      const done = await act.run(() =>
        core.POST('/platform/privacy/policies/{policyId}/transition', {
          params: {
            path: { policyId: policy.id },
            header: { 'Idempotency-Key': actKey.keyFor({ id: policy.id, ...body }) },
          },
          body,
        }),
      );
      if (done) {
        actKey.reset();
        setReload((n) => n + 1);
      }
    },
    [act, actKey, core],
  );

  const saveChecklist = async (policy: PrivacyPolicyView) => {
    const flag = (item: (typeof CHECKLIST_ITEMS)[number]) => checks[item] === true;
    const checklist = {
      jurisdictionIdentified: flag('jurisdictionIdentified'),
      sourceReferenceRecorded: flag('sourceReferenceRecorded'),
      counselReviewRecorded: flag('counselReviewRecorded'),
      dataClassesCovered: flag('dataClassesCovered'),
      lifecycleTriggerDefined: flag('lifecycleTriggerDefined'),
      durationsOrHoldSet: flag('durationsOrHoldSet'),
      employeeWordingVersionRecorded: flag('employeeWordingVersionRecorded'),
      rollbackReviewComplete: flag('rollbackReviewComplete'),
      reviewReference: reviewRef.trim() === '' ? null : reviewRef.trim(),
    };
    const body = { expectedVersion: policy.version, checklist };
    const done = await act.run(() =>
      core.PUT('/platform/privacy/policies/{policyId}/checklist', {
        params: {
          path: { policyId: policy.id },
          header: { 'Idempotency-Key': actKey.keyFor({ id: policy.id, ...body }) },
        },
        body,
      }),
    );
    if (done) {
      actKey.reset();
      setOpen(null);
      setReload((n) => n + 1);
    }
  };

  const openChecklist = (policy: PrivacyPolicyView) => {
    setOpen(policy.id);
    setChecks(Object.fromEntries(CHECKLIST_ITEMS.map((i) => [i, policy.checklist[i]])));
    setReviewRef(policy.checklist.reviewReference ?? '');
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="privacy-policies">
      <h2 id={`${ids}-title`}>{t('privacy.policy.title')}</h2>
      <p className="muted">{t('privacy.policy.help')}</p>
      <form
        className="card"
        aria-labelledby={`${ids}-new`}
        onSubmit={(event) => void submit(event)}
        data-testid="privacy-policy-form"
      >
        <h3 id={`${ids}-new`}>{t('privacy.policy.new')}</h3>
        <div className="field">
          <label htmlFor={`${ids}-country`}>{t('privacy.policy.country')}</label>
          <input
            id={`${ids}-country`}
            value={country}
            maxLength={2}
            autoComplete="off"
            onChange={(event) => {
              setCountry(event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-wording`}>{t('privacy.policy.wording')}</label>
          <input
            id={`${ids}-wording`}
            value={wording}
            maxLength={40}
            autoComplete="off"
            onChange={(event) => {
              setWording(event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-source`}>{t('privacy.policy.source')}</label>
          <input
            id={`${ids}-source`}
            value={source}
            maxLength={300}
            autoComplete="off"
            onChange={(event) => {
              setSource(event.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor={`${ids}-from`}>{t('privacy.policy.effectiveFrom')}</label>
          <input
            id={`${ids}-from`}
            type="date"
            value={from}
            onChange={(event) => {
              setFrom(event.target.value);
            }}
          />
        </div>
        {DATA_CLASSES.map((c, i) => (
          <fieldset key={c.dataClass} className="field" data-testid="privacy-rule">
            <legend>{t(`privacy.dataClass.${c.dataClass}`)}</legend>
            <label htmlFor={`${ids}-action-${i}`}>{t('privacy.policy.action')}</label>
            <select
              id={`${ids}-action-${i}`}
              value={ruleAt(i).action}
              onChange={(event) => {
                const action = event.target.value as RuleForm['action'];
                setRules(rules.map((r, n) => (n === i ? { ...r, action } : r)));
              }}
            >
              {ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {t(`privacy.action.${a}`)}
                </option>
              ))}
            </select>
            <label htmlFor={`${ids}-trigger-${i}`}>{t('privacy.policy.trigger')}</label>
            <select
              id={`${ids}-trigger-${i}`}
              value={ruleAt(i).trigger}
              onChange={(event) => {
                const trigger = event.target.value as RuleForm['trigger'];
                setRules(rules.map((r, n) => (n === i ? { ...r, trigger } : r)));
              }}
            >
              {TRIGGERS.map((g) => (
                <option key={g} value={g}>
                  {t(`privacy.trigger.${g}`)}
                </option>
              ))}
            </select>
            {ruleAt(i).action !== 'HOLD' && (
              <>
                <label htmlFor={`${ids}-days-${i}`}>{t('privacy.policy.days')}</label>
                <input
                  id={`${ids}-days-${i}`}
                  inputMode="numeric"
                  value={ruleAt(i).days}
                  onChange={(event) => {
                    const days = event.target.value.replace(/[^0-9]/g, '');
                    setRules(rules.map((r, n) => (n === i ? { ...r, days } : r)));
                  }}
                />
              </>
            )}
          </fieldset>
        ))}
        <PrivacyAlert failure={create.failure} testId="privacy-policy-create-failure" />
        <div className="button-row">
          <button type="submit" className="button" disabled={create.working}>
            {t('privacy.policy.create')}
          </button>
        </div>
      </form>
      <PrivacyAlert failure={loadFailure} testId="privacy-policy-load-failure" />
      <PrivacyAlert failure={act.failure} testId="privacy-policy-action-failure" />
      {items.length === 0 ? (
        <p className="muted" data-testid="privacy-policy-empty">
          {t('privacy.policy.empty')}
        </p>
      ) : (
        <ul className="partner-catalogue" data-testid="privacy-policy-list">
          {items.map((policy) => (
            <li
              key={policy.id}
              className="card"
              data-testid="privacy-policy"
              data-reference={policy.id}
              data-state={policy.state}
              data-country={policy.countryCode}
            >
              <h3>
                {policy.countryCode} · {policy.wordingVersion}
              </h3>
              <p>
                <PolicyState state={policy.state} />{' '}
                <span className="muted">{formatInstant(policy.updatedAt, i18n.language)}</span>
              </p>
              <dl className="detail-list">
                <dt>{t('privacy.policy.effectiveFrom')}</dt>
                <dd>{policy.effectiveFrom}</dd>
                <dt>{t('privacy.policy.source')}</dt>
                <dd>{policy.sourceReference}</dd>
              </dl>
              <ul className="detail-list">
                {policy.rules.map((rule) => (
                  <li key={rule.dataClass}>
                    {t(`privacy.dataClass.${rule.dataClass}`)} ·{' '}
                    {t(`privacy.action.${rule.action}`)}
                    {rule.durationDays === null
                      ? ''
                      : ` · ${t('privacy.policy.daysValue', { count: rule.durationDays })}`}
                  </li>
                ))}
              </ul>
              <div className="button-row">
                {policy.state === 'DRAFT' && (
                  <button
                    type="button"
                    className="button button--secondary"
                    data-testid="privacy-policy-checklist"
                    onClick={() => {
                      openChecklist(policy);
                    }}
                  >
                    {t('privacy.policy.checklist')}
                    <span className="visually-hidden"> — {policy.id}</span>
                  </button>
                )}
                {NEXT_POLICY_STATES[policy.state].map((to) => (
                  <button
                    key={to}
                    type="button"
                    className="button"
                    disabled={act.working}
                    data-testid={`privacy-policy-to-${to}`}
                    onClick={() => void move(policy, to)}
                  >
                    {t(`privacy.policy.to.${to}`)}
                    <span className="visually-hidden"> — {policy.id}</span>
                  </button>
                ))}
              </div>
              {open === policy.id && (
                <fieldset className="card" data-testid="privacy-checklist-form">
                  <legend>{t('privacy.policy.checklist')}</legend>
                  {CHECKLIST_ITEMS.map((item) => (
                    <div key={item} className="field field--checkbox">
                      <input
                        id={`${ids}-${policy.id}-${item}`}
                        type="checkbox"
                        checked={checks[item] === true}
                        onChange={(event) => {
                          setChecks({ ...checks, [item]: event.target.checked });
                        }}
                      />
                      <label htmlFor={`${ids}-${policy.id}-${item}`}>
                        {t(`privacy.checklist.${item}`)}
                      </label>
                    </div>
                  ))}
                  <div className="field">
                    <label htmlFor={`${ids}-${policy.id}-review`}>
                      {t('privacy.policy.reviewReference')}
                    </label>
                    <input
                      id={`${ids}-${policy.id}-review`}
                      value={reviewRef}
                      maxLength={120}
                      autoComplete="off"
                      onChange={(event) => {
                        setReviewRef(event.target.value);
                      }}
                    />
                  </div>
                  <div className="button-row">
                    <button
                      type="button"
                      className="button"
                      disabled={act.working}
                      data-testid="privacy-checklist-save"
                      onClick={() => void saveChecklist(policy)}
                    >
                      {t('privacy.policy.saveChecklist')}
                    </button>
                  </div>
                </fieldset>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

import { Fragment, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import {
  formatDay,
  NETWORK_FAILURE,
  type PayrollFailure,
  ISO_DATE,
} from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { AccountingNotice } from './AccountingBits';
import {
  ACCOUNT_FIELDS,
  accountCodeValid,
  accountingFailureOf,
  CURRENCIES,
  type AccountingAccounts,
  type AccountingCurrency,
  type AccountingMapping,
  type AccountingMappingPreview,
} from './accounting';

type ListLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: AccountingMapping[] };

const EMPTY_ACCOUNTS: AccountingAccounts = {
  salaryExpense: '',
  employeeDeductionLiability: '',
  employerContributionExpense: '',
  employerContributionLiability: '',
  netSalaryPayable: '',
};

const TONES = { SCHEDULED: 'info', ACTIVE: 'success', SUPERSEDED: 'neutral' } as const;

/** The versions of one currency, newest first, each with its derived status. */
function VersionList({
  legalEntityId,
  currency,
  revision,
}: {
  legalEntityId: string;
  currency: AccountingCurrency;
  revision: number;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<ListLoad>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/accounting-mappings', {
        params: { path: { legalEntityId }, query: { currency } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', items: data.items }
              : { kind: 'failed', failure: accountingFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, currency, revision]);

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="accounting-mapping-list"
      data-currency={currency}
    >
      <h2 id={`${ids}-title`}>{t('accounting.mappings.list.title', { currency })}</h2>
      {load.kind === 'loading' && (
        <p role="status" data-testid="accounting-mapping-loading">
          {t('accounting.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="accounting-mapping-list-failure" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="accounting-mapping-empty">
            {t('accounting.mappings.list.empty')}
          </p>
        ) : (
          <ScrollRegion label={t('accounting.mappings.list.tableLabel', { currency })}>
            <table className="timesheet-table" data-testid="accounting-mapping-table">
              <caption className="visually-hidden">
                {t('accounting.mappings.list.tableLabel', { currency })}
              </caption>
              <thead>
                <tr>
                  <th scope="col">{t('accounting.mappings.list.version')}</th>
                  <th scope="col">{t('accounting.mappings.list.effective')}</th>
                  <th scope="col">{t('accounting.mappings.list.status')}</th>
                  {ACCOUNT_FIELDS.map((field) => (
                    <th scope="col" key={field}>
                      {t(`accounting.accounts.${field}`)}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {load.items.map((mapping) => (
                  <tr
                    key={mapping.id}
                    data-testid="accounting-mapping-row"
                    data-status={mapping.status}
                  >
                    <th scope="row">{mapping.version}</th>
                    <td>{formatDay(i18n.language, mapping.effectiveFrom)}</td>
                    <td>
                      <StatusBadge tone={TONES[mapping.status]}>
                        {t(`accounting.mappings.status.${mapping.status}`)}
                      </StatusBadge>
                    </td>
                    {ACCOUNT_FIELDS.map((field) => (
                      <td key={field}>
                        <code>{mapping.accounts[field]}</code>
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        ))}
    </section>
  );
}

/** Schedule a new version: five codes and a date, a preview, a confirmation, then the commit. */
function MappingForm({
  legalEntityId,
  onCreated,
}: {
  legalEntityId: string;
  onCreated: (message: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [currency, setCurrency] = useState<AccountingCurrency>('CDF');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [accounts, setAccounts] = useState<AccountingAccounts>(EMPTY_ACCOUNTS);
  const [preview, setPreview] = useState<AccountingMappingPreview | null>(null);
  const [understood, setUnderstood] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);

  const trimmed: AccountingAccounts = {
    salaryExpense: accounts.salaryExpense.trim(),
    employeeDeductionLiability: accounts.employeeDeductionLiability.trim(),
    employerContributionExpense: accounts.employerContributionExpense.trim(),
    employerContributionLiability: accounts.employerContributionLiability.trim(),
    netSalaryPayable: accounts.netSalaryPayable.trim(),
  };
  const complete =
    ISO_DATE.test(effectiveFrom) && ACCOUNT_FIELDS.every((field) => trimmed[field] !== '');

  /** Any edit discards the review: nothing can be confirmed on values that changed. */
  const edit = () => {
    setPreview(null);
    setUnderstood(false);
    setFailure(null);
    key.reset();
  };

  const onPreview = async () => {
    if (busy || !complete) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/accounting-mappings/preview',
        {
          params: { path: { legalEntityId } },
          body: { currency, effectiveFrom, accounts: trimmed },
          cache: 'no-store',
        },
      );
      setUnderstood(false);
      key.reset();
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(accountingFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const committable =
    preview !== null &&
    preview.status === 'READY' &&
    preview.previewDigest !== null &&
    preview.expiresAt !== null;

  const onCommit = async () => {
    if (!preview || busy || !committable || !understood) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body = {
      currency: preview.currency,
      effectiveFrom: preview.effectiveFrom,
      accounts: preview.accounts,
      expectedVersion: preview.nextVersion,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      understandExportOnly: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/accounting-mappings',
        {
          params: {
            path: { legalEntityId },
            header: { 'Idempotency-Key': key.keyFor({ legalEntityId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        setPreview(null);
        setUnderstood(false);
        setEffectiveFrom('');
        setAccounts(EMPTY_ACCOUNTS);
        key.reset();
        onCreated(t('accounting.mappings.form.announceCreated'));
        return;
      }
      const next = accountingFailureOf(response, error);
      if (next.kind === 'stale') {
        setPreview(null);
        setUnderstood(false);
        key.reset();
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

  const whyKey =
    preview === null
      ? null
      : !committable
        ? 'accounting.mappings.form.whyBlocked'
        : !understood
          ? 'accounting.mappings.form.whyConfirm'
          : null;

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="accounting-mapping-form"
    >
      <h2 id={`${ids}-title`}>{t('accounting.mappings.form.title')}</h2>
      <p className="muted">{t('accounting.mappings.form.help')}</p>
      <div className="field">
        <label htmlFor={`${ids}-currency`}>{t('accounting.mappings.form.currency')}</label>
        <select
          id={`${ids}-currency`}
          value={currency}
          data-testid="accounting-mapping-currency"
          onChange={(event) => {
            setCurrency(event.target.value === 'USD' ? 'USD' : 'CDF');
            edit();
          }}
        >
          {CURRENCIES.map((option) => (
            <option key={option} value={option}>
              {t(`accounting.currency.${option}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${ids}-effective`}>{t('accounting.mappings.form.effectiveFrom')}</label>
        <input
          id={`${ids}-effective`}
          type="date"
          value={effectiveFrom}
          aria-describedby={`${ids}-effective-help`}
          data-testid="accounting-mapping-effective"
          onChange={(event) => {
            setEffectiveFrom(event.target.value);
            edit();
          }}
        />
        <p id={`${ids}-effective-help`} className="muted">
          {t('accounting.mappings.form.effectiveHelp')}
        </p>
      </div>
      {ACCOUNT_FIELDS.map((field) => (
        <div className="field" key={field}>
          <label htmlFor={`${ids}-${field}`}>{t(`accounting.accounts.${field}`)}</label>
          <input
            id={`${ids}-${field}`}
            type="text"
            value={accounts[field]}
            maxLength={60}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            aria-describedby={`${ids}-code-hint`}
            aria-invalid={
              trimmed[field] !== '' && !accountCodeValid(trimmed[field]) ? true : undefined
            }
            data-testid={`accounting-account-${field}`}
            onChange={(event) => {
              setAccounts((previous) => ({ ...previous, [field]: event.target.value }));
              edit();
            }}
          />
        </div>
      ))}
      <p id={`${ids}-code-hint`} className="muted" data-testid="accounting-code-hint">
        {t('accounting.mappings.form.codeHint')}
      </p>
      <div className="dialog-actions">
        <button
          type="button"
          className="button button--secondary"
          disabled={busy !== null || !complete}
          data-testid="accounting-mapping-preview-button"
          onClick={() => void onPreview()}
        >
          {busy === 'preview'
            ? t('accounting.mappings.form.previewing')
            : t('accounting.mappings.form.preview')}
        </button>
      </div>
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="accounting-mapping-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {failure?.kind === 'stale' && (
        <p role="status" data-testid="accounting-mapping-stale">
          {t('accounting.mappings.form.staleRefresh')}
        </p>
      )}
      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="accounting-mapping-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('accounting.mappings.form.previewHeading')}
          </h3>
          <p>
            <StatusBadge
              tone={preview.status === 'READY' ? 'success' : 'danger'}
              testId="accounting-mapping-preview-status"
            >
              {t(`accounting.previewStatus.${preview.status}`)}
            </StatusBadge>
          </p>
          <dl className="decision-summary decision-summary--wrapping">
            <dt>{t('accounting.mappings.form.currency')}</dt>
            <dd>{preview.currency}</dd>
            <dt>{t('accounting.mappings.form.nextVersion')}</dt>
            <dd data-testid="accounting-mapping-next-version">{preview.nextVersion}</dd>
            <dt>{t('accounting.mappings.form.effectiveFrom')}</dt>
            <dd>{formatDay(i18n.language, preview.effectiveFrom)}</dd>
            <dt>{t('accounting.mappings.form.previousEffective')}</dt>
            <dd>
              {preview.previousEffectiveFrom
                ? formatDay(i18n.language, preview.previousEffectiveFrom)
                : t('accounting.mappings.form.previousNone')}
            </dd>
            {ACCOUNT_FIELDS.map((field) => (
              <Fragment key={field}>
                <dt>{t(`accounting.accounts.${field}`)}</dt>
                <dd>
                  <code className="accounting-code">{preview.accounts[field]}</code>
                </dd>
              </Fragment>
            ))}
          </dl>
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="accounting-mapping-blockers">
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker} data-code={blocker}>
                    {t(`accounting.mappings.blockers.${blocker}`)}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {committable && preview.expiresAt && (
            <p className="muted">
              {t('accounting.mappings.form.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <div className="choice">
            <input
              id={`${ids}-understand`}
              type="checkbox"
              checked={understood}
              disabled={!committable}
              data-testid="accounting-mapping-understand"
              onChange={(event) => {
                setUnderstood(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-understand`}>{t('accounting.mappings.form.confirm')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="accounting-mapping-commit"
              disabled={busy !== null || !committable || !understood}
              aria-describedby={whyKey ? `${ids}-why` : undefined}
              onClick={() => void onCommit()}
            >
              {busy === 'commit'
                ? t('accounting.mappings.form.saving')
                : t('accounting.mappings.form.commit')}
            </button>
          </div>
          {whyKey && (
            <p id={`${ids}-why`} className="muted" data-testid="accounting-mapping-why">
              {t(whyKey)}
            </p>
          )}
        </section>
      )}
    </section>
  );
}

/**
 * MVP-096 (D96-8): the accounting mappings of a legal entity. One append-only list of versions per
 * currency (derived status SCHEDULED, ACTIVE or SUPERSEDED) and a form that schedules a new version
 * through a server preview and an explicit confirmation. Account codes are kept only in component
 * state, never in the URL (the entity identifier may be: it is already part of other payroll URLs).
 */
export function AccountingMappingsPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [params] = useSearchParams();
  const [entityId, setEntityId] = useState(params.get('entity') ?? '');
  const [revision, setRevision] = useState(0);
  const [announcement, setAnnouncement] = useState('');

  const entities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const selected = entityId || (entities.items.length === 1 ? (entities.items[0]?.id ?? '') : '');
  const single = entities.items.length === 1;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="accounting-mappings">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('accounting.mappings.title')}
        description={t('accounting.mappings.description')}
      />
      <AccountingNotice />
      <p role="status" className="visually-hidden" data-testid="accounting-mappings-announcer">
        {announcement}
      </p>
      <section className="card" aria-labelledby={`${ids}-entity`}>
        <h2 id={`${ids}-entity`}>{t('accounting.mappings.entity.title')}</h2>
        {entities.status === 'loading' && <p role="status">{t('accounting.loading')}</p>}
        {entities.status === 'failed' && (
          <p className="field__error" role="alert" data-testid="accounting-entities-error">
            {t('accounting.mappings.entity.unavailable')}
          </p>
        )}
        {entities.status === 'ready' && entities.items.length === 0 && (
          <p className="muted" data-testid="accounting-entities-none">
            {t('accounting.mappings.entity.none')}
          </p>
        )}
        {single && entities.items[0] && (
          <p data-testid="accounting-entity-name">
            {t('workSchedules.siteName', {
              name: entities.items[0].name,
              code: entities.items[0].code,
            })}
          </p>
        )}
        {entities.items.length > 1 && (
          <div className="field">
            <label htmlFor={`${ids}-entity-select`}>{t('accounting.mappings.entity.label')}</label>
            <select
              id={`${ids}-entity-select`}
              value={entityId}
              data-testid="accounting-entity"
              onChange={(event) => {
                setEntityId(event.target.value);
              }}
            >
              <option value="">{t('accounting.mappings.entity.choose')}</option>
              {entities.items.map((option) => (
                <option key={option.id} value={option.id}>
                  {t('workSchedules.siteName', { name: option.name, code: option.code })}
                </option>
              ))}
            </select>
          </div>
        )}
      </section>
      {selected && (
        <>
          <MappingForm
            key={`form-${selected}`}
            legalEntityId={selected}
            onCreated={(message) => {
              setAnnouncement(message);
              setRevision((value) => value + 1);
            }}
          />
          {CURRENCIES.map((currency) => (
            <VersionList
              key={`${selected}-${currency}`}
              legalEntityId={selected}
              currency={currency}
              revision={revision}
            />
          ))}
        </>
      )}
    </section>
  );
}

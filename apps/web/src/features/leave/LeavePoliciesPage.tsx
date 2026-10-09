import type { LeavePolicy } from '@divalhr/api-client';
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type ReactNode,
  type Ref,
  type SyntheticEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatDate, formatPeriod } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import {
  APPROVAL_ROUTES,
  BALANCE_MODES,
  EMPTY_FORM,
  LEAVE_NETWORK_FAILURE,
  PAYROLL_EFFECTS,
  UNITS,
  bodyOf,
  leaveFailureOf,
  problemsOf,
  toneOf,
  type Field,
  type LeaveFailure,
  type LeavePolicyForm,
} from './leavePolicies';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: LeaveFailure }
  | {
      kind: 'ready';
      items: LeavePolicy[];
      nextCursor: string | null;
      asOf: string;
      timezone: string;
    };

/** A refused request: the stable message, the named fields, retry delay and reference. */
function LeaveAlert({
  failure,
  alertRef,
  testId,
}: {
  failure: LeaveFailure;
  alertRef?: Ref<HTMLDivElement>;
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <div
      className="error-summary"
      role="alert"
      tabIndex={-1}
      ref={alertRef}
      data-testid={testId}
      data-kind={failure.kind}
    >
      <p>{t(failure.messageKey)}</p>
      {failure.fields && failure.fields.length > 0 && (
        <ul>
          {failure.fields.map((field) => (
            <li key={field}>{t(`leavePolicies.problems.${field}`)}</li>
          ))}
        </ul>
      )}
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/**
 * MVP-040A: the organization's leave policy catalogue, with each policy's status on the server's
 * business date, and the creation of a policy. Configuration only: requests, balance calculation
 * and approvals are not enabled. No value is suggested; nothing is kept in browser storage.
 */
export function LeavePoliciesPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const createKey = useIdempotencyKey();
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [form, setForm] = useState<LeavePolicyForm>(EMPTY_FORM);
  const [problems, setProblems] = useState<Set<Field>>(new Set());
  const [failure, setFailure] = useState<LeaveFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const failureBox = useRef<HTMLDivElement>(null);
  const problemsBox = useRef<HTMLDivElement>(null);
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const other = language === 'fr' ? 'en' : 'fr';

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/leave-policies', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? {
              kind: 'ready',
              items: data.items,
              nextCursor: data.nextCursor,
              asOf: data.asOf,
              timezone: data.timezone,
            }
          : { kind: 'failed', failure: leaveFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: LEAVE_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    const next = await fetchPage(null);
    setListing(next);
  }, [fetchPage]);

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setListing(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  useEffect(() => {
    if (failure) failureBox.current?.focus();
  }, [failure]);

  // R86-1: one page request at a time; a second activation never re-requests the same cursor.
  const paging = useRef<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const more = async (cursor: string) => {
    if (paging.current !== null) return;
    paging.current = cursor;
    setLoadingMore(true);
    try {
      const next = await fetchPage(cursor);
      setListing((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      paging.current = null;
      setLoadingMore(false);
    }
  };

  const set = <K extends keyof LeavePolicyForm>(key: K, value: LeavePolicyForm[K]) => {
    setForm((previous) => ({ ...previous, [key]: value }));
  };

  const onCreate = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = problemsOf(form);
    setProblems(found);
    setAnnouncement('');
    if (found.size > 0) {
      setFailure(null);
      requestAnimationFrame(() => problemsBox.current?.focus());
      return;
    }
    const body = bodyOf(form);
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/leave-policies', {
        body,
        // The same command keeps its key across retries; any edit is a new command (new key).
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
      });
      if (data) {
        createKey.reset();
        setForm(EMPTY_FORM);
        setProblems(new Set());
        setAnnouncement(
          t('leavePolicies.created', { name: data.policy.names[language], code: data.policy.code }),
        );
        await reload();
        return;
      }
      const refused = leaveFailureOf(response, error);
      if (refused.kind === 'validation' && refused.fields) setProblems(new Set(refused.fields));
      setFailure(refused);
    } catch {
      setFailure(LEAVE_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const fieldId = (field: Field) => `${ids}-${field}`;
  const invalid = (field: Field) => (problems.has(field) ? true : undefined);
  const describedBy = (field: Field, help?: boolean) =>
    [help ? `${fieldId(field)}-help` : null, problems.has(field) ? `${fieldId(field)}-error` : null]
      .filter(Boolean)
      .join(' ') || undefined;
  const fieldError = (field: Field) =>
    problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`leavePolicies.problems.${field}`)}
      </p>
    ) : null;

  const choice = (
    field: 'unit' | 'balanceMode' | 'approvalRoute' | 'payrollEffect',
    options: readonly string[],
    help?: ReactNode,
  ) => (
    <div className="field">
      <label htmlFor={fieldId(field)}>{t(`leavePolicies.form.${field}`)}</label>
      {help}
      {fieldError(field)}
      <select
        id={fieldId(field)}
        value={form[field]}
        aria-invalid={invalid(field)}
        aria-describedby={describedBy(field, help !== undefined)}
        onChange={(event) => {
          set(field, event.target.value as LeavePolicyForm[typeof field]);
        }}
      >
        <option value="">{t('leavePolicies.form.choose')}</option>
        {options.map((option) => (
          <option key={option} value={option}>
            {t(`leavePolicies.values.${field}.${option}`)}
          </option>
        ))}
      </select>
    </div>
  );

  const entitlement = (policy: LeavePolicy) =>
    policy.annualEntitlement === null
      ? t('leavePolicies.untracked')
      : t(`leavePolicies.entitlement.${policy.unit}`, {
          amount: new Intl.NumberFormat(language, {
            minimumFractionDigits: 0,
            maximumFractionDigits: 2,
          }).format(policy.annualEntitlement),
        });

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="leave-policies">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('leavePolicies.title')}
        description={t('leavePolicies.description')}
      />
      <div className="card" data-testid="leave-scope">
        <p>{t('leavePolicies.notEnabled')}</p>
        <p className="muted">{t('leavePolicies.legal')}</p>
      </div>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-list`} className="card">
        <h2 id={`${ids}-list`}>{t('leavePolicies.catalogue')}</h2>
        {listing.kind === 'ready' && (
          <p className="muted" data-testid="as-of">
            {t('leavePolicies.asOf', {
              date: formatDate(language, listing.asOf),
              timezone: listing.timezone,
            })}
          </p>
        )}
        {listing.kind === 'loading' && <p role="status">{t('leavePolicies.loading')}</p>}
        {listing.kind === 'failed' && (
          <>
            <LeaveAlert failure={listing.failure} testId="list-error" />
            {listing.failure.kind !== 'forbidden' && (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setListing({ kind: 'loading' });
                  void reload();
                }}
              >
                {t('leavePolicies.retry')}
              </button>
            )}
          </>
        )}
        {listing.kind === 'ready' && listing.items.length === 0 && (
          <p data-testid="policies-empty">{t('leavePolicies.empty')}</p>
        )}
        {listing.kind === 'ready' && listing.items.length > 0 && (
          <ScrollRegion label={t('leavePolicies.list')}>
            <table data-testid="policies">
              <caption className="visually-hidden">{t('leavePolicies.list')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('leavePolicies.columns.name')}</th>
                  <th scope="col">{t('leavePolicies.columns.code')}</th>
                  <th scope="col">{t('leavePolicies.columns.balance')}</th>
                  <th scope="col">{t('leavePolicies.columns.rules')}</th>
                  <th scope="col">{t('leavePolicies.columns.period')}</th>
                  <th scope="col">{t('leavePolicies.columns.status')}</th>
                </tr>
              </thead>
              <tbody>
                {listing.items.map((policy) => (
                  <tr key={policy.id} data-testid="policy-row">
                    <th scope="row">
                      <span lang={language}>{policy.names[language]}</span>
                      <br />
                      <span className="muted" lang={other}>
                        {policy.names[other]}
                      </span>
                    </th>
                    <td>{policy.code}</td>
                    <td>{entitlement(policy)}</td>
                    <td>
                      <ul className="plain-list">
                        <li>
                          {policy.minimumServiceDays === 0
                            ? t('leavePolicies.minimumServiceNone')
                            : t('leavePolicies.minimumService', {
                                count: policy.minimumServiceDays,
                              })}
                        </li>
                        <li>{t(`leavePolicies.values.approvalRoute.${policy.approvalRoute}`)}</li>
                        <li>{t(`leavePolicies.values.payrollEffect.${policy.payrollEffect}`)}</li>
                      </ul>
                    </td>
                    <td>{formatPeriod(t, language, policy.effectiveFrom, policy.effectiveTo)}</td>
                    <td>
                      <StatusBadge tone={toneOf(policy.status)} testId="policy-status">
                        {t(`leavePolicies.status.${policy.status}`)}
                      </StatusBadge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {listing.kind === 'ready' && listing.nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={loadingMore}
              aria-busy={loadingMore ? true : undefined}
              onClick={() => {
                if (listing.nextCursor) void more(listing.nextCursor);
              }}
            >
              {loadingMore ? t('leavePolicies.loadingMore') : t('leavePolicies.more')}
            </button>
          </p>
        )}
      </section>

      <section aria-labelledby={`${ids}-create`} className="card" data-testid="create-policy">
        <h2 id={`${ids}-create`}>{t('leavePolicies.form.title')}</h2>
        <p className="muted">{t('leavePolicies.form.intro')}</p>
        {problems.size > 0 && !failure && (
          <div
            className="error-summary"
            role="alert"
            tabIndex={-1}
            ref={problemsBox}
            data-testid="create-problems"
          >
            <p>{t('employees.form.problems.title')}</p>
            <ul>
              {[...problems].map((problem) => (
                <li key={problem}>
                  <a href={`#${fieldId(problem)}`}>{t(`leavePolicies.problems.${problem}`)}</a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {failure && <LeaveAlert failure={failure} alertRef={failureBox} testId="create-error" />}
        <form noValidate onSubmit={(event) => void onCreate(event)}>
          <div className="field">
            <label htmlFor={fieldId('code')}>{t('leavePolicies.form.code')}</label>
            <p id={`${fieldId('code')}-help`} className="field__help">
              {t('leavePolicies.form.codeHelp')}
            </p>
            {fieldError('code')}
            <input
              id={fieldId('code')}
              type="text"
              value={form.code}
              maxLength={20}
              autoComplete="off"
              spellCheck={false}
              aria-invalid={invalid('code')}
              aria-describedby={describedBy('code', true)}
              onChange={(event) => {
                set('code', event.target.value.toUpperCase());
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={fieldId('nameEn')}>{t('leavePolicies.form.nameEn')}</label>
            {fieldError('nameEn')}
            <input
              id={fieldId('nameEn')}
              type="text"
              lang="en"
              value={form.nameEn}
              maxLength={100}
              autoComplete="off"
              aria-invalid={invalid('nameEn')}
              aria-describedby={describedBy('nameEn')}
              onChange={(event) => {
                set('nameEn', event.target.value);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={fieldId('nameFr')}>{t('leavePolicies.form.nameFr')}</label>
            {fieldError('nameFr')}
            <input
              id={fieldId('nameFr')}
              type="text"
              lang="fr"
              value={form.nameFr}
              maxLength={100}
              autoComplete="off"
              aria-invalid={invalid('nameFr')}
              aria-describedby={describedBy('nameFr')}
              onChange={(event) => {
                set('nameFr', event.target.value);
              }}
            />
          </div>
          {choice('unit', UNITS)}
          {choice(
            'balanceMode',
            BALANCE_MODES,
            <p id={`${fieldId('balanceMode')}-help`} className="field__help">
              {t('leavePolicies.form.balanceModeHelp')}
            </p>,
          )}
          {form.balanceMode === 'TRACKED' && (
            <div className="field">
              <label htmlFor={fieldId('annualEntitlement')}>
                {t('leavePolicies.form.annualEntitlement')}
              </label>
              <p id={`${fieldId('annualEntitlement')}-help`} className="field__help">
                {t('leavePolicies.form.annualEntitlementHelp')}
              </p>
              {fieldError('annualEntitlement')}
              <input
                id={fieldId('annualEntitlement')}
                type="text"
                inputMode="decimal"
                value={form.annualEntitlement}
                maxLength={8}
                autoComplete="off"
                aria-invalid={invalid('annualEntitlement')}
                aria-describedby={describedBy('annualEntitlement', true)}
                onChange={(event) => {
                  set('annualEntitlement', event.target.value);
                }}
              />
            </div>
          )}
          <div className="field">
            <label htmlFor={fieldId('minimumServiceDays')}>
              {t('leavePolicies.form.minimumServiceDays')}
            </label>
            <p id={`${fieldId('minimumServiceDays')}-help`} className="field__help">
              {t('leavePolicies.form.minimumServiceDaysHelp')}
            </p>
            {fieldError('minimumServiceDays')}
            <input
              id={fieldId('minimumServiceDays')}
              type="text"
              inputMode="numeric"
              value={form.minimumServiceDays}
              maxLength={4}
              autoComplete="off"
              aria-invalid={invalid('minimumServiceDays')}
              aria-describedby={describedBy('minimumServiceDays', true)}
              onChange={(event) => {
                set('minimumServiceDays', event.target.value);
              }}
            />
          </div>
          {choice('approvalRoute', APPROVAL_ROUTES)}
          {choice(
            'payrollEffect',
            PAYROLL_EFFECTS,
            <p id={`${fieldId('payrollEffect')}-help`} className="field__help">
              {t('leavePolicies.form.payrollEffectHelp')}
            </p>,
          )}
          <div className="field">
            <label htmlFor={fieldId('effectiveFrom')}>
              {t('leavePolicies.form.effectiveFrom')}
            </label>
            {fieldError('effectiveFrom')}
            <input
              id={fieldId('effectiveFrom')}
              type="date"
              value={form.effectiveFrom}
              min="1900-01-01"
              max="2999-12-31"
              aria-invalid={invalid('effectiveFrom')}
              aria-describedby={describedBy('effectiveFrom')}
              onChange={(event) => {
                set('effectiveFrom', event.target.value);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={fieldId('effectiveTo')}>{t('leavePolicies.form.effectiveTo')}</label>
            <p id={`${fieldId('effectiveTo')}-help`} className="field__help">
              {t('leavePolicies.form.effectiveToHelp')}
            </p>
            {fieldError('effectiveTo')}
            <input
              id={fieldId('effectiveTo')}
              type="date"
              value={form.effectiveTo}
              min="1900-01-01"
              max="2999-12-31"
              aria-invalid={invalid('effectiveTo')}
              aria-describedby={describedBy('effectiveTo', true)}
              onChange={(event) => {
                set('effectiveTo', event.target.value);
              }}
            />
          </div>
          <button type="submit" className="button" disabled={busy}>
            {busy ? t('leavePolicies.form.submitting') : t('leavePolicies.form.submit')}
          </button>
        </form>
      </section>
    </section>
  );
}

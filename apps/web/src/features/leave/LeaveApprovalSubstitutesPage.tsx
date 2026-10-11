import type { EmployeeSummary, LeaveApprovalSubstitution } from '@divalhr/api-client';
import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type Ref,
  type SyntheticEvent,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatDate, formatPeriod } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { EmployeeSearchPicker } from './EmployeeSearchPicker';
import { REASON_MAX, codePoints, normalizeReason, type ReasonLocale } from './leaveApprovals';
import {
  SUBSTITUTE_FIELDS,
  SUBSTITUTE_NETWORK_FAILURE,
  emptySubstituteForm,
  revocable,
  revokeBodyOf,
  revokeProblemsOf,
  substituteBodyOf,
  substituteFailureOf,
  substituteProblemsOf,
  substituteToneOf,
  type RevokeField,
  type RevokeForm,
  type SubstituteFailure,
  type SubstituteField,
  type SubstituteForm,
} from './leaveSubstitutes';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: SubstituteFailure }
  | { kind: 'ready'; items: LeaveApprovalSubstitution[]; nextCursor: string | null; asOf: string };

type Revoking = {
  item: LeaveApprovalSubstitution;
  step: 'form' | 'confirm';
  form: RevokeForm;
  problems: Set<RevokeField>;
  failure: SubstituteFailure | null;
};

/** A refused request: the stable message, the named fields, retry delay and reference. */
function SubstituteAlert({
  failure,
  alertRef,
  testId,
}: {
  failure: SubstituteFailure;
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
            <li key={field}>{t(`leaveSubstitutes.problems.${field}`)}</li>
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
 * MVP-041G: the tenant administrators' leave approval substitutes (« Remplaçants pour approbation
 * des congés » under People): the appointments with their states on the organization's business
 * date, the appointment of one substitute for a manager (searched in the directory, reviewed, then
 * confirmed, with a stable idempotency key) and the revocation of an appointment in a modal
 * dialog. Names and reasons are plain text; nothing is kept in browser storage or put in a URL.
 */
export function LeaveApprovalSubstitutesPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const createKey = useIdempotencyKey();
  const revokeKey = useIdempotencyKey();
  const language: ReasonLocale = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [form, setForm] = useState<SubstituteForm>(() => emptySubstituteForm(language));
  const [step, setStep] = useState<'form' | 'confirm'>('form');
  const [problems, setProblems] = useState<Set<SubstituteField>>(new Set());
  const [failure, setFailure] = useState<SubstituteFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [revoking, setRevoking] = useState<Revoking | null>(null);
  const [announcement, setAnnouncement] = useState('');
  // One request in flight per action: a second activation sends nothing.
  const sending = useRef(false);
  const paging = useRef(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const failureBox = useRef<HTMLDivElement>(null);
  const problemsBox = useRef<HTMLDivElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const revokeConfirmRef = useRef<HTMLButtonElement>(null);
  const revokeProblemsRef = useRef<HTMLDivElement>(null);
  const revokeFailureRef = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const revoked = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/leave-approval-substitutions', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor, asOf: data.asOf }
          : { kind: 'failed', failure: substituteFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: SUBSTITUTE_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    setListing(await fetchPage(null));
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

  useEffect(() => {
    if (step === 'confirm') confirmRef.current?.focus();
  }, [step]);

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    try {
      const next = await fetchPage(cursor);
      setListing((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  const name = (employee: { givenNames: string; familyName: string; employeeNumber: string }) =>
    t('leaveSubstitutes.employee', {
      given: employee.givenNames,
      family: employee.familyName,
      number: employee.employeeNumber,
    });

  // ------------------------------------------------------------------------------------------
  // Appointment
  // ------------------------------------------------------------------------------------------

  const set = <K extends keyof SubstituteForm>(key: K, value: SubstituteForm[K]) => {
    setForm((previous) => ({ ...previous, [key]: value }));
  };

  const review = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = substituteProblemsOf(form);
    setProblems(found);
    setFailure(null);
    setAnnouncement('');
    if (found.size > 0) {
      requestAnimationFrame(() => problemsBox.current?.focus());
      return;
    }
    setStep('confirm');
  };

  const appoint = async () => {
    if (sending.current) return;
    sending.current = true;
    const body = substituteBodyOf(form);
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/leave-approval-substitutions', {
        body,
        // The same appointment keeps its key across retries; any edit is a new key.
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
      });
      if (data) {
        createKey.reset();
        setAnnouncement(
          t('leaveSubstitutes.created', {
            manager: form.manager ? name(form.manager) : '',
            substitute: form.substitute ? name(form.substitute) : '',
            period: formatPeriod(t, language, body.effectiveFrom, body.effectiveTo),
          }),
        );
        setForm(emptySubstituteForm(language));
        setProblems(new Set());
        setStep('form');
        await reload();
        return;
      }
      const refused = substituteFailureOf(response, error);
      if (refused.kind === 'validation') {
        setProblems(new Set(refused.fields ?? []));
        setStep('form');
      }
      setFailure(refused);
    } catch {
      setFailure(SUBSTITUTE_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const fieldId = (field: SubstituteField) => `${ids}-${field}`;
  const fieldError = (field: SubstituteField) =>
    problems.has(field) ? (
      <p id={`${fieldId(field)}-error`} className="field__error">
        {t(`leaveSubstitutes.problems.${field}`)}
      </p>
    ) : null;
  const describedBy = (field: SubstituteField, help?: string) =>
    [help, problems.has(field) ? `${fieldId(field)}-error` : null].filter(Boolean).join(' ') ||
    undefined;
  const reasonLength = codePoints(normalizeReason(form.reason));

  // ------------------------------------------------------------------------------------------
  // Revocation
  // ------------------------------------------------------------------------------------------

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (revoking && !element.open) element.showModal();
    if (!revoking && element.open) element.close();
  }, [revoking]);

  const revokeStep = revoking?.step;
  const revokeFailure = revoking?.failure;
  const revokeProblemCount = revoking?.problems.size ?? 0;
  useEffect(() => {
    if (!revokeStep) return;
    requestAnimationFrame(() => {
      if (revokeFailure) revokeFailureRef.current?.focus();
      else if (revokeProblemCount > 0) revokeProblemsRef.current?.focus();
      else if (revokeStep === 'confirm') revokeConfirmRef.current?.focus();
      else reasonRef.current?.focus();
    });
  }, [revokeStep, revokeFailure, revokeProblemCount]);

  const openRevoke = (item: LeaveApprovalSubstitution, button: HTMLButtonElement) => {
    opener.current = button;
    revoked.current = false;
    setAnnouncement('');
    setRevoking({
      item,
      step: 'form',
      form: { reasonLocale: language, reason: '' },
      problems: new Set(),
      failure: null,
    });
  };

  const closeRevoke = () => {
    if (!busy) dialogRef.current?.close();
  };

  /** Closing returns focus to the opening button, or to the list heading once it changed. */
  const onRevokeClosed = () => {
    const current = revoking;
    setRevoking(null);
    const changed = revoked.current || current?.failure?.kind === 'gone';
    if (changed) void reload();
    requestAnimationFrame(() => {
      if (!changed && opener.current?.isConnected) opener.current.focus();
      else headingRef.current?.focus();
    });
  };

  const reviewRevoke = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!revoking) return;
    const found = revokeProblemsOf(revoking.form);
    setRevoking({
      ...revoking,
      problems: found,
      failure: null,
      step: found.size > 0 ? 'form' : 'confirm',
    });
  };

  const revoke = async () => {
    if (!revoking || sending.current) return;
    sending.current = true;
    const { item } = revoking;
    const body = revokeBodyOf(revoking.form);
    setBusy(true);
    setRevoking({ ...revoking, failure: null });
    try {
      const { data, error, response } = await core.POST(
        '/leave-approval-substitutions/{substitutionId}/revocation',
        {
          params: {
            path: { substitutionId: item.id },
            header: { 'Idempotency-Key': revokeKey.keyFor({ id: item.id, body }) },
          },
          body,
        },
      );
      if (data) {
        revokeKey.reset();
        revoked.current = true;
        setAnnouncement(
          t('leaveSubstitutes.revoke.revoked', {
            manager: name(item.manager),
            substitute: name(item.substitute),
          }),
        );
        dialogRef.current?.close();
        return;
      }
      const refused = substituteFailureOf(response, error);
      if (refused.kind === 'gone') setAnnouncement(t(refused.messageKey));
      setRevoking((previous) =>
        previous
          ? {
              ...previous,
              failure: refused,
              step: refused.kind === 'validation' ? 'form' : previous.step,
              problems:
                refused.kind === 'validation' && refused.fields
                  ? new Set(
                      refused.fields.filter(
                        (field): field is RevokeField =>
                          field === 'reason' || field === 'reasonLocale',
                      ),
                    )
                  : previous.problems,
            }
          : previous,
      );
    } catch {
      setRevoking((previous) =>
        previous ? { ...previous, failure: SUBSTITUTE_NETWORK_FAILURE } : previous,
      );
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const updateRevoke = (change: Partial<RevokeForm>) => {
    setRevoking((previous) =>
      previous ? { ...previous, form: { ...previous.form, ...change } } : previous,
    );
  };

  const revokeFieldId = (field: RevokeField) => `${ids}-revoke-${field}`;
  const revokeLength = revoking ? codePoints(normalizeReason(revoking.form.reason)) : 0;

  // ------------------------------------------------------------------------------------------
  // Page
  // ------------------------------------------------------------------------------------------

  const picker = (field: 'manager' | 'substitute') => (
    <EmployeeSearchPicker
      idPrefix={fieldId(field)}
      legend={t(`leaveSubstitutes.form.${field}`)}
      value={form[field]}
      onChange={(value: EmployeeSummary | null) => {
        set(field, value);
      }}
      invalid={problems.has(field)}
      errorId={`${fieldId(field)}-error`}
      error={problems.has(field) ? t(`leaveSubstitutes.problems.${field}`) : null}
      testId={`${field}-picker`}
    />
  );

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="leave-substitutes">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('leaveSubstitutes.title')}
        description={t('leaveSubstitutes.description')}
      />
      <div className="card" data-testid="substitute-rules">
        <p>
          <strong>{t('leaveSubstitutes.rules.primary')}</strong>
        </p>
        <p>{t('leaveSubstitutes.rules.one')}</p>
        <p className="muted">{t('leaveSubstitutes.rules.scope')}</p>
      </div>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-list`} className="card">
        <h2 id={`${ids}-list`} ref={headingRef} tabIndex={-1}>
          {t('leaveSubstitutes.list.title')}
        </h2>
        {listing.kind === 'ready' && (
          <p className="muted" data-testid="as-of">
            {t('leaveSubstitutes.list.asOf', { date: formatDate(language, listing.asOf) })}
          </p>
        )}
        {listing.kind === 'loading' && <p role="status">{t('leaveSubstitutes.list.loading')}</p>}
        {listing.kind === 'failed' && (
          <>
            <SubstituteAlert failure={listing.failure} testId="list-error" />
            {listing.failure.kind !== 'forbidden' && listing.failure.kind !== 'mfaRequired' && (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setListing({ kind: 'loading' });
                  void reload();
                }}
              >
                {t('leaveSubstitutes.list.retry')}
              </button>
            )}
          </>
        )}
        {listing.kind === 'ready' && listing.items.length === 0 && (
          <p data-testid="substitutes-empty">{t('leaveSubstitutes.list.empty')}</p>
        )}
        {listing.kind === 'ready' && listing.items.length > 0 && (
          <ScrollRegion label={t('leaveSubstitutes.list.label')}>
            <table data-testid="substitutes">
              <caption className="visually-hidden">{t('leaveSubstitutes.list.label')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('leaveSubstitutes.columns.manager')}</th>
                  <th scope="col">{t('leaveSubstitutes.columns.substitute')}</th>
                  <th scope="col">{t('leaveSubstitutes.columns.period')}</th>
                  <th scope="col">{t('leaveSubstitutes.columns.state')}</th>
                  <th scope="col">{t('leaveSubstitutes.columns.reason')}</th>
                  <th scope="col">{t('leaveSubstitutes.columns.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {listing.items.map((item) => (
                  <tr key={item.id} data-testid="substitute-row" data-state={item.state}>
                    <th scope="row">{name(item.manager)}</th>
                    <td>{name(item.substitute)}</td>
                    <td>{formatPeriod(t, language, item.effectiveFrom, item.effectiveTo)}</td>
                    <td>
                      <StatusBadge tone={substituteToneOf(item.state)} testId="substitute-state">
                        {t(`leaveSubstitutes.states.${item.state}`)}
                      </StatusBadge>
                    </td>
                    <td>
                      <p lang={item.reasonLocale}>{item.reason}</p>
                      <p className="muted">
                        {t('leaveSubstitutes.createdOn', {
                          date: new Intl.DateTimeFormat(language, { dateStyle: 'medium' }).format(
                            new Date(item.createdAt),
                          ),
                        })}
                      </p>
                      {item.revocation && (
                        <div data-testid="substitute-revocation">
                          <p className="muted">
                            {t('leaveSubstitutes.revokedOn', {
                              date: new Intl.DateTimeFormat(language, {
                                dateStyle: 'medium',
                              }).format(new Date(item.revocation.revokedAt)),
                            })}
                          </p>
                          <p>
                            <span className="visually-hidden">
                              {t('leaveSubstitutes.revocationReason')}:{' '}
                            </span>
                            <span lang={item.revocation.reasonLocale}>
                              {item.revocation.reason}
                            </span>
                          </p>
                        </div>
                      )}
                    </td>
                    <td>
                      {revocable(item.state) && (
                        <button
                          type="button"
                          className="button button--secondary"
                          disabled={busy || revoking !== null}
                          aria-label={t('leaveSubstitutes.actions.revokeFor', {
                            manager: name(item.manager),
                            substitute: name(item.substitute),
                          })}
                          onClick={(event) => {
                            openRevoke(item, event.currentTarget);
                          }}
                        >
                          {t('leaveSubstitutes.actions.revoke')}
                        </button>
                      )}
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
              {loadingMore
                ? t('leaveSubstitutes.list.loadingMore')
                : t('leaveSubstitutes.list.more')}
            </button>
          </p>
        )}
      </section>

      <section aria-labelledby={`${ids}-create`} className="card" data-testid="create-substitute">
        <h2 id={`${ids}-create`}>
          {step === 'form'
            ? t('leaveSubstitutes.form.title')
            : t('leaveSubstitutes.form.confirmTitle')}
        </h2>
        {problems.size > 0 && !failure && step === 'form' && (
          <div
            className="error-summary"
            role="alert"
            tabIndex={-1}
            ref={problemsBox}
            data-testid="create-problems"
          >
            <p>{t('employees.form.problems.title')}</p>
            <ul>
              {SUBSTITUTE_FIELDS.filter((field) => problems.has(field)).map((problem) => (
                <li key={problem}>
                  <a
                    href={`#${fieldId(problem)}${problem === 'manager' || problem === 'substitute' ? '-find' : ''}`}
                  >
                    {t(`leaveSubstitutes.problems.${problem}`)}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {failure && (
          <SubstituteAlert failure={failure} alertRef={failureBox} testId="create-error" />
        )}
        {step === 'form' && (
          <form noValidate onSubmit={review}>
            <p className="muted">{t('leaveSubstitutes.form.intro')}</p>
            {picker('manager')}
            {picker('substitute')}
            <p id={`${ids}-dates-help`} className="field__help">
              {t('leaveSubstitutes.form.datesHelp')}
            </p>
            {DATE_FIELDS.map((field) => (
              <div className="field" key={field}>
                <label htmlFor={fieldId(field)}>{t(`leaveSubstitutes.form.${field}`)}</label>
                {fieldError(field)}
                <input
                  id={fieldId(field)}
                  type="date"
                  value={form[field]}
                  min="1900-01-01"
                  max="2999-12-31"
                  aria-invalid={problems.has(field) ? true : undefined}
                  aria-describedby={describedBy(field, `${ids}-dates-help`)}
                  onChange={(event) => {
                    set(field, event.target.value);
                  }}
                />
              </div>
            ))}
            <div className="field">
              <label htmlFor={fieldId('reason')}>{t('leaveSubstitutes.form.reason')}</label>
              <p id={`${fieldId('reason')}-help`} className="field__help">
                {t('leaveSubstitutes.form.reasonHelp', { max: REASON_MAX })}
              </p>
              {fieldError('reason')}
              <textarea
                id={fieldId('reason')}
                rows={3}
                lang={form.reasonLocale}
                value={form.reason}
                autoComplete="off"
                aria-invalid={problems.has('reason') ? true : undefined}
                aria-describedby={describedBy('reason', `${fieldId('reason')}-help`)}
                onChange={(event) => {
                  set('reason', event.target.value);
                }}
              />
              <p className="field__help" data-testid="reason-count">
                {t('leaveSubstitutes.form.count', { count: reasonLength, max: REASON_MAX })}
              </p>
            </div>
            <div className="field">
              <label htmlFor={fieldId('reasonLocale')}>
                {t('leaveSubstitutes.form.reasonLocale')}
              </label>
              <p id={`${fieldId('reasonLocale')}-help`} className="field__help">
                {t('leaveSubstitutes.form.reasonLocaleHelp')}
              </p>
              {fieldError('reasonLocale')}
              <select
                id={fieldId('reasonLocale')}
                value={form.reasonLocale}
                aria-invalid={problems.has('reasonLocale') ? true : undefined}
                aria-describedby={describedBy('reasonLocale', `${fieldId('reasonLocale')}-help`)}
                onChange={(event) => {
                  set('reasonLocale', event.target.value === 'en' ? 'en' : 'fr');
                }}
              >
                <option value="fr" lang="fr">
                  {t('leaveSubstitutes.form.locales.fr')}
                </option>
                <option value="en" lang="en">
                  {t('leaveSubstitutes.form.locales.en')}
                </option>
              </select>
            </div>
            <button type="submit" className="button" disabled={busy}>
              {t('leaveSubstitutes.form.review')}
            </button>
          </form>
        )}
        {step === 'confirm' && form.manager && form.substitute && (
          <div data-testid="create-confirm">
            <p>
              {t('leaveSubstitutes.form.confirm', {
                manager: name(form.manager),
                substitute: name(form.substitute),
                period: formatPeriod(t, language, form.effectiveFrom, form.effectiveTo),
              })}
            </p>
            <figure className="decision-reason">
              <figcaption>{t('leaveSubstitutes.form.reason')}</figcaption>
              <blockquote lang={form.reasonLocale} data-testid="create-reason">
                {normalizeReason(form.reason)}
              </blockquote>
            </figure>
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                ref={confirmRef}
                disabled={busy}
                aria-busy={busy ? true : undefined}
                onClick={() => void appoint()}
              >
                {busy
                  ? t('leaveSubstitutes.form.sending')
                  : t('leaveSubstitutes.form.confirmButton')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                onClick={() => {
                  setFailure(null);
                  setStep('form');
                }}
              >
                {t('leaveSubstitutes.form.back')}
              </button>
            </div>
          </div>
        )}
      </section>

      <dialog
        ref={dialogRef}
        className="decision-dialog"
        aria-labelledby={`${ids}-revoke-title`}
        data-testid="revoke-dialog"
        onClose={onRevokeClosed}
        onCancel={(event) => {
          // Esc closes the dialog, except while the revocation is being sent.
          if (busy) event.preventDefault();
        }}
      >
        {revoking && (
          <div className="decision-dialog__panel">
            <h2 id={`${ids}-revoke-title`}>
              {revoking.step === 'form'
                ? t('leaveSubstitutes.revoke.title')
                : t('leaveSubstitutes.revoke.confirmTitle')}
            </h2>
            <dl className="decision-summary" data-testid="revoke-summary">
              <dt>{t('leaveSubstitutes.columns.manager')}</dt>
              <dd>{name(revoking.item.manager)}</dd>
              <dt>{t('leaveSubstitutes.columns.substitute')}</dt>
              <dd>{name(revoking.item.substitute)}</dd>
              <dt>{t('leaveSubstitutes.columns.period')}</dt>
              <dd>
                {formatPeriod(t, language, revoking.item.effectiveFrom, revoking.item.effectiveTo)}
              </dd>
              <dt>{t('leaveSubstitutes.columns.state')}</dt>
              <dd>{t(`leaveSubstitutes.states.${revoking.item.state}`)}</dd>
            </dl>
            <p>{t('leaveSubstitutes.revoke.intro')}</p>
            {revoking.failure && (
              <SubstituteAlert
                failure={revoking.failure}
                alertRef={revokeFailureRef}
                testId="revoke-error"
              />
            )}
            {revoking.step === 'form' && (
              <form noValidate onSubmit={reviewRevoke}>
                {revoking.problems.size > 0 && !revoking.failure && (
                  <div
                    className="error-summary"
                    role="alert"
                    tabIndex={-1}
                    ref={revokeProblemsRef}
                    data-testid="revoke-problems"
                  >
                    <p>{t('employees.form.problems.title')}</p>
                    <ul>
                      {[...revoking.problems].map((problem) => (
                        <li key={problem}>
                          <a href={`#${revokeFieldId(problem)}`}>
                            {t(`leaveSubstitutes.problems.${problem}`)}
                          </a>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                <div className="field">
                  <label htmlFor={revokeFieldId('reason')}>
                    {t('leaveSubstitutes.form.reason')}
                  </label>
                  <p id={`${revokeFieldId('reason')}-help`} className="field__help">
                    {t('leaveSubstitutes.form.reasonHelp', { max: REASON_MAX })}
                  </p>
                  {revoking.problems.has('reason') && (
                    <p id={`${revokeFieldId('reason')}-error`} className="field__error">
                      {t('leaveSubstitutes.problems.reason')}
                    </p>
                  )}
                  <textarea
                    id={revokeFieldId('reason')}
                    ref={reasonRef}
                    rows={3}
                    lang={revoking.form.reasonLocale}
                    value={revoking.form.reason}
                    autoComplete="off"
                    aria-invalid={revoking.problems.has('reason') ? true : undefined}
                    aria-describedby={[
                      `${revokeFieldId('reason')}-help`,
                      revoking.problems.has('reason') ? `${revokeFieldId('reason')}-error` : '',
                    ]
                      .filter(Boolean)
                      .join(' ')}
                    onChange={(event) => {
                      updateRevoke({ reason: event.target.value });
                    }}
                  />
                  <p className="field__help" data-testid="revoke-reason-count">
                    {t('leaveSubstitutes.form.count', { count: revokeLength, max: REASON_MAX })}
                  </p>
                </div>
                <div className="field">
                  <label htmlFor={revokeFieldId('reasonLocale')}>
                    {t('leaveSubstitutes.form.reasonLocale')}
                  </label>
                  <select
                    id={revokeFieldId('reasonLocale')}
                    value={revoking.form.reasonLocale}
                    onChange={(event) => {
                      updateRevoke({ reasonLocale: event.target.value === 'en' ? 'en' : 'fr' });
                    }}
                  >
                    <option value="fr" lang="fr">
                      {t('leaveSubstitutes.form.locales.fr')}
                    </option>
                    <option value="en" lang="en">
                      {t('leaveSubstitutes.form.locales.en')}
                    </option>
                  </select>
                </div>
                <div className="dialog-actions">
                  <button type="submit" className="button" disabled={busy}>
                    {t('leaveSubstitutes.form.review')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={closeRevoke}
                  >
                    {t('leaveSubstitutes.revoke.cancel')}
                  </button>
                </div>
              </form>
            )}
            {revoking.step === 'confirm' && (
              <div data-testid="revoke-confirm">
                <p>
                  {t('leaveSubstitutes.revoke.confirm', {
                    manager: name(revoking.item.manager),
                    substitute: name(revoking.item.substitute),
                  })}
                </p>
                <figure className="decision-reason">
                  <figcaption>{t('leaveSubstitutes.revocationReason')}</figcaption>
                  <blockquote lang={revoking.form.reasonLocale} data-testid="revoke-reason">
                    {normalizeReason(revoking.form.reason)}
                  </blockquote>
                </figure>
                <div className="dialog-actions">
                  <button
                    type="button"
                    className="button"
                    ref={revokeConfirmRef}
                    disabled={busy || revoking.failure?.kind === 'gone'}
                    aria-busy={busy ? true : undefined}
                    onClick={() => void revoke()}
                  >
                    {busy
                      ? t('leaveSubstitutes.revoke.sending')
                      : t('leaveSubstitutes.revoke.confirmButton')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={() => {
                      setRevoking({ ...revoking, step: 'form', failure: null });
                    }}
                  >
                    {t('leaveSubstitutes.revoke.back')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={closeRevoke}
                  >
                    {t('leaveSubstitutes.revoke.cancel')}
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </dialog>
    </section>
  );
}

/** The two date fields of the appointment form, in order. */
const DATE_FIELDS = SUBSTITUTE_FIELDS.filter(
  (field): field is 'effectiveFrom' | 'effectiveTo' =>
    field === 'effectiveFrom' || field === 'effectiveTo',
);

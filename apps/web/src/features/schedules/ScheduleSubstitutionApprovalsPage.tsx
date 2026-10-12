import type {
  WorkScheduleSubstitutionApproval,
  WorkScheduleSubstitutionPerson,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { normalizeReason } from '../leave/leaveApprovals';
import { ProblemList, ReasonFields, ShiftFacts, SubstitutionAlert } from './SubstitutionParts';
import {
  SETTLED_SUBSTITUTION_KINDS,
  SUBSTITUTION_NETWORK_FAILURE,
  candidateQueryOf,
  decisionBodyOf,
  decisionProblemsOf,
  personName,
  substitutionFailureOf,
  type SubstitutionDecisionForm,
  type SubstitutionFailure,
  type SubstitutionField,
  type SubstitutionOutcome,
} from './substitutions';

const DECISION_SHIFT_TEST_ID = 'substitution-decision-shift';

type Inbox =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: SubstitutionFailure }
  | { kind: 'ready'; items: WorkScheduleSubstitutionApproval[]; nextCursor: string | null };

type Search =
  | { kind: 'idle' }
  | { kind: 'short' }
  | { kind: 'searching' }
  | { kind: 'failed'; failure: SubstitutionFailure }
  | { kind: 'ready'; items: WorkScheduleSubstitutionPerson[]; nextCursor: string | null };

type Dialog = {
  item: WorkScheduleSubstitutionApproval;
  step: 'form' | 'confirm';
  form: SubstitutionDecisionForm;
  problems: Set<SubstitutionField>;
  failure: SubstitutionFailure | null;
};

function employeeName(item: WorkScheduleSubstitutionApproval): string {
  return personName(item.employee);
}

/**
 * MVP-051: « Remplacements d’horaire » / "Shift substitutions" under My space: the pending, still
 * future substitution requests of the caller's direct reports, as the server routes them (the
 * reporting line on the shift's date, evaluated on every call). Each shows the employee, the shift
 * in its site's local time, and the employee's reason. The manager rejects with a reason, or
 * searches eligible direct reports (the server returns only eligible people, with their number and
 * name), chooses one and approves with a reason; the confirmation shows both employees. One
 * decision is sent with a stable idempotency key. The search text is sent only to the candidate
 * search and never stored in the browser; names and reasons are plain text.
 */
export function ScheduleSubstitutionApprovalsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const decisionKey = useIdempotencyKey();
  const language = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [inbox, setInbox] = useState<Inbox>({ kind: 'loading' });
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState<Search>({ kind: 'idle' });
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  const reasonRef = useRef<HTMLTextAreaElement>(null);
  const queryRef = useRef<HTMLInputElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const problemsRef = useRef<HTMLDivElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const decided = useRef(false);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Inbox> => {
      try {
        const { data, error, response } = await core.GET(
          '/me/work-schedule-substitution-approvals',
          { params: { query: cursor ? { cursor } : {} }, cache: 'no-store' },
        );
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: substitutionFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: SUBSTITUTION_NETWORK_FAILURE };
      }
    },
    [core],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setInbox(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    try {
      const next = await fetchPage(cursor);
      setInbox((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  useEffect(() => {
    const element = dialogRef.current;
    if (!element) return;
    if (dialog && !element.open) element.showModal();
    if (!dialog && element.open) element.close();
  }, [dialog]);

  const step = dialog?.step;
  const failureShown = dialog?.failure;
  const problemCount = dialog?.problems.size ?? 0;
  const approving = dialog?.form.decision === 'APPROVED';
  useEffect(() => {
    if (!step) return;
    requestAnimationFrame(() => {
      if (failureShown) failureRef.current?.focus();
      else if (problemCount > 0) problemsRef.current?.focus();
      else if (step === 'confirm') confirmRef.current?.focus();
      else if (approving) queryRef.current?.focus();
      else reasonRef.current?.focus();
    });
  }, [step, failureShown, problemCount, approving]);

  const removeItem = (id: string) => {
    setInbox((previous) =>
      previous.kind === 'ready'
        ? { ...previous, items: previous.items.filter((item) => item.id !== id) }
        : previous,
    );
  };

  const open = (
    item: WorkScheduleSubstitutionApproval,
    decision: SubstitutionOutcome,
    button: HTMLButtonElement,
  ) => {
    opener.current = button;
    decided.current = false;
    setAnnouncement('');
    setQuery('');
    setSearch({ kind: 'idle' });
    setDialog({
      item,
      step: 'form',
      form: { decision, substitute: null, reasonLocale: language, reason: '' },
      problems: new Set(),
      failure: null,
    });
  };

  const onClosed = () => {
    const current = dialog;
    setDialog(null);
    setQuery('');
    setSearch({ kind: 'idle' });
    const gone =
      decided.current ||
      (current?.failure ? SETTLED_SUBSTITUTION_KINDS.has(current.failure.kind) : false);
    if (current && gone) removeItem(current.item.id);
    requestAnimationFrame(() => {
      if (!gone && opener.current?.isConnected) opener.current.focus();
      else headingRef.current?.focus();
    });
  };

  const close = () => {
    if (!busy) dialogRef.current?.close();
  };

  const update = (change: Partial<SubstitutionDecisionForm>) => {
    setDialog((previous) =>
      previous ? { ...previous, form: { ...previous.form, ...change } } : previous,
    );
  };

  const findCandidates = async (cursor: string | null) => {
    if (!dialog || search.kind === 'searching') return;
    const text = candidateQueryOf(query);
    if (!text) {
      setSearch({ kind: 'short' });
      return;
    }
    const previous = search.kind === 'ready' && cursor ? search.items : [];
    setSearch({ kind: 'searching' });
    try {
      const { data, error, response } = await core.GET(
        '/me/work-schedule-substitution-approvals/{requestId}/candidates',
        {
          params: {
            path: { requestId: dialog.item.id },
            query: cursor ? { query: text, cursor } : { query: text },
          },
          cache: 'no-store',
        },
      );
      setSearch(
        data
          ? { kind: 'ready', items: [...previous, ...data.items], nextCursor: data.nextCursor }
          : { kind: 'failed', failure: substitutionFailureOf(response, error) },
      );
    } catch {
      setSearch({ kind: 'failed', failure: SUBSTITUTION_NETWORK_FAILURE });
    }
  };

  const review = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!dialog) return;
    const problems = decisionProblemsOf(dialog.form);
    setDialog({
      ...dialog,
      problems,
      failure: null,
      step: problems.size > 0 ? 'form' : 'confirm',
    });
  };

  const send = async () => {
    if (!dialog || busy) return;
    const { item } = dialog;
    const body = decisionBodyOf(dialog.form);
    setBusy(true);
    setDialog({ ...dialog, failure: null });
    try {
      const { data, error, response } = await core.POST(
        '/me/work-schedule-substitution-approvals/{requestId}/decision',
        {
          params: {
            path: { requestId: item.id },
            header: { 'Idempotency-Key': decisionKey.keyFor({ requestId: item.id, body }) },
          },
          body,
        },
      );
      if (data) {
        decisionKey.reset();
        decided.current = true;
        setAnnouncement(
          t(`scheduleSubstitutions.approvals.decided.${data.state}`, {
            name: employeeName(item),
          }),
        );
        dialogRef.current?.close();
        return;
      }
      const refused = substitutionFailureOf(response, error);
      setDialog((previous) =>
        previous
          ? {
              ...previous,
              failure: refused,
              step:
                refused.kind === 'validation' ||
                refused.kind === 'notEligible' ||
                refused.kind === 'conflict'
                  ? 'form'
                  : previous.step,
              form:
                refused.kind === 'notEligible' || refused.kind === 'conflict'
                  ? { ...previous.form, substitute: null }
                  : previous.form,
              problems:
                refused.kind === 'validation' && refused.fields
                  ? new Set(refused.fields)
                  : previous.problems,
            }
          : previous,
      );
    } catch {
      setDialog((previous) =>
        previous ? { ...previous, failure: SUBSTITUTION_NETWORK_FAILURE } : previous,
      );
    } finally {
      setBusy(false);
    }
  };

  const shiftOf = (item: WorkScheduleSubstitutionApproval, testId?: string) => (
    <ShiftFacts
      start={item.shift.start}
      end={item.shift.end}
      labels={item.shift.labels}
      siteName={item.shift.site.name}
      siteTimezone={item.shift.siteTimezone}
      testId={testId}
    />
  );

  const settled = dialog?.failure ? SETTLED_SUBSTITUTION_KINDS.has(dialog.failure.kind) : false;
  const substituteId = `${ids}-substitute`;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="substitution-approvals">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('scheduleSubstitutions.approvals.title')}
        description={t('scheduleSubstitutions.approvals.description')}
      />
      <div className="card" data-testid="substitution-approvals-scope">
        <p>{t('scheduleSubstitutions.approvals.scope')}</p>
        <p className="muted">{t('scheduleSubstitutions.approvals.routing')}</p>
      </div>
      <p role="status" className="visually-hidden" data-testid="substitution-announcer">
        {announcement}
      </p>
      <section aria-labelledby={`${ids}-inbox`} className="card">
        <h2 id={`${ids}-inbox`} ref={headingRef} tabIndex={-1}>
          {t('scheduleSubstitutions.approvals.inbox')}
        </h2>
        {inbox.kind === 'loading' && (
          <p role="status">{t('scheduleSubstitutions.approvals.loading')}</p>
        )}
        {inbox.kind === 'failed' && (
          <SubstitutionAlert failure={inbox.failure} testId="substitution-inbox-error" />
        )}
        {inbox.kind === 'ready' && inbox.items.length === 0 && (
          <p data-testid="substitution-inbox-empty">{t('scheduleSubstitutions.approvals.empty')}</p>
        )}
        {inbox.kind === 'ready' && inbox.items.length > 0 && (
          <ul className="plain-list approval-list" data-testid="substitution-items">
            {inbox.items.map((item) => (
              <li
                key={item.id}
                className="hierarchy-item approval-item"
                data-testid="substitution-item"
              >
                <h3>{`${item.employee.givenNames} ${item.employee.familyName}`}</h3>
                <p className="muted">
                  {t('leaveApprovals.employeeNumber', { number: item.employee.employeeNumber })}
                </p>
                {shiftOf(item)}
                <figure className="decision-reason">
                  <figcaption>{t('scheduleSubstitutions.approvals.employeeReason')}</figcaption>
                  <blockquote lang={item.reasonLocale} data-testid="substitution-item-reason">
                    {item.reason}
                  </blockquote>
                </figure>
                <div className="approval-actions">
                  <button
                    type="button"
                    className="button"
                    disabled={busy || dialog !== null}
                    aria-label={t('scheduleSubstitutions.approvals.approveFor', {
                      name: employeeName(item),
                    })}
                    onClick={(event) => {
                      open(item, 'APPROVED', event.currentTarget);
                    }}
                  >
                    {t('scheduleSubstitutions.approvals.approve')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy || dialog !== null}
                    aria-label={t('scheduleSubstitutions.approvals.rejectFor', {
                      name: employeeName(item),
                    })}
                    onClick={(event) => {
                      open(item, 'REJECTED', event.currentTarget);
                    }}
                  >
                    {t('scheduleSubstitutions.approvals.reject')}
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        {inbox.kind === 'ready' && inbox.nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={loadingMore}
              aria-busy={loadingMore ? true : undefined}
              onClick={() => {
                if (inbox.nextCursor) void more(inbox.nextCursor);
              }}
            >
              {loadingMore
                ? t('scheduleSubstitutions.approvals.loadingMore')
                : t('scheduleSubstitutions.approvals.more')}
            </button>
          </p>
        )}
      </section>

      <dialog
        ref={dialogRef}
        className="decision-dialog"
        aria-labelledby={`${ids}-dialog-title`}
        data-testid="substitution-decision-dialog"
        onClose={onClosed}
        onCancel={(event) => {
          if (busy) event.preventDefault();
        }}
      >
        {dialog && (
          <div className="decision-dialog__panel">
            <h2 id={`${ids}-dialog-title`}>
              {t(
                dialog.step === 'form'
                  ? `scheduleSubstitutions.approvals.dialog.title.${dialog.form.decision}`
                  : `scheduleSubstitutions.approvals.dialog.confirmTitle.${dialog.form.decision}`,
              )}
            </h2>
            <dl className="decision-summary" data-testid="substitution-decision-people">
              <dt>{t('scheduleSubstitutions.approvals.original')}</dt>
              <dd data-testid="substitution-original">{employeeName(dialog.item)}</dd>
              {dialog.form.decision === 'APPROVED' && dialog.form.substitute && (
                <>
                  <dt>{t('scheduleSubstitutions.approvals.replacement')}</dt>
                  <dd data-testid="substitution-replacement">
                    {personName(dialog.form.substitute)}
                  </dd>
                </>
              )}
            </dl>
            {shiftOf(dialog.item, DECISION_SHIFT_TEST_ID)}
            {dialog.failure && (
              <SubstitutionAlert
                failure={dialog.failure}
                alertRef={failureRef}
                testId="substitution-decision-error"
              />
            )}
            {dialog.step === 'form' && (
              <form noValidate onSubmit={review}>
                {dialog.problems.size > 0 && !dialog.failure && (
                  <ProblemList
                    idPrefix={ids}
                    problems={dialog.problems}
                    listRef={problemsRef}
                    testId="substitution-decision-problems"
                  />
                )}
                {dialog.form.decision === 'APPROVED' && (
                  <fieldset className="field" data-testid="substitute-picker">
                    <legend id={substituteId}>
                      {t('scheduleSubstitutions.approvals.picker.legend')}
                    </legend>
                    <p className="field__help">
                      {t('scheduleSubstitutions.approvals.picker.help')}
                    </p>
                    {dialog.problems.has('substitute') && (
                      <p id={`${ids}-substitute-error`} className="field__error">
                        {t('scheduleSubstitutions.problems.substitute')}
                      </p>
                    )}
                    {dialog.form.substitute ? (
                      <>
                        <p data-testid="substitute-selected">
                          {t('scheduleSubstitutions.approvals.picker.selected', {
                            name: personName(dialog.form.substitute),
                          })}
                        </p>
                        <button
                          type="button"
                          className="button button--secondary"
                          onClick={() => {
                            update({ substitute: null });
                          }}
                        >
                          {t('scheduleSubstitutions.approvals.picker.change')}
                        </button>
                      </>
                    ) : (
                      <>
                        <label htmlFor={`${ids}-query`}>
                          {t('scheduleSubstitutions.approvals.picker.find')}
                        </label>
                        <div className="toolbar">
                          <input
                            id={`${ids}-query`}
                            ref={queryRef}
                            type="search"
                            autoComplete="off"
                            value={query}
                            aria-invalid={dialog.problems.has('substitute') ? true : undefined}
                            aria-describedby={
                              dialog.problems.has('substitute')
                                ? `${ids}-substitute-error`
                                : undefined
                            }
                            onChange={(event) => {
                              setQuery(event.target.value);
                            }}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter') {
                                event.preventDefault();
                                void findCandidates(null);
                              }
                            }}
                          />
                          <button
                            type="button"
                            className="button button--secondary"
                            disabled={search.kind === 'searching'}
                            aria-busy={search.kind === 'searching' ? true : undefined}
                            onClick={() => void findCandidates(null)}
                          >
                            {search.kind === 'searching'
                              ? t('scheduleSubstitutions.approvals.picker.searching')
                              : t('scheduleSubstitutions.approvals.picker.search')}
                          </button>
                        </div>
                        {search.kind === 'short' && (
                          <p className="field__error">
                            {t('scheduleSubstitutions.approvals.picker.short')}
                          </p>
                        )}
                        {search.kind === 'failed' && (
                          <SubstitutionAlert
                            failure={search.failure}
                            testId="substitute-search-error"
                          />
                        )}
                        {search.kind === 'ready' && search.items.length === 0 && (
                          <p className="muted" role="status" data-testid="substitute-none">
                            {t('scheduleSubstitutions.approvals.picker.none')}
                          </p>
                        )}
                        {search.kind === 'ready' && search.items.length > 0 && (
                          <ul
                            className="plain-list"
                            aria-label={t('scheduleSubstitutions.approvals.picker.results')}
                            data-testid="substitute-results"
                          >
                            {search.items.map((person) => (
                              <li key={person.id}>
                                <button
                                  type="button"
                                  className="button button--secondary"
                                  data-testid="substitute-option"
                                  onClick={() => {
                                    update({ substitute: person });
                                    setSearch({ kind: 'idle' });
                                    setQuery('');
                                  }}
                                >
                                  {personName(person)}
                                </button>
                              </li>
                            ))}
                          </ul>
                        )}
                        {search.kind === 'ready' && search.nextCursor && (
                          <button
                            type="button"
                            className="button button--secondary"
                            onClick={() => void findCandidates(search.nextCursor)}
                          >
                            {t('scheduleSubstitutions.approvals.picker.more')}
                          </button>
                        )}
                      </>
                    )}
                  </fieldset>
                )}
                <ReasonFields
                  idPrefix={ids}
                  form={dialog.form}
                  problems={dialog.problems}
                  reasonRef={reasonRef}
                  onChange={update}
                />
                <div className="dialog-actions">
                  <button type="submit" className="button" disabled={busy || settled}>
                    {t('scheduleSubstitutions.form.review')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={close}
                  >
                    {t('scheduleSubstitutions.form.cancel')}
                  </button>
                </div>
              </form>
            )}
            {dialog.step === 'confirm' && (
              <div data-testid="substitution-decision-confirm">
                <p>
                  {dialog.form.decision === 'APPROVED' && dialog.form.substitute
                    ? t('scheduleSubstitutions.approvals.confirm.APPROVED', {
                        original: employeeName(dialog.item),
                        replacement: personName(dialog.form.substitute),
                      })
                    : t('scheduleSubstitutions.approvals.confirm.REJECTED', {
                        name: employeeName(dialog.item),
                      })}
                </p>
                <figure className="decision-reason">
                  <figcaption>{t('scheduleSubstitutions.form.reason')}</figcaption>
                  <blockquote
                    lang={dialog.form.reasonLocale}
                    data-testid="substitution-decision-reason"
                  >
                    {normalizeReason(dialog.form.reason)}
                  </blockquote>
                </figure>
                <div className="dialog-actions">
                  <button
                    type="button"
                    className="button"
                    ref={confirmRef}
                    disabled={busy || settled}
                    aria-busy={busy ? true : undefined}
                    onClick={() => void send()}
                  >
                    {busy
                      ? t('scheduleSubstitutions.form.sending')
                      : t(`scheduleSubstitutions.approvals.confirmButton.${dialog.form.decision}`)}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy || settled}
                    onClick={() => {
                      setDialog({ ...dialog, step: 'form', failure: null });
                    }}
                  >
                    {t('scheduleSubstitutions.form.back')}
                  </button>
                  <button
                    type="button"
                    className="button button--secondary"
                    disabled={busy}
                    onClick={close}
                  >
                    {t('scheduleSubstitutions.form.cancel')}
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

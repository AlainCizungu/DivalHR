import type {
  EmployeeSummary,
  WorkScheduleDetail,
  WorkSchedulePreview,
  WorkScheduleSummary,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { EmployeeSearchPicker } from '../leave/EmployeeSearchPicker';
import { formatDate } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import {
  FORM_FIELDS,
  LABEL_FIELDS,
  LABEL_MAX,
  MAX_DAYS,
  MAX_SLOTS,
  PERIOD_FIELDS,
  SCHEDULE_NETWORK_FAILURE,
  SLOT_FIELDS,
  TIME_FIELDS,
  duplicateSlot,
  emptyScheduleForm,
  emptySlot,
  formatTime,
  hasProblems,
  issueKey,
  overnight,
  scheduleBodyOf,
  scheduleFailureOf,
  scheduleProblemsOf,
  splitLocal,
  type FormProblems,
  type ScheduleFailure,
  type ScheduleForm,
  type SlotDraft,
  type SlotField,
} from './workSchedules';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ScheduleFailure }
  | { kind: 'ready'; items: WorkScheduleSummary[]; nextCursor: string | null; asOf: string };

type Viewing =
  | { kind: 'loading'; id: string }
  | { kind: 'failed'; id: string; failure: ScheduleFailure }
  | { kind: 'ready'; detail: WorkScheduleDetail };

const NO_PROBLEMS: FormProblems = { form: new Set(), slots: new Map() };

/** A refused request: the stable message, its slot and reason, retry delay and reference. */
function ScheduleAlert({
  failure,
  alertRef,
  testId,
}: {
  failure: ScheduleFailure;
  alertRef?: React.Ref<HTMLDivElement>;
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
      {failure.position !== undefined && (
        <p>{t('workSchedules.review.slotNumber', { position: failure.position })}</p>
      )}
      {failure.reasonKey && <p>{t(failure.reasonKey)}</p>}
      {failure.retryAfter !== undefined && (
        <p>{t('employees.retryAfter', { count: failure.retryAfter })}</p>
      )}
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** The slot's two labels, the current language first; each marked with its own language. */
function Labels({ en, fr, language }: { en: string; fr: string; language: 'en' | 'fr' }) {
  const [first, second] =
    language === 'fr'
      ? [
          { lang: 'fr', text: fr },
          { lang: 'en', text: en },
        ]
      : [
          { lang: 'en', text: en },
          { lang: 'fr', text: fr },
        ];
  return (
    <>
      <span lang={first.lang}>{first.text}</span>
      <br />
      <span className="muted" lang={second.lang}>
        {second.text}
      </span>
    </>
  );
}

/**
 * MVP-050A: the tenant administrators' site work schedules (« Horaires » under Operations): the
 * published schedules with their assigned and uncovered totals, one schedule's slots with an
 * uncovered filter, and a new schedule built in the browser (site, period, slots with English and
 * French labels and an optional employee), previewed by the server, reviewed and explicitly
 * confirmed. Nothing is kept in browser storage or put in a URL; there is no saved draft.
 */
export function WorkSchedulesPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const publishKey = useIdempotencyKey();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [viewing, setViewing] = useState<Viewing | null>(null);
  const [uncoveredOnly, setUncoveredOnly] = useState(false);
  const [form, setForm] = useState<ScheduleForm>(emptyScheduleForm);
  const [problems, setProblems] = useState<FormProblems>(NO_PROBLEMS);
  const [step, setStep] = useState<'edit' | 'review'>('edit');
  const [preview, setPreview] = useState<WorkSchedulePreview | null>(null);
  const [failure, setFailure] = useState<ScheduleFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const [announcement, setAnnouncement] = useState('');
  const sending = useRef(false);
  const paging = useRef(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const failureBox = useRef<HTMLDivElement>(null);
  const problemsBox = useRef<HTMLDivElement>(null);
  const reviewHeading = useRef<HTMLHeadingElement>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const addButton = useRef<HTMLButtonElement>(null);

  const legalEntities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const sites = useOptions(form.legalEntityId || null, () =>
    loadAll((cursor) =>
      core.GET('/sites', {
        params: { query: { legalEntityId: form.legalEntityId, cursor, limit: 200 } },
      }),
    ),
  );

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/work-schedules', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor, asOf: data.asOf }
          : { kind: 'failed', failure: scheduleFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: SCHEDULE_NETWORK_FAILURE };
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
    if (step === 'review') reviewHeading.current?.focus();
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

  const view = async (id: string) => {
    setViewing({ kind: 'loading', id });
    setUncoveredOnly(false);
    try {
      const { data, error, response } = await core.GET('/work-schedules/{scheduleId}', {
        params: { path: { scheduleId: id } },
        cache: 'no-store',
      });
      setViewing(
        data
          ? { kind: 'ready', detail: data }
          : { kind: 'failed', id, failure: scheduleFailureOf(response, error) },
      );
    } catch {
      setViewing({ kind: 'failed', id, failure: SCHEDULE_NETWORK_FAILURE });
    }
    requestAnimationFrame(() => detailHeading.current?.focus());
  };

  const name = (employee: { givenNames: string; familyName: string; employeeNumber: string }) =>
    t('leaveSubstitutes.employee', {
      given: employee.givenNames,
      family: employee.familyName,
      number: employee.employeeNumber,
    });

  const period = (first: string, last: string) =>
    first === last
      ? formatDate(language, first)
      : t('employees.period.closed', {
          from: formatDate(language, first),
          to: formatDate(language, last),
        });

  const times = (start: string, end: string) => {
    const from = splitLocal(start);
    const to = splitLocal(end);
    return t(to.date > from.date ? 'workSchedules.timesOvernight' : 'workSchedules.times', {
      date: formatDate(language, from.date),
      start: formatTime(language, from.time),
      end: formatTime(language, to.time),
    });
  };

  // ------------------------------------------------------------------------------------------
  // Editing
  // ------------------------------------------------------------------------------------------

  const edit = (change: Partial<ScheduleForm>) => {
    setForm((previous) => ({ ...previous, ...change }));
    setPreview(null);
  };

  const editSlot = (key: string, change: Partial<SlotDraft>) => {
    setForm((previous) => ({
      ...previous,
      slots: previous.slots.map((slot) => (slot.key === key ? { ...slot, ...change } : slot)),
    }));
    setPreview(null);
  };

  const addSlot = () => {
    // The last slot's date, or none.
    const previous = form.slots.reduce((_, slot) => slot.date, '');
    const slot = emptySlot(previous || form.firstDate);
    edit({ slots: [...form.slots, slot] });
    requestAnimationFrame(() => document.getElementById(`${ids}-${slot.key}-date`)?.focus());
  };

  const duplicate = (slot: SlotDraft) => {
    const copy = duplicateSlot(slot);
    const index = form.slots.findIndex((item) => item.key === slot.key);
    edit({ slots: [...form.slots.slice(0, index + 1), copy, ...form.slots.slice(index + 1)] });
    setAnnouncement(t('workSchedules.slot.duplicated', { position: index + 2 }));
    requestAnimationFrame(() => document.getElementById(`${ids}-${copy.key}-date`)?.focus());
  };

  const remove = (slot: SlotDraft) => {
    const index = form.slots.findIndex((item) => item.key === slot.key);
    const remaining = form.slots.filter((item) => item.key !== slot.key);
    edit({ slots: remaining });
    setAnnouncement(t('workSchedules.slot.removed', { position: index + 1 }));
    // Focus the next slot's first field, or the add button when none is left after it.
    const next = remaining[index] ?? remaining[index - 1];
    requestAnimationFrame(() => {
      const target = next ? document.getElementById(`${ids}-${next.key}-date`) : null;
      if (target) target.focus();
      else addButton.current?.focus();
    });
  };

  const slotField = (slot: SlotDraft, field: SlotField) => `${ids}-${slot.key}-${field}`;
  const slotError = (slot: SlotDraft, field: SlotField) =>
    problems.slots.get(slot.key)?.has(field) ?? false;

  // ------------------------------------------------------------------------------------------
  // Preview, review and publication
  // ------------------------------------------------------------------------------------------

  const requestPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (sending.current) return;
    const found = scheduleProblemsOf(form);
    setProblems(found);
    setFailure(null);
    setAnnouncement('');
    if (hasProblems(found)) {
      requestAnimationFrame(() => problemsBox.current?.focus());
      return;
    }
    sending.current = true;
    setBusy(true);
    try {
      const { data, error, response } = await core.POST('/work-schedules/preview', {
        body: scheduleBodyOf(form),
        cache: 'no-store',
      });
      if (data) {
        setPreview(data);
        setStep('review');
        return;
      }
      setFailure(scheduleFailureOf(response, error));
    } catch {
      setFailure(SCHEDULE_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const publish = async () => {
    if (!preview || sending.current) return;
    sending.current = true;
    const body = {
      ...scheduleBodyOf(form),
      previewDigest: preview.previewDigest,
      confirm: true as const,
    };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/work-schedules', {
        body,
        // The same publication keeps its key across retries; any edit is a new key.
        params: { header: { 'Idempotency-Key': publishKey.keyFor(body) } },
      });
      if (data) {
        publishKey.reset();
        setAnnouncement(
          t('workSchedules.published', {
            period: period(preview.firstDate, preview.lastDate),
            slots: data.totals.slots,
            uncovered: data.totals.uncovered,
          }),
        );
        setForm(emptyScheduleForm());
        setProblems(NO_PROBLEMS);
        setPreview(null);
        setStep('edit');
        await reload();
        void view(data.scheduleId);
        return;
      }
      const refused = scheduleFailureOf(response, error);
      if (refused.kind === 'stale') setPreview(null);
      setFailure(refused);
    } catch {
      setFailure(SCHEDULE_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const backToEdit = () => {
    setFailure(null);
    setStep('edit');
    requestAnimationFrame(() =>
      document.getElementById(`${ids}-site`)?.focus({ preventScroll: false }),
    );
  };

  // ------------------------------------------------------------------------------------------
  // Page
  // ------------------------------------------------------------------------------------------

  const shown =
    viewing?.kind === 'ready'
      ? viewing.detail.slots.filter((slot) => !uncoveredOnly || slot.employee === null)
      : [];
  const formProblems = FORM_FIELDS.filter((field) => problems.form.has(field));
  const slotProblems = form.slots.flatMap((slot, index) =>
    SLOT_FIELDS.filter((field) => slotError(slot, field)).map((field) => ({
      slot,
      index,
      field,
    })),
  );

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="work-schedules">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('workSchedules.title')}
        description={t('workSchedules.description')}
      />
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      <section aria-labelledby={`${ids}-list`} className="card">
        <h2 id={`${ids}-list`}>{t('workSchedules.list.title')}</h2>
        {listing.kind === 'loading' && <p role="status">{t('workSchedules.list.loading')}</p>}
        {listing.kind === 'failed' && (
          <>
            <ScheduleAlert failure={listing.failure} testId="list-error" />
            {listing.failure.kind !== 'forbidden' && listing.failure.kind !== 'mfaRequired' && (
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setListing({ kind: 'loading' });
                  void reload();
                }}
              >
                {t('workSchedules.list.retry')}
              </button>
            )}
          </>
        )}
        {listing.kind === 'ready' && listing.items.length === 0 && (
          <p data-testid="schedules-empty">{t('workSchedules.list.empty')}</p>
        )}
        {listing.kind === 'ready' && listing.items.length > 0 && (
          <ScrollRegion label={t('workSchedules.list.label')}>
            <table data-testid="schedules">
              <caption className="visually-hidden">{t('workSchedules.list.label')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('workSchedules.columns.site')}</th>
                  <th scope="col">{t('workSchedules.columns.period')}</th>
                  <th scope="col">{t('workSchedules.columns.slots')}</th>
                  <th scope="col">{t('workSchedules.columns.assigned')}</th>
                  <th scope="col">{t('workSchedules.columns.uncovered')}</th>
                  <th scope="col">{t('workSchedules.columns.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {listing.items.map((item) => (
                  <tr key={item.id} data-testid="schedule-row">
                    <th scope="row">
                      {item.site.name
                        ? t('workSchedules.siteName', {
                            name: item.site.name,
                            code: item.site.code,
                          })
                        : t('workSchedules.siteUnknown')}
                    </th>
                    <td>{period(item.firstDate, item.lastDate)}</td>
                    <td>{item.totals.slots}</td>
                    <td>{item.totals.assigned}</td>
                    <td>
                      {item.totals.uncovered > 0 ? (
                        <StatusBadge tone="warning" testId="schedule-uncovered">
                          {t('workSchedules.uncoveredCount', { count: item.totals.uncovered })}
                        </StatusBadge>
                      ) : (
                        <StatusBadge tone="success" testId="schedule-uncovered">
                          {t('workSchedules.fullyCovered')}
                        </StatusBadge>
                      )}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        aria-label={t('workSchedules.actions.viewFor', {
                          period: period(item.firstDate, item.lastDate),
                          site: item.site.name ?? item.site.code ?? '',
                        })}
                        onClick={() => void view(item.id)}
                      >
                        {t('workSchedules.actions.view')}
                      </button>
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
              {loadingMore ? t('workSchedules.list.loadingMore') : t('workSchedules.list.more')}
            </button>
          </p>
        )}
      </section>

      {viewing && (
        <section aria-labelledby={`${ids}-detail`} className="card" data-testid="schedule-detail">
          <h2 id={`${ids}-detail`} ref={detailHeading} tabIndex={-1}>
            {viewing.kind === 'ready'
              ? t('workSchedules.detail.title', {
                  site: viewing.detail.site.name ?? viewing.detail.site.code ?? '',
                  period: period(viewing.detail.firstDate, viewing.detail.lastDate),
                })
              : t('workSchedules.detail.loadingTitle')}
          </h2>
          {viewing.kind === 'loading' && <p role="status">{t('workSchedules.detail.loading')}</p>}
          {viewing.kind === 'failed' && (
            <ScheduleAlert failure={viewing.failure} testId="detail-error" />
          )}
          {viewing.kind === 'ready' && (
            <>
              <p data-testid="detail-totals">
                {t('workSchedules.totals', {
                  slots: viewing.detail.totals.slots,
                  assigned: viewing.detail.totals.assigned,
                  uncovered: viewing.detail.totals.uncovered,
                })}
              </p>
              <p className="muted" data-testid="detail-timezone">
                {t('workSchedules.timezone', { zone: viewing.detail.siteTimezone })}
              </p>
              <fieldset className="field" data-testid="coverage-filter">
                <legend>{t('workSchedules.detail.filter')}</legend>
                <label>
                  <input
                    type="radio"
                    name={`${ids}-coverage`}
                    checked={!uncoveredOnly}
                    onChange={() => {
                      setUncoveredOnly(false);
                    }}
                  />{' '}
                  {t('workSchedules.detail.all')}
                </label>
                <label>
                  <input
                    type="radio"
                    name={`${ids}-coverage`}
                    checked={uncoveredOnly}
                    onChange={() => {
                      setUncoveredOnly(true);
                    }}
                  />{' '}
                  {t('workSchedules.detail.uncoveredOnly')}
                </label>
              </fieldset>
              <p role="status" className="muted" data-testid="detail-count">
                {t('workSchedules.detail.count', { count: shown.length })}
              </p>
              {shown.length > 0 && (
                <ScrollRegion label={t('workSchedules.detail.label')}>
                  <table data-testid="detail-slots">
                    <caption className="visually-hidden">{t('workSchedules.detail.label')}</caption>
                    <thead>
                      <tr>
                        <th scope="col">{t('workSchedules.columns.when')}</th>
                        <th scope="col">{t('workSchedules.columns.work')}</th>
                        <th scope="col">{t('workSchedules.columns.employee')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {shown.map((slot) => (
                        <tr
                          key={slot.id}
                          data-testid="detail-slot"
                          data-covered={slot.employee ? 'true' : 'false'}
                        >
                          <th scope="row">{times(slot.start, slot.end)}</th>
                          <td>
                            <Labels en={slot.labels.en} fr={slot.labels.fr} language={language} />
                          </td>
                          <td>
                            {slot.employee ? (
                              name(slot.employee)
                            ) : (
                              <StatusBadge tone="warning" testId="slot-uncovered">
                                {t('workSchedules.uncovered')}
                              </StatusBadge>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </ScrollRegion>
              )}
            </>
          )}
        </section>
      )}

      <section aria-labelledby={`${ids}-create`} className="card" data-testid="create-schedule">
        <h2 id={`${ids}-create`}>
          {step === 'edit' ? t('workSchedules.form.title') : t('workSchedules.review.title')}
        </h2>
        {failure && <ScheduleAlert failure={failure} alertRef={failureBox} testId="create-error" />}
        {step === 'edit' && (
          <form noValidate onSubmit={(event) => void requestPreview(event)}>
            <p className="muted">
              {t('workSchedules.form.intro', { days: MAX_DAYS, slots: MAX_SLOTS })}
            </p>
            {(formProblems.length > 0 || slotProblems.length > 0) && !failure && (
              <div
                className="error-summary"
                role="alert"
                tabIndex={-1}
                ref={problemsBox}
                data-testid="create-problems"
              >
                <p>{t('employees.form.problems.title')}</p>
                <ul>
                  {formProblems.map((field) => (
                    <li key={field}>
                      <a href={`#${ids}-${field}`}>{t(`workSchedules.problems.${field}`)}</a>
                    </li>
                  ))}
                  {slotProblems.map(({ slot, index, field }) => (
                    <li key={`${slot.key}-${field}`}>
                      <a href={`#${slotField(slot, field)}`}>
                        {t('workSchedules.problems.slot', {
                          position: index + 1,
                          problem: t(`workSchedules.problems.${field}`),
                        })}
                      </a>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <fieldset className="field" data-testid="site-choice">
              <legend>{t('workSchedules.form.site')}</legend>
              {(legalEntities.status === 'failed' || sites.status === 'failed') && (
                <p className="field__error" role="alert">
                  {t('workSchedules.form.sitesUnavailable')}
                </p>
              )}
              <label htmlFor={`${ids}-legal-entity`}>{t('workSchedules.form.legalEntity')}</label>
              <select
                id={`${ids}-legal-entity`}
                value={form.legalEntityId}
                onChange={(event) => {
                  edit({ legalEntityId: event.target.value, siteId: '' });
                }}
              >
                <option value="">{t('workSchedules.form.chooseLegalEntity')}</option>
                {legalEntities.items.map((option) => (
                  <option key={option.id} value={option.id}>
                    {t('workSchedules.siteName', { name: option.name, code: option.code })}
                  </option>
                ))}
              </select>
              <label htmlFor={`${ids}-site`}>{t('workSchedules.form.siteLabel')}</label>
              {problems.form.has('site') && (
                <p id={`${ids}-site-error`} className="field__error">
                  {t('workSchedules.problems.site')}
                </p>
              )}
              <select
                id={`${ids}-site`}
                value={form.siteId}
                disabled={!form.legalEntityId}
                aria-invalid={problems.form.has('site') ? true : undefined}
                aria-describedby={problems.form.has('site') ? `${ids}-site-error` : undefined}
                onChange={(event) => {
                  edit({ siteId: event.target.value });
                }}
              >
                <option value="">{t('workSchedules.form.chooseSite')}</option>
                {sites.items.map((option) => (
                  <option key={option.id} value={option.id}>
                    {t('workSchedules.siteName', { name: option.name, code: option.code })}
                  </option>
                ))}
              </select>
            </fieldset>
            <p id={`${ids}-dates-help`} className="field__help">
              {t('workSchedules.form.datesHelp', { days: MAX_DAYS })}
            </p>
            {PERIOD_FIELDS.map((field) => (
              <div className="field" key={field}>
                <label htmlFor={`${ids}-${field}`}>{t(`workSchedules.form.${field}`)}</label>
                {problems.form.has(field) && (
                  <p id={`${ids}-${field}-error`} className="field__error">
                    {t(`workSchedules.problems.${field}`)}
                  </p>
                )}
                <input
                  id={`${ids}-${field}`}
                  type="date"
                  value={form[field]}
                  aria-invalid={problems.form.has(field) ? true : undefined}
                  aria-describedby={[
                    `${ids}-dates-help`,
                    problems.form.has(field) ? `${ids}-${field}-error` : '',
                  ]
                    .filter(Boolean)
                    .join(' ')}
                  onChange={(event) => {
                    const value = event.target.value;
                    setForm((previous) => ({
                      ...previous,
                      [field]: value,
                      // A new first date fills the slots that have no date yet.
                      slots:
                        field === 'firstDate'
                          ? previous.slots.map((slot) =>
                              slot.date ? slot : { ...slot, date: value },
                            )
                          : previous.slots,
                    }));
                    setPreview(null);
                  }}
                />
              </div>
            ))}
            <h3 id={`${ids}-slots`}>
              {t('workSchedules.form.slots', { count: form.slots.length })}
            </h3>
            {problems.form.has('slots') && (
              <p className="field__error">{t('workSchedules.problems.slots')}</p>
            )}
            <p className="field__help">{t('workSchedules.form.slotsHelp')}</p>
            <ol className="slot-list" data-testid="slots" aria-labelledby={`${ids}-slots`}>
              {form.slots.map((slot, index) => (
                <li key={slot.key} data-testid="slot">
                  <fieldset className="card slot-card">
                    <legend>{t('workSchedules.slot.legend', { position: index + 1 })}</legend>
                    <div className="slot-card__times">
                      {TIME_FIELDS.map((field) => (
                        <div className="field" key={field}>
                          <label htmlFor={slotField(slot, field)}>
                            {t(`workSchedules.slot.${field}`)}
                          </label>
                          {slotError(slot, field) && (
                            <p id={`${slotField(slot, field)}-error`} className="field__error">
                              {t(`workSchedules.problems.${field}`)}
                            </p>
                          )}
                          <input
                            id={slotField(slot, field)}
                            type={field === 'date' ? 'date' : 'time'}
                            step={field === 'date' ? undefined : 60}
                            min={field === 'date' ? form.firstDate || undefined : undefined}
                            max={field === 'date' ? form.lastDate || undefined : undefined}
                            value={slot[field]}
                            aria-invalid={slotError(slot, field) ? true : undefined}
                            aria-describedby={
                              slotError(slot, field) ? `${slotField(slot, field)}-error` : undefined
                            }
                            onChange={(event) => {
                              editSlot(slot.key, { [field]: event.target.value });
                            }}
                          />
                        </div>
                      ))}
                    </div>
                    {overnight(slot) && (
                      <p className="field__help" data-testid="slot-overnight">
                        {t('workSchedules.slot.overnight')}
                      </p>
                    )}
                    {LABEL_FIELDS.map((field) => (
                      <div className="field" key={field}>
                        <label htmlFor={slotField(slot, field)}>
                          {t(`workSchedules.slot.${field}`)}
                        </label>
                        <p id={`${slotField(slot, field)}-help`} className="field__help">
                          {t('workSchedules.slot.labelHelp', { max: LABEL_MAX })}
                        </p>
                        {slotError(slot, field) && (
                          <p id={`${slotField(slot, field)}-error`} className="field__error">
                            {t(`workSchedules.problems.${field}`)}
                          </p>
                        )}
                        <input
                          id={slotField(slot, field)}
                          type="text"
                          lang={field === 'labelEn' ? 'en' : 'fr'}
                          value={slot[field]}
                          maxLength={LABEL_MAX + 20}
                          autoComplete="off"
                          aria-invalid={slotError(slot, field) ? true : undefined}
                          aria-describedby={[
                            `${slotField(slot, field)}-help`,
                            slotError(slot, field) ? `${slotField(slot, field)}-error` : '',
                          ]
                            .filter(Boolean)
                            .join(' ')}
                          onChange={(event) => {
                            editSlot(slot.key, { [field]: event.target.value });
                          }}
                        />
                      </div>
                    ))}
                    <EmployeeSearchPicker
                      idPrefix={`${ids}-${slot.key}-employee`}
                      legend={t('workSchedules.slot.employee')}
                      value={slot.employee}
                      onChange={(value: EmployeeSummary | null) => {
                        editSlot(slot.key, { employee: value });
                      }}
                      invalid={false}
                      errorId={`${ids}-${slot.key}-employee-error`}
                      error={null}
                      testId="slot-employee"
                    />
                    {!slot.employee && (
                      <p className="field__help">{t('workSchedules.slot.uncoveredHelp')}</p>
                    )}
                    <div className="dialog-actions">
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={form.slots.length >= MAX_SLOTS}
                        aria-label={t('workSchedules.slot.duplicateFor', { position: index + 1 })}
                        onClick={() => {
                          duplicate(slot);
                        }}
                      >
                        {t('workSchedules.slot.duplicate')}
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={form.slots.length <= 1}
                        aria-label={t('workSchedules.slot.removeFor', { position: index + 1 })}
                        onClick={() => {
                          remove(slot);
                        }}
                      >
                        {t('workSchedules.slot.remove')}
                      </button>
                    </div>
                  </fieldset>
                </li>
              ))}
            </ol>
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--secondary"
                ref={addButton}
                disabled={form.slots.length >= MAX_SLOTS}
                onClick={addSlot}
              >
                {t('workSchedules.form.add')}
              </button>
              <button
                type="submit"
                className="button"
                disabled={busy}
                aria-busy={busy ? true : undefined}
              >
                {busy ? t('workSchedules.form.previewing') : t('workSchedules.form.preview')}
              </button>
            </div>
          </form>
        )}
        {step === 'review' && preview && (
          <div data-testid="schedule-review">
            <h3 ref={reviewHeading} tabIndex={-1}>
              {t('workSchedules.review.summary', {
                site: preview.site.name ?? preview.site.code ?? '',
                period: period(preview.firstDate, preview.lastDate),
              })}
            </h3>
            <p data-testid="review-totals">
              {t('workSchedules.totals', {
                slots: preview.totals.slots,
                assigned: preview.totals.assigned,
                uncovered: preview.totals.uncovered,
              })}
            </p>
            <p className="muted" data-testid="review-timezone">
              {t('workSchedules.timezone', { zone: preview.siteTimezone })}
            </p>
            {preview.warnings.includes('UNCOVERED_SLOTS') && (
              <p data-testid="review-warning">
                <StatusBadge tone="warning">{t('workSchedules.warning')}</StatusBadge>{' '}
                {t('workSchedules.review.uncoveredWarning', { count: preview.totals.uncovered })}
              </p>
            )}
            {!preview.publishable && (
              <div className="error-summary" role="alert" data-testid="review-blocked">
                <p>{t('workSchedules.review.blocked')}</p>
              </div>
            )}
            <ScrollRegion label={t('workSchedules.review.label')}>
              <table data-testid="review-slots">
                <caption className="visually-hidden">{t('workSchedules.review.label')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('workSchedules.columns.position')}</th>
                    <th scope="col">{t('workSchedules.columns.when')}</th>
                    <th scope="col">{t('workSchedules.columns.work')}</th>
                    <th scope="col">{t('workSchedules.columns.employee')}</th>
                    <th scope="col">{t('workSchedules.columns.check')}</th>
                  </tr>
                </thead>
                <tbody>
                  {preview.slots.map((slot) => (
                    <tr
                      key={slot.clientRef}
                      data-testid="review-slot"
                      data-issue={slot.issue ? slot.issue.code : ''}
                    >
                      <th scope="row">{slot.position}</th>
                      <td>{times(slot.start, slot.end)}</td>
                      <td>
                        <Labels en={slot.labels.en} fr={slot.labels.fr} language={language} />
                      </td>
                      <td>
                        {slot.employee ? (
                          name(slot.employee)
                        ) : (
                          <StatusBadge tone="warning">{t('workSchedules.uncovered')}</StatusBadge>
                        )}
                      </td>
                      <td data-testid="review-check">
                        {slot.issue ? (
                          <StatusBadge tone="danger">{t(issueKey(slot.issue))}</StatusBadge>
                        ) : (
                          <StatusBadge tone="success">{t('workSchedules.review.ok')}</StatusBadge>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
            <p>{t('workSchedules.review.confirmText')}</p>
            <div className="dialog-actions">
              <button
                type="button"
                className="button"
                disabled={busy || !preview.publishable}
                aria-busy={busy ? true : undefined}
                onClick={() => void publish()}
              >
                {busy ? t('workSchedules.review.publishing') : t('workSchedules.review.publish')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                onClick={backToEdit}
              >
                {t('workSchedules.review.back')}
              </button>
            </div>
          </div>
        )}
        {step === 'review' && !preview && (
          <div className="dialog-actions">
            <button type="button" className="button button--secondary" onClick={backToEdit}>
              {t('workSchedules.review.back')}
            </button>
          </div>
        )}
      </section>
    </section>
  );
}

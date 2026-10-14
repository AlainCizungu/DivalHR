import type {
  TimesheetDetail,
  TimesheetGeneration,
  TimesheetGenerationPreview,
  TimesheetGenerationSummary,
  TimesheetPeriod,
  TimesheetRefreshPreview,
  TimesheetTotals,
} from '@divalhr/api-client';
import { useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { formatInstant } from './attendance';
import { TimesheetHistory } from './TimesheetHistory';
import { TimesheetRows } from './TimesheetRows';
import {
  STATUS_TONES,
  TIMESHEET_NETWORK_FAILURE,
  currentMonday,
  durationParts,
  timesheetFailureOf,
  weekProblemsOf,
  type TimesheetFailure,
  type WeekField,
  type WeekForm,
  type WeekProblem,
} from './timesheets';

/** A fresh key per reviewed preview (the platform's key grammar): a retry reuses it. */
/**
 * MVP-070B/C: a refresh refused because the week holds submitted or approved time says so (the
 * same code refuses a generation of an empty week).
 */
function refreshFailureOf(failure: TimesheetFailure): TimesheetFailure {
  return failure.kind === 'notAllowed'
    ? { ...failure, messageKey: 'timesheets.refresh.locked' }
    : failure;
}

function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

function Failure({ failure, testId }: { failure: TimesheetFailure; testId: string }) {
  const { t } = useTranslation();
  return (
    <div className="error-summary" role="alert" data-testid={testId}>
      <p>{t(failure.messageKey)}</p>
      {failure.correlationId && (
        <p className="muted">{t('employees.reference', { id: failure.correlationId })}</p>
      )}
    </div>
  );
}

/** Counts by state and the worked time, as the server computed them. */
export function TotalsList({ totals, testId }: { totals: TimesheetTotals; testId: string }) {
  const { t } = useTranslation();
  return (
    <dl className="decision-summary" data-testid={testId}>
      <dt>{t('timesheets.totals.rows')}</dt>
      <dd data-testid={`${testId}-rows`}>{totals.rows}</dd>
      <dt>{t('timesheets.totals.ready')}</dt>
      <dd data-testid={`${testId}-ready`}>{totals.readyRows}</dd>
      <dt>{t('timesheets.totals.blocked')}</dt>
      <dd data-testid={`${testId}-blocked`}>
        {totals.blockedRows}
        {totals.blockedRows > 0 && (
          <span className="muted">
            {' '}
            (
            {[
              totals.missingRows > 0
                ? t('timesheets.totals.part', {
                    label: t('timesheets.totals.missing'),
                    count: totals.missingRows,
                  })
                : null,
              totals.openRows > 0
                ? t('timesheets.totals.part', {
                    label: t('timesheets.totals.open'),
                    count: totals.openRows,
                  })
                : null,
              totals.changedRows > 0
                ? t('timesheets.totals.part', {
                    label: t('timesheets.totals.changed'),
                    count: totals.changedRows,
                  })
                : null,
            ]
              .filter(Boolean)
              .join(', ')}
            )
          </span>
        )}
      </dd>
      <dt>{t('timesheets.totals.worked')}</dt>
      <dd data-testid={`${testId}-worked`}>
        {t('timesheets.duration', durationParts(totals.workedMinutes))}
      </dd>
    </dl>
  );
}

/** The site-local week and its zone. */
export function PeriodText({ period }: { period: TimesheetPeriod }) {
  const { t, i18n } = useTranslation();
  return (
    <>
      <p data-testid="timesheet-period">
        <strong>
          {period.siteName && period.siteCode
            ? t('workSchedules.siteName', { name: period.siteName, code: period.siteCode })
            : t('workSchedules.siteUnknown')}
        </strong>
        {' · '}
        {t('timesheets.period', {
          from: formatDate(i18n.language, period.weekStart),
          to: formatDate(i18n.language, period.lastDay),
        })}
      </p>
      <p className="muted">{t('timesheets.zone', { zone: period.timezone })}</p>
    </>
  );
}

/**
 * MVP-070A: « Feuilles de temps » / "Timesheets" under Operations. A tenant administrator with
 * MFA chooses a site and a Monday, previews what a generation would create (drafts, shifts, ready
 * and blocked rows, worked time) and generates the week's DRAFT timesheets after an explicit
 * confirmation; a week already generated opens its drafts. Each draft opens its rows and revisions.
 * A refresh is previewed (new drafts, drafts with a new revision, changed shifts) and applied only
 * with that exact preview: a stale one is refused and previewed again. Nothing here submits,
 * approves, prices or exports time; nothing is kept in browser storage or put in a URL.
 */
export function TimesheetsPage() {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const [legalEntityId, setLegalEntityId] = useState('');
  const [form, setForm] = useState<WeekForm>({ siteId: '', weekStart: currentMonday() });
  const [problems, setProblems] = useState<Map<WeekField, WeekProblem>>(new Map());
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);
  const [failure, setFailure] = useState<TimesheetFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  // The reviewed generation preview, its body and its key.
  const [preview, setPreview] = useState<TimesheetGenerationPreview | null>(null);
  const reviewed = useRef<WeekForm | null>(null);
  const generateKey = useRef(newKey());
  const [confirmed, setConfirmed] = useState(false);
  // The opened generation, its refresh preview and one draft.
  const [generation, setGeneration] = useState<TimesheetGeneration | null>(null);
  const [refreshPreview, setRefreshPreview] = useState<TimesheetRefreshPreview | null>(null);
  const refreshKey = useRef(newKey());
  const [refreshConfirmed, setRefreshConfirmed] = useState(false);
  const [detail, setDetail] = useState<TimesheetDetail | null>(null);
  const generationHeading = useRef<HTMLHeadingElement>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const refreshHeading = useRef<HTMLHeadingElement>(null);
  const refreshOpener = useRef<HTMLButtonElement>(null);
  const opener = useRef<HTMLButtonElement | null>(null);

  const legalEntities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const sites = useOptions(legalEntityId || null, () =>
    loadAll((cursor) =>
      core.GET('/sites', { params: { query: { legalEntityId, cursor, limit: 200 } } }),
    ),
  );

  const run = async (work: () => Promise<void>) => {
    if (sending.current) return;
    sending.current = true;
    setBusy(true);
    setFailure(null);
    try {
      await work();
    } catch {
      setFailure(TIMESHEET_NETWORK_FAILURE);
    } finally {
      sending.current = false;
      setBusy(false);
    }
  };

  const openGeneration = async (summary: TimesheetGenerationSummary) => {
    const { data, error, response } = await core.GET('/timesheets/generations/{generationId}', {
      params: { path: { generationId: summary.generationId } },
      cache: 'no-store',
    });
    if (!data) {
      setFailure(timesheetFailureOf('admin', response, error));
      return;
    }
    setGeneration(data);
    setDetail(null);
    setRefreshPreview(null);
    requestAnimationFrame(() => generationHeading.current?.focus());
  };

  const onPreview = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = weekProblemsOf(form);
    setProblems(found);
    if (found.size > 0) return;
    void run(async () => {
      const body = { siteId: form.siteId, weekStart: form.weekStart };
      const { data, error, response } = await core.POST('/timesheets/generation-preview', {
        body,
      });
      setGeneration(null);
      setDetail(null);
      setRefreshPreview(null);
      if (!data) {
        setPreview(null);
        const mapped = timesheetFailureOf('admin', response, error);
        if (mapped.fields?.includes('weekStart')) {
          setProblems(new Map([['weekStart', 'range']]));
        }
        setFailure(mapped);
        return;
      }
      reviewed.current = { ...form };
      generateKey.current = newKey();
      setConfirmed(false);
      setPreview(data);
    });
  };

  const onGenerate = () => {
    const body = reviewed.current;
    if (!body || !confirmed) return;
    void run(async () => {
      const { data, error, response } = await core.POST('/timesheets/generations', {
        params: { header: { 'Idempotency-Key': generateKey.current } },
        body: { siteId: body.siteId, weekStart: body.weekStart, confirm: true },
      });
      if (!data) {
        setFailure(timesheetFailureOf('admin', response, error));
        return;
      }
      setPreview(null);
      setAnnouncement(
        data.outcome === 'CREATED'
          ? t('timesheets.generated', { count: data.generation.drafts })
          : t('timesheets.existingOpened'),
      );
      await openGeneration(data.generation);
    });
  };

  const onRefreshPreview = () => {
    if (!generation) return;
    const period = generation.generation.period;
    void run(async () => {
      const { data, error, response } = await core.POST(
        '/timesheets/generations/{generationId}/refresh-preview',
        {
          params: { path: { generationId: generation.generation.generationId } },
          body: { siteId: period.siteId, weekStart: period.weekStart },
        },
      );
      if (!data) {
        setFailure(refreshFailureOf(timesheetFailureOf('admin', response, error)));
        return;
      }
      refreshKey.current = newKey();
      setRefreshConfirmed(false);
      setRefreshPreview(data);
      requestAnimationFrame(() => refreshHeading.current?.focus());
    });
  };

  const onRefresh = () => {
    if (!generation || !refreshPreview || !refreshConfirmed) return;
    const period = generation.generation.period;
    void run(async () => {
      const { data, error, response } = await core.POST(
        '/timesheets/generations/{generationId}/refreshes',
        {
          params: {
            path: { generationId: generation.generation.generationId },
            header: { 'Idempotency-Key': refreshKey.current },
          },
          body: {
            siteId: period.siteId,
            weekStart: period.weekStart,
            version: refreshPreview.version,
            previewDigest: refreshPreview.previewDigest,
            confirm: true,
          },
        },
      );
      if (!data) {
        const mapped = refreshFailureOf(timesheetFailureOf('admin', response, error));
        if (mapped.kind === 'stale' || mapped.kind === 'notAllowed') setRefreshPreview(null);
        setFailure(mapped);
        return;
      }
      setAnnouncement(t('timesheets.refresh.done', { version: data.generation.version }));
      await openGeneration(data.generation);
    });
  };

  const openDraft = (timesheetId: string) => {
    void run(async () => {
      const { data, error, response } = await core.GET('/timesheets/{timesheetId}', {
        params: { path: { timesheetId } },
        cache: 'no-store',
      });
      if (!data) {
        setFailure(timesheetFailureOf('admin', response, error));
        return;
      }
      setDetail(data);
      requestAnimationFrame(() => detailHeading.current?.focus());
    });
  };

  const name = (employee: { givenNames: string; familyName: string; employeeNumber: string }) =>
    t('timesheets.generation.employee', {
      givenNames: employee.givenNames,
      familyName: employee.familyName,
      number: employee.employeeNumber,
    });

  const problemText = (field: WeekField) => {
    const problem = problems.get(field);
    return problem ? t(`timesheets.form.problem.${problem}`) : null;
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="timesheets">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('timesheets.title')}
        description={t('timesheets.description')}
      />
      <p className="muted" data-testid="timesheets-scope">
        {t('timesheets.scope')}
      </p>
      <p role="status" className="visually-hidden" data-testid="timesheets-announcer">
        {announcement}
      </p>

      <form
        noValidate
        className="card"
        aria-label={t('timesheets.form.label')}
        onSubmit={onPreview}
        data-testid="timesheets-form"
      >
        <fieldset className="field">
          <legend>{t('timesheets.form.label')}</legend>
          {(legalEntities.status === 'failed' || sites.status === 'failed') && (
            <p className="field__error" role="alert">
              {t('timesheets.form.sitesUnavailable')}
            </p>
          )}
          <label htmlFor={`${ids}-legal-entity`}>{t('timesheets.form.legalEntity')}</label>
          <select
            id={`${ids}-legal-entity`}
            value={legalEntityId}
            onChange={(event) => {
              setLegalEntityId(event.target.value);
              setForm({ ...form, siteId: '' });
            }}
          >
            <option value="">{t('timesheets.form.chooseLegalEntity')}</option>
            {legalEntities.items.map((option) => (
              <option key={option.id} value={option.id}>
                {t('workSchedules.siteName', { name: option.name, code: option.code })}
              </option>
            ))}
          </select>
          <label htmlFor={`${ids}-siteId`}>{t('timesheets.form.site')}</label>
          <select
            id={`${ids}-siteId`}
            value={form.siteId}
            disabled={!legalEntityId}
            aria-invalid={problems.has('siteId') ? true : undefined}
            aria-describedby={problems.has('siteId') ? `${ids}-siteId-error` : undefined}
            data-testid="timesheets-site"
            onChange={(event) => {
              setForm({ ...form, siteId: event.target.value });
            }}
          >
            <option value="">{t('timesheets.form.chooseSite')}</option>
            {sites.items.map((option) => (
              <option key={option.id} value={option.id}>
                {t('workSchedules.siteName', { name: option.name, code: option.code })}
              </option>
            ))}
          </select>
          {problemText('siteId') && (
            <p id={`${ids}-siteId-error`} className="field__error">
              {problemText('siteId')}
            </p>
          )}
        </fieldset>
        <div className="field">
          <label htmlFor={`${ids}-week`}>{t('timesheets.form.week')}</label>
          <input
            id={`${ids}-week`}
            type="date"
            value={form.weekStart}
            aria-invalid={problems.has('weekStart') ? true : undefined}
            aria-describedby={`${ids}-week-hint${problems.has('weekStart') ? ` ${ids}-week-error` : ''}`}
            data-testid="timesheets-week"
            onChange={(event) => {
              setForm({ ...form, weekStart: event.target.value });
            }}
          />
          <p id={`${ids}-week-hint`} className="muted">
            {t('timesheets.form.weekHint')}
          </p>
          {problemText('weekStart') && (
            <p id={`${ids}-week-error`} className="field__error">
              {problemText('weekStart')}
            </p>
          )}
        </div>
        <p>
          <button
            type="submit"
            className="button"
            disabled={busy}
            aria-busy={busy ? true : undefined}
            data-testid="timesheets-preview"
          >
            {busy ? t('timesheets.form.previewing') : t('timesheets.form.preview')}
          </button>
        </p>
      </form>

      {failure && <Failure failure={failure} testId="timesheets-error" />}

      {preview && (
        <section
          className="card"
          aria-labelledby={`${ids}-preview-title`}
          data-testid="timesheets-generation-preview"
        >
          <h2 id={`${ids}-preview-title`}>{t('timesheets.preview.title')}</h2>
          <PeriodText period={preview.period} />
          {preview.generation ? (
            <>
              <p data-testid="timesheets-existing">
                {t('timesheets.preview.existing', { version: preview.generation.version })}
              </p>
              <p>
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  data-testid="timesheets-open-existing"
                  onClick={() => {
                    const existing = preview.generation;
                    if (existing) void run(() => openGeneration(existing));
                  }}
                >
                  {t('timesheets.preview.open')}
                </button>
              </p>
            </>
          ) : (
            <>
              <dl className="decision-summary">
                <dt>{t('timesheets.totals.drafts')}</dt>
                <dd data-testid="timesheets-preview-drafts">{preview.drafts}</dd>
              </dl>
              <TotalsList totals={preview.totals} testId="timesheets-preview-totals" />
              {preview.totals.blockedRows > 0 && (
                <p className="muted">{t('timesheets.preview.blockedNote')}</p>
              )}
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={confirmed}
                    data-testid="timesheets-confirm"
                    onChange={(event) => {
                      setConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('timesheets.preview.confirmCheck')}
                </label>
              </div>
              <p>
                <button
                  type="button"
                  className="button"
                  disabled={busy || !confirmed}
                  aria-busy={busy ? true : undefined}
                  data-testid="timesheets-generate"
                  onClick={onGenerate}
                >
                  {busy ? t('timesheets.preview.generating') : t('timesheets.preview.generate')}
                </button>
              </p>
            </>
          )}
        </section>
      )}

      {generation && (
        <section
          className="card"
          aria-labelledby={`${ids}-generation-title`}
          data-testid="timesheets-generation"
        >
          <h2 id={`${ids}-generation-title`} ref={generationHeading} tabIndex={-1}>
            {t('timesheets.generation.title')}
          </h2>
          <PeriodText period={generation.generation.period} />
          <p>
            <StatusBadge tone="neutral" testId="timesheets-generation-status">
              {t('timesheets.generation.status')}
            </StatusBadge>{' '}
            <span data-testid="timesheets-generation-version">
              {t('timesheets.generation.version', { version: generation.generation.version })}
            </span>
          </p>
          <p className="muted">
            {t('timesheets.generation.generatedAt', {
              time: formatInstant(
                language,
                generation.generation.generatedAt,
                generation.generation.period.timezone,
              ),
            })}
            {generation.generation.refreshedAt && (
              <>
                {' · '}
                {t('timesheets.generation.refreshedAt', {
                  time: formatInstant(
                    language,
                    generation.generation.refreshedAt,
                    generation.generation.period.timezone,
                  ),
                })}
              </>
            )}
          </p>
          <TotalsList totals={generation.generation.totals} testId="timesheets-generation-totals" />
          <ScrollRegion label={t('timesheets.generation.label')}>
            <table className="timesheet-table" data-testid="timesheets-drafts">
              <caption className="visually-hidden">{t('timesheets.generation.label')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('timesheets.generation.columns.employee')}</th>
                  <th scope="col">{t('timesheets.generation.columns.status')}</th>
                  <th scope="col">{t('timesheets.generation.columns.revision')}</th>
                  <th scope="col">{t('timesheets.generation.columns.rows')}</th>
                  <th scope="col">{t('timesheets.generation.columns.ready')}</th>
                  <th scope="col">{t('timesheets.generation.columns.blocked')}</th>
                  <th scope="col">{t('timesheets.generation.columns.worked')}</th>
                  <th scope="col">{t('timesheets.generation.columns.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {generation.timesheets.map((draft) => (
                  <tr key={draft.timesheetId} data-testid="timesheets-draft">
                    <th scope="row">{name(draft.employee)}</th>
                    <td>
                      <StatusBadge
                        tone={STATUS_TONES[draft.status]}
                        testId="timesheets-draft-status"
                      >
                        {t(`timesheets.status.${draft.status}`)}
                      </StatusBadge>
                    </td>
                    <td data-testid="timesheets-draft-revision">{draft.revision}</td>
                    <td>{draft.totals.rows}</td>
                    <td>{draft.totals.readyRows}</td>
                    <td>
                      {draft.totals.blockedRows > 0 ? (
                        <StatusBadge tone="warning" testId="timesheets-draft-blocked">
                          {draft.totals.blockedRows}
                        </StatusBadge>
                      ) : (
                        draft.totals.blockedRows
                      )}
                    </td>
                    <td data-testid="timesheets-draft-worked">
                      {t('timesheets.duration', durationParts(draft.totals.workedMinutes))}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('timesheets.generation.viewFor', {
                          name: name(draft.employee),
                        })}
                        data-testid="timesheets-draft-view"
                        onClick={(event) => {
                          opener.current = event.currentTarget;
                          openDraft(draft.timesheetId);
                        }}
                      >
                        {t('timesheets.generation.view')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>

          {!refreshPreview && (
            <p>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                ref={refreshOpener}
                data-testid="timesheets-refresh-preview"
                onClick={onRefreshPreview}
              >
                {busy ? t('timesheets.refresh.previewing') : t('timesheets.refresh.action')}
              </button>
            </p>
          )}
          {refreshPreview && (
            <section
              aria-labelledby={`${ids}-refresh-title`}
              data-testid="timesheets-refresh"
              className="card"
            >
              <h3 id={`${ids}-refresh-title`} ref={refreshHeading} tabIndex={-1}>
                {t('timesheets.refresh.title')}
              </h3>
              <p className="muted">{t('timesheets.refresh.description')}</p>
              <dl className="decision-summary">
                <dt>{t('timesheets.refresh.newDrafts')}</dt>
                <dd data-testid="timesheets-refresh-new">{refreshPreview.newDrafts}</dd>
                <dt>{t('timesheets.refresh.changedTimesheets')}</dt>
                <dd data-testid="timesheets-refresh-changed">{refreshPreview.changedTimesheets}</dd>
                <dt>{t('timesheets.refresh.unchangedTimesheets')}</dt>
                <dd data-testid="timesheets-refresh-unchanged">
                  {refreshPreview.unchangedTimesheets}
                </dd>
                <dt>{t('timesheets.refresh.changedRows')}</dt>
                <dd data-testid="timesheets-refresh-rows">{refreshPreview.changedRows}</dd>
              </dl>
              <TotalsList totals={refreshPreview.totals} testId="timesheets-refresh-totals" />
              {refreshPreview.newDrafts + refreshPreview.changedTimesheets === 0 && (
                <p data-testid="timesheets-refresh-nothing">{t('timesheets.refresh.nothing')}</p>
              )}
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={refreshConfirmed}
                    data-testid="timesheets-refresh-confirm"
                    onChange={(event) => {
                      setRefreshConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('timesheets.refresh.confirmCheck')}
                </label>
              </div>
              <div className="button-row">
                <button
                  type="button"
                  className="button"
                  disabled={busy || !refreshConfirmed}
                  aria-busy={busy ? true : undefined}
                  data-testid="timesheets-refresh-apply"
                  onClick={onRefresh}
                >
                  {busy ? t('timesheets.refresh.applying') : t('timesheets.refresh.apply')}
                </button>
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy}
                  onClick={() => {
                    setRefreshPreview(null);
                    requestAnimationFrame(() => refreshOpener.current?.focus());
                  }}
                >
                  {t('timesheets.refresh.cancel')}
                </button>
              </div>
            </section>
          )}
        </section>
      )}

      {detail && (
        <section
          className="card"
          aria-labelledby={`${ids}-detail-title`}
          data-testid="timesheets-detail"
        >
          <h2 id={`${ids}-detail-title`} ref={detailHeading} tabIndex={-1}>
            {t('timesheets.detail.title', { name: name(detail.employee) })}
          </h2>
          <PeriodText period={detail.period} />
          <p>
            <StatusBadge tone={STATUS_TONES[detail.status]} testId="timesheets-detail-status">
              {t(`timesheets.status.${detail.status}`)}
            </StatusBadge>{' '}
            <span data-testid="timesheets-detail-revision">
              {t('timesheets.detail.revision', { revision: detail.revision })}
            </span>
            {detail.submissionCycle > 0 && (
              <>
                {' '}
                <span data-testid="timesheets-detail-cycle">
                  {t('timesheets.detail.cycle', { cycle: detail.submissionCycle })}
                </span>
              </>
            )}
          </p>
          <TotalsList totals={detail.totals} testId="timesheets-detail-totals" />
          <TimesheetRows
            rows={detail.rows}
            zone={detail.period.timezone}
            label={t('timesheets.detail.rowsLabel')}
            testId="timesheets-detail-rows"
          />
          <h3>{t('timesheets.detail.revisionsLabel')}</h3>
          <ScrollRegion label={t('timesheets.detail.revisionsLabel')}>
            <table className="timesheet-table" data-testid="timesheets-detail-revisions">
              <caption className="visually-hidden">{t('timesheets.detail.revisionsLabel')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('timesheets.detail.columns.revision')}</th>
                  <th scope="col">{t('timesheets.detail.columns.version')}</th>
                  <th scope="col">{t('timesheets.detail.columns.createdAt')}</th>
                  <th scope="col">{t('timesheets.totals.ready')}</th>
                  <th scope="col">{t('timesheets.totals.blocked')}</th>
                  <th scope="col">{t('timesheets.totals.worked')}</th>
                </tr>
              </thead>
              <tbody>
                {detail.revisions.map((revision) => (
                  <tr key={revision.revision} data-testid="timesheets-revision">
                    <th scope="row">{revision.revision}</th>
                    <td>{revision.generationVersion}</td>
                    <td>{formatInstant(language, revision.createdAt, detail.period.timezone)}</td>
                    <td>{revision.totals.readyRows}</td>
                    <td>{revision.totals.blockedRows}</td>
                    <td>
                      {t('timesheets.duration', durationParts(revision.totals.workedMinutes))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
          <h3>{t('timesheets.history.title')}</h3>
          <TimesheetHistory
            actions={detail.actions}
            zone={detail.period.timezone}
            testId="timesheets-detail-history"
          />
          <p>
            <button
              type="button"
              className="button button--secondary"
              data-testid="timesheets-detail-close"
              onClick={() => {
                setDetail(null);
                requestAnimationFrame(() => {
                  if (opener.current?.isConnected) opener.current.focus();
                  else generationHeading.current?.focus();
                });
              }}
            >
              {t('timesheets.detail.close')}
            </button>
          </p>
        </section>
      )}
    </section>
  );
}

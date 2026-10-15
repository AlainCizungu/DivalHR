import type {
  TimesheetExport,
  TimesheetExportPreview,
  TimesheetExportScope,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { loadAll, useOptions } from '../people/unitOptions';
import { formatInstant } from './attendance';
import {
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
function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

/** A Blob saved under a safe name; nothing is kept in browser storage or put in a URL. */
function save(data: Blob, filename: string) {
  const url = URL.createObjectURL(data);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
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

/** The exported scope: legal entity, site and the site-local week. */
function ScopeText({ scope }: { scope: TimesheetExportScope }) {
  const { t, i18n } = useTranslation();
  return (
    <>
      <p data-testid="time-exports-scope">
        <strong>
          {scope.siteName && scope.siteCode
            ? t('workSchedules.siteName', { name: scope.siteName, code: scope.siteCode })
            : t('workSchedules.siteUnknown')}
        </strong>
        {scope.legalEntityName && scope.legalEntityCode && (
          <>
            {' · '}
            {t('workSchedules.siteName', {
              name: scope.legalEntityName,
              code: scope.legalEntityCode,
            })}
          </>
        )}
        {' · '}
        {t('timesheets.period', {
          from: formatDate(i18n.language, scope.weekStart),
          to: formatDate(i18n.language, scope.lastDay),
        })}
      </p>
      <p className="muted">{t('timesheets.zone', { zone: scope.timezone })}</p>
    </>
  );
}

/** Approved timesheets, employees, rows and worked time. */
function Counts({
  counts,
  testId,
}: {
  counts: { timesheets: number; employees: number; rows: number; workedMinutes: number };
  testId: string;
}) {
  const { t } = useTranslation();
  return (
    <dl className="decision-summary" data-testid={testId}>
      <dt>{t('timeExports.counts.timesheets')}</dt>
      <dd data-testid={`${testId}-timesheets`}>{counts.timesheets}</dd>
      <dt>{t('timeExports.counts.employees')}</dt>
      <dd data-testid={`${testId}-employees`}>{counts.employees}</dd>
      <dt>{t('timeExports.counts.rows')}</dt>
      <dd data-testid={`${testId}-rows`}>{counts.rows}</dd>
      <dt>{t('timeExports.counts.worked')}</dt>
      <dd data-testid={`${testId}-worked`}>
        {t('timesheets.duration', durationParts(counts.workedMinutes))}
      </dd>
    </dl>
  );
}

/**
 * MVP-070E: « Exports de temps » / "Time exports" under Operations. A tenant administrator with
 * MFA chooses a legal entity, one of its sites and a generated week, previews exactly what is
 * eligible (approved timesheets only, with the other timesheets counted by state), confirms and
 * creates an immutable export, then downloads its CSV and manifest. The history lists exports
 * newest first. Approved time only: nothing here calculates pay. Nothing is kept in browser
 * storage or put in a URL.
 */
export function TimeExportsPage() {
  const { t, i18n } = useTranslation();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const { core } = useApi();
  const ids = useId();
  const [legalEntityId, setLegalEntityId] = useState('');
  const [legalEntityMissing, setLegalEntityMissing] = useState(false);
  const [form, setForm] = useState<WeekForm>({ siteId: '', weekStart: currentMonday() });
  const [problems, setProblems] = useState<Map<WeekField, WeekProblem>>(new Map());
  const [busy, setBusy] = useState(false);
  const sending = useRef(false);
  const [failure, setFailure] = useState<TimesheetFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const [preview, setPreview] = useState<TimesheetExportPreview | null>(null);
  const reviewed = useRef<{ legalEntityId: string; siteId: string; weekStart: string } | null>(
    null,
  );
  const createKey = useRef(newKey());
  const [confirmed, setConfirmed] = useState(false);
  const [history, setHistory] = useState<TimesheetExport[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [historyFailed, setHistoryFailed] = useState(false);
  const [detail, setDetail] = useState<TimesheetExport | null>(null);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);

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

  const fetchHistory = useCallback(
    async (cursor: string | null) => {
      try {
        const { data } = await core.GET('/timesheet-exports', {
          params: { query: cursor ? { cursor, limit: 20 } : { limit: 20 } },
          cache: 'no-store',
        });
        return data ?? null;
      } catch {
        return null;
      }
    },
    [core],
  );

  const applyHistory = useCallback(
    (data: { items: TimesheetExport[]; nextCursor?: string | null } | null, append: boolean) => {
      if (!data) {
        setHistoryFailed(true);
        return;
      }
      setHistoryFailed(false);
      setHistory((previous) => (append ? [...previous, ...data.items] : data.items));
      setNextCursor(data.nextCursor ?? null);
    },
    [],
  );

  const loadHistory = async (cursor: string | null) => {
    applyHistory(await fetchHistory(cursor), cursor !== null);
  };

  useEffect(() => {
    let active = true;
    void fetchHistory(null).then((data) => {
      if (active) applyHistory(data, false);
    });
    return () => {
      active = false;
    };
  }, [fetchHistory, applyHistory]);

  const open = (exportItem: TimesheetExport) => {
    void run(async () => {
      const { data, error, response } = await core.GET('/timesheet-exports/{exportId}', {
        params: { path: { exportId: exportItem.exportId } },
        cache: 'no-store',
      });
      if (!data) {
        setFailure(timesheetFailureOf('exports', response, error));
        return;
      }
      setDetail(data);
      requestAnimationFrame(() => detailHeading.current?.focus());
    });
  };

  const onPreview = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = weekProblemsOf(form);
    setProblems(found);
    setLegalEntityMissing(!legalEntityId);
    if (found.size > 0 || !legalEntityId) return;
    void run(async () => {
      const body = { legalEntityId, siteId: form.siteId, weekStart: form.weekStart };
      const { data, error, response } = await core.POST('/timesheet-exports/preview', { body });
      if (!data) {
        setPreview(null);
        const mapped = timesheetFailureOf('exports', response, error);
        setFailure(
          mapped.kind === 'notFound'
            ? { ...mapped, messageKey: 'timeExports.preview.notGenerated' }
            : mapped,
        );
        return;
      }
      reviewed.current = body;
      createKey.current = newKey();
      setConfirmed(false);
      setPreview(data);
      requestAnimationFrame(() => previewHeading.current?.focus());
    });
  };

  const onCreate = () => {
    const body = reviewed.current;
    if (!body || !preview || !confirmed) return;
    void run(async () => {
      const { data, error, response } = await core.POST('/timesheet-exports', {
        params: { header: { 'Idempotency-Key': createKey.current } },
        body: { ...body, previewDigest: preview.previewDigest, confirm: true },
      });
      if (!data) {
        const mapped = timesheetFailureOf('exports', response, error);
        if (mapped.kind === 'stale' || mapped.kind === 'notAllowed') setPreview(null);
        setFailure(mapped);
        return;
      }
      setPreview(null);
      setAnnouncement(
        data.outcome === 'CREATED' ? t('timeExports.created') : t('timeExports.existing'),
      );
      await loadHistory(null);
      setDetail(data.export);
      requestAnimationFrame(() => detailHeading.current?.focus());
    });
  };

  const download = (exportItem: TimesheetExport, kind: 'csv' | 'manifest') => {
    void run(async () => {
      const path =
        kind === 'csv'
          ? ('/timesheet-exports/{exportId}/csv' as const)
          : ('/timesheet-exports/{exportId}/manifest' as const);
      const { data, error, response } = await core.GET(path, {
        params: { path: { exportId: exportItem.exportId } },
        parseAs: 'blob',
        cache: 'no-store',
      });
      if (!data) {
        setFailure(timesheetFailureOf('exports', response, error));
        return;
      }
      save(
        data,
        `divalhr-time-export-${exportItem.scope.weekStart}-${exportItem.exportId}.${
          kind === 'csv' ? 'csv' : 'manifest.json'
        }`,
      );
      setAnnouncement(
        kind === 'csv'
          ? t('timeExports.detail.csvDownloaded')
          : t('timeExports.detail.manifestDownloaded'),
      );
    });
  };

  const problemText = (field: WeekField) => {
    const problem = problems.get(field);
    return problem ? t(`timesheets.form.problem.${problem}`) : null;
  };

  const empty = preview !== null && preview.eligible.timesheets === 0;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="time-exports">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('timeExports.title')}
        description={t('timeExports.description')}
      />
      <p className="card" data-testid="time-exports-notice">
        <strong>{t('timeExports.notice')}</strong>
      </p>
      <p role="status" className="visually-hidden" data-testid="time-exports-announcer">
        {announcement}
      </p>

      <form
        noValidate
        className="card"
        aria-label={t('timeExports.form.label')}
        onSubmit={onPreview}
        data-testid="time-exports-form"
      >
        <fieldset className="field">
          <legend>{t('timeExports.form.label')}</legend>
          {(legalEntities.status === 'failed' || sites.status === 'failed') && (
            <p className="field__error" role="alert">
              {t('timesheets.form.sitesUnavailable')}
            </p>
          )}
          <label htmlFor={`${ids}-legal-entity`}>{t('timesheets.form.legalEntity')}</label>
          <select
            id={`${ids}-legal-entity`}
            value={legalEntityId}
            aria-invalid={legalEntityMissing ? true : undefined}
            aria-describedby={legalEntityMissing ? `${ids}-legal-entity-error` : undefined}
            data-testid="time-exports-legal-entity"
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
          {legalEntityMissing && (
            <p id={`${ids}-legal-entity-error`} className="field__error">
              {t('timeExports.form.legalEntityRequired')}
            </p>
          )}
          <label htmlFor={`${ids}-siteId`}>{t('timesheets.form.site')}</label>
          <select
            id={`${ids}-siteId`}
            value={form.siteId}
            disabled={!legalEntityId}
            aria-invalid={problems.has('siteId') ? true : undefined}
            aria-describedby={problems.has('siteId') ? `${ids}-siteId-error` : undefined}
            data-testid="time-exports-site"
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
            data-testid="time-exports-week"
            onChange={(event) => {
              setForm({ ...form, weekStart: event.target.value });
            }}
          />
          <p id={`${ids}-week-hint`} className="muted">
            {t('timeExports.form.weekHint')}
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
            data-testid="time-exports-preview-button"
          >
            {busy ? t('timesheets.form.previewing') : t('timesheets.form.preview')}
          </button>
        </p>
      </form>

      {failure && <Failure failure={failure} testId="time-exports-error" />}

      {preview && (
        <section
          className="card"
          aria-labelledby={`${ids}-preview-title`}
          data-testid="time-exports-preview"
        >
          <h2 id={`${ids}-preview-title`} ref={previewHeading} tabIndex={-1}>
            {t('timeExports.preview.title')}
          </h2>
          <ScopeText scope={preview.scope} />
          <p className="muted" data-testid="time-exports-preview-version">
            {t('timeExports.preview.version', { version: preview.generationVersion })}
          </p>
          <h3>{t('timeExports.preview.eligible')}</h3>
          <Counts counts={preview.eligible} testId="time-exports-eligible" />
          <h3>{t('timeExports.preview.excluded')}</h3>
          <dl className="decision-summary" data-testid="time-exports-excluded">
            <dt>{t('timeExports.excluded.draft')}</dt>
            <dd data-testid="time-exports-excluded-draft">{preview.excluded.draft}</dd>
            <dt>{t('timeExports.excluded.submitted')}</dt>
            <dd data-testid="time-exports-excluded-submitted">{preview.excluded.submitted}</dd>
            <dt>{t('timeExports.excluded.reopened')}</dt>
            <dd data-testid="time-exports-excluded-reopened">{preview.excluded.reopened}</dd>
          </dl>
          <p className="muted">{t('timeExports.preview.excludedNote')}</p>
          {empty ? (
            <p data-testid="time-exports-empty">{t('timeExports.preview.empty')}</p>
          ) : (
            <>
              {preview.existingExportId && (
                <p data-testid="time-exports-existing">{t('timeExports.preview.existing')}</p>
              )}
              <p className="muted" data-testid="time-exports-expiry">
                {t('timeExports.preview.expires', {
                  time: formatInstant(language, preview.expiresAt, preview.scope.timezone),
                })}
              </p>
              <div className="field">
                <label>
                  <input
                    type="checkbox"
                    checked={confirmed}
                    data-testid="time-exports-confirm"
                    onChange={(event) => {
                      setConfirmed(event.target.checked);
                    }}
                  />{' '}
                  {t('timeExports.preview.confirmCheck')}
                </label>
              </div>
              <p>
                <button
                  type="button"
                  className="button"
                  disabled={busy || !confirmed}
                  aria-busy={busy ? true : undefined}
                  data-testid="time-exports-create"
                  onClick={onCreate}
                >
                  {busy ? t('timeExports.preview.creating') : t('timeExports.preview.create')}
                </button>
              </p>
            </>
          )}
        </section>
      )}

      {detail && (
        <section
          className="card"
          aria-labelledby={`${ids}-detail-title`}
          data-testid="time-exports-detail"
        >
          <h2 id={`${ids}-detail-title`} ref={detailHeading} tabIndex={-1}>
            {t('timeExports.detail.title')}
          </h2>
          <p>
            <StatusBadge tone="success">{t('timeExports.status.READY')}</StatusBadge>
          </p>
          <ScopeText scope={detail.scope} />
          <dl className="decision-summary">
            <dt>{t('timeExports.detail.id')}</dt>
            <dd data-testid="time-exports-detail-id">
              <code>{detail.exportId}</code>
            </dd>
            <dt>{t('timeExports.detail.version')}</dt>
            <dd>{detail.generationVersion}</dd>
            <dt>{t('timeExports.detail.created')}</dt>
            <dd>{formatInstant(language, detail.createdAt, detail.scope.timezone)}</dd>
            <dt>{t('timeExports.detail.digest')}</dt>
            <dd data-testid="time-exports-detail-digest" className="break-anywhere">
              <code>{detail.csvSha256}</code>
            </dd>
          </dl>
          <Counts counts={detail.counts} testId="time-exports-detail-counts" />
          <p className="muted">{t('timeExports.detail.verify')}</p>
          <div className="actions">
            <button
              type="button"
              className="button"
              disabled={busy}
              data-testid="time-exports-download-csv"
              onClick={() => {
                download(detail, 'csv');
              }}
            >
              {t('timeExports.detail.csv')}
            </button>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="time-exports-download-manifest"
              onClick={() => {
                download(detail, 'manifest');
              }}
            >
              {t('timeExports.detail.manifest')}
            </button>
          </div>
        </section>
      )}

      <section
        className="card"
        aria-labelledby={`${ids}-history-title`}
        data-testid="time-exports-history"
      >
        <h2 id={`${ids}-history-title`}>{t('timeExports.history.title')}</h2>
        {historyFailed && (
          <p className="field__error" role="alert">
            {t('timeExports.history.unavailable')}
          </p>
        )}
        {history.length === 0 && !historyFailed ? (
          <p className="muted" data-testid="time-exports-history-empty">
            {t('timeExports.history.empty')}
          </p>
        ) : (
          <ScrollRegion label={t('timeExports.history.table')}>
            <table className="timesheet-table">
              <caption className="visually-hidden">{t('timeExports.history.table')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('timeExports.history.week')}</th>
                  <th scope="col">{t('timeExports.history.site')}</th>
                  <th scope="col">{t('timeExports.counts.timesheets')}</th>
                  <th scope="col">{t('timeExports.counts.worked')}</th>
                  <th scope="col">{t('timeExports.history.created')}</th>
                  <th scope="col">{t('timeExports.history.actions')}</th>
                </tr>
              </thead>
              <tbody>
                {history.map((item) => (
                  <tr key={item.exportId} data-testid="time-exports-item">
                    <td>
                      {t('timesheets.period', {
                        from: formatDate(i18n.language, item.scope.weekStart),
                        to: formatDate(i18n.language, item.scope.lastDay),
                      })}
                    </td>
                    <td>
                      {item.scope.siteName && item.scope.siteCode
                        ? t('workSchedules.siteName', {
                            name: item.scope.siteName,
                            code: item.scope.siteCode,
                          })
                        : t('workSchedules.siteUnknown')}
                    </td>
                    <td data-testid="time-exports-item-timesheets">{item.counts.timesheets}</td>
                    <td>{t('timesheets.duration', durationParts(item.counts.workedMinutes))}</td>
                    <td>{formatInstant(language, item.createdAt, item.scope.timezone)}</td>
                    <td>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy}
                        aria-label={t('timeExports.history.openFor', {
                          week: formatDate(i18n.language, item.scope.weekStart),
                          time: formatInstant(language, item.createdAt, item.scope.timezone),
                        })}
                        data-testid="time-exports-item-open"
                        onClick={() => {
                          open(item);
                        }}
                      >
                        {t('timeExports.history.open')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        )}
        {nextCursor && (
          <p>
            <button
              type="button"
              className="button button--secondary"
              disabled={busy}
              data-testid="time-exports-more"
              onClick={() => {
                void run(() => loadHistory(nextCursor));
              }}
            >
              {t('timeExports.history.more')}
            </button>
          </p>
        )}
      </section>
    </section>
  );
}

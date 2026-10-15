import type { MyTimesheet, MyTimesheetItem } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatDate } from '../people/history';
import { formatInstant } from './attendance';
import { TimesheetHistory } from './TimesheetHistory';
import { TimesheetRows } from './TimesheetRows';
import { TotalsList } from './TimesheetsPage';
import {
  STATUS_TONES,
  TIMESHEET_NETWORK_FAILURE,
  durationParts,
  timesheetFailureOf,
  type TimesheetFailure,
} from './timesheets';

type Listing =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: TimesheetFailure }
  | { kind: 'ready'; items: MyTimesheetItem[]; nextCursor: string | null };

type Opened =
  | { kind: 'loading'; id: string }
  | { kind: 'failed'; id: string; failure: TimesheetFailure }
  | { kind: 'ready'; id: string; timesheet: MyTimesheet };

/** A fresh key per reviewed submission (the platform's key grammar): a retry reuses it. */
function newKey(): string {
  return `web-${crypto.randomUUID()}`;
}

/**
 * MVP-070A/B/C: « Mes feuilles de temps » / "My timesheets" under My space: only the caller's own
 * weekly timesheets, newest week first, each with its site, week, state (« Brouillon » / "Draft",
 * « Soumise » / "Submitted", « Approuvée » / "Approved"), revision, counts and worked time. A
 * timesheet opens its shifts as the server stored them, whether it can be submitted now (or what
 * blocks it, to resolve with the organization), and its submission and decision history with a
 * return's reason. A clean draft is submitted after an explicit confirmation; the manager is never
 * named. Nothing is kept in browser storage or in a URL.
 */
export function MyTimesheetsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const language: 'en' | 'fr' = i18n.language.startsWith('fr') ? 'fr' : 'en';
  const [listing, setListing] = useState<Listing>({ kind: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const paging = useRef(false);
  const listRef = useRef<HTMLUListElement>(null);
  const [opened, setOpened] = useState<Opened | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const detailHeading = useRef<HTMLHeadingElement>(null);
  const [confirming, setConfirming] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitFailure, setSubmitFailure] = useState<TimesheetFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');
  const submitKey = useRef<string>(newKey());
  const submitButton = useRef<HTMLButtonElement>(null);
  const confirmCheck = useRef<HTMLInputElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Listing> => {
      try {
        const { data, error, response } = await core.GET('/me/timesheets', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor ?? null }
          : { kind: 'failed', failure: timesheetFailureOf('self', response, error) };
      } catch {
        return { kind: 'failed', failure: TIMESHEET_NETWORK_FAILURE };
      }
    },
    [core],
  );

  const reload = useCallback(async () => {
    setListing({ kind: 'loading' });
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

  const more = async (cursor: string) => {
    if (paging.current) return;
    paging.current = true;
    setLoadingMore(true);
    const before = listing.kind === 'ready' ? listing.items.length : 0;
    try {
      const next = await fetchPage(cursor);
      setListing((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
      requestAnimationFrame(() => {
        const item = listRef.current?.children[before] as HTMLElement | undefined;
        item?.focus();
      });
    } finally {
      paging.current = false;
      setLoadingMore(false);
    }
  };

  const load = async (id: string): Promise<Opened> => {
    try {
      const { data, error, response } = await core.GET('/me/timesheets/{timesheetId}', {
        params: { path: { timesheetId: id } },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', id, timesheet: data }
        : { kind: 'failed', id, failure: timesheetFailureOf('self', response, error) };
    } catch {
      return { kind: 'failed', id, failure: TIMESHEET_NETWORK_FAILURE };
    }
  };

  const open = async (id: string) => {
    setOpened({ kind: 'loading', id });
    setConfirming(false);
    setConfirmed(false);
    setSubmitFailure(null);
    submitKey.current = newKey();
    setOpened(await load(id));
    requestAnimationFrame(() => detailHeading.current?.focus());
  };

  const onSubmit = async (timesheet: MyTimesheet) => {
    if (!confirmed || submitting) return;
    setSubmitting(true);
    setSubmitFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/timesheets/{timesheetId}/submissions',
        {
          params: {
            path: { timesheetId: timesheet.timesheetId },
            header: { 'Idempotency-Key': submitKey.current },
          },
          body: {
            revision: timesheet.revision,
            generationVersion: timesheet.generationVersion,
            confirm: true,
          },
        },
      );
      if (!data) {
        setSubmitFailure(timesheetFailureOf('self', response, error));
        requestAnimationFrame(() => failureRef.current?.focus());
        return;
      }
      setAnnouncement(t('myTimesheets.submission.done', { cycle: data.cycle }));
      setConfirming(false);
      setConfirmed(false);
      submitKey.current = newKey();
      setOpened(await load(timesheet.timesheetId));
      const next = await fetchPage(null);
      setListing(next);
      requestAnimationFrame(() => detailHeading.current?.focus());
    } catch {
      setSubmitFailure(TIMESHEET_NETWORK_FAILURE);
      requestAnimationFrame(() => failureRef.current?.focus());
    } finally {
      setSubmitting(false);
    }
  };

  const week = (from: string, to: string) => ({
    from: formatDate(i18n.language, from),
    to: formatDate(i18n.language, to),
  });

  const last = opened?.kind === 'ready' ? opened.timesheet.actions.at(0) : undefined;

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-timesheets">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('myTimesheets.title')}
        description={t('myTimesheets.description')}
      />
      <p className="card" data-testid="my-timesheets-notice">
        {t('myTimesheets.notice')}
      </p>
      <p role="status" className="visually-hidden" data-testid="my-timesheets-announcer">
        {announcement}
      </p>
      {listing.kind === 'loading' && <p role="status">{t('myTimesheets.loading')}</p>}
      {listing.kind === 'failed' && (
        <div className="error-summary" role="alert" data-testid="my-timesheets-error">
          <p>{t(listing.failure.messageKey)}</p>
          {listing.failure.correlationId && (
            <p className="muted">
              {t('employees.reference', { id: listing.failure.correlationId })}
            </p>
          )}
          {listing.failure.kind !== 'linkRequired' && listing.failure.kind !== 'forbidden' && (
            <button
              type="button"
              className="button button--secondary"
              onClick={() => void reload()}
            >
              {t('myTimesheets.retry')}
            </button>
          )}
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length === 0 && (
        <div className="card" data-testid="my-timesheets-empty">
          <p>{t('myTimesheets.empty')}</p>
        </div>
      )}
      {listing.kind === 'ready' && listing.items.length > 0 && (
        <ul className="shift-list" ref={listRef} aria-label={t('myTimesheets.label')}>
          {listing.items.map((item) => {
            const range = week(item.period.weekStart, item.period.lastDay);
            return (
              <li key={item.timesheetId} className="card" tabIndex={-1} data-testid="my-timesheet">
                <h2 className="shift-list__date">{t('myTimesheets.week', range)}</h2>
                <p>
                  <StatusBadge tone={STATUS_TONES[item.status]} testId="my-timesheet-status">
                    {t(`timesheets.status.${item.status}`)}
                  </StatusBadge>{' '}
                  <span data-testid="my-timesheet-revision">
                    {t('myTimesheets.revision', { revision: item.revision })}
                  </span>
                </p>
                <dl className="decision-summary">
                  <dt>{t('myTimesheets.site')}</dt>
                  <dd data-testid="my-timesheet-site">
                    {item.period.siteName ?? t('workSchedules.siteUnknown')}
                  </dd>
                  <dt>{t('timesheets.totals.worked')}</dt>
                  <dd data-testid="my-timesheet-worked">
                    {t('timesheets.duration', durationParts(item.totals.workedMinutes))}
                  </dd>
                  <dt>{t('timesheets.totals.ready')}</dt>
                  <dd>{item.totals.readyRows}</dd>
                  <dt>{t('timesheets.totals.blocked')}</dt>
                  <dd data-testid="my-timesheet-blocked">{item.totals.blockedRows}</dd>
                </dl>
                <p>
                  <button
                    type="button"
                    className="button button--secondary"
                    aria-label={t('myTimesheets.viewFor', { from: range.from })}
                    data-testid="my-timesheet-view"
                    onClick={(event) => {
                      opener.current = event.currentTarget;
                      void open(item.timesheetId);
                    }}
                  >
                    {t('myTimesheets.view')}
                  </button>
                </p>
              </li>
            );
          })}
        </ul>
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
            {loadingMore ? t('myTimesheets.loadingMore') : t('myTimesheets.more')}
          </button>
        </p>
      )}
      {opened && (
        <section
          className="card"
          aria-labelledby={opened.kind === 'ready' ? `${ids}-detail-title` : undefined}
          aria-label={opened.kind === 'ready' ? undefined : t('myTimesheets.rowsLabel')}
          data-testid="my-timesheet-detail"
        >
          {opened.kind === 'loading' && <p role="status">{t('myTimesheets.detailLoading')}</p>}
          {opened.kind === 'failed' && (
            <div className="error-summary" role="alert" data-testid="my-timesheet-detail-error">
              <p>{t(opened.failure.messageKey)}</p>
            </div>
          )}
          {opened.kind === 'ready' && (
            <>
              <h2 id={`${ids}-detail-title`} ref={detailHeading} tabIndex={-1}>
                {t(
                  'myTimesheets.detailTitle',
                  week(opened.timesheet.period.weekStart, opened.timesheet.period.lastDay),
                )}
              </h2>
              <p>
                <StatusBadge
                  tone={STATUS_TONES[opened.timesheet.status]}
                  testId="my-timesheet-detail-status"
                >
                  {t(`timesheets.status.${opened.timesheet.status}`)}
                </StatusBadge>{' '}
                <span>{t('myTimesheets.revision', { revision: opened.timesheet.revision })}</span>
              </p>
              <p className="muted">
                {t('timesheets.zone', { zone: opened.timesheet.period.timezone })}
              </p>
              {opened.timesheet.status === 'SUBMITTED' && last && (
                <p className="card" data-testid="my-timesheet-awaiting">
                  {t('myTimesheets.awaiting', {
                    time: formatInstant(
                      language,
                      last.occurredAt,
                      opened.timesheet.period.timezone,
                    ),
                    cycle: last.cycle,
                  })}
                </p>
              )}
              {opened.timesheet.status === 'APPROVED' && last && (
                <p className="card" data-testid="my-timesheet-approved">
                  {t('myTimesheets.approved', {
                    time: formatInstant(
                      language,
                      last.occurredAt,
                      opened.timesheet.period.timezone,
                    ),
                    cycle: last.cycle,
                  })}
                </p>
              )}
              {(opened.timesheet.status === 'REOPENED' ||
                (opened.timesheet.status === 'DRAFT' && last?.action === 'REOPENED')) &&
                last && (
                  <div className="card" data-testid="my-timesheet-reopened">
                    <h3>{t('myTimesheets.reopened.title')}</h3>
                    <p>
                      {t('myTimesheets.reopened.what', {
                        time: formatInstant(
                          language,
                          last.occurredAt,
                          opened.timesheet.period.timezone,
                        ),
                        cycle: last.cycle,
                      })}
                    </p>
                    {opened.timesheet.status === 'REOPENED' ? (
                      <p data-testid="my-timesheet-reopened-next">
                        <strong>{t('myTimesheets.reopened.refreshTitle')}</strong>{' '}
                        {t('myTimesheets.reopened.refresh')}
                      </p>
                    ) : (
                      <p data-testid="my-timesheet-reopened-next">
                        {t('myTimesheets.reopened.refreshed', {
                          revision: opened.timesheet.revision,
                          cycle: opened.timesheet.submissionCycle + 1,
                        })}
                      </p>
                    )}
                  </div>
                )}
              {opened.timesheet.status === 'DRAFT' && last?.action === 'RETURNED' && (
                <div className="card" data-testid="my-timesheet-returned">
                  <h3>{t('myTimesheets.returned.title')}</h3>
                  <p>
                    <strong>{t('myTimesheets.returned.reason')}</strong>{' '}
                    <span data-testid="my-timesheet-returned-reason">{last.returnReason}</span>
                  </p>
                  <p>{t('myTimesheets.returned.instruction')}</p>
                </div>
              )}
              <TotalsList totals={opened.timesheet.totals} testId="my-timesheet-totals" />
              <TimesheetRows
                rows={opened.timesheet.rows}
                zone={opened.timesheet.period.timezone}
                label={t('myTimesheets.rowsLabel')}
                testId="my-timesheet-rows"
              />
              {opened.timesheet.status === 'DRAFT' && (
                <section
                  aria-labelledby={`${ids}-submission-title`}
                  data-testid="my-timesheet-submission"
                >
                  <h3 id={`${ids}-submission-title`}>
                    {opened.timesheet.submission.eligible
                      ? t('myTimesheets.submission.title')
                      : t('myTimesheets.submission.blockedTitle')}
                  </h3>
                  {!opened.timesheet.submission.eligible && (
                    <>
                      <ul data-testid="my-timesheet-blockers">
                        {opened.timesheet.submission.blockers.map((blocker) => (
                          <li key={blocker} data-blocker={blocker}>
                            {t(`myTimesheets.submission.blockers.${blocker}`)}
                          </li>
                        ))}
                      </ul>
                      <p>{t('myTimesheets.submission.resolve')}</p>
                    </>
                  )}
                  {opened.timesheet.submission.eligible && !confirming && (
                    <>
                      <p>{t('myTimesheets.submission.eligible')}</p>
                      <p>
                        <button
                          type="button"
                          className="button"
                          ref={submitButton}
                          data-testid="my-timesheet-submit"
                          onClick={() => {
                            setConfirming(true);
                            setConfirmed(false);
                            requestAnimationFrame(() => confirmCheck.current?.focus());
                          }}
                        >
                          {t('myTimesheets.submission.submit')}
                        </button>
                      </p>
                    </>
                  )}
                  {opened.timesheet.submission.eligible && confirming && (
                    <div data-testid="my-timesheet-confirm">
                      <div className="field">
                        <label>
                          <input
                            type="checkbox"
                            ref={confirmCheck}
                            checked={confirmed}
                            data-testid="my-timesheet-confirm-check"
                            onChange={(event) => {
                              setConfirmed(event.target.checked);
                            }}
                          />{' '}
                          {t('myTimesheets.submission.confirmCheck')}
                        </label>
                      </div>
                      <div className="button-row">
                        <button
                          type="button"
                          className="button"
                          disabled={!confirmed || submitting}
                          aria-busy={submitting ? true : undefined}
                          data-testid="my-timesheet-submit-confirm"
                          onClick={() => void onSubmit(opened.timesheet)}
                        >
                          {submitting
                            ? t('myTimesheets.submission.submitting')
                            : t('myTimesheets.submission.confirm')}
                        </button>
                        <button
                          type="button"
                          className="button button--secondary"
                          disabled={submitting}
                          onClick={() => {
                            setConfirming(false);
                            setConfirmed(false);
                            requestAnimationFrame(() => submitButton.current?.focus());
                          }}
                        >
                          {t('myTimesheets.submission.cancel')}
                        </button>
                      </div>
                    </div>
                  )}
                  {submitFailure && (
                    <div
                      className="error-summary"
                      role="alert"
                      tabIndex={-1}
                      ref={failureRef}
                      data-testid="my-timesheet-submit-error"
                    >
                      <p>{t(submitFailure.messageKey)}</p>
                      {submitFailure.correlationId && (
                        <p className="muted">
                          {t('employees.reference', { id: submitFailure.correlationId })}
                        </p>
                      )}
                    </div>
                  )}
                </section>
              )}
              <h3>{t('timesheets.history.title')}</h3>
              <TimesheetHistory
                actions={opened.timesheet.actions}
                zone={opened.timesheet.period.timezone}
                testId="my-timesheet-history"
              />
            </>
          )}
          <p>
            <button
              type="button"
              className="button button--secondary"
              data-testid="my-timesheet-close"
              onClick={() => {
                setOpened(null);
                setConfirming(false);
                requestAnimationFrame(() => {
                  if (opener.current?.isConnected) opener.current.focus();
                  else document.getElementById(`${ids}-title`)?.focus();
                });
              }}
            >
              {t('myTimesheets.close')}
            </button>
          </p>
        </section>
      )}
    </section>
  );
}

import type {
  CommitStatutoryProfile,
  StatutoryProfileCommand,
  StatutoryProfileEntry,
  StatutoryProfilePreview,
  StatutoryProfileView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { formatDay, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { StatutoryLabel } from './StatutoryBits';
import {
  CLASSIFICATIONS,
  EVIDENCE_CODES,
  ISO_DATE,
  PROFILE_BLOCKERS,
  statutoryFailureOf,
} from './statutory';

type ProfileLoad =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; view: StatutoryProfileView };

interface ProfileForm {
  effectiveFrom: string;
  classification: StatutoryProfileCommand['classification'];
  cnssSubject: boolean;
  irppSubject: boolean;
  evidenceCode: StatutoryProfileCommand['evidenceCode'];
}

function Timeline({
  entries,
  caption,
  testId,
}: {
  entries: StatutoryProfileEntry[];
  caption: string;
  testId: string;
}) {
  const { t, i18n } = useTranslation();
  return (
    <ScrollRegion label={caption}>
      <table className="timesheet-table" data-testid={testId}>
        <caption className="visually-hidden">{caption}</caption>
        <thead>
          <tr>
            <th scope="col">{t('statutory.profile.columns.sequence')}</th>
            <th scope="col">{t('statutory.profile.columns.effectiveFrom')}</th>
            <th scope="col">{t('statutory.profile.columns.classification')}</th>
            <th scope="col">{t('statutory.profile.columns.cnss')}</th>
            <th scope="col">{t('statutory.profile.columns.irpp')}</th>
            <th scope="col">{t('statutory.profile.columns.evidence')}</th>
            <th scope="col">{t('statutory.profile.columns.recorded')}</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((entry) => (
            <tr key={entry.profileId} data-testid="statutory-profile-row">
              <th scope="row">{entry.sequence}</th>
              <td>{formatDay(i18n.language, entry.effectiveFrom)}</td>
              <td>{t(`statutory.classification.${entry.classification}`)}</td>
              <td>{entry.cnssSubject ? t('statutory.yes') : t('statutory.no')}</td>
              <td>{entry.irppSubject ? t('statutory.yes') : t('statutory.no')}</td>
              <td>{t(`statutory.evidence.${entry.evidenceCode}`)}</td>
              <td>{formatInstant(i18n.language, entry.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </ScrollRegion>
  );
}

/**
 * MVP-094B: an employee's effective-dated statutory profile (classification, CNSS and IRPP subject
 * flags, an evidence code from a closed list; nothing else). Profiles are restricted HR data: the
 * page reads the timeline, previews a change, and appends it after an explicit confirmation with
 * an Idempotency-Key reused for a retry of the same payload. A correction is a new entry.
 */
export function StatutoryProfilePage() {
  const { employeeId = '' } = useParams();
  return <Profile key={employeeId} employeeId={employeeId} />;
}

function Profile({ employeeId }: { employeeId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const focus = useFocusRequest();
  const [load, setLoad] = useState<ProfileLoad>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [form, setForm] = useState<ProfileForm | null>(null);
  const [problems, setProblems] = useState<string[]>([]);
  const [preview, setPreview] = useState<StatutoryProfilePreview | null>(null);
  const [previewed, setPreviewed] = useState<StatutoryProfileCommand | null>(null);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [announcement, setAnnouncement] = useState('');

  const fetchView = useCallback(async (): Promise<ProfileLoad> => {
    try {
      const { data, error, response } = await core.GET(
        '/employees/{employeeId}/statutory-profile',
        {
          params: { path: { employeeId } },
          cache: 'no-store',
        },
      );
      return data
        ? { kind: 'ready', view: data }
        : { kind: 'failed', failure: statutoryFailureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: NETWORK_FAILURE };
    }
  }, [core, employeeId]);

  useEffect(() => {
    let active = true;
    void fetchView().then((next) => {
      if (!active) return;
      setLoad(next);
      focus.request('title');
    });
    return () => {
      active = false;
    };
    // The focus helper is stable in behaviour.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchView, revision]);

  const view = load.kind === 'ready' ? load.view : null;
  const values: ProfileForm = form ?? {
    effectiveFrom: view?.businessDate ?? '',
    classification: 'STANDARD_RESIDENT',
    cnssSubject: true,
    irppSubject: true,
    evidenceCode: 'EMPLOYMENT_CONTRACT',
  };
  const unsupported = values.classification === 'UNSUPPORTED';

  const edit = (next: Partial<ProfileForm>) => {
    setForm({ ...values, ...next });
    setPreview(null);
    setPreviewed(null);
    setFailure(null);
  };

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    if (!ISO_DATE.test(values.effectiveFrom)) {
      setProblems(['effectiveFrom']);
      focus.request('summary');
      return;
    }
    setProblems([]);
    const command: StatutoryProfileCommand = {
      effectiveFrom: values.effectiveFrom,
      classification: values.classification,
      cnssSubject: unsupported ? false : values.cnssSubject,
      irppSubject: unsupported ? false : values.irppSubject,
      evidenceCode: values.evidenceCode,
    };
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/statutory-profile/preview',
        { params: { path: { employeeId } }, body: command, cache: 'no-store' },
      );
      if (data) {
        setPreview(data);
        setPreviewed(command);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(statutoryFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const blocked =
    preview !== null &&
    (preview.blockers.length > 0 || preview.previewDigest === null || preview.expiresAt === null);

  const onCommit = async () => {
    if (!preview || !previewed || busy || blocked) return;
    if (preview.previewDigest === null || preview.expiresAt === null) return;
    const body: CommitStatutoryProfile = {
      ...previewed,
      expectedVersion: preview.version,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/statutory-profile',
        {
          params: {
            path: { employeeId },
            header: { 'Idempotency-Key': commitKey.keyFor({ employeeId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        setForm(null);
        setPreview(null);
        setPreviewed(null);
        setAnnouncement(t('statutory.profile.announce'));
        setRevision((value) => value + 1);
        return;
      }
      const next = statutoryFailureOf(response, error);
      if (next.kind === 'stale') {
        commitKey.reset();
        setPreview(null);
        setPreviewed(null);
        setRevision((value) => value + 1);
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

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="statutory-profile">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('statutory.profile.title')}
        description={t('statutory.profile.description')}
      />
      <StatutoryLabel />
      <p>
        <Link className="table-link" to="/admin/payroll-runs">
          {t('payrollRuns.detail.back')}
        </Link>{' '}
        <Link className="table-link" to={`/admin/people/${employeeId}`}>
          {t('statutory.profile.openEmployee')}
        </Link>
      </p>
      <p role="status" className="visually-hidden" data-testid="statutory-profile-announcer">
        {announcement}
      </p>
      {load.kind === 'loading' && (
        <p role="status" data-testid="statutory-profile-loading">
          {t('payrollRuns.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert
          failure={load.failure}
          testId="statutory-profile-error"
          alertRef={focus.register('title')}
        />
      )}
      {view && (
        <>
          <section
            className="card"
            aria-labelledby={`${ids}-current`}
            data-testid="statutory-profile-current"
          >
            <h2 id={`${ids}-current`} tabIndex={-1} ref={focus.register('title')}>
              {t('statutory.profile.current')}
            </h2>
            <p className="muted">{t('statutory.profile.restricted')}</p>
            {view.current ? (
              <dl className="decision-summary decision-summary--wrapping">
                <dt>{t('statutory.profile.columns.classification')}</dt>
                <dd>{t(`statutory.classification.${view.current.classification}`)}</dd>
                <dt>{t('statutory.profile.columns.cnss')}</dt>
                <dd>{view.current.cnssSubject ? t('statutory.yes') : t('statutory.no')}</dd>
                <dt>{t('statutory.profile.columns.irpp')}</dt>
                <dd>{view.current.irppSubject ? t('statutory.yes') : t('statutory.no')}</dd>
                <dt>{t('statutory.profile.columns.evidence')}</dt>
                <dd>{t(`statutory.evidence.${view.current.evidenceCode}`)}</dd>
                <dt>{t('statutory.profile.columns.effectiveFrom')}</dt>
                <dd>{formatDay(i18n.language, view.current.effectiveFrom)}</dd>
              </dl>
            ) : (
              <p className="muted" data-testid="statutory-profile-none">
                {t('statutory.profile.none')}
              </p>
            )}
            {view.timeline.length > 0 && (
              <>
                <h3>{t('statutory.profile.timeline')}</h3>
                <Timeline
                  entries={view.timeline}
                  caption={t('statutory.profile.timeline')}
                  testId="statutory-profile-timeline"
                />
              </>
            )}
          </section>

          <section
            className="card"
            aria-labelledby={`${ids}-form`}
            data-testid="statutory-profile-form"
          >
            <h2 id={`${ids}-form`}>{t('statutory.profile.formTitle')}</h2>
            <p className="muted">{t('statutory.profile.formHelp')}</p>
            {problems.length > 0 && (
              <div
                className="error-summary"
                role="alert"
                tabIndex={-1}
                ref={focus.register('summary')}
                data-testid="statutory-profile-problems"
              >
                <p>{t('employees.form.problems.title')}</p>
                <ul>
                  {problems.map((problem) => (
                    <li key={problem}>{t(`statutory.profile.problems.${problem}`)}</li>
                  ))}
                </ul>
              </div>
            )}
            <form noValidate onSubmit={(event) => void onPreview(event)}>
              <div className="field">
                <label htmlFor={`${ids}-date`}>{t('statutory.profile.effectiveFrom')}</label>
                <p id={`${ids}-date-help`} className="field__help">
                  {t('statutory.profile.effectiveFromHelp')}
                </p>
                <input
                  id={`${ids}-date`}
                  type="date"
                  value={values.effectiveFrom}
                  aria-invalid={problems.includes('effectiveFrom') ? true : undefined}
                  aria-describedby={`${ids}-date-help`}
                  data-testid="statutory-profile-date"
                  onChange={(event) => {
                    edit({ effectiveFrom: event.target.value });
                  }}
                />
              </div>
              <div className="field">
                <label htmlFor={`${ids}-class`}>{t('statutory.profile.classification')}</label>
                <p id={`${ids}-class-help`} className="field__help">
                  {t('statutory.profile.classificationHelp')}
                </p>
                <select
                  id={`${ids}-class`}
                  value={values.classification}
                  aria-describedby={`${ids}-class-help`}
                  data-testid="statutory-profile-classification"
                  onChange={(event) => {
                    const classification = event.target.value as ProfileForm['classification'];
                    edit(
                      classification === 'UNSUPPORTED'
                        ? { classification, cnssSubject: false, irppSubject: false }
                        : { classification },
                    );
                  }}
                >
                  {CLASSIFICATIONS.map((code) => (
                    <option key={code} value={code}>
                      {t(`statutory.classification.${code}`)}
                    </option>
                  ))}
                </select>
              </div>
              <fieldset className="field">
                <legend>{t('statutory.profile.subjects')}</legend>
                <p className="field__help">
                  {unsupported
                    ? t('statutory.profile.subjectsUnsupported')
                    : t('statutory.profile.subjectsHelp')}
                </p>
                <div className="choice">
                  <input
                    id={`${ids}-cnss`}
                    type="checkbox"
                    checked={unsupported ? false : values.cnssSubject}
                    disabled={unsupported}
                    data-testid="statutory-profile-cnss"
                    onChange={(event) => {
                      edit({ cnssSubject: event.target.checked });
                    }}
                  />
                  <label htmlFor={`${ids}-cnss`}>{t('statutory.profile.cnss')}</label>
                </div>
                <div className="choice">
                  <input
                    id={`${ids}-irpp`}
                    type="checkbox"
                    checked={unsupported ? false : values.irppSubject}
                    disabled={unsupported}
                    data-testid="statutory-profile-irpp"
                    onChange={(event) => {
                      edit({ irppSubject: event.target.checked });
                    }}
                  />
                  <label htmlFor={`${ids}-irpp`}>{t('statutory.profile.irpp')}</label>
                </div>
              </fieldset>
              <div className="field">
                <label htmlFor={`${ids}-evidence`}>{t('statutory.profile.evidence')}</label>
                <select
                  id={`${ids}-evidence`}
                  value={values.evidenceCode}
                  data-testid="statutory-profile-evidence"
                  onChange={(event) => {
                    edit({ evidenceCode: event.target.value as ProfileForm['evidenceCode'] });
                  }}
                >
                  {EVIDENCE_CODES.map((code) => (
                    <option key={code} value={code}>
                      {t(`statutory.evidence.${code}`)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="dialog-actions">
                <button
                  type="submit"
                  className="button"
                  disabled={busy !== null}
                  data-testid="statutory-profile-preview-button"
                >
                  {busy === 'preview'
                    ? t('payrollRuns.previewing')
                    : t('statutory.profile.preview')}
                </button>
              </div>
            </form>
            {failure && (
              <PayrollAlert
                failure={failure}
                testId="statutory-profile-failure"
                alertRef={focus.register('failure')}
              />
            )}
            {preview && (
              <section aria-labelledby={`${ids}-preview`} data-testid="statutory-profile-preview">
                <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
                  {t('payrollRuns.preview.title')}
                </h3>
                <h4>{t('payrollRuns.policy.before')}</h4>
                {preview.before.length === 0 ? (
                  <p className="muted">{t('payrollRuns.policy.nothing')}</p>
                ) : (
                  <Timeline
                    entries={preview.before}
                    caption={t('payrollRuns.policy.before')}
                    testId="statutory-profile-before"
                  />
                )}
                <h4>{t('payrollRuns.policy.after')}</h4>
                {preview.after.length === 0 ? (
                  <p className="muted">{t('payrollRuns.policy.nothing')}</p>
                ) : (
                  <Timeline
                    entries={preview.after}
                    caption={t('payrollRuns.policy.after')}
                    testId="statutory-profile-after"
                  />
                )}
                {preview.blockers.length > 0 && (
                  <div
                    className="error-summary"
                    role="alert"
                    data-testid="statutory-profile-blockers"
                  >
                    <p>{t('statutory.profile.blockersTitle')}</p>
                    <ul>
                      {PROFILE_BLOCKERS.filter((code) => preview.blockers.includes(code)).map(
                        (code) => (
                          <li key={code} data-code={code}>
                            {t(`statutory.profile.blockers.${code}`)}
                          </li>
                        ),
                      )}
                    </ul>
                  </div>
                )}
                {!blocked && preview.expiresAt && (
                  <p className="muted">
                    {t('payrollRuns.preview.expires', {
                      at: formatInstant(i18n.language, preview.expiresAt),
                    })}
                  </p>
                )}
                {!blocked && (
                  <div className="dialog-actions">
                    <button
                      type="button"
                      className="button"
                      data-testid="statutory-profile-confirm"
                      disabled={busy !== null}
                      onClick={() => void onCommit()}
                    >
                      {busy === 'commit' ? t('payrollRuns.saving') : t('statutory.profile.confirm')}
                    </button>
                  </div>
                )}
              </section>
            )}
          </section>
        </>
      )}
    </section>
  );
}

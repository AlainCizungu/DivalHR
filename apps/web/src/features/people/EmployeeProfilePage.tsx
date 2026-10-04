import type { Assignment, EmployeeProfile, EmploymentChange } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { EmployeeContractsSection } from '../contracts/EmployeeContractsSection';
import { AccessLinkSection } from './AccessLinkSection';
import { ChangeForm } from './ChangeForm';
import { ChangesSection } from './ChangesSection';
import { HistoryAlert } from './HistoryAlert';
import {
  formatDate,
  formatPeriod,
  HISTORY_NETWORK_FAILURE,
  historyFailureOf,
  KINDS,
  valueText,
  type HistoryFailure,
} from './history';
import { SeparationsSection } from './SeparationsSection';
import { TimelineSection } from './TimelineSection';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | { kind: 'ready'; profile: EmployeeProfile };

const CURRENT_KEYS = {
  PLACEMENT: 'placement',
  MANAGER: 'manager',
  CONTRACT: 'contract',
  COMPENSATION: 'compensation',
} as const;

/**
 * MVP-021: one employee's profile on the business date (today in the organization's time zone),
 * the effective-dated history, the recorded changes, and the change, correction and cancellation
 * flows; MVP-022 adds the separation and the employee's DivalHR access; MVP-030 the contracts.
 * The employee ID is the only identifier in the URL; nothing else is kept in the URL, history or
 * browser storage, and every request uses cache: 'no-store'.
 */
export function EmployeeProfilePage() {
  const { employeeId = '' } = useParams();
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [correcting, setCorrecting] = useState<Assignment | null>(null);
  const [formKey, setFormKey] = useState(0);
  const [announcement, setAnnouncement] = useState('');
  const heading = useRef<HTMLHeadingElement>(null);
  const focusHeading = useRef(false);

  const load = useCallback(async (): Promise<Loaded> => {
    try {
      const { data, error, response } = await core.GET('/employees/{employeeId}', {
        params: { path: { employeeId } },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', profile: data }
        : { kind: 'failed', failure: historyFailureOf(response, error) };
    } catch {
      return { kind: 'failed', failure: HISTORY_NETWORK_FAILURE };
    }
  }, [core, employeeId]);

  useEffect(() => {
    let active = true;
    void load().then((next) => {
      if (active) setLoaded(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision]);

  useEffect(() => {
    if (focusHeading.current && heading.current) {
      focusHeading.current = false;
      heading.current.focus();
    }
  });

  const recorded = (change: EmploymentChange, messageKey: string) => {
    setAnnouncement(
      t(messageKey, {
        date: formatDate(i18n.language, change.effectiveFrom),
      }),
    );
    setCorrecting(null);
    setFormKey((key) => key + 1);
    setRevision((value) => value + 1);
    focusHeading.current = true;
  };

  const changed = (messageKey: string, values: Record<string, string>, refresh: boolean) => {
    setAnnouncement(t(messageKey, values));
    if (refresh) {
      setCorrecting(null);
      setFormKey((key) => key + 1);
      setRevision((value) => value + 1);
      focusHeading.current = true;
    }
  };

  if (loaded.kind === 'loading') return <p>{t('employees.loading')}</p>;
  if (loaded.kind === 'failed') {
    return (
      <section aria-labelledby={`${ids}-title`}>
        <h1 id={`${ids}-title`}>{t('employees.profile.unavailable')}</h1>
        <HistoryAlert failure={loaded.failure} data-testid="profile-error" />
        <p>
          <Link to="/admin/people">{t('employees.profile.back')}</Link>
        </p>
      </section>
    );
  }
  const { profile } = loaded;
  const name = t('employees.fullName', { given: profile.givenNames, family: profile.familyName });

  return (
    <section aria-labelledby={`${ids}-title`}>
      <p>
        <Link to="/admin/people">{t('employees.profile.back')}</Link>
      </p>
      <h1 id={`${ids}-title`} tabIndex={-1} ref={heading}>
        {name}
      </h1>
      <p role="status" data-testid="announcer">
        {announcement}
      </p>
      <dl className="summary" data-testid="profile-summary">
        <dt>{t('employees.fields.employeeNumber')}</dt>
        <dd>{profile.employeeNumber}</dd>
        <dt>{t('employees.profile.employment')}</dt>
        <dd>
          {formatPeriod(t, i18n.language, profile.employment.startDate, profile.employment.endDate)}{' '}
          ({t(`employees.employmentStatus.${profile.employment.status}`)})
        </dd>
        <dt>{t('employees.profile.businessDate')}</dt>
        <dd>{formatDate(i18n.language, profile.businessDate)}</dd>
      </dl>

      <section aria-labelledby={`${ids}-current`} className="card" data-testid="current">
        <h2 id={`${ids}-current`}>{t('employees.profile.current')}</h2>
        <dl className="summary">
          {KINDS.map((kind) => {
            const row = profile.current[CURRENT_KEYS[kind]];
            return (
              <div key={kind}>
                <dt>{t(`employees.kinds.${kind}`)}</dt>
                <dd data-testid={`current-${kind}`}>
                  {row
                    ? `${valueText(t, row)} (${formatPeriod(t, i18n.language, row.effectiveFrom, row.effectiveTo)})`
                    : t(`employees.profile.unset.${kind}`)}
                </dd>
              </div>
            );
          })}
        </dl>
      </section>

      <ChangeForm
        key={correcting ? `correct-${correcting.id}` : `change-${formKey}`}
        employeeId={profile.id}
        businessDate={profile.businessDate}
        target={correcting}
        onClose={
          correcting
            ? () => {
                setCorrecting(null);
              }
            : undefined
        }
        onRecorded={(change) => {
          recorded(
            change,
            change.type === 'CORRECTION'
              ? 'employees.announce.corrected'
              : 'employees.announce.recorded',
          );
        }}
      />
      <SeparationsSection
        employeeId={profile.id}
        businessDate={profile.businessDate}
        revision={revision}
        onChanged={changed}
      />
      <EmployeeContractsSection
        employeeId={profile.id}
        businessDate={profile.businessDate}
        revision={revision}
        onChanged={changed}
      />
      <AccessLinkSection
        employeeId={profile.id}
        revision={revision}
        onChanged={(messageKey) => {
          changed(messageKey, {}, false);
        }}
      />
      <TimelineSection employeeId={profile.id} revision={revision} onCorrect={setCorrecting} />
      <ChangesSection
        employeeId={profile.id}
        businessDate={profile.businessDate}
        revision={revision}
        onCancelled={(change) => {
          recorded(change, 'employees.announce.cancelled');
        }}
      />
    </section>
  );
}

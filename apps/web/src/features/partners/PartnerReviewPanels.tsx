import type {
  PartnerApplicationCheck,
  PartnerApplicationDetail,
  PartnerApplicationStatus,
  PartnerCheckResult,
  PartnerReasonCode,
} from '@divalhr/api-client';
import { useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { ScrollRegion } from '../people/ScrollRegion';
import { ApprovalBoundary, PartnerAlert } from './PartnerBits';
import {
  CHECK_RESULTS,
  CONFIRMED_TARGETS,
  NETWORK_FAILURE,
  NOTE_MAX,
  REASONS,
  isRegulated,
  partnerFailureOf,
  type PartnerFailure,
} from './partners';

/**
 * Review panels. Internal notes live only in component state while typed: never in a URL, in
 * analytics, in browser storage or in a log, and the server never returns one in an idempotent
 * response body. Each command carries the version the reviewer saw; a stale version is refused.
 */

function CheckRow({
  application,
  check,
  onChanged,
}: {
  application: PartnerApplicationDetail;
  check: PartnerApplicationCheck;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [result, setResult] = useState<PartnerCheckResult>(check.result);
  const [note, setNote] = useState('');
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const closed = ['APPROVED_FOR_ONBOARDING', 'DECLINED', 'WITHDRAWN'].includes(application.status);
  const regulatedLocked =
    check.checkCode === 'REGULATORY_INFORMATION' && isRegulated(application.primaryCategory);

  const save = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = {
      result,
      expectedVersion: check.version,
      note: note.trim() === '' ? null : note.trim(),
    };
    try {
      const { data, error, response } = await core.PUT(
        '/platform/partner-applications/{reference}/checklist/{checkCode}',
        {
          params: {
            path: { reference: application.reference, checkCode: check.checkCode },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data !== undefined || response.ok) {
        idempotency.reset();
        setNote('');
        onChanged();
        return;
      }
      setFailure(partnerFailureOf(response, error));
      requestAnimationFrame(() => alertRef.current?.focus());
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <li className="card partner-check" data-testid="partner-check" data-check={check.checkCode}>
      <h3>
        {t(`partners.check.${check.checkCode}.title`)}{' '}
        <span className="muted">
          ({t(check.mandatory ? 'partners.review.mandatory' : 'partners.review.advisory')})
        </span>
      </h3>
      <p className="muted">{t(`partners.check.${check.checkCode}.help`)}</p>
      <p>
        <b>{t('partners.review.currentResult')}</b>{' '}
        <span data-testid="partner-check-result">{t(`partners.result.${check.result}`)}</span>
      </p>
      {check.note && (
        <p data-testid="partner-check-note">
          <b>{t('partners.review.currentNote')}</b> {check.note}
        </p>
      )}
      {!closed && (
        <form noValidate onSubmit={(event) => void save(event)} aria-busy={busy}>
          <div className="field">
            <label htmlFor={`${ids}-result`}>{t('partners.review.result')}</label>
            <select
              id={`${ids}-result`}
              value={result}
              onChange={(event) => {
                setResult(event.target.value as PartnerCheckResult);
              }}
            >
              {CHECK_RESULTS.filter((r) => !(regulatedLocked && r === 'NOT_APPLICABLE')).map(
                (r) => (
                  <option key={r} value={r}>
                    {t(`partners.result.${r}`)}
                  </option>
                ),
              )}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-note`}>{t('partners.review.note')}</label>
            <p id={`${ids}-note-help`} className="field__help">
              {t('partners.review.noteHelp', { max: NOTE_MAX })}
            </p>
            <textarea
              id={`${ids}-note`}
              rows={3}
              maxLength={NOTE_MAX}
              autoComplete="off"
              value={note}
              aria-describedby={`${ids}-note-help`}
              onChange={(event) => {
                setNote(event.target.value);
              }}
            />
          </div>
          <button type="submit" className="button" disabled={busy}>
            {t('partners.review.saveCheck')}
          </button>
        </form>
      )}
      {failure && (
        <PartnerAlert failure={failure} testId="partner-check-failure" alertRef={alertRef} />
      )}
    </li>
  );
}

export function ChecklistPanel({
  application,
  onChanged,
}: {
  application: PartnerApplicationDetail;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  return (
    <section aria-labelledby="partner-checklist-title" data-testid="partner-checklist">
      <h2 id="partner-checklist-title">{t('partners.review.checklist')}</h2>
      <p className="muted">{t('partners.review.checklistHelp')}</p>
      <ul className="plain-list">
        {application.checklist.map((check) => (
          <CheckRow
            key={check.checkCode}
            application={application}
            check={check}
            onChanged={onChanged}
          />
        ))}
      </ul>
    </section>
  );
}

export function TransitionPanel({
  application,
  onChanged,
}: {
  application: PartnerApplicationDetail;
  onChanged: () => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const idempotency = useIdempotencyKey();
  const [target, setTarget] = useState<PartnerApplicationStatus | null>(null);
  const [reason, setReason] = useState<PartnerReasonCode | ''>('');
  const [confirmed, setConfirmed] = useState(false);
  const [failure, setFailure] = useState<PartnerFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const alertRef = useRef<HTMLDivElement>(null);
  const blocked = application.approvalBlockedBy;

  const choose = (next: PartnerApplicationStatus) => {
    setTarget(next);
    const only = REASONS[next];
    setReason(only?.length === 1 ? (only[0] ?? '') : '');
    setConfirmed(false);
    setFailure(null);
  };

  const submit = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (target === null || busyRef.current) return;
    const reasons = REASONS[target];
    if (reasons && reason === '') {
      setFailure({ kind: 'invalid', messageKey: 'partners.review.reasonRequired' });
      return;
    }
    if (CONFIRMED_TARGETS.has(target) && !confirmed) {
      setFailure({ kind: 'invalid', messageKey: 'partners.review.confirmRequired' });
      return;
    }
    busyRef.current = true;
    setBusy(true);
    setFailure(null);
    const body = {
      toStatus: target,
      expectedVersion: application.version,
      reasonCode: reasons ? (reason as PartnerReasonCode) : null,
      confirmed: CONFIRMED_TARGETS.has(target),
    };
    try {
      const { data, error, response } = await core.POST(
        '/platform/partner-applications/{reference}:transition',
        {
          params: {
            path: { reference: application.reference },
            header: { 'Idempotency-Key': idempotency.keyFor(body) },
          },
          body,
        },
      );
      if (data) {
        idempotency.reset();
        setTarget(null);
        onChanged();
        return;
      }
      setFailure(partnerFailureOf(response, error));
      requestAnimationFrame(() => alertRef.current?.focus());
    } catch {
      setFailure(NETWORK_FAILURE);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const options = application.allowedTransitions;
  if (options.length === 0) {
    return (
      <section aria-labelledby={`${ids}-title`} data-testid="partner-transitions">
        <h2 id={`${ids}-title`}>{t('partners.review.transitions')}</h2>
        <p data-testid="partner-terminal">{t('errors.PARTNER_APPLICATION_TERMINAL')}</p>
      </section>
    );
  }

  const reasons = target ? REASONS[target] : undefined;
  return (
    <section aria-labelledby={`${ids}-title`} data-testid="partner-transitions">
      <h2 id={`${ids}-title`}>{t('partners.review.transitions')}</h2>
      {blocked.length > 0 && options.includes('APPROVED_FOR_ONBOARDING') && (
        <p className="card" data-testid="partner-approval-blocked">
          {t('partners.review.approvalBlocked')}{' '}
          {blocked.map((code) => t(`partners.check.${code}.title`)).join(', ')}
        </p>
      )}
      <div className="button-row" role="group" aria-label={t('partners.review.transitions')}>
        {options.map((option) => (
          <button
            key={option}
            type="button"
            className={`button${target === option ? '' : ' button--secondary'}`}
            aria-pressed={target === option}
            disabled={option === 'APPROVED_FOR_ONBOARDING' && blocked.length > 0}
            data-testid={`partner-transition-${option}`}
            onClick={() => {
              choose(option);
            }}
          >
            {t(`partners.transition.${option}`)}
          </button>
        ))}
      </div>
      {target && (
        <form
          noValidate
          onSubmit={(event) => void submit(event)}
          aria-busy={busy}
          data-testid="partner-transition-form"
        >
          {target === 'APPROVED_FOR_ONBOARDING' && (
            <ApprovalBoundary testId="partner-approval-warning" />
          )}
          {reasons && (
            <div className="field">
              <label htmlFor={`${ids}-reason`}>{t('partners.review.reason')}</label>
              <select
                id={`${ids}-reason`}
                value={reason}
                required
                onChange={(event) => {
                  setReason(event.target.value as PartnerReasonCode);
                }}
              >
                {reasons.length > 1 && <option value="">{t('partners.apply.select')}</option>}
                {reasons.map((code) => (
                  <option key={code} value={code}>
                    {t(`partners.reason.${code}`)}
                  </option>
                ))}
              </select>
            </div>
          )}
          {CONFIRMED_TARGETS.has(target) && (
            <label className="choice">
              <input
                type="checkbox"
                checked={confirmed}
                onChange={(event) => {
                  setConfirmed(event.target.checked);
                }}
              />
              {t(`partners.review.confirm.${target}`)}
            </label>
          )}
          <button type="submit" className="button" disabled={busy}>
            {t('partners.review.apply')}
          </button>
        </form>
      )}
      {failure && (
        <PartnerAlert failure={failure} testId="partner-transition-failure" alertRef={alertRef} />
      )}
    </section>
  );
}

export function ScrollTable({ label, children }: { label: string; children: React.ReactNode }) {
  return <ScrollRegion label={label}>{children}</ScrollRegion>;
}

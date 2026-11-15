import type { StatutoryPackVersionDetail } from '@divalhr/api-client';
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import { ISO_DATE, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import {
  commandsFor,
  needsConfirmation,
  NOTE_MAX,
  packFailureOf,
  REJECT_REASONS,
  RETIRE_REASONS,
  type PackCommand,
} from './statutoryPacks';

interface Body {
  expectedVersion: number;
  confirmed?: boolean;
  reasonCode?: string;
  note?: string;
  effectiveFrom?: string;
}

/**
 * The commands the current state allows, each with the confirmation its consequence needs. The
 * server stays the judge of every one: a self-approval, a stale version or an invalid transition is
 * refused before any state changes, and the refusal is shown here as a finished sentence.
 */
export function PackActions({
  detail,
  onDone,
}: {
  detail: StatutoryPackVersionDetail;
  onDone: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const { summary } = detail;
  const commands = commandsFor(summary.state, detail.submittedAt !== null);
  const [active, setActive] = useState<PackCommand | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [reasonCode, setReasonCode] = useState('');
  const [note, setNote] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);

  if (commands.length === 0) {
    return (
      <section className="card" data-testid="statutory-pack-actions">
        <h2>{t('statutoryPacks.actions.title')}</h2>
        <p className="muted">{t('statutoryPacks.actions.none')}</p>
      </section>
    );
  }

  const reasons = active === 'reject' ? REJECT_REASONS : RETIRE_REASONS;
  const ready =
    active !== null &&
    (!needsConfirmation(active) || confirmed) &&
    (active !== 'reject' && active !== 'retire' ? true : reasonCode !== '') &&
    (active !== 'schedule' || ISO_DATE.test(effectiveFrom));

  const choose = (command: PackCommand) => {
    setActive(command);
    setConfirmed(false);
    setReasonCode('');
    setNote('');
    setEffectiveFrom('');
    setFailure(null);
    key.reset();
  };

  const body = (command: PackCommand): Body => {
    const result: Body = { expectedVersion: summary.rowVersion };
    if (needsConfirmation(command)) result.confirmed = true;
    if (command === 'reject' || command === 'retire') result.reasonCode = reasonCode;
    if (command === 'reject' && note.trim() !== '') result.note = note.trim();
    if (command === 'schedule') result.effectiveFrom = effectiveFrom;
    return result;
  };

  const send = async (command: PackCommand) => {
    const payload = body(command);
    const options = {
      params: {
        path: { packCode: summary.packCode, version: String(summary.version) },
        header: {
          'Idempotency-Key': key.keyFor({
            code: summary.packCode,
            version: summary.version,
            command,
            payload,
          }),
        },
      },
      body: payload,
      cache: 'no-store' as const,
    };
    switch (command) {
      case 'validate':
        return core.POST(
          '/platform/statutory-packs/{packCode}/versions/{version}:validate',
          options,
        );
      case 'submit-review':
        return core.POST(
          '/platform/statutory-packs/{packCode}/versions/{version}:submit-review',
          options,
        );
      case 'approve':
        return core.POST(
          '/platform/statutory-packs/{packCode}/versions/{version}:approve',
          options,
        );
      case 'reject':
        return core.POST('/platform/statutory-packs/{packCode}/versions/{version}:reject', options);
      case 'schedule':
        return core.POST(
          '/platform/statutory-packs/{packCode}/versions/{version}:schedule',
          options,
        );
      case 'activate':
        return core.POST(
          '/platform/statutory-packs/{packCode}/versions/{version}:activate',
          options,
        );
      default:
        return core.POST('/platform/statutory-packs/{packCode}/versions/{version}:retire', options);
    }
  };

  const submit = async () => {
    if (active === null || busy || !ready) return;
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await send(active);
      if (data) {
        const command = active;
        setActive(null);
        key.reset();
        onDone(
          command === 'validate' && data.validationOutcome === 'FAILED'
            ? t('statutoryPacks.actions.announce.validateFailed')
            : t(`statutoryPacks.actions.announce.${command}`),
        );
        return;
      }
      setFailure(packFailureOf(response, error));
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="card" aria-labelledby={`${ids}-title`} data-testid="statutory-pack-actions">
      <h2 id={`${ids}-title`}>{t('statutoryPacks.actions.title')}</h2>
      {summary.state === 'VALIDATED' && detail.submittedByCaller && (
        <p className="muted" data-testid="statutory-pack-self-hint">
          {t('statutoryPacks.actions.selfHint')}
        </p>
      )}
      <div className="button-row" role="group" aria-label={t('statutoryPacks.actions.choose')}>
        {commands.map((command) => (
          <button
            key={command}
            type="button"
            className={`button ${active === command ? 'button--primary' : 'button--secondary'}`}
            aria-pressed={active === command}
            data-testid={`statutory-pack-action-${command}`}
            onClick={() => {
              choose(command);
            }}
          >
            {t(`statutoryPacks.actions.command.${command}`)}
          </button>
        ))}
      </div>
      {active && (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit();
          }}
          data-testid="statutory-pack-action-form"
          data-command={active}
        >
          <p>{t(`statutoryPacks.actions.help.${active}`)}</p>
          {(active === 'reject' || active === 'retire') && (
            <div className="field">
              <label htmlFor={`${ids}-reason`}>{t('statutoryPacks.actions.reason')}</label>
              <select
                id={`${ids}-reason`}
                value={reasonCode}
                data-testid="statutory-pack-reason"
                onChange={(event) => {
                  setReasonCode(event.target.value);
                  key.reset();
                }}
              >
                <option value="">{t('statutoryPacks.actions.reasonChoose')}</option>
                {reasons.map((reason) => (
                  <option key={reason} value={reason}>
                    {t(`statutoryPacks.reason.${reason}`)}
                  </option>
                ))}
              </select>
            </div>
          )}
          {active === 'reject' && (
            <div className="field">
              <label htmlFor={`${ids}-note`}>{t('statutoryPacks.actions.note')}</label>
              <textarea
                id={`${ids}-note`}
                value={note}
                maxLength={NOTE_MAX}
                rows={3}
                aria-describedby={`${ids}-note-help`}
                data-testid="statutory-pack-note"
                onChange={(event) => {
                  setNote(event.target.value);
                  key.reset();
                }}
              />
              <p id={`${ids}-note-help`} className="muted">
                {t('statutoryPacks.actions.noteHelp', { max: NOTE_MAX })}
              </p>
            </div>
          )}
          {active === 'schedule' && (
            <div className="field">
              <label htmlFor={`${ids}-date`}>{t('statutoryPacks.actions.effectiveFrom')}</label>
              <input
                id={`${ids}-date`}
                type="date"
                value={effectiveFrom}
                aria-describedby={`${ids}-date-help`}
                data-testid="statutory-pack-effective-from"
                onChange={(event) => {
                  setEffectiveFrom(event.target.value);
                  key.reset();
                }}
              />
              <p id={`${ids}-date-help`} className="muted">
                {t('statutoryPacks.actions.effectiveHelp')}
              </p>
            </div>
          )}
          {needsConfirmation(active) && (
            <div className="field field--checkbox">
              <input
                id={`${ids}-confirm`}
                type="checkbox"
                checked={confirmed}
                data-testid="statutory-pack-confirm"
                onChange={(event) => {
                  setConfirmed(event.target.checked);
                  key.reset();
                }}
              />
              <label htmlFor={`${ids}-confirm`}>
                {t(`statutoryPacks.actions.confirm.${active}`)}
              </label>
            </div>
          )}
          <button
            type="submit"
            className="button button--primary"
            disabled={busy || !ready}
            aria-describedby={ready ? undefined : `${ids}-why`}
            data-testid="statutory-pack-submit"
          >
            {t(`statutoryPacks.actions.command.${active}`)}
          </button>
          {!ready && (
            <p id={`${ids}-why`} className="muted">
              {t('statutoryPacks.actions.why')}
            </p>
          )}
        </form>
      )}
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="statutory-pack-action-failure"
          alertRef={focus.register('failure')}
        />
      )}
    </section>
  );
}

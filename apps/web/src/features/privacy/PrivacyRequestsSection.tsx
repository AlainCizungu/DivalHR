import type { PlatformPrivacyRequest, PrivacyRequestState } from '@divalhr/api-client';
import { useEffect, useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PrivacyAlert, RequestState } from './PrivacyBits';
import {
  DECISION_REASONS,
  MESSAGE_MAX,
  NEXT_STATES,
  NOTE_MAX,
  REASONS_REQUIRED,
  formatInstant,
  privacyFailureOf,
  useCommand,
} from './privacy';

type Reason = (typeof DECISION_REASONS)[number];

function Decision({ request, onDone }: { request: PlatformPrivacyRequest; onDone: () => void }) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const options = NEXT_STATES[request.state];
  const [to, setTo] = useState<PrivacyRequestState>(options[0] ?? 'UNDER_REVIEW');
  const [reason, setReason] = useState<Reason | ''>('');
  const [message, setMessage] = useState('');
  const [note, setNote] = useState('');
  const move = useCommand();
  const key = useIdempotencyKey();

  const submit = async (event: SyntheticEvent) => {
    event.preventDefault();
    const body = {
      expectedVersion: request.version,
      toState: to,
      reasonCode: reason === '' ? null : reason,
      employeeMessage: message.trim() === '' ? null : message.trim(),
      internalNote: note.trim() === '' ? null : note.trim(),
    };
    const done = await move.run(() =>
      core.POST('/platform/privacy/requests/{requestId}/transition', {
        params: {
          path: { requestId: request.id },
          header: { 'Idempotency-Key': key.keyFor({ id: request.id, ...body }) },
        },
        body,
      }),
    );
    if (done) {
      key.reset();
      onDone();
    }
  };

  return (
    <form
      className="card"
      onSubmit={(event) => void submit(event)}
      data-testid="privacy-decision-form"
    >
      <div className="field">
        <label htmlFor={`${ids}-to`}>{t('privacy.requests.moveTo')}</label>
        <select
          id={`${ids}-to`}
          value={to}
          onChange={(event) => {
            setTo(event.target.value as PrivacyRequestState);
          }}
        >
          {options.map((s) => (
            <option key={s} value={s}>
              {t(`privacy.state.${s}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${ids}-reason`}>
          {t('privacy.requests.reason')}
          {REASONS_REQUIRED.has(to) ? '' : ` (${t('privacy.optional')})`}
        </label>
        <select
          id={`${ids}-reason`}
          value={reason}
          onChange={(event) => {
            setReason(event.target.value as Reason | '');
          }}
        >
          <option value="">{t('privacy.requests.noReason')}</option>
          {DECISION_REASONS.map((r) => (
            <option key={r} value={r}>
              {t(`privacy.reason.${r}`)}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor={`${ids}-message`}>{t('privacy.requests.employeeMessage')}</label>
        <textarea
          id={`${ids}-message`}
          value={message}
          maxLength={MESSAGE_MAX}
          onChange={(event) => {
            setMessage(event.target.value);
          }}
        />
      </div>
      <div className="field">
        <label htmlFor={`${ids}-note`}>{t('privacy.requests.internalNote')}</label>
        <textarea
          id={`${ids}-note`}
          value={note}
          maxLength={NOTE_MAX}
          onChange={(event) => {
            setNote(event.target.value);
          }}
        />
        <p className="muted">{t('privacy.requests.internalNoteHelp')}</p>
      </div>
      <PrivacyAlert failure={move.failure} testId="privacy-decision-failure" />
      <div className="button-row">
        <button type="submit" className="button" disabled={move.working}>
          {t('privacy.requests.decide')}
        </button>
      </div>
    </form>
  );
}

/**
 * The employees' data-rights requests as platform privacy operations see them: masked contact, no
 * private choice and no note text, only whether a note exists. Tenant administrators never see
 * this queue.
 */
export function PrivacyRequestsSection() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [items, setItems] = useState<PlatformPrivacyRequest[]>([]);
  const [reload, setReload] = useState(0);
  const [failure, setFailure] = useState<ReturnType<typeof privacyFailureOf> | null>(null);

  useEffect(() => {
    let active = true;
    core
      .GET('/platform/privacy/requests', { params: { query: { limit: 50 } }, cache: 'no-store' })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setItems(data.items);
            setFailure(null);
          } else {
            setFailure(privacyFailureOf(response, error));
          }
        },
        () => undefined,
      );
    return () => {
      active = false;
    };
  }, [core, reload]);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="privacy-requests">
      <h2 id={`${ids}-title`}>{t('privacy.requests.title')}</h2>
      <PrivacyAlert failure={failure} testId="privacy-requests-failure" />
      {items.length === 0 ? (
        <p className="muted" data-testid="privacy-requests-empty">
          {t('privacy.requests.empty')}
        </p>
      ) : (
        <ul className="partner-catalogue" data-testid="privacy-requests-list">
          {items.map((request) => (
            <li
              key={request.id}
              className="card"
              data-testid="privacy-request"
              data-reference={request.id}
              data-state={request.state}
            >
              <h3>
                {t(`privacy.type.${request.requestType}`)} · {request.id}
              </h3>
              <p>
                <RequestState state={request.state} />{' '}
                <span className="muted">{formatInstant(request.submittedAt, i18n.language)}</span>
              </p>
              <dl className="detail-list">
                <dt>{t('privacy.requests.contact')}</dt>
                <dd data-testid="privacy-request-contact">{request.contactMasked}</dd>
                {request.referralId && (
                  <>
                    <dt>{t('privacy.my.referral')}</dt>
                    <dd>{request.referralId}</dd>
                  </>
                )}
                <dt>{t('privacy.requests.note')}</dt>
                <dd>
                  {request.notePresent
                    ? t('privacy.requests.noteYes')
                    : t('privacy.requests.noteNo')}
                </dd>
              </dl>
              {NEXT_STATES[request.state].length > 0 && (
                <Decision
                  request={request}
                  onDone={() => {
                    setReload((n) => n + 1);
                  }}
                />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

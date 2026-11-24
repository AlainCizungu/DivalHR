import type {
  PartnerReferralSummary,
  PrivacyRequestType,
  PrivacyRequestView,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { PageHeader } from '../../ui/primitives';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { PartnerAlert } from '../partners/PartnerBits';
import { NETWORK_FAILURE, type PartnerFailure } from '../partners/partners';
import { PrivacyAlert, RequestState } from './PrivacyBits';
import { OPEN_STATES, REQUEST_TYPES, formatInstant, privacyFailureOf, useCommand } from './privacy';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PartnerFailure }
  | { kind: 'ready'; items: PrivacyRequestView[] };

/**
 * LAUNCH-001: My space → Privacy requests. The employee asks for access, erasure, restriction or
 * objection about their own partner-referral data, follows the decision and downloads their own
 * access package. Only the employee can see these requests: tenant administrators cannot, and the
 * browser keeps nothing in storage.
 */
export function MyPrivacyPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [referrals, setReferrals] = useState<PartnerReferralSummary[]>([]);
  const [type, setType] = useState<PrivacyRequestType>('ACCESS');
  const [referral, setReferral] = useState('');
  const [reload, setReload] = useState(0);
  const create = useCommand();
  const act = useCommand();
  const key = useIdempotencyKey();
  const cancelKey = useIdempotencyKey();

  useEffect(() => {
    let active = true;
    core.GET('/me/privacy/requests', { params: { query: { limit: 50 } }, cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (!active) return;
        setLoad(
          data
            ? { kind: 'ready', items: data.items }
            : { kind: 'failed', failure: privacyFailureOf(response, error) },
        );
      },
      () => {
        if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
      },
    );
    return () => {
      active = false;
    };
  }, [core, reload]);

  useEffect(() => {
    let active = true;
    core.GET('/me/partner-referrals', { params: { query: { limit: 50 } }, cache: 'no-store' }).then(
      ({ data }) => {
        if (active && data) setReferrals(data.items);
      },
      () => undefined,
    );
    return () => {
      active = false;
    };
  }, [core]);

  const submit = async (event: SyntheticEvent) => {
    event.preventDefault();
    const body = { requestType: type, referralId: referral === '' ? null : referral };
    const created = await create.run(() =>
      core.POST('/me/privacy/requests', {
        params: { header: { 'Idempotency-Key': key.keyFor(body) } },
        body,
      }),
    );
    if (created) {
      key.reset();
      setReload((n) => n + 1);
    }
  };

  const cancel = useCallback(
    async (request: PrivacyRequestView) => {
      const body = { expectedVersion: request.version };
      const done = await act.run(() =>
        core.POST('/me/privacy/requests/{requestId}/cancel', {
          params: {
            path: { requestId: request.id },
            header: { 'Idempotency-Key': cancelKey.keyFor({ id: request.id, ...body }) },
          },
          body,
        }),
      );
      if (done) {
        cancelKey.reset();
        setReload((n) => n + 1);
      }
    },
    [act, cancelKey, core],
  );

  const download = useCallback(
    async (request: PrivacyRequestView) => {
      const blob = await act.run(async () => {
        const result = await core.GET('/me/privacy/requests/{requestId}/export', {
          params: { path: { requestId: request.id } },
          parseAs: 'blob',
          cache: 'no-store',
        });
        return { data: result.data, error: result.error, response: result.response };
      });
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'divalhr-privacy-access-package.json';
      anchor.click();
      URL.revokeObjectURL(url);
    },
    [act, core],
  );

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="my-privacy-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('privacy.my.title')}
        description={t('privacy.my.description')}
      />
      <p className="card" role="note" data-testid="my-privacy-private">
        {t('privacy.my.private')}
      </p>
      <form
        className="card"
        aria-labelledby={`${ids}-new`}
        onSubmit={(event) => void submit(event)}
        data-testid="my-privacy-form"
      >
        <h2 id={`${ids}-new`}>{t('privacy.my.new')}</h2>
        <div className="field">
          <label htmlFor={`${ids}-type`}>{t('privacy.my.type')}</label>
          <select
            id={`${ids}-type`}
            value={type}
            onChange={(event) => {
              setType(event.target.value as PrivacyRequestType);
            }}
          >
            {REQUEST_TYPES.map((r) => (
              <option key={r} value={r}>
                {t(`privacy.type.${r}`)}
              </option>
            ))}
          </select>
          <p className="muted">{t(`privacy.typeHelp.${type}`)}</p>
        </div>
        <div className="field">
          <label htmlFor={`${ids}-referral`}>{t('privacy.my.referral')}</label>
          <select
            id={`${ids}-referral`}
            value={referral}
            onChange={(event) => {
              setReferral(event.target.value);
            }}
          >
            <option value="">{t('privacy.my.allReferrals')}</option>
            {referrals.map((r) => (
              <option key={r.referralId} value={r.referralId}>
                {r.referralId} · {r.partnerDisplayName}
              </option>
            ))}
          </select>
        </div>
        <PrivacyAlert failure={create.failure} testId="my-privacy-create-failure" />
        <div className="button-row">
          <button type="submit" className="button" disabled={create.working}>
            {t('privacy.my.submit')}
          </button>
        </div>
      </form>
      <PrivacyAlert failure={act.failure} testId="my-privacy-action-failure" />
      {load.kind === 'loading' && (
        <p role="status" data-testid="my-privacy-loading">
          {t('privacy.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PartnerAlert failure={load.failure} testId="my-privacy-failure" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="my-privacy-empty">
            {t('privacy.my.empty')}
          </p>
        ) : (
          <ul className="partner-catalogue" data-testid="my-privacy-list">
            {load.items.map((item) => (
              <li
                key={item.id}
                className="card"
                data-testid="my-privacy-request"
                data-reference={item.id}
                data-state={item.state}
              >
                <h2>{t(`privacy.type.${item.requestType}`)}</h2>
                <p>
                  <RequestState state={item.state} />{' '}
                  <span className="muted">{formatInstant(item.submittedAt, i18n.language)}</span>
                </p>
                <dl className="detail-list">
                  <dt>{t('privacy.my.reference')}</dt>
                  <dd>{item.id}</dd>
                  {item.referralId && (
                    <>
                      <dt>{t('privacy.my.referral')}</dt>
                      <dd>{item.referralId}</dd>
                    </>
                  )}
                  {item.decisionReason && (
                    <>
                      <dt>{t('privacy.my.reason')}</dt>
                      <dd data-testid="my-privacy-reason">
                        {t(`privacy.reason.${item.decisionReason}`)}
                      </dd>
                    </>
                  )}
                  {item.employeeMessage && (
                    <>
                      <dt>{t('privacy.my.message')}</dt>
                      <dd data-testid="my-privacy-message">{item.employeeMessage}</dd>
                    </>
                  )}
                </dl>
                <div className="button-row">
                  {item.exportAvailable && (
                    <button
                      type="button"
                      className="button"
                      disabled={act.working}
                      data-testid="my-privacy-download"
                      onClick={() => void download(item)}
                    >
                      {t('privacy.my.download')}
                      <span className="visually-hidden"> — {item.id}</span>
                    </button>
                  )}
                  {OPEN_STATES.has(item.state) &&
                    (item.state === 'SUBMITTED' ||
                      item.state === 'UNDER_REVIEW' ||
                      item.state === 'MORE_INFORMATION_REQUIRED') && (
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={act.working}
                        data-testid="my-privacy-cancel"
                        onClick={() => void cancel(item)}
                      >
                        {t('privacy.my.cancel')}
                        <span className="visually-hidden"> — {item.id}</span>
                      </button>
                    )}
                </div>
              </li>
            ))}
          </ul>
        ))}
    </section>
  );
}

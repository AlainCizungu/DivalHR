import type { AccessLinkCandidate, EmployeeAccess } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { HistoryAlert } from './HistoryAlert';
import { HISTORY_NETWORK_FAILURE, historyFailureOf, type HistoryFailure } from './history';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: HistoryFailure }
  | { kind: 'ready'; access: EmployeeAccess };

const MAX_EMAIL = 254;

/**
 * MVP-022: the employee's DivalHR access. An administrator links the employee to the membership
 * holding an exact address (looked up, never listed or suggested), or removes the link. A
 * separation revokes the linked access. The address is sent only in a POST body, is never put in
 * the URL, history or browser storage, and the field is cleared once a link is created.
 */
export function AccessLinkSection({
  employeeId,
  revision,
  onChanged,
}: {
  employeeId: string;
  revision: number;
  onChanged: (messageKey: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const linkKey = useIdempotencyKey();
  const removeKey = useIdempotencyKey();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [email, setEmail] = useState('');
  const [emailProblem, setEmailProblem] = useState(false);
  const [candidate, setCandidate] = useState<AccessLinkCandidate | null>(null);
  const [failure, setFailure] = useState<HistoryFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const failureBox = useRef<HTMLDivElement>(null);
  const focusFailure = useRef(false);

  useEffect(() => {
    if (focusFailure.current && failureBox.current) {
      focusFailure.current = false;
      failureBox.current.focus();
    }
  });

  const load = useCallback(async (): Promise<Loaded> => {
    try {
      const { data, error, response } = await core.GET('/employees/{employeeId}/access-link', {
        params: { path: { employeeId } },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', access: data }
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

  const refused = (next: HistoryFailure) => {
    setFailure(next);
    focusFailure.current = true;
  };

  const lookup = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (busy) return;
    const address = email.trim();
    if (address.length === 0 || address.length > MAX_EMAIL || !address.includes('@')) {
      setEmailProblem(true);
      return;
    }
    setEmailProblem(false);
    setBusy(true);
    setFailure(null);
    setCandidate(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/access-link/lookup',
        { params: { path: { employeeId } }, body: { email: address }, cache: 'no-store' },
      );
      if (data) {
        linkKey.reset();
        setCandidate(data);
      } else {
        refused(historyFailureOf(response, error));
      }
    } catch {
      refused(HISTORY_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const link = async () => {
    if (!candidate?.linkable || busy) return;
    const body = { membershipId: candidate.membershipId };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/employees/{employeeId}/access-link', {
        params: {
          path: { employeeId },
          header: { 'Idempotency-Key': linkKey.keyFor({ employeeId, ...body }) },
        },
        body,
        cache: 'no-store',
      });
      if (data) {
        linkKey.reset();
        setEmail('');
        setCandidate(null);
        setLoaded({ kind: 'ready', access: data });
        onChanged('accessLink.announce.linked');
      } else {
        refused(historyFailureOf(response, error));
      }
    } catch {
      refused(HISTORY_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (loaded.kind !== 'ready' || !loaded.access.link || busy) return;
    const body = { linkId: loaded.access.link.id, expectedVersion: loaded.access.link.version };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/access-link/remove',
        {
          params: {
            path: { employeeId },
            header: { 'Idempotency-Key': removeKey.keyFor({ employeeId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        removeKey.reset();
        setLoaded({ kind: 'ready', access: data });
        onChanged('accessLink.announce.removed');
      } else {
        const next = historyFailureOf(response, error);
        if (next.code === 'ACCESS_LINK_VERSION_CONFLICT') removeKey.reset();
        refused(next);
      }
    } catch {
      refused(HISTORY_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const formatDateTime = (value: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeStyle: 'short' }).format(
      new Date(value),
    );

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="access-link">
      <h2 id={`${ids}-title`}>{t('accessLink.title')}</h2>
      {loaded.kind === 'loading' && <p>{t('employees.loading')}</p>}
      {loaded.kind === 'failed' && (
        <>
          <HistoryAlert failure={loaded.failure} data-testid="access-link-error" />
          <button
            type="button"
            className="button"
            onClick={() => {
              setLoaded({ kind: 'loading' });
              void load().then(setLoaded);
            }}
          >
            {t('employees.retry')}
          </button>
        </>
      )}
      {loaded.kind === 'ready' && (
        <>
          <p data-testid="access-link-state" data-state={loaded.access.state}>
            {t(`accessLink.state.${loaded.access.state}`)}
          </p>
          {loaded.access.link && (
            <dl className="summary">
              <dt>{t('accessLink.role')}</dt>
              <dd>{t(`users.form.roles.${loaded.access.link.role}`)}</dd>
              <dt>{t('accessLink.linkedAt')}</dt>
              <dd>{formatDateTime(loaded.access.link.linkedAt)}</dd>
            </dl>
          )}
          {loaded.access.state === 'ACTIVE' && loaded.access.link && (
            <div className="actions">
              <button
                type="button"
                className="button button--secondary"
                disabled={busy}
                onClick={() => void remove()}
              >
                {t('accessLink.remove')}
              </button>
            </div>
          )}
          {loaded.access.state === 'NOT_LINKED' && (
            <form noValidate onSubmit={(event) => void lookup(event)}>
              <div className="field">
                <label htmlFor={`${ids}-email`}>{t('accessLink.email')}</label>
                <p id={`${ids}-email-help`} className="field__help">
                  {t('accessLink.emailHelp')}
                </p>
                {emailProblem && (
                  <p id={`${ids}-email-error`} className="field__error">
                    {t('accessReview.validation.email.FORMAT')}
                  </p>
                )}
                <input
                  id={`${ids}-email`}
                  type="email"
                  autoComplete="off"
                  maxLength={MAX_EMAIL}
                  value={email}
                  aria-invalid={emailProblem ? true : undefined}
                  aria-describedby={
                    emailProblem ? `${ids}-email-help ${ids}-email-error` : `${ids}-email-help`
                  }
                  onChange={(event) => {
                    setEmail(event.target.value);
                    setCandidate(null);
                  }}
                />
              </div>
              <div className="actions">
                <button type="submit" className="button button--secondary" disabled={busy}>
                  {t('accessLink.lookup')}
                </button>
              </div>
            </form>
          )}
          {loaded.access.state === 'NOT_LINKED' && candidate && (
            <div data-testid="access-candidate">
              <p>{t('accessLink.candidate', { role: t(`users.form.roles.${candidate.role}`) })}</p>
              {candidate.linkable ? (
                <button
                  type="button"
                  className="button"
                  disabled={busy}
                  onClick={() => void link()}
                >
                  {t('accessLink.link')}
                </button>
              ) : (
                <p className="notice">
                  {t(`accessLink.notLinkable.${candidate.notLinkableReason ?? 'ALREADY_LINKED'}`)}
                </p>
              )}
            </div>
          )}
        </>
      )}
      {failure && (
        <HistoryAlert failure={failure} ref={failureBox} data-testid="access-link-failure" />
      )}
    </section>
  );
}

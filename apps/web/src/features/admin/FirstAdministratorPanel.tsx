import type { InvitationReceipt, Problem, TenantAdminBootstrap } from '@divalhr/api-client';
import { SUPPORTED_LOCALES, isSupportedLocale, type SupportedLocale } from '@divalhr/localization';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { EMAIL_MAX_LENGTH, emailError } from '../users/invitationForm';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; messageKey: string }
  | { kind: 'ready'; bootstrap: TenantAdminBootstrap };

type Action = 'create' | 'revoke' | 'resend';

type Work =
  { kind: 'idle' } | { kind: 'working'; action: Action } | { kind: 'failed'; messageKey: string };

type EmailConstraint = 'REQUIRED' | 'LENGTH' | 'FORMAT';

const PATH = '/organizations/{organizationId}/tenant-admin-bootstrap' as const;

function failureKey(status: number, problem: Partial<Problem> | undefined): string {
  if (status === 403 && problem?.code !== 'MFA_REQUIRED') return 'firstAdmin.unauthorized';
  return problem?.code ? `errors.${problem.code}` : 'errors.generic';
}

function emailFromProblem(problem: Partial<Problem> | undefined): EmailConstraint | null {
  if (problem?.code !== 'VALIDATION_FAILED') return null;
  const fields = Array.isArray(problem.params?.fields) ? problem.params.fields : [];
  for (const entry of fields as { field?: string; constraint?: string }[]) {
    if (entry.field === 'email') {
      return entry.constraint === 'REQUIRED' || entry.constraint === 'LENGTH'
        ? entry.constraint
        : 'FORMAT';
    }
  }
  return null;
}

/**
 * MVP-014: a platform administrator invites an organization's first tenant administrator, and
 * follows, revokes or reissues that one invitation. The organization ID stays in memory (props);
 * nothing is written to the URL, history or storage. The panel never receives the invitee's
 * address back from the server and shows only the bootstrap invitation's receipt.
 */
export function FirstAdministratorPanel({
  organizationId,
  defaultLocale,
  onNotFound,
}: {
  organizationId: string;
  defaultLocale: string;
  onNotFound?: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const initialLocale: SupportedLocale = isSupportedLocale(defaultLocale) ? defaultLocale : 'fr';
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [work, setWork] = useState<Work>({ kind: 'idle' });
  const [email, setEmail] = useState('');
  const [locale, setLocale] = useState<SupportedLocale>(initialLocale);
  const [emailProblem, setEmailProblem] = useState<EmailConstraint | null>(null);
  const [confirmingRevoke, setConfirmingRevoke] = useState(false);
  const summaryRef = useRef<HTMLDivElement>(null);
  const confirmRef = useRef<HTMLButtonElement>(null);
  const revokeRef = useRef<HTMLButtonElement>(null);
  const createKeys = useIdempotencyKey();
  const resendKeys = useIdempotencyKey();
  const focusSummary = () => requestAnimationFrame(() => summaryRef.current?.focus());

  type Fetched = { load: Load; notFound: boolean };

  const fetchStatus = useCallback(async (): Promise<Fetched> => {
    try {
      const { data, error, response } = await core.GET(PATH, {
        params: { path: { organizationId } },
        cache: 'no-store',
      });
      if (data) return { load: { kind: 'ready', bootstrap: data }, notFound: false };
      const problem = error as Partial<Problem> | undefined;
      return {
        load: { kind: 'failed', messageKey: failureKey(response.status, problem) },
        notFound: problem?.code === 'ORGANIZATION_NOT_FOUND',
      };
    } catch {
      return { load: { kind: 'failed', messageKey: 'errors.network' }, notFound: false };
    }
  }, [core, organizationId]);

  const apply = useCallback(
    (fetched: Fetched) => {
      setLoad(fetched.load);
      if (fetched.notFound) onNotFound?.();
    },
    [onNotFound],
  );

  const refresh = async () => {
    apply(await fetchStatus());
  };

  // The panel is mounted per organization (keyed by its ID), so it loads once on mount; a
  // manual retry resets it to 'loading' explicitly.
  useEffect(() => {
    let active = true;
    void fetchStatus().then((fetched) => {
      if (active) apply(fetched);
    });
    return () => {
      active = false;
    };
  }, [fetchStatus, apply]);

  const failed = (status: number, problem: Partial<Problem> | undefined) => {
    setWork({ kind: 'failed', messageKey: failureKey(status, problem) });
    focusSummary();
  };

  const onCreate = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (work.kind === 'working') return;
    const clientError = emailError(email);
    if (clientError) {
      setEmailProblem(clientError);
      focusSummary();
      return;
    }
    setEmailProblem(null);
    const body = { email: email.trim(), locale };
    setWork({ kind: 'working', action: 'create' });
    try {
      const { data, error, response } = await core.POST(PATH, {
        params: {
          path: { organizationId },
          header: { 'Idempotency-Key': createKeys.keyFor({ organizationId, ...body }) },
        },
        body,
        cache: 'no-store',
      });
      if (data) {
        createKeys.reset();
        setEmail('');
        setWork({ kind: 'idle' });
        await refresh();
        return;
      }
      const problem = error as Partial<Problem> | undefined;
      const field = emailFromProblem(problem);
      if (field) {
        setWork({ kind: 'idle' });
        setEmailProblem(field);
        focusSummary();
        return;
      }
      failed(response.status, problem);
      if (problem?.code === 'TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE') await refresh();
    } catch {
      setWork({ kind: 'failed', messageKey: 'errors.network' });
      focusSummary();
    }
  };

  const onRevoke = async () => {
    setConfirmingRevoke(false);
    setWork({ kind: 'working', action: 'revoke' });
    try {
      const { error, response } = await core.POST(`${PATH}/revoke`, {
        params: { path: { organizationId } },
        cache: 'no-store',
      });
      if (response.status === 204) {
        setWork({ kind: 'idle' });
        await refresh();
        return;
      }
      failed(response.status, error);
    } catch {
      setWork({ kind: 'failed', messageKey: 'errors.network' });
      focusSummary();
    }
  };

  const onResend = async (receipt: InvitationReceipt) => {
    setWork({ kind: 'working', action: 'resend' });
    try {
      const { data, error, response } = await core.POST(`${PATH}/resend`, {
        params: {
          path: { organizationId },
          header: {
            'Idempotency-Key': resendKeys.keyFor({
              resend: receipt.id,
              expiresAt: receipt.expiresAt,
            }),
          },
        },
        cache: 'no-store',
      });
      if (data) {
        resendKeys.reset();
        setWork({ kind: 'idle' });
        await refresh();
        return;
      }
      failed(response.status, error);
    } catch {
      setWork({ kind: 'failed', messageKey: 'errors.network' });
      focusSummary();
    }
  };

  const formatDate = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeStyle: 'short' }).format(
      new Date(iso),
    );
  const working = work.kind === 'working';
  const emailId = `${ids}-email`;
  const localeId = `${ids}-locale`;

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="first-admin-panel">
      <h2 id={`${ids}-title`}>{t('firstAdmin.title')}</h2>
      <p className="muted">{t('firstAdmin.description')}</p>

      <div
        ref={summaryRef}
        tabIndex={-1}
        className="form-summary"
        data-testid="first-admin-summary"
      >
        {emailProblem && (
          <div className="error-summary">
            <p>{t('users.errorSummary')}</p>
            <ul>
              <li>
                <a href={`#${emailId}`}>{t(`users.validation.email.${emailProblem}`)}</a>
              </li>
            </ul>
          </div>
        )}
        {work.kind === 'failed' && (
          <p className="error-summary" data-testid="first-admin-error">
            {t(work.messageKey)}
          </p>
        )}
      </div>

      {load.kind === 'loading' && (
        <p role="status" aria-busy="true">
          {t('firstAdmin.loading')}
        </p>
      )}

      {load.kind === 'failed' && (
        <div role="alert" data-testid="first-admin-load-error">
          <p>{t(load.messageKey)}</p>
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              setLoad({ kind: 'loading' });
              void refresh();
            }}
          >
            {t('users.retry')}
          </button>
        </div>
      )}

      {load.kind === 'ready' && load.bootstrap.invitation && (
        <div data-testid="first-admin-invitation">
          <h3>{t('firstAdmin.open.title')}</h3>
          <p aria-live="polite" data-testid="first-admin-delivery">
            {t(`users.delivery.${load.bootstrap.invitation.deliveryState}`)}
          </p>
          <p className="muted">
            {t('users.list.language', {
              language: t(`locale.${load.bootstrap.invitation.locale}`),
            })}
            {' · '}
            {t('users.list.expires', { date: formatDate(load.bootstrap.invitation.expiresAt) })}
          </p>
          {load.bootstrap.invitation.status === 'PENDING' && !confirmingRevoke && (
            <div className="actions">
              <button
                type="button"
                className="button button--secondary"
                disabled={working}
                onClick={() => {
                  if (load.bootstrap.invitation) void onResend(load.bootstrap.invitation);
                }}
              >
                {working && work.action === 'resend'
                  ? t('firstAdmin.actions.resending')
                  : t('firstAdmin.actions.resend')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={working}
                ref={revokeRef}
                onClick={() => {
                  setConfirmingRevoke(true);
                  requestAnimationFrame(() => confirmRef.current?.focus());
                }}
              >
                {t('firstAdmin.actions.revoke')}
              </button>
            </div>
          )}
          {confirmingRevoke && (
            <div role="group" aria-labelledby={`${ids}-confirm`} className="confirm">
              <p id={`${ids}-confirm`}>{t('firstAdmin.actions.confirmRevoke')}</p>
              <button
                type="button"
                className="button"
                ref={confirmRef}
                onClick={() => {
                  void onRevoke();
                }}
              >
                {t('firstAdmin.actions.confirmRevokeButton')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                onClick={() => {
                  setConfirmingRevoke(false);
                  requestAnimationFrame(() => revokeRef.current?.focus());
                }}
              >
                {t('firstAdmin.actions.cancel')}
              </button>
            </div>
          )}
        </div>
      )}

      {load.kind === 'ready' && !load.bootstrap.invitation && !load.bootstrap.available && (
        <p data-testid="first-admin-unavailable">{t('firstAdmin.unavailable')}</p>
      )}

      {load.kind === 'ready' && !load.bootstrap.invitation && load.bootstrap.available && (
        <form
          noValidate
          aria-labelledby={`${ids}-form-title`}
          aria-busy={working}
          onSubmit={(event) => void onCreate(event)}
          data-testid="first-admin-form"
        >
          <h3 id={`${ids}-form-title`}>{t('firstAdmin.form.title')}</h3>
          <div className="field">
            <label htmlFor={emailId}>{t('users.form.email')}</label>
            <p id={`${emailId}-help`} className="field__help">
              {t('firstAdmin.form.emailHelp')}
            </p>
            <input
              id={emailId}
              type="email"
              inputMode="email"
              autoComplete="off"
              spellCheck={false}
              maxLength={EMAIL_MAX_LENGTH}
              value={email}
              aria-invalid={emailProblem ? true : undefined}
              aria-describedby={[`${emailId}-help`, emailProblem ? `${emailId}-error` : null]
                .filter(Boolean)
                .join(' ')}
              onChange={(event) => {
                setEmail(event.target.value);
                if (work.kind === 'failed') setWork({ kind: 'idle' });
              }}
            />
            {emailProblem && (
              <p id={`${emailId}-error`} className="field__error">
                {t(`users.validation.email.${emailProblem}`)}
              </p>
            )}
          </div>
          <div className="field">
            <label htmlFor={localeId}>{t('users.form.locale')}</label>
            <select
              id={localeId}
              value={locale}
              onChange={(event) => {
                if (isSupportedLocale(event.target.value)) setLocale(event.target.value);
              }}
            >
              {SUPPORTED_LOCALES.map((value) => (
                <option key={value} value={value} lang={value}>
                  {t(`locale.${value}`)}
                </option>
              ))}
            </select>
          </div>
          <p className="field__help">{t('firstAdmin.form.roleNote')}</p>
          <button type="submit" className="button" disabled={working}>
            {working && work.action === 'create'
              ? t('firstAdmin.form.submitting')
              : t('firstAdmin.form.submit')}
          </button>
        </form>
      )}
    </section>
  );
}

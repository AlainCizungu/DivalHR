import type { InvitationPreview, Problem } from '@divalhr/api-client';
import { isSupportedLocale } from '@divalhr/localization';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useAuth } from '../../auth/AuthProvider';
import { useInstantText } from '../users/InvitationRow';
import { readInvitationToken, stripFragment } from './invitationToken';

type Step = 'inspect' | 'accept';
type PageState =
  | { kind: 'missing' }
  | { kind: 'working'; step: Step; token: string; preview?: InvitationPreview }
  | { kind: 'ready'; token: string; preview: InvitationPreview }
  | { kind: 'invalid' }
  | { kind: 'cannotAccept' }
  | {
      kind: 'retryable';
      step: Step;
      token: string;
      preview?: InvitationPreview;
      messageKey: string;
    }
  | { kind: 'accepted' };

type Outcome =
  { kind: 'invalid' } | { kind: 'cannotAccept' } | { kind: 'retryable'; messageKey: string };

/**
 * Every unusable token looks the same (404 INVITATION_INVALID, or 400 for a malformed body): the
 * page never says whether a link expired, was revoked, replaced or used. Conflicts other than
 * "cannot be accepted", 429, 503 and network failures are retryable with the same token.
 */
function outcomeOf(status: number, error: unknown): Outcome {
  const code = (error as Partial<Problem> | undefined)?.code;
  if (status === 404 || status === 400 || code === 'INVITATION_INVALID') return { kind: 'invalid' };
  if (code === 'INVITATION_CANNOT_BE_ACCEPTED') return { kind: 'cannotAccept' };
  return { kind: 'retryable', messageKey: code ? `errors.${code}` : 'errors.generic' };
}

/**
 * The anonymous invitation page (MVP-010). The token is taken from the fragment, removed from the
 * address bar at once, kept only in memory and sent only in POST bodies through a client that
 * never attaches an access token. Responses are no-store and are never cached by the browser or
 * the service worker.
 */
export function AcceptInvitationPage() {
  const { t, i18n } = useTranslation();
  const { publicCore } = useApi();
  const { signIn } = useAuth();
  const instant = useInstantText();
  const ids = useId();
  // Read once on the first render (pure); the fragment is stripped right after in an effect.
  const [state, setState] = useState<PageState>(() => {
    const token = readInvitationToken(window.location);
    return token ? { kind: 'working', step: 'inspect', token } : { kind: 'missing' };
  });
  const resultRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    stripFragment(window.location, window.history);
  }, []);

  const onFailure = useCallback(
    (
      step: Step,
      token: string,
      preview: InvitationPreview | undefined,
      status: number,
      error: unknown,
    ) => {
      const outcome = outcomeOf(status, error);
      setState(outcome.kind === 'retryable' ? { ...outcome, step, token, preview } : outcome);
    },
    [],
  );

  const inspectToken = state.kind === 'working' && state.step === 'inspect' ? state.token : null;
  useEffect(() => {
    if (!inspectToken) return undefined;
    let active = true;
    publicCore
      .POST('/public/invitations/inspect', { body: { token: inspectToken }, cache: 'no-store' })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (!data) {
            onFailure('inspect', inspectToken, undefined, response.status, error);
            return;
          }
          // The acceptance page uses the invitation's language (API contract); the visitor can
          // still switch languages afterwards.
          if (isSupportedLocale(data.locale) && i18n.language !== data.locale) {
            void i18n.changeLanguage(data.locale);
          }
          setState({ kind: 'ready', token: inspectToken, preview: data });
        },
        () => {
          if (active) {
            setState({
              kind: 'retryable',
              step: 'inspect',
              token: inspectToken,
              messageKey: 'errors.network',
            });
          }
        },
      );
    return () => {
      active = false;
    };
  }, [inspectToken, publicCore, i18n, onFailure]);

  const acceptWith = (token: string, preview: InvitationPreview | undefined) => {
    setState({ kind: 'working', step: 'accept', token, preview });
    publicCore.POST('/public/invitations/accept', { body: { token }, cache: 'no-store' }).then(
      ({ data, error, response }) => {
        if (data) setState({ kind: 'accepted' });
        else onFailure('accept', token, preview, response.status, error);
      },
      () => {
        setState({
          kind: 'retryable',
          step: 'accept',
          token,
          preview,
          messageKey: 'errors.network',
        });
      },
    );
  };

  // Final and retryable outcomes are announced once through the single live region (its text is
  // derived from the state) and receive focus.
  const resultKey =
    state.kind === 'accepted'
      ? 'invitation.accepted'
      : state.kind === 'invalid'
        ? 'invitation.invalid'
        : state.kind === 'cannotAccept'
          ? 'invitation.cannotAccept'
          : state.kind === 'retryable'
            ? state.messageKey
            : null;
  useEffect(() => {
    if (resultKey) requestAnimationFrame(() => resultRef.current?.focus());
  }, [resultKey, state]);

  const preview =
    state.kind === 'ready' || state.kind === 'working' || state.kind === 'retryable'
      ? state.preview
      : undefined;

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="invitation-page">
      <h1 id={`${ids}-title`}>{t('invitation.title')}</h1>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {resultKey ? t(resultKey) : ''}
      </p>
      {state.kind === 'working' && state.step === 'inspect' && (
        <p className="muted">{t('invitation.checking')}</p>
      )}
      {state.kind === 'missing' && <p>{t('invitation.missing')}</p>}
      {preview && (
        <div data-testid="invitation-preview">
          <p>{t('invitation.summary', { role: t(`invitation.roles.${preview.role}`) })}</p>
          <p className="muted">{t('invitation.expires', { date: instant(preview.expiresAt) })}</p>
          <p className="muted">{t('invitation.acceptHelp')}</p>
        </div>
      )}
      <div ref={resultRef} tabIndex={-1} data-testid="invitation-result">
        {state.kind === 'accepted' && (
          <>
            <p>{t('invitation.accepted')}</p>
            <button type="button" className="button" onClick={() => void signIn('/')}>
              {t('invitation.signIn')}
            </button>
          </>
        )}
        {state.kind === 'invalid' && <p className="error-summary">{t('invitation.invalid')}</p>}
        {state.kind === 'cannotAccept' && (
          <p className="error-summary">{t('invitation.cannotAccept')}</p>
        )}
        {state.kind === 'retryable' && (
          <div className="error-summary">
            <p>{t(state.messageKey)}</p>
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                const { step, token, preview: shown } = state;
                if (step === 'accept') acceptWith(token, shown);
                else setState({ kind: 'working', step, token, preview: shown });
              }}
            >
              {t('invitation.retry')}
            </button>
          </div>
        )}
      </div>
      {(state.kind === 'ready' || (state.kind === 'working' && state.step === 'accept')) && (
        <button
          type="button"
          className="button"
          disabled={state.kind === 'working'}
          onClick={() => {
            if (state.kind === 'ready') acceptWith(state.token, state.preview);
          }}
        >
          {state.kind === 'working' ? t('invitation.accepting') : t('invitation.accept')}
        </button>
      )}
    </section>
  );
}

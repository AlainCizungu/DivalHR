import type { Invitation, Problem } from '@divalhr/api-client';
import { useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { deliveryKey, rowAfterResend } from './invitationForm';

type Action = 'revoke' | 'resend';
type RowPhase =
  | { kind: 'idle' }
  | { kind: 'confirming'; action: Action }
  | { kind: 'working'; action: Action }
  | { kind: 'failed'; messageKey: string };

/** Date and time of an instant in the active language and the viewer's time zone. */
export function useInstantText() {
  const { i18n } = useTranslation();
  return (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
      new Date(iso),
    );
}

function failureKey(status: number, error: unknown): string {
  if (status === 403) return 'users.unauthorized';
  const code = (error as Partial<Problem> | undefined)?.code;
  return code ? `errors.${code}` : 'errors.generic';
}

/**
 * One invitation with its status, delivery state and two-step revoke and resend actions. The
 * confirmation receives focus when it opens; cancelling returns focus to the action button.
 */
export function InvitationRow({
  row,
  focusRef,
  onChanged,
  onAnnounce,
}: {
  row: Invitation;
  focusRef: ((node: HTMLElement | null) => void) | undefined;
  onChanged: (row: Invitation) => void;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const instant = useInstantText();
  const [phase, setPhase] = useState<RowPhase>({ kind: 'idle' });
  const confirmRef = useRef<HTMLButtonElement>(null);
  const actionRefs = useRef<Record<Action, HTMLButtonElement | null>>({
    revoke: null,
    resend: null,
  });
  const resendKey = useIdempotencyKey();

  const pending = row.status === 'PENDING';
  // MVP-014: a platform administrator's bootstrap invitation; tenant administrators may revoke it
  // but never reissue it.
  const bootstrap = row.origin === 'PLATFORM_BOOTSTRAP';
  const delivery = deliveryKey(row);
  const expired = row.status === 'EXPIRED';

  const confirm = (action: Action) => {
    setPhase({ kind: 'confirming', action });
    requestAnimationFrame(() => confirmRef.current?.focus());
  };
  const cancel = (action: Action) => {
    setPhase({ kind: 'idle' });
    requestAnimationFrame(() => actionRefs.current[action]?.focus());
  };

  const fail = (messageKey: string) => {
    setPhase({ kind: 'failed', messageKey });
    // Announced once, through the page's single live region; the text stays next to the row.
    onAnnounce(t(messageKey));
  };

  const run = async (action: Action) => {
    setPhase({ kind: 'working', action });
    try {
      if (action === 'revoke') {
        const { data, error, response } = await core.POST('/invitations/{invitationId}/revoke', {
          params: { path: { invitationId: row.id } },
          cache: 'no-store',
        });
        if (data) {
          setPhase({ kind: 'idle' });
          onChanged(data);
          onAnnounce(t('users.announce.revoked', { email: data.email }));
          return;
        }
        fail(failureKey(response.status, error));
      } else {
        const key = resendKey.keyFor({ invitation: row.id, issue: row.resendsRemaining });
        const { data, error, response } = await core.POST('/invitations/{invitationId}/resend', {
          params: { path: { invitationId: row.id }, header: { 'Idempotency-Key': key } },
          cache: 'no-store',
        });
        if (data) {
          resendKey.reset();
          setPhase({ kind: 'idle' });
          onChanged(rowAfterResend(row, data));
          onAnnounce(t('users.announce.resent', { email: row.email }));
          return;
        }
        fail(failureKey(response.status, error));
      }
    } catch {
      fail('errors.network');
    }
  };

  const working = phase.kind === 'working';

  return (
    <>
      <span className="hierarchy-item__main" ref={focusRef} tabIndex={-1}>
        <strong>{row.email}</strong>{' '}
        <span className="badge" data-testid="invitation-status">
          {t(`users.status.${row.status}`)}
        </span>
      </span>
      <span className="muted">
        {t('users.list.role', { role: t(`users.form.roles.${row.role}`) })} ·{' '}
        {t('users.list.language', { language: t(`locale.${row.locale}`) })}
      </span>
      {pending && (
        <span className="muted">{t('users.list.expires', { date: instant(row.expiresAt) })}</span>
      )}
      {expired && (
        <span className="muted">{t('users.list.expired', { date: instant(row.expiresAt) })}</span>
      )}
      {row.acceptedAt && (
        <span className="muted">
          {t('users.list.acceptedAt', { date: instant(row.acceptedAt) })}
        </span>
      )}
      {row.revokedAt && (
        <span className="muted">{t('users.list.revokedAt', { date: instant(row.revokedAt) })}</span>
      )}
      {delivery && (
        <span
          className={row.deliveryState === 'FAILED' ? 'field__error' : 'muted'}
          data-testid="invitation-delivery"
          data-delivery={row.deliveryState}
        >
          {row.deliveryState === 'FAILED' && <span aria-hidden="true">⚠ </span>}
          {t(delivery)}
        </span>
      )}
      {bootstrap && (
        <span className="muted" data-testid="invitation-origin">
          {t('users.list.origin.PLATFORM_BOOTSTRAP')}
        </span>
      )}
      {pending && !bootstrap && (
        <span className="muted">
          {t('users.list.resendsRemaining', { count: row.resendsRemaining })}
        </span>
      )}
      {pending && phase.kind !== 'confirming' && (
        <span className="actions">
          {row.resendsRemaining > 0 && !bootstrap && (
            <button
              ref={(node) => {
                actionRefs.current.resend = node;
              }}
              type="button"
              className="button button--secondary"
              aria-label={t('users.actions.resendLabel', { email: row.email })}
              disabled={working}
              onClick={() => {
                confirm('resend');
              }}
            >
              {working && phase.action === 'resend'
                ? t('users.actions.working')
                : t('users.actions.resend')}
            </button>
          )}
          <button
            ref={(node) => {
              actionRefs.current.revoke = node;
            }}
            type="button"
            className="button button--secondary"
            aria-label={t('users.actions.revokeLabel', { email: row.email })}
            disabled={working}
            onClick={() => {
              confirm('revoke');
            }}
          >
            {working && phase.action === 'revoke'
              ? t('users.actions.working')
              : t('users.actions.revoke')}
          </button>
        </span>
      )}
      {phase.kind === 'confirming' && (
        <span
          className="confirm"
          role="group"
          aria-labelledby={`${ids}-confirm`}
          data-testid="invitation-confirm"
        >
          <span id={`${ids}-confirm`}>
            {phase.action === 'revoke'
              ? t('users.actions.confirmRevoke', { email: row.email })
              : t('users.actions.confirmResend', { email: row.email })}
          </span>{' '}
          <button
            ref={confirmRef}
            type="button"
            className="button"
            onClick={() => void run(phase.action)}
          >
            {phase.action === 'revoke'
              ? t('users.actions.confirmRevokeButton')
              : t('users.actions.confirmResendButton')}
          </button>{' '}
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              cancel(phase.action);
            }}
          >
            {t('users.actions.cancel')}
          </button>
        </span>
      )}
      {phase.kind === 'failed' && (
        <span className="error-summary" data-testid="invitation-error">
          {t(phase.messageKey)}
        </span>
      )}
    </>
  );
}

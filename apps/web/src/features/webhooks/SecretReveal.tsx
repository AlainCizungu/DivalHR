import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * D110-5/D110-11: the one-time reveal of a signing secret. The value lives in this component's
 * state only: never in the URL, history, storage, logs or the DOM outside the read-only field,
 * and it is dropped when the administrator confirms they stored it or leaves the page. The
 * server cannot show it again; a lost secret is replaced by rotation.
 */
export function SecretReveal({
  secret,
  previousSecretExpiresAt,
  onDone,
}: {
  secret: string;
  previousSecretExpiresAt?: string | null;
  onDone: () => void;
}) {
  const { t, i18n } = useTranslation();
  const ids = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const [copied, setCopied] = useState<'idle' | 'copied' | 'failed'>('idle');
  const [stored, setStored] = useState(false);

  useEffect(() => {
    heading.current?.focus();
  }, []);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(secret);
      setCopied('copied');
    } catch {
      setCopied('failed');
    }
  };

  const expires = previousSecretExpiresAt
    ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(
        new Date(previousSecretExpiresAt),
      )
    : null;

  return (
    <section
      aria-labelledby={`${ids}-title`}
      className="card"
      data-testid="secret-reveal"
      role="group"
    >
      <h2 id={`${ids}-title`} tabIndex={-1} ref={heading}>
        {t('webhooks.secret.title')}
      </h2>
      <p role="alert">{t('webhooks.secret.once')}</p>
      <div className="field">
        <label htmlFor={`${ids}-value`}>{t('webhooks.secret.label')}</label>
        <input
          id={`${ids}-value`}
          readOnly
          value={secret}
          autoComplete="off"
          spellCheck={false}
          data-testid="secret-value"
          onFocus={(event) => {
            event.currentTarget.select();
          }}
        />
      </div>
      {expires && <p className="muted">{t('webhooks.secret.overlap', { when: expires })}</p>}
      <div className="actions">
        <button type="button" className="button button--secondary" onClick={() => void copy()}>
          {t('webhooks.secret.copy')}
        </button>
      </div>
      <p role="status" data-testid="copy-status">
        {copied === 'copied' && t('webhooks.secret.copied')}
        {copied === 'failed' && t('webhooks.secret.copyFailed')}
      </p>
      <div className="field">
        <label>
          <input
            type="checkbox"
            checked={stored}
            onChange={(event) => {
              setStored(event.target.checked);
            }}
          />{' '}
          {t('webhooks.secret.stored')}
        </label>
      </div>
      <div className="actions">
        <button type="button" className="button" disabled={!stored} onClick={onDone}>
          {t('webhooks.secret.done')}
        </button>
      </div>
    </section>
  );
}

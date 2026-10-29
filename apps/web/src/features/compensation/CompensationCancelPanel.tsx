import type {
  CompensationCancellationPreview,
  CompensationInterval,
  CompensationReceipt,
} from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import {
  compensationFailureOf,
  formatInstant,
  formatMoney,
  NETWORK_FAILURE,
  formatDate,
  type CompensationFailure,
} from './compensation';
import { CompensationAlert } from './CompensationBits';
import { PreviewIntervals, PreviewNotes } from './PreviewBits';

/**
 * MVP-093A: cancels one scheduled (unstarted) change. The cancellation is previewed as soon as the
 * panel opens; only an explicit confirm cancels it, repeating the preview's version, employment
 * digest, cancellation digest and expiry with a fresh Idempotency-Key.
 */
export function CompensationCancelPanel({
  employeeId,
  interval,
  onDone,
  onClose,
  onStale,
}: {
  employeeId: string;
  interval: CompensationInterval;
  onDone: (receipt: CompensationReceipt) => void;
  onClose: () => void;
  onStale: () => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const commitKey = useIdempotencyKey();
  const [preview, setPreview] = useState<CompensationCancellationPreview | null>(null);
  const [failure, setFailure] = useState<CompensationFailure | null>(null);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>('preview');
  const titleRef = useRef<HTMLHeadingElement>(null);
  const failureRef = useRef<HTMLDivElement>(null);
  const focusNext = useRef<'title' | 'failure' | null>('title');

  useEffect(() => {
    const element = focusNext.current === 'failure' ? failureRef.current : titleRef.current;
    if (focusNext.current && element) {
      focusNext.current = null;
      element.focus();
    }
  });

  useEffect(() => {
    const live = { active: true };
    void (async () => {
      try {
        const { data, error, response } = await core.POST(
          '/employees/{employeeId}/compensation/{changeId}/cancel-preview',
          {
            params: { path: { employeeId, changeId: interval.originChangeId } },
            cache: 'no-store',
          },
        );
        if (!live.active) return;
        if (data) setPreview(data);
        else {
          setFailure(compensationFailureOf(response, error));
          focusNext.current = 'failure';
        }
      } catch {
        if (!live.active) return;
        setFailure(NETWORK_FAILURE);
        focusNext.current = 'failure';
      } finally {
        if (live.active) setBusy(null);
      }
    })();
    return () => {
      live.active = false;
    };
  }, [core, employeeId, interval.originChangeId]);

  const blocked =
    preview !== null &&
    (preview.blockers.length > 0 ||
      preview.cancellationDigest === null ||
      preview.expiresAt === null);

  const confirm = async () => {
    if (!preview || busy || blocked) return;
    if (preview.cancellationDigest === null || preview.expiresAt === null) return;
    const body = {
      expectedVersion: preview.version,
      employmentDigest: preview.employmentDigest,
      cancellationDigest: preview.cancellationDigest,
      expiresAt: preview.expiresAt,
      confirmed: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/compensation/{changeId}/cancel',
        {
          params: {
            path: { employeeId, changeId: interval.originChangeId },
            header: {
              'Idempotency-Key': commitKey.keyFor({
                employeeId,
                changeId: interval.originChangeId,
                ...body,
              }),
            },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        commitKey.reset();
        onDone(data);
        return;
      }
      const next = compensationFailureOf(response, error);
      if (next.kind === 'stale') {
        commitKey.reset();
        setPreview(null);
        onStale();
      }
      setFailure(next);
      focusNext.current = 'failure';
    } catch {
      setFailure(NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(null);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="compensation-cancel-panel">
      <h3 id={`${ids}-title`} tabIndex={-1} ref={titleRef}>
        {t('compensation.cancel.title', {
          money: formatMoney(i18n.language, interval.money),
          date: formatDate(i18n.language, interval.effectiveFrom),
        })}
      </h3>
      {busy === 'preview' && <p role="status">{t('compensation.cancel.loading')}</p>}
      {failure && (
        <CompensationAlert failure={failure} testId="compensation-error" alertRef={failureRef} />
      )}
      {preview && (
        <>
          <p className="muted">{t('compensation.cancel.help')}</p>
          <PreviewIntervals
            before={preview.before}
            after={preview.after}
            testId="cancel-intervals"
            level={4}
          />
          <PreviewNotes warnings={preview.warnings} blockers={preview.blockers} />
          {!blocked && preview.expiresAt && (
            <p className="muted" data-testid="preview-expiry">
              {t('compensation.preview.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
        </>
      )}
      <div className="dialog-actions">
        {preview && !blocked && (
          <button
            type="button"
            className="button"
            data-testid="compensation-cancel-confirm"
            disabled={busy !== null}
            onClick={() => void confirm()}
          >
            {busy === 'commit' ? t('compensation.form.saving') : t('compensation.cancel.confirm')}
          </button>
        )}
        <button type="button" className="button button--secondary" onClick={onClose}>
          {t('compensation.cancel.keep')}
        </button>
      </div>
    </section>
  );
}

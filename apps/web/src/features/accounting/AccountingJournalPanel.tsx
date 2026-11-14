import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { MinorAmount } from '../payments/PaymentBits';
import { PayrollAlert, useFocusRequest } from '../payrollRuns/PayrollBits';
import {
  formatDay,
  formatMonth,
  NETWORK_FAILURE,
  type PayrollFailure,
} from '../payrollRuns/payrollRuns';
import { AccountingNotice, JournalLinesTable } from './AccountingBits';
import {
  accountingFailureOf,
  type AccountingJournalPreview,
  type AccountingJournalSummary,
} from './accounting';

/**
 * MVP-096 (D96-8): « Journal comptable » / "Accounting journal" on an approved payroll package.
 * The history of the package's journals (a journal whose package was voided later is marked
 * SOURCE_VOIDED), a server preview with the exact aggregated lines, the totals and the blockers,
 * one confirmation, and the generation. The journal is an export for manual import: nothing is
 * posted and nothing proves a payment.
 */
export function AccountingJournalPanel({
  legalEntityId,
  packageId,
  packageStatus,
  revision,
  onChanged,
}: {
  legalEntityId: string;
  packageId: string;
  packageStatus: 'APPROVED' | 'VOIDED';
  revision: number;
  onChanged: (message: string) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const navigate = useNavigate();
  const ids = useId();
  const key = useIdempotencyKey();
  const focus = useFocusRequest();
  const [journals, setJournals] = useState<AccountingJournalSummary[] | null>(null);
  const [listFailure, setListFailure] = useState<PayrollFailure | null>(null);
  const [preview, setPreview] = useState<AccountingJournalPreview | null>(null);
  const [understood, setUnderstood] = useState(false);
  const [busy, setBusy] = useState<'preview' | 'commit' | null>(null);
  const [failure, setFailure] = useState<PayrollFailure | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals', {
        params: { path: { legalEntityId, packageId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          if (data) {
            setJournals(data.items);
            setListFailure(null);
          } else {
            setListFailure(accountingFailureOf(response, error));
          }
        },
        () => {
          if (active) setListFailure(NETWORK_FAILURE);
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, packageId, version, revision]);

  const discard = () => {
    setPreview(null);
    setUnderstood(false);
    key.reset();
  };

  const onPreview = async () => {
    if (busy) return;
    setBusy('preview');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals/preview',
        { params: { path: { legalEntityId, packageId } }, cache: 'no-store' },
      );
      setUnderstood(false);
      key.reset();
      if (data) {
        setPreview(data);
        focus.request('preview');
      } else {
        setPreview(null);
        setFailure(accountingFailureOf(response, error));
        focus.request('failure');
      }
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const committable =
    preview !== null &&
    preview.status === 'READY' &&
    preview.mapping !== null &&
    preview.previewDigest !== null &&
    preview.expiresAt !== null;

  const onGenerate = async () => {
    if (!preview || busy || !committable || !understood) return;
    if (preview.previewDigest === null || preview.expiresAt === null || !preview.mapping) return;
    const body = {
      expectedPackageVersion: preview.packageVersion,
      expectedMappingVersion: preview.mapping.version,
      previewDigest: preview.previewDigest,
      expiresAt: preview.expiresAt,
      understandExportOnly: true,
    };
    setBusy('commit');
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals',
        {
          params: {
            path: { legalEntityId, packageId },
            header: { 'Idempotency-Key': key.keyFor({ legalEntityId, packageId, ...body }) },
          },
          body,
          cache: 'no-store',
        },
      );
      if (data) {
        discard();
        setVersion((value) => value + 1);
        onChanged(t('accounting.journal.announceGenerated'));
        void navigate(`/admin/accounting-journals/${legalEntityId}/${data.journalId}`);
        return;
      }
      const next = accountingFailureOf(response, error);
      if (next.kind === 'stale') {
        discard();
        setVersion((value) => value + 1);
      }
      setFailure(next);
      focus.request('failure');
    } catch {
      setFailure(NETWORK_FAILURE);
      focus.request('failure');
    } finally {
      setBusy(null);
    }
  };

  const whyKey =
    preview === null
      ? null
      : !committable
        ? 'accounting.journal.whyBlocked'
        : !understood
          ? 'accounting.journal.whyConfirm'
          : null;

  return (
    <section
      className="card"
      aria-labelledby={`${ids}-title`}
      data-testid="accounting-journal-panel"
    >
      <h2 id={`${ids}-title`}>{t('accounting.journal.title')}</h2>
      <p className="muted">{t('accounting.journal.help')}</p>
      <AccountingNotice />
      <h3 id={`${ids}-history`}>{t('accounting.journal.history.title')}</h3>
      {listFailure && <PayrollAlert failure={listFailure} testId="accounting-journals-failure" />}
      {journals === null && !listFailure && (
        <p role="status" data-testid="accounting-journals-loading">
          {t('accounting.loading')}
        </p>
      )}
      {journals?.length === 0 && (
        <p className="muted" data-testid="accounting-journals-empty">
          {t('accounting.journal.history.empty')}
        </p>
      )}
      {journals && journals.length > 0 && (
        <ul
          className="plain-list"
          aria-labelledby={`${ids}-history`}
          data-testid="accounting-journals"
        >
          {journals.map((journal) => (
            <li
              key={journal.id}
              data-testid="accounting-journal-item"
              data-source-voided={journal.sourceVoided ? 'true' : 'false'}
            >
              <Link
                className="table-link"
                to={`/admin/accounting-journals/${legalEntityId}/${journal.id}`}
                data-testid="accounting-journal-link"
              >
                {t('accounting.journal.history.item', { version: journal.version })}
              </Link>{' '}
              {journal.sourceVoided && (
                <StatusBadge tone="warning" testId="accounting-source-voided">
                  {t('accounting.journal.sourceVoided')}
                </StatusBadge>
              )}{' '}
              <span className="muted">
                {t('accounting.journal.history.meta', {
                  count: journal.lineCount,
                  at: formatInstant(i18n.language, journal.generatedAt),
                })}
              </span>
            </li>
          ))}
        </ul>
      )}
      {packageStatus === 'APPROVED' && (
        <div className="dialog-actions">
          <button
            type="button"
            className="button button--secondary"
            disabled={busy !== null}
            data-testid="accounting-journal-preview-button"
            onClick={() => void onPreview()}
          >
            {busy === 'preview'
              ? t('accounting.journal.previewing')
              : t('accounting.journal.preview')}
          </button>
        </div>
      )}
      {failure && (
        <PayrollAlert
          failure={failure}
          testId="accounting-journal-failure"
          alertRef={focus.register('failure')}
        />
      )}
      {failure?.kind === 'stale' && (
        <p role="status" data-testid="accounting-journal-stale">
          {t('accounting.journal.staleRefresh')}
        </p>
      )}
      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="accounting-journal-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={focus.register('preview')}>
            {t('accounting.journal.previewHeading')}
          </h3>
          <p>
            <StatusBadge
              tone={preview.status === 'READY' ? 'success' : 'danger'}
              testId="accounting-journal-preview-status"
            >
              {t(`accounting.previewStatus.${preview.status}`)}
            </StatusBadge>
          </p>
          <dl className="decision-summary decision-summary--wrapping">
            <dt>{t('accounting.journal.period')}</dt>
            <dd>{formatMonth(i18n.language, preview.period)}</dd>
            <dt>{t('accounting.journal.currency')}</dt>
            <dd>{preview.currency}</dd>
            <dt>{t('accounting.journal.packageVersion')}</dt>
            <dd>{preview.packageVersion}</dd>
            <dt>{t('accounting.journal.mapping')}</dt>
            <dd data-testid="accounting-journal-mapping">
              {preview.mapping
                ? t('accounting.journal.mappingValue', {
                    version: preview.mapping.version,
                    date: formatDay(i18n.language, preview.mapping.effectiveFrom),
                  })
                : t('accounting.journal.noMapping')}
            </dd>
            <dt>{t('accounting.journal.lineCount')}</dt>
            <dd data-testid="accounting-journal-line-count">{preview.lineCount}</dd>
            <dt>{t('accounting.journal.sourceEntries')}</dt>
            <dd data-testid="accounting-journal-source-entries">{preview.sourceEntryCount}</dd>
            <dt>{t('accounting.journal.totalDebits')}</dt>
            <dd data-testid="accounting-journal-debits">
              <MinorAmount minor={preview.totalDebitsMinor} currency={preview.currency} />
            </dd>
            <dt>{t('accounting.journal.totalCredits')}</dt>
            <dd data-testid="accounting-journal-credits">
              <MinorAmount minor={preview.totalCreditsMinor} currency={preview.currency} />
            </dd>
          </dl>
          {preview.blockers.length > 0 && (
            <div className="error-summary" role="alert" data-testid="accounting-journal-blockers">
              <ul>
                {preview.blockers.map((blocker) => (
                  <li key={blocker} data-code={blocker}>
                    {t(`accounting.journal.blockers.${blocker}`)}
                    {blocker === 'MAPPING_NOT_FOUND' && (
                      <>
                        {' '}
                        <Link
                          className="table-link"
                          to={`/admin/accounting-mappings?entity=${legalEntityId}`}
                          data-testid="accounting-mappings-link"
                        >
                          {t('accounting.journal.mappingLink')}
                        </Link>
                      </>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <JournalLinesTable
            lines={preview.lines}
            currency={preview.currency}
            testId="accounting-journal-preview-lines"
          />
          {committable && preview.expiresAt && (
            <p className="muted">
              {t('accounting.journal.expires', {
                at: formatInstant(i18n.language, preview.expiresAt),
              })}
            </p>
          )}
          <div className="choice">
            <input
              id={`${ids}-understand`}
              type="checkbox"
              checked={understood}
              disabled={!committable}
              data-testid="accounting-journal-understand"
              onChange={(event) => {
                setUnderstood(event.target.checked);
              }}
            />
            <label htmlFor={`${ids}-understand`}>{t('accounting.journal.confirm')}</label>
          </div>
          <div className="dialog-actions">
            <button
              type="button"
              className="button"
              data-testid="accounting-journal-generate"
              disabled={busy !== null || !committable || !understood}
              aria-describedby={whyKey ? `${ids}-why` : undefined}
              onClick={() => void onGenerate()}
            >
              {busy === 'commit'
                ? t('accounting.journal.generating')
                : t('accounting.journal.generate')}
            </button>
          </div>
          {whyKey && (
            <p id={`${ids}-why`} className="muted" data-testid="accounting-journal-why">
              {t(whyKey)}
            </p>
          )}
        </section>
      )}
    </section>
  );
}

import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { formatInstant } from '../compensation/compensation';
import { MinorAmount } from '../payments/PaymentBits';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import {
  formatDay,
  formatMonth,
  NETWORK_FAILURE,
  type PayrollFailure,
} from '../payrollRuns/payrollRuns';
import { AccountingNotice, JournalLinesTable } from './AccountingBits';
import { accountingFailureOf, journalFileName, type AccountingJournalView } from './accounting';
import { useJournalDownload, type JournalFile } from './useJournalDownload';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; view: AccountingJournalView };

/**
 * MVP-096: one generated journal. The immutable header, the exact lines, the CSV and manifest
 * downloads (disclosure-audited, never cached) and the link back to the package history. A journal
 * whose package was voided later is marked SOURCE_VOIDED; its lines and files do not change.
 */
export function AccountingJournalDetailPage() {
  const { legalEntityId = '', journalId = '' } = useParams();
  return (
    <Detail
      key={`${legalEntityId}/${journalId}`}
      legalEntityId={legalEntityId}
      journalId={journalId}
    />
  );
}

function Detail({ legalEntityId, journalId }: { legalEntityId: string; journalId: string }) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    core
      .GET('/legal-entities/{legalEntityId}/accounting-journals/{journalId}', {
        params: { path: { legalEntityId, journalId } },
        cache: 'no-store',
      })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', view: data }
              : { kind: 'failed', failure: accountingFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core, legalEntityId, journalId]);

  const view = load.kind === 'ready' ? load.view : null;
  const fileNameOf = useCallback(
    (file: JournalFile) =>
      journalFileName(
        view ?? { legalEntityCode: 'unknown', period: '0000-00', currency: 'CDF', version: 1 },
        file,
      ),
    [view],
  );
  const files = useJournalDownload(legalEntityId, journalId, fileNameOf);

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="accounting-journal-detail">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('accounting.detail.title')}
        description={t('accounting.detail.description')}
      />
      <AccountingNotice />
      {load.kind === 'loading' && (
        <p role="status" data-testid="accounting-detail-loading">
          {t('accounting.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="accounting-detail-failure" />
      )}
      {view && (
        <>
          <section
            className="card"
            aria-labelledby={`${ids}-summary`}
            data-testid="accounting-journal-summary"
          >
            <h2 id={`${ids}-summary`}>{t('accounting.detail.summary')}</h2>
            {view.sourceVoided && (
              <div role="status" data-testid="accounting-voided-note">
                <p>
                  <StatusBadge tone="warning" testId="accounting-source-voided">
                    {t('accounting.journal.sourceVoided')}
                  </StatusBadge>
                </p>
                <p>{t('accounting.journal.sourceVoidedHelp')}</p>
              </div>
            )}
            <dl className="decision-summary decision-summary--wrapping">
              <dt>{t('accounting.journal.period')}</dt>
              <dd>{formatMonth(i18n.language, view.period)}</dd>
              <dt>{t('accounting.journal.currency')}</dt>
              <dd>{view.currency}</dd>
              <dt>{t('accounting.detail.version')}</dt>
              <dd>{view.version}</dd>
              <dt>{t('accounting.journal.packageVersion')}</dt>
              <dd>{view.packageVersion}</dd>
              <dt>{t('accounting.journal.mapping')}</dt>
              <dd data-testid="accounting-detail-mapping">
                {t('accounting.journal.mappingValue', {
                  version: view.mapping.version,
                  date: formatDay(i18n.language, view.mapping.effectiveFrom),
                })}
              </dd>
              <dt>{t('accounting.detail.payslips')}</dt>
              <dd>{view.payslipCount}</dd>
              <dt>{t('accounting.journal.sourceEntries')}</dt>
              <dd>{view.sourceEntryCount}</dd>
              <dt>{t('accounting.journal.lineCount')}</dt>
              <dd data-testid="accounting-detail-line-count">{view.lineCount}</dd>
              <dt>{t('accounting.journal.totalDebits')}</dt>
              <dd data-testid="accounting-detail-debits">
                <MinorAmount minor={view.totalDebitsMinor} currency={view.currency} />
              </dd>
              <dt>{t('accounting.journal.totalCredits')}</dt>
              <dd data-testid="accounting-detail-credits">
                <MinorAmount minor={view.totalCreditsMinor} currency={view.currency} />
              </dd>
              <dt>{t('accounting.detail.generatedAt')}</dt>
              <dd>{formatInstant(i18n.language, view.generatedAt)}</dd>
              <dt>{t('accounting.detail.csvBytes')}</dt>
              <dd>{view.csvBytes}</dd>
              <dt>{t('accounting.detail.csvSha256')}</dt>
              <dd>
                <code className="accounting-code" data-testid="accounting-detail-sha">
                  {view.csvSha256}
                </code>
              </dd>
            </dl>
            <p>
              <Link
                className="table-link"
                to={`/admin/payroll-packages/${legalEntityId}/${view.packageId}`}
                data-testid="accounting-history-link"
              >
                {t('accounting.detail.back')}
              </Link>
            </p>
          </section>
          <section className="card" aria-labelledby={`${ids}-lines`}>
            <h2 id={`${ids}-lines`}>{t('accounting.detail.lines')}</h2>
            <JournalLinesTable
              lines={view.lines}
              currency={view.currency}
              testId="accounting-detail-lines"
            />
          </section>
          <section className="card" aria-labelledby={`${ids}-files`}>
            <h2 id={`${ids}-files`}>{t('accounting.detail.files')}</h2>
            <p className="muted">{t('accounting.detail.filesHelp')}</p>
            <div className="dialog-actions">
              <button
                type="button"
                className="button button--secondary"
                disabled={files.busy !== null}
                data-testid="accounting-download-csv"
                onClick={() => void files.download('csv')}
              >
                {t('accounting.detail.downloadCsv')}
              </button>
              <button
                type="button"
                className="button button--secondary"
                disabled={files.busy !== null}
                data-testid="accounting-download-manifest"
                onClick={() => void files.download('manifest')}
              >
                {t('accounting.detail.downloadManifest')}
              </button>
            </div>
            {files.failure && (
              <PayrollAlert failure={files.failure} testId="accounting-download-failure" />
            )}
            <p role="status" data-testid="accounting-download-done">
              {files.done ? t('accounting.detail.downloaded') : ''}
            </p>
          </section>
        </>
      )}
    </section>
  );
}

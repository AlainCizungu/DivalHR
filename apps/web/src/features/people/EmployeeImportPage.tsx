import type { EmployeeImport } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { FailureDetails } from './FailureDetails';
import { failureOf, NETWORK_FAILURE, type Failure } from './importFailure';
import { ImportRowsTable, type RowFilter } from './ImportRowsTable';
import { ImportTemplateSection } from './ImportTemplateSection';

/** Transport cap of the upload (A20-6): checked here first, enforced by the server. */
export const MAX_FILE_BYTES = 2 * 1024 * 1024;

/** Commit failures after which this import can no longer be committed. */
const TERMINAL = new Set(['IMPORT_STALE', 'IMPORT_NOT_COMMITTABLE', 'EMPLOYEE_IMPORT_NOT_FOUND']);

const ROW_FILTERS: readonly RowFilter[] = ['all', 'valid', 'invalid'];

type FocusTarget = 'preview' | 'result' | 'uploadError' | 'confirm' | 'actionError';

/**
 * MVP-020: employee import. Download a template, check a CSV, review the valid and invalid rows,
 * then confirm the creation of the valid rows or cancel the import. The file is sent as raw
 * text/csv bytes; nothing (file, rows, import ID, cursors) is written to the URL, history or
 * browser storage, and every request uses cache: 'no-store'. Retries of the same file or the same
 * commit reuse their idempotency key, so a lost response never creates employees twice.
 */
export function EmployeeImportPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const uploadKey = useIdempotencyKey();
  const commitKey = useIdempotencyKey();
  const [announcement, setAnnouncement] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [fileProblem, setFileProblem] = useState<'required' | 'tooLarge' | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadFailure, setUploadFailure] = useState<Failure | null>(null);
  const [record, setRecord] = useState<EmployeeImport | null>(null);
  const [filter, setFilter] = useState<RowFilter>('all');
  const [acknowledged, setAcknowledged] = useState(false);
  const [ackProblem, setAckProblem] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState<'commit' | 'discard' | null>(null);
  const [actionFailure, setActionFailure] = useState<Failure | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadError = useRef<HTMLDivElement>(null);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const resultHeading = useRef<HTMLHeadingElement>(null);
  const confirmText = useRef<HTMLParagraphElement>(null);
  const commitButton = useRef<HTMLButtonElement>(null);
  const actionError = useRef<HTMLDivElement>(null);
  const focusNext = useRef<FocusTarget | null>(null);

  const formatDateTime = (iso: string) =>
    new Intl.DateTimeFormat(i18n.language, { dateStyle: 'long', timeStyle: 'short' }).format(
      new Date(iso),
    );
  const formatSize = (bytes: number) =>
    new Intl.NumberFormat(i18n.language, {
      style: 'unit',
      unit: bytes < 1024 * 1024 ? 'kilobyte' : 'megabyte',
      maximumFractionDigits: 1,
    }).format(bytes < 1024 * 1024 ? bytes / 1024 : bytes / (1024 * 1024));

  // Moves focus once the target is rendered.
  useEffect(() => {
    const target = focusNext.current;
    if (!target) return;
    const element = {
      preview: previewHeading.current,
      result: resultHeading.current,
      uploadError: uploadError.current,
      confirm: confirmText.current,
      actionError: actionError.current,
    }[target];
    if (element) {
      focusNext.current = null;
      element.focus();
    }
  });

  const onUpload = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (uploading) return;
    setUploadFailure(null);
    if (!file) {
      setFileProblem('required');
      fileInput.current?.focus();
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      setFileProblem('tooLarge');
      fileInput.current?.focus();
      return;
    }
    setFileProblem(null);
    setUploading(true);
    try {
      const bytes = await file.arrayBuffer();
      const key = uploadKey.keyFor({
        name: file.name,
        size: file.size,
        lastModified: file.lastModified,
      });
      const { data, error, response } = await core.POST('/employee-imports', {
        params: { header: { 'Idempotency-Key': key } },
        // The exact bytes are sent: the server decodes strict UTF-8 and reports ENCODING itself.
        body: '',
        bodySerializer: () => bytes,
        headers: { 'Content-Type': 'text/csv' },
        cache: 'no-store',
      });
      if (data) {
        uploadKey.reset();
        commitKey.reset();
        setRecord(data);
        setFilter('all');
        setAcknowledged(false);
        setAckProblem(false);
        setConfirming(false);
        setActionFailure(null);
        setAnnouncement(
          t('employeeImport.announce.checked', {
            valid: data.validRows,
            invalid: data.invalidRows,
          }),
        );
        focusNext.current = 'preview';
      } else {
        // A rejected file creates nothing on the server: a new key lets a corrected file through.
        if (response.status < 500 && response.status !== 408 && response.status !== 429) {
          uploadKey.reset();
        }
        setUploadFailure(failureOf(response, error));
        focusNext.current = 'uploadError';
      }
    } catch {
      setUploadFailure(NETWORK_FAILURE);
      focusNext.current = 'uploadError';
    } finally {
      setUploading(false);
    }
  };

  const askConfirmation = () => {
    if (!record) return;
    if (record.requiresAcknowledgement && !acknowledged) {
      setAckProblem(true);
      return;
    }
    setAckProblem(false);
    setActionFailure(null);
    setConfirming(true);
    focusNext.current = 'confirm';
  };

  const commit = async () => {
    if (!record || busy) return;
    setBusy('commit');
    setActionFailure(null);
    const body = {
      previewDigest: record.previewDigest,
      validRows: record.validRows,
      acknowledgeInvalidRows: record.requiresAcknowledgement ? acknowledged : false,
    };
    try {
      const { data, error, response } = await core.POST('/employee-imports/{importId}/commit', {
        params: {
          path: { importId: record.id },
          header: { 'Idempotency-Key': commitKey.keyFor({ id: record.id, ...body }) },
        },
        body,
        cache: 'no-store',
      });
      if (data) {
        commitKey.reset();
        setRecord(data);
        setConfirming(false);
        focusNext.current = 'result';
        return;
      }
      const failure = failureOf(response, error);
      if (failure.code === 'IMPORT_PREVIEW_CHANGED') {
        // Show the current state of the import again before any new attempt.
        const current = await core.GET('/employee-imports/{importId}', {
          params: { path: { importId: record.id } },
          cache: 'no-store',
        });
        if (current.data) {
          commitKey.reset();
          setRecord(current.data);
          setAcknowledged(false);
        }
      }
      setConfirming(false);
      setActionFailure(failure);
      focusNext.current = 'actionError';
    } catch {
      setConfirming(false);
      setActionFailure(NETWORK_FAILURE);
      focusNext.current = 'actionError';
    } finally {
      setBusy(null);
    }
  };

  const discard = async () => {
    if (!record || busy) return;
    setBusy('discard');
    setActionFailure(null);
    setConfirming(false);
    try {
      const { data, error, response } = await core.POST('/employee-imports/{importId}/discard', {
        params: { path: { importId: record.id } },
        cache: 'no-store',
      });
      if (data) {
        setRecord(data);
        focusNext.current = 'result';
      } else {
        setActionFailure(failureOf(response, error));
        focusNext.current = 'actionError';
      }
    } catch {
      setActionFailure(NETWORK_FAILURE);
      focusNext.current = 'actionError';
    } finally {
      setBusy(null);
    }
  };

  const startAgain = () => {
    setRecord(null);
    setFile(null);
    setActionFailure(null);
    setUploadFailure(null);
    setConfirming(false);
    setAcknowledged(false);
    uploadKey.reset();
    commitKey.reset();
    if (fileInput.current) fileInput.current.value = '';
    setTimeout(() => fileInput.current?.focus(), 0);
  };

  const open = record?.status === 'VALIDATED';
  const committed = record?.status === 'COMMITTED';
  const closed = record !== null && !open;
  const terminal = actionFailure?.code !== undefined && TERMINAL.has(actionFailure.code);

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('employeeImport.title')}</h1>
      <p className="muted">{t('employeeImport.description')}</p>
      <p role="status" className="visually-hidden" data-testid="announcer">
        {announcement}
      </p>

      {closed && (
        <section aria-labelledby={`${ids}-result`} className="card" data-testid="import-result">
          <h2 id={`${ids}-result`} tabIndex={-1} ref={resultHeading}>
            {committed
              ? t('employeeImport.result.committedTitle')
              : t('employeeImport.result.discardedTitle')}
          </h2>
          {committed ? (
            <>
              <p data-testid="result-created">
                {t('employeeImport.result.created', { count: record.createdCount ?? 0 })}
              </p>
              <p data-testid="result-not-imported">
                {t('employeeImport.result.notImported', { count: record.notImportedCount ?? 0 })}
              </p>
              {(record.notImportedCount ?? 0) > 0 && (
                <ImportRowsTable
                  key={`${record.id}|result`}
                  importId={record.id}
                  filter="invalid"
                  caption={t('employeeImport.result.notImportedCaption')}
                  onAnnounce={setAnnouncement}
                  data-testid="not-imported-table"
                />
              )}
            </>
          ) : (
            <p>{t('employeeImport.result.discarded')}</p>
          )}
          <button type="button" className="button" onClick={startAgain}>
            {t('employeeImport.result.again')}
          </button>
        </section>
      )}

      {!closed && (
        <>
          <ImportTemplateSection />

          <section aria-labelledby={`${ids}-upload`} className="card">
            <h2 id={`${ids}-upload`}>{t('employeeImport.upload.title')}</h2>
            <form noValidate onSubmit={(event) => void onUpload(event)} data-testid="import-upload">
              <div className="field">
                <label htmlFor={`${ids}-file`}>{t('employeeImport.upload.file')}</label>
                <p id={`${ids}-file-help`} className="field__help">
                  {t('employeeImport.upload.help')}
                </p>
                <input
                  id={`${ids}-file`}
                  ref={fileInput}
                  type="file"
                  accept=".csv,text/csv"
                  aria-invalid={fileProblem ? true : undefined}
                  aria-describedby={
                    fileProblem ? `${ids}-file-help ${ids}-file-error` : `${ids}-file-help`
                  }
                  onChange={(event) => {
                    setFile(event.target.files?.[0] ?? null);
                    setFileProblem(null);
                    setUploadFailure(null);
                  }}
                />
                {file && (
                  <p className="field__help" data-testid="selected-file">
                    {t('employeeImport.upload.selected', {
                      name: file.name,
                      size: formatSize(file.size),
                    })}
                  </p>
                )}
                {fileProblem && (
                  <p id={`${ids}-file-error`} className="field__error" role="alert">
                    {t(`employeeImport.upload.${fileProblem}`)}
                  </p>
                )}
              </div>
              <div className="actions">
                <button type="submit" className="button" disabled={uploading}>
                  {uploading
                    ? t('employeeImport.upload.checking')
                    : t('employeeImport.upload.submit')}
                </button>
              </div>
            </form>
            {uploadFailure && (
              <FailureDetails
                failure={uploadFailure}
                data-testid="upload-error"
                ref={uploadError}
              />
            )}
          </section>
        </>
      )}

      {open && (
        <>
          <section aria-labelledby={`${ids}-preview`} className="card" data-testid="import-preview">
            <h2 id={`${ids}-preview`} tabIndex={-1} ref={previewHeading}>
              {t('employeeImport.preview.title')}
            </h2>
            <p data-testid="preview-counts">
              {t('employeeImport.preview.counts', {
                total: record.totalRows,
                valid: record.validRows,
                invalid: record.invalidRows,
              })}
            </p>
            <p className="muted" data-testid="preview-expires">
              {t('employeeImport.preview.expires', { date: formatDateTime(record.expiresAt) })}{' '}
              {t('employeeImport.timeZoneNote')}
            </p>
            {record.errorCounts.length > 0 && (
              <>
                <h3>{t('employeeImport.preview.errorsTitle')}</h3>
                <ul data-testid="error-counts">
                  {record.errorCounts.map((entry) => (
                    <li key={entry.code}>
                      {t('employeeImport.preview.errorCount', {
                        message: t(`employeeImport.rowErrors.${entry.code}`),
                        count: entry.count,
                      })}
                    </li>
                  ))}
                </ul>
              </>
            )}

            <fieldset className="field">
              <legend>{t('employeeImport.preview.filter')}</legend>
              {ROW_FILTERS.map((value) => (
                <label key={value} className="choice">
                  <input
                    type="radio"
                    name={`${ids}-filter`}
                    value={value}
                    checked={filter === value}
                    onChange={() => {
                      setFilter(value);
                    }}
                  />{' '}
                  {t(`employeeImport.preview.${value}`)}
                </label>
              ))}
            </fieldset>

            <ImportRowsTable
              key={`${record.id}|${filter}`}
              importId={record.id}
              filter={filter}
              caption={t('employeeImport.table.caption')}
              onAnnounce={setAnnouncement}
              data-testid="rows-table"
            />
          </section>

          <section aria-labelledby={`${ids}-commit`} className="card" data-testid="import-commit">
            <h2 id={`${ids}-commit`}>{t('employeeImport.commit.title')}</h2>
            {record.validRows === 0 ? (
              <p data-testid="nothing-to-commit">{t('employeeImport.commit.nothing')}</p>
            ) : (
              <>
                {record.requiresAcknowledgement && (
                  <div className="field">
                    <label className="choice">
                      <input
                        type="checkbox"
                        checked={acknowledged}
                        disabled={confirming || busy !== null || terminal}
                        aria-invalid={ackProblem ? true : undefined}
                        aria-describedby={ackProblem ? `${ids}-ack-error` : undefined}
                        onChange={(event) => {
                          setAcknowledged(event.target.checked);
                          setAckProblem(false);
                        }}
                      />{' '}
                      {t('employeeImport.commit.acknowledge', { count: record.invalidRows })}
                    </label>
                    {ackProblem && (
                      <p id={`${ids}-ack-error`} className="field__error" role="alert">
                        {t('employeeImport.commit.acknowledgeRequired')}
                      </p>
                    )}
                  </div>
                )}
                {confirming ? (
                  <div data-testid="commit-confirm">
                    <p tabIndex={-1} ref={confirmText}>
                      {t('employeeImport.commit.confirm', { count: record.validRows })}
                    </p>
                    <div className="actions">
                      <button
                        type="button"
                        className="button"
                        disabled={busy !== null}
                        onClick={() => void commit()}
                      >
                        {busy === 'commit'
                          ? t('employeeImport.commit.importing')
                          : t('employeeImport.commit.confirmYes')}
                      </button>
                      <button
                        type="button"
                        className="button button--secondary"
                        disabled={busy !== null}
                        onClick={() => {
                          setConfirming(false);
                          setTimeout(() => commitButton.current?.focus(), 0);
                        }}
                      >
                        {t('employeeImport.commit.confirmNo')}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div className="actions">
                    <button
                      type="button"
                      className="button"
                      ref={commitButton}
                      disabled={busy !== null || terminal}
                      onClick={askConfirmation}
                    >
                      {actionFailure && !terminal
                        ? t('employeeImport.commit.retry')
                        : t('employeeImport.commit.submit', { count: record.validRows })}
                    </button>
                  </div>
                )}
              </>
            )}
            {actionFailure && (
              <FailureDetails
                failure={actionFailure}
                data-testid="commit-error"
                ref={actionError}
              />
            )}
            <div className="actions">
              {terminal ? (
                <button type="button" className="button button--secondary" onClick={startAgain}>
                  {t('employeeImport.result.again')}
                </button>
              ) : (
                <button
                  type="button"
                  className="button button--secondary"
                  disabled={busy !== null}
                  onClick={() => void discard()}
                >
                  {busy === 'discard'
                    ? t('employeeImport.commit.discarding')
                    : t('employeeImport.commit.discard')}
                </button>
              )}
            </div>
          </section>
        </>
      )}
    </section>
  );
}

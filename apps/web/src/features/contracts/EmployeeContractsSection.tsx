import type {
  Contract,
  ContractPreview,
  ContractSummary,
  ContractVoidReason,
} from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatPeriod } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { AcknowledgementEvidence } from './AcknowledgementEvidence';
import { ContractAlert } from './ContractAlert';
import { ContractDocument } from './ContractDocument';
import {
  CONTRACT_NETWORK_FAILURE,
  contractFailureOf,
  END_REQUIRED,
  formatInstant,
  VOID_REASONS,
  type ContractFailure,
} from './contracts';

type Option = { versionId: string; label: string; contractType: string };

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ContractFailure }
  | { kind: 'ready'; items: ContractSummary[]; nextCursor: string | null; options: Option[] };

/**
 * MVP-030: an employee's contracts on their profile. An administrator previews a contract from an
 * approved template version (the exact text the employee will see), confirms the issue with the
 * preview's employment version and digest, reads each contract with its acknowledgement
 * evidence, and voids a contract that has not been acknowledged.
 */
export function EmployeeContractsSection({
  employeeId,
  businessDate,
  revision,
  onChanged,
}: {
  employeeId: string;
  businessDate: string;
  revision: number;
  onChanged: (messageKey: string, values: Record<string, string>, refresh: boolean) => void;
}) {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const issueKey = useIdempotencyKey();
  const voidKey = useIdempotencyKey();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [local, setLocal] = useState(0);
  const [versionId, setVersionId] = useState('');
  const [start, setStart] = useState(businessDate);
  const [end, setEnd] = useState('');
  const [problems, setProblems] = useState<Set<string>>(new Set());
  const [preview, setPreview] = useState<ContractPreview | null>(null);
  const [failure, setFailure] = useState<ContractFailure | null>(null);
  const [shown, setShown] = useState<Contract | null>(null);
  const [voidReason, setVoidReason] = useState<ContractVoidReason | ''>('');
  const [busy, setBusy] = useState(false);
  const previewHeading = useRef<HTMLHeadingElement>(null);
  const failureBox = useRef<HTMLDivElement>(null);
  const focusNext = useRef<'preview' | 'failure' | null>(null);

  useEffect(() => {
    const target = focusNext.current === 'preview' ? previewHeading.current : failureBox.current;
    if (focusNext.current && target) {
      focusNext.current = null;
      target.focus();
    }
  });

  const load = useCallback(
    async (cursor: string | null): Promise<Loaded> => {
      try {
        const [contracts, templates] = await Promise.all([
          core.GET('/employees/{employeeId}/contracts', {
            params: { path: { employeeId }, query: cursor ? { cursor } : {} },
            cache: 'no-store',
          }),
          core.GET('/contract-templates', {
            params: { query: { limit: 50 } },
            cache: 'no-store',
          }),
        ]);
        if (!contracts.data) {
          return {
            kind: 'failed',
            failure: contractFailureOf(contracts.response, contracts.error),
          };
        }
        const options: Option[] = [];
        for (const template of templates.data?.items ?? []) {
          for (const line of template.lines) {
            if (line.approvedVersionId && line.approvedVersionNumber !== null) {
              options.push({
                versionId: line.approvedVersionId,
                contractType: template.contractType,
                label: t('contracts.issue.templateOption', {
                  name: template.name,
                  code: template.code,
                  language: t(`contracts.languages.${line.locale}`),
                  number: line.approvedVersionNumber,
                }),
              });
            }
          }
        }
        return {
          kind: 'ready',
          items: contracts.data.items,
          nextCursor: contracts.data.nextCursor,
          options,
        };
      } catch {
        return { kind: 'failed', failure: CONTRACT_NETWORK_FAILURE };
      }
    },
    [core, employeeId, t],
  );

  useEffect(() => {
    let active = true;
    void load(null).then((next) => {
      if (active) setLoaded(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision, local]);

  const fail = (next: ContractFailure) => {
    setFailure(next);
    focusNext.current = 'failure';
    if (next.code === 'CONTRACT_PREVIEW_CHANGED') setPreview(null);
  };

  const command = () => ({
    templateVersionId: versionId,
    startDate: start,
    endDate: end === '' ? null : end,
  });

  const onPreview = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = new Set<string>();
    if (!versionId) found.add('template');
    if (!start) found.add('start');
    if (end !== '' && end < start) found.add('end');
    setProblems(found);
    setPreview(null);
    if (found.size > 0) return;
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/contracts/preview',
        { params: { path: { employeeId } }, body: command() },
      );
      if (data) {
        setPreview(data);
        focusNext.current = 'preview';
      } else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const onIssue = async () => {
    if (!preview) return;
    const body = {
      ...command(),
      expectedEmploymentVersion: preview.employmentVersion,
      previewDigest: preview.previewDigest,
    };
    setBusy(true);
    try {
      const { data, error, response } = await core.POST('/employees/{employeeId}/contracts', {
        params: {
          path: { employeeId },
          header: { 'Idempotency-Key': issueKey.keyFor({ employeeId, ...body }) },
        },
        body,
      });
      if (data) {
        issueKey.reset();
        setPreview(null);
        setVersionId('');
        setEnd('');
        setFailure(null);
        setShown(data);
        setLocal((value) => value + 1);
        onChanged('contracts.issue.announce.issued', {}, false);
      } else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const toggle = async (summary: ContractSummary) => {
    if (shown?.id === summary.id) {
      setShown(null);
      return;
    }
    try {
      const { data, error, response } = await core.GET(
        '/employees/{employeeId}/contracts/{contractId}',
        { params: { path: { employeeId, contractId: summary.id } }, cache: 'no-store' },
      );
      if (data) {
        setShown(data);
        setVoidReason('');
      } else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    }
  };

  const onVoid = async (contract: Contract) => {
    if (!voidReason) return;
    const body = { expectedVersion: contract.version, reasonCode: voidReason };
    setBusy(true);
    try {
      const { data, error, response } = await core.POST(
        '/employees/{employeeId}/contracts/{contractId}/void',
        {
          params: {
            path: { employeeId, contractId: contract.id },
            header: { 'Idempotency-Key': voidKey.keyFor({ id: contract.id, ...body }) },
          },
          body,
        },
      );
      if (data) {
        voidKey.reset();
        setShown(data);
        setFailure(null);
        setLocal((value) => value + 1);
        onChanged('contracts.issue.announce.voided', {}, false);
      } else fail(contractFailureOf(response, error));
    } catch {
      fail(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  const selected =
    loaded.kind === 'ready' ? loaded.options.find((o) => o.versionId === versionId) : undefined;

  return (
    <section aria-labelledby={`${ids}-title`} className="card" data-testid="contracts">
      <h2 id={`${ids}-title`}>{t('contracts.issue.title')}</h2>
      {failure && <ContractAlert failure={failure} ref={failureBox} data-testid="contract-error" />}
      {loaded.kind === 'loading' && <p role="status">{t('contracts.loading')}</p>}
      {loaded.kind === 'failed' && (
        <ContractAlert failure={loaded.failure} data-testid="contracts-error" />
      )}
      {loaded.kind === 'ready' && (
        <>
          {loaded.items.length === 0 ? (
            <p data-testid="contracts-none">{t('contracts.issue.none')}</p>
          ) : (
            <ScrollRegion label={t('contracts.issue.list')}>
              <table data-testid="contract-list">
                <caption className="visually-hidden">{t('contracts.issue.title')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('contracts.issue.columns.type')}</th>
                    <th scope="col">{t('contracts.issue.columns.period')}</th>
                    <th scope="col">{t('contracts.issue.columns.language')}</th>
                    <th scope="col">{t('contracts.issue.columns.state')}</th>
                    <th scope="col">{t('contracts.issue.columns.issued')}</th>
                  </tr>
                </thead>
                <tbody>
                  {loaded.items.map((item) => (
                    <tr key={item.id} data-testid="contract-row" data-state={item.state}>
                      <td>
                        <button
                          type="button"
                          className="button button--secondary"
                          aria-expanded={shown?.id === item.id}
                          onClick={() => void toggle(item)}
                        >
                          {t(`employees.contract.${item.contractType}`)}
                        </button>
                      </td>
                      <td>{formatPeriod(t, i18n.language, item.startDate, item.endDate)}</td>
                      <td>{t(`contracts.languages.${item.locale}`)}</td>
                      <td>{t(`contracts.issue.state.${item.state}`)}</td>
                      <td>{formatInstant(i18n.language, item.issuedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
          )}
          {loaded.nextCursor && (
            <button
              type="button"
              className="button button--secondary"
              onClick={() => {
                const cursor = loaded.nextCursor;
                void load(cursor).then((next) => {
                  setLoaded((previous) =>
                    next.kind === 'ready' && previous.kind === 'ready'
                      ? { ...next, items: [...previous.items, ...next.items] }
                      : next,
                  );
                });
              }}
            >
              {t('contracts.issue.more')}
            </button>
          )}
        </>
      )}

      {shown && (
        <div data-testid="contract-detail">
          <p data-testid="contract-state">{t(`contracts.issue.state.${shown.state}`)}</p>
          {shown.state === 'VOID' && shown.voidedAt && shown.voidReason && (
            <p>
              {t('contracts.issue.voided', {
                at: formatInstant(i18n.language, shown.voidedAt),
                reason: t(`contracts.issue.voidReasons.${shown.voidReason}`),
              })}
            </p>
          )}
          <ContractDocument
            snapshot={shown.snapshot}
            locale={shown.locale}
            integrity={shown.integrity}
            level={3}
          />
          {shown.acknowledgement && <AcknowledgementEvidence evidence={shown.acknowledgement} />}
          {shown.state === 'ISSUED' && (
            <div className="field" data-testid="void">
              <label htmlFor={`${ids}-void`}>{t('contracts.issue.voidReason')}</label>
              <p className="field__help">{t('contracts.issue.voidHelp')}</p>
              <select
                id={`${ids}-void`}
                value={voidReason}
                onChange={(event) => {
                  setVoidReason(event.target.value as ContractVoidReason | '');
                }}
              >
                <option value="">{t('employees.placement.choose')}</option>
                {VOID_REASONS.map((reason) => (
                  <option key={reason} value={reason}>
                    {t(`contracts.issue.voidReasons.${reason}`)}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="button button--secondary"
                disabled={busy || !voidReason}
                onClick={() => void onVoid(shown)}
              >
                {t('contracts.issue.voidConfirm')}
              </button>
            </div>
          )}
        </div>
      )}

      <h3>{t('contracts.issue.formTitle')}</h3>
      {loaded.kind === 'ready' && loaded.options.length === 0 ? (
        <p data-testid="no-template">{t('contracts.issue.noTemplate')}</p>
      ) : (
        <form noValidate onSubmit={(event) => void onPreview(event)} data-testid="issue-form">
          {problems.size > 0 && (
            <div className="error-summary" role="alert" data-testid="issue-problems">
              <p>{t('employees.form.problems.title')}</p>
              <ul>
                {[...problems].map((problem) => (
                  <li key={problem}>{t(`contracts.issue.problems.${problem}`)}</li>
                ))}
              </ul>
            </div>
          )}
          <div className="field">
            <label htmlFor={`${ids}-version`}>{t('contracts.issue.template')}</label>
            <select
              id={`${ids}-version`}
              value={versionId}
              aria-invalid={problems.has('template') ? true : undefined}
              onChange={(event) => {
                setVersionId(event.target.value);
                setPreview(null);
              }}
            >
              <option value="">{t('employees.placement.choose')}</option>
              {loaded.kind === 'ready' &&
                loaded.options.map((option) => (
                  <option key={option.versionId} value={option.versionId}>
                    {option.label}
                  </option>
                ))}
            </select>
          </div>
          <div className="field">
            <label htmlFor={`${ids}-start`}>{t('contracts.issue.start')}</label>
            <input
              id={`${ids}-start`}
              type="date"
              value={start}
              aria-invalid={problems.has('start') ? true : undefined}
              onChange={(event) => {
                setStart(event.target.value);
                setPreview(null);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={`${ids}-end`}>{t('contracts.issue.end')}</label>
            <p id={`${ids}-end-help`} className="field__help">
              {t('contracts.issue.endHelp')}
            </p>
            <input
              id={`${ids}-end`}
              type="date"
              value={end}
              aria-describedby={`${ids}-end-help`}
              aria-required={selected ? END_REQUIRED.has(selected.contractType) : undefined}
              aria-invalid={problems.has('end') ? true : undefined}
              onChange={(event) => {
                setEnd(event.target.value);
                setPreview(null);
              }}
            />
          </div>
          <button type="submit" className="button button--secondary" disabled={busy}>
            {t('contracts.issue.preview')}
          </button>
        </form>
      )}

      {preview && (
        <section aria-labelledby={`${ids}-preview`} data-testid="contract-preview">
          <h3 id={`${ids}-preview`} tabIndex={-1} ref={previewHeading}>
            {t('contracts.issue.previewTitle')}
          </h3>
          <p>
            {t(`employees.contract.${preview.contractType}`)} ·{' '}
            {formatPeriod(t, i18n.language, preview.startDate, preview.endDate)}
          </p>
          {preview.warnings.length > 0 && (
            <ul className="notice" data-testid="contract-warnings">
              {preview.warnings.map((warning) => (
                <li key={warning}>{t(`contracts.issue.warnings.${warning}`)}</li>
              ))}
            </ul>
          )}
          <ContractDocument
            snapshot={preview.snapshot}
            locale={preview.locale}
            integrity={preview.integrity}
            level={3}
          />
          <button type="button" className="button" disabled={busy} onClick={() => void onIssue()}>
            {t('contracts.issue.confirm')}
          </button>
        </section>
      )}
    </section>
  );
}

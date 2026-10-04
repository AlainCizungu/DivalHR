import type { MyContract } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { formatPeriod } from '../people/history';
import { AcknowledgementEvidence } from './AcknowledgementEvidence';
import { ContractAlert } from './ContractAlert';
import { ContractDocument } from './ContractDocument';
import { CONTRACT_NETWORK_FAILURE, contractFailureOf, type ContractFailure } from './contracts';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ContractFailure }
  | { kind: 'ready'; contract: MyContract };

/**
 * MVP-030: one of the employee's own contracts, displayed in full, then acknowledged with the
 * server-owned statement shown in the interface language (« Accuser réception » / "Acknowledge
 * receipt"). The request repeats the snapshot and statement digests that were displayed; the
 * server recomputes and compares them (A30-4). An acknowledgement records only that the employee
 * made the statement; it is not an electronic signature (A30-3).
 */
export function MyContractPage() {
  const { contractId = '' } = useParams();
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const ackKey = useIdempotencyKey();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [revision, setRevision] = useState(0);
  const [confirmed, setConfirmed] = useState(false);
  const [tick, setTick] = useState(false);
  const [failure, setFailure] = useState<ContractFailure | null>(null);
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const evidenceHeading = useRef<HTMLHeadingElement>(null);
  const failureBox = useRef<HTMLDivElement>(null);
  const focusNext = useRef<'evidence' | 'failure' | null>(null);

  useEffect(() => {
    const target = focusNext.current === 'evidence' ? evidenceHeading.current : failureBox.current;
    if (focusNext.current && target) {
      focusNext.current = null;
      target.focus();
    }
  });

  const load = useCallback(async (): Promise<Loaded> => {
    try {
      const { data, error, response } = await core.GET('/me/contracts/{contractId}', {
        params: { path: { contractId } },
        cache: 'no-store',
      });
      return data
        ? { kind: 'ready', contract: data }
        : {
            kind: 'failed',
            failure: contractFailureOf(response, error, 'contracts.my.unauthorized'),
          };
    } catch {
      return { kind: 'failed', failure: CONTRACT_NETWORK_FAILURE };
    }
  }, [core, contractId]);

  useEffect(() => {
    let active = true;
    void load().then((next) => {
      if (active) setLoaded(next);
    });
    return () => {
      active = false;
    };
  }, [load, revision]);

  if (loaded.kind === 'loading') return <p role="status">{t('contracts.loading')}</p>;
  if (loaded.kind === 'failed') {
    return (
      <section aria-labelledby={`${ids}-title`}>
        <h1 id={`${ids}-title`}>{t('contracts.my.title')}</h1>
        <ContractAlert failure={loaded.failure} data-testid="my-contract-error" />
        <p>
          <Link to="/me/contracts">{t('contracts.my.back')}</Link>
        </p>
      </section>
    );
  }
  const { contract } = loaded;
  const statement =
    contract.statements.find((s) => s.locale === i18n.language) ?? contract.statements[0];

  const acknowledge = async () => {
    if (!statement) return;
    if (!confirmed) {
      setTick(true);
      return;
    }
    const body = {
      snapshotSha256: contract.integrity.snapshotSha256,
      snapshotDigestVersion: contract.integrity.digestVersion,
      grammarVersion: contract.integrity.grammarVersion,
      rendererVersion: contract.integrity.rendererVersion,
      statementCode: statement.code,
      statementVersion: statement.version,
      statementLocale: statement.locale,
      statementSha256: statement.sha256,
    };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST(
        '/me/contracts/{contractId}/acknowledgement',
        {
          params: {
            path: { contractId },
            header: { 'Idempotency-Key': ackKey.keyFor({ contractId, ...body }) },
          },
          body,
        },
      );
      if (data) {
        ackKey.reset();
        setLoaded({ kind: 'ready', contract: data.contract });
        setNotice(t('contracts.my.acknowledged'));
        focusNext.current = 'evidence';
        return;
      }
      const next = contractFailureOf(response, error, 'contracts.my.unauthorized');
      if (next.code === 'CONTRACT_ACKNOWLEDGEMENT_CHANGED') {
        next.messageKey = 'contracts.my.changed';
        setConfirmed(false);
        setRevision((value) => value + 1);
      }
      setFailure(next);
      focusNext.current = 'failure';
    } catch {
      setFailure(CONTRACT_NETWORK_FAILURE);
      focusNext.current = 'failure';
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <p>
        <Link to="/me/contracts">{t('contracts.my.back')}</Link>
      </p>
      <h1 id={`${ids}-title`}>{t(`employees.contract.${contract.contractType}`)}</h1>
      <p>{formatPeriod(t, i18n.language, contract.startDate, contract.endDate)}</p>
      <p data-testid="my-contract-state" data-state={contract.state}>
        {t(`contracts.issue.state.${contract.state}`)}
      </p>
      <p role="status" data-testid="announcer">
        {notice}
      </p>
      <ContractDocument
        snapshot={contract.snapshot}
        locale={contract.locale}
        integrity={contract.integrity}
      />
      {failure && <ContractAlert failure={failure} ref={failureBox} data-testid="ack-error" />}
      {contract.state === 'VOID' && <p data-testid="voided">{t('contracts.my.voided')}</p>}
      {contract.state === 'ISSUED' && statement && (
        <section aria-labelledby={`${ids}-ack`} className="card" data-testid="acknowledge">
          <h2 id={`${ids}-ack`}>{t('contracts.my.acknowledgeTitle')}</h2>
          {tick && (
            <p className="field__error" role="alert">
              {t('contracts.my.tick')}
            </p>
          )}
          <div className="choice">
            <input
              id={`${ids}-statement`}
              type="checkbox"
              checked={confirmed}
              aria-invalid={tick ? true : undefined}
              aria-describedby={`${ids}-disclaimer`}
              onChange={(event) => {
                setConfirmed(event.target.checked);
                setTick(false);
              }}
            />
            <label htmlFor={`${ids}-statement`} lang={statement.locale}>
              {statement.text}
            </label>
          </div>
          <p id={`${ids}-disclaimer`} className="field__help">
            {t('contracts.my.disclaimer')}
          </p>
          <button
            type="button"
            className="button"
            disabled={busy}
            onClick={() => void acknowledge()}
          >
            {t('contracts.my.acknowledge')}
          </button>
        </section>
      )}
      {contract.acknowledgement && (
        <AcknowledgementEvidence evidence={contract.acknowledgement} headingRef={evidenceHeading} />
      )}
    </section>
  );
}

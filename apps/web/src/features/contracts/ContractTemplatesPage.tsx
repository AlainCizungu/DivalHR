import type { ContractClassification, ContractTemplateSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useRef, useState, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { useIdempotencyKey } from '../hierarchy/useIdempotencyKey';
import { CONTRACTS, codePoints } from '../people/history';
import { ScrollRegion } from '../people/ScrollRegion';
import { ContractAlert } from './ContractAlert';
import { CONTRACT_NETWORK_FAILURE, contractFailureOf, type ContractFailure } from './contracts';

const CODE = /^[A-Z0-9][A-Z0-9_-]{0,31}$/u;

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ContractFailure }
  | { kind: 'ready'; items: ContractTemplateSummary[]; nextCursor: string | null };

/**
 * MVP-030: the organization's contract templates, each with the state of its French and English
 * lines, and the creation of a template (code, name and type; its text lives in versions).
 */
export function ContractTemplatesPage() {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const navigate = useNavigate();
  const createKey = useIdempotencyKey();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [type, setType] = useState<ContractClassification | ''>('');
  const [problems, setProblems] = useState<Set<string>>(new Set());
  const [failure, setFailure] = useState<ContractFailure | null>(null);
  const [busy, setBusy] = useState(false);
  const failureBox = useRef<HTMLDivElement>(null);

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Loaded> => {
      try {
        const { data, error, response } = await core.GET('/contract-templates', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : { kind: 'failed', failure: contractFailureOf(response, error) };
      } catch {
        return { kind: 'failed', failure: CONTRACT_NETWORK_FAILURE };
      }
    },
    [core],
  );

  useEffect(() => {
    let active = true;
    void fetchPage(null).then((next) => {
      if (active) setLoaded(next);
    });
    return () => {
      active = false;
    };
  }, [fetchPage]);

  const more = (cursor: string) => {
    void fetchPage(cursor).then((next) => {
      setLoaded((previous) =>
        next.kind === 'ready' && previous.kind === 'ready'
          ? { ...next, items: [...previous.items, ...next.items] }
          : next,
      );
    });
  };

  useEffect(() => {
    if (failure) failureBox.current?.focus();
  }, [failure]);

  const onCreate = async (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    const found = new Set<string>();
    if (!CODE.test(code)) found.add('code');
    const length = codePoints(name.trim());
    if (length < 2 || length > 160) found.add('name');
    if (!type) found.add('type');
    setProblems(found);
    if (found.size > 0 || !type) return;
    const body = { code, name: name.trim(), contractType: type };
    setBusy(true);
    setFailure(null);
    try {
      const { data, error, response } = await core.POST('/contract-templates', {
        body,
        params: { header: { 'Idempotency-Key': createKey.keyFor(body) } },
      });
      if (data) {
        createKey.reset();
        void navigate(`/admin/contract-templates/${data.id}`, {
          state: { announce: 'contracts.templates.announce.created' },
        });
        return;
      }
      setFailure(contractFailureOf(response, error));
    } catch {
      setFailure(CONTRACT_NETWORK_FAILURE);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('contracts.templates.title')}</h1>
      <p className="muted">{t('contracts.templates.intro')}</p>

      {loaded.kind === 'loading' && <p role="status">{t('contracts.loading')}</p>}
      {loaded.kind === 'failed' && (
        <ContractAlert failure={loaded.failure} data-testid="templates-error" />
      )}
      {loaded.kind === 'ready' &&
        (loaded.items.length === 0 ? (
          <p data-testid="templates-empty">{t('contracts.templates.empty')}</p>
        ) : (
          <ScrollRegion label={t('contracts.templates.list')}>
            <table data-testid="templates">
              <caption className="visually-hidden">{t('contracts.templates.title')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('contracts.templates.columns.code')}</th>
                  <th scope="col">{t('contracts.templates.columns.name')}</th>
                  <th scope="col">{t('contracts.templates.columns.type')}</th>
                  <th scope="col">{t('contracts.templates.columns.lines')}</th>
                </tr>
              </thead>
              <tbody>
                {loaded.items.map((item) => (
                  <tr key={item.id}>
                    <td>{item.code}</td>
                    <td>
                      <Link
                        to={`/admin/contract-templates/${item.id}`}
                        aria-label={t('contracts.templates.open', { name: item.name })}
                      >
                        {item.name}
                      </Link>
                    </td>
                    <td>{t(`employees.contract.${item.contractType}`)}</td>
                    <td>
                      <ul className="plain-list">
                        {item.lines.map((line) => {
                          const language = t(`contracts.languages.${line.locale}`);
                          return (
                            <li key={line.locale}>
                              {line.approvedVersionNumber !== null
                                ? t('contracts.templates.line.approved', {
                                    language,
                                    number: line.approvedVersionNumber,
                                  })
                                : t('contracts.templates.line.none', { language })}
                              {line.draftVersionId !== null &&
                                ` · ${t('contracts.templates.line.draft', { language })}`}
                            </li>
                          );
                        })}
                      </ul>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollRegion>
        ))}
      {loaded.kind === 'ready' && loaded.nextCursor && (
        <p>
          <button
            type="button"
            className="button button--secondary"
            onClick={() => {
              if (loaded.nextCursor) more(loaded.nextCursor);
            }}
          >
            {t('contracts.templates.more')}
          </button>
        </p>
      )}

      <section aria-labelledby={`${ids}-create`} className="card" data-testid="create-template">
        <h2 id={`${ids}-create`}>{t('contracts.templates.create.title')}</h2>
        {problems.size > 0 && (
          <div className="error-summary" role="alert" data-testid="create-problems">
            <p>{t('employees.form.problems.title')}</p>
            <ul>
              {[...problems].map((problem) => (
                <li key={problem}>{t(`contracts.templates.create.problems.${problem}`)}</li>
              ))}
            </ul>
          </div>
        )}
        {failure && <ContractAlert failure={failure} ref={failureBox} data-testid="create-error" />}
        <form noValidate onSubmit={(event) => void onCreate(event)}>
          <div className="field">
            <label htmlFor={`${ids}-code`}>{t('contracts.templates.create.code')}</label>
            <p id={`${ids}-code-help`} className="field__help">
              {t('contracts.templates.create.codeHelp')}
            </p>
            <input
              id={`${ids}-code`}
              type="text"
              value={code}
              maxLength={32}
              autoComplete="off"
              aria-describedby={`${ids}-code-help`}
              aria-invalid={problems.has('code') ? true : undefined}
              onChange={(event) => {
                setCode(event.target.value.toUpperCase());
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={`${ids}-name`}>{t('contracts.templates.create.name')}</label>
            <input
              id={`${ids}-name`}
              type="text"
              value={name}
              maxLength={160}
              autoComplete="off"
              aria-invalid={problems.has('name') ? true : undefined}
              onChange={(event) => {
                setName(event.target.value);
              }}
            />
          </div>
          <div className="field">
            <label htmlFor={`${ids}-type`}>{t('contracts.templates.create.type')}</label>
            <select
              id={`${ids}-type`}
              value={type}
              aria-invalid={problems.has('type') ? true : undefined}
              onChange={(event) => {
                setType(event.target.value as ContractClassification | '');
              }}
            >
              <option value="">{t('employees.placement.choose')}</option>
              {CONTRACTS.map((option) => (
                <option key={option} value={option}>
                  {t(`employees.contract.${option}`)}
                </option>
              ))}
            </select>
          </div>
          <button type="submit" className="button" disabled={busy}>
            {t('contracts.templates.create.submit')}
          </button>
        </form>
      </section>
    </section>
  );
}

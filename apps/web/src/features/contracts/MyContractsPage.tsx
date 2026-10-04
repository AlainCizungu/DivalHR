import type { MyContractSummary } from '@divalhr/api-client';
import { useCallback, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { formatPeriod } from '../people/history';
import { ContractAlert } from './ContractAlert';
import { CONTRACT_NETWORK_FAILURE, contractFailureOf, type ContractFailure } from './contracts';

type Loaded =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: ContractFailure }
  | { kind: 'ready'; items: MyContractSummary[]; nextCursor: string | null };

/** MVP-030: « Mes contrats » / "My contracts": the authenticated employee's own contracts. */
export function MyContractsPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [loaded, setLoaded] = useState<Loaded>({ kind: 'loading' });

  const fetchPage = useCallback(
    async (cursor: string | null): Promise<Loaded> => {
      try {
        const { data, error, response } = await core.GET('/me/contracts', {
          params: { query: cursor ? { cursor } : {} },
          cache: 'no-store',
        });
        return data
          ? { kind: 'ready', items: data.items, nextCursor: data.nextCursor }
          : {
              kind: 'failed',
              failure: contractFailureOf(response, error, 'contracts.my.unauthorized'),
            };
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

  return (
    <section aria-labelledby={`${ids}-title`}>
      <h1 id={`${ids}-title`}>{t('contracts.my.title')}</h1>
      <p className="muted">{t('contracts.my.intro')}</p>
      {loaded.kind === 'loading' && <p role="status">{t('contracts.loading')}</p>}
      {loaded.kind === 'failed' && (
        <ContractAlert failure={loaded.failure} data-testid="my-contracts-error" />
      )}
      {loaded.kind === 'ready' &&
        (loaded.items.length === 0 ? (
          <p data-testid="my-contracts-none">{t('contracts.my.none')}</p>
        ) : (
          <ul className="plain-list" data-testid="my-contracts">
            {loaded.items.map((item) => (
              <li key={item.id} className="hierarchy-item" data-state={item.state}>
                <h2>
                  <Link to={`/me/contracts/${item.id}`}>
                    {t(`employees.contract.${item.contractType}`)}
                  </Link>
                </h2>
                <p>{formatPeriod(t, i18n.language, item.startDate, item.endDate)}</p>
                <p data-testid="my-contract-state">{t(`contracts.issue.state.${item.state}`)}</p>
              </li>
            ))}
          </ul>
        ))}
      {loaded.kind === 'ready' && loaded.nextCursor && (
        <button
          type="button"
          className="button button--secondary"
          onClick={() => {
            if (loaded.nextCursor) more(loaded.nextCursor);
          }}
        >
          {t('contracts.issue.more')}
        </button>
      )}
    </section>
  );
}

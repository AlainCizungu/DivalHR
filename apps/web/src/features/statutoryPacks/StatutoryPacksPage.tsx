import type { StatutoryPackVersionSummary } from '@divalhr/api-client';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { PageHeader, StatusBadge } from '../../ui/primitives';
import { PayrollAlert } from '../payrollRuns/PayrollBits';
import { formatDay, NETWORK_FAILURE, type PayrollFailure } from '../payrollRuns/payrollRuns';
import { ScrollRegion } from '../people/ScrollRegion';
import { LegalNotice, StateBadge } from './StatutoryPackBits';
import { packFailureOf, versionPath } from './statutoryPacks';

type Load =
  | { kind: 'loading' }
  | { kind: 'failed'; failure: PayrollFailure }
  | { kind: 'ready'; items: StatutoryPackVersionSummary[]; next: string | null };

/**
 * MVP-097A: the platform list of statutory pack versions. A platform administrator sees the state,
 * the effective interval and the last technical validation of each version, and opens one to act.
 * Nothing here is an approval: the permanent notice says so.
 */
export function StatutoryPacksPage() {
  const { t, i18n } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const [load, setLoad] = useState<Load>({ kind: 'loading' });
  const [moreBusy, setMoreBusy] = useState(false);
  const loadingMore = useRef(false);

  useEffect(() => {
    let active = true;
    core
      .GET('/platform/statutory-packs', { params: { query: { limit: 25 } }, cache: 'no-store' })
      .then(
        ({ data, error, response }) => {
          if (!active) return;
          setLoad(
            data
              ? { kind: 'ready', items: data.items, next: data.nextCursor }
              : { kind: 'failed', failure: packFailureOf(response, error) },
          );
        },
        () => {
          if (active) setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
        },
      );
    return () => {
      active = false;
    };
  }, [core]);

  const more = async () => {
    if (load.kind !== 'ready' || load.next === null || loadingMore.current) return;
    loadingMore.current = true;
    setMoreBusy(true);
    try {
      const { data, error, response } = await core.GET('/platform/statutory-packs', {
        params: { query: { limit: 25, cursor: load.next } },
        cache: 'no-store',
      });
      setLoad(
        data
          ? { kind: 'ready', items: [...load.items, ...data.items], next: data.nextCursor }
          : { kind: 'failed', failure: packFailureOf(response, error) },
      );
    } catch {
      setLoad({ kind: 'failed', failure: NETWORK_FAILURE });
    } finally {
      loadingMore.current = false;
      setMoreBusy(false);
    }
  };

  return (
    <section aria-labelledby={`${ids}-title`} data-testid="statutory-packs-page">
      <PageHeader
        titleId={`${ids}-title`}
        title={t('statutoryPacks.title')}
        description={t('statutoryPacks.description')}
      />
      <LegalNotice />
      {load.kind === 'loading' && (
        <p role="status" data-testid="statutory-packs-loading">
          {t('statutoryPacks.loading')}
        </p>
      )}
      {load.kind === 'failed' && (
        <PayrollAlert failure={load.failure} testId="statutory-packs-failure" />
      )}
      {load.kind === 'ready' &&
        (load.items.length === 0 ? (
          <p className="muted" data-testid="statutory-packs-empty">
            {t('statutoryPacks.empty')}
          </p>
        ) : (
          <>
            <ScrollRegion label={t('statutoryPacks.list.tableLabel')}>
              <table className="timesheet-table" data-testid="statutory-packs-table">
                <caption className="visually-hidden">{t('statutoryPacks.list.tableLabel')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t('statutoryPacks.list.pack')}</th>
                    <th scope="col">{t('statutoryPacks.list.country')}</th>
                    <th scope="col">{t('statutoryPacks.list.state')}</th>
                    <th scope="col">{t('statutoryPacks.list.effective')}</th>
                    <th scope="col">{t('statutoryPacks.list.validation')}</th>
                  </tr>
                </thead>
                <tbody>
                  {load.items.map((item) => (
                    <tr
                      key={`${item.packCode}-${item.version}`}
                      data-testid="statutory-pack-row"
                      data-pack-code={item.packCode}
                      data-state={item.state}
                    >
                      <th scope="row">
                        <Link to={versionPath(item.packCode, item.version)}>
                          {t('statutoryPacks.list.packVersion', {
                            code: item.packCode,
                            version: item.version,
                          })}
                        </Link>
                        <span className="muted"> {item.semanticVersion}</span>
                      </th>
                      <td>{item.country}</td>
                      <td>
                        <StateBadge state={item.state} />
                        {item.state === 'DRAFT' && (
                          <p className="muted" data-testid="statutory-pack-draft-notice">
                            {t('statutoryPacks.draftNotice')}
                          </p>
                        )}
                        {item.state === 'LEGACY' && (
                          <p className="muted" data-testid="statutory-pack-legacy-notice">
                            {t('statutoryPacks.legacyNotice')}
                          </p>
                        )}
                      </td>
                      <td>
                        {formatDay(i18n.language, item.effectiveFrom)}
                        {' – '}
                        {item.effectiveTo
                          ? formatDay(i18n.language, item.effectiveTo)
                          : t('statutoryPacks.openEnded')}
                      </td>
                      <td>
                        {item.lastValidation ? (
                          <StatusBadge
                            tone={item.lastValidation === 'PASSED' ? 'success' : 'danger'}
                          >
                            {t(`statutoryPacks.validation.${item.lastValidation}`)}
                          </StatusBadge>
                        ) : (
                          <span className="muted">{t('statutoryPacks.validation.NONE')}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </ScrollRegion>
            {load.next !== null && (
              <button
                type="button"
                className="button button--secondary"
                disabled={moreBusy}
                onClick={() => void more()}
                data-testid="statutory-packs-more"
              >
                {t('statutoryPacks.list.more')}
              </button>
            )}
          </>
        ))}
    </section>
  );
}

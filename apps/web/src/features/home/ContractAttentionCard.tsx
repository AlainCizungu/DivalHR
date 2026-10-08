import type { ContractExpirationCounts } from '@divalhr/api-client';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useApi } from '../../app/ApiProvider';
import { Icon } from '../../ui/Icon';
import { Card, Skeleton } from '../../ui/primitives';

type Summary =
  { kind: 'loading' } | { kind: 'failed' } | { kind: 'ready'; counts: ContractExpirationCounts };

/**
 * MVP-031A (C3b, a narrow amendment of UI-001 D10): the one live figure on a role home. It renders
 * only for organization administrators, reads the server's summary (the same rule and business
 * date as the queue) and always keeps its link, whatever the count's state.
 */
export function ContractAttentionCard() {
  const { t } = useTranslation();
  const { core } = useApi();
  const id = useId();
  const [summary, setSummary] = useState<Summary>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    core.GET('/contract-expirations/summary', { cache: 'no-store' }).then(
      ({ data }) => {
        if (active) setSummary(data ? { kind: 'ready', counts: data.counts } : { kind: 'failed' });
      },
      () => {
        if (active) setSummary({ kind: 'failed' });
      },
    );
    return () => {
      active = false;
    };
  }, [core]);

  return (
    <Card labelledBy={id} className="feature-card" testId="home-contract-attention">
      <span className="feature-card__icon">
        <Icon name="expiry" />
      </span>
      <div className="feature-card__body" aria-busy={summary.kind === 'loading'}>
        <h2 id={id}>{t('home.tenant.attention.title')}</h2>
        {summary.kind === 'loading' && (
          <>
            <p role="status">{t('home.tenant.attention.loading')}</p>
            <Skeleton lines={1} />
          </>
        )}
        {summary.kind === 'failed' && (
          <p data-testid="attention-failed">{t('home.tenant.attention.failed')}</p>
        )}
        {summary.kind === 'ready' && summary.counts.total === 0 && (
          <p data-testid="attention-count">{t('home.tenant.attention.none')}</p>
        )}
        {summary.kind === 'ready' && summary.counts.total > 0 && (
          <p data-testid="attention-count">
            {t('home.tenant.attention.count', { count: summary.counts.total })}
            {summary.counts.expired > 0 &&
              ` ${t('home.tenant.attention.expired', { count: summary.counts.expired })}`}
          </p>
        )}
        <Link to="/admin/contract-expirations" className="button">
          {t('home.tenant.attention.open')}
          <Icon name="chevronRight" />
        </Link>
      </div>
    </Card>
  );
}

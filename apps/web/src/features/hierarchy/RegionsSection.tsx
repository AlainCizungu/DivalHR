import type { LegalEntity, Region, Site } from '@divalhr/api-client';
import { useCallback, useId, useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { RegionForm } from './HierarchyForms';
import { PagedList, usePagedList } from './pagedList';
import { useRegionOptions } from './regionOptions';
import { SitesOf } from './SitesSection';

/**
 * Everything beneath the selected legal entity (MVP-002 Increment 3A): its regions (optional) and
 * its sites, which stay directly reachable whether or not they have a region. Keyed by the legal
 * entity, so each selection starts from its first pages.
 */
export function LegalEntityStructure({
  parent,
  regionsHeadingRef,
  selectedSiteId,
  onSelectSite,
  onSiteUpdated,
  onAnnounce,
}: {
  parent: LegalEntity;
  regionsHeadingRef: RefObject<HTMLHeadingElement | null>;
  selectedSiteId: string | undefined;
  onSelectSite: (site: Site) => void;
  onSiteUpdated: (site: Site) => void;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const periodText = usePeriodText();
  const regionOptions = useRegionOptions(parent.id);
  const fetchRegions = useCallback(
    (cursor?: string) =>
      core.GET('/regions', { params: { query: { legalEntityId: parent.id, cursor } } }),
    [core, parent.id],
  );
  const regions = usePagedList<Region>(fetchRegions);
  const sitesHeading = useRef<HTMLHeadingElement>(null);
  const [retrying, setRetrying] = useState(false);
  const optionsUnavailable = regionOptions.status === 'failed' || retrying;

  // Recovery for the region options behind site labels and the region selects. The outcome is
  // announced once through the page's single live region; on success focus moves to the Sites
  // heading (the retry control disappears), on failure it stays on the retry control.
  const retryRegionOptions = () => {
    if (retrying) return;
    setRetrying(true);
    void regionOptions.retry().then((outcome) => {
      if (outcome === 'cancelled') return;
      setRetrying(false);
      if (outcome === 'ready') {
        onAnnounce(t('hierarchy.regionOptions.restored'));
        requestAnimationFrame(() => sitesHeading.current?.focus());
      } else {
        onAnnounce(t('hierarchy.regionOptions.failedAgain'));
      }
    });
  };

  return (
    <>
      <section aria-labelledby={`${ids}-regions`} className="card" data-testid="regions-section">
        <h2 id={`${ids}-regions`} ref={regionsHeadingRef} tabIndex={-1}>
          {t('hierarchy.regions.title', { name: parent.name, code: parent.code })}
        </h2>
        <p className="muted">{t('hierarchy.regions.description')}</p>
        <PagedList
          list={regions}
          emptyKey="hierarchy.regions.empty"
          loadMoreKey="hierarchy.regions.loadMore"
          testId="region-list"
          render={(region, ref) => (
            <>
              <span className="hierarchy-item__main" ref={ref} tabIndex={-1}>
                <strong>{region.name}</strong> <span className="badge">{region.code}</span>
              </span>
              <span className="muted">{periodText(region.effectiveFrom, region.effectiveTo)}</span>
            </>
          )}
        />
        <RegionForm
          parent={parent}
          onCreated={(region) => {
            regions.insertCreated(region);
            regionOptions.add(region);
            onAnnounce(t('hierarchy.regions.created', { name: region.name, code: region.code }));
          }}
        />
      </section>

      <section aria-labelledby={`${ids}-sites`} className="card" data-testid="sites-section">
        <h2 id={`${ids}-sites`} ref={sitesHeading} tabIndex={-1}>
          {t('hierarchy.sites.title', { name: parent.name, code: parent.code })}
        </h2>
        {optionsUnavailable && (
          <div className="error-summary" data-testid="region-options-error">
            <p id={`${ids}-options-error`}>{t('hierarchy.regionOptions.failed')}</p>
            <button
              type="button"
              className="button button--secondary"
              aria-describedby={`${ids}-options-error`}
              aria-busy={retrying}
              onClick={retryRegionOptions}
            >
              {retrying ? t('hierarchy.regionOptions.loading') : t('hierarchy.regionOptions.retry')}
            </button>
          </div>
        )}
        <SitesOf
          parent={parent}
          regions={regionOptions}
          selectedSiteId={selectedSiteId}
          onSelectSite={onSelectSite}
          onSiteUpdated={onSiteUpdated}
          onAnnounce={onAnnounce}
        />
      </section>
    </>
  );
}

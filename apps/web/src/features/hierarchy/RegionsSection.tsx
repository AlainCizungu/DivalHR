import type { LegalEntity, Region, Site } from '@divalhr/api-client';
import { useCallback, useId, type RefObject } from 'react';
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
        <h2 id={`${ids}-sites`} tabIndex={-1}>
          {t('hierarchy.sites.title', { name: parent.name, code: parent.code })}
        </h2>
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

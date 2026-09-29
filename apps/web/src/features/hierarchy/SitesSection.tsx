import type { LegalEntity, Site } from '@divalhr/api-client';
import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { SiteForm } from './HierarchyForms';
import { PagedList, usePagedList } from './pagedList';

export function SitesOf({
  parent,
  selectedSiteId,
  onSelectSite,
  onAnnounce,
}: {
  parent: LegalEntity;
  selectedSiteId: string | undefined;
  onSelectSite: (site: Site) => void;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const periodText = usePeriodText();
  const fetchSites = useCallback(
    (cursor?: string) =>
      core.GET('/sites', { params: { query: { legalEntityId: parent.id, cursor } } }),
    [core, parent.id],
  );
  const sites = usePagedList<Site>(fetchSites);
  return (
    <>
      <PagedList
        list={sites}
        emptyKey="hierarchy.sites.empty"
        loadMoreKey="hierarchy.sites.loadMore"
        testId="site-list"
        render={(site, ref) => (
          <>
            <span className="hierarchy-item__main">
              <strong>{site.name}</strong> <span className="badge">{site.code}</span>
            </span>
            <span className="muted">
              {t(`timezones.${site.timezone}`)} · {periodText(site.effectiveFrom, site.effectiveTo)}
            </span>
            <button
              ref={ref}
              type="button"
              className="button button--secondary"
              aria-pressed={selectedSiteId === site.id}
              aria-label={t('hierarchy.sites.select', { name: site.name, code: site.code })}
              onClick={() => {
                onSelectSite(site);
              }}
            >
              {t('hierarchy.sites.selectShort')}
            </button>
          </>
        )}
      />
      <SiteForm
        parent={parent}
        onCreated={(site) => {
          sites.insertCreated(site);
          onAnnounce(t('hierarchy.sites.created', { name: site.name, code: site.code }));
        }}
      />
    </>
  );
}

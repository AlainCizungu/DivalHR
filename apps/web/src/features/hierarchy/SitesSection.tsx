import type { LegalEntity, Site } from '@divalhr/api-client';
import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { SiteForm } from './HierarchyForms';
import { PagedList, usePagedList } from './pagedList';

export function SitesOf({
  parent,
  onAnnounce,
}: {
  parent: LegalEntity;
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
            <span className="hierarchy-item__main" ref={ref} tabIndex={-1}>
              <strong>{site.name}</strong> <span className="badge">{site.code}</span>
            </span>
            <span className="muted">
              {t(`timezones.${site.timezone}`)} · {periodText(site.effectiveFrom, site.effectiveTo)}
            </span>
          </>
        )}
      />
      <SiteForm
        parent={parent}
        onCreated={(site) => {
          sites.prepend(site);
          onAnnounce(t('hierarchy.sites.created', { name: site.name, code: site.code }));
        }}
      />
    </>
  );
}

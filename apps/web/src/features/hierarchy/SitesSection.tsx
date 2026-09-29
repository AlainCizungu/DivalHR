import type { LegalEntity, Site } from '@divalhr/api-client';
import { useCallback, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { AssignRegionForm, SiteForm } from './HierarchyForms';
import { PagedList, usePagedList } from './pagedList';
import type { RegionOptions } from './regionOptions';

export function SitesOf({
  parent,
  regions,
  selectedSiteId,
  onSelectSite,
  onSiteUpdated,
  onAnnounce,
}: {
  parent: LegalEntity;
  regions: RegionOptions;
  selectedSiteId: string | undefined;
  onSelectSite: (site: Site) => void;
  onSiteUpdated: (site: Site) => void;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
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
          <SiteRow
            site={site}
            focusRef={ref}
            selected={selectedSiteId === site.id}
            regions={regions}
            onSelect={onSelectSite}
            onReload={sites.retry}
            onAssigned={(updated) => {
              sites.replaceItem(updated);
              onSiteUpdated(updated);
              const region = regions.byId(updated.regionId);
              onAnnounce(
                region
                  ? t('hierarchy.assignment.done', {
                      site: updated.name,
                      siteCode: updated.code,
                      region: region.name,
                      regionCode: region.code,
                    })
                  : t('hierarchy.assignment.doneGeneric', {
                      site: updated.name,
                      siteCode: updated.code,
                    }),
              );
            }}
          />
        )}
      />
      <SiteForm
        parent={parent}
        regions={regions.regions}
        regionsStatus={regions.status}
        onCreated={(site) => {
          sites.insertCreated(site);
          onAnnounce(t('hierarchy.sites.created', { name: site.name, code: site.code }));
        }}
      />
    </>
  );
}

/**
 * One site: its region status (a programmatic focus target after assignment), the site-selection
 * control and, for a site without a region, an inline first-assignment form.
 */
function SiteRow({
  site,
  focusRef,
  selected,
  regions,
  onSelect,
  onReload,
  onAssigned,
}: {
  site: Site;
  focusRef: ((node: HTMLElement | null) => void) | undefined;
  selected: boolean;
  regions: RegionOptions;
  onSelect: (site: Site) => void;
  onReload: () => void;
  onAssigned: (site: Site) => void;
}) {
  const { t } = useTranslation();
  const periodText = usePeriodText();
  const formId = useId();
  const [open, setOpen] = useState(false);
  const statusRef = useRef<HTMLSpanElement>(null);
  const assignRef = useRef<HTMLButtonElement>(null);
  const region = regions.byId(site.regionId);
  const unassigned = !site.regionId;
  // Assignment needs the region choices: it is offered only once they are loaded (the Sites
  // section shows a retry control otherwise), never with an empty selector.
  const assignable = unassigned && regions.status === 'ready';
  const status = unassigned
    ? t('hierarchy.sites.noRegion')
    : region
      ? t('hierarchy.sites.region', { name: region.name, code: region.code })
      : t('hierarchy.sites.regionAssigned');

  return (
    <>
      <span className="hierarchy-item__main">
        <strong>{site.name}</strong> <span className="badge">{site.code}</span>
      </span>
      <span className="muted">
        {t(`timezones.${site.timezone}`)} · {periodText(site.effectiveFrom, site.effectiveTo)}
      </span>
      <span className="muted" ref={statusRef} tabIndex={-1} data-testid="site-region">
        {status}
      </span>
      <button
        ref={focusRef}
        type="button"
        className="button button--secondary"
        aria-pressed={selected}
        aria-label={t('hierarchy.sites.select', { name: site.name, code: site.code })}
        onClick={() => {
          onSelect(site);
        }}
      >
        {t('hierarchy.sites.selectShort')}
      </button>
      {assignable && (
        <button
          ref={assignRef}
          type="button"
          className="button button--secondary"
          aria-expanded={open}
          aria-controls={open ? formId : undefined}
          aria-label={t('hierarchy.assignment.open', { name: site.name, code: site.code })}
          onClick={() => {
            setOpen((current) => !current);
          }}
        >
          {t('hierarchy.assignment.openShort')}
        </button>
      )}
      {assignable && open && (
        <AssignRegionForm
          id={formId}
          site={site}
          regions={regions.regions}
          onReload={onReload}
          onCancel={() => {
            setOpen(false);
            requestAnimationFrame(() => assignRef.current?.focus());
          }}
          onAssigned={(updated) => {
            setOpen(false);
            onAssigned(updated);
            requestAnimationFrame(() => statusRef.current?.focus());
          }}
        />
      )}
    </>
  );
}

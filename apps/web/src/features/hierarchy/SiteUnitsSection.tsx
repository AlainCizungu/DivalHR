import type { CostCenter, Department, Site } from '@divalhr/api-client';
import { useCallback, useId } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { SiteUnitForm } from './HierarchyForms';
import type { SiteUnitKind } from './hierarchyForm';
import { PagedList, usePagedList } from './pagedList';

/** Departments and cost centers of the selected site (MVP-002 Increment 2). */
export function SiteUnitsOf({
  site,
  onAnnounce,
}: {
  site: Site;
  onAnnounce: (message: string) => void;
}) {
  return (
    <>
      <SiteUnitList kind="department" site={site} onAnnounce={onAnnounce} />
      <SiteUnitList kind="costCenter" site={site} onAnnounce={onAnnounce} />
    </>
  );
}

function SiteUnitList({
  kind,
  site,
  onAnnounce,
}: {
  kind: SiteUnitKind;
  site: Site;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const ids = useId();
  const periodText = usePeriodText();
  const fetchPage = useCallback(
    (cursor?: string) =>
      kind === 'department'
        ? core.GET('/departments', { params: { query: { siteId: site.id, cursor } } })
        : core.GET('/cost-centers', { params: { query: { siteId: site.id, cursor } } }),
    [core, kind, site.id],
  );
  const units = usePagedList<Department | CostCenter>(fetchPage);
  const group = kind === 'department' ? 'departments' : 'costCenters';
  return (
    <section aria-labelledby={`${ids}-title`} data-testid={`${kind}-section`}>
      <h3 id={`${ids}-title`}>{t(`hierarchy.${group}.title`)}</h3>
      <PagedList
        list={units}
        emptyKey={`hierarchy.${group}.empty`}
        loadMoreKey={`hierarchy.${group}.loadMore`}
        testId={`${kind}-list`}
        render={(unit, ref) => (
          <>
            <span className="hierarchy-item__main" ref={ref} tabIndex={-1}>
              <strong>{unit.name}</strong> <span className="badge">{unit.code}</span>
            </span>
            <span className="muted">{periodText(unit.effectiveFrom, unit.effectiveTo)}</span>
          </>
        )}
      />
      <SiteUnitForm
        kind={kind}
        site={site}
        onCreated={(unit) => {
          units.prepend(unit);
          onAnnounce(t(`hierarchy.${group}.created`, { name: unit.name, code: unit.code }));
        }}
      />
    </section>
  );
}

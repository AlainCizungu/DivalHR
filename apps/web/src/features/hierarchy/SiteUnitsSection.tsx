import type { CostCenter, Department, Site } from '@divalhr/api-client';
import { useCallback, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { SiteUnitForm } from './HierarchyForms';
import type { SiteUnitKind } from './hierarchyForm';
import { PagedList, usePagedList } from './pagedList';
import { TeamsOf, type TeamParentSelection } from './TeamsSection';

/**
 * Departments and cost centers of the selected site (MVP-002 Increment 2), followed by the teams of
 * one selected department or cost center (Increment 3B). One parent is selected at a time across
 * both lists; selecting it moves focus to the Teams heading.
 */
export function SiteUnitsOf({
  site,
  onAnnounce,
}: {
  site: Site;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const ids = useId();
  const [selection, setSelection] = useState<TeamParentSelection | null>(null);
  const teamsHeading = useRef<HTMLHeadingElement>(null);
  const select = (next: TeamParentSelection) => {
    setSelection(next);
    requestAnimationFrame(() => teamsHeading.current?.focus());
  };
  return (
    <>
      <SiteUnitList
        kind="department"
        site={site}
        selection={selection}
        onSelect={select}
        onAnnounce={onAnnounce}
      />
      <SiteUnitList
        kind="costCenter"
        site={site}
        selection={selection}
        onSelect={select}
        onAnnounce={onAnnounce}
      />
      <section aria-labelledby={`${ids}-teams-title`} data-testid="team-section">
        <h3 id={`${ids}-teams-title`} ref={teamsHeading} tabIndex={-1}>
          {selection
            ? t(`hierarchy.teams.${selection.kind}.title`, {
                name: selection.unit.name,
                code: selection.unit.code,
              })
            : t('hierarchy.teams.title')}
        </h3>
        {selection ? (
          <TeamsOf
            key={`${selection.kind}:${selection.unit.id}`}
            selection={selection}
            onAnnounce={onAnnounce}
          />
        ) : (
          <p className="muted" data-testid="team-none">
            {t('hierarchy.teams.none')}
          </p>
        )}
      </section>
    </>
  );
}

function SiteUnitList({
  kind,
  site,
  selection,
  onSelect,
  onAnnounce,
}: {
  kind: SiteUnitKind;
  site: Site;
  selection: TeamParentSelection | null;
  onSelect: (selection: TeamParentSelection) => void;
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
            <button
              type="button"
              className="button button--secondary"
              aria-pressed={selection?.kind === kind && selection.unit.id === unit.id}
              aria-label={t(`hierarchy.teams.${kind}.select`, { name: unit.name, code: unit.code })}
              onClick={() => {
                onSelect({ kind, unit });
              }}
            >
              {t('hierarchy.teams.selectShort')}
            </button>
          </>
        )}
      />
      <SiteUnitForm
        kind={kind}
        site={site}
        onCreated={(unit) => {
          units.insertCreated(unit);
          onAnnounce(t(`hierarchy.${group}.created`, { name: unit.name, code: unit.code }));
        }}
      />
    </section>
  );
}

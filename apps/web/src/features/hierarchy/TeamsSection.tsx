import type { CostCenter, Department, Team } from '@divalhr/api-client';
import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';
import { usePeriodText } from './HierarchyFields';
import { TeamForm } from './HierarchyForms';
import type { TeamParentKind } from './hierarchyForm';
import { PagedList, usePagedList } from './pagedList';

/** The department or cost center whose teams are shown. */
export interface TeamParentSelection {
  kind: TeamParentKind;
  unit: Department | CostCenter;
}

/**
 * Teams of the selected department or cost center (MVP-002 Increment 3B). The list is keyed by
 * parent type and id so that switching parents starts a fresh list, cursor and form.
 */
export function TeamsOf({
  selection,
  onAnnounce,
}: {
  selection: TeamParentSelection;
  onAnnounce: (message: string) => void;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const periodText = usePeriodText();
  const { kind, unit } = selection;
  const fetchPage = useCallback(
    (cursor?: string) =>
      kind === 'department'
        ? core.GET('/teams', { params: { query: { departmentId: unit.id, cursor } } })
        : core.GET('/teams', { params: { query: { costCenterId: unit.id, cursor } } }),
    [core, kind, unit.id],
  );
  const teams = usePagedList<Team>(fetchPage);
  return (
    <>
      <PagedList
        list={teams}
        emptyKey={`hierarchy.teams.${kind}.empty`}
        loadMoreKey="hierarchy.teams.loadMore"
        testId="team-list"
        render={(team, ref) => (
          <>
            <span className="hierarchy-item__main" ref={ref} tabIndex={-1}>
              <strong>{team.name}</strong> <span className="badge">{team.code}</span>
            </span>
            <span className="muted">{periodText(team.effectiveFrom, team.effectiveTo)}</span>
          </>
        )}
      />
      <TeamForm
        parentKind={kind}
        parent={unit}
        onCreated={(team) => {
          teams.insertCreated(team);
          onAnnounce(t('hierarchy.teams.created', { name: team.name, code: team.code }));
        }}
      />
    </>
  );
}

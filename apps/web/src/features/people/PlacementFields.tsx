import type { PlacementInput } from '@divalhr/api-client';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../app/ApiProvider';

/** Every page of a hierarchy list (at most 200 per page, as the region selects do). */
async function loadAll<T>(
  fetchPage: (cursor?: string) => Promise<{ data?: { data: T[]; nextCursor?: string } }>,
): Promise<T[]> {
  const all: T[] = [];
  let cursor: string | undefined;
  do {
    const { data } = await fetchPage(cursor);
    if (!data) throw new Error('units unavailable');
    all.push(...data.data);
    cursor = data.nextCursor;
  } while (cursor);
  return all;
}

type Option = { id: string; code: string; name: string };
type Options = { status: 'idle' | 'loading' | 'ready' | 'failed'; items: Option[] };
type Loaded = { key: string; status: 'ready' | 'failed'; items: Option[] };

/** Options for a parent key; a different key reads as loading until its own result arrives. */
function useOptions(key: string | null, load: () => Promise<Option[]>): Options {
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  useEffect(() => {
    if (key === null) return;
    let active = true;
    load().then(
      (items) => {
        if (active) setLoaded({ key, status: 'ready', items });
      },
      () => {
        if (active) setLoaded({ key, status: 'failed', items: [] });
      },
    );
    return () => {
      active = false;
    };
    // The loader is keyed by `key`; recreating it on each render must not reload.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  if (key === null) return { status: 'idle', items: [] };
  if (loaded?.key !== key) return { status: 'loading', items: [] };
  return { status: loaded.status, items: loaded.items };
}

export const EMPTY_PLACEMENT: PlacementInput = {
  legalEntityId: '',
  siteId: '',
  departmentId: null,
  costCenterId: null,
  teamId: null,
};

/**
 * The placement of a change: legal entity, site, then a department or a cost center and an
 * optional team under it. Units are listed through the hierarchy API; the server checks again that
 * every unit exists, matches its parent and is in effect over the whole new period.
 */
export function PlacementFields({
  idPrefix,
  value,
  onChange,
  invalid,
}: {
  idPrefix: string;
  value: PlacementInput;
  onChange: (value: PlacementInput) => void;
  invalid: boolean;
}) {
  const { t } = useTranslation();
  const { core } = useApi();
  const legalEntities = useOptions('all', () =>
    loadAll((cursor) => core.GET('/legal-entities', { params: { query: { cursor, limit: 200 } } })),
  );
  const sites = useOptions(value.legalEntityId || null, () =>
    loadAll((cursor) =>
      core.GET('/sites', {
        params: { query: { legalEntityId: value.legalEntityId, cursor, limit: 200 } },
      }),
    ),
  );
  const departments = useOptions(value.siteId || null, () =>
    loadAll((cursor) =>
      core.GET('/departments', { params: { query: { siteId: value.siteId, cursor, limit: 200 } } }),
    ),
  );
  const costCenters = useOptions(value.siteId || null, () =>
    loadAll((cursor) =>
      core.GET('/cost-centers', {
        params: { query: { siteId: value.siteId, cursor, limit: 200 } },
      }),
    ),
  );
  const parent = value.departmentId
    ? `department:${value.departmentId}`
    : value.costCenterId
      ? `costCenter:${value.costCenterId}`
      : null;
  const teams = useOptions(parent, () =>
    loadAll((cursor) =>
      value.departmentId
        ? core.GET('/teams', {
            params: { query: { departmentId: value.departmentId, cursor, limit: 200 } },
          })
        : core.GET('/teams', {
            params: { query: { costCenterId: value.costCenterId ?? '', cursor, limit: 200 } },
          }),
    ),
  );
  const failed = [legalEntities, sites, departments, costCenters, teams].some(
    (options) => options.status === 'failed',
  );
  const label = (option: Option) =>
    t('employees.placement.option', { name: option.name, code: option.code });

  return (
    <fieldset className="field" data-testid="placement-fields">
      <legend>{t('employees.kinds.PLACEMENT')}</legend>
      {failed && (
        <p className="field__error" role="alert">
          {t('employees.placement.unavailable')}
        </p>
      )}
      <label htmlFor={`${idPrefix}-le`}>{t('employees.placement.fields.legalEntityId')}</label>
      <select
        id={`${idPrefix}-le`}
        value={value.legalEntityId}
        aria-invalid={invalid && !value.legalEntityId ? true : undefined}
        onChange={(event) => {
          onChange({ ...EMPTY_PLACEMENT, legalEntityId: event.target.value });
        }}
      >
        <option value="">{t('employees.placement.choose')}</option>
        {legalEntities.items.map((option) => (
          <option key={option.id} value={option.id}>
            {label(option)}
          </option>
        ))}
      </select>
      <label htmlFor={`${idPrefix}-site`}>{t('employees.placement.fields.siteId')}</label>
      <select
        id={`${idPrefix}-site`}
        value={value.siteId}
        disabled={!value.legalEntityId}
        aria-invalid={invalid && !value.siteId ? true : undefined}
        onChange={(event) => {
          onChange({
            ...EMPTY_PLACEMENT,
            legalEntityId: value.legalEntityId,
            siteId: event.target.value,
          });
        }}
      >
        <option value="">{t('employees.placement.choose')}</option>
        {sites.items.map((option) => (
          <option key={option.id} value={option.id}>
            {label(option)}
          </option>
        ))}
      </select>
      <label htmlFor={`${idPrefix}-unit`}>{t('employees.placement.unit')}</label>
      <select
        id={`${idPrefix}-unit`}
        value={parent ?? ''}
        disabled={!value.siteId}
        onChange={(event) => {
          const [type, id] = event.target.value.split(':');
          onChange({
            legalEntityId: value.legalEntityId,
            siteId: value.siteId,
            departmentId: type === 'department' ? (id ?? null) : null,
            costCenterId: type === 'costCenter' ? (id ?? null) : null,
            teamId: null,
          });
        }}
      >
        <option value="">{t('employees.placement.noUnit')}</option>
        <optgroup label={t('employees.placement.fields.departmentId')}>
          {departments.items.map((option) => (
            <option key={option.id} value={`department:${option.id}`}>
              {label(option)}
            </option>
          ))}
        </optgroup>
        <optgroup label={t('employees.placement.fields.costCenterId')}>
          {costCenters.items.map((option) => (
            <option key={option.id} value={`costCenter:${option.id}`}>
              {label(option)}
            </option>
          ))}
        </optgroup>
      </select>
      <label htmlFor={`${idPrefix}-team`}>{t('employees.placement.fields.teamId')}</label>
      <select
        id={`${idPrefix}-team`}
        value={value.teamId ?? ''}
        disabled={parent === null}
        onChange={(event) => {
          onChange({ ...value, teamId: event.target.value || null });
        }}
      >
        <option value="">{t('employees.placement.noTeam')}</option>
        {teams.items.map((option) => (
          <option key={option.id} value={option.id}>
            {label(option)}
          </option>
        ))}
      </select>
    </fieldset>
  );
}

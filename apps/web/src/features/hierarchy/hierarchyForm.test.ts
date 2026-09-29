import type { CreateTeam } from '@divalhr/api-client';
import { describe, expect, it } from 'vitest';
import {
  ASSIGNMENT_FIELDS,
  LEGAL_ENTITY_FIELDS,
  REGION_FIELDS,
  SITE_FIELDS,
  SITE_UNIT_FIELDS,
  TEAM_FIELDS,
  fieldErrorsFromProblem,
  toAssignmentPayload,
  toRegionPayload,
  toSitePayload,
  toSiteUnitPayload,
  toTeamPayload,
  validateAssignment,
  validateLegalEntity,
  validateRegion,
  validateSite,
  validateSiteUnit,
  validateTeam,
} from './hierarchyForm';

const entity = {
  code: 'kin-01',
  name: 'Société',
  countryCode: 'CD',
  effectiveFrom: '2026-01-01',
  effectiveTo: '',
};

describe('hierarchy form rules', () => {
  it('accepts normalized codes and open-ended periods', () => {
    expect(validateLegalEntity(entity)).toEqual({});
  });

  it('mirrors the server code, name and date rules', () => {
    expect(
      validateLegalEntity({
        ...entity,
        code: 'A',
        name: ' ',
        effectiveFrom: '2026-02-30',
        effectiveTo: '3000-01-01',
      }),
    ).toEqual({ code: 'LENGTH', name: 'REQUIRED', effectiveFrom: 'FORMAT', effectiveTo: 'RANGE' });
    expect(validateLegalEntity({ ...entity, code: 'A B', effectiveTo: '2025-12-31' })).toEqual({
      code: 'FORMAT',
      effectiveTo: 'BEFORE_START',
    });
    expect(validateLegalEntity({ ...entity, effectiveFrom: '1899-12-31' })).toEqual({
      effectiveFrom: 'RANGE',
    });
  });

  it('only offers the parent country time zones and builds null for open ends', () => {
    const site = {
      code: ' gombe ',
      name: 'Gombe',
      timezone: 'Europe/Paris',
      regionId: '',
      effectiveFrom: '2026-01-01',
      effectiveTo: '',
    };
    expect(validateSite(site, 'CD')).toEqual({ timezone: 'NOT_SUPPORTED' });
    expect(toSitePayload('id-1', { ...site, timezone: 'Africa/Kinshasa' })).toEqual({
      legalEntityId: 'id-1',
      code: 'GOMBE',
      name: 'Gombe',
      timezone: 'Africa/Kinshasa',
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
    });
  });

  it('maps server problems to fields without trusting unknown fields', () => {
    expect(
      fieldErrorsFromProblem(
        { code: 'SITE_PERIOD_OUTSIDE_LEGAL_ENTITY', params: { field: 'effectiveFrom' } },
        SITE_FIELDS,
      ),
    ).toEqual({ effectiveFrom: 'OUTSIDE_PARENT' });
    expect(
      fieldErrorsFromProblem(
        {
          code: 'VALIDATION_FAILED',
          params: {
            fields: [
              { field: 'code', constraint: 'FORMAT' },
              { field: 'tenantId', constraint: 'UNKNOWN_PROPERTY' },
            ],
          },
        },
        LEGAL_ENTITY_FIELDS,
      ),
    ).toEqual({ code: 'FORMAT' });
    expect(
      fieldErrorsFromProblem(
        { code: 'LEGAL_ENTITY_NOT_FOUND', params: { field: 'legalEntityId' } },
        SITE_FIELDS,
      ),
    ).toBeNull();
    expect(fieldErrorsFromProblem({ code: 'CURSOR_INVALID', params: {} }, SITE_FIELDS)).toBeNull();
  });

  it('validates and normalizes department and cost-center payloads', () => {
    expect(
      validateSiteUnit({
        code: 'x',
        name: '',
        effectiveFrom: '2026-05-01',
        effectiveTo: '2026-04-30',
      }),
    ).toEqual({ code: 'LENGTH', name: 'REQUIRED', effectiveTo: 'BEFORE_START' });
    expect(
      toSiteUnitPayload('site-1', {
        code: ' rh ',
        name: ' Ressources humaines ',
        effectiveFrom: '2026-01-01',
        effectiveTo: '',
      }),
    ).toEqual({
      siteId: 'site-1',
      code: 'RH',
      name: 'Ressources humaines',
      effectiveFrom: '2026-01-01',
      effectiveTo: null,
    });
    for (const code of ['DEPARTMENT_PERIOD_OUTSIDE_SITE', 'COST_CENTER_PERIOD_OUTSIDE_SITE']) {
      expect(
        fieldErrorsFromProblem({ code, params: { field: 'effectiveFrom' } }, SITE_UNIT_FIELDS),
      ).toEqual({ effectiveFrom: 'OUTSIDE_SITE' });
    }
    for (const code of ['DUPLICATE_DEPARTMENT_CODE', 'DUPLICATE_COST_CENTER_CODE']) {
      expect(fieldErrorsFromProblem({ code, params: { field: 'code' } }, SITE_UNIT_FIELDS)).toEqual(
        {
          code: 'DUPLICATE_CODE',
        },
      );
    }
    expect(
      fieldErrorsFromProblem(
        { code: 'SITE_NOT_FOUND', params: { field: 'siteId' } },
        SITE_UNIT_FIELDS,
      ),
    ).toBeNull();
  });

  it('omits regionId without a region so the site payload is unchanged', () => {
    const site = {
      code: 'kat',
      name: 'Katanga',
      timezone: 'Africa/Lubumbashi',
      regionId: '',
      effectiveFrom: '2026-01-01',
      effectiveTo: '',
    };
    expect(toSitePayload('le-1', site)).not.toHaveProperty('regionId');
    expect(toSitePayload('le-1', { ...site, regionId: 'rg-1' })).toMatchObject({
      legalEntityId: 'le-1',
      regionId: 'rg-1',
    });
  });

  it('validates regions and assignments and maps their server problems', () => {
    expect(validateRegion({ code: '', name: 'R', effectiveFrom: '', effectiveTo: '' })).toEqual({
      code: 'REQUIRED',
      name: 'LENGTH',
      effectiveFrom: 'REQUIRED',
    });
    expect(
      toRegionPayload('le-1', {
        code: ' kat_nord ',
        name: ' Région Grand Katanga ',
        effectiveFrom: '2026-01-01',
        effectiveTo: '2026-12-31',
      }),
    ).toEqual({
      legalEntityId: 'le-1',
      code: 'KAT_NORD',
      name: 'Région Grand Katanga',
      effectiveFrom: '2026-01-01',
      effectiveTo: '2026-12-31',
    });
    expect(validateAssignment('')).toEqual({ regionId: 'REQUIRED' });
    expect(validateAssignment('rg-1')).toEqual({});
    expect(toAssignmentPayload('rg-1')).toEqual({ regionId: 'rg-1' });
    expect(
      fieldErrorsFromProblem(
        { code: 'DUPLICATE_REGION_CODE', params: { field: 'code' } },
        REGION_FIELDS,
      ),
    ).toEqual({ code: 'DUPLICATE_CODE' });
    expect(
      fieldErrorsFromProblem(
        { code: 'REGION_PERIOD_OUTSIDE_LEGAL_ENTITY', params: { field: 'effectiveTo' } },
        REGION_FIELDS,
      ),
    ).toEqual({ effectiveTo: 'OUTSIDE_LEGAL_ENTITY' });
    for (const [code, constraint] of [
      ['REGION_NOT_FOUND', 'NOT_FOUND'],
      ['SITE_REGION_LEGAL_ENTITY_MISMATCH', 'MISMATCH'],
      ['SITE_PERIOD_OUTSIDE_REGION', 'OUTSIDE_REGION'],
    ] as const) {
      expect(
        fieldErrorsFromProblem({ code, params: { field: 'regionId' } }, ASSIGNMENT_FIELDS),
      ).toEqual({ regionId: constraint });
      expect(fieldErrorsFromProblem({ code, params: { field: 'regionId' } }, SITE_FIELDS)).toEqual({
        regionId: constraint,
      });
    }
    expect(
      fieldErrorsFromProblem(
        { code: 'SITE_REGION_ALREADY_ASSIGNED', params: {} },
        ASSIGNMENT_FIELDS,
      ),
    ).toBeNull();
  });

  it('builds a team payload naming exactly one parent and keeps the generated union', () => {
    const values = {
      code: ' paie ',
      name: ' Équipe paie ',
      effectiveFrom: '2026-03-01',
      effectiveTo: '',
    };
    const department: CreateTeam = toTeamPayload('department', 'dept-id', values);
    expect(department).toEqual({
      departmentId: 'dept-id',
      code: 'PAIE',
      name: 'Équipe paie',
      effectiveFrom: '2026-03-01',
      effectiveTo: null,
    });
    expect('costCenterId' in department).toBe(false);
    const costCenter: CreateTeam = toTeamPayload('costCenter', 'cc-id', {
      ...values,
      effectiveTo: '2026-12-31',
    });
    expect(costCenter).toEqual({
      costCenterId: 'cc-id',
      code: 'PAIE',
      name: 'Équipe paie',
      effectiveFrom: '2026-03-01',
      effectiveTo: '2026-12-31',
    });
    expect('departmentId' in costCenter).toBe(false);
    expect(validateTeam(values)).toEqual({});
    expect(validateTeam({ ...values, code: '', effectiveTo: '2026-01-01' })).toEqual({
      code: 'REQUIRED',
      effectiveTo: 'BEFORE_START',
    });
  });

  it('maps team problems to fields and leaves parent problems form-level', () => {
    expect(
      fieldErrorsFromProblem(
        { code: 'DUPLICATE_TEAM_CODE', params: { field: 'code' } },
        TEAM_FIELDS,
      ),
    ).toEqual({ code: 'DUPLICATE_CODE' });
    expect(
      fieldErrorsFromProblem(
        { code: 'TEAM_PERIOD_OUTSIDE_DEPARTMENT', params: { field: 'effectiveFrom' } },
        TEAM_FIELDS,
      ),
    ).toEqual({ effectiveFrom: 'OUTSIDE_DEPARTMENT' });
    expect(
      fieldErrorsFromProblem(
        { code: 'TEAM_PERIOD_OUTSIDE_COST_CENTER', params: { field: 'effectiveTo' } },
        TEAM_FIELDS,
      ),
    ).toEqual({ effectiveTo: 'OUTSIDE_COST_CENTER' });
    for (const code of [
      'TEAM_PARENT_REQUIRED',
      'TEAM_PARENT_AMBIGUOUS',
      'DEPARTMENT_NOT_FOUND',
      'COST_CENTER_NOT_FOUND',
    ]) {
      expect(fieldErrorsFromProblem({ code, params: {} }, TEAM_FIELDS)).toBeNull();
    }
  });
});

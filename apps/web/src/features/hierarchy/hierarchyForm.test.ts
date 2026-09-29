import { describe, expect, it } from 'vitest';
import {
  LEGAL_ENTITY_FIELDS,
  SITE_FIELDS,
  SITE_UNIT_FIELDS,
  fieldErrorsFromProblem,
  toSitePayload,
  toSiteUnitPayload,
  validateLegalEntity,
  validateSite,
  validateSiteUnit,
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
});

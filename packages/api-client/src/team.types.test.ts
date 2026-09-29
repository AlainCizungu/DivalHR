import { describe, expect, it } from 'vitest';
import type { CreateTeam, TeamParentQuery } from './index';

const base = { code: 'EQ-01', name: 'Équipe', effectiveFrom: '2026-01-01' };
const id = '11111111-1111-4111-8111-111111111111';

describe('team contract types (exactly one parent)', () => {
  it('accepts exactly one parent and rejects none or both at compile time', () => {
    const department: CreateTeam = { ...base, departmentId: id };
    const costCenter: CreateTeam = { ...base, costCenterId: id, departmentId: null };
    // @ts-expect-error neither parent is supplied
    const neither: CreateTeam = { ...base };
    // @ts-expect-error both parents are supplied
    const both: CreateTeam = { ...base, departmentId: id, costCenterId: id };
    const byDepartment: TeamParentQuery = { departmentId: id };
    // @ts-expect-error both list filters are supplied
    const bothFilters: TeamParentQuery = { departmentId: id, costCenterId: id };
    expect([department, costCenter, neither, both, byDepartment, bothFilters]).toHaveLength(6);
  });
});

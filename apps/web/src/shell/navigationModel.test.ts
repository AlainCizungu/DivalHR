import { describe, expect, it } from 'vitest';
import { ROUTES, type Role } from '../app/routes';
import { activeItemPath, NAVIGATION, navigationRoles, visibleGroups } from './navigationModel';

const visiblePaths = (roles: Role[]) =>
  visibleGroups(roles).flatMap((group) => group.items.map((item) => item.to));

const PLATFORM = [
  '/admin/organizations/new',
  '/admin/organizations/first-admin',
  '/admin/partner-applications',
  '/admin/partners',
  '/admin/partner-referrals',
  '/admin/privacy',
  '/admin/statutory-packs',
];
const TENANT = [
  '/admin/people',
  '/admin/people/import',
  '/admin/leave-policies',
  '/admin/leave-approvals',
  '/admin/leave-routing-exceptions',
  '/admin/leave-approval-substitutes',
  '/admin/work-schedules',
  '/admin/operations',
  '/admin/tasks',
  '/admin/expenses',
  '/admin/attendance-review',
  '/admin/timesheets',
  '/admin/time-exports',
  '/admin/payroll-preparation',
  '/admin/payroll-runs',
  '/admin/payroll-packages',
  '/admin/salary-advance-policies',
  '/admin/salary-advance-requests',
  '/admin/salary-advance-handoffs',
  '/admin/accounting-mappings',
  '/admin/hierarchy',
  '/admin/contract-templates',
  '/admin/contract-expirations',
  '/admin/users',
  '/admin/access',
  '/admin/catalogue',
  '/admin/webhooks',
  '/admin/integrations/whatsapp',
];
const EMPLOYEE = [
  '/me/contracts',
  '/me/schedule',
  '/me/attendance',
  '/me/timesheets',
  '/me/time-approvals',
  '/me/team-operations',
  '/me/team-attendance',
  '/me/schedule/substitutions',
  '/me/catalogue',
  '/me/partner-products',
  '/me/partner-requests',
  '/me/privacy',
  '/me/leave',
  '/me/leave/approvals',
  '/me/tasks',
  '/me/expenses',
  '/me/compensation',
  '/me/salary-advance',
  '/me/payment-destination',
  '/me/payslips',
  '/me/expense-approvals',
];

describe('role-to-navigation matrix (UI-001 proposal §5)', () => {
  it.each<[string, Role[], string[]]>([
    ['no role', [], ['/']],
    ['platform-admin', ['platform-admin'], ['/', ...PLATFORM]],
    ['tenant-admin', ['tenant-admin'], ['/', ...TENANT]],
    ['employee', ['employee'], ['/', ...EMPLOYEE]],
    ['tenant-admin + employee', ['employee', 'tenant-admin'], ['/', ...TENANT, ...EMPLOYEE]],
    [
      'platform-admin + tenant-admin',
      ['tenant-admin', 'platform-admin'],
      ['/', ...PLATFORM, ...TENANT],
    ],
    [
      'all roles',
      ['employee', 'platform-admin', 'tenant-admin'],
      ['/', ...PLATFORM, ...TENANT, ...EMPLOYEE],
    ],
  ])('%s sees exactly its destinations, in group order', (_name, roles, expected) => {
    expect(visiblePaths(roles)).toEqual(expected);
  });

  it('drops groups that have no visible item', () => {
    expect(visibleGroups(['employee']).map((group) => group.id)).toEqual(['overview', 'mySpace']);
  });

  it.each([
    ['anonymous', { kind: 'anonymous' } as const],
    ['loading', { kind: 'loading' } as const],
    ['error', { kind: 'error', code: 'generic' } as const],
  ])('a %s session grants no role, so only Home shows', (_name, session) => {
    expect(navigationRoles(session)).toEqual([]);
    expect(visiblePaths([...navigationRoles(session)])).toEqual(['/']);
  });
});

describe('navigation and route registry consistency', () => {
  it('every navigation item opens a registered route guarded by the same role', () => {
    for (const group of NAVIGATION) {
      for (const item of group.items) {
        const route = ROUTES.find((candidate) => candidate.path === item.to);
        expect(route, item.to).toBeDefined();
        expect(route?.access?.role ?? null, item.to).toBe(item.role);
      }
    }
  });

  it('every guarded list page is reachable from the navigation', () => {
    const navPaths = NAVIGATION.flatMap((group) => group.items.map((item) => item.to));
    const listPages = ROUTES.filter((route) => route.access && !route.path.includes(':'));
    for (const route of listPages) expect(navPaths, route.path).toContain(route.path);
  });

  it('keeps every pre-UI-001 URL, plus the MVP-031A queue, MVP-040A/041A/041B leave pages, MVP-050A schedules, MVP-051 substitutions, MVP-070A timesheets, MVP-070B/C time approvals, MVP-070E time exports and MVP-090A payroll preparation', () => {
    expect(ROUTES.map((route) => route.path).sort()).toEqual(
      [
        '/',
        '/status',
        '/admin/organizations/new',
        '/admin/organizations/first-admin',
        '/admin/partner-applications',
        '/admin/partner-applications/:reference',
        '/admin/partners',
        '/admin/partners/:reference',
        '/admin/partners/:reference/products/:code',
        '/admin/catalogue',
        '/me/catalogue',
        '/me/partner-products',
        '/me/partner-products/:productId',
        '/me/partner-requests',
        '/me/partner-requests/:referralId',
        '/me/privacy',
        '/admin/privacy',
        '/admin/partner-referrals',
        '/admin/partner-referrals/:referralId',
        '/admin/statutory-packs',
        '/admin/statutory-packs/:packCode/:version',
        '/admin/hierarchy',
        '/admin/users',
        '/admin/access',
        '/admin/webhooks',
        '/admin/integrations/whatsapp',
        '/admin/people',
        '/admin/people/:employeeId',
        '/admin/people/import',
        '/admin/contract-templates',
        '/admin/contract-templates/:templateId',
        '/admin/contract-expirations',
        '/admin/leave-policies',
        '/admin/leave-approvals',
        '/admin/leave-routing-exceptions',
        '/admin/leave-approval-substitutes',
        '/admin/work-schedules',
        '/admin/operations',
        '/admin/tasks',
        '/admin/expenses',
        '/admin/attendance-review',
        '/admin/timesheets',
        '/admin/time-exports',
        '/admin/payroll-preparation',
        '/admin/payroll-runs',
        '/admin/payroll-runs/:legalEntityId/:runId',
        '/admin/payroll-runs/:legalEntityId/:runId/statutory',
        '/admin/payroll-runs/:legalEntityId/:runId/statutory/:calculationId/approval',
        '/admin/payroll-packages',
        '/admin/salary-advance-policies',
        '/admin/salary-advance-requests',
        '/admin/salary-advance-handoffs',
        '/admin/payroll-packages/:legalEntityId/:packageId',
        '/admin/payment-batches/:legalEntityId/:batchId',
        '/admin/accounting-journals/:legalEntityId/:journalId',
        '/admin/accounting-mappings',
        '/admin/payroll-packages/:legalEntityId/:packageId/payslips/:payslipId',
        '/admin/people/:employeeId/statutory-profile',
        '/me/contracts',
        '/me/schedule',
        '/me/attendance',
        '/me/timesheets',
        '/me/time-approvals',
        '/me/team-operations',
        '/me/team-attendance',
        '/me/schedule/substitutions',
        '/me/leave',
        '/me/leave/approvals',
        '/me/tasks',
        '/me/expenses',
        '/me/compensation',
        '/me/salary-advance',
        '/me/payment-destination',
        '/me/payslips',
        '/me/payslips/:payslipId',
        '/me/expense-approvals',
        '/me/contracts/:contractId',
        '/invitation',
        '/partners/apply',
        '/auth/callback',
      ].sort(),
    );
  });

  it('marks only the invitation, the partner application and the sign-in callback as public flows', () => {
    expect(ROUTES.filter((route) => route.frame === 'public').map((route) => route.path)).toEqual([
      '/invitation',
      '/partners/apply',
      '/auth/callback',
    ]);
  });
});

describe('current-page item', () => {
  it.each([
    ['/', '/'],
    ['/admin/people', '/admin/people'],
    ['/admin/people/:employeeId', '/admin/people'],
    ['/admin/people/import', '/admin/people/import'],
    ['/admin/contract-templates/:templateId', '/admin/contract-templates'],
    ['/me/contracts/:contractId', '/me/contracts'],
    ['/status', null],
    ['/invitation', null],
    [null, null],
  ])('%s highlights %s', (pattern, expected) => {
    expect(activeItemPath(pattern)).toBe(expected);
  });
});

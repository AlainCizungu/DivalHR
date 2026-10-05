import { describe, expect, it } from 'vitest';
import { ROUTES, type Role } from '../app/routes';
import { activeItemPath, NAVIGATION, navigationRoles, visibleGroups } from './navigation';

const visiblePaths = (roles: Role[]) =>
  visibleGroups(roles).flatMap((group) => group.items.map((item) => item.to));

const PLATFORM = ['/admin/organizations/new', '/admin/organizations/first-admin'];
const TENANT = [
  '/admin/people',
  '/admin/people/import',
  '/admin/hierarchy',
  '/admin/contract-templates',
  '/admin/users',
  '/admin/access',
];
const EMPLOYEE = ['/me/contracts'];

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

  it('keeps every pre-UI-001 URL', () => {
    expect(ROUTES.map((route) => route.path).sort()).toEqual(
      [
        '/',
        '/status',
        '/admin/organizations/new',
        '/admin/organizations/first-admin',
        '/admin/hierarchy',
        '/admin/users',
        '/admin/access',
        '/admin/people',
        '/admin/people/:employeeId',
        '/admin/people/import',
        '/admin/contract-templates',
        '/admin/contract-templates/:templateId',
        '/me/contracts',
        '/me/contracts/:contractId',
        '/invitation',
        '/auth/callback',
      ].sort(),
    );
  });

  it('marks only the invitation and sign-in callback as public flows', () => {
    expect(ROUTES.filter((route) => route.frame === 'public').map((route) => route.path)).toEqual([
      '/invitation',
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

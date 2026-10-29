import type { SessionState } from '../app/SessionProvider';
import type { Role } from '../app/routes';
import type { IconName } from '../ui/Icon';

export interface NavItem {
  /** Route path in the registry; the link target. */
  to: string;
  labelKey: string;
  icon: IconName;
  /** The role the route requires; null for Home, which every visitor may open. */
  role: Role | null;
}

export interface NavGroupDef {
  id:
    | 'overview'
    | 'platform'
    | 'people'
    | 'operations'
    | 'payroll'
    | 'organization'
    | 'documents'
    | 'access'
    | 'integrations'
    | 'mySpace';
  labelKey: string;
  items: NavItem[];
}

/**
 * UI-001 navigation model (proposal §5). Labels reuse the existing nav.* keys. Order: overview,
 * platform, organization administration, then the user's own space.
 */
export const NAVIGATION: NavGroupDef[] = [
  {
    id: 'overview',
    labelKey: 'nav.groups.overview',
    items: [{ to: '/', labelKey: 'nav.home', icon: 'home', role: null }],
  },
  {
    id: 'platform',
    labelKey: 'nav.groups.platform',
    items: [
      {
        to: '/admin/organizations/new',
        labelKey: 'nav.platformAdmin',
        icon: 'organization',
        role: 'platform-admin',
      },
      {
        to: '/admin/organizations/first-admin',
        labelKey: 'nav.firstAdmin',
        icon: 'key',
        role: 'platform-admin',
      },
    ],
  },
  {
    id: 'people',
    labelKey: 'nav.groups.people',
    items: [
      {
        to: '/admin/people',
        labelKey: 'nav.employees',
        icon: 'people',
        role: 'tenant-admin',
      },
      {
        to: '/admin/people/import',
        labelKey: 'nav.employeeImport',
        icon: 'import',
        role: 'tenant-admin',
      },
      {
        to: '/admin/leave-policies',
        labelKey: 'nav.leavePolicies',
        icon: 'calendar',
        role: 'tenant-admin',
      },
      {
        to: '/admin/leave-approvals',
        labelKey: 'nav.leaveApprovals',
        icon: 'check',
        role: 'tenant-admin',
      },
      {
        to: '/admin/leave-routing-exceptions',
        labelKey: 'nav.leaveRoutingExceptions',
        icon: 'check',
        role: 'tenant-admin',
      },
      {
        to: '/admin/leave-approval-substitutes',
        labelKey: 'nav.leaveApprovalSubstitutes',
        icon: 'people',
        role: 'tenant-admin',
      },
    ],
  },
  {
    id: 'operations',
    labelKey: 'nav.groups.operations',
    items: [
      {
        to: '/admin/work-schedules',
        labelKey: 'nav.workSchedules',
        icon: 'clock',
        role: 'tenant-admin',
      },
      {
        to: '/admin/operations',
        labelKey: 'nav.operationsStatus',
        icon: 'pulse',
        role: 'tenant-admin',
      },
      { to: '/admin/tasks', labelKey: 'nav.tasks', icon: 'check', role: 'tenant-admin' },
      { to: '/admin/expenses', labelKey: 'nav.expenses', icon: 'expenses', role: 'tenant-admin' },
      {
        to: '/admin/attendance-review',
        labelKey: 'nav.attendanceReview',
        icon: 'check',
        role: 'tenant-admin',
      },
      {
        to: '/admin/timesheets',
        labelKey: 'nav.timesheets',
        icon: 'calendar',
        role: 'tenant-admin',
      },
      {
        to: '/admin/time-exports',
        labelKey: 'nav.timeExports',
        icon: 'payroll',
        role: 'tenant-admin',
      },
    ],
  },
  {
    id: 'payroll',
    labelKey: 'nav.groups.payroll',
    items: [
      {
        to: '/admin/payroll-preparation',
        labelKey: 'nav.payrollPreparation',
        icon: 'payroll',
        role: 'tenant-admin',
      },
    ],
  },
  {
    id: 'organization',
    labelKey: 'nav.groups.organization',
    items: [
      {
        to: '/admin/hierarchy',
        labelKey: 'nav.hierarchy',
        icon: 'structure',
        role: 'tenant-admin',
      },
    ],
  },
  {
    id: 'documents',
    labelKey: 'nav.groups.documents',
    items: [
      {
        to: '/admin/contract-templates',
        labelKey: 'nav.contractTemplates',
        icon: 'contract',
        role: 'tenant-admin',
      },
      {
        to: '/admin/contract-expirations',
        labelKey: 'nav.contractExpirations',
        icon: 'expiry',
        role: 'tenant-admin',
      },
    ],
  },
  {
    id: 'access',
    labelKey: 'nav.groups.access',
    items: [
      { to: '/admin/users', labelKey: 'nav.users', icon: 'invite', role: 'tenant-admin' },
      { to: '/admin/access', labelKey: 'nav.accessReview', icon: 'shield', role: 'tenant-admin' },
    ],
  },
  {
    id: 'integrations',
    labelKey: 'nav.groups.integrations',
    items: [
      { to: '/admin/webhooks', labelKey: 'nav.webhooks', icon: 'webhook', role: 'tenant-admin' },
      {
        to: '/admin/integrations/whatsapp',
        labelKey: 'nav.whatsapp',
        icon: 'phone',
        role: 'tenant-admin',
      },
    ],
  },
  {
    id: 'mySpace',
    labelKey: 'nav.groups.mySpace',
    items: [
      { to: '/me/contracts', labelKey: 'nav.myContracts', icon: 'contract', role: 'employee' },
      { to: '/me/schedule', labelKey: 'nav.mySchedule', icon: 'clock', role: 'employee' },
      { to: '/me/attendance', labelKey: 'nav.myAttendance', icon: 'check', role: 'employee' },
      { to: '/me/timesheets', labelKey: 'nav.myTimesheets', icon: 'calendar', role: 'employee' },
      {
        to: '/me/time-approvals',
        labelKey: 'nav.timeApprovals',
        icon: 'check',
        role: 'employee',
      },
      {
        to: '/me/team-operations',
        labelKey: 'nav.teamOperations',
        icon: 'pulse',
        role: 'employee',
      },
      {
        to: '/me/team-attendance',
        labelKey: 'nav.teamAttendance',
        icon: 'people',
        role: 'employee',
      },
      {
        to: '/me/schedule/substitutions',
        labelKey: 'nav.myScheduleSubstitutions',
        icon: 'rotation',
        role: 'employee',
      },
      { to: '/me/leave', labelKey: 'nav.myLeave', icon: 'calendar', role: 'employee' },
      {
        to: '/me/leave/approvals',
        labelKey: 'nav.myLeaveApprovals',
        icon: 'check',
        role: 'employee',
      },
      { to: '/me/tasks', labelKey: 'nav.myTasks', icon: 'check', role: 'employee' },
      { to: '/me/expenses', labelKey: 'nav.myExpenses', icon: 'expenses', role: 'employee' },
      {
        to: '/me/compensation',
        labelKey: 'nav.myCompensation',
        icon: 'contract',
        role: 'employee',
      },
      {
        to: '/me/expense-approvals',
        labelKey: 'nav.expenseApprovals',
        icon: 'check',
        role: 'employee',
      },
    ],
  },
];

/** Roles the navigation may rely on: only a loaded, server-verified session ever grants any. */
export function navigationRoles(session: SessionState): readonly Role[] {
  return session.kind === 'ready' ? session.session.roles : [];
}

/**
 * The groups and items a holder of these roles may open. Anonymous, loading, failed and role-less
 * sessions get Home only, so a deep link opened while the session loads never shows another
 * role's destinations.
 */
export function visibleGroups(roles: readonly Role[]): NavGroupDef[] {
  return NAVIGATION.map((group) => ({
    ...group,
    items: group.items.filter((item) => item.role === null || roles.includes(item.role)),
  })).filter((group) => group.items.length > 0);
}

/**
 * The navigation item for a matched route pattern: the item with the longest path that is the
 * pattern itself or one of its parents. "/admin/people/:employeeId" belongs to Employees, while
 * "/admin/people/import" has its own item. Home matches only "/".
 */
export function activeItemPath(routePattern: string | null): string | null {
  if (!routePattern) return null;
  let best: string | null = null;
  for (const group of NAVIGATION) {
    for (const { to } of group.items) {
      const matches =
        to === '/'
          ? routePattern === '/'
          : routePattern === to || routePattern.startsWith(`${to}/`);
      if (matches && (best === null || to.length > best.length)) best = to;
    }
  }
  return best;
}

import type { CurrentSession } from '@divalhr/api-client';
import type { ReactNode } from 'react';
import { CallbackPage } from '../auth/CallbackPage';
import { AccessReviewPage } from '../features/access-review/AccessReviewPage';
import { CreateOrganizationPage } from '../features/admin/CreateOrganizationPage';
import { FirstAdministratorPage } from '../features/admin/FirstAdministratorPage';
import { ContractExpirationsPage } from '../features/contracts/ContractExpirationsPage';
import { ContractTemplatePage } from '../features/contracts/ContractTemplatePage';
import { ContractTemplatesPage } from '../features/contracts/ContractTemplatesPage';
import { MyContractPage } from '../features/contracts/MyContractPage';
import { MyContractsPage } from '../features/contracts/MyContractsPage';
import { HierarchyPage } from '../features/hierarchy/HierarchyPage';
import { HomePage } from '../features/home/HomePage';
import { AcceptInvitationPage } from '../features/invitation/AcceptInvitationPage';
import { EmployeeDirectoryPage } from '../features/people/EmployeeDirectoryPage';
import { EmployeeImportPage } from '../features/people/EmployeeImportPage';
import { EmployeeProfilePage } from '../features/people/EmployeeProfilePage';
import { StatusPage } from '../features/status/StatusPage';
import { UsersPage } from '../features/users/UsersPage';

export type Role = CurrentSession['roles'][number];

/** A breadcrumb step: a translation key, and a path when the step is a page of its own. */
export interface CrumbDef {
  key: string;
  to?: string;
}

/**
 * UI-001 route registry. Presentation metadata only: the guard it names is the same usability
 * guard as before, and the Core API authorizes every request. URLs are unchanged.
 */
export interface AppRoute {
  path: string;
  element: ReactNode;
  /** Pages for one role, wrapped in that role's existing guard with these message keys. */
  access?: { role: Role; deniedKey: string; signInKey: string };
  /** Public flows render without the signed-in sidebar and user menu, even when signed in. */
  frame?: 'public';
  /** UI-002: anonymous visitors see this route in the landing-page frame (D1). */
  anonymousFrame?: 'landing';
  crumbs?: CrumbDef[];
}

const HOME: CrumbDef = { key: 'nav.home', to: '/' };
const PEOPLE: CrumbDef = { key: 'nav.groups.people' };
const ORGANIZATION: CrumbDef = { key: 'nav.groups.organization' };
const DOCUMENTS: CrumbDef = { key: 'nav.groups.documents' };
const ACCESS: CrumbDef = { key: 'nav.groups.access' };
const PLATFORM: CrumbDef = { key: 'nav.groups.platform' };
const MY_SPACE: CrumbDef = { key: 'nav.groups.mySpace' };

const tenantAdmin = (deniedKey: string, signInKey: string) =>
  ({ role: 'tenant-admin', deniedKey, signInKey }) as const;

export const ROUTES: AppRoute[] = [
  { path: '/', element: <HomePage />, anonymousFrame: 'landing' },
  { path: '/status', element: <StatusPage />, crumbs: [HOME, { key: 'nav.status' }] },
  {
    path: '/admin/organizations/new',
    element: <CreateOrganizationPage />,
    access: {
      role: 'platform-admin',
      deniedKey: 'createOrganization.unauthorized',
      signInKey: 'createOrganization.signInRequired',
    },
    crumbs: [HOME, PLATFORM, { key: 'nav.platformAdmin' }],
  },
  {
    path: '/admin/organizations/first-admin',
    element: <FirstAdministratorPage />,
    access: {
      role: 'platform-admin',
      deniedKey: 'firstAdmin.unauthorized',
      signInKey: 'firstAdmin.signInRequired',
    },
    crumbs: [HOME, PLATFORM, { key: 'nav.firstAdmin' }],
  },
  {
    path: '/admin/hierarchy',
    element: <HierarchyPage />,
    access: tenantAdmin('hierarchy.unauthorized', 'hierarchy.signInRequired'),
    crumbs: [HOME, ORGANIZATION, { key: 'nav.hierarchy' }],
  },
  {
    path: '/admin/users',
    element: <UsersPage />,
    access: tenantAdmin('users.unauthorized', 'users.signInRequired'),
    crumbs: [HOME, ACCESS, { key: 'nav.users' }],
  },
  {
    path: '/admin/access',
    element: <AccessReviewPage />,
    access: tenantAdmin('accessReview.unauthorized', 'accessReview.signInRequired'),
    crumbs: [HOME, ACCESS, { key: 'nav.accessReview' }],
  },
  {
    path: '/admin/people',
    element: <EmployeeDirectoryPage />,
    access: tenantAdmin('employees.unauthorized', 'employees.signInRequired'),
    crumbs: [HOME, PEOPLE, { key: 'nav.employees' }],
  },
  {
    path: '/admin/people/:employeeId',
    element: <EmployeeProfilePage />,
    access: tenantAdmin('employees.unauthorized', 'employees.signInRequired'),
    crumbs: [
      HOME,
      PEOPLE,
      { key: 'nav.employees', to: '/admin/people' },
      { key: 'shell.crumb.employeeRecord' },
    ],
  },
  {
    path: '/admin/people/import',
    element: <EmployeeImportPage />,
    access: tenantAdmin('employeeImport.unauthorized', 'employeeImport.signInRequired'),
    crumbs: [HOME, PEOPLE, { key: 'nav.employeeImport' }],
  },
  {
    path: '/admin/contract-templates',
    element: <ContractTemplatesPage />,
    access: tenantAdmin('contracts.unauthorized', 'contracts.signInRequired'),
    crumbs: [HOME, DOCUMENTS, { key: 'nav.contractTemplates' }],
  },
  {
    path: '/admin/contract-templates/:templateId',
    element: <ContractTemplatePage />,
    access: tenantAdmin('contracts.unauthorized', 'contracts.signInRequired'),
    crumbs: [
      HOME,
      DOCUMENTS,
      { key: 'nav.contractTemplates', to: '/admin/contract-templates' },
      { key: 'shell.crumb.contractTemplate' },
    ],
  },
  {
    path: '/admin/contract-expirations',
    element: <ContractExpirationsPage />,
    access: tenantAdmin('contractExpirations.unauthorized', 'contractExpirations.signInRequired'),
    crumbs: [HOME, DOCUMENTS, { key: 'nav.contractExpirations' }],
  },
  {
    path: '/me/contracts',
    element: <MyContractsPage />,
    access: {
      role: 'employee',
      deniedKey: 'contracts.my.unauthorized',
      signInKey: 'contracts.my.signInRequired',
    },
    crumbs: [HOME, MY_SPACE, { key: 'nav.myContracts' }],
  },
  {
    path: '/me/contracts/:contractId',
    element: <MyContractPage />,
    access: {
      role: 'employee',
      deniedKey: 'contracts.my.unauthorized',
      signInKey: 'contracts.my.signInRequired',
    },
    crumbs: [
      HOME,
      MY_SPACE,
      { key: 'nav.myContracts', to: '/me/contracts' },
      { key: 'shell.crumb.contract' },
    ],
  },
  { path: '/invitation', element: <AcceptInvitationPage />, frame: 'public' },
  { path: '/auth/callback', element: <CallbackPage />, frame: 'public' },
];

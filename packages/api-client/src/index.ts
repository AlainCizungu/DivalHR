import createClient, { type Middleware } from 'openapi-fetch';
import type { paths as AiServicePaths } from './generated/ai-service';
import type { components as CoreComponents, paths as CorePaths } from './generated/core-api';

export type SystemStatus = CoreComponents['schemas']['SystemStatus'];
export type CurrentSession = CoreComponents['schemas']['CurrentSession'];
export type Problem = CoreComponents['schemas']['Problem'];
export type ErrorCode = CoreComponents['schemas']['ErrorCode'];
export type Organization = CoreComponents['schemas']['Organization'];
export type AccessReviewPage = CoreComponents['schemas']['AccessReviewPage'];
export type AccessReviewEntry = CoreComponents['schemas']['AccessReviewEntry'];
export type AccessReviewSummary = CoreComponents['schemas']['AccessReviewSummary'];
export type AccessReviewLookup = CoreComponents['schemas']['AccessReviewLookup'];
export type CreateOrganization = CoreComponents['schemas']['CreateOrganization'];
export type LegalEntity = CoreComponents['schemas']['LegalEntity'];
export type CreateLegalEntity = CoreComponents['schemas']['CreateLegalEntity'];
export type LegalEntityPage = CoreComponents['schemas']['LegalEntityPage'];
export type Site = CoreComponents['schemas']['Site'];
export type CreateSite = CoreComponents['schemas']['CreateSite'];
export type SitePage = CoreComponents['schemas']['SitePage'];
export type Department = CoreComponents['schemas']['Department'];
export type CreateDepartment = CoreComponents['schemas']['CreateDepartment'];
export type DepartmentPage = CoreComponents['schemas']['DepartmentPage'];
export type CostCenter = CoreComponents['schemas']['CostCenter'];
export type CreateCostCenter = CoreComponents['schemas']['CreateCostCenter'];
export type CostCenterPage = CoreComponents['schemas']['CostCenterPage'];
export type Region = CoreComponents['schemas']['Region'];
export type CreateRegion = CoreComponents['schemas']['CreateRegion'];
export type RegionPage = CoreComponents['schemas']['RegionPage'];
export type AssignSiteRegion = CoreComponents['schemas']['AssignSiteRegion'];
export type Team = CoreComponents['schemas']['Team'];
/** Exactly one parent: a discriminated union generated from the CreateTeam oneOf. */
export type CreateTeam = CoreComponents['schemas']['CreateTeam'];
export type TeamPage = CoreComponents['schemas']['TeamPage'];
export type InvitationRole = CoreComponents['schemas']['InvitationRole'];
export type InvitationStatus = CoreComponents['schemas']['InvitationStatus'];
export type DeliveryState = CoreComponents['schemas']['DeliveryState'];
export type CreateInvitation = CoreComponents['schemas']['CreateInvitation'];
/** Create and resend responses: no email address, replayed exactly. */
export type InvitationReceipt = CoreComponents['schemas']['InvitationReceipt'];
export type Invitation = CoreComponents['schemas']['Invitation'];
export type InvitationPage = CoreComponents['schemas']['InvitationPage'];
export type InvitationPreview = CoreComponents['schemas']['InvitationPreview'];
export type InvitationAcceptance = CoreComponents['schemas']['InvitationAcceptance'];
/** MVP-014: who created an invitation. */
export type InvitationOrigin = CoreComponents['schemas']['InvitationOrigin'];
/** MVP-014: a platform administrator invites an organization's first tenant administrator. */
export type CreateTenantAdminBootstrap = CoreComponents['schemas']['CreateTenantAdminBootstrap'];
/** MVP-014: bootstrap availability and the open bootstrap invitation (no address). */
export type TenantAdminBootstrap = CoreComponents['schemas']['TenantAdminBootstrap'];
/** MVP-020: an employee import (status and counts only, never personal data). */
export type EmployeeImport = CoreComponents['schemas']['EmployeeImport'];
export type EmployeeImportRowPage = CoreComponents['schemas']['EmployeeImportRowPage'];
export type EmployeeImportRow = CoreComponents['schemas']['EmployeeImportRow'];
export type EmployeeImportRowErrorCode = CoreComponents['schemas']['EmployeeImportRowErrorCode'];
export type EmployeeImportColumn = CoreComponents['schemas']['EmployeeImportColumn'];
export type CommitEmployeeImport = CoreComponents['schemas']['CommitEmployeeImport'];
/**
 * The listTeams parent filter: exactly one of departmentId or costCenterId
 * (x-divalhr-exactly-one-of in docs/API-SPEC.yaml).
 */
export type TeamParentQuery =
  { departmentId: string; costCenterId?: never } | { costCenterId: string; departmentId?: never };

export interface ClientOptions {
  /** Base URL including /api/v1, e.g. http://localhost:8080/api/v1 */
  baseUrl: string;
  /** Returns the current in-memory access token, if any. Tokens are never persisted. */
  getAccessToken?: () => string | undefined;
  fetch?: typeof globalThis.fetch;
  /**
   * Called when the Core answers 403 MFA_REQUIRED (MVP-011): the session must step up to
   * multifactor authentication. The response is still returned to the caller unchanged.
   */
  onMfaRequired?: () => void;
}

/** RFC 9470 step-up: a 403 problem whose stable code is MFA_REQUIRED. */
export async function isMfaRequired(response: Response): Promise<boolean> {
  if (response.status !== 403) return false;
  if (!(response.headers.get('Content-Type') ?? '').includes('json')) return false;
  try {
    const body = (await response.clone().json()) as { code?: unknown };
    return body.code === 'MFA_REQUIRED';
  } catch {
    return false;
  }
}

const correlationMiddleware: Middleware = {
  onRequest({ request }) {
    if (!request.headers.has('X-Correlation-Id')) {
      request.headers.set('X-Correlation-Id', crypto.randomUUID());
    }
    return request;
  },
};

/** Client for the Core API (authoritative contract: docs/API-SPEC.yaml). */
export function createCoreApiClient(options: ClientOptions) {
  const client = createClient<CorePaths>({ baseUrl: options.baseUrl, fetch: options.fetch });
  client.use(correlationMiddleware);
  if (options.getAccessToken) {
    const getToken = options.getAccessToken;
    client.use({
      onRequest({ request }) {
        const token = getToken();
        if (token) request.headers.set('Authorization', `Bearer ${token}`);
        return request;
      },
    });
  }
  if (options.onMfaRequired) {
    const onMfaRequired = options.onMfaRequired;
    client.use({
      async onResponse({ response }) {
        if (await isMfaRequired(response)) onMfaRequired();
        return response;
      },
    });
  }
  return client;
}

/**
 * Client for the AI Service public status endpoint only. The AI Service is not a browser-facing
 * backend; no token is ever attached.
 */
export function createAiServiceStatusClient(options: Omit<ClientOptions, 'getAccessToken'>) {
  const client = createClient<AiServicePaths>({ baseUrl: options.baseUrl, fetch: options.fetch });
  client.use(correlationMiddleware);
  return client;
}

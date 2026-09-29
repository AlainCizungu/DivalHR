import createClient, { type Middleware } from 'openapi-fetch';
import type { paths as AiServicePaths } from './generated/ai-service';
import type { components as CoreComponents, paths as CorePaths } from './generated/core-api';

export type SystemStatus = CoreComponents['schemas']['SystemStatus'];
export type CurrentSession = CoreComponents['schemas']['CurrentSession'];
export type Problem = CoreComponents['schemas']['Problem'];
export type ErrorCode = CoreComponents['schemas']['ErrorCode'];
export type Organization = CoreComponents['schemas']['Organization'];
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

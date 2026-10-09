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
export type EmployeeSummary = CoreComponents['schemas']['EmployeeSummary'];
export type EmployeePage = CoreComponents['schemas']['EmployeePage'];
export type EmployeeSearch = CoreComponents['schemas']['EmployeeSearch'];
export type EmployeeProfile = CoreComponents['schemas']['EmployeeProfile'];
export type Employment = CoreComponents['schemas']['Employment'];
export type EmploymentStatus = CoreComponents['schemas']['EmploymentStatus'];
export type Assignment = CoreComponents['schemas']['Assignment'];
export type AssignmentKind = CoreComponents['schemas']['AssignmentKind'];
export type AssignmentStatus = CoreComponents['schemas']['AssignmentStatus'];
export type AssignmentPage = CoreComponents['schemas']['AssignmentPage'];
export type AssignmentPeriod = CoreComponents['schemas']['AssignmentPeriod'];
export type PlacementValue = CoreComponents['schemas']['PlacementValue'];
export type PlacementInput = CoreComponents['schemas']['PlacementInput'];
export type ManagerValue = CoreComponents['schemas']['ManagerValue'];
export type UnitRef = CoreComponents['schemas']['UnitRef'];
export type ContractClassification = CoreComponents['schemas']['ContractClassification'];
export type CompensationBasis = CoreComponents['schemas']['CompensationBasis'];
export type EmploymentChange = CoreComponents['schemas']['EmploymentChange'];
export type EmploymentChangePage = CoreComponents['schemas']['EmploymentChangePage'];
export type EmploymentChangeReason = CoreComponents['schemas']['EmploymentChangeReason'];
export type EmploymentChangeTiming = CoreComponents['schemas']['EmploymentChangeTiming'];
export type EmploymentChangeCommand = CoreComponents['schemas']['EmploymentChangeCommand'];
export type CreateEmploymentChange = CoreComponents['schemas']['CreateEmploymentChange'];
export type EmploymentChangePreview = CoreComponents['schemas']['EmploymentChangePreview'];
export type EmploymentChangeCancellationPreview =
  CoreComponents['schemas']['EmploymentChangeCancellationPreview'];
export type CancelEmploymentChange = CoreComponents['schemas']['CancelEmploymentChange'];
export type EmploymentChangeResult = CoreComponents['schemas']['EmploymentChangeResult'];
export type PreviewKind = CoreComponents['schemas']['PreviewKind'];
export type EmployeeAccess = CoreComponents['schemas']['EmployeeAccess'];
export type EmployeeAccessLink = CoreComponents['schemas']['EmployeeAccessLink'];
export type EmployeeAccessLinkState = CoreComponents['schemas']['EmployeeAccessLinkState'];
export type AccessLinkCandidate = CoreComponents['schemas']['AccessLinkCandidate'];
export type SeparationReason = CoreComponents['schemas']['SeparationReason'];
export type SeparationAccessTiming = CoreComponents['schemas']['SeparationAccessTiming'];
export type SeparationAcknowledgement = CoreComponents['schemas']['SeparationAcknowledgement'];
export type SeparationReportPlan = CoreComponents['schemas']['SeparationReportPlan'];
export type SeparationCommand = CoreComponents['schemas']['SeparationCommand'];
export type CreateSeparation = CoreComponents['schemas']['CreateSeparation'];
export type SeparationBlocker = CoreComponents['schemas']['SeparationBlocker'];
export type SeparationReport = CoreComponents['schemas']['SeparationReport'];
export type SeparationAccessPreview = CoreComponents['schemas']['SeparationAccessPreview'];
export type SeparationPreview = CoreComponents['schemas']['SeparationPreview'];
export type SeparationTask = CoreComponents['schemas']['SeparationTask'];
export type SeparationTaskCode = CoreComponents['schemas']['SeparationTaskCode'];
export type SeparationTaskStatus = CoreComponents['schemas']['SeparationTaskStatus'];
export type SeparationAccessState = CoreComponents['schemas']['SeparationAccessState'];
export type Separation = CoreComponents['schemas']['Separation'];
export type SeparationList = CoreComponents['schemas']['SeparationList'];
export type SeparationResult = CoreComponents['schemas']['SeparationResult'];
export type SeparationCancellationPreview =
  CoreComponents['schemas']['SeparationCancellationPreview'];
export type ContractLocale = CoreComponents['schemas']['ContractLocale'];
export type ContractTemplateVersionState =
  CoreComponents['schemas']['ContractTemplateVersionState'];
export type ContractState = CoreComponents['schemas']['ContractState'];
export type ContractVoidReason = CoreComponents['schemas']['ContractVoidReason'];
export type ContractTemplateProblemReason =
  CoreComponents['schemas']['ContractTemplateProblemReason'];
export type ContractPlaceholder = CoreComponents['schemas']['ContractPlaceholder'];
export type CreateContractTemplate = CoreComponents['schemas']['CreateContractTemplate'];
export type ContractTemplateLine = CoreComponents['schemas']['ContractTemplateLine'];
export type ContractTemplateSummary = CoreComponents['schemas']['ContractTemplateSummary'];
export type ContractTemplatePage = CoreComponents['schemas']['ContractTemplatePage'];
export type ContractTemplateVersionSummary =
  CoreComponents['schemas']['ContractTemplateVersionSummary'];
export type ContractTemplate = CoreComponents['schemas']['ContractTemplate'];
export type ContractTemplateVersion = CoreComponents['schemas']['ContractTemplateVersion'];
export type ContractTemplateValidation = CoreComponents['schemas']['ContractTemplateValidation'];
export type ContractTemplateProblem = CoreComponents['schemas']['ContractTemplateProblem'];
export type CreateContractTemplateVersion =
  CoreComponents['schemas']['CreateContractTemplateVersion'];
export type UpdateContractTemplateVersion =
  CoreComponents['schemas']['UpdateContractTemplateVersion'];
export type ContractBlock = CoreComponents['schemas']['ContractBlock'];
export type ContractSnapshot = CoreComponents['schemas']['ContractSnapshot'];
export type ContractIntegrity = CoreComponents['schemas']['ContractIntegrity'];
export type ContractPreviewCommand = CoreComponents['schemas']['ContractPreviewCommand'];
export type IssueContract = CoreComponents['schemas']['IssueContract'];
export type ContractWarning = CoreComponents['schemas']['ContractWarning'];
export type ContractPreview = CoreComponents['schemas']['ContractPreview'];
export type ContractAcknowledgementEvidence =
  CoreComponents['schemas']['ContractAcknowledgementEvidence'];
export type ContractSummary = CoreComponents['schemas']['ContractSummary'];
export type ContractPage = CoreComponents['schemas']['ContractPage'];
export type Contract = CoreComponents['schemas']['Contract'];
export type VoidContract = CoreComponents['schemas']['VoidContract'];
export type MyContractSummary = CoreComponents['schemas']['MyContractSummary'];
export type MyContractPage = CoreComponents['schemas']['MyContractPage'];
export type AcknowledgementStatement = CoreComponents['schemas']['AcknowledgementStatement'];
export type MyContract = CoreComponents['schemas']['MyContract'];
export type AcknowledgeContract = CoreComponents['schemas']['AcknowledgeContract'];
export type ContractAcknowledgementResult =
  CoreComponents['schemas']['ContractAcknowledgementResult'];
export type ContractExpirationCategory = CoreComponents['schemas']['ContractExpirationCategory'];
export type ContractExpirationSearch = CoreComponents['schemas']['ContractExpirationSearch'];
export type ContractExpirationCounts = CoreComponents['schemas']['ContractExpirationCounts'];
export type ContractExpirationUnit = CoreComponents['schemas']['ContractExpirationUnit'];
export type ContractExpiration = CoreComponents['schemas']['ContractExpiration'];
export type ContractExpirationPage = CoreComponents['schemas']['ContractExpirationPage'];
export type ContractExpirationSummary = CoreComponents['schemas']['ContractExpirationSummary'];
export type LeaveUnit = CoreComponents['schemas']['LeaveUnit'];
export type LeaveBalanceMode = CoreComponents['schemas']['LeaveBalanceMode'];
export type LeaveApprovalRoute = CoreComponents['schemas']['LeaveApprovalRoute'];
export type LeavePayrollEffect = CoreComponents['schemas']['LeavePayrollEffect'];
export type LeavePolicyStatus = CoreComponents['schemas']['LeavePolicyStatus'];
export type LeavePolicy = CoreComponents['schemas']['LeavePolicy'];
export type LeavePolicyPage = CoreComponents['schemas']['LeavePolicyPage'];
export type LeavePolicyResult = CoreComponents['schemas']['LeavePolicyResult'];
export type CreateLeavePolicy = CoreComponents['schemas']['CreateLeavePolicy'];
export type LeaveRequestState = CoreComponents['schemas']['LeaveRequestState'];
export type MyLeavePolicy = CoreComponents['schemas']['MyLeavePolicy'];
export type MyLeavePolicyPage = CoreComponents['schemas']['MyLeavePolicyPage'];
export type MyLeaveRequest = CoreComponents['schemas']['MyLeaveRequest'];
export type MyLeaveRequestPage = CoreComponents['schemas']['MyLeaveRequestPage'];
export type CreateMyLeaveRequest = CoreComponents['schemas']['CreateMyLeaveRequest'];
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

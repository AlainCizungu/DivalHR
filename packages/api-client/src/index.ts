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
export type MyLeaveDecision = CoreComponents['schemas']['MyLeaveDecision'];
export type LeaveApproval = CoreComponents['schemas']['LeaveApproval'];
export type LeaveApprovalPage = CoreComponents['schemas']['LeaveApprovalPage'];
export type DecideLeaveRequest = CoreComponents['schemas']['DecideLeaveRequest'];
export type LeaveDecisionReceipt = CoreComponents['schemas']['LeaveDecisionReceipt'];
export type MyLeaveCancellation = CoreComponents['schemas']['MyLeaveCancellation'];
export type CancelMyLeaveRequest = CoreComponents['schemas']['CancelMyLeaveRequest'];
export type LeaveCancellationReceipt = CoreComponents['schemas']['LeaveCancellationReceipt'];
export type MyLeaveAmendment = CoreComponents['schemas']['MyLeaveAmendment'];
export type AmendMyLeaveRequest = CoreComponents['schemas']['AmendMyLeaveRequest'];
export type LeaveAmendmentReceipt = CoreComponents['schemas']['LeaveAmendmentReceipt'];
export type MyLeaveWithdrawal = CoreComponents['schemas']['MyLeaveWithdrawal'];
export type WithdrawMyApprovedLeave = CoreComponents['schemas']['WithdrawMyApprovedLeave'];
export type LeaveWithdrawalReceipt = CoreComponents['schemas']['LeaveWithdrawalReceipt'];
export type LeaveRoutingException = CoreComponents['schemas']['LeaveRoutingException'];
export type LeaveRoutingExceptionPage = CoreComponents['schemas']['LeaveRoutingExceptionPage'];
/** MVP-041G: leave approval substitutes (tenant administrators) and the inbox capacity. */
export type LeaveApprovalCapacity = CoreComponents['schemas']['LeaveApprovalCapacity'];
export type LeaveApprovalSubstitution = CoreComponents['schemas']['LeaveApprovalSubstitution'];
export type LeaveApprovalSubstitutionPage =
  CoreComponents['schemas']['LeaveApprovalSubstitutionPage'];
export type LeaveApprovalSubstitutionState =
  CoreComponents['schemas']['LeaveApprovalSubstitutionState'];
export type LeaveApprovalSubstitutionEmployee =
  CoreComponents['schemas']['LeaveApprovalSubstitutionEmployee'];
export type CreateLeaveApprovalSubstitution =
  CoreComponents['schemas']['CreateLeaveApprovalSubstitution'];
export type RevokeLeaveApprovalSubstitution =
  CoreComponents['schemas']['RevokeLeaveApprovalSubstitution'];
export type LeaveApprovalSubstitutionReceipt =
  CoreComponents['schemas']['LeaveApprovalSubstitutionReceipt'];
export type LeaveApprovalSubstitutionRevocationReceipt =
  CoreComponents['schemas']['LeaveApprovalSubstitutionRevocationReceipt'];
/** MVP-050A: site work schedules (tenant administrators) and the employee's own shifts. */
export type WorkScheduleRequest = CoreComponents['schemas']['WorkScheduleRequest'];
export type PublishWorkScheduleRequest = CoreComponents['schemas']['PublishWorkScheduleRequest'];
export type WorkScheduleSlotInput = CoreComponents['schemas']['WorkScheduleSlotInput'];
export type WorkScheduleLabels = CoreComponents['schemas']['WorkScheduleLabels'];
export type WorkScheduleEmployee = CoreComponents['schemas']['WorkScheduleEmployee'];
export type WorkScheduleSite = CoreComponents['schemas']['WorkScheduleSite'];
export type WorkScheduleTotals = CoreComponents['schemas']['WorkScheduleTotals'];
export type WorkScheduleSlotIssue = CoreComponents['schemas']['WorkScheduleSlotIssue'];
export type WorkSchedulePreview = CoreComponents['schemas']['WorkSchedulePreview'];
export type WorkSchedulePreviewSlot = CoreComponents['schemas']['WorkSchedulePreviewSlot'];
export type WorkSchedulePublicationReceipt =
  CoreComponents['schemas']['WorkSchedulePublicationReceipt'];
export type WorkScheduleSummary = CoreComponents['schemas']['WorkScheduleSummary'];
export type WorkSchedulePage = CoreComponents['schemas']['WorkSchedulePage'];
export type WorkScheduleSlot = CoreComponents['schemas']['WorkScheduleSlot'];
export type WorkScheduleDetail = CoreComponents['schemas']['WorkScheduleDetail'];
export type WorkScheduleStatus = CoreComponents['schemas']['WorkScheduleStatus'];
export type CancelWorkScheduleRequest = CoreComponents['schemas']['CancelWorkScheduleRequest'];
export type WorkScheduleReplacementRequest =
  CoreComponents['schemas']['WorkScheduleReplacementRequest'];
export type ReplaceWorkScheduleRequest = CoreComponents['schemas']['ReplaceWorkScheduleRequest'];
export type WorkScheduleChangeReceipt = CoreComponents['schemas']['WorkScheduleChangeReceipt'];
export type MyWorkShift = CoreComponents['schemas']['MyWorkShift'];
export type MyWorkSchedulePage = CoreComponents['schemas']['MyWorkSchedulePage'];
/** MVP-051: shift substitution. */
export type WorkScheduleAssignmentSource =
  CoreComponents['schemas']['WorkScheduleAssignmentSource'];
export type WorkScheduleSubstitutionState =
  CoreComponents['schemas']['WorkScheduleSubstitutionState'];
export type CreateWorkScheduleSubstitutionRequest =
  CoreComponents['schemas']['CreateWorkScheduleSubstitutionRequest'];
export type DecideWorkScheduleSubstitution =
  CoreComponents['schemas']['DecideWorkScheduleSubstitution'];
export type WorkScheduleSubstitutionDecisionReceipt =
  CoreComponents['schemas']['WorkScheduleSubstitutionDecisionReceipt'];
export type WorkScheduleSubstitutionShift =
  CoreComponents['schemas']['WorkScheduleSubstitutionShift'];
export type WorkScheduleSubstitutionPerson =
  CoreComponents['schemas']['WorkScheduleSubstitutionPerson'];
export type MyWorkScheduleSubstitutionDecision =
  CoreComponents['schemas']['MyWorkScheduleSubstitutionDecision'];
export type MyWorkScheduleSubstitution = CoreComponents['schemas']['MyWorkScheduleSubstitution'];
export type MyWorkScheduleSubstitutionPage =
  CoreComponents['schemas']['MyWorkScheduleSubstitutionPage'];
export type WorkScheduleSubstitutionApprovalEmployee =
  CoreComponents['schemas']['WorkScheduleSubstitutionApprovalEmployee'];
export type WorkScheduleSubstitutionApproval =
  CoreComponents['schemas']['WorkScheduleSubstitutionApproval'];
export type WorkScheduleSubstitutionApprovalPage =
  CoreComponents['schemas']['WorkScheduleSubstitutionApprovalPage'];
export type WorkScheduleSubstitutionCandidatePage =
  CoreComponents['schemas']['WorkScheduleSubstitutionCandidatePage'];
export type WorkScheduleSlotSubstitution =
  CoreComponents['schemas']['WorkScheduleSlotSubstitution'];
/** MVP-060A: online attendance on scheduled shifts. */
export type MyWorkShiftAttendance = CoreComponents['schemas']['MyWorkShiftAttendance'];
export type WorkShiftAttendanceStatus = CoreComponents['schemas']['WorkShiftAttendanceStatus'];
export type AttendanceStatus = CoreComponents['schemas']['AttendanceStatus'];
export type CheckInAttendanceRequest = CoreComponents['schemas']['CheckInAttendanceRequest'];
export type AttendanceReceipt = CoreComponents['schemas']['AttendanceReceipt'];
export type AttendanceShift = CoreComponents['schemas']['AttendanceShift'];
export type MyAttendanceRecord = CoreComponents['schemas']['MyAttendanceRecord'];
export type MyAttendancePage = CoreComponents['schemas']['MyAttendancePage'];
/** MVP-060B: supervisor-assisted attendance for direct reports. */
export type AttendanceRecordingMethod = CoreComponents['schemas']['AttendanceRecordingMethod'];
export type TeamAttendanceRequiredAction =
  CoreComponents['schemas']['TeamAttendanceRequiredAction'];
export type SupervisorCheckInRequest = CoreComponents['schemas']['SupervisorCheckInRequest'];
export type SupervisorCheckOutRequest = CoreComponents['schemas']['SupervisorCheckOutRequest'];
export type SupervisorAttendanceReceipt = CoreComponents['schemas']['SupervisorAttendanceReceipt'];
export type TeamAttendanceAction = CoreComponents['schemas']['TeamAttendanceAction'];
export type TeamAttendanceActionPage = CoreComponents['schemas']['TeamAttendanceActionPage'];
/** MVP-060C: factual attendance exceptions (employee, manager and tenant administrator views). */
export type AttendanceExceptionType = CoreComponents['schemas']['AttendanceExceptionType'];
export type AttendanceException = CoreComponents['schemas']['AttendanceException'];
export type AttendanceExceptionPage = CoreComponents['schemas']['AttendanceExceptionPage'];
export type TeamAttendanceException = CoreComponents['schemas']['TeamAttendanceException'];
export type TeamAttendanceExceptionPage = CoreComponents['schemas']['TeamAttendanceExceptionPage'];
/** MVP-100: today's operational status (administrator and manager views, one response shape). */
export type OperationalStatus = CoreComponents['schemas']['OperationalStatus'];
export type OperationalStatusNotCheckedIn =
  CoreComponents['schemas']['OperationalStatusNotCheckedIn'];
export type OperationalStatusShift = CoreComponents['schemas']['OperationalStatusShift'];
export type OperationalStatusOverdueLeave =
  CoreComponents['schemas']['OperationalStatusOverdueLeave'];
export type OperationalStatusOverdueTimesheet =
  CoreComponents['schemas']['OperationalStatusOverdueTimesheet'];
/** MVP-101: the grounded daily briefing (administrator and manager views, one response shape). */
export type GenerateOperationalSummaryRequest =
  CoreComponents['schemas']['GenerateOperationalSummaryRequest'];
export type OperationalSummary = CoreComponents['schemas']['OperationalSummary'];
export type OperationalSummaryHighlight = CoreComponents['schemas']['OperationalSummaryHighlight'];
/** MVP-061A: synchronization of one attendance event captured offline (consumed by MVP-061B). */
export type SyncOfflineAttendanceRequest =
  CoreComponents['schemas']['SyncOfflineAttendanceRequest'];
export type OfflineAttendanceEventType = CoreComponents['schemas']['OfflineAttendanceEventType'];
export type OfflineAttendanceSyncResult = CoreComponents['schemas']['OfflineAttendanceSyncResult'];
/** MVP-062: attendance corrections by tenant administrators. */
export type AttendanceCorrectionOperation =
  CoreComponents['schemas']['AttendanceCorrectionOperation'];
export type AttendanceCorrectionReason = CoreComponents['schemas']['AttendanceCorrectionReason'];
export type AttendanceCorrectionWarning = CoreComponents['schemas']['AttendanceCorrectionWarning'];
export type AttendanceCorrectionRequest = CoreComponents['schemas']['AttendanceCorrectionRequest'];
export type MissingAttendanceCorrectionRequest =
  CoreComponents['schemas']['MissingAttendanceCorrectionRequest'];
export type CommitAttendanceCorrectionRequest =
  CoreComponents['schemas']['CommitAttendanceCorrectionRequest'];
export type CommitMissingAttendanceCorrectionRequest =
  CoreComponents['schemas']['CommitMissingAttendanceCorrectionRequest'];
export type AttendanceCorrectionTimes = CoreComponents['schemas']['AttendanceCorrectionTimes'];
export type AttendanceCorrectionPreview = CoreComponents['schemas']['AttendanceCorrectionPreview'];
export type AttendanceCorrectionReceipt = CoreComponents['schemas']['AttendanceCorrectionReceipt'];
export type CorrectedAttendanceRecord = CoreComponents['schemas']['CorrectedAttendanceRecord'];
export type AttendanceCorrectionHistory = CoreComponents['schemas']['AttendanceCorrectionHistory'];
export type AttendanceCorrectionHistoryEntry =
  CoreComponents['schemas']['AttendanceCorrectionHistoryEntry'];
/** MVP-070A: weekly draft timesheets (administrators) and the employee's own drafts. */
export type TimesheetPeriodRequest = CoreComponents['schemas']['TimesheetPeriodRequest'];
export type GenerateTimesheetsRequest = CoreComponents['schemas']['GenerateTimesheetsRequest'];
export type RefreshTimesheetsRequest = CoreComponents['schemas']['RefreshTimesheetsRequest'];
export type TimesheetPeriod = CoreComponents['schemas']['TimesheetPeriod'];
export type TimesheetTotals = CoreComponents['schemas']['TimesheetTotals'];
export type TimesheetGenerationSummary = CoreComponents['schemas']['TimesheetGenerationSummary'];
export type TimesheetGenerationPreview = CoreComponents['schemas']['TimesheetGenerationPreview'];
export type TimesheetGenerationResult = CoreComponents['schemas']['TimesheetGenerationResult'];
export type TimesheetEmployee = CoreComponents['schemas']['TimesheetEmployee'];
export type TimesheetDraftItem = CoreComponents['schemas']['TimesheetDraftItem'];
export type TimesheetGeneration = CoreComponents['schemas']['TimesheetGeneration'];
export type TimesheetRefreshPreview = CoreComponents['schemas']['TimesheetRefreshPreview'];
export type TimesheetRefreshResult = CoreComponents['schemas']['TimesheetRefreshResult'];
export type TimesheetLabels = CoreComponents['schemas']['TimesheetLabels'];
export type TimesheetCostCenter = CoreComponents['schemas']['TimesheetCostCenter'];
export type TimesheetRow = CoreComponents['schemas']['TimesheetRow'];
export type TimesheetRevision = CoreComponents['schemas']['TimesheetRevision'];
export type TimesheetDetail = CoreComponents['schemas']['TimesheetDetail'];
export type MyTimesheetItem = CoreComponents['schemas']['MyTimesheetItem'];
export type MyTimesheetPage = CoreComponents['schemas']['MyTimesheetPage'];
export type MyTimesheetRow = CoreComponents['schemas']['MyTimesheetRow'];
export type MyTimesheet = CoreComponents['schemas']['MyTimesheet'];
export type SubmitTimesheetRequest = CoreComponents['schemas']['SubmitTimesheetRequest'];
export type ApproveTimesheetRequest = CoreComponents['schemas']['ApproveTimesheetRequest'];
export type ReturnTimesheetRequest = CoreComponents['schemas']['ReturnTimesheetRequest'];
export type TimesheetActionReceipt = CoreComponents['schemas']['TimesheetActionReceipt'];
export type TimesheetAction = CoreComponents['schemas']['TimesheetAction'];
export type MyTimesheetAction = CoreComponents['schemas']['MyTimesheetAction'];
export type MyTimesheetSubmission = CoreComponents['schemas']['MyTimesheetSubmission'];
export type TimeApprovalEmployee = CoreComponents['schemas']['TimeApprovalEmployee'];
export type TimeApprovalItem = CoreComponents['schemas']['TimeApprovalItem'];
export type TimeApprovalPage = CoreComponents['schemas']['TimeApprovalPage'];
export type TimeApprovalDetail = CoreComponents['schemas']['TimeApprovalDetail'];
// MVP-070E: approved-time exports.
export type TimesheetExportPreviewRequest =
  CoreComponents['schemas']['TimesheetExportPreviewRequest'];
export type CreateTimesheetExportRequest =
  CoreComponents['schemas']['CreateTimesheetExportRequest'];
export type TimesheetExportScope = CoreComponents['schemas']['TimesheetExportScope'];
export type TimesheetExportCounts = CoreComponents['schemas']['TimesheetExportCounts'];
export type TimesheetExportExclusions = CoreComponents['schemas']['TimesheetExportExclusions'];
export type TimesheetExportPreview = CoreComponents['schemas']['TimesheetExportPreview'];
export type TimesheetExport = CoreComponents['schemas']['TimesheetExport'];
export type TimesheetExportResult = CoreComponents['schemas']['TimesheetExportResult'];
export type TimesheetExportPage = CoreComponents['schemas']['TimesheetExportPage'];
// MVP-071: period locks.
export type TimesheetPeriodScopeRequest = CoreComponents['schemas']['TimesheetPeriodScopeRequest'];
export type LockTimesheetPeriodRequest = CoreComponents['schemas']['LockTimesheetPeriodRequest'];
export type TimesheetPeriodUnlockPreviewRequest =
  CoreComponents['schemas']['TimesheetPeriodUnlockPreviewRequest'];
export type UnlockTimesheetPeriodRequest =
  CoreComponents['schemas']['UnlockTimesheetPeriodRequest'];
export type TimesheetPeriodUnlockReason = CoreComponents['schemas']['TimesheetPeriodUnlockReason'];
export type TimesheetPeriodLockState = CoreComponents['schemas']['TimesheetPeriodLockState'];
export type TimesheetPeriodScope = CoreComponents['schemas']['TimesheetPeriodScope'];
export type TimesheetPeriodStates = CoreComponents['schemas']['TimesheetPeriodStates'];
export type TimesheetPeriodCounts = CoreComponents['schemas']['TimesheetPeriodCounts'];
export type TimesheetPeriodLockPreview = CoreComponents['schemas']['TimesheetPeriodLockPreview'];
export type TimesheetPeriodAction = CoreComponents['schemas']['TimesheetPeriodAction'];
export type TimesheetPeriodResult = CoreComponents['schemas']['TimesheetPeriodResult'];
export type TimesheetPeriodUnlockPreview =
  CoreComponents['schemas']['TimesheetPeriodUnlockPreview'];
export type TimesheetPeriodHistory = CoreComponents['schemas']['TimesheetPeriodHistory'];
// MVP-090A: payroll inputs.
export type PayrollInputPreviewRequest = CoreComponents['schemas']['PayrollInputPreviewRequest'];
export type GeneratePayrollInputsRequest =
  CoreComponents['schemas']['GeneratePayrollInputsRequest'];
export type PayrollInputScope = CoreComponents['schemas']['PayrollInputScope'];
export type PayrollInputCounts = CoreComponents['schemas']['PayrollInputCounts'];
export type PayrollInputCodeCount = CoreComponents['schemas']['PayrollInputCodeCount'];
export type PayrollInputWeek = CoreComponents['schemas']['PayrollInputWeek'];
export type PayrollInputPreview = CoreComponents['schemas']['PayrollInputPreview'];
export type PayrollInputRun = CoreComponents['schemas']['PayrollInputRun'];
export type PayrollInputSourceWeek = CoreComponents['schemas']['PayrollInputSourceWeek'];
export type PayrollInputEmployee = CoreComponents['schemas']['PayrollInputEmployee'];
export type PayrollInputRunDetail = CoreComponents['schemas']['PayrollInputRunDetail'];
export type PayrollInputResult = CoreComponents['schemas']['PayrollInputResult'];
export type PayrollInputRunPage = CoreComponents['schemas']['PayrollInputRunPage'];
export type PayrollInputLifecycle = CoreComponents['schemas']['PayrollInputLifecycle'];
export type SupersedePayrollInputRunRequest =
  CoreComponents['schemas']['SupersedePayrollInputRunRequest'];
export type LockPayrollInputRunRequest = CoreComponents['schemas']['LockPayrollInputRunRequest'];
export type CreatePayrollInputExportRequest =
  CoreComponents['schemas']['CreatePayrollInputExportRequest'];
export type PayrollInputSupersedePreview =
  CoreComponents['schemas']['PayrollInputSupersedePreview'];
export type PayrollInputSupersedeResult = CoreComponents['schemas']['PayrollInputSupersedeResult'];
export type PayrollInputLockPreview = CoreComponents['schemas']['PayrollInputLockPreview'];
export type PayrollInputLockResult = CoreComponents['schemas']['PayrollInputLockResult'];
export type PayrollInputVersion = CoreComponents['schemas']['PayrollInputVersion'];
export type PayrollInputVersions = CoreComponents['schemas']['PayrollInputVersions'];
export type PayrollInputExport = CoreComponents['schemas']['PayrollInputExport'];
export type PayrollInputExportResult = CoreComponents['schemas']['PayrollInputExportResult'];
export type PayrollInputExportPage = CoreComponents['schemas']['PayrollInputExportPage'];
export type WebhookEventType = CoreComponents['schemas']['WebhookEventType'];
export type CreateWebhookRequest = CoreComponents['schemas']['CreateWebhookRequest'];
export type UpdateWebhookRequest = CoreComponents['schemas']['UpdateWebhookRequest'];
export type WebhookCommandRequest = CoreComponents['schemas']['WebhookCommandRequest'];
export type TestDeliveryRequest = CoreComponents['schemas']['TestDeliveryRequest'];
export type Webhook = CoreComponents['schemas']['Webhook'];
export type WebhookResult = CoreComponents['schemas']['WebhookResult'];
export type WebhookPage = CoreComponents['schemas']['WebhookPage'];
export type WebhookDelivery = CoreComponents['schemas']['WebhookDelivery'];
export type WebhookDeliveryPage = CoreComponents['schemas']['WebhookDeliveryPage'];
export type WebhookDeliveryAttempt = CoreComponents['schemas']['WebhookDeliveryAttempt'];
export type WebhookDeliveryDetail = CoreComponents['schemas']['WebhookDeliveryDetail'];
export type WebhookTestDelivery = CoreComponents['schemas']['WebhookTestDelivery'];
/** MVP-111: the tenant's WhatsApp Business connector, recipients and deliveries. */
export type WhatsAppNotificationType = CoreComponents['schemas']['WhatsAppNotificationType'];
export type WhatsAppSelectableNotificationType = CoreComponents['schemas']['WhatsAppSelectableNotificationType'];
export type WhatsAppLanguage = CoreComponents['schemas']['WhatsAppLanguage'];
export type WhatsAppConsentSource = CoreComponents['schemas']['WhatsAppConsentSource'];
export type WhatsAppTemplateRequest = CoreComponents['schemas']['WhatsAppTemplateRequest'];
export type CreateWhatsAppConnectorRequest = CoreComponents['schemas']['CreateWhatsAppConnectorRequest'];
export type UpdateWhatsAppConnectorRequest = CoreComponents['schemas']['UpdateWhatsAppConnectorRequest'];
export type WhatsAppConnectorCommandRequest = CoreComponents['schemas']['WhatsAppConnectorCommandRequest'];
export type RotateWhatsAppTokenRequest = CoreComponents['schemas']['RotateWhatsAppTokenRequest'];
export type WhatsAppConsentRequest = CoreComponents['schemas']['WhatsAppConsentRequest'];
export type CreateWhatsAppRecipientRequest = CoreComponents['schemas']['CreateWhatsAppRecipientRequest'];
export type UpdateWhatsAppRecipientRequest = CoreComponents['schemas']['UpdateWhatsAppRecipientRequest'];
export type WhatsAppRecipientCommandRequest = CoreComponents['schemas']['WhatsAppRecipientCommandRequest'];
export type WhatsAppTestRequest = CoreComponents['schemas']['WhatsAppTestRequest'];
export type WhatsAppTemplate = CoreComponents['schemas']['WhatsAppTemplate'];
export type WhatsAppConnector = CoreComponents['schemas']['WhatsAppConnector'];
export type WhatsAppTemplateCheck = CoreComponents['schemas']['WhatsAppTemplateCheck'];
export type WhatsAppVerification = CoreComponents['schemas']['WhatsAppVerification'];
export type WhatsAppRecipient = CoreComponents['schemas']['WhatsAppRecipient'];
export type WhatsAppRecipientList = CoreComponents['schemas']['WhatsAppRecipientList'];
export type WhatsAppDelivery = CoreComponents['schemas']['WhatsAppDelivery'];
export type WhatsAppDeliveryPage = CoreComponents['schemas']['WhatsAppDeliveryPage'];
export type WhatsAppAttempt = CoreComponents['schemas']['WhatsAppAttempt'];
export type WhatsAppStatusCallback = CoreComponents['schemas']['WhatsAppStatusCallback'];
export type WhatsAppDeliveryDetail = CoreComponents['schemas']['WhatsAppDeliveryDetail'];
export type WhatsAppTestDelivery = CoreComponents['schemas']['WhatsAppTestDelivery'];
export type PayrollVarianceView = CoreComponents['schemas']['PayrollVarianceView'];
export type PayrollVarianceCandidates = CoreComponents['schemas']['PayrollVarianceCandidates'];
export type PayrollVarianceThresholds = CoreComponents['schemas']['PayrollVarianceThresholds'];
export type PayrollVarianceFigures = CoreComponents['schemas']['PayrollVarianceFigures'];
export type PayrollVarianceLine = CoreComponents['schemas']['PayrollVarianceLine'];
export type PayrollVarianceTotals = CoreComponents['schemas']['PayrollVarianceTotals'];
export type PayrollVarianceReview = CoreComponents['schemas']['PayrollVarianceReview'];
export type TimesheetRowState = TimesheetRow['state'];
export type TimesheetStatus = TimesheetDetail['status'];
export type TimesheetActionKind = TimesheetAction['action'];
export type TimesheetSubmissionBlocker = MyTimesheetSubmission['blockers'][number];
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

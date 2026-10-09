package com.divalhr.core.platform.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes. Clients translate these; the API never returns translated
 * text as an identifier.
 */
public enum ErrorCode {
  /** The request failed validation. */
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
  /** No valid bearer token was presented. */
  AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED),
  /** The caller is authenticated but not permitted to perform the action. */
  ACCESS_DENIED(HttpStatus.FORBIDDEN),
  /** The verified token does not carry a tenant context. */
  TENANT_CONTEXT_MISSING(HttpStatus.FORBIDDEN),
  /** The caller attempted to access another tenant's resource. */
  TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN),
  /** The resource does not exist or is not visible to the caller. */
  NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The idempotency key was already used with a different payload. */
  IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
  /** The country is not supported. */
  COUNTRY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** The locale is not supported. */
  LOCALE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** The time zone is not supported for the country. */
  TIMEZONE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** A currency is not supported for the country. */
  CURRENCY_NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
  /** The legal entity does not exist in the caller's tenant (missing and foreign look alike). */
  LEGAL_ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A legal entity with this code already exists in the tenant, in any letter case. */
  DUPLICATE_LEGAL_ENTITY_CODE(HttpStatus.CONFLICT),
  /** A site with this code already exists in the tenant, in any letter case. */
  DUPLICATE_SITE_CODE(HttpStatus.CONFLICT),
  /** The effective period ends before it starts. */
  EFFECTIVE_DATE_INVALID(HttpStatus.BAD_REQUEST),
  /** The site period is not contained in its legal entity's period. */
  SITE_PERIOD_OUTSIDE_LEGAL_ENTITY(HttpStatus.BAD_REQUEST),
  /** The pagination cursor is malformed, tampered with or bound to another query. */
  CURSOR_INVALID(HttpStatus.BAD_REQUEST),
  /** The site does not exist in the caller's tenant (missing and foreign look the same). */
  SITE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A department with this code already exists in the tenant, in any letter case. */
  DUPLICATE_DEPARTMENT_CODE(HttpStatus.CONFLICT),
  /** A cost center with this code already exists in the tenant, in any letter case. */
  DUPLICATE_COST_CENTER_CODE(HttpStatus.CONFLICT),
  /** The department's effective period is not contained in its site's. */
  DEPARTMENT_PERIOD_OUTSIDE_SITE(HttpStatus.BAD_REQUEST),
  /** The cost center's effective period is not contained in its site's. */
  COST_CENTER_PERIOD_OUTSIDE_SITE(HttpStatus.BAD_REQUEST),
  /** The referenced region does not exist in the caller's tenant (missing or foreign). */
  REGION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A region with this code already exists in the tenant, in any letter case. */
  DUPLICATE_REGION_CODE(HttpStatus.CONFLICT),
  /** The region's effective period is not contained in its legal entity's. */
  REGION_PERIOD_OUTSIDE_LEGAL_ENTITY(HttpStatus.BAD_REQUEST),
  /** The site's effective period is not contained in the region's. */
  SITE_PERIOD_OUTSIDE_REGION(HttpStatus.BAD_REQUEST),
  /** The region belongs to another legal entity than the site. */
  SITE_REGION_LEGAL_ENTITY_MISMATCH(HttpStatus.BAD_REQUEST),
  /** The site already has a different region; changing or clearing it is not supported. */
  SITE_REGION_ALREADY_ASSIGNED(HttpStatus.CONFLICT),
  /** A team names no parent: neither departmentId nor costCenterId was supplied. */
  TEAM_PARENT_REQUIRED(HttpStatus.BAD_REQUEST),
  /** A team names two parents: both departmentId and costCenterId were supplied. */
  TEAM_PARENT_AMBIGUOUS(HttpStatus.BAD_REQUEST),
  /** The referenced department does not exist in the caller's tenant (missing or foreign). */
  DEPARTMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The referenced cost center does not exist in the caller's tenant (missing or foreign). */
  COST_CENTER_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** A team with this code already exists in the tenant, in any letter case. */
  DUPLICATE_TEAM_CODE(HttpStatus.CONFLICT),
  /** The team's effective period is not contained in its department's. */
  TEAM_PERIOD_OUTSIDE_DEPARTMENT(HttpStatus.BAD_REQUEST),
  /** The team's effective period is not contained in its cost center's. */
  TEAM_PERIOD_OUTSIDE_COST_CENTER(HttpStatus.BAD_REQUEST),
  /** The invitation does not exist in the caller's tenant (missing and foreign are identical). */
  INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** An open invitation for the address already exists in the caller's tenant. */
  INVITATION_ALREADY_PENDING(HttpStatus.CONFLICT),
  /** The address already belongs to a member of the caller's tenant. */
  INVITATION_RECIPIENT_ALREADY_MEMBER(HttpStatus.CONFLICT),
  /** The invitation is accepted, expired or being accepted, so it cannot change. */
  INVITATION_NOT_PENDING(HttpStatus.CONFLICT),
  /** The tenant's invitation quota is used up. */
  INVITATION_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** The invitation was reissued too often or too recently. */
  INVITATION_RESEND_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** The invitation token is unknown, malformed, expired, revoked or already used. */
  INVITATION_INVALID(HttpStatus.NOT_FOUND),
  /** The invitation cannot be accepted with this address (no reason is disclosed). */
  INVITATION_CANNOT_BE_ACCEPTED(HttpStatus.CONFLICT),
  /** Another acceptance of the same invitation is in progress; retry. */
  INVITATION_ACCEPTANCE_IN_PROGRESS(HttpStatus.CONFLICT),
  /** The identity provider could not be reached; retry. */
  IDENTITY_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
  /** The organization is malformed, unknown or not active (platform operations, MVP-014). */
  ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /**
   * The organization already has a tenant administrator or an open tenant-admin invitation; which
   * one is not disclosed (MVP-014).
   */
  TENANT_ADMIN_BOOTSTRAP_UNAVAILABLE(HttpStatus.CONFLICT),
  /** Too many anonymous requests from one client. */
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
  /** A privileged operation requires multifactor authentication for this session (MVP-011). */
  MFA_REQUIRED(HttpStatus.FORBIDDEN),
  /** An unexpected server error occurred. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
  /** The employee import file is not an accepted CSV (params.reason, params.column) (MVP-020). */
  IMPORT_FILE_INVALID(HttpStatus.BAD_REQUEST),
  /** The employee import body exceeds the size cap (MVP-020). */
  IMPORT_FILE_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
  /** The employee import body was not received in time; nothing was stored (MVP-020). */
  IMPORT_UPLOAD_TIMEOUT(HttpStatus.REQUEST_TIMEOUT),
  /** The tenant already has the maximum number of open imports (MVP-020). */
  IMPORT_LIMIT_REACHED(HttpStatus.CONFLICT),
  /** The import does not exist in the caller's tenant (missing, foreign, malformed) (MVP-020). */
  EMPLOYEE_IMPORT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The import is no longer open (params.status) (MVP-020). */
  IMPORT_NOT_COMMITTABLE(HttpStatus.CONFLICT),
  /** The commit does not repeat the staged preview (MVP-020). */
  IMPORT_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** A row became invalid since the preview; nothing was created (MVP-020). */
  IMPORT_STALE(HttpStatus.CONFLICT),
  /** The import has no valid row (MVP-020). */
  IMPORT_NOTHING_TO_COMMIT(HttpStatus.CONFLICT),
  /** The import's database work timed out and was rolled back entirely (MVP-020). */
  IMPORT_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
  /** The employee does not exist in the caller's tenant (missing, foreign, malformed) (MVP-021). */
  EMPLOYEE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The change does not exist for this employee in the caller's tenant (MVP-021). */
  EMPLOYMENT_CHANGE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The employment changed since the caller read its version (MVP-021). */
  EMPLOYMENT_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** The commit does not repeat the current preview (MVP-021). */
  EMPLOYMENT_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** A row of the kind already starts on the effective date (params.field) (MVP-021). */
  EMPLOYMENT_CHANGE_DATE_TAKEN(HttpStatus.CONFLICT),
  /** Only an active scheduled business change can be cancelled (MVP-021). */
  EMPLOYMENT_CHANGE_NOT_CANCELLABLE(HttpStatus.CONFLICT),
  /** A later change depends on the change to cancel (MVP-021, M21-2). */
  EMPLOYMENT_CHANGE_HAS_DEPENDENTS(HttpStatus.CONFLICT),
  /** The change would not alter the timeline (params.field) (MVP-021). */
  EMPLOYMENT_CHANGE_NO_EFFECT(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The effective date is outside the employment (MVP-021). */
  EMPLOYMENT_DATE_OUTSIDE_EMPLOYMENT(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The date is earlier than the configured retroactive window allows (MVP-021). */
  RETROACTIVE_WINDOW_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The manager cannot be assigned (params.reason) (MVP-021). */
  MANAGER_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The placement cannot be assigned (params.field, params.reason) (MVP-021). */
  PLACEMENT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The change's database work timed out and was rolled back entirely (MVP-021). */
  EMPLOYMENT_CHANGE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
  /** The separation does not exist for that employee in the tenant (MVP-022). */
  SEPARATION_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The checklist task does not exist for that separation (MVP-022). */
  SEPARATION_TASK_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** No membership of the caller's tenant holds the address (MVP-022). */
  MEMBERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The employment already has a separation that is not cancelled (MVP-022). */
  SEPARATION_EXISTS(HttpStatus.CONFLICT),
  /** Future effects after the last day must be cancelled first (params.count) (MVP-022). */
  SEPARATION_FUTURE_CHANGES(HttpStatus.CONFLICT),
  /** The linked access is protected (params.reason SELF or ADMIN_ACCESS) (MVP-022). */
  SEPARATION_PROTECTED(HttpStatus.CONFLICT),
  /** The recomputed separation differs from the preview (MVP-022). */
  SEPARATION_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** The separation can no longer be cancelled (params.reason) (MVP-022). */
  SEPARATION_NOT_CANCELLABLE(HttpStatus.CONFLICT),
  /** The checklist task changed since it was read (MVP-022). */
  SEPARATION_TASK_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** The checklist of a cancelled separation is closed (MVP-022). */
  SEPARATION_TASK_CLOSED(HttpStatus.CONFLICT),
  /** The link cannot be created (params.reason) (MVP-022). */
  ACCESS_LINK_CONFLICT(HttpStatus.CONFLICT),
  /** A separation holds the link (MVP-022). */
  ACCESS_LINK_LOCKED(HttpStatus.CONFLICT),
  /** The link changed since it was read (MVP-022). */
  ACCESS_LINK_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** The sign-in removal does not need a manual retry (MVP-022). */
  ACCESS_REVOCATION_NOT_RETRYABLE(HttpStatus.CONFLICT),
  /** The last day is outside the permitted window (MVP-022). */
  SEPARATION_DATE_OUT_OF_RANGE(HttpStatus.UNPROCESSABLE_CONTENT),
  /** IMMEDIATELY requires a last day of today or earlier (MVP-022, A22-1). */
  SEPARATION_ACCESS_TIMING_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** An acknowledgement the preview required is missing (params.acknowledgement) (MVP-022). */
  SEPARATION_ACKNOWLEDGEMENT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Direct-report intervals need a plan (MVP-022). */
  SEPARATION_REPORT_PLAN_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** More direct-report intervals than one separation may rewrite (params.count) (MVP-022). */
  SEPARATION_TOO_MANY_INTERVALS(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Unknown, malformed or foreign template or version (MVP-030). */
  CONTRACT_TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** Unknown, malformed, foreign or another employee's contract (MVP-030). */
  CONTRACT_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The template version changed since it was read (MVP-030). */
  CONTRACT_TEMPLATE_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** Only a draft version is edited, approved or deleted (MVP-030). */
  CONTRACT_TEMPLATE_NOT_DRAFT(HttpStatus.CONFLICT),
  /** Only an approved version is issued or retired (MVP-030). */
  CONTRACT_TEMPLATE_NOT_APPROVED(HttpStatus.CONFLICT),
  /** The template code is already used in the organization, or a draft already exists (MVP-030). */
  CONTRACT_TEMPLATE_CODE_TAKEN(HttpStatus.CONFLICT),
  /** The result no longer matches the preview (MVP-030). */
  CONTRACT_PREVIEW_CHANGED(HttpStatus.CONFLICT),
  /** The contract changed since it was read (MVP-030). */
  CONTRACT_VERSION_CONFLICT(HttpStatus.CONFLICT),
  /** Another non-void contract of the employment overlaps the period (MVP-030, D11). */
  CONTRACT_PERIOD_OVERLAP(HttpStatus.CONFLICT),
  /** A separation is recorded for the employee (MVP-030). */
  CONTRACT_EMPLOYMENT_ENDED(HttpStatus.CONFLICT),
  /** Only an issued, unacknowledged contract is voided (MVP-030). */
  CONTRACT_NOT_VOIDABLE(HttpStatus.CONFLICT),
  /** A void contract is never acknowledged (MVP-030). */
  CONTRACT_NOT_ACKNOWLEDGEABLE(HttpStatus.CONFLICT),
  /** The displayed snapshot or statement does not match the server's (MVP-030, A30-4). */
  CONTRACT_ACKNOWLEDGEMENT_CHANGED(HttpStatus.CONFLICT),
  /** The template text breaks grammar v1 (params.reason, params.line) (MVP-030, A30-2). */
  CONTRACT_TEMPLATE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** A placeholder has no value for this employee (params.placeholder) (MVP-030, D9). */
  CONTRACT_VALUE_MISSING(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The contract dates break the rules (params.field) (MVP-030, D10). */
  CONTRACT_DATES_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
  /** Approval needs the TEXT_VERIFIED acknowledgement (MVP-030). */
  CONTRACT_TEMPLATE_ACKNOWLEDGEMENT_REQUIRED(HttpStatus.UNPROCESSABLE_CONTENT),
  /** The caller's employee membership has no active employee link (MVP-030, A30-1). */
  EMPLOYEE_LINK_REQUIRED(HttpStatus.FORBIDDEN),
  /** The contract operation took too long and nothing was written (MVP-030). */
  CONTRACT_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE),
  /** A leave policy with this code already exists in the organization (MVP-040A). */
  LEAVE_POLICY_CODE_EXISTS(HttpStatus.CONFLICT),
  /** The policy is unknown, ended or does not cover the whole interval (MVP-041A; no params). */
  LEAVE_POLICY_NOT_REQUESTABLE(HttpStatus.CONFLICT),
  /**
   * The employee is not eligible (params.reason EMPLOYMENT_PERIOD or MINIMUM_SERVICE; MVP-041A).
   */
  LEAVE_REQUEST_NOT_ELIGIBLE(HttpStatus.CONFLICT),
  /** Another pending request of the employee overlaps these dates (MVP-041A; no params). */
  LEAVE_REQUEST_OVERLAP(HttpStatus.CONFLICT),
  /**
   * No leave request the caller may see or decide: unknown, another tenant's, another route's or
   * not a report's on its first day are indistinguishable (MVP-041B; no params).
   */
  LEAVE_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND),
  /** The leave request already has its decision (MVP-041B; no params). */
  LEAVE_REQUEST_ALREADY_DECIDED(HttpStatus.CONFLICT);

  private final HttpStatus status;

  ErrorCode(HttpStatus status) {
    this.status = status;
  }

  /**
   * Returns the HTTP status associated with this code.
   *
   * @return the HTTP status
   */
  public HttpStatus status() {
    return status;
  }
}

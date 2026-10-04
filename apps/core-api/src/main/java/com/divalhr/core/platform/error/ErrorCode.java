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
  EMPLOYMENT_CHANGE_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE);

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

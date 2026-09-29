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
  /** An unexpected server error occurred. */
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

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

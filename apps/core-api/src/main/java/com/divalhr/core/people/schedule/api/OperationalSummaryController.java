package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.OperationalSummaryResponses.Summary;
import com.divalhr.core.people.schedule.application.OperationalSummaryService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-101 (ADR 0027): the grounded daily operational briefing, generated on demand from the same
 * audiences, scopes and authorization as the operational status (MVP-100). The manager's briefing
 * requires the {@code employee} role and the caller's current active employee link; the
 * organization briefing requires {@code tenant-admin} with exact MFA. Tenant, actor, scope and data
 * are never request fields. Every response is {@code private, no-store}.
 */
@RestController
public class OperationalSummaryController {

  private final OperationalSummaryService summaries;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param summaries the briefing service
   * @param tenants verified tenant resolver
   */
  public OperationalSummaryController(
      OperationalSummaryService summaries, TenantContextResolver tenants) {
    this.summaries = summaries;
    this.tenants = tenants;
  }

  /**
   * Generates today's briefing of the caller's team.
   *
   * @param body the requested language
   * @param authentication verified employee
   * @param request current request
   * @return the briefing, after its audit committed
   */
  @Operation(operationId = "generateTeamOperationalSummary")
  @PostMapping(
      path = "/api/v1/me/team-operations/status-summary",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = OperationalSummaryService.TEAM_OPERATION)
  @SubjectRateLimited(bucket = OperationalSummaryService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = OperationalSummaryService.TENANT_BUCKET)
  public ResponseEntity<Summary> team(
      @RequestBody GenerateOperationalSummaryRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Object language = language(body);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, AttendanceExceptionController.CACHE_CONTROL)
        .body(Summary.of(summaries.team(caller(authentication, request), language)));
  }

  /**
   * Generates today's briefing of the organization, optionally of one site and/or one legal entity.
   *
   * @param siteId optional site
   * @param legalEntityId optional legal entity
   * @param body the requested language
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the briefing, after its audit committed
   */
  @Operation(operationId = "generateOperationalSummary")
  @PostMapping(
      path = "/api/v1/operations/status-summary",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = OperationalSummaryService.ADMIN_OPERATION)
  @SubjectRateLimited(bucket = OperationalSummaryService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = OperationalSummaryService.TENANT_BUCKET)
  public ResponseEntity<Summary> tenant(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "legalEntityId", required = false) String legalEntityId,
      @RequestBody GenerateOperationalSummaryRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Object language = language(body);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, AttendanceExceptionController.CACHE_CONTROL)
        .body(
            Summary.of(
                summaries.tenant(
                    caller(authentication, request), language, siteId, legalEntityId)));
  }

  /** The raw language, after the body itself is checked (missing, unknown property). */
  private static Object language(GenerateOperationalSummaryRequest body) {
    FieldErrors errors = new FieldErrors();
    if (body == null) {
      errors.add("body", Constraint.REQUIRED);
      errors.throwIfAny();
      throw new IllegalStateException("unreachable");
    }
    if (!body.unknownProperties().isEmpty()) {
      errors.add("body", Constraint.UNKNOWN_PROPERTY);
    }
    if (body.getLanguage() == null) {
      errors.add("language", Constraint.REQUIRED);
    }
    errors.throwIfAny();
    return body.getLanguage();
  }

  private PeopleCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new PeopleCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

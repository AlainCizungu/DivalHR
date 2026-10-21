package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.OperationalStatusResponses.Status;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
import com.divalhr.core.people.schedule.application.OperationalStatusService;
import com.divalhr.core.people.schedule.application.WorkScheduleService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-100 (ADR 0026): the read-only operational status of today. The manager's view requires the
 * {@code employee} role and the caller's current active employee link; the organization view
 * requires {@code tenant-admin} with exact MFA. Tenant and actor are never request fields. Every
 * response is {@code private, no-store}.
 */
@RestController
public class OperationalStatusController {

  private final OperationalStatusService operations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param operations the operational status
   * @param tenants verified tenant resolver
   */
  public OperationalStatusController(
      OperationalStatusService operations, TenantContextResolver tenants) {
    this.operations = operations;
    this.tenants = tenants;
  }

  /**
   * Today's operational status of the caller's team.
   *
   * @param authentication verified employee
   * @param request current request
   * @return the status, after its disclosure audit committed
   */
  @Operation(operationId = "getTeamOperationalStatus")
  @GetMapping("/api/v1/me/team-operations/status")
  @EmployeeSelfOperation(operation = OperationalStatusService.TEAM_VIEW)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Status> team(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, AttendanceExceptionController.CACHE_CONTROL)
        .body(Status.of(operations.team(caller(authentication, request))));
  }

  /**
   * Today's operational status of the organization, optionally of one site and/or one legal entity.
   *
   * @param siteId optional site
   * @param legalEntityId optional legal entity
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the status, after its disclosure audit committed
   */
  @Operation(operationId = "getOperationalStatus")
  @GetMapping("/api/v1/operations/status")
  @TenantAdminOperation(operation = OperationalStatusService.ADMIN_VIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Status> tenant(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "legalEntityId", required = false) String legalEntityId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, AttendanceExceptionController.CACHE_CONTROL)
        .body(Status.of(operations.tenant(caller(authentication, request), siteId, legalEntityId)));
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

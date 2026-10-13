package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.AttendanceExceptionResponses.Page;
import com.divalhr.core.people.schedule.api.AttendanceExceptionResponses.TeamPage;
import com.divalhr.core.people.schedule.application.AttendanceExceptionService;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
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
 * MVP-060C: the read-only review of factual attendance exceptions. The employee's own and the
 * manager's views require the {@code employee} role and the caller's current active employee link;
 * the organization view requires {@code tenant-admin} with exact MFA. Tenant and actor are never
 * request fields. Every response is {@code private, no-store}.
 */
@RestController
public class AttendanceExceptionController {

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final AttendanceExceptionService exceptions;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param exceptions the exception review
   * @param tenants verified tenant resolver
   */
  public AttendanceExceptionController(
      AttendanceExceptionService exceptions, TenantContextResolver tenants) {
    this.exceptions = exceptions;
    this.tenants = tenants;
  }

  /**
   * The caller's own factual attendance exceptions.
   *
   * @param type optional type
   * @param from optional first site-local date
   * @param to optional last site-local date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyAttendanceExceptions")
  @GetMapping("/api/v1/me/attendance/exceptions")
  @EmployeeSelfOperation(operation = AttendanceExceptionService.SELF_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> mine(
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(exceptions.self(caller(authentication, request), type, from, to, cursor, limit));
  }

  /**
   * The caller's direct reports' factual attendance exceptions.
   *
   * @param type optional type
   * @param from optional first site-local date
   * @param to optional last site-local date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listTeamAttendanceExceptions")
  @GetMapping("/api/v1/me/team-attendance/exceptions")
  @EmployeeSelfOperation(operation = AttendanceExceptionService.TEAM_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<TeamPage> team(
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(exceptions.team(caller(authentication, request), type, from, to, cursor, limit));
  }

  /**
   * The organization's factual attendance exceptions, optionally of one site.
   *
   * @param siteId optional site
   * @param type optional type
   * @param from optional first site-local date
   * @param to optional last site-local date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listAttendanceExceptions")
  @GetMapping("/api/v1/attendance/exceptions")
  @TenantAdminOperation(operation = AttendanceExceptionService.ADMIN_LIST)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<TeamPage> tenant(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "type", required = false) String type,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            exceptions.tenant(
                caller(authentication, request), siteId, type, from, to, cursor, limit));
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

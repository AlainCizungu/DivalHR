package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.AttendanceResponses.Page;
import com.divalhr.core.people.schedule.api.AttendanceResponses.Receipt;
import com.divalhr.core.people.schedule.application.AttendanceService;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Online attendance for employees (MVP-060A, Issue #113): the caller's own check-in, check-out and
 * history. Every operation requires the {@code employee} role and the caller's current active
 * employee link; every response is {@code private, no-store}.
 */
@RestController
public class AttendanceController {

  /** The employee's attendance (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/attendance";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final AttendanceService attendance;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param attendance attendance service
   * @param tenants verified tenant resolver
   */
  public AttendanceController(AttendanceService attendance, TenantContextResolver tenants) {
    this.attendance = attendance;
    this.tenants = tenants;
  }

  /**
   * The caller's own records, newest shift first.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyAttendance")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = AttendanceService.SELF_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> history(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(attendance.history(caller(authentication, request), cursor, limit));
  }

  /**
   * Checks the caller in on one of their shifts.
   *
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body the slot
   * @param authentication verified employee
   * @param request current request
   * @return 201 with the receipt, or the replayed original
   */
  @Operation(operationId = "checkInMyAttendance")
  @PostMapping(path = PATH + "/check-in", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @EmployeeSelfOperation(operation = AttendanceService.CHECK_IN)
  @SubjectRateLimited(bucket = AttendanceService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = AttendanceService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> checkIn(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CheckInAttendanceRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        attendance.checkIn(caller(authentication, request), idempotencyKey, body);
    return respond(ResponseEntity.status(HttpStatus.CREATED), result);
  }

  /**
   * Checks the caller out of one of their open records.
   *
   * @param attendanceRecordId path value
   * @param idempotencyKey {@code Idempotency-Key}
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the receipt, or the replayed original
   */
  @Operation(operationId = "checkOutMyAttendance")
  @PostMapping(PATH + "/{attendanceRecordId}/check-out")
  @EmployeeSelfOperation(operation = AttendanceService.CHECK_OUT)
  @SubjectRateLimited(bucket = AttendanceService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = AttendanceService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> checkOut(
      @PathVariable("attendanceRecordId") String attendanceRecordId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        attendance.checkOut(caller(authentication, request), attendanceRecordId, idempotencyKey);
    return respond(ResponseEntity.ok(), result);
  }

  private static ResponseEntity<Receipt> respond(
      ResponseEntity.BodyBuilder response, IdempotentOperation.Result<Receipt> result) {
    response.header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
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

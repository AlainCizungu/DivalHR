package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.History;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.Preview;
import com.divalhr.core.people.schedule.api.AttendanceCorrectionResponses.Receipt;
import com.divalhr.core.people.schedule.application.AttendanceCorrectionService;
import com.divalhr.core.people.schedule.application.WorkScheduleService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-062 (Issue #126): attendance corrections for tenant administrators. Every operation requires
 * the exact tenant-administrator role and privileged MFA; tenant and actor are never request
 * fields; every response is {@code private, no-store}.
 */
@RestController
public class AttendanceCorrectionController {

  /** A record's corrections (full public path; server base {@code /api/v1}). */
  public static final String RECORD_PATH = "/api/v1/attendance/records/{attendanceRecordId}";

  /** The missing-record corrections. */
  public static final String MISSING_PATH = "/api/v1/attendance/corrections/missing-record";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final AttendanceCorrectionService corrections;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param corrections the correction service
   * @param tenants verified tenant resolver
   */
  public AttendanceCorrectionController(
      AttendanceCorrectionService corrections, TenantContextResolver tenants) {
    this.corrections = corrections;
    this.tenants = tenants;
  }

  /**
   * Previews a REPLACE_TIMES correction of an existing record; writes nothing but its disclosure
   * audit record.
   *
   * @param attendanceRecordId path value
   * @param body the proposal
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewAttendanceRecordCorrection")
  @PostMapping(
      path = RECORD_PATH + "/correction-preview",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = AttendanceCorrectionService.PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<Preview> previewReplace(
      @PathVariable("attendanceRecordId") String attendanceRecordId,
      @RequestBody AttendanceCorrectionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            corrections.previewReplace(caller(authentication, request), attendanceRecordId, body));
  }

  /**
   * Commits a previewed REPLACE_TIMES correction.
   *
   * @param attendanceRecordId path value
   * @param idempotencyKey required idempotency key
   * @param body the proposal, target version, preview digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "correctAttendanceRecord")
  @PostMapping(path = RECORD_PATH + "/corrections", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = AttendanceCorrectionService.CREATE)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> commitReplace(
      @PathVariable("attendanceRecordId") String attendanceRecordId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitAttendanceCorrectionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return created(
        corrections.commitReplace(
            caller(authentication, request), attendanceRecordId, idempotencyKey, body));
  }

  /**
   * A record's current effective values, original events and corrections, newest first.
   *
   * @param attendanceRecordId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the history
   */
  @Operation(operationId = "listAttendanceRecordCorrections")
  @GetMapping(RECORD_PATH + "/corrections")
  @TenantAdminOperation(operation = AttendanceCorrectionService.HISTORY)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<History> history(
      @PathVariable("attendanceRecordId") String attendanceRecordId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(corrections.history(caller(authentication, request), attendanceRecordId));
  }

  /**
   * Previews a CREATE_MISSING_RECORD correction; writes nothing but its disclosure audit record.
   *
   * @param body the proposal
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewMissingAttendanceCorrection")
  @PostMapping(path = MISSING_PATH + "-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = AttendanceCorrectionService.PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<Preview> previewMissing(
      @RequestBody MissingAttendanceCorrectionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(corrections.previewMissing(caller(authentication, request), body));
  }

  /**
   * Commits a previewed CREATE_MISSING_RECORD correction.
   *
   * @param idempotencyKey required idempotency key
   * @param body the proposal, target version, preview digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createMissingAttendanceRecord")
  @PostMapping(path = MISSING_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = AttendanceCorrectionService.CREATE)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> commitMissing(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitMissingAttendanceCorrectionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return created(
        corrections.commitMissing(caller(authentication, request), idempotencyKey, body));
  }

  private static ResponseEntity<Receipt> created(IdempotentOperation.Result<Receipt> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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

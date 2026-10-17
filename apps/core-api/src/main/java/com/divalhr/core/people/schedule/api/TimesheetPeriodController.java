package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.History;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.LockPreview;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.Result;
import com.divalhr.core.people.schedule.api.TimesheetPeriodResponses.UnlockPreview;
import com.divalhr.core.people.schedule.application.TimesheetPeriodService;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-071 period locks (Issue #139, ADR 0022): tenant administrators with exact MFA preview and
 * commit the lock and the unlock of one site's generated week and read its immutable history. Every
 * response is {@code private, no-store}.
 */
@RestController
public class TimesheetPeriodController {

  /** The administrators' paths (full public path; server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/timesheet-periods";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final TimesheetPeriodService periods;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param periods the period lock service
   * @param tenants verified tenant resolver
   */
  public TimesheetPeriodController(TimesheetPeriodService periods, TenantContextResolver tenants) {
    this.periods = periods;
    this.tenants = tenants;
  }

  /**
   * Previews the lock of a legal entity's site and generated week; writes nothing but its
   * disclosure audit record.
   *
   * @param body legal entity, site and week
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewTimesheetPeriodLock")
  @PostMapping(path = PATH + "/lock-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetPeriodService.LOCK_PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<LockPreview> previewLock(
      @RequestBody TimesheetPeriodScopeRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(periods.previewLock(caller(authentication, request), body));
  }

  /**
   * Locks a reviewed week.
   *
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body scope, versions, preview digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the result, or the replayed original
   */
  @Operation(operationId = "lockTimesheetPeriod")
  @PostMapping(path = PATH + "/locks", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetPeriodService.LOCK)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Result> lock(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody LockTimesheetPeriodRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(periods.lock(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * Previews the unlock of a locked week with its reason; writes nothing but its disclosure audit
   * record.
   *
   * @param body legal entity, site, week, reason code and explanation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewTimesheetPeriodUnlock")
  @PostMapping(path = PATH + "/unlock-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetPeriodService.UNLOCK_PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<UnlockPreview> previewUnlock(
      @RequestBody TimesheetPeriodUnlockPreviewRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(periods.previewUnlock(caller(authentication, request), body));
  }

  /**
   * Unlocks a locked week after its reviewed preview.
   *
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body scope, reason, explanation, current lock, preview digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the result, or the replayed original
   */
  @Operation(operationId = "unlockTimesheetPeriod")
  @PostMapping(path = PATH + "/unlocks", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetPeriodService.UNLOCK)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Result> unlock(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody UnlockTimesheetPeriodRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(periods.unlock(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * A generated week's lock state and immutable history, newest first.
   *
   * @param generationId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the history
   */
  @Operation(operationId = "getTimesheetPeriodHistory")
  @GetMapping(PATH + "/{generationId}")
  @TenantAdminOperation(operation = TimesheetPeriodService.HISTORY)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<History> history(
      @PathVariable("generationId") String generationId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(periods.history(caller(authentication, request), generationId));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static <T> ResponseEntity<T> replayable(IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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

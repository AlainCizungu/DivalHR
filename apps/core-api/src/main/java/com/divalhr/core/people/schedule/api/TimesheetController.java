package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.TimesheetResponses.Detail;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationPreview;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationResult;
import com.divalhr.core.people.schedule.api.TimesheetResponses.GenerationView;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyDetail;
import com.divalhr.core.people.schedule.api.TimesheetResponses.MyPage;
import com.divalhr.core.people.schedule.api.TimesheetResponses.RefreshPreview;
import com.divalhr.core.people.schedule.api.TimesheetResponses.RefreshResult;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
import com.divalhr.core.people.schedule.application.TimesheetService;
import com.divalhr.core.people.schedule.application.WorkScheduleService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-070A weekly draft timesheets (Issue #128, ADR 0018): tenant administrators with exact MFA
 * preview, generate, read and refresh one site's week; employees read only their own drafts. Every
 * response is {@code private, no-store}.
 */
@RestController
public class TimesheetController {

  /** The administrators' paths (full public path; server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/timesheets";

  /** The employees' own paths. */
  public static final String SELF_PATH = "/api/v1/me/timesheets";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final TimesheetService timesheets;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param timesheets the timesheet service
   * @param tenants verified tenant resolver
   */
  public TimesheetController(TimesheetService timesheets, TenantContextResolver tenants) {
    this.timesheets = timesheets;
    this.tenants = tenants;
  }

  /**
   * Previews the generation of one site's week; writes nothing but its disclosure audit record.
   *
   * @param body site and week
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewTimesheetGeneration")
  @PostMapping(path = PATH + "/generation-preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetService.GENERATION_PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<GenerationPreview> previewGeneration(
      @RequestBody TimesheetPeriodRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(timesheets.previewGeneration(caller(authentication, request), body));
  }

  /**
   * Generates one site's week, or returns its existing generation.
   *
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body site, week and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the result, or the replayed original
   */
  @Operation(operationId = "generateTimesheets")
  @PostMapping(path = PATH + "/generations", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetService.GENERATE)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<GenerationResult> generate(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody GenerateTimesheetsRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(timesheets.generate(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * A generated week with its drafts.
   *
   * @param generationId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the generation
   */
  @Operation(operationId = "getTimesheetGeneration")
  @GetMapping(PATH + "/generations/{generationId}")
  @TenantAdminOperation(operation = TimesheetService.GENERATION_READ)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<GenerationView> generation(
      @PathVariable("generationId") String generationId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(timesheets.generation(caller(authentication, request), generationId));
  }

  /**
   * Previews a refresh of a generated week; writes nothing but its disclosure audit record.
   *
   * @param generationId path value
   * @param body the generation's site and week
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewTimesheetRefresh")
  @PostMapping(
      path = PATH + "/generations/{generationId}/refresh-preview",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetService.REFRESH_PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<RefreshPreview> previewRefresh(
      @PathVariable("generationId") String generationId,
      @RequestBody TimesheetPeriodRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(timesheets.previewRefresh(caller(authentication, request), generationId, body));
  }

  /**
   * Commits a previewed refresh.
   *
   * @param generationId path value
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body site, week, version, preview digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the result, or the replayed original
   */
  @Operation(operationId = "refreshTimesheets")
  @PostMapping(
      path = PATH + "/generations/{generationId}/refreshes",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = TimesheetService.REFRESH)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RefreshResult> refresh(
      @PathVariable("generationId") String generationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RefreshTimesheetsRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        timesheets.refresh(caller(authentication, request), generationId, idempotencyKey, body));
  }

  /**
   * One draft with its current rows and every revision.
   *
   * @param timesheetId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the draft
   */
  @Operation(operationId = "getTimesheet")
  @GetMapping(PATH + "/{timesheetId}")
  @TenantAdminOperation(operation = TimesheetService.READ)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Detail> timesheet(
      @PathVariable("timesheetId") String timesheetId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(timesheets.timesheet(caller(authentication, request), timesheetId));
  }

  /**
   * One page of the caller's own drafts, newest week first.
   *
   * @param cursor opaque cursor of the next page
   * @param limit page size (1 to 100, default 20)
   * @param authentication verified employee
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listMyTimesheets")
  @GetMapping(SELF_PATH)
  @EmployeeSelfOperation(operation = TimesheetService.SELF_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<MyPage> mine(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(timesheets.mine(caller(authentication, request), cursor, limit));
  }

  /**
   * One of the caller's own drafts with its current rows.
   *
   * @param timesheetId path value
   * @param authentication verified employee
   * @param request current request
   * @return the draft
   */
  @Operation(operationId = "getMyTimesheet")
  @GetMapping(SELF_PATH + "/{timesheetId}")
  @EmployeeSelfOperation(operation = TimesheetService.SELF_READ)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<MyDetail> myTimesheet(
      @PathVariable("timesheetId") String timesheetId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(timesheets.myTimesheet(caller(authentication, request), timesheetId));
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

package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Detail;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Page;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Preview;
import com.divalhr.core.people.schedule.api.WorkScheduleResponses.Receipt;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site work schedules for tenant administrators (MVP-050A, Issue #99): preview, publish, list and
 * read. Every operation requires the exact tenant-administrator role and privileged MFA; every
 * response is {@code private, no-store}.
 */
@RestController
@RequestMapping(WorkScheduleController.PATH)
public class WorkScheduleController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/work-schedules";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final WorkScheduleService schedules;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param schedules work schedule service
   * @param tenants verified tenant resolver
   */
  public WorkScheduleController(WorkScheduleService schedules, TenantContextResolver tenants) {
    this.schedules = schedules;
    this.tenants = tenants;
  }

  /**
   * Evaluates a proposed schedule against the current facts; writes nothing but its disclosure
   * audit record.
   *
   * @param body the proposal
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewWorkSchedule")
  @PostMapping(path = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WorkScheduleService.PREVIEW)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<Preview> preview(
      @RequestBody WorkScheduleRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(schedules.preview(caller(authentication, request), body));
  }

  /**
   * Publishes a previewed schedule.
   *
   * @param idempotencyKey required idempotency key
   * @param body the proposal, its preview digest and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "publishWorkSchedule")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = WorkScheduleService.PUBLISH)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> publish(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody PublishWorkScheduleRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        schedules.publish(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The tenant's published schedules, latest period first.
   *
   * @param siteId optional site filter
   * @param from optional: schedules ending on or after this date
   * @param to optional: schedules starting on or before this date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listWorkSchedules")
  @GetMapping
  @TenantAdminOperation(operation = WorkScheduleService.LIST)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(schedules.list(caller(authentication, request), siteId, from, to, cursor, limit));
  }

  /**
   * One published schedule with every slot.
   *
   * @param scheduleId the schedule
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the schedule, after its disclosure audit committed
   */
  @Operation(operationId = "getWorkSchedule")
  @GetMapping("/{scheduleId}")
  @TenantAdminOperation(operation = WorkScheduleService.GET)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Detail> get(
      @PathVariable("scheduleId") String scheduleId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(schedules.get(caller(authentication, request), scheduleId));
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

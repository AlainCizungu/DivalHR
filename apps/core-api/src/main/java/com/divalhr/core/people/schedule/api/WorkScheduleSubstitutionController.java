package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.ApprovalPage;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.CandidatePage;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.DecisionReceipt;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.MyPage;
import com.divalhr.core.people.schedule.api.WorkScheduleSubstitutionResponses.MyRequest;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
import com.divalhr.core.people.schedule.application.WorkScheduleSubstitutionService;
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
 * Shift substitution for employees (MVP-051, Issue #105): the employee's own requests and history,
 * and the effective manager's inbox, candidate search and decision. Every operation requires the
 * {@code employee} role and the caller's current active employee link; every response is {@code
 * private, no-store}.
 */
@RestController
public class WorkScheduleSubstitutionController {

  /** The employee's requests (server base {@code /api/v1}). */
  public static final String SELF_PATH = "/api/v1/me/work-schedule-substitutions";

  /** The manager's inbox (server base {@code /api/v1}). */
  public static final String MANAGER_PATH = "/api/v1/me/work-schedule-substitution-approvals";

  /** Every response holds Restricted HR/Operations data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final WorkScheduleSubstitutionService substitutions;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param substitutions substitution service
   * @param tenants verified tenant resolver
   */
  public WorkScheduleSubstitutionController(
      WorkScheduleSubstitutionService substitutions, TenantContextResolver tenants) {
    this.substitutions = substitutions;
    this.tenants = tenants;
  }

  /**
   * The caller's own requests, newest first.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyWorkScheduleSubstitutions")
  @GetMapping(SELF_PATH)
  @EmployeeSelfOperation(operation = WorkScheduleSubstitutionService.SELF_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<MyPage> history(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(substitutions.history(caller(authentication, request), cursor, limit));
  }

  /**
   * Requests a substitute for one of the caller's own shifts.
   *
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body the request
   * @param authentication verified employee
   * @param request current request
   * @return 201 with the request, or the replayed original
   */
  @Operation(operationId = "requestWorkScheduleSubstitution")
  @PostMapping(path = SELF_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @EmployeeSelfOperation(operation = WorkScheduleSubstitutionService.REQUEST)
  @SubjectRateLimited(bucket = WorkScheduleSubstitutionService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleSubstitutionService.TENANT_WRITE_BUCKET)
  public ResponseEntity<MyRequest> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateWorkScheduleSubstitutionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<MyRequest> result =
        substitutions.request(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The pending requests routed to the caller as manager, oldest first.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyWorkScheduleSubstitutionApprovals")
  @GetMapping(MANAGER_PATH)
  @EmployeeSelfOperation(operation = WorkScheduleSubstitutionService.MANAGER_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ApprovalPage> inbox(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(substitutions.inbox(caller(authentication, request), cursor, limit));
  }

  /**
   * Eligible substitutes for a request routed to the caller.
   *
   * @param requestId path value
   * @param query the search query
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "searchWorkScheduleSubstitutionCandidates")
  @GetMapping(MANAGER_PATH + "/{requestId}/candidates")
  @EmployeeSelfOperation(operation = WorkScheduleSubstitutionService.CANDIDATE_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<CandidatePage> candidates(
      @PathVariable("requestId") String requestId,
      @RequestParam(name = "query", required = false) String query,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            substitutions.candidates(
                caller(authentication, request), requestId, query, cursor, limit));
  }

  /**
   * Approves or rejects a request routed to the caller.
   *
   * @param requestId path value
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body the decision
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the minimal receipt, or the replayed original
   */
  @Operation(operationId = "decideWorkScheduleSubstitution")
  @PostMapping(
      path = MANAGER_PATH + "/{requestId}/decision",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = WorkScheduleSubstitutionService.DECIDE)
  @SubjectRateLimited(bucket = WorkScheduleSubstitutionService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleSubstitutionService.TENANT_WRITE_BUCKET)
  public ResponseEntity<DecisionReceipt> decide(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody DecideWorkScheduleSubstitutionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<DecisionReceipt> result =
        substitutions.decide(caller(authentication, request), requestId, idempotencyKey, body);
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

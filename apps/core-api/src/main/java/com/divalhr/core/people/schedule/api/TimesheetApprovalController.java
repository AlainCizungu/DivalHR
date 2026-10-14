package com.divalhr.core.people.schedule.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ActionReceipt;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ApprovalDetail;
import com.divalhr.core.people.schedule.api.TimesheetResponses.ApprovalPage;
import com.divalhr.core.people.schedule.application.MyWorkScheduleService;
import com.divalhr.core.people.schedule.application.TimesheetApprovalService;
import com.divalhr.core.people.schedule.application.WorkScheduleService;
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
 * MVP-070B/C weekly time submission and approval (Issue #130, ADR 0019): an employee submits one of
 * their own clean drafts; the manager the timesheet routes to now lists, reads, approves or returns
 * it. Every operation is employee self-service (an active employee-access link); a tenant
 * administrator role grants nothing here. Every response is {@code private, no-store}.
 */
@RestController
public class TimesheetApprovalController {

  /** The routed manager's paths (full public path; server base {@code /api/v1}). */
  public static final String APPROVALS_PATH = "/api/v1/me/time-approvals";

  private final TimesheetApprovalService approvals;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param approvals the approval service
   * @param tenants verified tenant resolver
   */
  public TimesheetApprovalController(
      TimesheetApprovalService approvals, TenantContextResolver tenants) {
    this.approvals = approvals;
    this.tenants = tenants;
  }

  /**
   * Submits one of the caller's own clean drafts to the manager it routes to.
   *
   * @param timesheetId path value
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body the reviewed revision and generation version, and the confirmation
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the receipt, or the replayed original
   */
  @Operation(operationId = "submitMyTimesheet")
  @PostMapping(
      path = TimesheetController.SELF_PATH + "/{timesheetId}/submissions",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = TimesheetApprovalService.SUBMIT)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ActionReceipt> submit(
      @PathVariable("timesheetId") String timesheetId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody SubmitTimesheetRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        approvals.submit(caller(authentication, request), timesheetId, idempotencyKey, body));
  }

  /**
   * One page of the submitted timesheets the caller approves now.
   *
   * @param siteId optional site filter
   * @param weekStart optional site-local Monday filter
   * @param cursor opaque cursor of the next page
   * @param limit page size (1 to 100, default 20)
   * @param authentication verified employee acting as a manager
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listMyTimeApprovals")
  @GetMapping(APPROVALS_PATH)
  @EmployeeSelfOperation(operation = TimesheetApprovalService.APPROVAL_LIST)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ApprovalPage> queue(
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "weekStart", required = false) String weekStart,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(approvals.queue(caller(authentication, request), siteId, weekStart, cursor, limit));
  }

  /**
   * One submitted timesheet the caller approves now.
   *
   * @param timesheetId path value
   * @param authentication verified employee acting as a manager
   * @param request current request
   * @return the timesheet
   */
  @Operation(operationId = "getMyTimeApproval")
  @GetMapping(APPROVALS_PATH + "/{timesheetId}")
  @EmployeeSelfOperation(operation = TimesheetApprovalService.APPROVAL_READ)
  @SubjectRateLimited(bucket = MyWorkScheduleService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ApprovalDetail> approval(
      @PathVariable("timesheetId") String timesheetId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(approvals.approval(caller(authentication, request), timesheetId));
  }

  /**
   * Approves exactly the reviewed submission.
   *
   * @param timesheetId path value
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body the reviewed evidence and the confirmation
   * @param authentication verified employee acting as a manager
   * @param request current request
   * @return 200 with the receipt, or the replayed original
   */
  @Operation(operationId = "approveTimesheet")
  @PostMapping(
      path = APPROVALS_PATH + "/{timesheetId}/approval",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = TimesheetApprovalService.APPROVE)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ActionReceipt> approve(
      @PathVariable("timesheetId") String timesheetId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ApproveTimesheetRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        approvals.approve(caller(authentication, request), timesheetId, idempotencyKey, body));
  }

  /**
   * Returns exactly the reviewed submission with a reason.
   *
   * @param timesheetId path value
   * @param idempotencyKey {@code Idempotency-Key}
   * @param body the reviewed evidence, the reason and the confirmation
   * @param authentication verified employee acting as a manager
   * @param request current request
   * @return 200 with the receipt, or the replayed original
   */
  @Operation(operationId = "returnTimesheet")
  @PostMapping(
      path = APPROVALS_PATH + "/{timesheetId}/return",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = TimesheetApprovalService.RETURN)
  @SubjectRateLimited(bucket = WorkScheduleService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WorkScheduleService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ActionReceipt> giveBack(
      @PathVariable("timesheetId") String timesheetId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ReturnTimesheetRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return replayable(
        approvals.giveBack(caller(authentication, request), timesheetId, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, TimesheetController.CACHE_CONTROL)
        .body(body);
  }

  private static <T> ResponseEntity<T> replayable(IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, TimesheetController.CACHE_CONTROL);
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

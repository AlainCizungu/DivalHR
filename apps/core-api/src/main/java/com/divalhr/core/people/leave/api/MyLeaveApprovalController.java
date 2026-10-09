package com.divalhr.core.people.leave.api;

import com.divalhr.core.people.application.PeopleCaller;
import com.divalhr.core.people.leave.api.LeaveApprovalResponses.Page;
import com.divalhr.core.people.leave.api.LeaveApprovalResponses.Receipt;
import com.divalhr.core.people.leave.application.LeaveApprovalService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee-manager leave approval endpoints (MVP-041B, Issue #89). Every handler is an employee
 * self-service operation ({@code EmployeeSelfOperation}): subject, role {@code employee}, tenant
 * and an active membership are checked, then the request limits, before any argument or body is
 * read. The manager is the caller's own linked employee; only requests reporting to them on their
 * first day are listed or decided, re-evaluated on every call and replay. Responses are {@code
 * private, no-store}.
 */
@RestController
@RequestMapping(MyLeaveApprovalController.PATH)
public class MyLeaveApprovalController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/leave-approvals";

  /** Every response holds Restricted HR data. */
  static final String CACHE_CONTROL = "private, no-store";

  private final LeaveApprovalService approvals;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param approvals leave approval service
   * @param tenants verified tenant resolver
   */
  public MyLeaveApprovalController(LeaveApprovalService approvals, TenantContextResolver tenants) {
    this.approvals = approvals;
    this.tenants = tenants;
  }

  /**
   * The pending requests that report to the caller's linked employee, newest first.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyLeaveApprovals")
  @GetMapping
  @EmployeeSelfOperation(operation = LeaveApprovalService.MANAGER_LIST)
  @SubjectRateLimited(bucket = LeaveApprovalService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(approvals.managerList(caller(authentication, request), cursor, limit));
  }

  /**
   * Approves or rejects a pending request of one of the caller's reports.
   *
   * @param requestId the request
   * @param idempotencyKey required idempotency key
   * @param body decision, reason locale and reason
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "decideManagedLeaveRequest")
  @PostMapping(path = "/{requestId}/decision", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = LeaveApprovalService.MANAGER_DECIDE)
  @SubjectRateLimited(bucket = LeaveApprovalService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = LeaveApprovalService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> decide(
      @PathVariable("requestId") String requestId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody DecideLeaveRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        approvals.managerDecide(caller(authentication, request), requestId, idempotencyKey, body);
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

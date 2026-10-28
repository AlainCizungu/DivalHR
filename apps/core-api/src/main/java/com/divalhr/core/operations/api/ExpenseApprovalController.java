package com.divalhr.core.operations.api;

import com.divalhr.core.operations.api.ExpenseResponses.CommandReceipt;
import com.divalhr.core.operations.api.ExpenseResponses.DetailView;
import com.divalhr.core.operations.api.ExpenseResponses.Page;
import com.divalhr.core.operations.application.ExpenseApprovalService;
import com.divalhr.core.operations.application.ExpenseReceiptDownload;
import com.divalhr.core.operations.application.OperationsCaller;
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
 * The effective manager's expense endpoints (MVP-081, Issue #169). Every handler is an employee
 * self-service operation ({@code EmployeeSelfOperation}); a tenant administrator role grants
 * nothing here. The route is resolved again on every call: an expense that is unknown, foreign, not
 * submitted or no longer routed to the caller is the same safe {@code 404 EXPENSE_NOT_FOUND}.
 * Approval is not reimbursement or payment. Responses are {@code private, no-store}.
 */
@RestController
@RequestMapping(ExpenseApprovalController.PATH)
public class ExpenseApprovalController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/expense-approvals";

  private final ExpenseApprovalService approvals;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param approvals approval service
   * @param tenants verified tenant resolver
   */
  public ExpenseApprovalController(
      ExpenseApprovalService approvals, TenantContextResolver tenants) {
    this.approvals = approvals;
    this.tenants = tenants;
  }

  /**
   * The submitted expenses that currently route to the caller, oldest submission first.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyExpenseApprovals")
  @GetMapping
  @EmployeeSelfOperation(operation = ExpenseApprovalService.LIST)
  @SubjectRateLimited(bucket = ExpenseApprovalService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(approvals.list(caller(authentication, request), cursor, limit));
  }

  /**
   * One submitted expense routed to the caller.
   *
   * @param expenseId expense
   * @param authentication verified employee
   * @param request current request
   * @return the detail, after its disclosure audit committed
   */
  @Operation(operationId = "getMyExpenseApproval")
  @GetMapping("/{expenseId}")
  @EmployeeSelfOperation(operation = ExpenseApprovalService.READ)
  @SubjectRateLimited(bucket = ExpenseApprovalService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DetailView> read(
      @PathVariable("expenseId") String expenseId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(approvals.read(caller(authentication, request), expenseId));
  }

  /**
   * Downloads the receipt bound to one submitted revision of an expense routed to the caller.
   *
   * @param expenseId expense
   * @param revision revision
   * @param authentication verified employee
   * @param request current request
   * @return the receipt as an attachment
   */
  @Operation(operationId = "downloadMyExpenseApprovalReceipt")
  @GetMapping("/{expenseId}/revisions/{revision}/receipt")
  @EmployeeSelfOperation(operation = ExpenseApprovalService.RECEIPT_READ)
  @SubjectRateLimited(bucket = ExpenseApprovalService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> receipt(
      @PathVariable("expenseId") String expenseId,
      @PathVariable("revision") String revision,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return download(approvals.receipt(caller(authentication, request), expenseId, revision));
  }

  /**
   * Approves an expense routed to the caller. Approval is not reimbursement or payment.
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param body expected version
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "approveMyExpenseApproval")
  @PostMapping(path = "/{expenseId}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = ExpenseApprovalService.APPROVE)
  @SubjectRateLimited(bucket = ExpenseApprovalService.SUBJECT_DECISION_BUCKET)
  @TenantRateLimited(bucket = ExpenseApprovalService.TENANT_DECISION_BUCKET)
  public ResponseEntity<CommandReceipt> approve(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ExpenseVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        approvals.approve(caller(authentication, request), expenseId, idempotencyKey, body));
  }

  /**
   * Returns an expense routed to the caller for correction, with a reason.
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param body expected version and reason
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "returnMyExpenseApproval")
  @PostMapping(path = "/{expenseId}/return", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = ExpenseApprovalService.RETURN)
  @SubjectRateLimited(bucket = ExpenseApprovalService.SUBJECT_DECISION_BUCKET)
  @TenantRateLimited(bucket = ExpenseApprovalService.TENANT_DECISION_BUCKET)
  public ResponseEntity<CommandReceipt> returnExpense(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ReturnExpenseRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        approvals.returnExpense(caller(authentication, request), expenseId, idempotencyKey, body));
  }

  private static ResponseEntity<CommandReceipt> respond(
      IdempotentOperation.Result<CommandReceipt> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static final String CACHE_CONTROL = "private, no-store";

  private static ResponseEntity<byte[]> download(ExpenseReceiptDownload receipt) {
    String extension =
        switch (receipt.mediaType()) {
          case "application/pdf" -> "pdf";
          case "image/jpeg" -> "jpg";
          default -> "png";
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(receipt.mediaType()))
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt." + extension + "\"")
        .header("X-Content-Type-Options", "nosniff")
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(receipt.content());
  }

  private OperationsCaller caller(
      JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new OperationsCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

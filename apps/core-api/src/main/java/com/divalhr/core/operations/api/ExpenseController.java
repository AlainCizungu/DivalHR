package com.divalhr.core.operations.api;

import com.divalhr.core.operations.api.ExpenseResponses.DetailView;
import com.divalhr.core.operations.api.ExpenseResponses.Page;
import com.divalhr.core.operations.application.ExpenseReceiptDownload;
import com.divalhr.core.operations.application.ExpenseService;
import com.divalhr.core.operations.application.OperationsCaller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant administrator expense endpoints (MVP-081, Issue #169): read-only. Every handler is a
 * tenant administrator operation ({@code TenantAdminOperation}: subject, role, exact MFA, tenant
 * and active membership are checked before any argument is read). There is no create, change,
 * submit, withdraw, approve or return on another person's behalf. Responses are {@code private,
 * no-store}.
 */
@RestController
@RequestMapping(ExpenseController.PATH)
public class ExpenseController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/expenses";

  private final ExpenseService expenses;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param expenses administrator expense service
   * @param tenants verified tenant resolver
   */
  public ExpenseController(ExpenseService expenses, TenantContextResolver tenants) {
    this.expenses = expenses;
    this.tenants = tenants;
  }

  /**
   * The tenant's submitted expenses.
   *
   * @param employeeId owner filter
   * @param state state filter
   * @param currency currency filter
   * @param category category filter
   * @param from earliest business date
   * @param to latest business date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified administrator
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listExpenses")
  @GetMapping
  @TenantAdminOperation(operation = ExpenseService.LIST)
  @SubjectRateLimited(bucket = ExpenseService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "employeeId", required = false) String employeeId,
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "currency", required = false) String currency,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            expenses.list(
                caller(authentication, request),
                cursor,
                limit,
                employeeId,
                state,
                currency,
                category,
                from,
                to));
  }

  /**
   * One submitted expense with its submitted revisions and history.
   *
   * @param expenseId expense
   * @param authentication verified administrator
   * @param request current request
   * @return the detail, after its disclosure audit committed
   */
  @Operation(operationId = "getExpense")
  @GetMapping("/{expenseId}")
  @TenantAdminOperation(operation = ExpenseService.READ)
  @SubjectRateLimited(bucket = ExpenseService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DetailView> read(
      @PathVariable("expenseId") String expenseId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(expenses.read(caller(authentication, request), expenseId));
  }

  /**
   * Downloads the receipt bound to one submitted revision.
   *
   * @param expenseId expense
   * @param revision revision
   * @param authentication verified administrator
   * @param request current request
   * @return the receipt as an attachment
   */
  @Operation(operationId = "downloadExpenseReceipt")
  @GetMapping("/{expenseId}/revisions/{revision}/receipt")
  @TenantAdminOperation(operation = ExpenseService.RECEIPT_READ)
  @SubjectRateLimited(bucket = ExpenseService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> receipt(
      @PathVariable("expenseId") String expenseId,
      @PathVariable("revision") String revision,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return download(expenses.receipt(caller(authentication, request), expenseId, revision));
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

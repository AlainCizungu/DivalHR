package com.divalhr.core.operations.api;

import com.divalhr.core.operations.api.ExpenseResponses.CommandReceipt;
import com.divalhr.core.operations.api.ExpenseResponses.DetailView;
import com.divalhr.core.operations.api.ExpenseResponses.Page;
import com.divalhr.core.operations.application.MyExpenseService;
import com.divalhr.core.operations.application.ExpenseReceiptDownload;
import com.divalhr.core.operations.application.OperationsCaller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
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
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee self-service expense endpoints (MVP-081, Issue #169). Every handler is an employee
 * self-service operation ({@code EmployeeSelfOperation}): subject, role {@code employee}, tenant
 * and an active membership are checked, then the request limits, before any argument or body is
 * read; the service binds every query to the caller's own active employee-access link. The receipt
 * upload is a bounded raw body (no multipart): its headers are validated and the caller's right to
 * the expense is established before the first body byte is read. Responses are {@code private,
 * no-store}.
 */
@RestController
@RequestMapping(MyExpenseController.PATH)
public class MyExpenseController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/me/expenses";

  private final MyExpenseService expenses;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param expenses self-service expense service
   * @param tenants verified tenant resolver
   */
  public MyExpenseController(MyExpenseService expenses, TenantContextResolver tenants) {
    this.expenses = expenses;
    this.tenants = tenants;
  }

  /**
   * Creates a draft.
   *
   * @param idempotencyKey required idempotency key
   * @param body the values
   * @param authentication verified employee
   * @param request current request
   * @return 201 with the draft; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createMyExpense")
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyExpenseService.CREATE)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MyExpenseService.TENANT_WRITE_BUCKET)
  public ResponseEntity<CommandReceipt> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateExpenseRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.CREATED, expenses.create(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * The caller's own expenses.
   *
   * @param state state filter
   * @param currency currency filter
   * @param from earliest business date
   * @param to latest business date
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified employee
   * @param request current request
   * @return the page, after its disclosure audit committed
   */
  @Operation(operationId = "listMyExpenses")
  @GetMapping
  @EmployeeSelfOperation(operation = MyExpenseService.LIST)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "currency", required = false) String currency,
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
                caller(authentication, request), cursor, limit, state, currency, from, to));
  }

  /**
   * One of the caller's own expenses with its revisions and history.
   *
   * @param expenseId expense
   * @param authentication verified employee
   * @param request current request
   * @return the detail, after its disclosure audit committed
   */
  @Operation(operationId = "getMyExpense")
  @GetMapping("/{expenseId}")
  @EmployeeSelfOperation(operation = MyExpenseService.READ)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DetailView> read(
      @PathVariable("expenseId") String expenseId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(expenses.read(caller(authentication, request), expenseId));
  }

  /**
   * Replaces the values of the editable revision.
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param body expected version and values
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "updateMyExpenseDraft")
  @PostMapping(path = "/{expenseId}/draft", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyExpenseService.UPDATE)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MyExpenseService.TENANT_WRITE_BUCKET)
  public ResponseEntity<CommandReceipt> update(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody UpdateExpenseDraftRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        expenses.update(caller(authentication, request), expenseId, idempotencyKey, body));
  }

  /**
   * Attaches or replaces the receipt of the editable revision: a bounded raw body of a PDF, JPEG or
   * PNG, with the expected version in {@code X-Expected-Version} and the display name in {@code
   * X-Receipt-File-Name} (percent-encoded UTF-8).
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param expectedVersion expected version header
   * @param encodedName display name header
   * @param authentication verified employee
   * @param request current request (media type, body stream)
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   * @throws IOException when the body stream cannot be opened
   */
  @Operation(operationId = "setMyExpenseReceipt")
  @PutMapping(path = "/{expenseId}/receipt")
  @EmployeeSelfOperation(operation = MyExpenseService.RECEIPT_SET)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_RECEIPT_BUCKET)
  @TenantRateLimited(bucket = MyExpenseService.TENANT_RECEIPT_BUCKET)
  public ResponseEntity<CommandReceipt> setReceipt(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestHeader(name = "X-Expected-Version", required = false) String expectedVersion,
      @RequestHeader(name = "X-Receipt-File-Name", required = false) String encodedName,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request)
      throws IOException {
    try (InputStream body = request.getInputStream()) {
      return respond(
          HttpStatus.OK,
          expenses.setReceipt(
              caller(authentication, request),
              expenseId,
              idempotencyKey,
              expectedVersion,
              request.getContentType(),
              encodedName,
              request.getContentLengthLong(),
              body));
    }
  }

  /**
   * Removes the receipt of the editable revision.
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param expectedVersion expected version header
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "removeMyExpenseReceipt")
  @DeleteMapping("/{expenseId}/receipt")
  @EmployeeSelfOperation(operation = MyExpenseService.RECEIPT_REMOVE)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_RECEIPT_BUCKET)
  @TenantRateLimited(bucket = MyExpenseService.TENANT_RECEIPT_BUCKET)
  public ResponseEntity<CommandReceipt> removeReceipt(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestHeader(name = "X-Expected-Version", required = false) String expectedVersion,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        expenses.removeReceipt(
            caller(authentication, request), expenseId, idempotencyKey, expectedVersion));
  }

  /**
   * Downloads the receipt bound to one revision of the caller's own expense.
   *
   * @param expenseId expense
   * @param revision revision
   * @param authentication verified employee
   * @param request current request
   * @return the receipt as an attachment
   */
  @Operation(operationId = "downloadMyExpenseReceipt")
  @GetMapping("/{expenseId}/revisions/{revision}/receipt")
  @EmployeeSelfOperation(operation = MyExpenseService.RECEIPT_READ)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> receipt(
      @PathVariable("expenseId") String expenseId,
      @PathVariable("revision") String revision,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return download(expenses.receipt(caller(authentication, request), expenseId, revision));
  }

  /**
   * Submits the editable revision to the manager it routes to.
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param body expected version
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "submitMyExpense")
  @PostMapping(path = "/{expenseId}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyExpenseService.SUBMIT)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MyExpenseService.TENANT_WRITE_BUCKET)
  public ResponseEntity<CommandReceipt> submit(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ExpenseVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        expenses.submit(caller(authentication, request), expenseId, idempotencyKey, body));
  }

  /**
   * Withdraws a submitted expense before a decision.
   *
   * @param expenseId expense
   * @param idempotencyKey required idempotency key
   * @param body expected version
   * @param authentication verified employee
   * @param request current request
   * @return 200 with the expense; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "withdrawMyExpense")
  @PostMapping(path = "/{expenseId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = MyExpenseService.WITHDRAW)
  @SubjectRateLimited(bucket = MyExpenseService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MyExpenseService.TENANT_WRITE_BUCKET)
  public ResponseEntity<CommandReceipt> withdraw(
      @PathVariable("expenseId") String expenseId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ExpenseVersionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return respond(
        HttpStatus.OK,
        expenses.withdraw(caller(authentication, request), expenseId, idempotencyKey, body));
  }

  private static ResponseEntity<CommandReceipt> respond(
      HttpStatus status, IdempotentOperation.Result<CommandReceipt> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"receipt." + extension + "\"")
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

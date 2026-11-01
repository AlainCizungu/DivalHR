package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentResponses.DestinationPage;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationPreview;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationReceipt;
import com.divalhr.core.payroll.api.PaymentResponses.DestinationRecord;
import com.divalhr.core.payroll.application.PaymentDestinationService;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
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
 * MVP-095A: an employee's salary payment destination for tenant administrators with privileged MFA
 * (ADR 0036): the masked read, the history, the preview and the idempotent commit. The tenant comes
 * from the verified JWT and the recorder is the verified subject (never the body or the path);
 * every response is {@code Cache-Control: private, no-store}. The account, phone, holder and branch
 * are never returned: only the masked value, and only after the commit. A recorded destination is
 * not verified with a bank or provider.
 */
@RestController
public class EmployeePaymentDestinationController {

  /** Base path of an employee's payment destination. */
  public static final String PATH = "/api/v1/employees/{employeeId}/payment-destination";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PaymentDestinationService destinations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param destinations destination service
   * @param tenants verified tenant context
   */
  public EmployeePaymentDestinationController(
      PaymentDestinationService destinations, TenantContextResolver tenants) {
    this.destinations = destinations;
    this.tenants = tenants;
  }

  /**
   * The employee's masked destination.
   *
   * @param employeeId employee
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the masked record
   */
  @Operation(operationId = "getEmployeePaymentDestination")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PaymentDestinationService.READ)
  @SubjectRateLimited(bucket = PaymentDestinationService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DestinationRecord> get(
      @PathVariable("employeeId") String employeeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(destinations.current(Caller.of(tenants, authentication, request), employeeId));
  }

  /**
   * The employee's destination history, newest first.
   *
   * @param employeeId employee
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of masked versions
   */
  @Operation(operationId = "listEmployeePaymentDestinationHistory")
  @GetMapping(PATH + "/history")
  @TenantAdminOperation(operation = PaymentDestinationService.READ)
  @SubjectRateLimited(bucket = PaymentDestinationService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DestinationPage> history(
      @PathVariable("employeeId") String employeeId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            destinations.history(
                Caller.of(tenants, authentication, request), employeeId, cursor, limit));
  }

  /**
   * Previews recording a destination.
   *
   * @param employeeId employee
   * @param body the destination
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPaymentDestination")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PaymentDestinationService.PREVIEW)
  @SubjectRateLimited(bucket = PaymentDestinationService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PaymentDestinationService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<DestinationPreview> preview(
      @PathVariable("employeeId") String employeeId,
      @RequestBody PaymentDestinationCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(destinations.preview(Caller.of(tenants, authentication, request), employeeId, body));
  }

  /**
   * Records a previewed destination.
   *
   * @param employeeId employee
   * @param idempotencyKey required idempotency key
   * @param body the destination, the evidence the preview returned and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the masked receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitPaymentDestination")
  @PostMapping(path = PATH + "/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PaymentDestinationService.COMMIT)
  @SubjectRateLimited(bucket = PaymentDestinationService.SUBJECT_COMMIT_BUCKET)
  @TenantRateLimited(bucket = PaymentDestinationService.TENANT_COMMIT_BUCKET)
  public ResponseEntity<DestinationReceipt> commit(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPaymentDestinationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<DestinationReceipt> result =
        destinations.recordDestination(
            Caller.of(tenants, authentication, request), employeeId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }
}

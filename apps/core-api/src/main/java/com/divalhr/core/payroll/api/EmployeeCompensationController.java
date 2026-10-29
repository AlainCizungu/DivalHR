package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.CompensationResponses.CancellationPreview;
import com.divalhr.core.payroll.api.CompensationResponses.Preview;
import com.divalhr.core.payroll.api.CompensationResponses.Receipt;
import com.divalhr.core.payroll.api.CompensationResponses.RecordView;
import com.divalhr.core.payroll.application.CompensationService;
import com.divalhr.core.payroll.application.PayrollCaller;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-093A: monthly base compensation of an employee, for tenant administrators with privileged
 * MFA (ADR 0032). The tenant comes from the verified JWT (never the body or path); every response
 * is {@code Cache-Control: private, no-store}. Recording compensation does not calculate payroll
 * or initiate payment.
 */
@RestController
public class EmployeeCompensationController {

  /** Base path of an employee's compensation. */
  public static final String PATH = "/api/v1/employees/{employeeId}/compensation";

  private static final String CACHE_CONTROL = "private, no-store";

  private final CompensationService compensation;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param compensation compensation service
   * @param tenants verified tenant context
   */
  public EmployeeCompensationController(
      CompensationService compensation, TenantContextResolver tenants) {
    this.compensation = compensation;
    this.tenants = tenants;
  }

  /**
   * The employee's compensation and a page of its history.
   *
   * @param employeeId employee
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the record
   */
  @Operation(operationId = "getEmployeeCompensation")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = CompensationService.READ)
  @SubjectRateLimited(bucket = CompensationService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RecordView> read(
      @PathVariable("employeeId") String employeeId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(compensation.read(caller(authentication, request), employeeId, cursor, limit));
  }

  /**
   * Previews establishing, changing or correcting compensation.
   *
   * @param employeeId employee
   * @param body the command
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmployeeCompensation")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = CompensationService.PREVIEW)
  @SubjectRateLimited(bucket = CompensationService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = CompensationService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<Preview> preview(
      @PathVariable("employeeId") String employeeId,
      @RequestBody CompensationCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(compensation.preview(caller(authentication, request), employeeId, body));
  }

  /**
   * Records a previewed command.
   *
   * @param employeeId employee
   * @param idempotencyKey required idempotency key
   * @param body the previewed command with its digests
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitEmployeeCompensation")
  @PostMapping(path = PATH + "/commit", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = CompensationService.COMMIT)
  @SubjectRateLimited(bucket = CompensationService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = CompensationService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> commit(
      @PathVariable("employeeId") String employeeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitCompensationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return created(
        compensation.commit(caller(authentication, request), employeeId, idempotencyKey, body));
  }

  /**
   * Previews the cancellation of a scheduled change.
   *
   * @param employeeId employee
   * @param changeId scheduled change
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewEmployeeCompensationCancellation")
  @PostMapping(path = PATH + "/{changeId}/cancel-preview")
  @TenantAdminOperation(operation = CompensationService.CANCEL_PREVIEW)
  @SubjectRateLimited(bucket = CompensationService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = CompensationService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<CancellationPreview> previewCancellation(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("changeId") String changeId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        compensation.previewCancellation(caller(authentication, request), employeeId, changeId));
  }

  /**
   * Cancels a scheduled change.
   *
   * @param employeeId employee
   * @param changeId scheduled change
   * @param idempotencyKey required idempotency key
   * @param body the previewed cancellation with its digests
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelEmployeeCompensationChange")
  @PostMapping(path = PATH + "/{changeId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = CompensationService.CANCEL)
  @SubjectRateLimited(bucket = CompensationService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = CompensationService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Receipt> cancel(
      @PathVariable("employeeId") String employeeId,
      @PathVariable("changeId") String changeId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CancelCompensationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return created(
        compensation.cancel(
            caller(authentication, request), employeeId, changeId, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static ResponseEntity<Receipt> created(IdempotentOperation.Result<Receipt> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private PayrollCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new PayrollCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

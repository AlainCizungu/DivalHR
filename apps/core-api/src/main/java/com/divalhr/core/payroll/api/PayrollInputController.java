package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollInputResponses.Detail;
import com.divalhr.core.payroll.api.PayrollInputResponses.Page;
import com.divalhr.core.payroll.api.PayrollInputResponses.Preview;
import com.divalhr.core.payroll.api.PayrollInputResponses.Result;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollInputService;
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
 * MVP-090A: time-based payroll inputs for tenant administrators with privileged MFA. The tenant
 * comes from the verified JWT (never the body or path); every response is {@code Cache-Control:
 * private, no-store}.
 */
@RestController
public class PayrollInputController {

  /** Base path. */
  public static final String PATH = "/api/v1/payroll-inputs";

  static final String CACHE_CONTROL = "private, no-store";

  private final PayrollInputService inputs;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param inputs payroll inputs
   * @param tenants verified tenant context
   */
  public PayrollInputController(PayrollInputService inputs, TenantContextResolver tenants) {
    this.inputs = inputs;
    this.tenants = tenants;
  }

  /**
   * What a run would hold now.
   *
   * @param body legal entity and period
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPayrollInputs")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollInputService.PREVIEW)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<Preview> preview(
      @RequestBody PayrollInputPreviewRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(inputs.preview(caller(authentication, request), body));
  }

  /**
   * Generates the run of a reviewed preview.
   *
   * @param idempotencyKey the idempotency key
   * @param body scope, digests and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "generatePayrollInputs")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollInputService.GENERATE)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = PayrollInputService.TENANT_WRITE_BUCKET)
  public ResponseEntity<Result> generate(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody GeneratePayrollInputsRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Result> result =
        inputs.generate(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The tenant's runs, newest first.
   *
   * @param legalEntityId filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listPayrollInputRuns")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PayrollInputService.LIST)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "legalEntityId", required = false) String legalEntityId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(inputs.list(caller(authentication, request), legalEntityId, cursor, limit));
  }

  /**
   * One run with its source weeks and employee inputs.
   *
   * @param runId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the run
   */
  @Operation(operationId = "getPayrollInputRun")
  @GetMapping(PATH + "/{runId}")
  @TenantAdminOperation(operation = PayrollInputService.READ)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Detail> run(
      @PathVariable("runId") String runId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(inputs.run(caller(authentication, request), runId));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
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

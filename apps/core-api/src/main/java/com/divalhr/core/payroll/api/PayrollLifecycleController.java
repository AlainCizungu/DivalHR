package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollInputService;
import com.divalhr.core.payroll.application.PayrollLifecycleService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-092: the version chain and the final lock of payroll-input runs, for tenant administrators
 * with privileged MFA. The tenant comes from the verified JWT (never the body or path); every
 * response is {@code Cache-Control: private, no-store}. Worked-time inputs only: nothing here
 * calculates or authorizes pay.
 */
@RestController
public class PayrollLifecycleController {

  private static final String PATH = PayrollInputController.PATH + "/{runId}";
  private static final String CACHE_CONTROL = "private, no-store";

  private final PayrollLifecycleService lifecycle;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param lifecycle payroll-input lifecycle
   * @param tenants verified tenant context
   */
  public PayrollLifecycleController(
      PayrollLifecycleService lifecycle, TenantContextResolver tenants) {
    this.lifecycle = lifecycle;
    this.tenants = tenants;
  }

  /**
   * What a superseding version would hold now.
   *
   * @param runId the run to replace
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewSupersedePayrollInputRun")
  @PostMapping(path = PATH + "/supersede-preview")
  @TenantAdminOperation(operation = PayrollLifecycleService.SUPERSEDE_PREVIEW)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.SupersedePreview> supersedePreview(
      @PathVariable("runId") String runId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(lifecycle.supersedePreview(caller(authentication, request), runId));
  }

  /**
   * Creates the next version of a run from a reviewed preview.
   *
   * @param runId the run to replace
   * @param idempotencyKey the idempotency key
   * @param body reason, digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the new version
   */
  @Operation(operationId = "supersedePayrollInputRun")
  @PostMapping(path = PATH + "/supersede", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollLifecycleService.SUPERSEDE)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = PayrollInputService.TENANT_WRITE_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.SupersedeResult> supersede(
      @PathVariable("runId") String runId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody SupersedePayrollInputRunRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        lifecycle.supersede(caller(authentication, request), runId, idempotencyKey, body));
  }

  /**
   * The whole version chain of the run's scope.
   *
   * @param runId any version of the scope
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the chain, oldest first
   */
  @Operation(operationId = "listPayrollInputRunVersions")
  @GetMapping(PATH + "/versions")
  @TenantAdminOperation(operation = PayrollLifecycleService.VERSIONS)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.Versions> versions(
      @PathVariable("runId") String runId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(lifecycle.versions(caller(authentication, request), runId));
  }

  /**
   * What locking a run would make final.
   *
   * @param runId the run
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewLockPayrollInputRun")
  @PostMapping(path = PATH + "/lock-preview")
  @TenantAdminOperation(operation = PayrollLifecycleService.LOCK_PREVIEW)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.LockPreview> lockPreview(
      @PathVariable("runId") String runId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(lifecycle.lockPreview(caller(authentication, request), runId));
  }

  /**
   * Locks a run from a reviewed preview.
   *
   * @param runId the run
   * @param idempotencyKey the idempotency key
   * @param body digest and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the locked run
   */
  @Operation(operationId = "lockPayrollInputRun")
  @PostMapping(path = PATH + "/lock", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollLifecycleService.LOCK)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = PayrollInputService.TENANT_WRITE_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.LockResult> lock(
      @PathVariable("runId") String runId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody LockPayrollInputRunRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(lifecycle.lock(caller(authentication, request), runId, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static <T> ResponseEntity<T> command(IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
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

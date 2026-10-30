package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollResponses.RunDetail;
import com.divalhr.core.payroll.api.PayrollResponses.RunPage;
import com.divalhr.core.payroll.api.PayrollResponses.RunPreview;
import com.divalhr.core.payroll.api.PayrollResponses.RunReceipt;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollRunService;
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
 * MVP-094A: monthly payroll runs of a legal entity (contractual gross), for tenant administrators
 * with privileged MFA (ADR 0033). The tenant comes from the verified JWT (never the body or path);
 * every response is {@code Cache-Control: private, no-store}. Contractual gross is not taxable
 * gross, net pay or money owed, and a draft is not statutory payroll.
 */
@RestController
public class PayrollRunController {

  /** Base path of a legal entity's payroll runs. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/payroll-runs";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayrollRunService runs;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param runs payroll run service
   * @param tenants verified tenant context
   */
  public PayrollRunController(PayrollRunService runs, TenantContextResolver tenants) {
    this.runs = runs;
    this.tenants = tenants;
  }

  /**
   * Previews a month: the population, the blockers and the contractual gross of each employee.
   *
   * @param legalEntityId legal entity
   * @param body the month and currency
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPayrollRun")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollRunService.PREVIEW)
  @SubjectRateLimited(bucket = PayrollRunService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PayrollRunService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<RunPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestBody PayrollRunCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(runs.preview(caller(authentication, request), legalEntityId, body));
  }

  /**
   * The legal entity's run versions.
   *
   * @param legalEntityId legal entity
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of runs
   */
  @Operation(operationId = "listPayrollRuns")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PayrollRunService.READ)
  @SubjectRateLimited(bucket = PayrollRunService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RunPage> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(runs.list(caller(authentication, request), legalEntityId, cursor, limit));
  }

  /**
   * Creates a previewed run.
   *
   * @param legalEntityId legal entity
   * @param idempotencyKey required idempotency key
   * @param body the previewed month with its digests
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitPayrollRun")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PayrollRunService.CREATE)
  @SubjectRateLimited(bucket = PayrollRunService.SUBJECT_CREATE_BUCKET)
  @TenantRateLimited(bucket = PayrollRunService.TENANT_CREATE_BUCKET)
  public ResponseEntity<RunReceipt> create(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPayrollRunRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<RunReceipt> result =
        runs.create(caller(authentication, request), legalEntityId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * One frozen run and a page of its lines.
   *
   * @param legalEntityId legal entity
   * @param runId run
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the run
   */
  @Operation(operationId = "getPayrollRun")
  @GetMapping(PATH + "/{runId}")
  @TenantAdminOperation(operation = PayrollRunService.READ)
  @SubjectRateLimited(bucket = PayrollRunService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RunDetail> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(runs.get(caller(authentication, request), legalEntityId, runId, cursor, limit));
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

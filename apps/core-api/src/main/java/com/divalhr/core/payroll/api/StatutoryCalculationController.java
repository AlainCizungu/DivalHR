package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.StatutoryResponses.CalculationDetail;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationPage;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationPreview;
import com.divalhr.core.payroll.api.StatutoryResponses.CalculationReceipt;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.StatutoryCalculationService;
import com.divalhr.core.payroll.application.StatutoryPackService;
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
 * MVP-094B: the statutory calculation of a frozen payroll run (employee deductions, employer
 * contributions, a preview net), for tenant administrators with privileged MFA (ADR 0034). The
 * tenant comes from the verified JWT (never the body or path); every response is {@code
 * Cache-Control: private, no-store}. A statutory preview is not a payslip, a payment, an approval
 * or a filing, and makes no legal-compliance claim.
 */
@RestController
public class StatutoryCalculationController {

  /** Base path of a run's statutory calculations. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/payroll-runs/{runId}/statutory-calculations";

  private static final String CACHE_CONTROL = "private, no-store";

  private final StatutoryCalculationService calculations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param calculations calculation service
   * @param tenants verified tenant context
   */
  public StatutoryCalculationController(
      StatutoryCalculationService calculations, TenantContextResolver tenants) {
    this.calculations = calculations;
    this.tenants = tenants;
  }

  /**
   * Previews the statutory calculation of a run.
   *
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param body the (empty) command
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewStatutoryCalculation")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = StatutoryCalculationService.PREVIEW)
  @SubjectRateLimited(bucket = StatutoryCalculationService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = StatutoryCalculationService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<CalculationPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @RequestBody StatutoryCalculationCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        calculations.preview(caller(authentication, request), legalEntityId, runId, body));
  }

  /**
   * Creates a previewed calculation.
   *
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitStatutoryCalculation")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = StatutoryCalculationService.CREATE)
  @SubjectRateLimited(bucket = StatutoryCalculationService.SUBJECT_CREATE_BUCKET)
  @TenantRateLimited(bucket = StatutoryCalculationService.TENANT_CREATE_BUCKET)
  public ResponseEntity<CalculationReceipt> create(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitStatutoryCalculationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<CalculationReceipt> result =
        calculations.create(
            caller(authentication, request), legalEntityId, runId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The statutory calculation versions of a run.
   *
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of calculations
   */
  @Operation(operationId = "listStatutoryCalculations")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = StatutoryCalculationService.READ)
  @SubjectRateLimited(bucket = StatutoryPackService.SUBJECT_READ_BUCKET)
  public ResponseEntity<CalculationPage> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        calculations.list(
            caller(authentication, request), legalEntityId, runId, cursor, limit));
  }

  /**
   * One frozen calculation, its pack evidence and a page of its lines.
   *
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param calculationId calculation
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the calculation
   */
  @Operation(operationId = "getStatutoryCalculation")
  @GetMapping(PATH + "/{calculationId}")
  @TenantAdminOperation(operation = StatutoryCalculationService.READ)
  @SubjectRateLimited(bucket = StatutoryPackService.SUBJECT_READ_BUCKET)
  public ResponseEntity<CalculationDetail> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @PathVariable("calculationId") String calculationId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        calculations.get(
            caller(authentication, request), legalEntityId, runId, calculationId, cursor, limit));
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

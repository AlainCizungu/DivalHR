package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CancelPolicy;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.CommitPolicy;
import com.divalhr.core.payroll.api.SalaryAdvanceBodies.PolicyCommand;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyEntry;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyPreview;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyReceipt;
import com.divalhr.core.payroll.api.SalaryAdvanceResponses.PolicyView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.SalaryAdvancePolicyService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-120A: the employer salary-advance participation policy of a legal entity, for tenant
 * administrators with privileged MFA (ADR 0041). The tenant comes from the verified JWT (never the
 * body or path); every response is {@code Cache-Control: private, no-store}. A policy is an
 * employer participation rule, not a credit product: DivalHR is not the lender.
 */
@RestController
public class SalaryAdvancePolicyController {

  /** Base path of a legal entity's salary-advance policies. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/salary-advance-policies";

  private static final String CACHE_CONTROL = "private, no-store";

  private final SalaryAdvancePolicyService policies;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param policies salary-advance policy service
   * @param tenants verified tenant context
   */
  public SalaryAdvancePolicyController(
      SalaryAdvancePolicyService policies, TenantContextResolver tenants) {
    this.policies = policies;
    this.tenants = tenants;
  }

  /**
   * The policy timeline of a legal entity.
   *
   * @param legalEntityId legal entity
   * @param currency optional currency filter
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the timeline
   */
  @Operation(operationId = "listSalaryAdvancePolicies")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = SalaryAdvancePolicyService.READ)
  @SubjectRateLimited(bucket = SalaryAdvancePolicyService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PolicyView> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "currency", required = false) String currency,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(policies.read(caller(authentication, request), legalEntityId, currency));
  }

  /**
   * Previews appending a policy version.
   *
   * @param legalEntityId legal entity
   * @param body the policy
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewSalaryAdvancePolicy")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvancePolicyService.PREVIEW)
  @SubjectRateLimited(bucket = SalaryAdvancePolicyService.SUBJECT_PREVIEW_BUCKET)
  public ResponseEntity<PolicyPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestBody PolicyCommand body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(policies.preview(caller(authentication, request), legalEntityId, body));
  }

  /**
   * Records a previewed policy version.
   *
   * @param legalEntityId legal entity
   * @param idempotencyKey required idempotency key
   * @param body the previewed policy with its digest
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitSalaryAdvancePolicy")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvancePolicyService.COMMIT)
  @SubjectRateLimited(bucket = SalaryAdvancePolicyService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<PolicyReceipt> commit(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPolicy body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<PolicyReceipt> result =
        policies.commit(caller(authentication, request), legalEntityId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * Cancels a policy version that has not taken effect.
   *
   * @param legalEntityId legal entity
   * @param policyId policy
   * @param idempotencyKey required idempotency key
   * @param body the version being cancelled and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 200 with the cancelled version; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelSalaryAdvancePolicy")
  @PostMapping(path = PATH + "/{policyId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvancePolicyService.CANCEL)
  @SubjectRateLimited(bucket = SalaryAdvancePolicyService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<PolicyEntry> cancel(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("policyId") String policyId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CancelPolicy body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<PolicyEntry> result =
        policies.cancel(
            caller(authentication, request), legalEntityId, policyId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
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

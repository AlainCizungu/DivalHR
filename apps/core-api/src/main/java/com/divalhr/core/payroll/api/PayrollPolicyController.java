package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollResponses.PolicyPreview;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyReceipt;
import com.divalhr.core.payroll.api.PayrollResponses.PolicyView;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollPolicyService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-094A: the effective-dated monthly payroll policy of a legal entity, for tenant administrators
 * with privileged MFA (ADR 0033). The tenant comes from the verified JWT (never the body or path);
 * every response is {@code Cache-Control: private, no-store}. These are employer policies, not
 * legal-compliance claims.
 */
@RestController
public class PayrollPolicyController {

  /** Base path of a legal entity's payroll policy. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/payroll-policy";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayrollPolicyService policies;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param policies payroll policy service
   * @param tenants verified tenant context
   */
  public PayrollPolicyController(PayrollPolicyService policies, TenantContextResolver tenants) {
    this.policies = policies;
    this.tenants = tenants;
  }

  /**
   * The policy timeline of a legal entity.
   *
   * @param legalEntityId legal entity
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the timeline
   */
  @Operation(operationId = "getPayrollPolicy")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PayrollPolicyService.READ)
  @SubjectRateLimited(bucket = PayrollPolicyService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PolicyView> read(
      @PathVariable("legalEntityId") String legalEntityId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(policies.read(caller(authentication, request), legalEntityId));
  }

  /**
   * Previews appending a policy.
   *
   * @param legalEntityId legal entity
   * @param body the policy
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPayrollPolicy")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollPolicyService.PREVIEW)
  @SubjectRateLimited(bucket = PayrollPolicyService.SUBJECT_PREVIEW_BUCKET)
  public ResponseEntity<PolicyPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestBody PayrollPolicyCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(policies.preview(caller(authentication, request), legalEntityId, body));
  }

  /**
   * Records a previewed policy.
   *
   * @param legalEntityId legal entity
   * @param idempotencyKey required idempotency key
   * @param body the previewed policy with its digest
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitPayrollPolicy")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PayrollPolicyService.COMMIT)
  @SubjectRateLimited(bucket = PayrollPolicyService.SUBJECT_COMMIT_BUCKET)
  public ResponseEntity<PolicyReceipt> commit(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPayrollPolicyRequest body,
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

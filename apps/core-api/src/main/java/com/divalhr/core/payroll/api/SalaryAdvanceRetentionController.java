package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.SalaryAdvanceRetentionBodies.ConfigureRetention;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionBodies.ConfirmPurge;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionResponses.Policy;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionResponses.PurgePreview;
import com.divalhr.core.payroll.api.SalaryAdvanceRetentionResponses.PurgeResult;
import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.SalaryAdvanceHandoffReviewService;
import com.divalhr.core.payroll.application.SalaryAdvanceRetentionService;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-120C: the tenant administrator's evidence-retention policy and controlled purge of delivery
 * diagnostics (ADR 0042 amendment), exact MFA only. The tenant comes from the verified JWT; every
 * response is {@code Cache-Control: private, no-store}. There is no automatic purge, no bulk export
 * and no endpoint that deletes anything that was not previewed and explicitly confirmed.
 */
@RestController
public class SalaryAdvanceRetentionController {

  /** The organization policy. */
  public static final String POLICY_PATH = "/api/v1/salary-advance-retention-policy";

  /** The purge preview and confirmation base path. */
  public static final String PURGE_PATH = "/api/v1/salary-advance-evidence-purge";

  private static final String CACHE_CONTROL = "private, no-store";

  private final SalaryAdvanceRetentionService retention;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param retention retention service
   * @param tenants verified tenant context
   */
  public SalaryAdvanceRetentionController(
      SalaryAdvanceRetentionService retention, TenantContextResolver tenants) {
    this.retention = retention;
    this.tenants = tenants;
  }

  /**
   * The organization retention policy (retention is disabled until one exists).
   *
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the policy
   */
  @Operation(operationId = "getSalaryAdvanceRetentionPolicy")
  @GetMapping(POLICY_PATH)
  @TenantAdminOperation(operation = SalaryAdvanceRetentionService.POLICY_READ)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_READ_BUCKET)
  public ResponseEntity<Policy> policy(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(retention.policy(caller(authentication, request)));
  }

  /**
   * Configures the retention period (a new immutable version; prospective only).
   *
   * @param body the requested period
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the policy after the change
   */
  @Operation(operationId = "configureSalaryAdvanceRetentionPolicy")
  @PutMapping(path = POLICY_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvanceRetentionService.POLICY_CONFIGURE)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_RECONCILE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffReviewService.TENANT_RECONCILE_BUCKET)
  public ResponseEntity<Policy> configure(
      @RequestBody ConfigureRetention body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(retention.configure(caller(authentication, request), body));
  }

  /**
   * Previews the delivery diagnostics a purge would delete (counts and a digest only).
   *
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewSalaryAdvanceEvidencePurge")
  @PostMapping(path = PURGE_PATH + "/preview")
  @TenantAdminOperation(operation = SalaryAdvanceRetentionService.PURGE_PREVIEW)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_RECONCILE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffReviewService.TENANT_RECONCILE_BUCKET)
  public ResponseEntity<PurgePreview> preview(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(retention.preview(caller(authentication, request)));
  }

  /**
   * Confirms exactly one previewed purge.
   *
   * @param previewId the preview
   * @param body the digest the administrator saw and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the result; a repeated confirmation replays the stored result
   */
  @Operation(operationId = "confirmSalaryAdvanceEvidencePurge")
  @PostMapping(
      path = PURGE_PATH + "/{previewId}/confirm",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = SalaryAdvanceRetentionService.PURGE_CONFIRM)
  @SubjectRateLimited(bucket = SalaryAdvanceHandoffReviewService.SUBJECT_RECONCILE_BUCKET)
  @TenantRateLimited(bucket = SalaryAdvanceHandoffReviewService.TENANT_RECONCILE_BUCKET)
  public ResponseEntity<PurgeResult> confirm(
      @PathVariable("previewId") String previewId,
      @RequestBody ConfirmPurge body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(retention.confirm(caller(authentication, request), previewId, body));
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

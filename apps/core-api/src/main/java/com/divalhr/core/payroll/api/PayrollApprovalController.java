package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PayrollPackageResponses.ApprovalPreview;
import com.divalhr.core.payroll.api.PayrollPackageResponses.ApprovalReceipt;
import com.divalhr.core.payroll.application.PayrollApprovalService;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-094C: approval of a frozen payroll run and its statutory calculation, publishing one
 * bilingual payslip per employee, for tenant administrators with privileged MFA (ADR 0035). The
 * tenant comes from the verified JWT and the approver is the verified subject (never the body or
 * the path); every response is {@code Cache-Control: private, no-store}. Approved payroll is not a
 * payment, a filing or a claim of legal compliance.
 */
@RestController
public class PayrollApprovalController {

  /** Base path of a calculation's approval. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/payroll-runs/{runId}"
          + "/statutory-calculations/{calculationId}/approval";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PayrollApprovalService approvals;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param approvals approval service
   * @param tenants verified tenant context
   */
  public PayrollApprovalController(
      PayrollApprovalService approvals, TenantContextResolver tenants) {
    this.approvals = approvals;
    this.tenants = tenants;
  }

  /**
   * Previews the approval of a payroll run and its statutory calculation.
   *
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param calculationId statutory calculation
   * @param body the (empty) command
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPayrollApproval")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollApprovalService.PREVIEW)
  @SubjectRateLimited(bucket = PayrollApprovalService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PayrollApprovalService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<ApprovalPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @PathVariable("calculationId") String calculationId,
      @RequestBody PayrollApprovalPreviewRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            approvals.preview(
                caller(authentication, request), legalEntityId, runId, calculationId, body));
  }

  /**
   * Approves a previewed payroll and publishes its payslips.
   *
   * @param legalEntityId legal entity
   * @param runId payroll run
   * @param calculationId statutory calculation
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned and the three confirmations
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "commitPayrollApproval")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PayrollApprovalService.COMMIT)
  @SubjectRateLimited(bucket = PayrollApprovalService.SUBJECT_COMMIT_BUCKET)
  @TenantRateLimited(bucket = PayrollApprovalService.TENANT_COMMIT_BUCKET)
  public ResponseEntity<ApprovalReceipt> approve(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("runId") String runId,
      @PathVariable("calculationId") String calculationId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPayrollApprovalRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<ApprovalReceipt> result =
        approvals.approve(
            caller(authentication, request),
            legalEntityId,
            runId,
            calculationId,
            idempotencyKey,
            body);
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

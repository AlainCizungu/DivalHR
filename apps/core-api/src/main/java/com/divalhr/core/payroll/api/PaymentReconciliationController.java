package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentReconciliationResponses.InquiryPage;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.Preview;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.Receipt;
import com.divalhr.core.payroll.api.PaymentReconciliationResponses.View;
import com.divalhr.core.payroll.application.PaymentReconciliationService;
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
 * MVP-095C: reconciliation of an uncertain provider submission for tenant administrators with
 * privileged MFA (ADR 0038): the preview, the idempotent creation, the current state and the
 * inquiry history. The tenant comes from the verified JWT and the actor is the verified subject
 * (never the body or the path); every response is {@code Cache-Control: private, no-store}. No
 * endpoint sends or resends a payment, and there is no employee, manager or platform endpoint.
 */
@RestController
public class PaymentReconciliationController {

  /** Base path of a submission's reconciliation. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/payment-batches/{batchId}/submission/reconciliation";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PaymentReconciliationService reconciliations;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param reconciliations reconciliation service
   * @param tenants verified tenant context
   */
  public PaymentReconciliationController(
      PaymentReconciliationService reconciliations, TenantContextResolver tenants) {
    this.reconciliations = reconciliations;
    this.tenants = tenants;
  }

  /**
   * Previews opening an investigation of an uncertain submission.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPaymentReconciliation")
  @PostMapping(path = PATH + "/preview")
  @TenantAdminOperation(operation = PaymentReconciliationService.PREVIEW)
  @SubjectRateLimited(bucket = PaymentReconciliationService.PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PaymentReconciliationService.PREVIEW_BUCKET)
  public ResponseEntity<Preview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            reconciliations.preview(
                Caller.of(tenants, authentication, request), legalEntityId, batchId));
  }

  /**
   * Opens an investigation of a previewed submission.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned and the two confirmations
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createPaymentReconciliation")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PaymentReconciliationService.CREATE)
  @SubjectRateLimited(bucket = PaymentReconciliationService.WRITE_BUCKET)
  @TenantRateLimited(bucket = PaymentReconciliationService.WRITE_BUCKET)
  public ResponseEntity<Receipt> create(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPaymentReconciliationRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        reconciliations.open(
            Caller.of(tenants, authentication, request),
            legalEntityId,
            batchId,
            idempotencyKey,
            body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The current state of the submission's investigation.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the reconciliation
   */
  @Operation(operationId = "getPaymentReconciliation")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PaymentReconciliationService.READ)
  @SubjectRateLimited(bucket = PaymentReconciliationService.READ_BUCKET)
  @TenantRateLimited(bucket = PaymentReconciliationService.READ_BUCKET)
  public ResponseEntity<View> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            reconciliations.get(
                Caller.of(tenants, authentication, request), legalEntityId, batchId));
  }

  /**
   * A page of the investigation's inquiry history, newest first.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of evidence rows
   */
  @Operation(operationId = "listPaymentReconciliationInquiries")
  @GetMapping(PATH + "/inquiries")
  @TenantAdminOperation(operation = PaymentReconciliationService.READ)
  @SubjectRateLimited(bucket = PaymentReconciliationService.READ_BUCKET)
  @TenantRateLimited(bucket = PaymentReconciliationService.READ_BUCKET)
  public ResponseEntity<InquiryPage> inquiries(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            reconciliations.inquiries(
                Caller.of(tenants, authentication, request),
                legalEntityId,
                batchId,
                cursor,
                limit));
  }
}

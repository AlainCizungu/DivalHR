package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentSubmissionResponses.AttemptPage;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.SubmissionPreview;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.SubmissionReceipt;
import com.divalhr.core.payroll.api.PaymentSubmissionResponses.SubmissionView;
import com.divalhr.core.payroll.application.PaymentSubmissionService;
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
 * MVP-095B: provider submission of a prepared payment batch for tenant administrators with
 * privileged MFA (ADR 0037): the preview, the idempotent creation, the current state and the
 * attempt history. The tenant comes from the verified JWT and the actor is the verified subject
 * (never the body or the path); every response is {@code Cache-Control: private, no-store}. There
 * is no employee, manager or platform endpoint. "Accepted for processing" never means paid.
 */
@RestController
public class PaymentSubmissionController {

  /** Base path of a batch's submission. */
  public static final String PATH =
      "/api/v1/legal-entities/{legalEntityId}/payment-batches/{batchId}";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PaymentSubmissionService submissions;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param submissions submission service
   * @param tenants verified tenant context
   */
  public PaymentSubmissionController(
      PaymentSubmissionService submissions, TenantContextResolver tenants) {
    this.submissions = submissions;
    this.tenants = tenants;
  }

  /**
   * Previews submitting a prepared batch to the controlled provider.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPaymentSubmission")
  @PostMapping(path = PATH + "/submissions/preview")
  @TenantAdminOperation(operation = PaymentSubmissionService.PREVIEW)
  @SubjectRateLimited(bucket = PaymentSubmissionService.PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PaymentSubmissionService.PREVIEW_BUCKET)
  public ResponseEntity<SubmissionPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            submissions.preview(
                Caller.of(tenants, authentication, request), legalEntityId, batchId));
  }

  /**
   * Submits a previewed batch.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned and the two confirmations
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createPaymentSubmission")
  @PostMapping(path = PATH + "/submissions", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PaymentSubmissionService.CREATE)
  @SubjectRateLimited(bucket = PaymentSubmissionService.WRITE_BUCKET)
  @TenantRateLimited(bucket = PaymentSubmissionService.WRITE_BUCKET)
  public ResponseEntity<SubmissionReceipt> create(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPaymentSubmissionRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<SubmissionReceipt> result =
        submissions.submit(
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
   * The current state of the batch's submission.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the submission
   */
  @Operation(operationId = "getPaymentSubmission")
  @GetMapping(PATH + "/submission")
  @TenantAdminOperation(operation = PaymentSubmissionService.READ)
  @SubjectRateLimited(bucket = PaymentSubmissionService.READ_BUCKET)
  @TenantRateLimited(bucket = PaymentSubmissionService.READ_BUCKET)
  public ResponseEntity<SubmissionView> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(submissions.get(Caller.of(tenants, authentication, request), legalEntityId, batchId));
  }

  /**
   * A page of the submission's attempt history, newest first.
   *
   * @param legalEntityId legal entity
   * @param batchId payment batch
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of evidence rows
   */
  @Operation(operationId = "listPaymentSubmissionAttempts")
  @GetMapping(PATH + "/submission/attempts")
  @TenantAdminOperation(operation = PaymentSubmissionService.READ)
  @SubjectRateLimited(bucket = PaymentSubmissionService.READ_BUCKET)
  @TenantRateLimited(bucket = PaymentSubmissionService.READ_BUCKET)
  public ResponseEntity<AttemptPage> attempts(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            submissions.attempts(
                Caller.of(tenants, authentication, request),
                legalEntityId,
                batchId,
                cursor,
                limit));
  }
}

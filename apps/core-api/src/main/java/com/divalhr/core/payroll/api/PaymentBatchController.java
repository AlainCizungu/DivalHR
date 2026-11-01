package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.PaymentResponses.BatchDetail;
import com.divalhr.core.payroll.api.PaymentResponses.BatchPage;
import com.divalhr.core.payroll.api.PaymentResponses.BatchPreview;
import com.divalhr.core.payroll.api.PaymentResponses.BatchReceipt;
import com.divalhr.core.payroll.api.PaymentResponses.CancelPreview;
import com.divalhr.core.payroll.api.PaymentResponses.CancelReceipt;
import com.divalhr.core.payroll.api.PaymentResponses.LinePage;
import com.divalhr.core.payroll.application.PaymentBatchService;
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
 * MVP-095A: payment batches of approved payroll packages for tenant administrators with privileged
 * MFA (ADR 0036): the preview and the idempotent preparation, the history, the lines, and the
 * preview and the idempotent cancellation. The tenant comes from the verified JWT and the actor is
 * the verified subject (never the body or the path); every response is {@code Cache-Control:
 * private, no-store}. A prepared batch records instructions to pay; it is not a payment, a
 * transmission to a bank or provider, a reconciliation or a claim of legal compliance.
 */
@RestController
public class PaymentBatchController {

  /** Base path of a package's payment batches. */
  public static final String PACKAGE_PATH =
      "/api/v1/legal-entities/{legalEntityId}/payroll-packages/{packageId}/payment-batches";

  /** Base path of a legal entity's payment batches. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/payment-batches";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PaymentBatchService batches;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param batches batch service
   * @param tenants verified tenant context
   */
  public PaymentBatchController(PaymentBatchService batches, TenantContextResolver tenants) {
    this.batches = batches;
    this.tenants = tenants;
  }

  /**
   * Previews preparing a payment batch from an approved package.
   *
   * @param legalEntityId legal entity
   * @param packageId payroll package
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPaymentBatch")
  @PostMapping(path = PACKAGE_PATH + "/preview")
  @TenantAdminOperation(operation = PaymentBatchService.PREVIEW)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PaymentBatchService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<BatchPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            batches.preview(
                Caller.of(tenants, authentication, request), legalEntityId, packageId));
  }

  /**
   * Prepares a previewed payment batch.
   *
   * @param legalEntityId legal entity
   * @param packageId payroll package
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned and the two confirmations
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "preparePaymentBatch")
  @PostMapping(path = PACKAGE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PaymentBatchService.PREPARE)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_COMMIT_BUCKET)
  @TenantRateLimited(bucket = PaymentBatchService.TENANT_COMMIT_BUCKET)
  public ResponseEntity<BatchReceipt> prepare(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPaymentBatchRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<BatchReceipt> result =
        batches.prepare(
            Caller.of(tenants, authentication, request),
            legalEntityId,
            packageId,
            idempotencyKey,
            body);
    return created(result.replayed()).body(result.body());
  }

  /**
   * The legal entity's batches, newest first.
   *
   * @param legalEntityId legal entity
   * @param packageId optional package filter
   * @param status optional status filter
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of batches
   */
  @Operation(operationId = "listPaymentBatches")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PaymentBatchService.READ)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_READ_BUCKET)
  public ResponseEntity<BatchPage> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "packageId", required = false) String packageId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            batches.list(
                Caller.of(tenants, authentication, request),
                legalEntityId,
                packageId,
                status,
                cursor,
                limit));
  }

  /**
   * One batch with its evidence.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the batch
   */
  @Operation(operationId = "getPaymentBatch")
  @GetMapping(PATH + "/{batchId}")
  @TenantAdminOperation(operation = PaymentBatchService.READ)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_READ_BUCKET)
  public ResponseEntity<BatchDetail> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(batches.get(Caller.of(tenants, authentication, request), legalEntityId, batchId));
  }

  /**
   * A page of a batch's lines.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return a page of lines
   */
  @Operation(operationId = "listPaymentBatchLines")
  @GetMapping(PATH + "/{batchId}/lines")
  @TenantAdminOperation(operation = PaymentBatchService.READ)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_READ_BUCKET)
  public ResponseEntity<LinePage> lines(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            batches.lines(
                Caller.of(tenants, authentication, request),
                legalEntityId,
                batchId,
                cursor,
                limit));
  }

  /**
   * Previews cancelling a prepared batch.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param body the closed reason
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewPaymentBatchCancellation")
  @PostMapping(path = PATH + "/{batchId}/cancel/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PaymentBatchService.CANCEL_PREVIEW)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_PREVIEW_BUCKET)
  @TenantRateLimited(bucket = PaymentBatchService.TENANT_PREVIEW_BUCKET)
  public ResponseEntity<CancelPreview> previewCancel(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestBody PaymentBatchCancelCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            batches.previewCancel(
                Caller.of(tenants, authentication, request), legalEntityId, batchId, body));
  }

  /**
   * Cancels a previewed batch.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param idempotencyKey required idempotency key
   * @param body the reason, the evidence the preview returned and the acknowledgement
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "cancelPaymentBatch")
  @PostMapping(path = PATH + "/{batchId}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = PaymentBatchService.CANCEL)
  @SubjectRateLimited(bucket = PaymentBatchService.SUBJECT_COMMIT_BUCKET)
  @TenantRateLimited(bucket = PaymentBatchService.TENANT_COMMIT_BUCKET)
  public ResponseEntity<CancelReceipt> cancel(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitPaymentBatchCancelRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<CancelReceipt> result =
        batches.cancel(
            Caller.of(tenants, authentication, request),
            legalEntityId,
            batchId,
            idempotencyKey,
            body);
    return created(result.replayed()).body(result.body());
  }

  private static ResponseEntity.BodyBuilder created(boolean replayed) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (replayed) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response;
  }
}

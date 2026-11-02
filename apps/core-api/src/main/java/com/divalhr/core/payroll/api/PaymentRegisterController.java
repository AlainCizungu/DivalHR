package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.application.PaymentRegisterService;
import com.divalhr.core.payroll.application.PaymentRegisterService.Download;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-095A: the deterministic payment register of a batch for tenant administrators with privileged
 * MFA (ADR 0036): a UTF-8 CSV and a canonical JSON manifest. Every download is a fail-closed
 * disclosure audit and is rate limited per subject and per tenant. Responses are {@code
 * Cache-Control: private, no-store}, attachments with a generated filename (never a name, an
 * employee number or an amount) and {@code X-Content-Type-Options: nosniff}. The register is an
 * instruction-preparation artifact, not proof of transmission or payment.
 */
@RestController
public class PaymentRegisterController {

  /** Media type of the register. */
  public static final String CSV_VALUE = "text/csv;charset=UTF-8";

  private static final String CACHE_CONTROL = "private, no-store";
  private static final MediaType CSV = MediaType.parseMediaType(CSV_VALUE);

  private final PaymentRegisterService registers;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param registers register service
   * @param tenants verified tenant context
   */
  public PaymentRegisterController(
      PaymentRegisterService registers, TenantContextResolver tenants) {
    this.registers = registers;
    this.tenants = tenants;
  }

  /**
   * The CSV register of a batch.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the CSV attachment
   */
  @Operation(operationId = "downloadPaymentRegisterCsv")
  @GetMapping(path = PaymentBatchController.PATH + "/{batchId}/register.csv", produces = CSV_VALUE)
  @TenantAdminOperation(operation = PaymentRegisterService.DOWNLOAD)
  @SubjectRateLimited(bucket = PaymentRegisterService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = PaymentRegisterService.TENANT_BUCKET)
  public ResponseEntity<byte[]> csv(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        registers.csv(Caller.of(tenants, authentication, request), legalEntityId, batchId), CSV);
  }

  /**
   * The JSON manifest of a batch.
   *
   * @param legalEntityId legal entity
   * @param batchId batch
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the JSON attachment
   */
  @Operation(operationId = "downloadPaymentRegisterManifest")
  @GetMapping(
      path = PaymentBatchController.PATH + "/{batchId}/manifest.json",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PaymentRegisterService.DOWNLOAD)
  @SubjectRateLimited(bucket = PaymentRegisterService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = PaymentRegisterService.TENANT_BUCKET)
  public ResponseEntity<byte[]> manifest(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("batchId") String batchId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        registers.manifest(Caller.of(tenants, authentication, request), legalEntityId, batchId),
        MediaType.APPLICATION_JSON);
  }

  private static ResponseEntity<byte[]> attachment(Download download, MediaType type) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(download.filename()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(type)
        .body(download.bytes());
  }
}

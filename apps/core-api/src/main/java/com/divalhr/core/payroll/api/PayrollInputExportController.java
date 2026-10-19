package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.application.PayrollCaller;
import com.divalhr.core.payroll.application.PayrollInputService;
import com.divalhr.core.payroll.application.PayrollLifecycleService;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
 * MVP-092: worked-time exports of locked payroll-input runs, for tenant administrators with
 * privileged MFA. Every response is {@code Cache-Control: private, no-store}; downloads are
 * attachments with a name built only from approved scope, dates and version. Worked-time inputs for
 * payroll preparation only: nothing here calculates or authorizes pay.
 */
@RestController
public class PayrollInputExportController {

  /** Base path. */
  public static final String PATH = "/api/v1/payroll-input-exports";

  private static final String CACHE_CONTROL = "private, no-store";
  private static final MediaType CSV =
      new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);

  private final PayrollLifecycleService lifecycle;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param lifecycle payroll-input lifecycle
   * @param tenants verified tenant context
   */
  public PayrollInputExportController(
      PayrollLifecycleService lifecycle, TenantContextResolver tenants) {
    this.lifecycle = lifecycle;
    this.tenants = tenants;
  }

  /**
   * Creates the export of a locked run, or returns its existing one.
   *
   * @param idempotencyKey the idempotency key
   * @param body the run and confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "createPayrollInputExport")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollLifecycleService.EXPORT_CREATE)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = PayrollInputService.TENANT_WRITE_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.ExportResult> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreatePayrollInputExportRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<PayrollLifecycleResponses.ExportResult> result =
        lifecycle.createExport(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The tenant's exports, newest first.
   *
   * @param legalEntityId filter, or {@code null}
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listPayrollInputExports")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = PayrollLifecycleService.EXPORT_LIST)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.ExportPage> list(
      @RequestParam(name = "legalEntityId", required = false) String legalEntityId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(lifecycle.exports(caller(authentication, request), legalEntityId, cursor, limit));
  }

  /**
   * One export.
   *
   * @param exportId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the export
   */
  @Operation(operationId = "getPayrollInputExport")
  @GetMapping(PATH + "/{exportId}")
  @TenantAdminOperation(operation = PayrollLifecycleService.EXPORT_READ)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<PayrollLifecycleResponses.ExportView> export(
      @PathVariable("exportId") String exportId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(lifecycle.exportView(caller(authentication, request), exportId));
  }

  /**
   * Downloads the CSV.
   *
   * @param exportId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the CSV attachment
   */
  @Operation(operationId = "downloadPayrollInputExportCsv")
  @GetMapping(path = PATH + "/{exportId}/csv", produces = "text/csv")
  @TenantAdminOperation(operation = PayrollLifecycleService.EXPORT_DOWNLOAD)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> csv(
      @PathVariable("exportId") String exportId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(lifecycle.csv(caller(authentication, request), exportId), CSV);
  }

  /**
   * Downloads the manifest.
   *
   * @param exportId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the manifest attachment
   */
  @Operation(operationId = "downloadPayrollInputExportManifest")
  @GetMapping(path = PATH + "/{exportId}/manifest", produces = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = PayrollLifecycleService.EXPORT_DOWNLOAD)
  @SubjectRateLimited(bucket = PayrollInputService.SUBJECT_READ_BUCKET)
  public ResponseEntity<byte[]> manifest(
      @PathVariable("exportId") String exportId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        lifecycle.manifest(caller(authentication, request), exportId), MediaType.APPLICATION_JSON);
  }

  private static ResponseEntity<byte[]> attachment(
      PayrollLifecycleResponses.Download download, MediaType type) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(download.filename()).build().toString())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(type)
        .body(download.bytes());
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

package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.application.AccountingDownloadService;
import com.divalhr.core.payroll.application.AccountingDownloadService.Download;
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
 * MVP-096: the deterministic CSV and canonical JSON manifest of an accounting journal for tenant
 * administrators with privileged MFA (ADR 0039 section 9). Every download is a fail-closed
 * disclosure audit and is rate limited per subject and per tenant. Responses are {@code
 * Cache-Control: private, no-store}, attachments with a generated filename (never a name or an
 * employee number) and {@code X-Content-Type-Options: nosniff}. Accounting export only: not posted
 * and not proof of payment.
 */
@RestController
public class AccountingDownloadController {

  /** Media type of the CSV. */
  public static final String CSV_VALUE = "text/csv;charset=UTF-8";

  private static final String CACHE_CONTROL = "private, no-store";
  private static final MediaType CSV = MediaType.parseMediaType(CSV_VALUE);

  private final AccountingDownloadService downloads;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param downloads download service
   * @param tenants verified tenant context
   */
  public AccountingDownloadController(
      AccountingDownloadService downloads, TenantContextResolver tenants) {
    this.downloads = downloads;
    this.tenants = tenants;
  }

  /**
   * The CSV of a journal.
   *
   * @param legalEntityId legal entity
   * @param journalId accounting journal
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the CSV attachment
   */
  @Operation(operationId = "downloadAccountingJournalCsv")
  @GetMapping(path = AccountingJournalController.PATH + "/{journalId}/csv", produces = CSV_VALUE)
  @TenantAdminOperation(operation = AccountingDownloadService.DOWNLOAD)
  @SubjectRateLimited(bucket = AccountingDownloadService.READ_BUCKET)
  @TenantRateLimited(bucket = AccountingDownloadService.READ_BUCKET)
  public ResponseEntity<byte[]> csv(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("journalId") String journalId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        downloads.csv(Caller.of(tenants, authentication, request), legalEntityId, journalId), CSV);
  }

  /**
   * The JSON manifest of a journal.
   *
   * @param legalEntityId legal entity
   * @param journalId accounting journal
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the JSON attachment
   */
  @Operation(operationId = "downloadAccountingJournalManifest")
  @GetMapping(
      path = AccountingJournalController.PATH + "/{journalId}/manifest",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = AccountingDownloadService.DOWNLOAD)
  @SubjectRateLimited(bucket = AccountingDownloadService.READ_BUCKET)
  @TenantRateLimited(bucket = AccountingDownloadService.READ_BUCKET)
  public ResponseEntity<byte[]> manifest(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("journalId") String journalId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return attachment(
        downloads.manifest(Caller.of(tenants, authentication, request), legalEntityId, journalId),
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

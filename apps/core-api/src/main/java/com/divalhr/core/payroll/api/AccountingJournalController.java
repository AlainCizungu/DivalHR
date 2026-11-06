package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.AccountingResponses.JournalList;
import com.divalhr.core.payroll.api.AccountingResponses.JournalPreview;
import com.divalhr.core.payroll.api.AccountingResponses.JournalView;
import com.divalhr.core.payroll.api.AccountingResponses.Receipt;
import com.divalhr.core.payroll.application.AccountingJournalService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-096: the accounting journal of an approved payroll package for tenant administrators with
 * privileged MFA (ADR 0039): the preview, the idempotent generation, the history of a package and
 * the immutable journal. The tenant comes from the verified JWT and the actor is the verified
 * subject (never the body or the path); every response is {@code Cache-Control: private,
 * no-store}. There is no cancel, delete, regenerate or overwrite endpoint, and none contacts an
 * accounting system, a bank or a provider. Accounting export only: not posted and not proof of
 * payment.
 */
@RestController
public class AccountingJournalController {

  /** Base path of a package's accounting journals. */
  public static final String PACKAGE_PATH =
      "/api/v1/legal-entities/{legalEntityId}/payroll-packages/{packageId}/accounting-journals";

  /** Base path of a legal entity's accounting journals. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/accounting-journals";

  private static final String CACHE_CONTROL = "private, no-store";

  private final AccountingJournalService journals;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param journals journal service
   * @param tenants verified tenant context
   */
  public AccountingJournalController(
      AccountingJournalService journals, TenantContextResolver tenants) {
    this.journals = journals;
    this.tenants = tenants;
  }

  /**
   * Previews the journal of a package.
   *
   * @param legalEntityId legal entity
   * @param packageId payroll package
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewAccountingJournal")
  @PostMapping(path = PACKAGE_PATH + "/preview")
  @TenantAdminOperation(operation = AccountingJournalService.PREVIEW)
  @SubjectRateLimited(bucket = AccountingJournalService.PREVIEW_BUCKET)
  public ResponseEntity<JournalPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            journals.preview(
                Caller.of(tenants, authentication, request), legalEntityId, packageId));
  }

  /**
   * Generates the journal of a previewed package.
   *
   * @param legalEntityId legal entity
   * @param packageId payroll package
   * @param idempotencyKey required idempotency key
   * @param body the evidence the preview returned and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "generateAccountingJournal")
  @PostMapping(path = PACKAGE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = AccountingJournalService.GENERATE)
  @SubjectRateLimited(bucket = AccountingJournalService.WRITE_BUCKET)
  @TenantRateLimited(bucket = AccountingJournalService.WRITE_BUCKET)
  public ResponseEntity<Receipt> generate(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitAccountingJournalRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<Receipt> result =
        journals.generate(
            Caller.of(tenants, authentication, request),
            legalEntityId,
            packageId,
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
   * The journals of a package.
   *
   * @param legalEntityId legal entity
   * @param packageId payroll package
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the history
   */
  @Operation(operationId = "listAccountingJournals")
  @GetMapping(PACKAGE_PATH)
  @TenantAdminOperation(operation = AccountingJournalService.READ)
  @SubjectRateLimited(bucket = AccountingJournalService.READ_BUCKET)
  @TenantRateLimited(bucket = AccountingJournalService.READ_BUCKET)
  public ResponseEntity<JournalList> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("packageId") String packageId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            journals.list(Caller.of(tenants, authentication, request), legalEntityId, packageId));
  }

  /**
   * One journal with its lines.
   *
   * @param legalEntityId legal entity
   * @param journalId accounting journal
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the journal
   */
  @Operation(operationId = "getAccountingJournal")
  @GetMapping(PATH + "/{journalId}")
  @TenantAdminOperation(operation = AccountingJournalService.READ)
  @SubjectRateLimited(bucket = AccountingJournalService.READ_BUCKET)
  @TenantRateLimited(bucket = AccountingJournalService.READ_BUCKET)
  public ResponseEntity<JournalView> get(
      @PathVariable("legalEntityId") String legalEntityId,
      @PathVariable("journalId") String journalId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(journals.get(Caller.of(tenants, authentication, request), legalEntityId, journalId));
  }
}

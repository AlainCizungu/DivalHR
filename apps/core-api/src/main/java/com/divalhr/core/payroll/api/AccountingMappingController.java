package com.divalhr.core.payroll.api;

import com.divalhr.core.payroll.api.AccountingResponses.MappingList;
import com.divalhr.core.payroll.api.AccountingResponses.MappingPreview;
import com.divalhr.core.payroll.api.AccountingResponses.MappingReceipt;
import com.divalhr.core.payroll.application.AccountingMappingService;
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
 * MVP-096: the append-only accounting mapping versions of a legal entity for tenant administrators
 * with privileged MFA (ADR 0039): the list, the preview and the idempotent creation. The tenant
 * comes from the verified JWT and the actor is the verified subject (never the body or the path);
 * every response is {@code Cache-Control: private, no-store}. A version is never edited or
 * deleted, and no endpoint contacts an accounting system. Accounting export only: not posted and
 * not proof of payment.
 */
@RestController
public class AccountingMappingController {

  /** Base path of a legal entity's accounting mappings. */
  public static final String PATH = "/api/v1/legal-entities/{legalEntityId}/accounting-mappings";

  private static final String CACHE_CONTROL = "private, no-store";

  private final AccountingMappingService mappings;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param mappings mapping service
   * @param tenants verified tenant context
   */
  public AccountingMappingController(
      AccountingMappingService mappings, TenantContextResolver tenants) {
    this.mappings = mappings;
    this.tenants = tenants;
  }

  /**
   * Lists the mapping versions of a legal entity, newest first.
   *
   * @param legalEntityId legal entity
   * @param currency optional currency filter
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the versions
   */
  @Operation(operationId = "listAccountingMappings")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = AccountingMappingService.READ)
  @SubjectRateLimited(bucket = AccountingMappingService.READ_BUCKET)
  @TenantRateLimited(bucket = AccountingMappingService.READ_BUCKET)
  public ResponseEntity<MappingList> list(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestParam(name = "currency", required = false) String currency,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            mappings.list(Caller.of(tenants, authentication, request), legalEntityId, currency));
  }

  /**
   * Previews appending a mapping version.
   *
   * @param legalEntityId legal entity
   * @param body the currency, effective date and five account codes
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the preview
   */
  @Operation(operationId = "previewAccountingMapping")
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = AccountingMappingService.PREVIEW)
  @SubjectRateLimited(bucket = AccountingMappingService.PREVIEW_BUCKET)
  public ResponseEntity<MappingPreview> preview(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestBody PreviewAccountingMappingRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            mappings.preview(
                Caller.of(tenants, authentication, request), legalEntityId, body));
  }

  /**
   * Appends a previewed mapping version.
   *
   * @param legalEntityId legal entity
   * @param idempotencyKey required idempotency key
   * @param body the previewed version, the evidence the preview returned and the confirmation
   * @param authentication verified tenant administrator
   * @param request current request
   * @return 201 with the receipt; replays carry {@code Idempotent-Replayed: true}
   */
  @Operation(operationId = "createAccountingMapping")
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @TenantAdminOperation(operation = AccountingMappingService.CREATE)
  @SubjectRateLimited(bucket = AccountingMappingService.WRITE_BUCKET)
  @TenantRateLimited(bucket = AccountingMappingService.WRITE_BUCKET)
  public ResponseEntity<MappingReceipt> create(
      @PathVariable("legalEntityId") String legalEntityId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CommitAccountingMappingRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    IdempotentOperation.Result<MappingReceipt> result =
        mappings.create(
            Caller.of(tenants, authentication, request), legalEntityId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }
}

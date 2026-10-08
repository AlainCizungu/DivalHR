package com.divalhr.core.documents.api;

import com.divalhr.core.documents.api.ContractExpirationResponses.Page;
import com.divalhr.core.documents.api.ContractExpirationResponses.Summary;
import com.divalhr.core.documents.application.ContractExpirationService;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The contract expiration queue (MVP-031A, Issue #73). Both handlers are
 * {@code @TenantAdminOperation} (tenant administrator, exact MFA, matching membership, before the
 * body is read), exactly like the other administrator contract reads; both are fail-closed
 * disclosures.
 */
@RestController
@RequestMapping(ContractExpirationController.PATH)
public class ContractExpirationController {

  /** Full public path (server base {@code /api/v1}). */
  public static final String PATH = "/api/v1/contract-expirations";

  private final ContractExpirationService expirations;
  private final Callers callers;

  /**
   * Creates the controller.
   *
   * @param expirations expiration service
   * @param callers verified caller helper
   */
  public ContractExpirationController(ContractExpirationService expirations, Callers callers) {
    this.expirations = expirations;
    this.callers = callers;
  }

  /**
   * A page of the queue. Filters travel in the body so the search text never reaches a URL.
   *
   * @param body filters and position
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "searchContractExpirations")
  @PostMapping(path = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = ContractExpirationService.SEARCH)
  @SubjectRateLimited(bucket = ContractExpirationService.SUBJECT_BUCKET)
  @TenantRateLimited(bucket = "employee-search")
  public ResponseEntity<Page> search(
      @RequestBody ContractExpirationSearchRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(expirations.search(callers.caller(authentication, request), body));
  }

  /**
   * The unfiltered counts (the administrator home card).
   *
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the summary
   */
  @Operation(operationId = "getContractExpirationSummary")
  @GetMapping("/summary")
  @TenantAdminOperation(operation = ContractExpirationService.SUMMARY)
  @SubjectRateLimited(bucket = ContractExpirationService.SUBJECT_BUCKET)
  public ResponseEntity<Summary> summary(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return Callers.ok(expirations.summary(callers.caller(authentication, request)));
  }
}

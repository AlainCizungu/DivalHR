package com.divalhr.core.identity.api;

import com.divalhr.core.identity.application.AccessReviewService;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The read-only access review (MVP-012B). Tenant administrators with exact MFA and a matching
 * membership (12A gate) only; one per-subject quota across the three operations, checked after
 * authorization and before binding (B5). Responses are {@code Cache-Control: private, no-store}
 * (also set on errors by {@link AccessReviewCacheControlFilter}) and are serialized only after the
 * disclosure audit committed (B2).
 */
@RestController
@RequestMapping(AccessReviewController.PATH)
public class AccessReviewController {

  /** Base path. */
  public static final String PATH = "/api/v1/access-review";

  /** Cache policy of every access-review response. */
  public static final String CACHE_CONTROL = "private, no-store";

  private final AccessReviewService reviews;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param reviews access review
   * @param tenants verified tenant resolver
   */
  public AccessReviewController(AccessReviewService reviews, TenantContextResolver tenants) {
    this.reviews = reviews;
    this.tenants = tenants;
  }

  /**
   * Lists the organization's active access.
   *
   * @param role optional role filter (validated by the service)
   * @param legalEntityId optional legal entity (validated by the service)
   * @param siteId optional site (validated by the service)
   * @param cursor opaque cursor
   * @param limit page size (validated by the service)
   * @param authentication verified caller
   * @param request current request (correlation ID)
   * @return one page
   */
  @Operation(operationId = "listAccessReviewEntries")
  @GetMapping("/entries")
  @TenantAdminOperation(operation = AccessReviewService.LIST)
  @SubjectRateLimited(bucket = AccessReviewService.RATE_BUCKET)
  public ResponseEntity<AccessReviewPageResponse> list(
      @RequestParam(name = "role", required = false) String role,
      @RequestParam(name = "legalEntityId", required = false) String legalEntityId,
      @RequestParam(name = "siteId", required = false) String siteId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(
            reviews.list(
                caller(authentication, request), role, legalEntityId, siteId, cursor, limit));
  }

  /**
   * Looks up one exact address.
   *
   * @param body exactly {@code {"email"}}
   * @param authentication verified caller
   * @param request current request
   * @return zero or one entry
   */
  @Operation(operationId = "lookupAccessReviewEntry")
  @PostMapping(path = "/lookup", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = AccessReviewService.LOOKUP)
  @SubjectRateLimited(bucket = AccessReviewService.RATE_BUCKET)
  public ResponseEntity<AccessReviewPageResponse> lookup(
      @RequestBody AccessReviewLookupRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(reviews.lookup(caller(authentication, request), body));
  }

  /**
   * Counts the active members per role.
   *
   * @param authentication verified caller
   * @param request current request
   * @return counts
   */
  @Operation(operationId = "getAccessReviewSummary")
  @GetMapping("/summary")
  @TenantAdminOperation(operation = AccessReviewService.SUMMARY)
  @SubjectRateLimited(bucket = AccessReviewService.RATE_BUCKET)
  public ResponseEntity<AccessReviewSummaryResponse> summary(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(reviews.summary(caller(authentication, request)));
  }

  private AccessReviewService.Caller caller(
      JwtAuthenticationToken authentication, HttpServletRequest request) {
    return new AccessReviewService.Caller(
        tenants.current().tenantId(),
        authentication.getToken().getSubject(),
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

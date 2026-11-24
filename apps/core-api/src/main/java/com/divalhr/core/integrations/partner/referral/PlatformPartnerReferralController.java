package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.History;
import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.Page;
import com.divalhr.core.integrations.partner.referral.PlatformReferralBodies.Referral;
import com.divalhr.core.integrations.partner.referral.PlatformReferralService.PlatformCaller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only operational view of partner referrals for platform administrators with exact MFA
 * (MVP-120F, D120F-5). Tenant administrators, managers and employees are refused before any
 * argument is read. There is no approval, edit, resend, override or deletion. Every response is
 * {@code Cache-Control: private, no-store}.
 */
@RestController
public class PlatformPartnerReferralController {

  /** Base path. */
  public static final String PATH = "/api/v1/platform/partner-referrals";

  /** Per-subject read bucket (requests per minute). */
  public static final String READ_BUCKET = "partner-referral-platform-read";

  private static final String CACHE_CONTROL = "private, no-store";

  private final PlatformReferralService service;

  /**
   * Creates the controller.
   *
   * @param service platform read service
   */
  public PlatformPartnerReferralController(PlatformReferralService service) {
    this.service = service;
  }

  /**
   * The operational queue.
   *
   * @param state optional lifecycle state
   * @param category optional category
   * @param cursor optional cursor
   * @param limit optional page size
   * @param authentication verified platform administrator
   * @return the page
   */
  @Operation(operationId = "listPlatformPartnerReferrals")
  @GetMapping(PATH)
  @PlatformScoped(operation = PlatformReferralService.LIST)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication) {
    return ok(service.list(subject(authentication), state, category, cursor, limit));
  }

  /**
   * One referral, operational view.
   *
   * @param referralId opaque referral reference
   * @param authentication verified platform administrator
   * @param request current request
   * @return the referral
   */
  @Operation(operationId = "getPlatformPartnerReferral")
  @GetMapping(PATH + "/{referralId}")
  @PlatformScoped(operation = PlatformReferralService.GET)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<Referral> get(
      @PathVariable("referralId") String referralId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        service.get(
            new PlatformCaller(
                subject(authentication),
                String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE))),
            referralId));
  }

  /**
   * The append-only history of one referral.
   *
   * @param referralId opaque referral reference
   * @param authentication verified platform administrator
   * @return the history
   */
  @Operation(operationId = "listPlatformPartnerReferralHistory")
  @GetMapping(PATH + "/{referralId}/history")
  @PlatformScoped(operation = PlatformReferralService.HISTORY)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<History> history(
      @PathVariable("referralId") String referralId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication) {
    subject(authentication);
    return ok(service.history(referralId));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }
}

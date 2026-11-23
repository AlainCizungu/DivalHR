package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Preview;
import com.divalhr.core.integrations.partner.referral.ReferralCommands.Inputs;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.EmployeeSelfOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The employee's consented partner referral (MVP-120F, ADR 0045). Subject, role {@code employee},
 * tenant and an active membership are checked, then the request limit, before any argument is read.
 * A referral is a consented introduction: DivalHR decides nothing and moves no money. Tenant
 * administrators and platform administrators have no route here. Every response is {@code
 * Cache-Control: private, no-store}.
 */
@RestController
public class MyPartnerReferralController {

  /** Base path. */
  public static final String PATH = "/api/v1/me/partner-referrals";

  /** Per-subject preview bucket (requests per minute). */
  public static final String PREVIEW_BUCKET = "partner-referral-preview";

  private static final int MAX_BODY_BYTES = 2048;
  private static final String CACHE_CONTROL = "private, no-store";

  private final PartnerReferralPreviewService previews;
  private final TenantContextResolver tenants;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param previews preview service
   * @param tenants verified tenant context
   * @param json JSON mapper
   */
  public MyPartnerReferralController(
      PartnerReferralPreviewService previews, TenantContextResolver tenants, JsonMapper json) {
    this.previews = previews;
    this.tenants = tenants;
    this.json = json;
  }

  /**
   * Previews exactly what a referral would disclose; writes nothing.
   *
   * @param authentication verified employee
   * @param request current request (the raw body is read here, bounded)
   * @return the preview
   */
  @Operation(
      operationId = "previewMyPartnerReferral",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema =
                          @Schema(implementation = PartnerReferralBodies.PreviewRequest.class))))
  @PostMapping(path = PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = "partner-referral.preview")
  @SubjectRateLimited(bucket = PREVIEW_BUCKET)
  public ResponseEntity<Preview> preview(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = ReferralBodyReader.read(request, json, MAX_BODY_BYTES);
    Inputs inputs = ReferralCommands.preview(body);
    return ResponseEntity.ok()
        .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
        .body(previews.preview(caller(authentication, request), inputs));
  }

  private ReferralCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new ReferralCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

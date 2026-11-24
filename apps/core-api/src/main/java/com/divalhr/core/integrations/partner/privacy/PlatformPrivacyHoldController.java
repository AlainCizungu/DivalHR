package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.HoldView;
import com.divalhr.core.integrations.partner.privacy.PrivacyHoldService.Caller;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds on partner referrals (LAUNCH-001, L6), exact MFA only. Tenant administrators, managers and
 * employees are refused before any argument is read. Every response is {@code Cache-Control:
 * private, no-store}.
 */
@RestController
public class PlatformPrivacyHoldController {

  /** Base path. */
  public static final String PATH = "/api/v1/platform/privacy/referrals/{referralId}/holds";

  static final int MAX_BODY_BYTES = 4096;
  private static final String CACHE_CONTROL = "private, no-store";

  private final PrivacyHoldService holds;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param holds hold service
   * @param json JSON mapper
   */
  public PlatformPrivacyHoldController(PrivacyHoldService holds, JsonMapper json) {
    this.holds = holds;
    this.json = json;
  }

  /**
   * Places a hold on a referral.
   *
   * @param referralId opaque referral reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the hold
   */
  @Operation(
      operationId = "placePlatformPrivacyHold",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.PlaceHold.class))))
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyHoldService.PLACE)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<HoldView> place(
      @PathVariable("referralId") String referralId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.CREATED,
        holds.place(caller(authentication, request), referralId, idempotencyKey, body));
  }

  /**
   * Releases a hold.
   *
   * @param referralId opaque referral reference
   * @param holdId opaque hold reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the hold
   */
  @Operation(
      operationId = "releasePlatformPrivacyHold",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.ReleaseHold.class))))
  @PostMapping(path = PATH + "/{holdId}/release", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyHoldService.RELEASE)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<HoldView> release(
      @PathVariable("referralId") String referralId,
      @PathVariable("holdId") String holdId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.OK,
        holds.release(caller(authentication, request), referralId, holdId, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> write(
      HttpStatus status, IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static Caller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new Caller(
        subject, String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

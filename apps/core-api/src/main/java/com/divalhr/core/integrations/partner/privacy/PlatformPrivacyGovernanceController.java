package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.ApplicationPreview;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.ApplicationResult;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgePreview;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PurgeResult;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Policy application and purge for platform administrators with exact MFA (LAUNCH-001, L5 and L9).
 * Tenant administrators, managers and employees are refused before any argument is read. There is
 * no route that purges by itself: every purge is a preview followed by a confirmation. Every
 * response is {@code Cache-Control: private, no-store}.
 */
@RestController
public class PlatformPrivacyGovernanceController {

  /** Base path of the policy application. */
  public static final String APPLICATION_PATH = "/api/v1/platform/privacy/policy-application";

  /** Base path of the purge. */
  public static final String PURGE_PATH = "/api/v1/platform/privacy/purge";

  static final int MAX_BODY_BYTES = 4096;
  private static final String CACHE_CONTROL = "private, no-store";

  private final PrivacyPolicyApplicationService applications;
  private final PrivacyPurgeService purges;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param applications policy application service
   * @param purges purge service
   * @param json JSON mapper
   */
  public PlatformPrivacyGovernanceController(
      PrivacyPolicyApplicationService applications, PrivacyPurgeService purges, JsonMapper json) {
    this.applications = applications;
    this.purges = purges;
    this.json = json;
  }

  /**
   * Previews assigning an ACTIVE policy to POLICY_PENDING referrals.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the preview
   */
  @Operation(
      operationId = "previewPlatformPrivacyPolicyApplication",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.PreviewApplication.class))))
  @PostMapping(path = APPLICATION_PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPolicyApplicationService.PREVIEW)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<ApplicationPreview> previewApplication(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        applications.preview(
            new PrivacyPolicyApplicationService.Caller(
                subject(authentication), correlation(request)),
            idempotencyKey,
            body));
  }

  /**
   * Confirms the assignment of an ACTIVE policy.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the evidence
   */
  @Operation(
      operationId = "commitPlatformPrivacyPolicyApplication",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.Commit.class))))
  @PostMapping(path = APPLICATION_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPolicyApplicationService.COMMIT)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<ApplicationResult> commitApplication(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        applications.commit(
            new PrivacyPolicyApplicationService.Caller(
                subject(authentication), correlation(request)),
            idempotencyKey,
            body));
  }

  /**
   * Previews a minimization or erasure.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the preview
   */
  @Operation(
      operationId = "previewPlatformPrivacyPurge",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.PreviewPurge.class))))
  @PostMapping(path = PURGE_PATH + "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPurgeService.PREVIEW)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<PurgePreview> previewPurge(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        purges.preview(
            new PrivacyPurgeService.Caller(subject(authentication), correlation(request)),
            idempotencyKey,
            body));
  }

  /**
   * Confirms a minimization or erasure.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the immutable evidence
   */
  @Operation(
      operationId = "commitPlatformPrivacyPurge",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.Commit.class))))
  @PostMapping(path = PURGE_PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPurgeService.COMMIT)
  @SubjectRateLimited(bucket = PlatformPrivacyPolicyController.WRITE_BUCKET)
  public ResponseEntity<PurgeResult> commitPurge(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        purges.commit(
            new PrivacyPurgeService.Caller(subject(authentication), correlation(request)),
            idempotencyKey,
            body));
  }

  private static <T> ResponseEntity<T> write(IdempotentOperation.Result<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.OK).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static String correlation(HttpServletRequest request) {
    return String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE));
  }

  private static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }
}

package com.divalhr.core.integrations.partner.privacy;

import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyHistory;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyPage;
import com.divalhr.core.integrations.partner.privacy.PrivacyBodies.PolicyView;
import com.divalhr.core.integrations.partner.privacy.PrivacyPolicyService.Caller;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Governed privacy-policy versions for platform administrators with exact MFA (LAUNCH-001, L3 and
 * L4). Tenant administrators, managers and employees are refused before any argument is read. Every
 * response is {@code Cache-Control: private, no-store}.
 */
@RestController
public class PlatformPrivacyPolicyController {

  /** Base path. */
  public static final String PATH = "/api/v1/platform/privacy/policies";

  /** Per-subject read bucket (requests per minute). */
  public static final String READ_BUCKET = "privacy-platform-read";

  /** Per-subject mutation bucket (requests per minute). */
  public static final String WRITE_BUCKET = "privacy-platform-write";

  /** Largest accepted command body. */
  static final int MAX_BODY_BYTES = 8192;

  private static final String CACHE_CONTROL = "private, no-store";

  private final PrivacyPolicyService policies;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param policies policy service
   * @param json JSON mapper
   */
  public PlatformPrivacyPolicyController(PrivacyPolicyService policies, JsonMapper json) {
    this.policies = policies;
    this.json = json;
  }

  /**
   * Creates a DRAFT policy version.
   *
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the version
   */
  @Operation(
      operationId = "createPlatformPrivacyPolicy",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.CreatePolicy.class))))
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPolicyService.CREATE)
  @SubjectRateLimited(bucket = WRITE_BUCKET)
  public ResponseEntity<PolicyView> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.CREATED, policies.create(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * A page of policy versions.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified platform administrator
   * @return the page
   */
  @Operation(operationId = "listPlatformPrivacyPolicies")
  @GetMapping(PATH)
  @PlatformScoped(operation = PrivacyPolicyService.LIST)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<PolicyPage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication) {
    return ok(policies.list(subject(authentication), cursor, limit));
  }

  /**
   * One policy version.
   *
   * @param policyId opaque policy reference
   * @param authentication verified platform administrator
   * @return the version
   */
  @Operation(operationId = "getPlatformPrivacyPolicy")
  @GetMapping(PATH + "/{policyId}")
  @PlatformScoped(operation = PrivacyPolicyService.GET)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<PolicyView> get(
      @PathVariable("policyId") String policyId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication) {
    subject(authentication);
    return ok(policies.get(policyId));
  }

  /**
   * Replaces the checklist of a DRAFT.
   *
   * @param policyId opaque policy reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the version
   */
  @Operation(
      operationId = "putPlatformPrivacyPolicyChecklist",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.PutChecklist.class))))
  @PutMapping(path = PATH + "/{policyId}/checklist", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPolicyService.CHECKLIST)
  @SubjectRateLimited(bucket = WRITE_BUCKET)
  public ResponseEntity<PolicyView> checklist(
      @PathVariable("policyId") String policyId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.OK,
        policies.putChecklist(caller(authentication, request), policyId, idempotencyKey, body));
  }

  /**
   * Moves a policy version along its closed lifecycle.
   *
   * @param policyId opaque policy reference
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param authentication verified platform administrator
   * @param request current request
   * @return the version
   */
  @Operation(
      operationId = "transitionPlatformPrivacyPolicy",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PrivacyCommandBodies.TransitionPolicy.class))))
  @PostMapping(path = PATH + "/{policyId}/transition", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PlatformScoped(operation = PrivacyPolicyService.TRANSITION)
  @SubjectRateLimited(bucket = WRITE_BUCKET)
  public ResponseEntity<PolicyView> transition(
      @PathVariable("policyId") String policyId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = PrivacyCommands.read(request, json, MAX_BODY_BYTES);
    return write(
        HttpStatus.OK,
        policies.transition(caller(authentication, request), policyId, idempotencyKey, body));
  }

  /**
   * The append-only history of one policy version.
   *
   * @param policyId opaque policy reference
   * @param authentication verified platform administrator
   * @return the history
   */
  @Operation(operationId = "listPlatformPrivacyPolicyHistory")
  @GetMapping(PATH + "/{policyId}/history")
  @PlatformScoped(operation = PrivacyPolicyService.HISTORY)
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<PolicyHistory> history(
      @PathVariable("policyId") String policyId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication) {
    subject(authentication);
    return ok(policies.history(policyId));
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
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
    return new Caller(
        subject(authentication),
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }

  private static String subject(JwtAuthenticationToken authentication) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return subject;
  }
}

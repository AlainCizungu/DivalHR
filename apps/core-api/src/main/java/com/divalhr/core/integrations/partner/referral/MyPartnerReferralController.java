package com.divalhr.core.integrations.partner.referral;

import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Page;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.Preview;
import com.divalhr.core.integrations.partner.referral.PartnerReferralBodies.View;
import com.divalhr.core.integrations.partner.referral.ReferralCommands.Inputs;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
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

  /** Per-subject submission bucket (requests per minute). */
  public static final String COMMIT_BUCKET = "partner-referral-commit";

  /** Per-subject withdrawal bucket (requests per minute). */
  public static final String WITHDRAW_BUCKET = "partner-referral-withdraw";

  /** Per-subject read bucket (requests per minute). */
  public static final String READ_BUCKET = "partner-referral-read";

  private static final int MAX_BODY_BYTES = 2048;
  private static final String CACHE_CONTROL = "private, no-store";

  private final PartnerReferralPreviewService previews;
  private final PartnerReferralService referrals;
  private final TenantContextResolver tenants;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param previews preview service
   * @param referrals submission, reads and withdrawal
   * @param tenants verified tenant context
   * @param json JSON mapper
   */
  public MyPartnerReferralController(
      PartnerReferralPreviewService previews,
      PartnerReferralService referrals,
      TenantContextResolver tenants,
      JsonMapper json) {
    this.previews = previews;
    this.referrals = referrals;
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

  /**
   * Submits the consented referral; replays carry {@code Idempotent-Replayed: true}.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified employee
   * @param request current request (the raw body is read here, bounded)
   * @return 201 with the referral
   */
  @Operation(
      operationId = "submitMyPartnerReferral",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema =
                          @Schema(implementation = PartnerReferralBodies.SubmitRequest.class))))
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  @EmployeeSelfOperation(operation = PartnerReferralService.SELF_SUBMIT)
  @SubjectRateLimited(bucket = COMMIT_BUCKET)
  public ResponseEntity<View> submit(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = ReferralBodyReader.read(request, json, MAX_BODY_BYTES);
    IdempotentOperation.Result<View> result =
        referrals.submit(caller(authentication, request), idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  /**
   * The employee's own referrals, newest first.
   *
   * @param state optional lifecycle state
   * @param cursor optional cursor
   * @param limit optional page size
   * @param authentication verified employee
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listMyPartnerReferrals")
  @GetMapping(PATH)
  @EmployeeSelfOperation(operation = "partner-referral.list")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "state", required = false) String state,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(referrals.list(caller(authentication, request), state, cursor, limit));
  }

  /**
   * One of the employee's own referrals.
   *
   * @param referralId opaque referral reference
   * @param authentication verified employee
   * @param request current request
   * @return the referral
   */
  @Operation(operationId = "getMyPartnerReferral")
  @GetMapping(PATH + "/{referralId}")
  @EmployeeSelfOperation(operation = "partner-referral.get")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<View> get(
      @PathVariable("referralId") String referralId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(referrals.get(caller(authentication, request), referralId));
  }

  /**
   * Withdraws a referral before the partner has, or may have, received it.
   *
   * @param referralId opaque referral reference
   * @param idempotencyKey required idempotency key
   * @param authentication verified employee
   * @param request current request (the raw body is read here, bounded)
   * @return the withdrawn referral
   */
  @Operation(
      operationId = "withdrawMyPartnerReferral",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = MediaType.APPLICATION_JSON_VALUE,
                      schema =
                          @Schema(implementation = PartnerReferralBodies.WithdrawRequest.class))))
  @PostMapping(path = PATH + "/{referralId}/withdraw", consumes = MediaType.APPLICATION_JSON_VALUE)
  @EmployeeSelfOperation(operation = PartnerReferralService.SELF_WITHDRAW)
  @SubjectRateLimited(bucket = WITHDRAW_BUCKET)
  public ResponseEntity<View> withdraw(
      @PathVariable("referralId") String referralId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    JsonNode body = ReferralBodyReader.read(request, json, MAX_BODY_BYTES);
    IdempotentOperation.Result<View> result =
        referrals.withdraw(caller(authentication, request), referralId, idempotencyKey, body);
    ResponseEntity.BodyBuilder response =
        ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
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

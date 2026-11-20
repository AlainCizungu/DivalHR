package com.divalhr.core.integrations.partner;

import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Check;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Detail;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.HistoryPage;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.Page;
import com.divalhr.core.integrations.partner.PartnerApplicationBodies.TransitionReceipt;
import com.divalhr.core.integrations.partner.PartnerReviewService.Caller;
import com.divalhr.core.integrations.partner.PartnerReviewService.Query;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Review of partner applications by platform administrators with exact MFA ({@link
 * PlatformScoped}, MVP-120D, ADR 0043). Subject, platform role and assurance are checked before the
 * path or body is bound; the caller's tenant claim is never read, so a tenant administrator,
 * manager or employee is refused. Every response is {@code Cache-Control: private, no-store}. An
 * unknown or malformed reference is the same 404. No operation here creates a credential,
 * membership, tenant access, integration configuration or listing.
 */
@RestController
public class PartnerApplicationReviewController {

  /** Base path of the review routes. */
  public static final String PATH = "/api/v1/platform/partner-applications";

  /** Rate-limit bucket of reads. */
  public static final String READ_BUCKET = "partner-application-read";

  /** Rate-limit bucket of commands. */
  public static final String COMMAND_BUCKET = "partner-application-command";

  /** Largest accepted command body. */
  static final int MAX_BYTES = 4096;

  private static final String CACHE_CONTROL = "private, no-store";
  private static final String ONE = PATH + "/{reference}";

  private final PartnerReviewService review;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param review review service
   * @param json JSON mapper
   */
  public PartnerApplicationReviewController(PartnerReviewService review, JsonMapper json) {
    this.review = review;
    this.json = json;
  }

  /**
   * The review queue.
   *
   * @param cursor opaque cursor
   * @param limit page size
   * @param status status filter
   * @param category category filter
   * @param capability capability filter
   * @param country country filter
   * @param submittedFrom first submission date
   * @param submittedTo last submission date
   * @param authentication verified platform administrator
   * @param request current request
   * @return a page of applications
   */
  @Operation(operationId = "listPartnerApplications")
  @GetMapping(PATH)
  @PlatformScoped(operation = "partner-application.list")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<Page> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "category", required = false) String category,
      @RequestParam(name = "capability", required = false) String capability,
      @RequestParam(name = "country", required = false) String country,
      @RequestParam(name = "submittedFrom", required = false) String submittedFrom,
      @RequestParam(name = "submittedTo", required = false) String submittedTo,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        review.list(
            caller(authentication, request),
            new Query(
                cursor, limit, status, category, capability, country, submittedFrom, submittedTo)));
  }

  /**
   * One application in detail.
   *
   * @param reference opaque reference
   * @param authentication verified platform administrator
   * @param request current request
   * @return the application
   */
  @Operation(operationId = "getPartnerApplication")
  @GetMapping(ONE)
  @PlatformScoped(operation = "partner-application.view")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<Detail> get(
      @PathVariable("reference") String reference,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(review.get(caller(authentication, request), reference));
  }

  /**
   * The immutable history of an application.
   *
   * @param reference opaque reference
   * @param cursor opaque cursor
   * @param limit page size
   * @param authentication verified platform administrator
   * @param request current request
   * @return a page of history
   */
  @Operation(operationId = "getPartnerApplicationHistory")
  @GetMapping(ONE + "/history")
  @PlatformScoped(operation = "partner-application.history")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<HistoryPage> history(
      @PathVariable("reference") String reference,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(review.history(caller(authentication, request), reference, cursor, limit));
  }

  /**
   * Records one due-diligence check result.
   *
   * @param reference opaque reference
   * @param checkCode check code
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the updated check
   */
  @Operation(
      operationId = "updatePartnerApplicationCheck",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation = PartnerApplicationBodies.CheckCommand.class))))
  @PutMapping(ONE + "/checklist/{checkCode}")
  @PlatformScoped(operation = "partner-application.check")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<Check> updateCheck(
      @PathVariable("reference") String reference,
      @PathVariable("checkCode") String checkCode,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = PartnerBodyReader.read(request, json, MAX_BYTES);
    return command(review.updateCheck(caller, reference, checkCode, idempotencyKey, body));
  }

  /**
   * Moves an application to its next status.
   *
   * @param reference opaque reference
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the transition receipt
   */
  @Operation(
      operationId = "transitionPartnerApplication",
      requestBody =
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              required = true,
              content =
                  @io.swagger.v3.oas.annotations.media.Content(
                      mediaType = "application/json",
                      schema =
                          @io.swagger.v3.oas.annotations.media.Schema(
                              implementation =
                                  PartnerApplicationBodies.TransitionCommand.class))))
  @PostMapping(ONE + ":transition")
  @PlatformScoped(operation = "partner-application.transition")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<TransitionReceipt> transition(
      @PathVariable("reference") String reference,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = PartnerBodyReader.read(request, json, MAX_BYTES);
    return command(review.transition(caller, reference, idempotencyKey, body));
  }

  private static <T> ResponseEntity<T> command(IdempotentOperation.Result<T> result) {
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

  private static Caller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new Caller(
        subject, String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

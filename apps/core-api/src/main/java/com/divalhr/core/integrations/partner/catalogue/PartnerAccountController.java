package com.divalhr.core.integrations.partner.catalogue;

import com.divalhr.core.integrations.partner.PartnerReviewService.Caller;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.CreateReceipt;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.HistoryPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.PartnerDetail;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.PartnerPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductDetail;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductHistoryPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductList;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ProductTransitionReceipt;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.ReadyApplicationPage;
import com.divalhr.core.integrations.partner.catalogue.PartnerAccountBodies.TransitionReceipt;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.security.PlatformScoped;
import com.divalhr.core.platform.web.CorrelationId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
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
 * Partner accounts and the product catalogue for platform administrators with exact MFA ({@link
 * PlatformScoped}, MVP-120E, ADR 0044). Subject, platform role and assurance are checked before the
 * path or body is bound; the caller's tenant claim is never read, so a tenant administrator,
 * manager or employee is refused. Every response is {@code Cache-Control: private, no-store}. An
 * unknown or malformed reference is the same 404. No operation here connects a partner, issues a
 * credential or invitation, sends a notification or reads employee data.
 */
@RestController
public class PartnerAccountController {

  /** Base path of the platform routes. */
  public static final String PATH = "/api/v1/platform/partners";

  /**
   * Activation lives outside the partner-resource path space, so no request can be read both as an
   * activation and as a command on a partner whose reference is a literal path segment.
   */
  public static final String ACTIVATIONS = "/api/v1/platform/partner-activations";

  /** Rate-limit bucket of reads. */
  public static final String READ_BUCKET = "partner-account-read";

  /** Rate-limit bucket of commands. */
  public static final String COMMAND_BUCKET = "partner-account-command";

  private static final int SMALL_BODY = 4096;
  private static final int CONTENT_BODY = 32768;
  private static final String CACHE_CONTROL = "private, no-store";
  private static final String ONE = PATH + "/{partnerReference}";
  private static final String PRODUCT = ONE + "/products/{productCode}";

  private final PartnerAccountService service;
  private final JsonMapper json;

  /**
   * Creates the controller.
   *
   * @param service account service
   * @param json JSON mapper
   */
  public PartnerAccountController(PartnerAccountService service, JsonMapper json) {
    this.service = service;
    this.json = json;
  }

  /**
   * Approved applications and their partners.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "listPartnerApplicationsReadyForActivation")
  @GetMapping(ACTIVATIONS + "/ready-applications")
  @PlatformScoped(operation = "partner-account.ready")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<ReadyApplicationPage> listPartnerApplicationsReadyForActivation(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(service.ready(caller(authentication, request), cursor, limit));
  }

  /**
   * Creates the partner of an approved application.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the receipt
   */
  @Operation(
      operationId = "createPartnerFromApplication",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema = @Schema(implementation = PartnerAccountBodies.CreateCommand.class))))
  @PostMapping(ACTIVATIONS + "/from-application/{applicationReference}")
  @PlatformScoped(operation = "partner-account.create")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<CreateReceipt> createPartnerFromApplication(
      @PathVariable("applicationReference") String applicationReference,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = AccountBodyReader.read(request, json, SMALL_BODY);
    return command(service.create(caller, applicationReference, idempotencyKey, body));
  }

  /**
   * The partners.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "listPartners")
  @GetMapping(PATH)
  @PlatformScoped(operation = "partner-account.list")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<PartnerPage> listPartners(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "category", required = false) String category,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(service.list(caller(authentication, request), cursor, limit, status, category));
  }

  /**
   * One partner.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "getPartner")
  @GetMapping(ONE)
  @PlatformScoped(operation = "partner-account.view")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<PartnerDetail> getPartner(
      @PathVariable("partnerReference") String partnerReference,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(service.get(caller(authentication, request), partnerReference));
  }

  /**
   * The history of a partner.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "getPartnerHistory")
  @GetMapping(ONE + "/history")
  @PlatformScoped(operation = "partner-account.history")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<HistoryPage> getPartnerHistory(
      @PathVariable("partnerReference") String partnerReference,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(service.history(caller(authentication, request), partnerReference, cursor, limit));
  }

  /**
   * Activates, suspends, reactivates or closes a partner, or amends its scope.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the receipt
   */
  @Operation(
      operationId = "transitionPartner",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema =
                          @Schema(implementation = PartnerAccountBodies.TransitionCommand.class))))
  @PostMapping(ONE + "/transitions")
  @PlatformScoped(operation = "partner-account.transition")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<TransitionReceipt> transitionPartner(
      @PathVariable("partnerReference") String partnerReference,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = AccountBodyReader.read(request, json, SMALL_BODY);
    return command(service.transition(caller, partnerReference, idempotencyKey, body));
  }

  /**
   * The products of a partner.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "listPartnerProducts")
  @GetMapping(ONE + "/products")
  @PlatformScoped(operation = "partner-account.products")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<ProductList> listPartnerProducts(
      @PathVariable("partnerReference") String partnerReference,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(service.products(caller(authentication, request), partnerReference));
  }

  /**
   * Creates a product with its first draft version.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the receipt
   */
  @Operation(
      operationId = "createPartnerProduct",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema =
                          @Schema(
                              implementation = PartnerAccountBodies.ProductCreateCommand.class))))
  @PostMapping(ONE + "/products")
  @PlatformScoped(operation = "partner-account.product-create")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<ProductDetail> createPartnerProduct(
      @PathVariable("partnerReference") String partnerReference,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = AccountBodyReader.read(request, json, CONTENT_BODY);
    return command(service.createProduct(caller, partnerReference, idempotencyKey, body));
  }

  /**
   * One product with every version.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "getPartnerProduct")
  @GetMapping(PRODUCT)
  @PlatformScoped(operation = "partner-account.product")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<ProductDetail> getPartnerProduct(
      @PathVariable("partnerReference") String partnerReference,
      @PathVariable("productCode") String productCode,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(service.product(caller(authentication, request), partnerReference, productCode));
  }

  /**
   * Saves the draft of a product.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the receipt
   */
  @Operation(
      operationId = "savePartnerProductDraft",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema =
                          @Schema(
                              implementation = PartnerAccountBodies.ProductDraftCommand.class))))
  @PutMapping(PRODUCT + "/draft")
  @PlatformScoped(operation = "partner-account.product-save")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<ProductDetail> savePartnerProductDraft(
      @PathVariable("partnerReference") String partnerReference,
      @PathVariable("productCode") String productCode,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = AccountBodyReader.read(request, json, CONTENT_BODY);
    return command(service.saveDraft(caller, partnerReference, productCode, idempotencyKey, body));
  }

  /**
   * Submits, returns, publishes, pauses, resumes or retires a product version.
   *
   * @param idempotencyKey required idempotency key
   * @param authentication verified platform administrator
   * @param request current request (the raw body is read here, bounded)
   * @return the receipt
   */
  @Operation(
      operationId = "transitionPartnerProduct",
      requestBody =
          @RequestBody(
              required = true,
              content =
                  @Content(
                      mediaType = "application/json",
                      schema =
                          @Schema(
                              implementation =
                                  PartnerAccountBodies.ProductTransitionCommand.class))))
  @PostMapping(PRODUCT + "/transitions")
  @PlatformScoped(operation = "partner-account.product-transition")
  @SubjectRateLimited(bucket = COMMAND_BUCKET)
  public ResponseEntity<ProductTransitionReceipt> transitionPartnerProduct(
      @PathVariable("partnerReference") String partnerReference,
      @PathVariable("productCode") String productCode,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    Caller caller = caller(authentication, request);
    JsonNode body = AccountBodyReader.read(request, json, SMALL_BODY);
    return command(
        service.transitionProduct(caller, partnerReference, productCode, idempotencyKey, body));
  }

  /**
   * The history of a product.
   *
   * @param authentication verified platform administrator
   * @param request current request
   * @return the result
   */
  @Operation(operationId = "getPartnerProductHistory")
  @GetMapping(PRODUCT + "/history")
  @PlatformScoped(operation = "partner-account.product-history")
  @SubjectRateLimited(bucket = READ_BUCKET)
  public ResponseEntity<ProductHistoryPage> getPartnerProductHistory(
      @PathVariable("partnerReference") String partnerReference,
      @PathVariable("productCode") String productCode,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(
        service.productHistory(
            caller(authentication, request), partnerReference, productCode, cursor, limit));
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

package com.divalhr.core.integrations.api;

import com.divalhr.core.integrations.application.WebhookCaller;
import com.divalhr.core.integrations.application.WebhookService;
import com.divalhr.core.integrations.application.WebhookService.Command;
import com.divalhr.core.integrations.application.WebhookViews.DeliveryDetail;
import com.divalhr.core.integrations.application.WebhookViews.DeliveryPage;
import com.divalhr.core.integrations.application.WebhookViews.TestDeliveryResult;
import com.divalhr.core.integrations.application.WebhookViews.WebhookPage;
import com.divalhr.core.integrations.application.WebhookViews.WebhookResult;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.idempotency.IdempotencyKeys;
import com.divalhr.core.platform.ratelimit.SubjectRateLimited;
import com.divalhr.core.platform.ratelimit.TenantRateLimited;
import com.divalhr.core.platform.security.TenantAdminOperation;
import com.divalhr.core.platform.tenancy.TenantContextResolver;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MVP-110: management of signed outbound webhooks, for tenant administrators with privileged MFA
 * and an active membership of the current tenant. Every response is {@code Cache-Control: private,
 * no-store}. The signing secret appears only in the create and rotate responses of the request that
 * made it.
 */
@RestController
public class WebhookController {

  /** Base path. */
  public static final String PATH = "/api/v1/integrations/webhooks";

  private static final String CACHE_CONTROL = "private, no-store";

  private final WebhookService webhooks;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param webhooks webhook service
   * @param tenants verified tenant context
   */
  public WebhookController(WebhookService webhooks, TenantContextResolver tenants) {
    this.webhooks = webhooks;
    this.tenants = tenants;
  }

  /**
   * Creates a webhook.
   *
   * @param idempotencyKey the idempotency key
   * @param body the request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the webhook and its one-time secret
   */
  @Operation(operationId = "createWebhook")
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WebhookService.CREATE)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WebhookService.TENANT_WRITE_BUCKET)
  public ResponseEntity<WebhookResult> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateWebhookRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.CREATED, webhooks.create(caller(authentication, request), idempotencyKey, body));
  }

  /**
   * The tenant's webhooks, newest first.
   *
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listWebhooks")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = WebhookService.READ)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_READ_BUCKET)
  public ResponseEntity<WebhookPage> list(
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(webhooks.list(caller(authentication, request), cursor, limit));
  }

  /**
   * One webhook.
   *
   * @param webhookId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the webhook
   */
  @Operation(operationId = "getWebhook")
  @GetMapping(PATH + "/{webhookId}")
  @TenantAdminOperation(operation = WebhookService.READ)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_READ_BUCKET)
  public ResponseEntity<WebhookResult> get(
      @PathVariable("webhookId") String webhookId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(webhooks.get(caller(authentication, request), webhookId));
  }

  /**
   * Changes the name, endpoint and events.
   *
   * @param webhookId path value
   * @param idempotencyKey the idempotency key
   * @param body the request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the webhook
   */
  @Operation(operationId = "updateWebhook")
  @PatchMapping(path = PATH + "/{webhookId}", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WebhookService.UPDATE)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WebhookService.TENANT_WRITE_BUCKET)
  public ResponseEntity<WebhookResult> update(
      @PathVariable("webhookId") String webhookId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody UpdateWebhookRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        webhooks.update(caller(authentication, request), webhookId, idempotencyKey, body));
  }

  /**
   * Rotates the signing secret.
   *
   * @param webhookId path value
   * @param idempotencyKey the idempotency key
   * @param body the request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the webhook and its one-time new secret
   */
  @Operation(operationId = "rotateWebhookSecret")
  @PostMapping(
      path = PATH + "/{webhookId}/rotate-secret",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WebhookService.ROTATE)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WebhookService.TENANT_WRITE_BUCKET)
  public ResponseEntity<WebhookResult> rotate(
      @PathVariable("webhookId") String webhookId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody WebhookCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        webhooks.rotateSecret(caller(authentication, request), webhookId, idempotencyKey, body));
  }

  /**
   * Disables a webhook.
   *
   * @param webhookId path value
   * @param idempotencyKey the idempotency key
   * @param body the request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the webhook
   */
  @Operation(operationId = "disableWebhook")
  @PostMapping(path = PATH + "/{webhookId}/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WebhookService.DISABLE)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WebhookService.TENANT_WRITE_BUCKET)
  public ResponseEntity<WebhookResult> disable(
      @PathVariable("webhookId") String webhookId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody WebhookCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        webhooks.disable(caller(authentication, request), webhookId, idempotencyKey, body));
  }

  /**
   * Enables a disabled or suspended webhook.
   *
   * @param webhookId path value
   * @param idempotencyKey the idempotency key
   * @param body the request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the webhook
   */
  @Operation(operationId = "enableWebhook")
  @PostMapping(path = PATH + "/{webhookId}/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WebhookService.ENABLE)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = WebhookService.TENANT_WRITE_BUCKET)
  public ResponseEntity<WebhookResult> enable(
      @PathVariable("webhookId") String webhookId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody WebhookCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        webhooks.enable(caller(authentication, request), webhookId, idempotencyKey, body));
  }

  /**
   * Queues one test delivery.
   *
   * @param webhookId path value
   * @param idempotencyKey the idempotency key
   * @param body the request
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the accepted delivery
   */
  @Operation(operationId = "createWebhookTestDelivery")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PostMapping(
      path = PATH + "/{webhookId}/test-deliveries",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = WebhookService.TEST)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<TestDeliveryResult> testDelivery(
      @PathVariable("webhookId") String webhookId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody TestDeliveryRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.ACCEPTED,
        webhooks.testDelivery(caller(authentication, request), webhookId, idempotencyKey, body));
  }

  /**
   * A webhook's delivery history, newest first.
   *
   * @param webhookId path value
   * @param cursor opaque cursor, or {@code null}
   * @param limit page size, or {@code null}
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the page
   */
  @Operation(operationId = "listWebhookDeliveries")
  @GetMapping(PATH + "/{webhookId}/deliveries")
  @TenantAdminOperation(operation = WebhookService.DELIVERY_READ)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DeliveryPage> deliveries(
      @PathVariable("webhookId") String webhookId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(webhooks.deliveries(caller(authentication, request), webhookId, cursor, limit));
  }

  /**
   * One delivery with its attempts.
   *
   * @param webhookId path value
   * @param deliveryId path value
   * @param authentication verified tenant administrator
   * @param request current request
   * @return the delivery
   */
  @Operation(operationId = "getWebhookDelivery")
  @GetMapping(PATH + "/{webhookId}/deliveries/{deliveryId}")
  @TenantAdminOperation(operation = WebhookService.DELIVERY_READ)
  @SubjectRateLimited(bucket = WebhookService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DeliveryDetail> delivery(
      @PathVariable("webhookId") String webhookId,
      @PathVariable("deliveryId") String deliveryId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(webhooks.delivery(caller(authentication, request), webhookId, deliveryId));
  }

  private static <T> ResponseEntity<T> command(HttpStatus status, Command<T> result) {
    ResponseEntity.BodyBuilder response =
        ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
    if (result.replayed()) {
      response.header(IdempotencyKeys.REPLAYED_HEADER, "true");
    }
    return response.body(result.body());
  }

  private static <T> ResponseEntity<T> ok(T body) {
    return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
  }

  private WebhookCaller caller(JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new WebhookCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

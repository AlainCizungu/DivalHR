package com.divalhr.core.integrations.messaging.api;

import com.divalhr.core.integrations.messaging.application.MessagingCaller;
import com.divalhr.core.integrations.messaging.application.MessagingService;
import com.divalhr.core.integrations.messaging.application.MessagingService.Command;
import com.divalhr.core.integrations.messaging.application.MessagingViews.ConnectorView;
import com.divalhr.core.integrations.messaging.application.MessagingViews.DeliveryDetail;
import com.divalhr.core.integrations.messaging.application.MessagingViews.DeliveryPage;
import com.divalhr.core.integrations.messaging.application.MessagingViews.RecipientList;
import com.divalhr.core.integrations.messaging.application.MessagingViews.RecipientView;
import com.divalhr.core.integrations.messaging.application.MessagingViews.TestDeliveryResult;
import com.divalhr.core.integrations.messaging.application.MessagingViews.VerificationView;
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
 * MVP-111: management of the tenant's WhatsApp Business connector, for tenant administrators with
 * privileged MFA and an active membership of the current tenant. Every response is {@code
 * Cache-Control: private, no-store}. The access token is accepted on connect and rotate only and
 * never returned; a phone number is only ever shown masked.
 */
@RestController
public class MessagingController {

  /** Base path. */
  public static final String PATH = "/api/v1/integrations/messaging/whatsapp";

  static final String CACHE_CONTROL = "private, no-store";

  private final MessagingService messaging;
  private final TenantContextResolver tenants;

  /**
   * Creates the controller.
   *
   * @param messaging messaging service
   * @param tenants verified tenant context
   */
  public MessagingController(MessagingService messaging, TenantContextResolver tenants) {
    this.messaging = messaging;
    this.tenants = tenants;
  }

  /** Connects the tenant's one connector as a draft. */
  @Operation(operationId = "createWhatsAppConnector")
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.CREATE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ConnectorView> create(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateConnectorRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.CREATED,
        messaging.create(caller(authentication, request), idempotencyKey, body));
  }

  /** The tenant's connector. */
  @Operation(operationId = "getWhatsAppConnector")
  @GetMapping(PATH)
  @TenantAdminOperation(operation = MessagingService.READ)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_READ_BUCKET)
  public ResponseEntity<ConnectorView> connector(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(messaging.connector(caller(authentication, request)));
  }

  /** Changes identifiers and templates (returns the connector to draft). */
  @Operation(operationId = "updateWhatsAppConnector")
  @PatchMapping(path = PATH, consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.UPDATE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ConnectorView> update(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody UpdateConnectorRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK, messaging.update(caller(authentication, request), idempotencyKey, body));
  }

  /** Verifies credential and templates with the provider and activates. */
  @Operation(operationId = "verifyWhatsAppConnector")
  @PostMapping(path = PATH + "/verify", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.VERIFY)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<VerificationView> verify(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ConnectorCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK, messaging.verify(caller(authentication, request), idempotencyKey, body));
  }

  /** Rotates the access token. */
  @Operation(operationId = "rotateWhatsAppToken")
  @PostMapping(path = PATH + "/rotate-token", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.ROTATE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ConnectorView> rotateToken(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RotateTokenRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        messaging.rotateToken(caller(authentication, request), idempotencyKey, body));
  }

  /** Disables the connector. */
  @Operation(operationId = "disableWhatsAppConnector")
  @PostMapping(path = PATH + "/disable", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.DISABLE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ConnectorView> disable(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ConnectorCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK, messaging.disable(caller(authentication, request), idempotencyKey, body));
  }

  /** Enables a disabled or suspended connector. */
  @Operation(operationId = "enableWhatsAppConnector")
  @PostMapping(path = PATH + "/enable", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.ENABLE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<ConnectorView> enable(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody ConnectorCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK, messaging.enable(caller(authentication, request), idempotencyKey, body));
  }

  /** The recipients (masked numbers). */
  @Operation(operationId = "listWhatsAppRecipients")
  @GetMapping(PATH + "/recipients")
  @TenantAdminOperation(operation = MessagingService.READ)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RecipientList> recipients(
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(messaging.recipients(caller(authentication, request)));
  }

  /** Registers an opted-in recipient. */
  @Operation(operationId = "createWhatsAppRecipient")
  @ResponseStatus(HttpStatus.CREATED)
  @PostMapping(path = PATH + "/recipients", consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.RECIPIENT_CREATE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RecipientView> createRecipient(
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody CreateRecipientRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.CREATED,
        messaging.createRecipient(caller(authentication, request), idempotencyKey, body));
  }

  /** One recipient. */
  @Operation(operationId = "getWhatsAppRecipient")
  @GetMapping(PATH + "/recipients/{recipientId}")
  @TenantAdminOperation(operation = MessagingService.READ)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_READ_BUCKET)
  public ResponseEntity<RecipientView> recipient(
      @PathVariable("recipientId") String recipientId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(messaging.recipient(caller(authentication, request), recipientId));
  }

  /** Changes label, language or notification types. */
  @Operation(operationId = "updateWhatsAppRecipient")
  @PatchMapping(
      path = PATH + "/recipients/{recipientId}",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.RECIPIENT_UPDATE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RecipientView> updateRecipient(
      @PathVariable("recipientId") String recipientId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody UpdateRecipientRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        messaging.updateRecipient(
            caller(authentication, request), recipientId, idempotencyKey, body));
  }

  /** Disables a recipient. */
  @Operation(operationId = "disableWhatsAppRecipient")
  @PostMapping(
      path = PATH + "/recipients/{recipientId}/disable",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.RECIPIENT_DISABLE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RecipientView> disableRecipient(
      @PathVariable("recipientId") String recipientId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RecipientCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        messaging.disableRecipient(
            caller(authentication, request), recipientId, idempotencyKey, body));
  }

  /** Enables a recipient. */
  @Operation(operationId = "enableWhatsAppRecipient")
  @PostMapping(
      path = PATH + "/recipients/{recipientId}/enable",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.RECIPIENT_ENABLE)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  @TenantRateLimited(bucket = MessagingService.TENANT_WRITE_BUCKET)
  public ResponseEntity<RecipientView> enableRecipient(
      @PathVariable("recipientId") String recipientId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody RecipientCommandRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.OK,
        messaging.enableRecipient(
            caller(authentication, request), recipientId, idempotencyKey, body));
  }

  /** Queues one test message (3 per recipient and 10 per tenant per hour). */
  @Operation(operationId = "createWhatsAppTestDelivery")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @PostMapping(
      path = PATH + "/recipients/{recipientId}/test-deliveries",
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @TenantAdminOperation(operation = MessagingService.TEST)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_WRITE_BUCKET)
  public ResponseEntity<TestDeliveryResult> testDelivery(
      @PathVariable("recipientId") String recipientId,
      @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
      @RequestBody MessagingTestRequest body,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return command(
        HttpStatus.ACCEPTED,
        messaging.testDelivery(caller(authentication, request), recipientId, idempotencyKey, body));
  }

  /** Delivery history, newest first. */
  @Operation(operationId = "listWhatsAppDeliveries")
  @GetMapping(PATH + "/deliveries")
  @TenantAdminOperation(operation = MessagingService.DELIVERY_READ)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DeliveryPage> deliveries(
      @RequestParam(name = "recipientId", required = false) String recipientId,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "limit", required = false) String limit,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(messaging.deliveries(caller(authentication, request), recipientId, cursor, limit));
  }

  /** One delivery with attempts and provider statuses. */
  @Operation(operationId = "getWhatsAppDelivery")
  @GetMapping(PATH + "/deliveries/{deliveryId}")
  @TenantAdminOperation(operation = MessagingService.DELIVERY_READ)
  @SubjectRateLimited(bucket = MessagingService.SUBJECT_READ_BUCKET)
  public ResponseEntity<DeliveryDetail> delivery(
      @PathVariable("deliveryId") String deliveryId,
      @Parameter(hidden = true) JwtAuthenticationToken authentication,
      @Parameter(hidden = true) HttpServletRequest request) {
    return ok(messaging.delivery(caller(authentication, request), deliveryId));
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

  private MessagingCaller caller(
      JwtAuthenticationToken authentication, HttpServletRequest request) {
    String subject = authentication.getToken().getSubject();
    if (subject == null || subject.isBlank()) {
      throw new ApiException(ErrorCode.ACCESS_DENIED, Map.of());
    }
    return new MessagingCaller(
        tenants.current().tenantId(),
        subject,
        String.valueOf(request.getAttribute(CorrelationId.REQUEST_ATTRIBUTE)));
  }
}

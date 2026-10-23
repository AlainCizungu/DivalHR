package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.api.CreateWebhookRequest;
import com.divalhr.core.integrations.api.TestDeliveryRequest;
import com.divalhr.core.integrations.api.UpdateWebhookRequest;
import com.divalhr.core.integrations.api.WebhookCommandRequest;
import com.divalhr.core.integrations.application.WebhookViews.AttemptItem;
import com.divalhr.core.integrations.application.WebhookViews.DeliveryDetail;
import com.divalhr.core.integrations.application.WebhookViews.DeliveryPage;
import com.divalhr.core.integrations.application.WebhookViews.DeliveryView;
import com.divalhr.core.integrations.application.WebhookViews.TestDeliveryResult;
import com.divalhr.core.integrations.application.WebhookViews.WebhookPage;
import com.divalhr.core.integrations.application.WebhookViews.WebhookResult;
import com.divalhr.core.integrations.application.WebhookViews.WebhookView;
import com.divalhr.core.integrations.domain.Delivery;
import com.divalhr.core.integrations.domain.FailureClass;
import com.divalhr.core.integrations.domain.PublicEvent;
import com.divalhr.core.integrations.domain.SecretCipher;
import com.divalhr.core.integrations.domain.Webhook;
import com.divalhr.core.integrations.domain.WebhookEventType;
import com.divalhr.core.integrations.domain.WebhookStatus;
import com.divalhr.core.integrations.domain.WebhookUrl;
import com.divalhr.core.integrations.internal.JdbcWebhookRepository;
import com.divalhr.core.platform.audit.AuditEvent;
import com.divalhr.core.platform.audit.AuditRecorder;
import com.divalhr.core.platform.error.ApiException;
import com.divalhr.core.platform.error.ErrorCode;
import com.divalhr.core.platform.error.FieldErrors;
import com.divalhr.core.platform.error.FieldErrors.Constraint;
import com.divalhr.core.platform.idempotency.Fingerprints;
import com.divalhr.core.platform.idempotency.IdempotentOperation;
import com.divalhr.core.platform.observability.OperationMetrics.Outcome;
import com.divalhr.core.platform.outbox.EventEnvelope;
import com.divalhr.core.platform.outbox.OutboxWriter;
import com.divalhr.core.platform.pagination.CursorCodec;
import com.divalhr.core.platform.pagination.CursorScope;
import com.divalhr.core.platform.pagination.KeysetPosition;
import com.divalhr.core.platform.ratelimit.RateLimitedException;
import com.divalhr.core.platform.ratelimit.TenantRateLimitedException;
import com.divalhr.core.platform.ratelimit.TenantRateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Management of outbound webhooks (MVP-110, ADR 0028): create, read, change, rotate the secret,
 * disable, enable, send a test delivery and read delivery history. Commands are idempotent and run
 * state, audit, outbox event and idempotency record in one transaction; reads are fail-closed
 * disclosures. Nothing here logs or audits an address, a secret or a payload.
 */
@Service
public class WebhookService {

  /** Create (idempotency operation, interceptor operation and audit action). */
  public static final String CREATE = "webhook.create";

  /** Update (idempotency operation, interceptor operation and audit action). */
  public static final String UPDATE = "webhook.update";

  /** Secret rotation (idempotency operation, interceptor operation and audit action). */
  public static final String ROTATE = "webhook.rotate-secret";

  /** Disable (idempotency operation, interceptor operation and audit action). */
  public static final String DISABLE = "webhook.disable";

  /** Enable (idempotency operation, interceptor operation and audit action). */
  public static final String ENABLE = "webhook.enable";

  /** Test delivery (idempotency operation, interceptor operation and audit action). */
  public static final String TEST = "webhook.test-delivery";

  /** Webhook list and detail (interceptor operation and disclosure audit action). */
  public static final String READ = "webhook.read";

  /** Delivery history and detail (interceptor operation and disclosure audit action). */
  public static final String DELIVERY_READ = "webhook.delivery-read";

  /** Subject bucket of reads. */
  public static final String SUBJECT_READ_BUCKET = "webhook-read";

  /** Subject bucket of commands. */
  public static final String SUBJECT_WRITE_BUCKET = "webhook-write";

  /** Tenant bucket of commands. */
  public static final String TENANT_WRITE_BUCKET = "webhook-write";

  /** Tenant bucket of accepted test deliveries (20 per hour). */
  public static final String TENANT_TEST_BUCKET = "webhook-test-delivery";

  /** How long the previous secret keeps signing after a rotation (D110-4). */
  public static final Duration SECRET_OVERLAP = Duration.ofHours(24);

  private static final String SOURCE = "core-api/integrations";
  private static final int SCHEMA_VERSION = 1;
  private static final Duration TRANSACTION_TIMEOUT = Duration.ofSeconds(30);
  private static final int PER_WEBHOOK_TEST_LIMIT = 5;
  private static final Logger LOG = LoggerFactory.getLogger(WebhookService.class);
  private static final Pattern LIMIT = Pattern.compile("^[0-9]{1,3}$");
  private static final Pattern UUID_SHAPE =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private static final IdempotentOperation.Spec CREATE_SPEC =
      new IdempotentOperation.Spec(CREATE, "webhook", "create", "created", 201);
  private static final IdempotentOperation.Spec UPDATE_SPEC =
      new IdempotentOperation.Spec(UPDATE, "webhook", "update", "updated", 200);
  private static final IdempotentOperation.Spec ROTATE_SPEC =
      new IdempotentOperation.Spec(ROTATE, "webhook_secret", "rotate", "rotated", 200);
  private static final IdempotentOperation.Spec DISABLE_SPEC =
      new IdempotentOperation.Spec(DISABLE, "webhook", "disable", "disabled", 200);
  private static final IdempotentOperation.Spec ENABLE_SPEC =
      new IdempotentOperation.Spec(ENABLE, "webhook", "enable", "enabled", 200);
  private static final IdempotentOperation.Spec TEST_SPEC =
      new IdempotentOperation.Spec(TEST, "webhook_test_delivery", "create", "created", 202);

  private final IdempotentOperation operations;
  private final JdbcWebhookRepository webhooks;
  private final WebhookSecrets secrets;
  private final DestinationGuard guard;
  private final IntegrationProperties properties;
  private final TenantRateLimiter tenantLimiter;
  private final CursorCodec cursors;
  private final AuditRecorder audit;
  private final OutboxWriter outbox;
  private final Clock clock;
  private final PlatformTransactionManager transactions;

  /**
   * Creates the service.
   *
   * @param operations shared idempotent command flow
   * @param webhooks storage
   * @param secrets secret sealing
   * @param guard destination validation
   * @param properties settings
   * @param tenantLimiter tenant fixed-window limiter
   * @param cursors signed cursors
   * @param audit audit recorder
   * @param outbox outbox writer
   * @param clock the platform clock
   * @param transactions transaction manager
   */
  public WebhookService(
      IdempotentOperation operations,
      JdbcWebhookRepository webhooks,
      WebhookSecrets secrets,
      DestinationGuard guard,
      IntegrationProperties properties,
      TenantRateLimiter tenantLimiter,
      CursorCodec cursors,
      AuditRecorder audit,
      OutboxWriter outbox,
      Clock clock,
      PlatformTransactionManager transactions) {
    this.operations = operations;
    this.webhooks = webhooks;
    this.secrets = secrets;
    this.guard = guard;
    this.properties = properties;
    this.tenantLimiter = tenantLimiter;
    this.cursors = cursors;
    this.audit = audit;
    this.outbox = outbox;
    this.clock = clock;
    this.transactions = transactions;
  }

  // ------------------------------------------------------------------------------------------
  // Commands
  // ------------------------------------------------------------------------------------------

  /**
   * Creates a webhook; the signing secret is in the response of this request only.
   *
   * @param caller verified tenant administrator
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the result; {@code replayed} requests carry no secret
   */
  public Command<WebhookResult> create(
      WebhookCaller caller, String idempotencyKey, CreateWebhookRequest body) {
    var command =
        operations.validated(
            CREATE_SPEC, () -> WebhookCommand.create(idempotencyKey, body, properties::parseUrl));
    checkDestination(command.configuration().url());
    String[] issued = new String[1];
    var result =
        operations.execute(
            CREATE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(caller.tenant().value().toString()),
            WebhookResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              UUID id = UUID.randomUUID();
              byte[] secret = SecretCipher.newSigningSecret();
              try {
                webhooks.insert(
                    caller.tenant(),
                    id,
                    command.configuration().name(),
                    command.configuration().url().normalized(),
                    command.configuration().events(),
                    caller.subject(),
                    now,
                    secrets.seal(secret, caller.tenant().value(), id, 1));
              } catch (DataIntegrityViolationException violated) {
                throw mapped(violated);
              }
              Webhook created = webhooks.find(caller.tenant(), id, false).orElseThrow();
              record(caller, CREATE, id, now, metadata(created, "CREATED"), created);
              emit(caller, "integrations.webhook.created.v1", created, now);
              issued[0] = encode(secret);
              return new IdempotentOperation.Completed<>(
                  new WebhookResult(WebhookView.of(created), null, null), id, Outcome.CREATED);
            });
    return finish(result, issued[0]);
  }

  /**
   * Changes name, endpoint and events.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the result
   */
  public Command<WebhookResult> update(
      WebhookCaller caller, String webhookId, String idempotencyKey, UpdateWebhookRequest body) {
    var command =
        operations.validated(
            UPDATE_SPEC, () -> WebhookCommand.update(idempotencyKey, body, properties::parseUrl));
    UUID id = id(webhookId);
    checkDestination(command.configuration().url());
    var result =
        operations.execute(
            UPDATE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(caller.tenant().value().toString(), id),
            WebhookResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Webhook current = lock(caller, id);
              if (current.configVersion() != command.configVersion()) {
                throw new ApiException(ErrorCode.WEBHOOK_VERSION_STALE, Map.of());
              }
              boolean changed =
                  !current.name().equals(command.configuration().name())
                      || !current.url().equals(command.configuration().url().normalized())
                      || !current.eventTypes().equals(command.configuration().events());
              if (!changed) {
                return new IdempotentOperation.Completed<>(
                    new WebhookResult(WebhookView.of(current), null, null), id, Outcome.UNCHANGED);
              }
              if (!webhooks.updateConfiguration(
                  caller.tenant(),
                  id,
                  command.configVersion(),
                  command.configuration().name(),
                  command.configuration().url().normalized(),
                  command.configuration().events(),
                  caller.subject(),
                  now)) {
                throw new ApiException(ErrorCode.WEBHOOK_VERSION_STALE, Map.of());
              }
              Webhook updated = webhooks.find(caller.tenant(), id, false).orElseThrow();
              record(caller, UPDATE, id, now, metadata(updated, "CONFIGURATION"), updated);
              emit(caller, "integrations.webhook.updated.v1", updated, now);
              return new IdempotentOperation.Completed<>(
                  new WebhookResult(WebhookView.of(updated), null, null), id, Outcome.UPDATED);
            });
    return finish(result, null);
  }

  /**
   * Rotates the signing secret: the new secret signs at once and the previous one keeps signing for
   * 24 hours.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the result; {@code replayed} requests carry no secret
   */
  public Command<WebhookResult> rotateSecret(
      WebhookCaller caller, String webhookId, String idempotencyKey, WebhookCommandRequest body) {
    var command =
        operations.validated(ROTATE_SPEC, () -> WebhookCommand.versioned(idempotencyKey, body));
    UUID id = id(webhookId);
    String[] issued = new String[1];
    var result =
        operations.execute(
            ROTATE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(caller.tenant().value().toString(), id, "rotate-secret"),
            WebhookResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Webhook current = lock(caller, id);
              if (current.configVersion() != command.configVersion()) {
                throw new ApiException(ErrorCode.WEBHOOK_VERSION_STALE, Map.of());
              }
              byte[] secret = SecretCipher.newSigningSecret();
              int version = current.secretVersion() + 1;
              Instant expiry = now.plus(SECRET_OVERLAP);
              webhooks.rotateSecret(
                  caller.tenant(),
                  id,
                  version,
                  secrets.seal(secret, caller.tenant().value(), id, version),
                  expiry,
                  caller.subject(),
                  now);
              Webhook rotated = webhooks.find(caller.tenant(), id, false).orElseThrow();
              record(caller, ROTATE, id, now, metadata(rotated, "SECRET"), rotated);
              emit(caller, "integrations.webhook.secret-rotated.v1", rotated, now);
              issued[0] = encode(secret);
              return new IdempotentOperation.Completed<>(
                  new WebhookResult(WebhookView.of(rotated), null, expiry), id, Outcome.UPDATED);
            });
    return finish(result, issued[0]);
  }

  /**
   * Disables a webhook; open deliveries are cancelled and history stays readable.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the result
   */
  public Command<WebhookResult> disable(
      WebhookCaller caller, String webhookId, String idempotencyKey, WebhookCommandRequest body) {
    return stop(caller, webhookId, idempotencyKey, body);
  }

  private Command<WebhookResult> stop(
      WebhookCaller caller, String webhookId, String idempotencyKey, WebhookCommandRequest body) {
    var command =
        operations.validated(DISABLE_SPEC, () -> WebhookCommand.versioned(idempotencyKey, body));
    UUID id = id(webhookId);
    var result =
        operations.execute(
            DISABLE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(caller.tenant().value().toString(), id, "disable"),
            WebhookResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Webhook current = lock(caller, id);
              if (current.status() == WebhookStatus.DISABLED) {
                throw new ApiException(ErrorCode.WEBHOOK_ALREADY_DISABLED, Map.of());
              }
              if (current.configVersion() != command.configVersion()) {
                throw new ApiException(ErrorCode.WEBHOOK_VERSION_STALE, Map.of());
              }
              webhooks.setStatus(
                  caller.tenant(), id, WebhookStatus.DISABLED, caller.subject(), now);
              webhooks.cancelOpenDeliveries(caller.tenant(), id, now);
              Webhook disabled = webhooks.find(caller.tenant(), id, false).orElseThrow();
              record(caller, DISABLE, id, now, metadata(disabled, "STATUS"), disabled);
              emit(caller, "integrations.webhook.disabled.v1", disabled, now);
              return new IdempotentOperation.Completed<>(
                  new WebhookResult(WebhookView.of(disabled), null, null), id, Outcome.UPDATED);
            });
    return finish(result, null);
  }

  /**
   * Enables a disabled or suspended webhook: the failure counter restarts and older failed
   * deliveries are not replayed.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the result
   */
  public Command<WebhookResult> enable(
      WebhookCaller caller, String webhookId, String idempotencyKey, WebhookCommandRequest body) {
    var command =
        operations.validated(ENABLE_SPEC, () -> WebhookCommand.versioned(idempotencyKey, body));
    UUID id = id(webhookId);
    var result =
        operations.execute(
            ENABLE_SPEC,
            caller.subject(),
            idempotencyKey,
            command.canonical(caller.tenant().value().toString(), id, "enable"),
            WebhookResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Webhook current = lock(caller, id);
              if (current.status() == WebhookStatus.ACTIVE) {
                throw new ApiException(ErrorCode.WEBHOOK_ALREADY_ENABLED, Map.of());
              }
              if (current.configVersion() != command.configVersion()) {
                throw new ApiException(ErrorCode.WEBHOOK_VERSION_STALE, Map.of());
              }
              webhooks.setStatus(caller.tenant(), id, WebhookStatus.ACTIVE, caller.subject(), now);
              Webhook enabled = webhooks.find(caller.tenant(), id, false).orElseThrow();
              record(caller, ENABLE, id, now, metadata(enabled, "STATUS"), enabled);
              emit(caller, "integrations.webhook.enabled.v1", enabled, now);
              return new IdempotentOperation.Completed<>(
                  new WebhookResult(WebhookView.of(enabled), null, null), id, Outcome.UPDATED);
            });
    return finish(result, null);
  }

  /**
   * Queues one {@code webhook.test.v1} delivery. Both limits answer the same {@code
   * WEBHOOK_TEST_RATE_LIMITED}; they are checked only after authorization, validation and the state
   * checks, and a replay of the same key is answered without counting again.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param idempotencyKey {@code Idempotency-Key} header
   * @param body the request
   * @return the accepted delivery
   */
  public Command<TestDeliveryResult> testDelivery(
      WebhookCaller caller, String webhookId, String idempotencyKey, TestDeliveryRequest body) {
    operations.validated(
        TEST_SPEC,
        () -> {
          WebhookCommand.test(idempotencyKey, body);
          return Boolean.TRUE;
        });
    UUID id = id(webhookId);
    var result =
        operations.execute(
            TEST_SPEC,
            caller.subject(),
            idempotencyKey,
            Map.of(
                "tenantId",
                caller.tenant().value().toString(),
                "webhookId",
                id.toString(),
                "action",
                "test-delivery",
                "confirm",
                true),
            TestDeliveryResult.class,
            TRANSACTION_TIMEOUT,
            () -> {
              Instant now = Instant.now(clock);
              Webhook current = lock(caller, id);
              switch (current.status()) {
                case DISABLED -> throw new ApiException(ErrorCode.WEBHOOK_DISABLED, Map.of());
                case SUSPENDED -> throw new ApiException(ErrorCode.WEBHOOK_SUSPENDED, Map.of());
                default -> {
                  // active
                }
              }
              if (!secrets.available()) {
                throw new ApiException(ErrorCode.WEBHOOK_SECRET_UNAVAILABLE, Map.of());
              }
              if (webhooks.countTestDeliveriesLastHour(caller.tenant(), id, now)
                  >= PER_WEBHOOK_TEST_LIMIT) {
                throw new RateLimitedException(ErrorCode.WEBHOOK_TEST_RATE_LIMITED, 3600);
              }
              try {
                tenantLimiter.acquire(TENANT_TEST_BUCKET, caller.tenant());
              } catch (TenantRateLimitedException limited) {
                throw new RateLimitedException(
                    ErrorCode.WEBHOOK_TEST_RATE_LIMITED, limited.retryAfterSeconds());
              }
              UUID deliveryId = UUID.randomUUID();
              UUID eventId = UUID.randomUUID();
              PublicEvent event =
                  new PublicEvent(
                      eventId, WebhookEventType.WEBHOOK_TEST, now, caller.tenant().value(), id, 1);
              try {
                webhooks.insertDelivery(
                    caller.tenant(),
                    deliveryId,
                    id,
                    eventId,
                    WebhookEventType.WEBHOOK_TEST.code(),
                    "TEST",
                    event.canonicalBytes(),
                    now);
              } catch (DataIntegrityViolationException violated) {
                throw mapped(violated);
              }
              Map<String, Object> metadata = metadata(current, "TEST_DELIVERY");
              record(caller, TEST, id, now, metadata, current);
              return new IdempotentOperation.Completed<>(
                  new TestDeliveryResult(deliveryId, id, eventId), deliveryId, Outcome.CREATED);
            });
    return new Command<>(result.body(), result.replayed());
  }

  // ------------------------------------------------------------------------------------------
  // Reads (fail-closed disclosures)
  // ------------------------------------------------------------------------------------------

  /**
   * One page of the tenant's webhooks, newest first.
   *
   * @param caller verified tenant administrator
   * @param cursor opaque cursor or null
   * @param limit page size or null
   * @return the page, after its disclosure audit committed
   */
  public WebhookPage list(WebhookCaller caller, String cursor, String limit) {
    return observed(
        READ,
        () -> {
          int size = pageSize(limit);
          CursorScope scope = new CursorScope(READ, caller.tenant(), Map.of());
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          return read(
              () -> {
                List<Webhook> rows =
                    webhooks.page(
                        caller.tenant(),
                        after == null ? null : fromCode(after.code()),
                        after == null ? null : after.id(),
                        size + 1);
                boolean more = rows.size() > size;
                List<Webhook> page = more ? rows.subList(0, size) : rows;
                disclose(caller, READ, "webhook-list", caller.tenant().value(), page.size());
                String next =
                    more
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                toCode(page.get(size - 1).createdAt()), page.get(size - 1).id()))
                        : null;
                return new WebhookPage(page.stream().map(WebhookView::of).toList(), next);
              });
        });
  }

  /**
   * One webhook.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @return the webhook, after its disclosure audit committed
   */
  public WebhookResult get(WebhookCaller caller, String webhookId) {
    return observed(
        READ,
        () -> {
          UUID id = id(webhookId);
          return read(
              () -> {
                Webhook found =
                    webhooks
                        .find(caller.tenant(), id, false)
                        .orElseThrow(() -> new ApiException(ErrorCode.WEBHOOK_NOT_FOUND, Map.of()));
                disclose(caller, READ, "webhook-detail", id, 1);
                Instant previous =
                    webhooks.signingSecrets(caller.tenant(), id, Instant.now(clock)).stream()
                        .map(JdbcWebhookRepository.StoredSecret::expiresAt)
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                return new WebhookResult(WebhookView.of(found), null, previous);
              });
        });
  }

  /**
   * One page of a webhook's delivery history, newest first.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param cursor opaque cursor or null
   * @param limit page size or null
   * @return the page, after its disclosure audit committed
   */
  public DeliveryPage deliveries(
      WebhookCaller caller, String webhookId, String cursor, String limit) {
    return observed(
        DELIVERY_READ,
        () -> {
          UUID id = id(webhookId);
          int size = pageSize(limit);
          CursorScope scope =
              new CursorScope(DELIVERY_READ, caller.tenant(), Map.of("webhookId", id.toString()));
          KeysetPosition after = cursor == null ? null : cursors.decode(cursor, scope);
          return read(
              () -> {
                requireExists(caller, id);
                List<Delivery> rows =
                    webhooks.deliveries(
                        caller.tenant(),
                        id,
                        after == null ? null : fromCode(after.code()),
                        after == null ? null : after.id(),
                        size + 1);
                boolean more = rows.size() > size;
                List<Delivery> page = more ? rows.subList(0, size) : rows;
                disclose(caller, DELIVERY_READ, "delivery-list", id, page.size());
                String next =
                    more
                        ? cursors.encode(
                            scope,
                            new KeysetPosition(
                                toCode(page.get(size - 1).createdAt()), page.get(size - 1).id()))
                        : null;
                return new DeliveryPage(page.stream().map(DeliveryView::of).toList(), next);
              });
        });
  }

  /**
   * One delivery with its attempts.
   *
   * @param caller verified tenant administrator
   * @param webhookId path value
   * @param deliveryId path value
   * @return the delivery, after its disclosure audit committed
   */
  public DeliveryDetail delivery(WebhookCaller caller, String webhookId, String deliveryId) {
    return observed(
        DELIVERY_READ,
        () -> {
          UUID id = id(webhookId);
          UUID did = deliveryId(deliveryId);
          return read(
              () -> {
                requireExists(caller, id);
                Delivery found =
                    webhooks
                        .delivery(caller.tenant(), id, did)
                        .orElseThrow(
                            () -> new ApiException(ErrorCode.WEBHOOK_DELIVERY_NOT_FOUND, Map.of()));
                disclose(caller, DELIVERY_READ, "delivery-detail", did, 1);
                return new DeliveryDetail(
                    DeliveryView.of(found),
                    webhooks.attempts(caller.tenant(), did).stream().map(AttemptItem::of).toList());
              });
        });
  }

  // ------------------------------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------------------------------

  /**
   * A command's outcome.
   *
   * @param body the body to return
   * @param replayed whether an earlier response for the key was replayed
   * @param <T> body type
   */
  public record Command<T>(T body, boolean replayed) {}

  private Command<WebhookResult> finish(
      IdempotentOperation.Result<WebhookResult> result, String issuedSecret) {
    if (result.replayed() || issuedSecret == null) {
      return new Command<>(result.body().withoutSecret(), result.replayed());
    }
    return new Command<>(result.body().withSecret(issuedSecret), false);
  }

  private static String encode(byte[] secret) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
  }

  private void checkDestination(WebhookUrl url) {
    if (!secrets.available()) {
      throw new ApiException(ErrorCode.WEBHOOK_SECRET_UNAVAILABLE, Map.of());
    }
    try {
      guard.validated(url);
    } catch (DestinationGuard.Refused refused) {
      throw new ApiException(
          refused.failure() == FailureClass.DNS_BLOCKED
              ? ErrorCode.WEBHOOK_DESTINATION_BLOCKED
              : ErrorCode.WEBHOOK_URL_INVALID,
          Map.of());
    }
  }

  private Webhook lock(WebhookCaller caller, UUID id) {
    return webhooks
        .find(caller.tenant(), id, true)
        .orElseThrow(() -> new ApiException(ErrorCode.WEBHOOK_NOT_FOUND, Map.of()));
  }

  private void requireExists(WebhookCaller caller, UUID id) {
    if (webhooks.find(caller.tenant(), id, false).isEmpty()) {
      throw new ApiException(ErrorCode.WEBHOOK_NOT_FOUND, Map.of());
    }
  }

  /** A malformed or unknown id is the same 404 as a foreign one. */
  private static UUID id(String value) {
    if (value == null || !UUID_SHAPE.matcher(value).matches()) {
      throw new ApiException(ErrorCode.WEBHOOK_NOT_FOUND, Map.of());
    }
    return UUID.fromString(value);
  }

  private static UUID deliveryId(String value) {
    if (value == null || !UUID_SHAPE.matcher(value).matches()) {
      throw new ApiException(ErrorCode.WEBHOOK_DELIVERY_NOT_FOUND, Map.of());
    }
    return UUID.fromString(value);
  }

  private static int pageSize(String limit) {
    if (limit == null) {
      return DEFAULT_LIMIT;
    }
    if (!LIMIT.matcher(limit).matches()
        || Integer.parseInt(limit) < 1
        || Integer.parseInt(limit) > MAX_LIMIT) {
      new FieldErrors().add("limit", Constraint.RANGE).throwIfAny();
    }
    return Integer.parseInt(limit);
  }

  private static String toCode(Instant createdAt) {
    return "G" + (createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1000L);
  }

  private static Instant fromCode(String code) {
    long micros = Long.parseLong(code.substring(1));
    return Instant.ofEpochSecond(
        Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1000L);
  }

  private static ApiException mapped(DataIntegrityViolationException violated) {
    String text = String.valueOf(violated.getMostSpecificCause().getMessage());
    if (text.contains("webhook limit reached")) {
      return new ApiException(ErrorCode.WEBHOOK_LIMIT_REACHED, Map.of());
    }
    if (text.contains("test delivery quota")) {
      return new RateLimitedException(ErrorCode.WEBHOOK_TEST_RATE_LIMITED, 3600);
    }
    throw violated;
  }

  /** Bounded metadata: status, selected event codes and count, secret version, change kind. */
  private static Map<String, Object> metadata(Webhook webhook, String changeKind) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("status", webhook.status().name());
    metadata.put("eventTypes", webhook.eventTypes());
    metadata.put("eventTypeCount", webhook.eventTypes().size());
    metadata.put("secretVersion", webhook.secretVersion());
    metadata.put("changeKind", changeKind);
    return metadata;
  }

  private void record(
      WebhookCaller caller,
      String action,
      UUID id,
      Instant now,
      Map<String, Object> metadata,
      Webhook state) {
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            "webhook",
            id,
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-WEBHOOK-STATE\nversion=1\naction="
                    + action
                    + "\nid="
                    + id
                    + "\nstatus="
                    + state.status()
                    + "\nconfigVersion="
                    + state.configVersion()
                    + "\nsecretVersion="
                    + state.secretVersion()
                    + "\nevents="
                    + state.eventTypes().stream().collect(Collectors.joining(","))
                    + "\n")));
  }

  /** The module's own lifecycle event: identifiers, status and versions only. */
  private void emit(WebhookCaller caller, String type, Webhook webhook, Instant now) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("webhookId", webhook.id().toString());
    data.put("status", webhook.status().name());
    data.put("configVersion", webhook.configVersion());
    data.put("secretVersion", webhook.secretVersion());
    outbox.append(
        new EventEnvelope(
            UUID.randomUUID().toString(),
            type,
            SCHEMA_VERSION,
            caller.tenant().value().toString(),
            SOURCE,
            webhook.id().toString(),
            now.toString(),
            caller.correlationId(),
            null,
            data));
  }

  /** The fail-closed disclosure audit: the view and count; identifiers only in the digest. */
  private void disclose(WebhookCaller caller, String action, String view, UUID target, int count) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("view", view);
    metadata.put("page", "first");
    metadata.put("resultCount", count);
    Instant now = Instant.now(clock);
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            now,
            caller.subject(),
            action,
            "organization",
            caller.tenant().value(),
            caller.tenant().value(),
            "SUCCESS",
            caller.correlationId(),
            metadata,
            Fingerprints.sha256(
                "DIVALHR-WEBHOOK-DISCLOSURE\nversion=1\naction="
                    + action
                    + "\ntarget="
                    + target
                    + "\nby="
                    + caller.subject()
                    + "\n")));
  }

  private <T> T read(Supplier<T> work) {
    TransactionTemplate template = new TransactionTemplate(transactions);
    template.setTimeout((int) TRANSACTION_TIMEOUT.toSeconds());
    T result = template.execute(status -> work.get());
    return java.util.Objects.requireNonNull(result, "read transaction returned nothing");
  }

  /** Logs a read's bounded outcome; rejections log only their code. */
  private <T> T observed(String operation, Supplier<T> work) {
    try {
      T result = work.get();
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", "listed")
          .log("webhook_read");
      return result;
    } catch (ApiException rejected) {
      LOG.atInfo()
          .addKeyValue("operation", operation)
          .addKeyValue("outcome", rejected.code().name().toLowerCase(Locale.ROOT))
          .log("webhook_read_rejected");
      throw rejected;
    }
  }
}

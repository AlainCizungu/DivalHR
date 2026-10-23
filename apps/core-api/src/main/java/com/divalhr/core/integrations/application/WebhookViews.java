package com.divalhr.core.integrations.application;

import com.divalhr.core.integrations.domain.Delivery;
import com.divalhr.core.integrations.domain.Webhook;
import com.divalhr.core.integrations.internal.JdbcWebhookRepository.AttemptView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response shapes of the webhook API (D110-3, D110-10). None carries a secret or a payload. */
public final class WebhookViews {

  private WebhookViews() {}

  /**
   * A webhook as the administrator sees it.
   *
   * @param id webhook id
   * @param name display name
   * @param url endpoint
   * @param eventTypes subscribed events
   * @param status ACTIVE, DISABLED or SUSPENDED
   * @param configVersion optimistic configuration version
   * @param secretVersion current secret version
   * @param consecutiveFailures consecutive terminally failed deliveries
   * @param lastSuccessAt last success
   * @param lastFailureAt last terminal failure
   * @param createdAt created
   * @param updatedAt updated
   */
  public record WebhookView(
      UUID id,
      String name,
      String url,
      List<String> eventTypes,
      String status,
      int configVersion,
      int secretVersion,
      int consecutiveFailures,
      Instant lastSuccessAt,
      Instant lastFailureAt,
      Instant createdAt,
      Instant updatedAt) {
    static WebhookView of(Webhook w) {
      return new WebhookView(
          w.id(),
          w.name(),
          w.url(),
          w.eventTypes(),
          w.status().name(),
          w.configVersion(),
          w.secretVersion(),
          w.consecutiveFailures(),
          w.lastSuccessAt(),
          w.lastFailureAt(),
          w.createdAt(),
          w.updatedAt());
    }
  }

  /**
   * The result of a command. {@code secret} is present exactly once (create, rotate), only in the
   * response to the request that created it, and is never stored or replayed.
   *
   * @param webhook the webhook after the command
   * @param secret the new signing secret (URL-safe base64), or null
   * @param previousSecretExpiresAt when the previous secret stops signing (rotation), or null
   */
  public record WebhookResult(WebhookView webhook, String secret, Instant previousSecretExpiresAt) {
    WebhookResult withoutSecret() {
      return new WebhookResult(webhook, null, previousSecretExpiresAt);
    }

    WebhookResult withSecret(String value) {
      return new WebhookResult(webhook, value, previousSecretExpiresAt);
    }
  }

  /**
   * A page of webhooks.
   *
   * @param items rows
   * @param nextCursor opaque signed cursor, or null
   */
  public record WebhookPage(List<WebhookView> items, String nextCursor) {}

  /**
   * One delivery in history.
   *
   * @param id delivery id
   * @param webhookId webhook
   * @param eventId event id
   * @param eventType public event type
   * @param state PENDING, RETRYING, SUCCEEDED, FAILED or CANCELLED
   * @param attemptCount attempts made
   * @param createdAt created
   * @param lastAttemptAt last attempt
   * @param nextAttemptAt next scheduled attempt
   * @param lastHttpStatus exact status of the last response, when there was one
   * @param lastFailureClass closed failure class of the last attempt
   */
  public record DeliveryView(
      UUID id,
      UUID webhookId,
      UUID eventId,
      String eventType,
      String state,
      int attemptCount,
      Instant createdAt,
      Instant lastAttemptAt,
      Instant nextAttemptAt,
      Integer lastHttpStatus,
      String lastFailureClass) {
    static DeliveryView of(Delivery d) {
      return new DeliveryView(
          d.id(),
          d.webhookId(),
          d.eventId(),
          d.eventType(),
          d.state().name(),
          d.attemptCount(),
          d.createdAt(),
          d.lastAttemptAt(),
          d.nextAttemptAt(),
          d.lastHttpStatus(),
          d.lastFailureClass() == null ? null : d.lastFailureClass().name());
    }
  }

  /**
   * A page of deliveries.
   *
   * @param items rows
   * @param nextCursor opaque signed cursor, or null
   */
  public record DeliveryPage(List<DeliveryView> items, String nextCursor) {}

  /**
   * One attempt in a delivery's detail.
   *
   * @param number attempt number
   * @param startedAt start
   * @param httpStatus exact status when a response arrived
   * @param failureClass closed class, null on success
   * @param latencyMillis wall time
   */
  public record AttemptItem(
      int number, Instant startedAt, Integer httpStatus, String failureClass, int latencyMillis) {
    static AttemptItem of(AttemptView a) {
      return new AttemptItem(
          a.number(),
          a.startedAt(),
          a.httpStatus(),
          a.failureClass() == null ? null : a.failureClass().name(),
          a.latencyMillis());
    }
  }

  /**
   * A delivery with its attempts.
   *
   * @param delivery the delivery
   * @param attempts its attempts, in order
   */
  public record DeliveryDetail(DeliveryView delivery, List<AttemptItem> attempts) {}

  /**
   * An accepted test delivery.
   *
   * @param deliveryId delivery to follow in the history
   * @param webhookId webhook
   * @param eventId test event id
   */
  public record TestDeliveryResult(UUID deliveryId, UUID webhookId, UUID eventId) {}
}

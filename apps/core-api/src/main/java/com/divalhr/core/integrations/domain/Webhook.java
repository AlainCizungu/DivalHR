package com.divalhr.core.integrations.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A webhook as stored (never the secret, never logged with its URL).
 *
 * @param id immutable webhook id
 * @param name display name
 * @param url normalized HTTPS endpoint
 * @param eventTypes subscribed public event types
 * @param status lifecycle status
 * @param configVersion optimistic configuration version
 * @param secretVersion current secret version
 * @param consecutiveFailures consecutive terminally failed deliveries
 * @param lastSuccessAt last successful delivery
 * @param lastFailureAt last terminally failed delivery
 * @param createdAt creation instant
 * @param updatedAt last change instant
 */
public record Webhook(
    UUID id,
    String name,
    String url,
    List<String> eventTypes,
    WebhookStatus status,
    int configVersion,
    int secretVersion,
    int consecutiveFailures,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * @return a representation without the endpoint
   */
  @Override
  public String toString() {
    return "Webhook[" + id + ", " + status + "]";
  }
}

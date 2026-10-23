package com.divalhr.core.integrations.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The public webhook payload (D110-7), serialized once to canonical bytes: fixed property order, no
 * whitespace, UTF-8. Built only from allow-listed fields; nothing of an internal payload is passed
 * through. The shape is the one in {@code webhook-event.schema.json}.
 *
 * @param eventId unique event id (the internal outbox event id, so it is stable for dedupe)
 * @param type catalogue entry
 * @param occurredAt event time
 * @param tenantId tenant
 * @param resourceId affected resource
 * @param version resource version in the public contract
 */
public record PublicEvent(
    UUID eventId,
    WebhookEventType type,
    Instant occurredAt,
    UUID tenantId,
    UUID resourceId,
    int version) {

  /**
   * @return the canonical JSON bytes sent on every attempt
   */
  public byte[] canonicalBytes() {
    StringBuilder json = new StringBuilder(384);
    json.append("{\"schemaVersion\":1,\"eventId\":\"")
        .append(eventId)
        .append("\",\"eventType\":\"")
        .append(type.code())
        .append("\",\"occurredAt\":\"")
        .append(occurredAt)
        .append("\",\"tenantId\":\"")
        .append(tenantId)
        .append("\",\"resource\":{\"kind\":\"")
        .append(type.resourceKind())
        .append("\",\"id\":\"")
        .append(resourceId)
        .append("\",\"status\":\"")
        .append(type.resourceStatus())
        .append("\",\"version\":")
        .append(version)
        .append("},\"links\":[");
    type.link().ifPresent(l -> json.append('"').append(l).append('"'));
    json.append("]}");
    return json.toString().getBytes(StandardCharsets.UTF_8);
  }
}

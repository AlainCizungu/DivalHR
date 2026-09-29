package com.divalhr.core.platform.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.UUID;

/**
 * Business event envelope matching {@code
 * packages/shared-contracts/schemas/event-envelope.schema.json}. {@code causationId} is always
 * serialized, as {@code null} when there is no causing event.
 *
 * @param eventId unique event ID (consumers deduplicate on it)
 * @param eventType namespaced, versioned type
 * @param schemaVersion data schema version
 * @param tenantId tenant scope
 * @param source producing module
 * @param subject affected entity ID; never personal data
 * @param eventTime UTC ISO-8601 time
 * @param correlationId request correlation ID
 * @param causationId causing event ID, or {@code null}
 * @param data event data
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int schemaVersion,
    String tenantId,
    String source,
    String subject,
    String eventTime,
    String correlationId,
    @JsonInclude(JsonInclude.Include.ALWAYS) String causationId,
    Map<String, Object> data) {

  /** Defensively copies data. */
  public EventEnvelope {
    data = Map.copyOf(data);
  }

  /**
   * Parses the tenant ID.
   *
   * @return tenant UUID
   */
  public UUID tenantUuid() {
    return UUID.fromString(tenantId);
  }
}

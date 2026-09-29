package com.divalhr.core.platform.audit;

import com.divalhr.core.platform.operation.OperationName;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An append-only audit record. {@code metadata} holds safe configuration values only: never names,
 * tokens or personal data.
 *
 * @param id audit event ID
 * @param occurredAt UTC time
 * @param actorSubject verified JWT subject
 * @param action fixed action name
 * @param resourceType resource type
 * @param resourceId resource ID
 * @param tenantId tenant the event belongs to
 * @param result SUCCESS, DENIED or FAILURE
 * @param correlationId request correlation ID
 * @param metadata safe change metadata
 * @param afterStateSha256 integrity reference to the persisted state
 */
public record AuditEvent(
    UUID id,
    Instant occurredAt,
    String actorSubject,
    String action,
    String resourceType,
    UUID resourceId,
    UUID tenantId,
    String result,
    String correlationId,
    Map<String, Object> metadata,
    String afterStateSha256) {

  /** Requires a well-formed action and defensively copies metadata. */
  public AuditEvent {
    OperationName.require(action, "audit action");
    metadata = Map.copyOf(metadata);
  }
}

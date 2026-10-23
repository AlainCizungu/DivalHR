package com.divalhr.core.integrations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery as shown in history (no payload, no secret, no response data).
 *
 * @param id delivery id
 * @param webhookId webhook
 * @param eventId event id
 * @param eventType public event type
 * @param state delivery state
 * @param attemptCount attempts made
 * @param createdAt creation instant
 * @param lastAttemptAt last attempt instant
 * @param nextAttemptAt next scheduled attempt
 * @param lastHttpStatus exact status of the last response, when there was one
 * @param lastFailureClass closed failure class of the last attempt
 */
public record Delivery(
    UUID id,
    UUID webhookId,
    UUID eventId,
    String eventType,
    DeliveryState state,
    int attemptCount,
    Instant createdAt,
    Instant lastAttemptAt,
    Instant nextAttemptAt,
    Integer lastHttpStatus,
    FailureClass lastFailureClass) {}

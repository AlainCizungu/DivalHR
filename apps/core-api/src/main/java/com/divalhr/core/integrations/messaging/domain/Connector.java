package com.divalhr.core.integrations.messaging.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The tenant's WhatsApp connector as stored: configuration and a bounded projection, never the
 * token. The Meta identifiers are configuration the administrator typed; they are shown back to
 * administrators only and never appear in audit, logs, metrics, errors or URLs.
 *
 * @param id immutable connector id
 * @param state lifecycle state
 * @param configVersion optimistic configuration version
 * @param credentialVersion current credential version
 * @param wabaId WhatsApp Business Account id
 * @param phoneNumberId sending phone-number id
 * @param displayNumber the sending number as shown to recipients
 * @param consecutiveFailures consecutive terminally failed deliveries
 * @param verifiedAt when the provider last confirmed credential and templates, or null
 * @param lastSuccessAt last delivered or read message
 * @param lastFailureAt last terminal failure
 * @param createdAt creation instant
 * @param updatedAt last change instant
 */
public record Connector(
    UUID id,
    ConnectorState state,
    int configVersion,
    int credentialVersion,
    String wabaId,
    String phoneNumberId,
    String displayNumber,
    int consecutiveFailures,
    Instant verifiedAt,
    Instant lastSuccessAt,
    Instant lastFailureAt,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * @return a representation without the provider identifiers
   */
  @Override
  public String toString() {
    return "Connector[" + id + ", " + state + "]";
  }
}

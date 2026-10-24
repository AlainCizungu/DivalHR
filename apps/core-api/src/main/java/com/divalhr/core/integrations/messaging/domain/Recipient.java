package com.divalhr.core.integrations.messaging.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An opted-in operational recipient as stored. Only the masked number is held in this object; the
 * full number exists as ciphertext in the database and is opened only to send.
 *
 * @param id immutable recipient id
 * @param label administrator-chosen label (1 to 80 plain-text characters)
 * @param numberMasked masked number
 * @param language template language
 * @param notificationTypes selected notification types
 * @param status lifecycle status
 * @param consentSource how the opt-in was obtained
 * @param consentConfirmedAt when the opt-in was confirmed
 * @param version optimistic version
 * @param createdAt creation instant
 * @param updatedAt last change instant
 */
public record Recipient(
    UUID id,
    String label,
    String numberMasked,
    MessagingLanguage language,
    List<NotificationType> notificationTypes,
    RecipientStatus status,
    ConsentSource consentSource,
    Instant consentConfirmedAt,
    int version,
    Instant createdAt,
    Instant updatedAt) {

  /** Copies the list. */
  public Recipient {
    notificationTypes = List.copyOf(notificationTypes);
  }

  /**
   * @return a representation without label or number
   */
  @Override
  public String toString() {
    return "Recipient[" + id + ", " + status + "]";
  }
}

package com.divalhr.core.integrations.messaging.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One notification delivery as stored. It never holds message content, a template parameter, a deep
 * link or the provider message id.
 *
 * @param id delivery id
 * @param recipientId recipient
 * @param recipientLabel the recipient's label at read time
 * @param numberMasked the recipient's masked number
 * @param type notification type
 * @param test whether it is an administrator test
 * @param language template language used
 * @param state delivery state
 * @param attemptCount attempts made
 * @param createdAt creation instant
 * @param lastAttemptAt last attempt instant
 * @param nextAttemptAt next scheduled attempt
 * @param acceptedAt when the provider accepted it
 * @param deliveredAt when the provider reported delivery
 * @param readAt when the provider reported a read
 * @param completedAt when it reached a final state
 * @param lastHttpStatus exact status of the last answer
 * @param failure closed failure class of the last failed attempt
 */
public record MessagingDelivery(
    UUID id,
    UUID recipientId,
    String recipientLabel,
    String numberMasked,
    NotificationType type,
    boolean test,
    MessagingLanguage language,
    DeliveryStatus state,
    int attemptCount,
    Instant createdAt,
    Instant lastAttemptAt,
    Instant nextAttemptAt,
    Instant acceptedAt,
    Instant deliveredAt,
    Instant readAt,
    Instant completedAt,
    Integer lastHttpStatus,
    SendFailure failure) {

  /**
   * @return a representation without label or number
   */
  @Override
  public String toString() {
    return "MessagingDelivery[" + id + ", " + state + "]";
  }
}

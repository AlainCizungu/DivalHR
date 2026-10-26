package com.divalhr.core.integrations.messaging.domain;

/**
 * Delivery states (D111-9). {@code ACCEPTED}, {@code DELIVERED} and {@code READ} are provider
 * statuses and only move forward; a message with a provider message id is never sent again.
 */
public enum DeliveryStatus {
  /** Queued for the first attempt. */
  PENDING,
  /** A retryable failure happened; the next attempt is scheduled. */
  RETRYING,
  /** The provider accepted the message. */
  ACCEPTED,
  /** The provider reported delivery to the handset. */
  DELIVERED,
  /** The provider reported that the message was read. */
  READ,
  /** A terminal failure. */
  FAILED,
  /** Cancelled before sending (connector or recipient stopped). */
  CANCELLED,
  /** The last allowed attempt ended without knowing whether the provider accepted it. */
  STATUS_UNKNOWN;

  /**
   * @return whether the delivery can no longer change
   */
  public boolean isFinal() {
    return this == READ || this == FAILED || this == CANCELLED || this == STATUS_UNKNOWN;
  }

  /**
   * @return whether the delivery has not yet reached the provider
   */
  public boolean isOpen() {
    return this == PENDING || this == RETRYING;
  }

  /**
   * @return the rank of provider statuses (0 for the others), used to keep callbacks monotonic
   */
  public int providerRank() {
    return switch (this) {
      case ACCEPTED -> 1;
      case DELIVERED -> 2;
      case READ -> 3;
      default -> 0;
    };
  }
}

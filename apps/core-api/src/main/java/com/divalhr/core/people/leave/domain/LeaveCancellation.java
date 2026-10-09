package com.divalhr.core.people.leave.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The employee's cancellation of their own pending leave request (MVP-041C, Issue #91): immutable,
 * one per request. The reason is Restricted HR, in the language it was written in; the cancelling
 * subject is stored by the database only and never part of this view.
 *
 * @param id cancellation
 * @param requestId the cancelled request
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason (decision-reason grammar version 1)
 * @param cancelledAt cancellation time
 */
public record LeaveCancellation(
    UUID id, UUID requestId, String reasonLocale, String reason, Instant cancelledAt) {

  /**
   * Identifiers only: the reason never reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveCancellation[id=" + id + ", requestId=" + requestId + "]";
  }
}

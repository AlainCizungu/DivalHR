package com.divalhr.core.people.leave.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The employee's amendment of their own pending leave request by replacement (MVP-041D, Issue #92):
 * immutable, one per original and one per replacement. The reason is Restricted HR, in the language
 * it was written in; the amending subject is stored by the database only and never part of this
 * view.
 *
 * @param id amendment
 * @param originalRequestId the amended (replaced) request
 * @param replacementRequestId the pending request that replaces it
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason (decision-reason grammar version 1)
 * @param amendedAt amendment time
 */
public record LeaveAmendment(
    UUID id,
    UUID originalRequestId,
    UUID replacementRequestId,
    String reasonLocale,
    String reason,
    Instant amendedAt) {

  /**
   * Identifiers only: the reason never reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveAmendment[id="
        + id
        + ", originalRequestId="
        + originalRequestId
        + ", replacementRequestId="
        + replacementRequestId
        + "]";
  }
}

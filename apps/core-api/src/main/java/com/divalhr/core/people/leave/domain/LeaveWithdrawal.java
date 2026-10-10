package com.divalhr.core.people.leave.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The employee's withdrawal of their own approved leave before it starts (MVP-041F, Issue #95):
 * immutable, one per request; the original approval decision is kept beside it. The reason is
 * Restricted HR, in the language it was written in; the withdrawing subject is stored by the
 * database only and never part of this view.
 *
 * @param id withdrawal
 * @param requestId the withdrawn request
 * @param reasonLocale {@code en} or {@code fr}
 * @param reason the reason (decision-reason grammar version 1)
 * @param withdrawnAt withdrawal time
 */
public record LeaveWithdrawal(
    UUID id, UUID requestId, String reasonLocale, String reason, Instant withdrawnAt) {

  /**
   * Identifiers only: the reason never reaches a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "LeaveWithdrawal[id=" + id + ", requestId=" + requestId + "]";
  }
}

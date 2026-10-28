package com.divalhr.core.operations.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One append-only action of an expense's history (MVP-081).
 *
 * @param seq sequence
 * @param action action
 * @param occurredAt instant
 * @param actorRole {@code employee} or {@code manager}
 * @param fromState prior state or {@code null}
 * @param toState resulting state
 * @param revision revision acted on
 * @param receiptPresent whether the revision had a receipt
 * @param managerEmployeeId the routed or deciding manager, or {@code null}
 * @param returnReason return reason or {@code null}
 */
public record ExpenseAction(
    int seq,
    ExpenseActionType action,
    Instant occurredAt,
    String actorRole,
    ExpenseState fromState,
    ExpenseState toState,
    int revision,
    boolean receiptPresent,
    UUID managerEmployeeId,
    String returnReason) {

  @Override
  public String toString() {
    return "ExpenseAction[" + seq + ", " + action + "]";
  }
}

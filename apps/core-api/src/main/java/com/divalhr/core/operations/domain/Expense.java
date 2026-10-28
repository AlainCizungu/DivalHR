package com.divalhr.core.operations.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The current projection of an expense with the facts of its current revision (MVP-081). Merchant
 * and description are Restricted financial data: {@code toString} never shows them.
 *
 * @param id expense
 * @param employeeId the owning employee (immutable)
 * @param state state
 * @param cycle submission cycle (equals the current revision)
 * @param revision current revision
 * @param version optimistic version
 * @param businessDate date of the expense
 * @param currency currency
 * @param amountMinor amount in minor units
 * @param category category
 * @param merchant merchant (restricted plain text)
 * @param description description (restricted plain text)
 * @param receipt the current revision's receipt or {@code null}
 * @param createdAt creation instant
 * @param submittedAt last submission instant or {@code null}
 * @param decidedAt last decision, return or withdrawal instant or {@code null}
 * @param lastActionAt last action instant
 * @param revisionSubmitted whether the current revision is already submitted (immutable)
 * @param routedManagerEmployeeId the manager the last submission was routed to, or {@code null}
 */
public record Expense(
    UUID id,
    UUID employeeId,
    ExpenseState state,
    int cycle,
    int revision,
    int version,
    LocalDate businessDate,
    ExpenseCurrency currency,
    long amountMinor,
    ExpenseCategory category,
    String merchant,
    String description,
    ReceiptInfo receipt,
    Instant createdAt,
    Instant submittedAt,
    Instant decidedAt,
    Instant lastActionAt,
    boolean revisionSubmitted,
    UUID routedManagerEmployeeId) {

  /**
   * Receipt metadata (never the bytes).
   *
   * @param id receipt
   * @param mediaType verified media type
   * @param sizeBytes size
   * @param sha256 hex digest
   * @param fileName display name
   */
  public record ReceiptInfo(
      UUID id, String mediaType, int sizeBytes, String sha256, String fileName) {

    @Override
    public String toString() {
      return "ReceiptInfo[" + id + "]";
    }
  }

  @Override
  public String toString() {
    return "Expense[" + id + ", " + state + ", v" + version + "]";
  }
}

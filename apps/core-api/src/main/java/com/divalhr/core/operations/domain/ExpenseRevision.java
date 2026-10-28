package com.divalhr.core.operations.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One revision of an expense: its business evidence and bound receipt (MVP-081, D81-5, D81-8).
 *
 * @param revision number
 * @param businessDate date
 * @param currency currency
 * @param amountMinor minor units
 * @param category category
 * @param merchant merchant (restricted text)
 * @param description description (restricted text)
 * @param receipt bound receipt or {@code null}
 * @param createdAt creation instant
 * @param submittedAt submission instant or {@code null} while editable
 */
public record ExpenseRevision(
    int revision,
    LocalDate businessDate,
    ExpenseCurrency currency,
    long amountMinor,
    ExpenseCategory category,
    String merchant,
    String description,
    Expense.ReceiptInfo receipt,
    Instant createdAt,
    Instant submittedAt) {

  @Override
  public String toString() {
    return "ExpenseRevision[" + revision + "]";
  }
}

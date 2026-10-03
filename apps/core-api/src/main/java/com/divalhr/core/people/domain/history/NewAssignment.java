package com.divalhr.core.people.domain.history;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A row a plan will insert. Its ID is assigned when the plan is computed so the commit writes
 * exactly what the preview showed (the digest covers values and dates, not IDs).
 *
 * @param id row ID
 * @param kind kind
 * @param from first day
 * @param to last day, or {@code null}
 * @param value value
 * @param origin change whose value the row carries
 * @param restores restored row (cancellations only), or {@code null}
 */
public record NewAssignment(
    UUID id,
    AssignmentKind kind,
    LocalDate from,
    LocalDate to,
    AssignmentValue value,
    UUID origin,
    UUID restores) {

  /** Requires the mandatory fields. */
  public NewAssignment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(origin, "origin");
    if (to != null && to.isBefore(from)) {
      throw new IllegalArgumentException("period ends before it starts");
    }
  }
}

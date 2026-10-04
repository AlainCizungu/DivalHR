package com.divalhr.core.people.domain.history;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored assignment row. Business values and effective dates never change; the supersession pair
 * is set once when a later change replaces the row (M21-5).
 *
 * @param id row ID
 * @param kind kind
 * @param from first day (inclusive)
 * @param to last day (inclusive), or {@code null} when open-ended
 * @param value business value
 * @param createdBy change that wrote the row
 * @param origin change whose value the row carries
 * @param restores for a row restored by a cancellation, the row the cancelled change replaced
 * @param supersededBy change that replaced the row, or {@code null} while active
 * @param supersededAt when it was replaced, or {@code null}
 */
public record Assignment(
    UUID id,
    AssignmentKind kind,
    LocalDate from,
    LocalDate to,
    AssignmentValue value,
    UUID createdBy,
    UUID origin,
    UUID restores,
    UUID supersededBy,
    Instant supersededAt) {

  /** Requires the mandatory fields and a value of the row's kind. */
  public Assignment {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(origin, "origin");
    if (value.kind() != kind) {
      throw new IllegalArgumentException("value of another kind");
    }
  }

  /**
   * Whether no change has replaced the row.
   *
   * @return true while active
   */
  public boolean active() {
    return supersededBy == null;
  }

  /**
   * Whether the row's period contains a day.
   *
   * @param day day
   * @return true when {@code from <= day <= to}
   */
  public boolean covers(LocalDate day) {
    return !day.isBefore(from) && (to == null || !day.isAfter(to));
  }
}

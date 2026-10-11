package com.divalhr.core.people.schedule.domain;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One proposed slot as normalized from the request: one required worker.
 *
 * @param clientRef the client's correlation reference (never stored)
 * @param position 1-based position in the submitted order
 * @param start local start (minute precision)
 * @param end local end (minute precision)
 * @param labelEn English label (grammar version 1)
 * @param labelFr French label (grammar version 1)
 * @param employeeId the assigned employee, or {@code null} for an uncovered slot
 */
public record SlotDraft(
    String clientRef,
    int position,
    LocalDateTime start,
    LocalDateTime end,
    String labelEn,
    String labelFr,
    UUID employeeId) {

  /** Requires every value but the employee. */
  public SlotDraft {
    Objects.requireNonNull(clientRef, "clientRef");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(end, "end");
    Objects.requireNonNull(labelEn, "labelEn");
    Objects.requireNonNull(labelFr, "labelFr");
  }

  /**
   * Positions only: labels and employees never reach a log line through {@code toString}.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "SlotDraft[position=" + position + "]";
  }
}

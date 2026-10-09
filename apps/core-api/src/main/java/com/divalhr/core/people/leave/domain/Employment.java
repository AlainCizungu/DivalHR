package com.divalhr.core.people.leave.domain;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The employment period a leave request is checked against (MVP-041A).
 *
 * @param id employment
 * @param effectiveFrom first day
 * @param effectiveTo last day, or {@code null} when open-ended
 */
public record Employment(UUID id, LocalDate effectiveFrom, LocalDate effectiveTo) {

  /**
   * Whether the employment covers a whole interval (both ends inclusive).
   *
   * @param start first day
   * @param end last day
   * @return whether it covers
   */
  public boolean covers(LocalDate start, LocalDate end) {
    return !effectiveFrom.isAfter(start) && (effectiveTo == null || !effectiveTo.isBefore(end));
  }

  /**
   * Identifiers only.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "Employment[id=" + id + "]";
  }
}

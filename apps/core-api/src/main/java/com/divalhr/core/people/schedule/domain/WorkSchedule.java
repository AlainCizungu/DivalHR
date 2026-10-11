package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A published work schedule (immutable evidence).
 *
 * @param id schedule
 * @param siteId site
 * @param firstDate first date
 * @param lastDate last date
 * @param siteTimezone the site's IANA time zone snapshot (R100-1)
 * @param slotCount slots
 * @param assignedCount assigned slots
 * @param publishedAt publication instant
 */
public record WorkSchedule(
    UUID id,
    UUID siteId,
    LocalDate firstDate,
    LocalDate lastDate,
    String siteTimezone,
    int slotCount,
    int assignedCount,
    Instant publishedAt) {

  /** Requires every value. */
  public WorkSchedule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(siteId, "siteId");
    Objects.requireNonNull(firstDate, "firstDate");
    Objects.requireNonNull(lastDate, "lastDate");
    Objects.requireNonNull(siteTimezone, "siteTimezone");
    Objects.requireNonNull(publishedAt, "publishedAt");
  }

  /**
   * Uncovered slots, derived.
   *
   * @return slots without an assigned employee
   */
  public int uncoveredCount() {
    return slotCount - assignedCount;
  }
}

package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One of the caller's own published shifts.
 *
 * @param slotId slot
 * @param scheduleId schedule
 * @param siteId site
 * @param siteTimezone the schedule's stored site time zone snapshot
 * @param start local start
 * @param end local end
 * @param startAt start instant
 * @param labelEn English label
 * @param labelFr French label
 */
public record OwnShift(
    UUID slotId,
    UUID scheduleId,
    UUID siteId,
    String siteTimezone,
    LocalDateTime start,
    LocalDateTime end,
    Instant startAt,
    String labelEn,
    String labelFr) {

  @Override
  public String toString() {
    return "OwnShift[slotId=" + slotId + "]";
  }
}

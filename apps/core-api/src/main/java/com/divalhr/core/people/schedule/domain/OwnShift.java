package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One of the caller's own operational shifts (MVP-050A), read from the slot coverage (MVP-051): the
 * employee it was published to, or the approved substitute.
 *
 * @param slotId slot
 * @param scheduleId schedule
 * @param siteId site
 * @param siteTimezone the schedule's stored site time zone snapshot
 * @param start local start
 * @param end local end
 * @param startAt start instant
 * @param endAt end instant (MVP-060A)
 * @param labelEn English label
 * @param labelFr French label
 * @param source how the caller came to work it (MVP-051)
 * @param attendance the caller's own attendance on it (MVP-060A)
 */
public record OwnShift(
    UUID slotId,
    UUID scheduleId,
    UUID siteId,
    String siteTimezone,
    LocalDateTime start,
    LocalDateTime end,
    Instant startAt,
    Instant endAt,
    String labelEn,
    String labelFr,
    Substitution.Source source,
    Attendance.Mark attendance) {

  @Override
  public String toString() {
    return "OwnShift[slotId=" + slotId + "]";
  }
}

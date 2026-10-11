package com.divalhr.core.people.schedule.domain;

import java.util.Objects;

/**
 * A published schedule with its derived lifecycle (MVP-050B).
 *
 * @param schedule the immutable schedule
 * @param lifecycle its derived state and links
 */
public record ScheduleView(WorkSchedule schedule, Lifecycle lifecycle) {

  /** Requires both. */
  public ScheduleView {
    Objects.requireNonNull(schedule, "schedule");
    Objects.requireNonNull(lifecycle, "lifecycle");
  }
}

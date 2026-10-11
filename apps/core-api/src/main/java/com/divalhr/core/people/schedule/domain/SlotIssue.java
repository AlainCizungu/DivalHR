package com.divalhr.core.people.schedule.domain;

import java.util.Objects;

/**
 * Why a proposed slot cannot be published: a stable code and a stable reason, never a value.
 *
 * @param code stable error code name ({@code WORK_SCHEDULE_SLOT_INVALID}, {@code
 *     WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE} or {@code WORK_SCHEDULE_EMPLOYEE_CONFLICT})
 * @param reason stable reason
 */
public record SlotIssue(String code, String reason) {

  /** {@code WORK_SCHEDULE_SLOT_INVALID}. */
  public static final String SLOT_INVALID = "WORK_SCHEDULE_SLOT_INVALID";

  /** {@code WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE}. */
  public static final String NOT_ELIGIBLE = "WORK_SCHEDULE_EMPLOYEE_NOT_ELIGIBLE";

  /** {@code WORK_SCHEDULE_EMPLOYEE_CONFLICT}. */
  public static final String CONFLICT = "WORK_SCHEDULE_EMPLOYEE_CONFLICT";

  /** Requires both. */
  public SlotIssue {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(reason, "reason");
  }
}

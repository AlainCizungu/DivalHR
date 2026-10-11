package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A proposed slot after normalization and validation.
 *
 * @param draft the normalized proposal
 * @param startAt the start instant, or {@code null} when the local start is not one instant
 * @param endAt the end instant, or {@code null} when the local end is not one instant
 * @param issue why it cannot be published, or {@code null}
 */
public record PlannedSlot(SlotDraft draft, Instant startAt, Instant endAt, SlotIssue issue) {

  /** Requires the draft. */
  public PlannedSlot {
    Objects.requireNonNull(draft, "draft");
  }

  /**
   * Whether the slot ends on a later local date than it starts.
   *
   * @return true for an overnight slot
   */
  public boolean overnight() {
    return draft.end().toLocalDate().isAfter(draft.start().toLocalDate());
  }

  /**
   * The same slot with an issue (the first one found is kept).
   *
   * @param found the issue
   * @return this slot when it already has one, otherwise a copy with it
   */
  public PlannedSlot withIssue(SlotIssue found) {
    return issue != null ? this : new PlannedSlot(draft, startAt, endAt, found);
  }

  @Override
  public String toString() {
    return "PlannedSlot[position=" + draft.position() + "]";
  }
}

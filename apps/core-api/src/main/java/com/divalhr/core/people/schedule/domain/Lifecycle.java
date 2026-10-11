package com.divalhr.core.people.schedule.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A schedule's derived state (MVP-050B, D50B-2): published, cancelled or replaced, from the
 * append-only terminal evidence; never stored on the schedule itself.
 *
 * @param status derived state
 * @param changedAt the terminal action's instant, or {@code null} while published
 * @param reason the terminal action's reason (Restricted HR), or {@code null} while published
 * @param predecessorId the schedule this one replaced, or {@code null}
 * @param successorId the schedule that replaced this one, or {@code null}
 */
public record Lifecycle(
    Status status, Instant changedAt, String reason, UUID predecessorId, UUID successorId) {

  /** Derived schedule states. */
  public enum Status {
    /** Operational: no terminal action. */
    PUBLISHED,
    /** Cancelled; its dates and employee instants are released. */
    CANCELLED,
    /** Replaced by a successor of the same site and period. */
    REPLACED
  }

  /** Requires the state; terminal states carry their instant and reason. */
  public Lifecycle {
    Objects.requireNonNull(status, "status");
    if (status != Status.PUBLISHED) {
      Objects.requireNonNull(changedAt, "changedAt");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * A published schedule's lifecycle.
   *
   * @param predecessorId the schedule it replaced, or {@code null}
   * @return the lifecycle
   */
  public static Lifecycle published(UUID predecessorId) {
    return new Lifecycle(Status.PUBLISHED, null, null, predecessorId, null);
  }

  /**
   * The state and links only: the reason never reaches a log line.
   *
   * @return safe text
   */
  @Override
  public String toString() {
    return "Lifecycle[status=" + status + "]";
  }
}

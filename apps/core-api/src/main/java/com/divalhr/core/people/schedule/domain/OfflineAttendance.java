package com.divalhr.core.people.schedule.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The trust rules of offline attendance synchronization (MVP-061A, A61A-3; ADR 0016 amendment). The
 * claimed device time is untrusted evidence supplied by the client, validated against the unchanged
 * MVP-060A windows; the receipt instant is the server's. The safeguards are fixed pilot constants,
 * mirrored in V30, never tenant-configurable.
 */
public final class OfflineAttendance {

  /** The claimed device time may be at most this long after the server receipt (inclusive). */
  public static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

  /** The receipt may be at most this long after the claimed device time (inclusive). */
  public static final Duration MAX_AGE = Duration.ofHours(72);

  /** The receipt may be at most this long after the applicable window closed (inclusive). */
  public static final Duration MAX_AFTER_WINDOW = Duration.ofHours(72);

  /** The device UTC offset bound in minutes, either side of UTC (UTC-14:00 to UTC+14:00). */
  public static final int MAX_OFFSET_MINUTES = 840;

  private OfflineAttendance() {}

  /** The event an offline submission carries. */
  public enum Type {
    /** A check-in on a scheduled shift. */
    CHECK_IN(Attendance.EventType.CHECKED_IN),
    /** The check-out of the caller's record on that shift. */
    CHECK_OUT(Attendance.EventType.CHECKED_OUT);

    private final Attendance.EventType eventType;

    Type(Attendance.EventType eventType) {
      this.eventType = eventType;
    }

    /**
     * The stored event type.
     *
     * @return the event type
     */
    public Attendance.EventType eventType() {
      return eventType;
    }

    /**
     * The submission type of a stored event type.
     *
     * @param eventType stored type
     * @return the submission type
     */
    public static Type of(Attendance.EventType eventType) {
      return eventType == Attendance.EventType.CHECKED_IN ? CHECK_IN : CHECK_OUT;
    }
  }

  /** The stable outcome of an accepted submission. */
  public enum Outcome {
    /** Applied now (201). */
    APPLIED,
    /** The identical submission was applied before; the original result (200). */
    ALREADY_APPLIED
  }

  /** A synchronization safeguard that refuses a submission. */
  public enum Safeguard {
    /** The claimed device time is more than 5 minutes after the receipt. */
    FUTURE_TIME,
    /** Received more than 72 hours after the claimed time or after the window closed. */
    TOO_OLD
  }

  /**
   * The first safeguard the submission breaks, in a fixed order: future time, age, then the receipt
   * after the window closed. Every bound is inclusive at exactly its constant.
   *
   * @param occurredAt the claimed device occurrence instant
   * @param receivedAt the server receipt instant
   * @param windowClosesAt the end of the applicable window: the scheduled end for a check-in, the
   *     check-out deadline for a check-out
   * @return the broken safeguard, or empty
   */
  public static Optional<Safeguard> refusal(
      Instant occurredAt, Instant receivedAt, Instant windowClosesAt) {
    if (occurredAt.isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
      return Optional.of(Safeguard.FUTURE_TIME);
    }
    if (receivedAt.isAfter(occurredAt.plus(MAX_AGE))
        || receivedAt.isAfter(windowClosesAt.plus(MAX_AFTER_WINDOW))) {
      return Optional.of(Safeguard.TOO_OLD);
    }
    return Optional.empty();
  }
}

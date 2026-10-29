package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * The date rules of a monthly compensation (MVP-093A, D93-5): the business date comes from the
 * organization's time zone (the caller supplies {@code today}); every effective date lies in {@code
 * [today - 60 days, today + 366 days]} (exact boundaries accepted, one day beyond refused); a date
 * before today is retroactive; and a start lies inside an employment interval. Pure rules, no I/O.
 */
public final class CompensationDates {

  /** How far back an effective date (or a correction's reach) may go. */
  public static final int RETROACTIVE_DAYS = 60;

  /** How far ahead an effective date may go. */
  public static final int FUTURE_DAYS = 366;

  private CompensationDates() {}

  /**
   * One employment interval of the employee, as the people port reports it.
   *
   * @param id employment
   * @param version employment timeline version
   * @param start first day
   * @param end last day, or {@code null} while open
   */
  public record Span(UUID id, long version, LocalDate start, LocalDate end) {

    /** Requires the identity and the first day. */
    public Span {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(start, "start");
    }

    /**
     * Whether a day is inside the interval.
     *
     * @param day business date
     * @return true when {@code start <= day <= end} (open end: any later day)
     */
    public boolean covers(LocalDate day) {
      return !day.isBefore(start) && (end == null || !day.isAfter(end));
    }
  }

  /**
   * The earliest legal effective date.
   *
   * @param today business date
   * @return {@code today - 60 days}
   */
  public static LocalDate earliest(LocalDate today) {
    return today.minusDays(RETROACTIVE_DAYS);
  }

  /**
   * The latest legal effective date.
   *
   * @param today business date
   * @return {@code today + 366 days}
   */
  public static LocalDate latest(LocalDate today) {
    return today.plusDays(FUTURE_DAYS);
  }

  /**
   * Whether an effective date is inside the legal window.
   *
   * @param day effective date
   * @param today business date
   * @return true when the date is accepted (both boundaries included)
   */
  public static boolean withinWindow(LocalDate day, LocalDate today) {
    return !day.isBefore(earliest(today)) && !day.isAfter(latest(today));
  }

  /**
   * Whether an effective date is retroactive.
   *
   * @param day effective date
   * @param today business date
   * @return true when the date is before today
   */
  public static boolean retroactive(LocalDate day, LocalDate today) {
    return day.isBefore(today);
  }

  /**
   * Whether a correction's reach is inside the legal window: the earlier of the old and the new
   * start may not be before {@code today - 60 days}.
   *
   * @param oldStart the start of the fact being corrected
   * @param newStart the start after the correction
   * @param today business date
   * @return true when the reach is accepted
   */
  public static boolean reachWithinWindow(LocalDate oldStart, LocalDate newStart, LocalDate today) {
    LocalDate reach = oldStart.isBefore(newStart) ? oldStart : newStart;
    return !reach.isBefore(earliest(today));
  }

  /**
   * Whether a day lies inside some employment interval.
   *
   * @param day effective date
   * @param employments the employee's employment intervals
   * @return true when one covers the day
   */
  public static boolean withinEmployment(LocalDate day, Collection<Span> employments) {
    for (Span span : employments) {
      if (span.covers(day)) {
        return true;
      }
    }
    return false;
  }
}

package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The effective-dated statutory profile timeline of an employee (MVP-094B, ADR 0034 section 5): the
 * profile in force on a day is the row with the greatest effective date not after it, then the
 * greatest sequence, so a correction is a new row. Pure rules, no I/O.
 */
public final class StatutoryProfileTimeline {

  /** At most this many profile rows per employee. */
  public static final int MAX_PROFILES = 100;

  /** The earliest effective date of a profile. */
  public static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

  /** A profile can take effect at most this many days after today. */
  public static final int MAX_DAYS_AHEAD = 366;

  private static final Comparator<StatutoryProfile> CHRONOLOGICAL =
      Comparator.comparing(StatutoryProfile::effectiveFrom)
          .thenComparingInt(StatutoryProfile::sequence);

  private StatutoryProfileTimeline() {}

  /** Why a profile row cannot be appended. */
  public enum Blocker {
    /** The effective date is before 2000-01-01 or more than 366 days after today. */
    EFFECTIVE_DATE_OUT_OF_RANGE,
    /** The profile in force on that date is identical in content. */
    NO_CHANGE,
    /** The employee already has 100 rows. */
    LIMIT_REACHED
  }

  /**
   * What appending a profile row would do.
   *
   * @param blockers why it cannot be recorded (empty when it can), in declaration order
   * @param after the timeline after it, oldest first (equal to the current one when blocked)
   */
  public record Plan(List<Blocker> blockers, List<StatutoryProfile> after) {

    /** Copies the lists. */
    public Plan {
      blockers = List.copyOf(blockers);
      after = List.copyOf(after);
    }

    /**
     * Whether the row can be recorded.
     *
     * @return true when there is no blocker
     */
    public boolean committable() {
      return blockers.isEmpty();
    }
  }

  /**
   * The profile of an employee for a period of eligible days, with its blockers.
   *
   * @param profile the profile in force on the first eligible day, if any
   * @param blockers {@code PROFILE_MISSING}, {@code PROFILE_UNSUPPORTED} and {@code
   *     PROFILE_CHANGES_IN_MONTH}, in that order, as they apply
   */
  public record PeriodProfile(
      Optional<StatutoryProfile> profile, List<StatutoryLineBlocker> blockers) {

    /** Copies the list. */
    public PeriodProfile {
      blockers = List.copyOf(blockers);
    }

    /**
     * Whether the period has a usable, stable, supported profile.
     *
     * @return true when there is no blocker
     */
    public boolean usable() {
      return blockers.isEmpty();
    }
  }

  /**
   * The profile in force on a day.
   *
   * @param profiles the employee's rows in any order
   * @param day the date
   * @return the row with the greatest effective date not after the day, then the greatest sequence
   */
  public static Optional<StatutoryProfile> inForce(List<StatutoryProfile> profiles, LocalDate day) {
    StatutoryProfile found = null;
    for (StatutoryProfile profile : profiles) {
      if (!profile.effectiveFrom().isAfter(day)
          && (found == null || CHRONOLOGICAL.compare(profile, found) > 0)) {
        found = profile;
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * The version of an employee's profile: the number of rows, 0 when none.
   *
   * @param profiles the rows
   * @return the version
   */
  public static int version(List<StatutoryProfile> profiles) {
    return profiles.size();
  }

  /**
   * The rows, oldest effective date first, then by sequence.
   *
   * @param profiles the rows in any order
   * @return the sorted copy
   */
  public static List<StatutoryProfile> chronological(List<StatutoryProfile> profiles) {
    List<StatutoryProfile> sorted = new ArrayList<>(profiles);
    sorted.sort(CHRONOLOGICAL);
    return sorted;
  }

  /**
   * The profile for a period of eligible days (ADR 0034 section 6): the row in force on the first
   * eligible day must hold for the whole period.
   *
   * @param profiles the employee's rows
   * @param firstEligibleDay the first eligible day
   * @param lastEligibleDay the last eligible day, not before the first
   * @return the profile in force on the first day with the blockers: {@code PROFILE_MISSING} when
   *     none is in force; {@code PROFILE_UNSUPPORTED} when it is classified unsupported; {@code
   *     PROFILE_CHANGES_IN_MONTH} when a row takes effect after the first day and up to the last
   *     day
   * @throws IllegalArgumentException when the last day is before the first
   */
  public static PeriodProfile forPeriod(
      List<StatutoryProfile> profiles, LocalDate firstEligibleDay, LocalDate lastEligibleDay) {
    if (lastEligibleDay.isBefore(firstEligibleDay)) {
      throw new IllegalArgumentException("the period ends on or after its first day");
    }
    Optional<StatutoryProfile> inForce = inForce(profiles, firstEligibleDay);
    List<StatutoryLineBlocker> blockers = new ArrayList<>();
    if (inForce.isEmpty()) {
      blockers.add(StatutoryLineBlocker.PROFILE_MISSING);
    } else if (inForce.get().classification() == StatutoryClassification.UNSUPPORTED) {
      blockers.add(StatutoryLineBlocker.PROFILE_UNSUPPORTED);
    }
    boolean changes =
        profiles.stream()
            .anyMatch(
                p ->
                    p.effectiveFrom().isAfter(firstEligibleDay)
                        && !p.effectiveFrom().isAfter(lastEligibleDay));
    if (changes) {
      blockers.add(StatutoryLineBlocker.PROFILE_CHANGES_IN_MONTH);
    }
    return new PeriodProfile(inForce, blockers);
  }

  /**
   * Evaluates appending a profile row (the preview command).
   *
   * @param existing the employee's rows now
   * @param candidate the row to append (its sequence is the next one)
   * @param today today in the business timezone of the organization
   * @return the plan: the blockers {@code EFFECTIVE_DATE_OUT_OF_RANGE} (before 2000-01-01 or after
   *     today + 366 days), {@code NO_CHANGE} (the profile in force on that date has the same
   *     content) and {@code LIMIT_REACHED} (100 rows), and the timeline after the change
   */
  public static Plan plan(
      List<StatutoryProfile> existing, StatutoryProfile candidate, LocalDate today) {
    List<Blocker> blockers = new ArrayList<>();
    LocalDate date = candidate.effectiveFrom();
    if (date.isBefore(EARLIEST) || date.isAfter(today.plusDays(MAX_DAYS_AHEAD))) {
      blockers.add(Blocker.EFFECTIVE_DATE_OUT_OF_RANGE);
    }
    if (inForce(existing, date).filter(candidate::sameContent).isPresent()) {
      blockers.add(Blocker.NO_CHANGE);
    }
    if (existing.size() >= MAX_PROFILES) {
      blockers.add(Blocker.LIMIT_REACHED);
    }
    List<StatutoryProfile> after = new ArrayList<>(existing);
    if (blockers.isEmpty()) {
      after.add(candidate);
    }
    return new Plan(blockers, chronological(after));
  }
}

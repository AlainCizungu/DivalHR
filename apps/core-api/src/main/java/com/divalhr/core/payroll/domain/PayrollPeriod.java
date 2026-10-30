package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A calendar month of a payroll run, {@code YYYY-MM} (MVP-094A, D94A-5). A run needs a fully closed
 * month (its last day is before the business date) within the prior 24 months, in the
 * organization's business time zone; the caller supplies the business date. Pure rules, no I/O.
 *
 * @param month the calendar month
 */
public record PayrollPeriod(YearMonth month) {

  /** How many months back a run may reach (the 24th month back is still allowed). */
  public static final int MONTHS_BACK = 24;

  /** How far ahead a policy may take effect, in days. */
  public static final int POLICY_FUTURE_DAYS = 366;

  private static final Pattern SHAPE = Pattern.compile("^[0-9]{4}-(0[1-9]|1[0-2])$");

  /** Requires the month. */
  public PayrollPeriod {
    Objects.requireNonNull(month, "month");
  }

  /**
   * Reads the wire text {@code YYYY-MM}.
   *
   * @param text candidate
   * @return the period, or empty when not exactly a year 2000 to 2999 and a month
   */
  public static Optional<PayrollPeriod> parse(String text) {
    if (text == null || !SHAPE.matcher(text).matches()) {
      return Optional.empty();
    }
    YearMonth month = YearMonth.parse(text);
    if (month.getYear() < 2000 || month.getYear() > 2999) {
      return Optional.empty();
    }
    return Optional.of(new PayrollPeriod(month));
  }

  /**
   * The first day of the month.
   *
   * @return first day
   */
  public LocalDate first() {
    return month.atDay(1);
  }

  /**
   * The last day of the month.
   *
   * @return last day
   */
  public LocalDate last() {
    return month.atEndOfMonth();
  }

  /**
   * The number of days of the month.
   *
   * @return 28 to 31
   */
  public int days() {
    return month.lengthOfMonth();
  }

  /**
   * The wire text.
   *
   * @return {@code YYYY-MM}
   */
  public String text() {
    return month.toString();
  }

  /**
   * The first day of the earliest month a run may name (and the earliest policy date): the first
   * day of the month 24 months before the business date's month.
   *
   * @param today business date
   * @return earliest legal day
   */
  public static LocalDate earliestStart(LocalDate today) {
    return today.withDayOfMonth(1).minusMonths(MONTHS_BACK);
  }

  /**
   * The latest policy effective date.
   *
   * @param today business date
   * @return {@code today + 366 days}
   */
  public static LocalDate latestPolicyDate(LocalDate today) {
    return today.plusDays(POLICY_FUTURE_DAYS);
  }

  /**
   * Whether a run may be created for this month on the business date: closed (its last day is
   * before today) and not older than the 24 prior months.
   *
   * @param today business date
   * @return true when legal
   */
  public boolean isRunnable(LocalDate today) {
    return last().isBefore(today) && !first().isBefore(earliestStart(today));
  }

  /**
   * Whether a policy effective date is legal on the business date.
   *
   * @param day candidate effective date
   * @param today business date
   * @return true inside {@code [earliestStart(today), today + 366 days]}
   */
  public static boolean isPolicyDate(LocalDate day, LocalDate today) {
    return !day.isBefore(earliestStart(today)) && !day.isAfter(latestPolicyDate(today));
  }
}

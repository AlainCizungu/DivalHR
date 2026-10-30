package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.StatutoryProfileTimeline.PeriodProfile;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The statutory preview of a whole frozen payroll run, in memory (MVP-094B, ADR 0034 section 6).
 * Pure rules: the caller reads the pack, the run, its lines, the profile rows and the unmodeled
 * earnings and passes them in; nothing is read or written here, and there is no clock.
 *
 * <p>Amounts are produced only when there is no run-level blocker. Employee-level profile and
 * earnings blockers are reported anyway, so profiles can be completed. A line gets amounts only
 * when it has no blocker. A calculation can be committed only when its {@linkplain
 * Result#committable() result is committable}.
 */
public final class StatutoryCalculator {

  /** The only currency the preview supports (no exchange-rate policy exists). */
  public static final String SUPPORTED_CURRENCY = "CDF";

  private StatutoryCalculator() {}

  /** The lifecycle status of a rule pack. */
  public enum PackStatus {
    /** Inspectable and testable, never produces an amount. */
    DRAFT,
    /** Professionally verified; the only status that produces amounts. */
    VERIFIED,
    /** No longer in effect for new calculations. */
    RETIRED
  }

  /**
   * The rule pack in effect for the legal entity and month.
   *
   * @param status the lifecycle status
   * @param effectiveFrom first day of the pack's interval
   * @param effectiveTo last day of the interval (inclusive), or {@code null} when open
   * @param rules the parsed rules, or {@code null} when the pack does not parse (never applicable)
   */
  public record Pack(
      PackStatus status, LocalDate effectiveFrom, LocalDate effectiveTo, StatutoryRules rules) {

    /** Requires the status and the first day. */
    public Pack {
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    }
  }

  /**
   * The facts of the source run.
   *
   * @param current whether the run is the current DRAFT of its chain
   * @param currency the run currency code
   * @param periodStart the first day of the run's month
   */
  public record Run(boolean current, String currency, LocalDate periodStart) {

    /** Requires the currency and the month. */
    public Run {
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(periodStart, "periodStart");
    }
  }

  /**
   * One frozen line of the source run.
   *
   * @param sourceLineId the source line
   * @param employeeId the employee
   * @param employeeNumber the employee number
   * @param grossMinor the frozen contractual gross in whole francs
   * @param firstEligibleDay the first eligible day of the line in the month
   * @param lastEligibleDay the last eligible day of the line in the month
   */
  public record SourceLine(
      UUID sourceLineId,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      LocalDate firstEligibleDay,
      LocalDate lastEligibleDay) {

    /** Requires the identities and ordered days, and a gross of 0 or more. */
    public SourceLine {
      Objects.requireNonNull(sourceLineId, "sourceLineId");
      Objects.requireNonNull(employeeId, "employeeId");
      Objects.requireNonNull(employeeNumber, "employeeNumber");
      Objects.requireNonNull(firstEligibleDay, "firstEligibleDay");
      Objects.requireNonNull(lastEligibleDay, "lastEligibleDay");
      if (grossMinor < 0 || lastEligibleDay.isBefore(firstEligibleDay)) {
        throw new IllegalArgumentException("a gross of 0 or more and ordered days are required");
      }
    }
  }

  /**
   * The outcome for one source line.
   *
   * @param sourceLineId the source line
   * @param employeeId the employee
   * @param employeeNumber the employee number
   * @param grossMinor the frozen gross
   * @param blockers the line blockers, in declaration order
   * @param line the calculated line, or {@code null} when the line is blocked or the run is
   */
  public record LineResult(
      UUID sourceLineId,
      UUID employeeId,
      String employeeNumber,
      long grossMinor,
      List<StatutoryLineBlocker> blockers,
      StatutoryLine line) {

    /** Copies the list. */
    public LineResult {
      blockers = List.copyOf(blockers);
    }
  }

  /**
   * The outcome for a run.
   *
   * @param runBlockers the run-level blockers, in declaration order
   * @param lines one result per source line, in input order
   * @param supportedCount lines calculated with at least one applicable component
   * @param excludedCount lines calculated with no applicable component (kept, net = gross)
   * @param blockedCount lines with at least one blocker
   * @param grossTotal the sum of the calculated lines' gross
   * @param employeeDeductionsTotal the sum of their employee deductions
   * @param employerContributionsTotal the sum of their employer contributions
   * @param previewNetTotal the sum of their preview net
   * @param employerCostTotal the sum of their employer cost
   */
  public record Result(
      List<StatutoryRunBlocker> runBlockers,
      List<LineResult> lines,
      int supportedCount,
      int excludedCount,
      int blockedCount,
      long grossTotal,
      long employeeDeductionsTotal,
      long employerContributionsTotal,
      long previewNetTotal,
      long employerCostTotal) {

    /** Copies the lists. */
    public Result {
      runBlockers = List.copyOf(runBlockers);
      lines = List.copyOf(lines);
    }

    /**
     * Whether the calculation can be committed: no run-level blocker, no blocked line and at least
     * one line.
     *
     * @return true when the preview is ready
     */
    public boolean committable() {
      return runBlockers.isEmpty() && blockedCount == 0 && !lines.isEmpty();
    }
  }

  /**
   * Calculates the statutory preview of a run.
   *
   * @param pack the pack in effect for the legal entity and month, or {@code null} when none
   * @param run the run facts
   * @param sources the frozen source lines
   * @param profiles each employee's profile rows (an employee without an entry has none)
   * @param unmodeledEarnings the employees that have an earning other than the contractual base
   * @return the result; the totals cover the calculated lines and are 0 when a run-level blocker
   *     exists
   * @throws ArithmeticException when a total does not fit a {@code long}; it cannot happen for the
   *     gross values the payroll database admits
   */
  public static Result calculate(
      Pack pack,
      Run run,
      List<SourceLine> sources,
      Map<UUID, List<StatutoryProfile>> profiles,
      Set<UUID> unmodeledEarnings) {
    Objects.requireNonNull(run, "run");
    List<StatutoryRunBlocker> runBlockers = runBlockers(pack, run);
    boolean amounts = runBlockers.isEmpty();
    List<LineResult> results = new ArrayList<>();
    int supported = 0;
    int excluded = 0;
    int blocked = 0;
    long gross = 0;
    long deductions = 0;
    long contributions = 0;
    long net = 0;
    long cost = 0;
    for (SourceLine source : sources) {
      List<StatutoryLineBlocker> blockers = new ArrayList<>();
      PeriodProfile period =
          StatutoryProfileTimeline.forPeriod(
              profiles.getOrDefault(source.employeeId(), List.of()),
              source.firstEligibleDay(),
              source.lastEligibleDay());
      blockers.addAll(period.blockers());
      if (unmodeledEarnings.contains(source.employeeId())) {
        blockers.add(StatutoryLineBlocker.UNMODELED_EARNINGS);
      }
      StatutoryLine line = null;
      if (amounts && blockers.isEmpty()) {
        StatutoryProfile profile = period.profile().orElseThrow();
        line =
            StatutoryEngine.calculate(
                pack.rules(), profile.cnssSubject(), profile.irppSubject(), source.grossMinor());
        blockers.addAll(line.blockers());
        if (line.blocked()) {
          line = null;
        }
      }
      if (!blockers.isEmpty()) {
        blocked++;
      } else if (line != null) {
        if (line.excluded()) {
          excluded++;
        } else {
          supported++;
        }
        gross = Math.addExact(gross, line.grossMinor());
        deductions = Math.addExact(deductions, line.employeeDeductionsMinor());
        contributions = Math.addExact(contributions, line.employerContributionsMinor());
        net = Math.addExact(net, line.previewNetMinor());
        cost = Math.addExact(cost, line.employerCostMinor());
      }
      results.add(
          new LineResult(
              source.sourceLineId(),
              source.employeeId(),
              source.employeeNumber(),
              source.grossMinor(),
              blockers,
              line));
    }
    return new Result(
        runBlockers,
        results,
        supported,
        excluded,
        blocked,
        gross,
        deductions,
        contributions,
        net,
        cost);
  }

  /**
   * The run-level blockers.
   *
   * @param pack the pack in effect, or {@code null}
   * @param run the run facts
   * @return the blockers in declaration order
   */
  static List<StatutoryRunBlocker> runBlockers(Pack pack, Run run) {
    List<StatutoryRunBlocker> blockers = new ArrayList<>();
    if (!run.current()) {
      blockers.add(StatutoryRunBlocker.SOURCE_RUN_NOT_CURRENT);
    }
    if (!SUPPORTED_CURRENCY.equals(run.currency())) {
      blockers.add(StatutoryRunBlocker.CURRENCY_NOT_SUPPORTED);
    }
    if (pack == null) {
      blockers.add(StatutoryRunBlocker.RULE_PACK_MISSING);
    } else if (!usable(pack, run.periodStart())) {
      blockers.add(StatutoryRunBlocker.RULE_PACK_NOT_VERIFIED);
    }
    return blockers;
  }

  /** A pack is usable when VERIFIED, parsed, and its interval covers the whole month. */
  private static boolean usable(Pack pack, LocalDate periodStart) {
    if (pack.status() != PackStatus.VERIFIED || pack.rules() == null) {
      return false;
    }
    LocalDate lastDay = periodStart.plusMonths(1).minusDays(1);
    return !pack.effectiveFrom().isAfter(periodStart)
        && (pack.effectiveTo() == null || !pack.effectiveTo().isBefore(lastDay));
  }
}

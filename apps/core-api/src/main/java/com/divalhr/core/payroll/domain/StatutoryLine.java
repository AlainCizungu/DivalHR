package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.StatutoryRules.Category;
import com.divalhr.core.payroll.domain.StatutoryRules.Party;
import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * The statutory preview of one employee line (MVP-094B, ADR 0034 section 6), as produced by {@link
 * StatutoryEngine}. Money is whole CDF francs. Employer contributions are separate: they are never
 * deducted from the employee.
 *
 * <p>{@code previewNetMinor = grossMinor - employeeDeductionsMinor} and {@code employerCostMinor =
 * grossMinor + employerContributionsMinor}. When {@code blockers} is not empty the amounts are
 * informational only (the net can be negative) and must not be stored.
 *
 * @param grossMinor the contractual gross, as given
 * @param components one entry per rule of the pack, in rule order (none when excluded)
 * @param employeeDeductionsMinor the sum of the employee components
 * @param employerContributionsMinor the sum of the employer components
 * @param previewNetMinor the gross less the employee deductions
 * @param employerCostMinor the gross plus the employer contributions
 * @param excluded true when no component applies to the profile: kept with no components, net =
 *     gross
 * @param blockers the line blockers the engine found (only {@code DEDUCTIONS_EXCEED_GROSS})
 */
public record StatutoryLine(
    long grossMinor,
    List<Component> components,
    long employeeDeductionsMinor,
    long employerContributionsMinor,
    long previewNetMinor,
    long employerCostMinor,
    boolean excluded,
    List<StatutoryLineBlocker> blockers) {

  /** Copies the lists. */
  public StatutoryLine {
    components = List.copyOf(components);
    blockers = List.copyOf(blockers);
  }

  /**
   * Whether the line has a blocker.
   *
   * @return true when the line cannot be committed
   */
  public boolean blocked() {
    return !blockers.isEmpty();
  }

  /**
   * One component of a line.
   *
   * @param componentId the rule id
   * @param party who bears it
   * @param category what it is
   * @param baseMinor the monthly base it was computed on
   * @param amountMinor the amount, rounded once; 0 when the profile is not subject
   * @param explanations the stable explanation codes, method first, then {@code CAP_APPLIED} if it
   *     applied, then the base
   * @param trace the progressive trace, or {@code null} for a flat rate or a component not subject
   */
  public record Component(
      String componentId,
      Party party,
      Category category,
      long baseMinor,
      long amountMinor,
      List<StatutoryExplanation> explanations,
      Trace trace) {

    /** Requires the identity and copies the list. */
    public Component {
      Objects.requireNonNull(componentId, "componentId");
      Objects.requireNonNull(party, "party");
      Objects.requireNonNull(category, "category");
      explanations = List.copyOf(explanations);
    }
  }

  /**
   * The intermediate values of a progressive component.
   *
   * @param annualBase the monthly base times the periods per year (exact)
   * @param periodsPerYear 1 or 12
   * @param slices the slices of the annual base that fall in a bracket (positive width only)
   * @param numerator {@code N}, the sum of slice width times rate in basis points; the exact
   *     monthly tax is {@code N / (10000 * periodsPerYear)}
   * @param capApplied whether the cap was smaller than the exact tax and was used
   */
  public record Trace(
      BigInteger annualBase,
      int periodsPerYear,
      List<Slice> slices,
      BigInteger numerator,
      boolean capApplied) {

    /** Copies the list. */
    public Trace {
      Objects.requireNonNull(annualBase, "annualBase");
      Objects.requireNonNull(numerator, "numerator");
      slices = List.copyOf(slices);
    }
  }

  /**
   * One slice of the annual base.
   *
   * @param from the exclusive lower bound (the previous bracket's bound, 0 for the first)
   * @param to the inclusive upper bound of the bracket, or {@code null} for the open bracket
   * @param rateBp the bracket rate in basis points
   * @param amount the width of the slice in whole francs (annual)
   */
  public record Slice(long from, Long to, int rateBp, BigInteger amount) {

    /** Requires the amount. */
    public Slice {
      Objects.requireNonNull(amount, "amount");
    }
  }
}

package com.divalhr.core.payroll.domain;

import com.divalhr.core.payroll.domain.StatutoryRules.Base;
import com.divalhr.core.payroll.domain.StatutoryRules.Bracket;
import com.divalhr.core.payroll.domain.StatutoryRules.Category;
import com.divalhr.core.payroll.domain.StatutoryRules.Party;
import com.divalhr.core.payroll.domain.StatutoryRules.Progressive;
import com.divalhr.core.payroll.domain.StatutoryRules.Rate;
import com.divalhr.core.payroll.domain.StatutoryRules.Rounding;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The pure statutory calculation of one employee line (MVP-094B, ADR 0034 section 3 and 6). It
 * interprets the data of a {@link StatutoryRules} pack with exact {@link BigInteger} arithmetic:
 * every rate, threshold, period count, cap, base, order and rounding comes from the rules, none is
 * written here (the only constant is the basis-point unit of {@link StatutoryRules#BASIS_POINTS}).
 * No floating point, no clock, no I/O, no expression language.
 *
 * <p>For a monthly base {@code B}: a {@code RATE} component is {@code round(B * rateBp / 10000)}; a
 * {@code PROGRESSIVE} component annualizes ({@code A = B * periodsPerYear}), sums the slices of
 * {@code A} in each bracket times its rate ({@code N}), and is {@code round(N / (10000 *
 * periodsPerYear))}, or, when the pack has a cap and {@code N > B * capBp * periodsPerYear}, the
 * rounded cap {@code round(B * capBp / 10000)}. Rounding happens once, at the end.
 */
public final class StatutoryEngine {

  private static final BigInteger UNIT = BigInteger.valueOf(StatutoryRules.BASIS_POINTS);

  private StatutoryEngine() {}

  /**
   * Calculates the statutory preview of one line.
   *
   * <p>Components are evaluated in rule order. A component whose required flag is false has amount
   * 0 and the explanation {@code NOT_SUBJECT}. When no component applies the line is {@code
   * excluded}: no components, no deductions, net = gross. When the employee deductions exceed the
   * gross the line carries the blocker {@code DEDUCTIONS_EXCEED_GROSS}.
   *
   * @param rules the validated rules of the pack
   * @param cnssSubject whether the employee is subject to CNSS
   * @param irppSubject whether the employee is subject to the IRPP salary withholding
   * @param grossMinor the contractual gross in whole francs, 0 or more (any {@code long} is exact)
   * @return the line
   * @throws IllegalArgumentException when the gross is negative
   * @throws ArithmeticException when a total (deductions, contributions or employer cost) does not
   *     fit a {@code long}; it cannot happen for the gross values the payroll database admits
   */
  public static StatutoryLine calculate(
      StatutoryRules rules, boolean cnssSubject, boolean irppSubject, long grossMinor) {
    Objects.requireNonNull(rules, "rules");
    if (grossMinor < 0) {
      throw new IllegalArgumentException("the gross is never negative");
    }
    BigInteger gross = BigInteger.valueOf(grossMinor);
    BigInteger employeeSocial = BigInteger.ZERO;
    boolean anyApplies = false;
    List<StatutoryLine.Component> components = new ArrayList<>();
    for (StatutoryRules.Component rule : rules.components()) {
      BigInteger base =
          rule.base() == Base.GROSS ? gross : gross.subtract(employeeSocial).max(BigInteger.ZERO);
      StatutoryExplanation baseCode =
          rule.base() == Base.GROSS
              ? StatutoryExplanation.BASE_GROSS
              : StatutoryExplanation.BASE_GROSS_LESS_EMPLOYEE_SOCIAL;
      boolean applies =
          switch (rule.requires()) {
            case CNSS_SUBJECT -> cnssSubject;
            case IRPP_SUBJECT -> irppSubject;
          };
      StatutoryLine.Component component;
      if (applies) {
        anyApplies = true;
        component = apply(rule, base, baseCode);
      } else {
        component =
            new StatutoryLine.Component(
                rule.id(),
                rule.party(),
                rule.category(),
                base.longValueExact(),
                0L,
                List.of(StatutoryExplanation.NOT_SUBJECT, baseCode),
                null);
      }
      if (rule.party() == Party.EMPLOYEE && rule.category() == Category.SOCIAL_CONTRIBUTION) {
        employeeSocial = employeeSocial.add(BigInteger.valueOf(component.amountMinor()));
      }
      components.add(component);
    }
    if (!anyApplies) {
      return new StatutoryLine(
          grossMinor, List.of(), 0L, 0L, grossMinor, grossMinor, true, List.of());
    }
    long deductions = 0;
    long contributions = 0;
    for (StatutoryLine.Component component : components) {
      if (component.party() == Party.EMPLOYEE) {
        deductions = Math.addExact(deductions, component.amountMinor());
      } else {
        contributions = Math.addExact(contributions, component.amountMinor());
      }
    }
    List<StatutoryLineBlocker> blockers =
        deductions > grossMinor ? List.of(StatutoryLineBlocker.DEDUCTIONS_EXCEED_GROSS) : List.of();
    return new StatutoryLine(
        grossMinor,
        components,
        deductions,
        contributions,
        Math.subtractExact(grossMinor, deductions),
        Math.addExact(grossMinor, contributions),
        false,
        blockers);
  }

  private static StatutoryLine.Component apply(
      StatutoryRules.Component rule, BigInteger base, StatutoryExplanation baseCode) {
    List<StatutoryExplanation> explanations = new ArrayList<>();
    BigInteger amount;
    StatutoryLine.Trace trace = null;
    if (rule.method() instanceof Rate rate) {
      amount = round(base.multiply(BigInteger.valueOf(rate.rateBp())), UNIT, rule.rounding());
      explanations.add(StatutoryExplanation.RATE_APPLIED);
    } else if (rule.method() instanceof Progressive progressive) {
      BigInteger periods = BigInteger.valueOf(progressive.periodsPerYear());
      BigInteger annual = base.multiply(periods);
      List<StatutoryLine.Slice> slices = new ArrayList<>();
      BigInteger numerator = BigInteger.ZERO;
      long lower = 0;
      for (Bracket bracket : progressive.brackets()) {
        BigInteger upper =
            bracket.upToAnnual() == null
                ? annual
                : annual.min(BigInteger.valueOf(bracket.upToAnnual()));
        BigInteger width = upper.subtract(BigInteger.valueOf(lower)).max(BigInteger.ZERO);
        if (width.signum() > 0) {
          slices.add(new StatutoryLine.Slice(lower, bracket.upToAnnual(), bracket.rateBp(), width));
          numerator = numerator.add(width.multiply(BigInteger.valueOf(bracket.rateBp())));
        }
        if (bracket.upToAnnual() != null) {
          lower = bracket.upToAnnual();
        }
      }
      boolean capApplied = false;
      if (progressive.capBp() != null) {
        BigInteger capBp = BigInteger.valueOf(progressive.capBp());
        capApplied = numerator.compareTo(base.multiply(capBp).multiply(periods)) > 0;
      }
      if (capApplied) {
        BigInteger capBp = BigInteger.valueOf(progressive.capBp());
        amount = round(base.multiply(capBp), UNIT, rule.rounding());
      } else {
        amount = round(numerator, UNIT.multiply(periods), rule.rounding());
      }
      explanations.add(StatutoryExplanation.PROGRESSIVE_APPLIED);
      if (capApplied) {
        explanations.add(StatutoryExplanation.CAP_APPLIED);
      }
      trace =
          new StatutoryLine.Trace(
              annual, progressive.periodsPerYear(), slices, numerator, capApplied);
    } else {
      throw new IllegalStateException("unknown method");
    }
    explanations.add(baseCode);
    return new StatutoryLine.Component(
        rule.id(),
        rule.party(),
        rule.category(),
        base.longValueExact(),
        amount.longValueExact(),
        explanations,
        trace);
  }

  /**
   * Rounds the non-negative quotient {@code n / d} to a whole number, once.
   *
   * @param n the numerator, 0 or more
   * @param d the denominator, positive
   * @param rounding the mode
   * @return {@code HALF_UP}: {@code floor((2n + d) / 2d)}; {@code DOWN}: {@code floor(n / d)};
   *     {@code UP}: {@code ceil(n / d)}
   */
  static BigInteger round(BigInteger n, BigInteger d, Rounding rounding) {
    return switch (rounding) {
      case HALF_UP -> n.shiftLeft(1).add(d).divide(d.shiftLeft(1));
      case DOWN -> n.divide(d);
      case UP -> n.add(d).subtract(BigInteger.ONE).divide(d);
    };
  }
}

package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * MVP-120A (D120A-4, D120A-5): the deterministic, closed employer-policy rules. Pure and offline:
 * integer arithmetic only (no floating point, no currency conversion), no clock and no storage. The
 * database recomputes the same ceiling and tenure (V52), so a service that disagrees is refused.
 */
public final class SalaryAdvanceRules {

  /** Open requests the schema ever allows. */
  public static final int MAX_OPEN_REQUESTS = 3;

  /** Requests allowed per employee in a rolling twelve months. */
  public static final int ROLLING_LIMIT = 10;

  private SalaryAdvanceRules() {}

  /**
   * The employer-policy ceiling in whole minor units: {@code min(floor(referenceNet * percent /
   * 100), absoluteMax)}. The percentage is a whole percent from 1 to 50, so the product of a
   * non-negative net up to 10^9 and a percentage fits a {@code long}; the quotient is rounded down.
   *
   * @param referenceNetMinor the frozen reference net, in minor units (at least 1)
   * @param maxPercent whole percent, 1 to 50
   * @param absoluteMaxMinor optional absolute maximum, or {@code null}
   * @return the ceiling in minor units
   */
  public static long ceiling(long referenceNetMinor, int maxPercent, Long absoluteMaxMinor) {
    if (referenceNetMinor < 1 || maxPercent < 1 || maxPercent > 50) {
      throw new IllegalArgumentException("outside the policy bounds");
    }
    long byPercent = Math.multiplyExact(referenceNetMinor, (long) maxPercent) / 100L;
    return absoluteMaxMinor == null ? byPercent : Math.min(byPercent, absoluteMaxMinor);
  }

  /**
   * Completed calendar months of employment on a date, with the same value as PostgreSQL's {@code
   * age(date, start)} in whole years times twelve plus months.
   *
   * @param start employment start
   * @param on the request date
   * @return completed months, 0 when the start is not before the date
   */
  public static int tenureMonths(LocalDate start, LocalDate on) {
    if (!start.isBefore(on)) {
      return 0;
    }
    return (int) Period.between(start, on).toTotalMonths();
  }

  /**
   * The amount reason, or empty when the amount fits the bounds.
   *
   * @param requestedMinor requested amount
   * @param minRequestMinor policy minimum
   * @param ceilingMinor employer-policy ceiling
   * @return {@link SalaryAdvanceReason#AMOUNT_OUT_OF_RANGE} when outside, else empty
   */
  public static Optional<SalaryAdvanceReason> amountReason(
      long requestedMinor, long minRequestMinor, long ceilingMinor) {
    return requestedMinor < minRequestMinor || requestedMinor > ceilingMinor || requestedMinor < 1
        ? Optional.of(SalaryAdvanceReason.AMOUNT_OUT_OF_RANGE)
        : Optional.empty();
  }

  /**
   * The facts the closed eligibility reads. Booleans are true when the condition holds.
   *
   * @param policyApplies an enabled, non-cancelled policy is in force
   * @param employmentActive the employee has employment covering the date
   * @param placedInPolicyEntity the placement is in the policy's legal entity
   * @param tenureMonths completed employment months
   * @param minTenureMonths policy minimum
   * @param referencePresent an eligible approved package payslip exists
   * @param currencyMatches policy, package and request share the currency
   * @param openRequests SUBMITTED and EMPLOYER_CONFIRMED requests
   * @param maxOpenRequests policy limit, 1 to 3
   * @param requestsInLastTwelveMonths requests submitted in the rolling twelve months
   * @param waitingUntil first date a new request is allowed again, or {@code null}
   * @param today the organization's business date
   */
  public record Facts(
      boolean policyApplies,
      boolean employmentActive,
      boolean placedInPolicyEntity,
      int tenureMonths,
      int minTenureMonths,
      boolean referencePresent,
      boolean currencyMatches,
      int openRequests,
      int maxOpenRequests,
      int requestsInLastTwelveMonths,
      LocalDate waitingUntil,
      LocalDate today) {}

  /**
   * The closed set of reasons that stop an employee from requesting under the policy, in a stable
   * order. An empty list means preliminarily eligible: the request fits employer policy only.
   *
   * @param facts the facts
   * @return the reasons, in enum order
   */
  public static List<SalaryAdvanceReason> reasons(Facts facts) {
    Set<SalaryAdvanceReason> found = EnumSet.noneOf(SalaryAdvanceReason.class);
    if (!facts.policyApplies()) {
      found.add(SalaryAdvanceReason.NO_ENABLED_POLICY);
    }
    if (!facts.employmentActive()) {
      found.add(SalaryAdvanceReason.EMPLOYMENT_NOT_ACTIVE);
    } else if (facts.policyApplies() && !facts.placedInPolicyEntity()) {
      found.add(SalaryAdvanceReason.LEGAL_ENTITY_MISMATCH);
    }
    if (facts.policyApplies()
        && facts.employmentActive()
        && facts.tenureMonths() < facts.minTenureMonths()) {
      found.add(SalaryAdvanceReason.TENURE_TOO_SHORT);
    }
    if (!facts.referencePresent()) {
      found.add(SalaryAdvanceReason.PAYROLL_REFERENCE_MISSING);
    } else if (!facts.currencyMatches()) {
      found.add(SalaryAdvanceReason.CURRENCY_MISMATCH);
    }
    if (facts.policyApplies()
        && facts.openRequests() >= Math.min(MAX_OPEN_REQUESTS, facts.maxOpenRequests())) {
      found.add(SalaryAdvanceReason.OPEN_LIMIT_REACHED);
    }
    if (facts.waitingUntil() != null && facts.today().isBefore(facts.waitingUntil())) {
      found.add(SalaryAdvanceReason.WAITING_PERIOD);
    }
    if (facts.requestsInLastTwelveMonths() >= ROLLING_LIMIT) {
      found.add(SalaryAdvanceReason.ROLLING_LIMIT_REACHED);
    }
    return new ArrayList<>(found);
  }
}

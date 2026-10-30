package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The effective-dated policy timeline of a legal entity (MVP-094A, D94A-3): the policy in force on
 * a date is the one with the greatest effective date not after it; the end of a policy is derived
 * (the day before the next one). Pure rules, no I/O.
 */
public final class PayrollPolicyTimeline {

  /** At most this many policies per legal entity. */
  public static final int MAX_POLICIES = 60;

  private PayrollPolicyTimeline() {}

  /** Why a policy cannot be appended. */
  public enum Blocker {
    /** The same pay day and method is already in force on its date, or the date is used. */
    NO_CHANGE,
    /** The legal entity already has 60 policies. */
    LIMIT_REACHED
  }

  /**
   * A policy with its derived end.
   *
   * @param policy the policy
   * @param effectiveTo inclusive last day, or {@code null} for the last policy
   */
  public record Entry(PayrollPolicy policy, LocalDate effectiveTo) {}

  /**
   * What appending a policy would do.
   *
   * @param blockers why it cannot be recorded (empty when it can)
   * @param after the timeline after it (equal to the current one when blocked)
   */
  public record Plan(List<Blocker> blockers, List<PayrollPolicy> after) {

    /** Copies the lists. */
    public Plan {
      blockers = List.copyOf(blockers);
      after = List.copyOf(after);
    }

    /**
     * Whether the policy can be recorded.
     *
     * @return true when there is no blocker
     */
    public boolean committable() {
      return blockers.isEmpty();
    }
  }

  /**
   * The policies with their derived ends, newest effective date first.
   *
   * @param policies the policies in any order
   * @return the entries
   */
  public static List<Entry> entries(List<PayrollPolicy> policies) {
    List<PayrollPolicy> ascending = new ArrayList<>(policies);
    ascending.sort(Comparator.comparing(PayrollPolicy::effectiveFrom));
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < ascending.size(); i++) {
      LocalDate end =
          i + 1 < ascending.size() ? ascending.get(i + 1).effectiveFrom().minusDays(1) : null;
      entries.add(new Entry(ascending.get(i), end));
    }
    entries.sort(Comparator.comparing((Entry e) -> e.policy().effectiveFrom()).reversed());
    return entries;
  }

  /**
   * The policy in force on a date.
   *
   * @param policies the policies
   * @param day the date
   * @return the policy with the greatest effective date not after the day, if any
   */
  public static Optional<PayrollPolicy> inForce(List<PayrollPolicy> policies, LocalDate day) {
    PayrollPolicy found = null;
    for (PayrollPolicy policy : policies) {
      if (!policy.effectiveFrom().isAfter(day)
          && (found == null || policy.effectiveFrom().isAfter(found.effectiveFrom()))) {
        found = policy;
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * The version of a legal entity's policies: the last sequence, 0 when none.
   *
   * @param policies the policies
   * @return the version
   */
  public static int version(List<PayrollPolicy> policies) {
    int version = 0;
    for (PayrollPolicy policy : policies) {
      version = Math.max(version, policy.sequence());
    }
    return version;
  }

  /**
   * Evaluates appending a candidate.
   *
   * @param existing the policies now
   * @param candidate the policy to append (its sequence is the next one)
   * @return the plan
   */
  public static Plan plan(List<PayrollPolicy> existing, PayrollPolicy candidate) {
    List<Blocker> blockers = new ArrayList<>();
    if (existing.size() >= MAX_POLICIES) {
      blockers.add(Blocker.LIMIT_REACHED);
    }
    boolean dateUsed =
        existing.stream().anyMatch(p -> p.effectiveFrom().equals(candidate.effectiveFrom()));
    boolean identical =
        inForce(existing, candidate.effectiveFrom()).filter(candidate::sameRule).isPresent();
    if (dateUsed || identical) {
      blockers.add(Blocker.NO_CHANGE);
    }
    List<PayrollPolicy> after = new ArrayList<>(existing);
    if (blockers.isEmpty()) {
      after.add(candidate);
    }
    return new Plan(blockers, after);
  }
}

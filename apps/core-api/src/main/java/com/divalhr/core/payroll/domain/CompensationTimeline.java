package com.divalhr.core.payroll.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The algebra of an employee's active compensation timeline (MVP-093A, D93-5 and D93-7). The
 * timeline is a list of intervals, each with its own value, that never overlap, are gap-free after
 * the first and of which only the last is open-ended. Every operation takes the active facts and
 * returns a {@link Plan}: the active facts it supersedes, the facts it creates and the resulting
 * timeline. Nothing is updated in place: a fact whose end changes is superseded and re-created
 * (keeping its origin change). A rule that refuses the operation is a {@link
 * CompensationRuleException}. Pure rules, no I/O.
 */
public final class CompensationTimeline {

  private CompensationTimeline() {}

  /**
   * An effective-dated value.
   *
   * @param origin the change that introduced the value and start
   * @param money the monthly base amount
   * @param from first day
   * @param to last day (inclusive), or {@code null} for the open-ended last interval
   */
  public record Interval(UUID origin, CompensationMoney money, LocalDate from, LocalDate to) {

    /** Requires the origin, the amount and the first day, and an ordered period. */
    public Interval {
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(money, "money");
      Objects.requireNonNull(from, "from");
      if (to != null && to.isBefore(from)) {
        throw new IllegalArgumentException("an interval ends on or after its first day");
      }
    }

    /**
     * Whether another interval has the same origin, value and period.
     *
     * @param other another interval
     * @return true when identical
     */
    public boolean sameAs(Interval other) {
      return other != null
          && origin.equals(other.origin)
          && money.sameAs(other.money)
          && from.equals(other.from)
          && Objects.equals(to, other.to);
    }
  }

  /**
   * A stored active fact.
   *
   * @param id fact
   * @param createdSeq the change sequence that created it
   * @param interval its value and period
   */
  public record Fact(UUID id, int createdSeq, Interval interval) {

    /** Requires every component. */
    public Fact {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(interval, "interval");
    }
  }

  /**
   * What an operation does.
   *
   * @param superseded the active facts it supersedes
   * @param created the facts it creates, in timeline order
   * @param after the whole active timeline afterwards, in timeline order
   */
  public record Plan(List<Fact> superseded, List<Interval> created, List<Interval> after) {

    /** Copies the lists. */
    public Plan {
      superseded = List.copyOf(superseded);
      created = List.copyOf(created);
      after = List.copyOf(after);
    }

    /**
     * How many intervals of the resulting timeline start after a day.
     *
     * @param today business date
     * @return the number of scheduled (unstarted) intervals
     */
    public int scheduledAfter(LocalDate today) {
      int count = 0;
      for (Interval interval : after) {
        if (interval.from().isAfter(today)) {
          count++;
        }
      }
      return count;
    }
  }

  /**
   * Whether a list of intervals is a legal active timeline: ordered, non-overlapping, gap-free
   * after the first and with only the last interval open-ended.
   *
   * @param intervals intervals in any order
   * @return true when legal (an empty timeline is legal)
   */
  public static boolean legal(List<Interval> intervals) {
    List<Interval> sorted = new ArrayList<>(intervals);
    sorted.sort(Comparator.comparing(Interval::from));
    for (int i = 0; i < sorted.size(); i++) {
      Interval current = sorted.get(i);
      if (i == sorted.size() - 1) {
        if (current.to() != null) {
          return false;
        }
      } else {
        Interval next = sorted.get(i + 1);
        if (current.to() == null || !current.to().plusDays(1).equals(next.from())) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * The first compensation fact: an interval from the date, open-ended.
   *
   * @param active the active facts (must be empty)
   * @param changeId the change being recorded
   * @param money the amount
   * @param from first day
   * @return the plan
   * @throws CompensationRuleException {@code TRANSITION_NOT_ALLOWED} when a fact already exists
   */
  public static Plan establish(
      List<Fact> active, UUID changeId, CompensationMoney money, LocalDate from) {
    if (!active.isEmpty()) {
      throw new CompensationRuleException(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    }
    return finish(active, List.of(), List.of(new Interval(changeId, money, from, null)));
  }

  /**
   * A new effective-dated amount: appended after the last interval, which is closed on the prior
   * day.
   *
   * @param active the active facts
   * @param changeId the change being recorded
   * @param money the new amount
   * @param from first day (strictly after the start of the last interval)
   * @return the plan
   * @throws CompensationRuleException {@code TRANSITION_NOT_ALLOWED} without a fact or when the
   *     date does not follow the last interval's start; {@code NO_CHANGE} for an identical value
   */
  public static Plan change(
      List<Fact> active, UUID changeId, CompensationMoney money, LocalDate from) {
    List<Fact> facts = ordered(active);
    if (facts.isEmpty()) {
      throw new CompensationRuleException(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    }
    Fact last = facts.get(facts.size() - 1);
    Interval latest = last.interval();
    if (!from.isAfter(latest.from())) {
      throw new CompensationRuleException(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    }
    if (latest.money().sameAs(money)) {
      throw new CompensationRuleException(CompensationBlocker.NO_CHANGE);
    }
    List<Interval> created = new ArrayList<>();
    created.add(new Interval(latest.origin(), latest.money(), latest.from(), from.minusDays(1)));
    created.add(new Interval(changeId, money, from, null));
    return finish(facts, List.of(last), created);
  }

  /**
   * The correction of one active fact (addressed by its origin change): amount, currency and/or
   * start. The preceding interval's end is adjusted; the following intervals are untouched.
   *
   * @param active the active facts
   * @param changeId the change being recorded
   * @param target the origin change of the fact to correct
   * @param money the corrected amount
   * @param from the corrected first day
   * @return the plan
   * @throws CompensationRuleException {@code TRANSITION_NOT_ALLOWED} when no active fact has the
   *     origin or the new start would swallow the preceding interval or pass the fact's own end;
   *     {@code NO_CHANGE} for identical values
   */
  public static Plan correct(
      List<Fact> active, UUID changeId, UUID target, CompensationMoney money, LocalDate from) {
    List<Fact> facts = ordered(active);
    int index = indexOf(facts, target);
    if (index < 0) {
      throw new CompensationRuleException(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    }
    Fact fact = facts.get(index);
    Interval old = fact.interval();
    if (old.money().sameAs(money) && old.from().equals(from)) {
      throw new CompensationRuleException(CompensationBlocker.NO_CHANGE);
    }
    if (old.to() != null && from.isAfter(old.to())) {
      throw new CompensationRuleException(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    }
    Fact preceding = index > 0 ? facts.get(index - 1) : null;
    if (preceding != null && !from.isAfter(preceding.interval().from())) {
      throw new CompensationRuleException(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    }
    List<Fact> superseded = new ArrayList<>();
    List<Interval> created = new ArrayList<>();
    if (preceding != null && !preceding.interval().to().equals(from.minusDays(1))) {
      Interval before = preceding.interval();
      superseded.add(preceding);
      created.add(new Interval(before.origin(), before.money(), before.from(), from.minusDays(1)));
    }
    superseded.add(fact);
    created.add(new Interval(changeId, money, from, old.to()));
    return finish(facts, superseded, created);
  }

  /**
   * The cancellation of an unstarted scheduled change: the preceding interval is restored through
   * the cancelled interval's former end.
   *
   * @param active the active facts
   * @param target the origin change of the fact to cancel
   * @param targetIsChange whether that change was a {@code CHANGED} (only those can be cancelled)
   * @param today business date
   * @return the plan
   * @throws CompensationRuleException {@code NOT_CANCELLABLE} unless the active fact exists, was
   *     introduced by a {@code CHANGED}, starts after today and has a preceding interval
   */
  public static Plan cancel(
      List<Fact> active, UUID target, boolean targetIsChange, LocalDate today) {
    List<Fact> facts = ordered(active);
    int index = indexOf(facts, target);
    if (index < 1 || !targetIsChange) {
      throw new CompensationRuleException(CompensationBlocker.NOT_CANCELLABLE);
    }
    Fact fact = facts.get(index);
    if (!fact.interval().from().isAfter(today)) {
      throw new CompensationRuleException(CompensationBlocker.NOT_CANCELLABLE);
    }
    Fact preceding = facts.get(index - 1);
    Interval before = preceding.interval();
    Interval restored =
        new Interval(before.origin(), before.money(), before.from(), fact.interval().to());
    return finish(facts, List.of(preceding, fact), List.of(restored));
  }

  private static List<Fact> ordered(List<Fact> active) {
    List<Fact> facts = new ArrayList<>(active);
    facts.sort(Comparator.comparing(fact -> fact.interval().from()));
    return facts;
  }

  private static int indexOf(List<Fact> facts, UUID origin) {
    for (int i = 0; i < facts.size(); i++) {
      if (facts.get(i).interval().origin().equals(origin)) {
        return i;
      }
    }
    return -1;
  }

  private static Plan finish(List<Fact> active, List<Fact> superseded, List<Interval> created) {
    Set<UUID> removed = new HashSet<>();
    superseded.forEach(fact -> removed.add(fact.id()));
    List<Interval> after = new ArrayList<>();
    for (Fact fact : active) {
      if (!removed.contains(fact.id())) {
        after.add(fact.interval());
      }
    }
    after.addAll(created);
    after.sort(Comparator.comparing(Interval::from));
    if (!legal(after)) {
      throw new IllegalStateException("the planned compensation timeline is not legal");
    }
    List<Interval> orderedCreated = new ArrayList<>(created);
    orderedCreated.sort(Comparator.comparing(Interval::from));
    return new Plan(superseded, orderedCreated, after);
  }
}

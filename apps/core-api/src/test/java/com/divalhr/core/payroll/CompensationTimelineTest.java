package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.CompensationBlocker;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationRuleException;
import com.divalhr.core.payroll.domain.CompensationTimeline;
import com.divalhr.core.payroll.domain.CompensationTimeline.Fact;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.payroll.domain.CompensationTimeline.Plan;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * MVP-093A (D93-5, D93-7): the timeline algebra. Establish, change, correct and cancel produce the
 * exact superseded, created and resulting interval sets, the active timeline stays gap-free with
 * only the last interval open-ended, and a refused operation changes nothing.
 */
class CompensationTimelineTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
  private static final CompensationMoney A = usd(100_000L);
  private static final CompensationMoney B = usd(120_000L);
  private static final CompensationMoney C = usd(150_000L);

  private static CompensationMoney usd(long cents) {
    return CompensationMoney.ofMinor(CompensationCurrency.USD, cents);
  }

  private static LocalDate day(int offset) {
    return TODAY.plusDays(offset);
  }

  private static Interval interval(UUID origin, CompensationMoney money, int from, Integer to) {
    return new Interval(origin, money, day(from), to == null ? null : day(to));
  }

  /** Persists a plan the way the repository does: supersede, then insert the created facts. */
  private static List<Fact> apply(List<Fact> active, Plan plan, int seq) {
    List<Fact> next = new ArrayList<>();
    for (Fact fact : active) {
      boolean removed = plan.superseded().stream().anyMatch(s -> s.id().equals(fact.id()));
      if (!removed) {
        next.add(fact);
      }
    }
    for (Interval created : plan.created()) {
      next.add(new Fact(UUID.randomUUID(), seq, created));
    }
    return next;
  }

  private static List<Interval> intervals(List<Fact> facts) {
    List<Interval> result = new ArrayList<>();
    facts.forEach(fact -> result.add(fact.interval()));
    result.sort((x, y) -> x.from().compareTo(y.from()));
    return result;
  }

  private static List<Fact> started(UUID origin, CompensationMoney money, int from) {
    return apply(List.of(), CompensationTimeline.establish(List.of(), origin, money, day(from)), 1);
  }

  private static Plan change(List<Fact> active, CompensationMoney money, int from) {
    return CompensationTimeline.change(active, UUID.randomUUID(), money, day(from));
  }

  private static Plan correct(List<Fact> active, UUID target, CompensationMoney money, int from) {
    return CompensationTimeline.correct(active, UUID.randomUUID(), target, money, day(from));
  }

  private static CompensationBlocker refusal(Supplier<Plan> operation) {
    try {
      operation.get();
    } catch (CompensationRuleException refused) {
      return refused.blocker();
    }
    throw new AssertionError("the operation was expected to be refused");
  }

  @Test
  void establishCreatesTheOpenEndedFirstIntervalAndOnlyOnce() {
    UUID change = UUID.randomUUID();
    Plan plan = CompensationTimeline.establish(List.of(), change, A, day(-10));
    assertThat(plan.superseded()).isEmpty();
    assertThat(plan.created()).hasSize(1);
    assertThat(plan.created().get(0).sameAs(interval(change, A, -10, null))).isTrue();
    assertThat(plan.after()).hasSize(1);
    assertThat(CompensationTimeline.legal(plan.after())).isTrue();
    List<Fact> active = apply(List.of(), plan, 1);
    UUID again = UUID.randomUUID();
    Supplier<Plan> second = () -> CompensationTimeline.establish(active, again, B, day(0));
    assertThat(refusal(second)).isEqualTo(CompensationBlocker.TRANSITION_NOT_ALLOWED);
  }

  @Test
  void changeClosesTheLastIntervalOnThePriorDayAndKeepsItsOrigin() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    List<Fact> active = started(first, A, -20);
    Plan plan = CompensationTimeline.change(active, second, B, day(30));
    assertThat(plan.superseded()).hasSize(1);
    assertThat(plan.superseded().get(0).interval().origin()).isEqualTo(first);
    assertThat(plan.created()).hasSize(2);
    // The truncated copy keeps the origin and value; the new interval is open-ended.
    assertThat(plan.created().get(0).sameAs(interval(first, A, -20, 29))).isTrue();
    assertThat(plan.created().get(1).sameAs(interval(second, B, 30, null))).isTrue();
    assertThat(plan.after()).hasSize(2);
    assertThat(CompensationTimeline.legal(plan.after())).isTrue();
    assertThat(plan.scheduledAfter(TODAY)).isEqualTo(1);
  }

  @Test
  void changeRefusesAnIdenticalValueAnEarlierDateAndAMissingTimeline() {
    UUID first = UUID.randomUUID();
    List<Fact> active = started(first, A, -20);
    assertThat(refusal(() -> change(active, A, 5))).isEqualTo(CompensationBlocker.NO_CHANGE);
    // The new interval must start strictly after the start of the last one.
    CompensationBlocker notAllowed = CompensationBlocker.TRANSITION_NOT_ALLOWED;
    assertThat(refusal(() -> change(active, B, -20))).isEqualTo(notAllowed);
    assertThat(refusal(() -> change(active, B, -21))).isEqualTo(notAllowed);
    assertThat(refusal(() -> change(List.of(), B, 0))).isEqualTo(notAllowed);
    // One day after the last start is the earliest legal change.
    Plan plan = change(active, B, -19);
    assertThat(plan.created().get(0).to()).isEqualTo(day(-20));
  }

  /** A(-30..-1) B(0..9) C(10..open), recorded by three changes. */
  private static List<Fact> three(UUID a, UUID b, UUID c) {
    List<Fact> active = started(a, A, -30);
    active = apply(active, CompensationTimeline.change(active, b, B, day(0)), 2);
    return apply(active, CompensationTimeline.change(active, c, C, day(10)), 3);
  }

  @Test
  void correctReplacesTheAmountOnlyAndTouchesNothingElse() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Fact> active = three(a, b, c);
    UUID fix = UUID.randomUUID();
    Plan plan = CompensationTimeline.correct(active, fix, b, usd(125_000L), day(0));
    assertThat(plan.superseded()).hasSize(1);
    assertThat(plan.superseded().get(0).interval().origin()).isEqualTo(b);
    assertThat(plan.created()).hasSize(1);
    assertThat(plan.created().get(0).sameAs(interval(fix, usd(125_000L), 0, 9))).isTrue();
    assertThat(plan.after()).hasSize(3);
  }

  @Test
  void correctMovingTheStartEarlierShortensThePrecedingInterval() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Fact> active = three(a, b, c);
    UUID fix = UUID.randomUUID();
    Plan plan = CompensationTimeline.correct(active, fix, b, B, day(-5));
    assertThat(plan.superseded()).hasSize(2);
    assertThat(plan.created()).hasSize(2);
    assertThat(plan.created().get(0).sameAs(interval(a, A, -30, -6))).isTrue();
    assertThat(plan.created().get(1).sameAs(interval(fix, B, -5, 9))).isTrue();
    assertThat(CompensationTimeline.legal(plan.after())).isTrue();
    assertThat(plan.after()).hasSize(3);
  }

  @Test
  void correctMovingTheStartLaterExtendsThePrecedingInterval() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Fact> active = three(a, b, c);
    UUID fix = UUID.randomUUID();
    Plan plan = CompensationTimeline.correct(active, fix, b, B, day(4));
    assertThat(plan.created().get(0).sameAs(interval(a, A, -30, 3))).isTrue();
    assertThat(plan.created().get(1).sameAs(interval(fix, B, 4, 9))).isTrue();
    // The last day of the fact itself is the latest start; one day more is refused.
    Plan edge = correct(active, b, B, 9);
    assertThat(edge.created().get(1).from()).isEqualTo(day(9));
    assertThat(refusal(() -> correct(active, b, B, 10)))
        .isEqualTo(CompensationBlocker.TRANSITION_NOT_ALLOWED);
  }

  @Test
  void correctCannotSwallowThePrecedingIntervalOrChangeNothing() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Fact> active = three(a, b, c);
    CompensationBlocker notAllowed = CompensationBlocker.TRANSITION_NOT_ALLOWED;
    assertThat(refusal(() -> correct(active, b, B, -30))).isEqualTo(notAllowed);
    assertThat(refusal(() -> correct(active, b, B, -31))).isEqualTo(notAllowed);
    assertThat(refusal(() -> correct(active, b, B, 0))).isEqualTo(CompensationBlocker.NO_CHANGE);
    assertThat(refusal(() -> correct(active, UUID.randomUUID(), B, 0))).isEqualTo(notAllowed);
    // One day after the preceding start is the earliest legal start.
    Plan plan = correct(active, b, B, -29);
    assertThat(plan.created().get(0).to()).isEqualTo(day(-30));
  }

  @Test
  void correctOfTheFirstIntervalMayMoveItsStartEitherWayLeavingAGapBeforeIt() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Fact> active = three(a, b, c);
    Plan earlier = correct(active, a, A, -40);
    assertThat(earlier.superseded()).hasSize(1);
    assertThat(earlier.created()).hasSize(1);
    assertThat(earlier.created().get(0).from()).isEqualTo(day(-40));
    assertThat(earlier.created().get(0).to()).isEqualTo(day(-1));
    Plan later = correct(active, a, A, -10);
    assertThat(later.created().get(0).from()).isEqualTo(day(-10));
    assertThat(CompensationTimeline.legal(later.after())).isTrue();
    // The open-ended last interval has no upper bound for its own start.
    Plan last = correct(active, c, C, 300);
    assertThat(last.created().get(1).to()).isNull();
    assertThat(last.created().get(0).to()).isEqualTo(day(299));
  }

  @Test
  void correctMayChangeTheCurrencyWithoutConvertingTheAmount() {
    UUID a = UUID.randomUUID();
    List<Fact> active = started(a, A, -5);
    CompensationMoney francs = CompensationMoney.ofMinor(CompensationCurrency.CDF, 100_000L);
    Plan plan = correct(active, a, francs, -5);
    assertThat(plan.created().get(0).money().currency()).isEqualTo(CompensationCurrency.CDF);
    assertThat(plan.created().get(0).money().minor()).isEqualTo(100_000L);
  }

  @Test
  void cancelRestoresThePrecedingIntervalThroughTheCancelledEnd() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    List<Fact> active = started(a, A, -30);
    active = apply(active, CompensationTimeline.change(active, b, B, day(20)), 2);
    Plan plan = CompensationTimeline.cancel(active, b, true, TODAY);
    assertThat(plan.superseded()).hasSize(2);
    assertThat(plan.created()).hasSize(1);
    assertThat(plan.created().get(0).sameAs(interval(a, A, -30, null))).isTrue();
    assertThat(plan.after()).hasSize(1);
    assertThat(plan.scheduledAfter(TODAY)).isEqualTo(0);
  }

  @Test
  void cancelInTheMiddleExtendsThePrecedingIntervalThroughTheFormerEnd() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    UUID c = UUID.randomUUID();
    List<Fact> active = started(a, A, -30);
    active = apply(active, CompensationTimeline.change(active, b, B, day(20)), 2);
    active = apply(active, CompensationTimeline.change(active, c, C, day(40)), 3);
    Plan plan = CompensationTimeline.cancel(active, b, true, TODAY);
    assertThat(plan.created()).hasSize(1);
    assertThat(plan.created().get(0).sameAs(interval(a, A, -30, 39))).isTrue();
    assertThat(plan.after()).hasSize(2);
    assertThat(CompensationTimeline.legal(plan.after())).isTrue();
    assertThat(plan.scheduledAfter(TODAY)).isEqualTo(1);
  }

  @Test
  void cancelRefusesStartedFirstUnknownAndNonChangeIntervals() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    List<Fact> active = started(a, A, -30);
    active = apply(active, CompensationTimeline.change(active, b, B, day(0)), 2);
    List<Fact> facts = active;
    // Starting today is started: only a change that starts after today can be cancelled.
    assertThat(refusal(() -> CompensationTimeline.cancel(facts, b, true, day(0))))
        .isEqualTo(CompensationBlocker.NOT_CANCELLABLE);
    assertThat(refusal(() -> CompensationTimeline.cancel(facts, b, true, day(5))))
        .isEqualTo(CompensationBlocker.NOT_CANCELLABLE);
    Plan oneDayBefore = CompensationTimeline.cancel(facts, b, true, day(-1));
    assertThat(oneDayBefore.after()).hasSize(1);
    assertThat(refusal(() -> CompensationTimeline.cancel(facts, a, true, day(-40))))
        .isEqualTo(CompensationBlocker.NOT_CANCELLABLE);
    UUID unknown = UUID.randomUUID();
    assertThat(refusal(() -> CompensationTimeline.cancel(facts, unknown, true, day(-40))))
        .isEqualTo(CompensationBlocker.NOT_CANCELLABLE);
    assertThat(refusal(() -> CompensationTimeline.cancel(facts, b, false, day(-40))))
        .isEqualTo(CompensationBlocker.NOT_CANCELLABLE);
  }

  @Test
  void legalDetectsOverlapGapsAndAnOpenIntervalThatIsNotLast() {
    UUID o = UUID.randomUUID();
    assertThat(CompensationTimeline.legal(List.of())).isTrue();
    assertThat(CompensationTimeline.legal(List.of(interval(o, A, 0, null)))).isTrue();
    Interval open = interval(o, B, 10, null);
    assertThat(CompensationTimeline.legal(List.of(interval(o, A, 0, 9), open))).isTrue();
    // Overlap.
    assertThat(CompensationTimeline.legal(List.of(interval(o, A, 0, 10), open))).isFalse();
    // Gap.
    assertThat(CompensationTimeline.legal(List.of(interval(o, A, 0, 8), open))).isFalse();
    // The last interval must be open-ended; an earlier one must not be.
    assertThat(CompensationTimeline.legal(List.of(interval(o, A, 0, 9)))).isFalse();
    assertThat(CompensationTimeline.legal(List.of(interval(o, A, 0, null), open))).isFalse();
  }

  /** Every operation over a small set of dates and amounts keeps the timeline legal. */
  @Test
  void everyReachableTimelineOfASmallEnumerationStaysLegalAndRefusalsChangeNothing() {
    CompensationMoney[] amounts = {A, B, C};
    int[] days = {-12, -9, -6, -3, 0, 3, 6, 9};
    List<List<Fact>> frontier = new ArrayList<>();
    frontier.add(List.of());
    int[] seq = {0};
    int explored = 0;
    for (int depth = 0; depth < 3; depth++) {
      List<List<Fact>> next = new ArrayList<>();
      for (List<Fact> state : frontier) {
        List<Interval> before = intervals(state);
        List<Plan> plans = new ArrayList<>();
        for (CompensationMoney money : amounts) {
          for (int d : days) {
            UUID id = UUID.randomUUID();
            plans.add(attempt(() -> CompensationTimeline.establish(state, id, money, day(d))));
            plans.add(attempt(() -> CompensationTimeline.change(state, id, money, day(d))));
            for (Fact target : state) {
              UUID origin = target.interval().origin();
              plans.add(attempt(() -> correct(state, origin, money, d)));
            }
          }
        }
        for (Fact target : state) {
          UUID origin = target.interval().origin();
          plans.add(attempt(() -> CompensationTimeline.cancel(state, origin, true, day(-1))));
        }
        for (Plan plan : plans) {
          explored++;
          if (plan == null) {
            // A refusal is a pure function: the state is untouched.
            assertThat(intervals(state).toString()).isEqualTo(before.toString());
            continue;
          }
          assertThat(CompensationTimeline.legal(plan.after())).isTrue();
          List<Fact> applied = apply(state, plan, ++seq[0]);
          assertThat(CompensationTimeline.legal(intervals(applied))).isTrue();
          assertThat(intervals(applied).toString()).isEqualTo(plan.after().toString());
          if (depth < 2) {
            next.add(applied);
          }
        }
      }
      frontier = next.size() > 150 ? new ArrayList<>(next.subList(0, 150)) : next;
    }
    assertThat(explored > 1000).isTrue();
  }

  private static Plan attempt(Supplier<Plan> operation) {
    try {
      return operation.get();
    } catch (CompensationRuleException refused) {
      return null;
    }
  }
}

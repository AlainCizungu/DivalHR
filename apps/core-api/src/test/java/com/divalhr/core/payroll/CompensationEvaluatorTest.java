package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.divalhr.core.payroll.domain.CompensationAction;
import com.divalhr.core.payroll.domain.CompensationBlocker;
import com.divalhr.core.payroll.domain.CompensationCurrency;
import com.divalhr.core.payroll.domain.CompensationDates.Span;
import com.divalhr.core.payroll.domain.CompensationEvaluator;
import com.divalhr.core.payroll.domain.CompensationEvaluator.Command;
import com.divalhr.core.payroll.domain.CompensationEvaluator.Context;
import com.divalhr.core.payroll.domain.CompensationEvaluator.Evaluation;
import com.divalhr.core.payroll.domain.CompensationMoney;
import com.divalhr.core.payroll.domain.CompensationTimeline;
import com.divalhr.core.payroll.domain.CompensationTimeline.Fact;
import com.divalhr.core.payroll.domain.CompensationTimeline.Interval;
import com.divalhr.core.payroll.domain.CompensationWarning;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * MVP-093A (D93-5, D93-7, D93-10): the single evaluation behind both the preview and the commit:
 * amount range, exact date boundaries, correction reach, employment containment, transitions,
 * limits, warnings and the acknowledgement rule.
 */
class CompensationEvaluatorTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
  private static final Span EMPLOYED =
      new Span(UUID.randomUUID(), 4, LocalDate.of(2020, 1, 1), null);

  private static CompensationMoney usd(String text) {
    return CompensationMoney.parse(CompensationCurrency.USD, text).money();
  }

  private static CompensationMoney.Parsed parsed(CompensationCurrency c, String text) {
    return CompensationMoney.parse(c, text);
  }

  private static LocalDate day(int offset) {
    return TODAY.plusDays(offset);
  }

  private static Context empty() {
    return new Context(TODAY, List.of(EMPLOYED), List.of(), 0);
  }

  private static Command establish(int from, String amount) {
    return new Command(
        CompensationAction.ESTABLISHED, day(from), parsed(CompensationCurrency.USD, amount), null);
  }

  private static Command change(int from, String amount) {
    return new Command(
        CompensationAction.CHANGED, day(from), parsed(CompensationCurrency.USD, amount), null);
  }

  private static Command correct(UUID target, int from, String amount) {
    return new Command(
        CompensationAction.CORRECTED, day(from), parsed(CompensationCurrency.USD, amount), target);
  }

  private static Fact fact(UUID origin, String amount, int from, Integer to, int seq) {
    return new Fact(
        UUID.randomUUID(),
        seq,
        new Interval(origin, usd(amount), day(from), to == null ? null : day(to)));
  }

  private static Context with(int version, Fact... facts) {
    return new Context(TODAY, List.of(EMPLOYED), List.of(facts), version);
  }

  @Test
  void aFirstRecordIsCommittableAndTodayIsNotRetroactive() {
    Evaluation evaluation =
        CompensationEvaluator.evaluate(establish(0, "1250.00"), empty(), UUID.randomUUID());
    assertThat(evaluation.committable()).isTrue();
    assertThat(evaluation.retroactive()).isFalse();
    assertThat(evaluation.requiresAcknowledgement()).isFalse();
    assertThat(evaluation.warnings()).isEmpty();
    assertThat(evaluation.plan().created()).hasSize(1);
  }

  @Test
  void theDateWindowBoundariesAreExactForACommand() {
    UUID change = UUID.randomUUID();
    assertThat(CompensationEvaluator.evaluate(establish(-60, "10"), empty(), change).blockers())
        .isEmpty();
    assertThat(CompensationEvaluator.evaluate(establish(-61, "10"), empty(), change).blockers())
        .containsExactly(CompensationBlocker.DATE_OUT_OF_RANGE);
    assertThat(CompensationEvaluator.evaluate(establish(366, "10"), empty(), change).blockers())
        .isEmpty();
    assertThat(CompensationEvaluator.evaluate(establish(367, "10"), empty(), change).blockers())
        .containsExactly(CompensationBlocker.DATE_OUT_OF_RANGE);
  }

  @Test
  void aDateBeforeTodayIsRetroactiveNeedsAcknowledgementAndWarns() {
    Evaluation yesterday =
        CompensationEvaluator.evaluate(establish(-1, "10"), empty(), UUID.randomUUID());
    assertThat(yesterday.retroactive()).isTrue();
    assertThat(yesterday.requiresAcknowledgement()).isTrue();
    assertThat(yesterday.warnings()).containsExactly(CompensationWarning.RETROACTIVE);
    Evaluation tomorrow =
        CompensationEvaluator.evaluate(establish(1, "10"), empty(), UUID.randomUUID());
    assertThat(tomorrow.retroactive()).isFalse();
    assertThat(tomorrow.requiresAcknowledgement()).isFalse();
  }

  @Test
  void anAmountOutsideTheRangeIsABlockerNotAnException() {
    Command cdfZero =
        new Command(
            CompensationAction.ESTABLISHED, day(0), parsed(CompensationCurrency.CDF, "0"), null);
    Evaluation evaluation = CompensationEvaluator.evaluate(cdfZero, empty(), UUID.randomUUID());
    assertThat(evaluation.blockers()).containsExactly(CompensationBlocker.AMOUNT_OUT_OF_RANGE);
    assertThat(evaluation.plan()).isNull();
    assertThat(
            CompensationEvaluator.evaluate(establish(0, "100000.01"), empty(), UUID.randomUUID())
                .blockers())
        .containsExactly(CompensationBlocker.AMOUNT_OUT_OF_RANGE);
  }

  @Test
  void aMalformedAmountIsARequestErrorNeverAnEvaluation() {
    try {
      new Command(
          CompensationAction.ESTABLISHED, day(0), parsed(CompensationCurrency.USD, "1e3"), null);
      throw new AssertionError("a malformed amount must not reach the evaluator");
    } catch (IllegalArgumentException refused) {
      assertThat(refused.getMessage()).doesNotContain("1e3");
    }
  }

  @Test
  void aStartOutsideEveryEmploymentIsAnEmploymentConflict() {
    Span employed = new Span(UUID.randomUUID(), 2, day(-30), day(20));
    Context context = new Context(TODAY, List.of(employed), List.of(), 0);
    UUID change = UUID.randomUUID();
    assertThat(CompensationEvaluator.evaluate(establish(-30, "10"), context, change).blockers())
        .isEmpty();
    assertThat(CompensationEvaluator.evaluate(establish(20, "10"), context, change).blockers())
        .isEmpty();
    assertThat(CompensationEvaluator.evaluate(establish(21, "10"), context, change).blockers())
        .containsExactly(CompensationBlocker.EMPLOYMENT_CONFLICT);
    assertThat(CompensationEvaluator.evaluate(establish(-31, "10"), context, change).blockers())
        .containsExactly(CompensationBlocker.EMPLOYMENT_CONFLICT);
  }

  @Test
  void blockersAreReturnedInTheContractsOrder() {
    Span employed = new Span(UUID.randomUUID(), 2, day(-10), null);
    Context context = new Context(TODAY, List.of(employed), List.of(), 100);
    Command command =
        new Command(
            CompensationAction.ESTABLISHED, day(-100), parsed(CompensationCurrency.USD, "0"), null);
    assertThat(CompensationEvaluator.evaluate(command, context, UUID.randomUUID()).blockers())
        .containsExactly(
            CompensationBlocker.AMOUNT_OUT_OF_RANGE,
            CompensationBlocker.DATE_OUT_OF_RANGE,
            CompensationBlocker.EMPLOYMENT_CONFLICT,
            CompensationBlocker.LIMIT_REACHED);
  }

  @Test
  void transitionsFollowTheTimeline() {
    UUID first = UUID.randomUUID();
    Context recorded = with(1, fact(first, "100.00", -20, null, 1));
    UUID change = UUID.randomUUID();
    assertThat(CompensationEvaluator.evaluate(establish(0, "10"), recorded, change).blockers())
        .containsExactly(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    assertThat(CompensationEvaluator.evaluate(change(5, "100.00"), recorded, change).blockers())
        .containsExactly(CompensationBlocker.NO_CHANGE);
    assertThat(CompensationEvaluator.evaluate(change(-20, "200.00"), recorded, change).blockers())
        .containsExactly(CompensationBlocker.TRANSITION_NOT_ALLOWED);
    assertThat(CompensationEvaluator.evaluate(change(5, "200.00"), recorded, change).blockers())
        .isEmpty();
    assertThat(CompensationEvaluator.evaluate(change(5, "200.00"), empty(), change).blockers())
        .containsExactly(CompensationBlocker.TRANSITION_NOT_ALLOWED);
  }

  @Test
  void aCorrectionAlwaysNeedsAcknowledgementAndItsReachIsHeldToTheWindow() {
    UUID old = UUID.randomUUID();
    UUID recent = UUID.randomUUID();
    // The old fact started 61 days ago; the recent one 10 days ago.
    Context context =
        with(2, fact(old, "100.00", -61, -11, 1), fact(recent, "150.00", -10, null, 2));
    UUID change = UUID.randomUUID();
    // The new start is inside the window but the fact being corrected reaches before it.
    Evaluation reach = CompensationEvaluator.evaluate(correct(old, -60, "120.00"), context, change);
    assertThat(reach.blockers()).containsExactly(CompensationBlocker.DATE_OUT_OF_RANGE);
    // A fact that starts exactly 60 days ago is within the reach.
    Context edge = with(2, fact(old, "100.00", -60, -11, 1), fact(recent, "150.00", -10, null, 2));
    Evaluation ok = CompensationEvaluator.evaluate(correct(old, -60, "120.00"), edge, change);
    assertThat(ok.blockers()).isEmpty();
    assertThat(ok.requiresAcknowledgement()).isTrue();
    // Moving the start of a recent fact before the window is refused (the new start is outside).
    Evaluation tooFar =
        CompensationEvaluator.evaluate(correct(recent, -61, "150.00"), edge, change);
    assertThat(tooFar.blockers()).contains(CompensationBlocker.DATE_OUT_OF_RANGE);
    // A correction dated in the future still needs the acknowledgement.
    Evaluation future = CompensationEvaluator.evaluate(correct(recent, 5, "160.00"), edge, change);
    assertThat(future.retroactive()).isFalse();
    assertThat(future.requiresAcknowledgement()).isTrue();
  }

  @Test
  void aLaterScheduledChangeIsWarnedAboutWhenAnotherFactIsCorrected() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Context context =
        with(2, fact(first, "100.00", -20, 9, 1), fact(second, "150.00", 10, null, 2));
    Evaluation evaluation =
        CompensationEvaluator.evaluate(correct(first, -20, "110.00"), context, UUID.randomUUID());
    assertThat(evaluation.blockers()).isEmpty();
    // The corrected start is in the past (retroactive) and a later change is scheduled.
    assertThat(evaluation.warnings())
        .containsExactly(
            CompensationWarning.RETROACTIVE, CompensationWarning.LATER_CHANGE_SCHEDULED);
    // The command's own scheduled interval is not "another" one.
    Evaluation own =
        CompensationEvaluator.evaluate(
            change(10, "150.00"), with(1, fact(first, "100.00", -20, null, 1)), UUID.randomUUID());
    assertThat(own.warnings()).isEmpty();
  }

  @Test
  void theChangeLimitIsOneHundredAndTheScheduledLimitIsTen() {
    UUID first = UUID.randomUUID();
    Context atLimit = with(100, fact(first, "100.00", -20, null, 1));
    UUID id = UUID.randomUUID();
    assertThat(CompensationEvaluator.evaluate(change(5, "200.00"), atLimit, id).blockers())
        .containsExactly(CompensationBlocker.LIMIT_REACHED);
    Context below = with(99, fact(first, "100.00", -20, null, 1));
    assertThat(CompensationEvaluator.evaluate(change(5, "200.00"), below, id).blockers()).isEmpty();
    // Ten scheduled intervals are allowed; the eleventh is not.
    Context nine = scheduled(9);
    assertThat(CompensationTimeline.legal(intervals(nine.active()))).isTrue();
    assertThat(CompensationEvaluator.evaluate(change(10, "300.00"), nine, UUID.randomUUID()).plan())
        .isNotNull();
    Context ten = scheduled(10);
    UUID next = UUID.randomUUID();
    Evaluation eleventh = CompensationEvaluator.evaluate(change(11, "300.00"), ten, next);
    assertThat(eleventh.blockers()).containsExactly(CompensationBlocker.LIMIT_REACHED);
    assertThat(eleventh.plan()).isNull();
  }

  /** The started first interval, then {@code count} one-day scheduled intervals (the last open). */
  private static Context scheduled(int count) {
    List<Fact> facts = new ArrayList<>();
    facts.add(fact(UUID.randomUUID(), "100.00", -20, 0, 1));
    for (int i = 1; i <= count; i++) {
      Integer to = i == count ? null : i;
      facts.add(fact(UUID.randomUUID(), (100 + i) + ".00", i, to, i + 1));
    }
    return new Context(TODAY, List.of(EMPLOYED), facts, count + 1);
  }

  private static List<Interval> intervals(List<Fact> facts) {
    List<Interval> result = new ArrayList<>();
    facts.forEach(fact -> result.add(fact.interval()));
    return result;
  }

  @Test
  void aCancellationNeedsAnUnstartedChangeAndCountsAgainstTheChangeLimit() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    Context context =
        with(2, fact(first, "100.00", -20, 9, 1), fact(second, "150.00", 10, null, 2));
    Evaluation ok = CompensationEvaluator.evaluateCancellation(second, true, context);
    assertThat(ok.committable()).isTrue();
    assertThat(ok.plan().after()).hasSize(1);
    assertThat(CompensationEvaluator.evaluateCancellation(second, false, context).blockers())
        .containsExactly(CompensationBlocker.NOT_CANCELLABLE);
    assertThat(CompensationEvaluator.evaluateCancellation(first, true, context).blockers())
        .containsExactly(CompensationBlocker.NOT_CANCELLABLE);
    Context full = with(100, fact(first, "100.00", -20, 9, 1), fact(second, "150.00", 10, null, 2));
    assertThat(CompensationEvaluator.evaluateCancellation(second, true, full).blockers())
        .containsExactly(CompensationBlocker.LIMIT_REACHED);
  }

  @Test
  void aBlockedEvaluationHasNoPlan() {
    Evaluation blocked =
        CompensationEvaluator.evaluate(establish(-61, "10"), empty(), UUID.randomUUID());
    assertThat(blocked.committable()).isFalse();
    assertThat(blocked.plan()).isNull();
  }
}

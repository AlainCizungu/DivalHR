package com.divalhr.core.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.divalhr.core.payroll.domain.PayrollPayDay;
import com.divalhr.core.payroll.domain.PayrollPolicy;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline.Blocker;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline.Entry;
import com.divalhr.core.payroll.domain.PayrollPolicyTimeline.Plan;
import com.divalhr.core.payroll.domain.PayrollProration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-094A (D94A-3): the effective-dated policy timeline and the appending rules. */
class PayrollPolicyTimelineTest {

  private static LocalDate d(String text) {
    return LocalDate.parse(text);
  }

  private static PayrollPolicy policy(
      int sequence, String from, PayrollPayDay payDay, PayrollProration method) {
    return new PayrollPolicy(UUID.randomUUID(), sequence, d(from), payDay, method);
  }

  private static PayrollPolicy calendar(int sequence, String from) {
    return policy(sequence, from, PayrollPayDay.ofDay(25), PayrollProration.CALENDAR_DAYS);
  }

  @Test
  void thePolicyInForceIsTheGreatestEffectiveDateNotAfterTheDay() {
    PayrollPolicy first = calendar(1, "2026-01-01");
    PayrollPolicy second =
        policy(2, "2026-04-01", PayrollPayDay.lastDay(), PayrollProration.FIXED_30_DAYS);
    List<PayrollPolicy> timeline = List.of(second, first);
    assertThat(PayrollPolicyTimeline.inForce(timeline, d("2025-12-31"))).isEmpty();
    assertThat(PayrollPolicyTimeline.inForce(timeline, d("2026-01-01"))).contains(first);
    assertThat(PayrollPolicyTimeline.inForce(timeline, d("2026-03-31"))).contains(first);
    assertThat(PayrollPolicyTimeline.inForce(timeline, d("2026-04-01"))).contains(second);
    assertThat(PayrollPolicyTimeline.inForce(timeline, d("2030-01-01"))).contains(second);
  }

  @Test
  void theEndOfAPolicyIsDerivedAndTheNewestIsFirst() {
    PayrollPolicy first = calendar(1, "2026-01-01");
    PayrollPolicy second = calendar(2, "2026-04-01");
    PayrollPolicy backdated =
        policy(3, "2025-07-01", PayrollPayDay.ofDay(5), PayrollProration.NONE);
    List<Entry> entries = PayrollPolicyTimeline.entries(List.of(first, second, backdated));
    assertThat(entries.stream().map(entry -> entry.policy().sequence()).toList())
        .containsExactly(2, 1, 3);
    assertThat(entries.get(0).effectiveTo()).isNull();
    assertThat(entries.get(1).effectiveTo()).isEqualTo(d("2026-03-31"));
    assertThat(entries.get(2).effectiveTo()).isEqualTo(d("2025-12-31"));
  }

  @Test
  void theVersionIsTheLastSequence() {
    assertThat(PayrollPolicyTimeline.version(List.of())).isZero();
    assertThat(
            PayrollPolicyTimeline.version(
                List.of(calendar(1, "2026-01-01"), calendar(2, "2026-02-01"))))
        .isEqualTo(2);
  }

  @Test
  void aDifferentPolicyIsAppended() {
    PayrollPolicy first = calendar(1, "2026-01-01");
    PayrollPolicy candidate =
        policy(2, "2026-04-01", PayrollPayDay.lastDay(), PayrollProration.CALENDAR_DAYS);
    Plan plan = PayrollPolicyTimeline.plan(List.of(first), candidate);
    assertThat(plan.committable()).isTrue();
    assertThat(plan.blockers()).isEmpty();
    assertThat(plan.after()).containsExactly(first, candidate);
  }

  @Test
  void aPolicyIdenticalToTheOneInForceOnItsDateChangesNothing() {
    PayrollPolicy first = calendar(1, "2026-01-01");
    PayrollPolicy same = calendar(2, "2026-04-01");
    Plan plan = PayrollPolicyTimeline.plan(List.of(first), same);
    assertThat(plan.blockers()).containsExactly(Blocker.NO_CHANGE);
    assertThat(plan.after()).containsExactly(first);
    // The same rule as the one in force is a change when an earlier date is in force there.
    PayrollPolicy other =
        policy(2, "2026-02-01", PayrollPayDay.ofDay(10), PayrollProration.CALENDAR_DAYS);
    PayrollPolicy back = calendar(3, "2026-03-01");
    assertThat(PayrollPolicyTimeline.plan(List.of(first, other), back).committable()).isTrue();
  }

  @Test
  void anEffectiveDateAlreadyUsedIsRefused() {
    PayrollPolicy first = calendar(1, "2026-01-01");
    PayrollPolicy sameDate =
        policy(2, "2026-01-01", PayrollPayDay.lastDay(), PayrollProration.NONE);
    assertThat(PayrollPolicyTimeline.plan(List.of(first), sameDate).blockers())
        .containsExactly(Blocker.NO_CHANGE);
  }

  @Test
  void atMostSixtyPoliciesPerLegalEntity() {
    List<PayrollPolicy> sixty = new ArrayList<>();
    LocalDate start = d("2022-01-01");
    for (int i = 1; i <= 60; i++) {
      sixty.add(
          policy(
              i,
              start.plusMonths(i).toString(),
              PayrollPayDay.ofDay(i % 28 + 1),
              PayrollProration.CALENDAR_DAYS));
    }
    PayrollPolicy next = policy(61, "2030-01-01", PayrollPayDay.lastDay(), PayrollProration.NONE);
    Plan plan = PayrollPolicyTimeline.plan(sixty, next);
    assertThat(plan.blockers()).contains(Blocker.LIMIT_REACHED);
    assertThat(plan.committable()).isFalse();
    assertThat(plan.after()).hasSize(60);
    assertThat(PayrollPolicyTimeline.plan(sixty.subList(0, 59), next).committable()).isTrue();
  }

  @Test
  void payDaysAreOneToTwentyEightOrTheLastDay() {
    assertThat(PayrollPayDay.ofDay(1).day()).isEqualTo(1);
    assertThat(PayrollPayDay.ofDay(28).day()).isEqualTo(28);
    assertThat(PayrollPayDay.lastDay().day()).isNull();
    assertThatThrownBy(() -> PayrollPayDay.ofDay(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PayrollPayDay.ofDay(29)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PayrollPayDay(PayrollPayDay.Kind.LAST_DAY, 5))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

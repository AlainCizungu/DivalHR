package com.divalhr.core.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * MVP-013 (D6, A13-2): the denial-audit budget bounds writes per actor and per instance in fixed
 * one-minute windows, keeps no state across windows, and suppresses rather than fails open.
 */
class DenialAuditBudgetTest {

  /** A clock the test moves. */
  private static final class MovingClock extends Clock {
    private Instant now;

    MovingClock(String start) {
      this.now = Instant.parse(start);
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static DenialAuditProperties props(int perActor, int perInstance, int tracked) {
    return new DenialAuditProperties(perActor, perInstance, tracked, null, null, null);
  }

  @Test
  void eachActorHasItsOwnBudgetPerWindow() {
    DenialAuditBudget budget =
        new DenialAuditBudget(props(2, 100, 100), new MovingClock("2026-10-03T10:00:00Z"));
    assertThat(budget.tryAcquire("actor-a")).isTrue();
    assertThat(budget.tryAcquire("actor-a")).isTrue();
    assertThat(budget.tryAcquire("actor-a")).isFalse();
    assertThat(budget.tryAcquire("actor-a")).isFalse();
    assertThat(budget.tryAcquire("actor-b")).isTrue();
  }

  @Test
  void theInstanceCeilingSuppressesEveryActor() {
    DenialAuditBudget budget =
        new DenialAuditBudget(props(5, 10, 100), new MovingClock("2026-10-03T10:00:00Z"));
    for (int i = 0; i < 10; i++) {
      assertThat(budget.tryAcquire("actor-" + i)).isTrue();
    }
    assertThat(budget.tryAcquire("fresh-actor")).isFalse();
    assertThat(budget.tryAcquire("actor-1")).isFalse();
  }

  @Test
  void windowRolloverStartsFreshAndCarriesNothingOver() {
    MovingClock clock = new MovingClock("2026-10-03T10:00:59Z");
    DenialAuditBudget budget = new DenialAuditBudget(props(1, 10, 100), clock);
    assertThat(budget.tryAcquire("actor")).isTrue();
    assertThat(budget.tryAcquire("actor")).isFalse();
    clock.advance(Duration.ofSeconds(1));
    // New window: the earlier suppression leaves no trace.
    assertThat(budget.tryAcquire("actor")).isTrue();
    assertThat(budget.tryAcquire("actor")).isFalse();
  }

  @Test
  void aFullTrackingTableSuppressesNewActorsButKeepsKnownOnesUntilTheWindowEnds() {
    MovingClock clock = new MovingClock("2026-10-03T10:00:00Z");
    DenialAuditBudget budget = new DenialAuditBudget(props(3, 100_000, 100), clock);
    for (int i = 0; i < 100; i++) {
      assertThat(budget.tryAcquire("actor-" + i)).isTrue();
    }
    assertThat(budget.tryAcquire("newcomer")).isFalse();
    assertThat(budget.tryAcquire("actor-7")).isTrue();
    clock.advance(Duration.ofMinutes(1));
    assertThat(budget.tryAcquire("newcomer")).isTrue();
  }

  @Test
  void concurrentDenialsNeverExceedTheActorBudget() throws Exception {
    DenialAuditBudget budget =
        new DenialAuditBudget(props(7, 1000, 100), new MovingClock("2026-10-03T10:00:00Z"));
    List<Callable<Boolean>> calls = new ArrayList<>();
    for (int i = 0; i < 64; i++) {
      calls.add(() -> budget.tryAcquire("same-actor"));
    }
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      long granted = 0;
      for (Future<Boolean> result : pool.invokeAll(calls)) {
        granted += result.get() ? 1 : 0;
      }
      assertThat(granted).isEqualTo(7);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void propertiesHaveSafeDefaultsAndBounds() {
    DenialAuditProperties defaults = new DenialAuditProperties(null, null, null, null, null, null);
    assertThat(defaults.perActorPerMinute()).isEqualTo(20);
    assertThat(defaults.perInstancePerMinute()).isEqualTo(600);
    assertThat(defaults.maxTrackedActors()).isEqualTo(10_000);
    assertThat(defaults.poolSize()).isEqualTo(2);
    assertThat(defaults.connectionTimeout()).isEqualTo(Duration.ofSeconds(1));
    assertThat(defaults.statementTimeout()).isEqualTo(Duration.ofSeconds(2));
    assertThatThrownBy(() -> new DenialAuditProperties(0, null, null, null, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new DenialAuditProperties(null, null, null, 5, null, null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> new DenialAuditProperties(null, null, null, null, Duration.ofSeconds(30), null))
        .isInstanceOf(IllegalStateException.class);
  }
}

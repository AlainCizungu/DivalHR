package com.divalhr.core.integrations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetryScheduleTest {

  @Test
  void fixedScheduleWithoutJitter() {
    UUID id = UUID.randomUUID();
    assertThat(RetrySchedule.delayAfter(1, id, false)).isEqualTo(Duration.ofMinutes(1));
    assertThat(RetrySchedule.delayAfter(2, id, false)).isEqualTo(Duration.ofMinutes(5));
    assertThat(RetrySchedule.delayAfter(3, id, false)).isEqualTo(Duration.ofMinutes(30));
    assertThat(RetrySchedule.delayAfter(4, id, false)).isEqualTo(Duration.ofHours(2));
    assertThat(RetrySchedule.delayAfter(5, id, false)).isEqualTo(Duration.ofHours(12));
  }

  @Test
  void noAttemptFollowsTheSixth() {
    assertThatThrownBy(() -> RetrySchedule.delayAfter(6, UUID.randomUUID(), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RetrySchedule.delayAfter(0, UUID.randomUUID(), false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jitterIsDeterministicAndWithinTenPercent() {
    UUID id = UUID.fromString("6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a11");
    for (int attempt = 1; attempt < RetrySchedule.MAX_ATTEMPTS; attempt++) {
      Duration base = RetrySchedule.delayAfter(attempt, id, false);
      Duration jittered = RetrySchedule.delayAfter(attempt, id, true);
      assertThat(jittered).isEqualTo(RetrySchedule.delayAfter(attempt, id, true));
      assertThat(jittered.toMillis())
          .isBetween(base.toMillis() * 9 / 10, base.toMillis() * 11 / 10);
    }
  }
}

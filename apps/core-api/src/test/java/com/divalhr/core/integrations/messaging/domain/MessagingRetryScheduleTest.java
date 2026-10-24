package com.divalhr.core.integrations.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** MVP-111 (D111-9): five attempts; 1 minute, 5 minutes, 30 minutes, 2 hours. */
class MessagingRetryScheduleTest {

  @Test
  void fixedScheduleWithoutJitter() {
    UUID id = UUID.randomUUID();
    assertThat(MessagingRetrySchedule.MAX_ATTEMPTS).isEqualTo(5);
    assertThat(MessagingRetrySchedule.delayAfter(1, id, false, null))
        .isEqualTo(Duration.ofMinutes(1));
    assertThat(MessagingRetrySchedule.delayAfter(2, id, false, null))
        .isEqualTo(Duration.ofMinutes(5));
    assertThat(MessagingRetrySchedule.delayAfter(3, id, false, null))
        .isEqualTo(Duration.ofMinutes(30));
    assertThat(MessagingRetrySchedule.delayAfter(4, id, false, null))
        .isEqualTo(Duration.ofHours(2));
  }

  @Test
  void noAttemptFollowsTheFifthOrPrecedesTheFirst() {
    assertThatThrownBy(() -> MessagingRetrySchedule.delayAfter(5, UUID.randomUUID(), false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> MessagingRetrySchedule.delayAfter(0, UUID.randomUUID(), false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jitterIsDeterministicAndWithinTenPercent() {
    UUID id = UUID.fromString("6f1c1f0e-8a8b-4b43-9a3e-0f6f7b0c2a11");
    for (int attempt = 1; attempt < MessagingRetrySchedule.MAX_ATTEMPTS; attempt++) {
      Duration base = MessagingRetrySchedule.delayAfter(attempt, id, false, null);
      Duration jittered = MessagingRetrySchedule.delayAfter(attempt, id, true, null);
      assertThat(jittered).isEqualTo(MessagingRetrySchedule.delayAfter(attempt, id, true, null));
      assertThat(jittered.toMillis())
          .isBetween(base.toMillis() * 9 / 10, base.toMillis() * 11 / 10);
    }
  }

  @Test
  void aProviderRetryAfterCanLengthenButNeverShortenAndIsBoundedToTwoHours() {
    UUID id = UUID.randomUUID();
    assertThat(MessagingRetrySchedule.delayAfter(1, id, false, Duration.ofSeconds(10)))
        .isEqualTo(Duration.ofMinutes(1));
    assertThat(MessagingRetrySchedule.delayAfter(1, id, false, Duration.ofMinutes(20)))
        .isEqualTo(Duration.ofMinutes(20));
    assertThat(MessagingRetrySchedule.delayAfter(1, id, false, Duration.ofDays(3)))
        .isEqualTo(Duration.ofHours(2));
    assertThat(MessagingRetrySchedule.delayAfter(4, id, false, Duration.ofDays(3)))
        .isEqualTo(Duration.ofHours(2));
    assertThat(MessagingRetrySchedule.delayAfter(2, id, false, Duration.ofSeconds(-5)))
        .isEqualTo(Duration.ofMinutes(5));
  }
}

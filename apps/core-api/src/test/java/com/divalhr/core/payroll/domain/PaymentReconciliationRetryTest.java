package com.divalhr.core.payroll.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** MVP-095C (ADR 0038 section 6): the bounded inquiry retry schedule. */
class PaymentReconciliationRetryTest {

  @Test
  void delaysFollowTheApprovedScheduleAndStopAfterSixAttempts() {
    assertThat(PaymentReconciliationRetry.MAX_ATTEMPTS).isEqualTo(6);
    assertThat(List.of(1, 2, 3, 4, 5).stream().map(PaymentReconciliationRetry::delayAfter).toList())
        .containsExactly(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofHours(1),
            Duration.ofHours(6));
  }

  @Test
  void anAttemptOutsideOneToFiveHasNoSuccessor() {
    for (int attempt : new int[] {-1, 0, 6, 7, Integer.MAX_VALUE}) {
      assertThatThrownBy(() -> PaymentReconciliationRetry.delayAfter(attempt))
          .as("attempt " + attempt)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
